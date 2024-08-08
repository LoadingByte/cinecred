package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.ceilDiv
import com.loadingbyte.cinecred.imaging.Bitmap.Alpha.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.*
import com.loadingbyte.cinecred.imaging.Bitmap.Range.FULL
import com.loadingbyte.cinecred.imaging.Bitmap.Range.LIMITED
import com.loadingbyte.cinecred.imaging.BitmapConverter.StageType.*
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.LINEAR
import com.loadingbyte.cinecred.natives.skcms.skcms_Curve
import com.loadingbyte.cinecred.natives.skcms.skcms_ICCProfile
import com.loadingbyte.cinecred.natives.skcms.skcms_h.*
import com.loadingbyte.cinecred.natives.zimg.zimg_graph_builder_params
import com.loadingbyte.cinecred.natives.zimg.zimg_h.*
import com.loadingbyte.cinecred.natives.zimg.zimg_image_buffer
import com.loadingbyte.cinecred.natives.zimg.zimg_image_buffer_const
import com.loadingbyte.cinecred.natives.zimg.zimg_image_format
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.IntVector
import jdk.incubator.vector.Vector
import jdk.incubator.vector.VectorOperators.*
import jdk.incubator.vector.VectorShape
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.javacpp.DoublePointer
import java.lang.Byte.toUnsignedInt
import java.lang.Float.float16ToFloat
import java.lang.Float.floatToFloat16
import java.lang.Short.toUnsignedInt
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemoryLayout.PathElement.sequenceElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import jdk.incubator.vector.ByteVector.SPECIES_PREFERRED as B
import jdk.incubator.vector.ByteVector.fromMemorySegment as vec
import jdk.incubator.vector.FloatVector.SPECIES_PREFERRED as F
import jdk.incubator.vector.FloatVector.fromMemorySegment as vec
import jdk.incubator.vector.IntVector.SPECIES_PREFERRED as I
import jdk.incubator.vector.ShortVector.SPECIES_PREFERRED as S
import jdk.incubator.vector.ShortVector.fromMemorySegment as vec


/**
 * @param srcAligned Assumes that the source [Bitmap.isAligned]. This admits certain optimizations.
 * @param dstAligned Assumes that the destination [Bitmap.isAligned]. This admits certain optimizations.
 * @param promiseOpaque Assumes that the source alpha channel is 1 everywhere. This admits certain optimizations.
 * @param approxTransfer Use faster, but less precise transfer characteristics conversion.
 * @param nearestNeighbor Use very fast nearest neighbor scaling.
 */
class BitmapConverter(
    private val srcSpec: Bitmap.Spec,
    private val dstSpec: Bitmap.Spec,
    private val srcAligned: Boolean = true,
    private val dstAligned: Boolean = true,
    promiseOpaque: Boolean = false,
    approxTransfer: Boolean = false,
    nearestNeighbor: Boolean = false
) : AutoCloseable {

    private val stages = mutableListOf<Stage>()
    private val cleanable = CLEANER.register(this, CleanerAction(stages))

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    // Notice that the intermediate bitmaps are not cleaned by this action, as they have their own cleaner action.
    private class CleanerAction(private val stages: List<Stage>) : Runnable {
        override fun run() {
            stages.forEach(Stage::close)
        }
    }

    private val closureProtector = ClosureProtector()
    private val effSpecs: List<Bitmap.Spec>
    private val intermediates = mutableListOf<Bitmap>()

    init {
        require(
            (srcSpec.representation.pixelFormat.family == GRAY) == (dstSpec.representation.pixelFormat.family == GRAY)
        ) { "Cannot convert between gray and chromatic bitmaps." }
        val srcC = srcSpec.content
        val dstC = dstSpec.content
        require(
            srcC == Bitmap.Content.PROGRESSIVE_FRAME || dstC == Bitmap.Content.PROGRESSIVE_FRAME ||
                    (srcC == Bitmap.Content.ONLY_TOP_FIELD || srcC == Bitmap.Content.ONLY_BOT_FIELD) ==
                    (dstC == Bitmap.Content.ONLY_TOP_FIELD || dstC == Bitmap.Content.ONLY_BOT_FIELD)
        ) { "Cannot convert between a single field and two interleaved fields." }

        // Find a pipeline of stages that converts bitmaps between the src and dst spec.
        val (stageTypes, effSpecs) = Pathfinder(srcSpec, dstSpec, srcAligned, dstAligned, nearestNeighbor).run()

        // If the first stage (un)premultiplies and/or drops the alpha channel, but the operation actually doesn't do
        // anything apart from copying the data, we can remove the stage and let reinterpretation take care of it.
        if (stageTypes.first() == UN_PREMUL_OR_DROP_ALPHA_CHANNEL &&
            UnPremulOrDropAlphaChannelStage.isNoOp(promiseOpaque, effSpecs[0], effSpecs[1])
        ) {
            stageTypes.removeFirst()
            effSpecs.removeFirst()
        }
        // If no more stages remain, we add back a simple blit.
        if (stageTypes.isEmpty()) {
            stageTypes += BLIT
            effSpecs += dstSpec
        }

        this.effSpecs = effSpecs

        // Add stages one by one so if creating one throws an exception,
        // all the previously created ones will be closed by the cleaner.
        for ((i, stageType) in stageTypes.withIndex())
            stages += when (stageType) {
                BLIT -> BlitStage
                PLANAR_FLOAT -> PlanarFloatStage
                ADD_ALPHA_CHANNEL -> AddAlphaChannelStage
                UN_PREMUL_OR_DROP_ALPHA_CHANNEL -> UnPremulOrDropAlphaChannelStage(promiseOpaque)
                LIMITED_X2RGB10BE -> LimitedX2RGB10BEStage
                SWS -> SwsStage(effSpecs[i], effSpecs[i + 1])
                SKCMS -> SkcmsStage(effSpecs[i], effSpecs[i + 1], promiseOpaque)
                ZIMG -> ZimgStage(effSpecs[i], effSpecs[i + 1], promiseOpaque, approxTransfer, nearestNeighbor)
            }

        // Allocate reusable intermediate bitmaps that connect the processors with each other.
        // Re-use the same intermediate bitmap multiple times if a stage supports in-place processing.
        var i = 1
        while (i < stageTypes.size) {
            var j = i
            while (j < stageTypes.size && isInplace(stageTypes[j], effSpecs[j], effSpecs[j + 1]))
                j++
            if (j != i) {
                // If this run of in-place operations encompasses the final destination bitmap, separate two cases:
                //  1. If the final destination has alpha, we want to operate in-place on that, i.e., the destination
                //     will be our intermediate. As such, we don't need to create any more intermediates.
                //  2. If it doesn't have alpha, we can't operate in-place on it. So the last stage will need to
                //     perform a non-in-place operation. As such, we need intermediates before the final destination.
                if (j == stageTypes.size)
                    if (effSpecs.last().representation.pixelFormat.hasAlpha)
                        break
                    else
                        j--
                val backingSpec = effSpecs.subList(i, j + 1).first { it.representation.pixelFormat.hasAlpha }
                val backing = Bitmap.allocate(backingSpec)
                while (i <= j) {
                    val spec = effSpecs[i++]
                    intermediates += if (spec == backingSpec) backing else backing.reinterpretedView(spec)
                }
            } else
                intermediates += Bitmap.allocate(effSpecs[i++])
        }
    }

    private fun isInplace(stageType: StageType, srcSpec: Bitmap.Spec, dstSpec: Bitmap.Spec): Boolean =
        stageType == ADD_ALPHA_CHANNEL || stageType == UN_PREMUL_OR_DROP_ALPHA_CHANNEL ||
                stageType == SKCMS &&
                srcSpec.representation.pixelFormat.code == AV_PIX_FMT_RGBAF32 &&
                dstSpec.representation.pixelFormat.code == AV_PIX_FMT_RGBAF32

    /** Releases the underlying native memory early. After having called this, calls to [convert] methods will throw. */
    override fun close() {
        closureProtector.close()
        cleanable.clean()
        intermediates.forEach(Bitmap::close)
    }

    fun convert(src: Bitmap, dst: Bitmap) {
        require(!src.sharesStorageWith(dst)) { "Source and destination bitmap can't use the same underlying memory." }
        require(src.spec == srcSpec) { "Actual input bitmap's spec doesn't match spec expected by converter." }
        require(dst.spec == dstSpec) { "Actual output bitmap's spec doesn't match spec expected by converter." }
        require(!srcAligned || src.isAligned) { "Input bitmap is not aligned even though that was claimed." }
        require(!dstAligned || dst.isAligned) { "Output bitmap is not aligned even though that was claimed." }
        closureProtector.requireNotClosed {
            src.requireNotClosed {
                dst.requireNotClosed {
                    for ((i, stage) in stages.withIndex())
                        withStageBitmap(i, src, dst) { stageSrc ->
                            withStageBitmap(i + 1, src, dst) { stageDst ->
                                stage.process(stageSrc, stageDst)
                            }
                        }
                }
            }
        }
    }

    private inline fun withStageBitmap(idx: Int, src: Bitmap, dst: Bitmap, block: (Bitmap) -> Unit) {
        var bitmap: Bitmap
        var usingView = false
        if (idx in 1..intermediates.size)
            bitmap = intermediates[idx - 1]
        else {
            // Notice that idx can be smaller than stages.size here, since any amount of trailing stages can operate
            // in-place on the destination bitmap.
            bitmap = if (idx == 0) src else dst
            // If the spec has been forced to be some other one, or if stages operate in-place on the destination
            // bitmap, we need to reinterpret the bitmap's view.
            val effSpec = effSpecs[idx]
            if (bitmap.spec != effSpec) {
                bitmap = bitmap.reinterpretedView(effSpec)
                usingView = true
            }
        }
        try {
            block(bitmap)
        } finally {
            if (usingView)
                bitmap.close()
        }
    }


    companion object {

        /** @see [BitmapConverter] */
        fun convert(
            src: Bitmap,
            dst: Bitmap,
            promiseOpaque: Boolean = false,
            approxTransfer: Boolean = false,
            nearestNeighbor: Boolean = false
        ) {
            BitmapConverter(
                src.spec, dst.spec, src.isAligned, dst.isAligned, promiseOpaque, approxTransfer, nearestNeighbor
            ).use { it.convert(src, dst) }
        }

        // Some useful constants shared across stage implementations:
        private val NBO = ByteOrder.nativeOrder()
        /** The number of bytes in a preferred vector of any type. */
        private val VLEN = B.vectorByteSize()
        private val HALF_VLEN = VLEN / 2
        private val QUART_VLEN = VLEN / 4
        private val EIGHTH_VLEN = VLEN / 8
        private val SIXTEENTH_VLEN = VLEN / 16

    }


    private enum class StageType {
        BLIT, PLANAR_FLOAT, ADD_ALPHA_CHANNEL, UN_PREMUL_OR_DROP_ALPHA_CHANNEL, LIMITED_X2RGB10BE, SWS, SKCMS, ZIMG
    }


    private interface Stage : AutoCloseable {
        fun process(src: Bitmap, dst: Bitmap)
    }


    /* ********************************
       ********** PATHFINDER **********
       ******************************** */

    /**
     * We have a variety of stages that perform certain conversions at our disposal. This class finds the best chain of
     * stages that in the end converts from the source to the destination spec.
     */
    private class Pathfinder(
        private val srcSpec: Bitmap.Spec,
        private val dstSpec: Bitmap.Spec,
        srcAligned: Boolean,
        dstAligned: Boolean,
        private val nearestNeighbor: Boolean
    ) {

        // Choose sets of formats, primaries, transfer characteristics, contents, and resolutions of the src, dst, and
        // potential intermediate bitmaps. Then build lookups from state numbers to the corresponding objects.
        //
        // Note: The pathfinder code expects the formats to be organized in a very particular way. The non-float32 src
        // formats must come first and the non-float32 dst formats must come last. In between, there are intermediate
        // float32 formats.
        // Assuming that both the src, dst, and their zimg-supported equivalents are non-float32, we would end up with
        // the following formats array:
        //   - src actual
        //   - src zimg-supported equivalent
        //   - ... various intermediate float32 formats ...
        //   - dst zimg-supported equivalent
        //   - dst actual
        // In contrast, when those src/dst formats are float32, they are be absorbed into the float32 pool and
        // deduplicated if necessary so that no float32 format occurs more than once.
        private val fmtObjs: Array<Fmt>
        private val priObjs: Array<ColorSpace.Primaries?>
        private val trcObjs: Array<ColorSpace.Transfer>
        // Note: The pathfinder code expects PROGRESSIVE_FRAME (if it's present) to be at the end of the array.
        private val conObjs: Array<Bitmap.Content>
        private val resObjs: Array<Resolution>

        init {
            val srcRep = srcSpec.representation
            val dstRep = dstSpec.representation

            val (srcFullF, srcAny) = setOf(
                Fmt(srcRep.pixelFormat, srcRep.range, srcRep.yuvCoefficients, srcRep.chromaLocation),
                Fmt(ZimgStage.equiv(srcRep.pixelFormat), srcRep.range, srcRep.yuvCoefficients, srcRep.chromaLocation),
            ).partition(Fmt::isFullF)
            val (dstFullF, dstAny) = setOf(
                Fmt(dstRep.pixelFormat, dstRep.range, dstRep.yuvCoefficients, dstRep.chromaLocation),
                Fmt(ZimgStage.equiv(dstRep.pixelFormat), dstRep.range, dstRep.yuvCoefficients, dstRep.chromaLocation),
            ).partition(Fmt::isFullF)
            val fullFPool = HashSet<Fmt>()
            fullFPool += srcFullF
            fullFPool += dstFullF
            fullFPool += Fmt(planarFloatEquiv(srcRep.pixelFormat), FULL, null, AVCHROMA_LOC_UNSPECIFIED)
            fullFPool += Fmt(planarFloatEquiv(dstRep.pixelFormat), FULL, null, AVCHROMA_LOC_UNSPECIFIED)
            fullFPool += Fmt(interleavedFloatEquiv(srcRep.pixelFormat), FULL, null, AVCHROMA_LOC_UNSPECIFIED)
            fullFPool += Fmt(interleavedFloatEquiv(dstRep.pixelFormat), FULL, null, AVCHROMA_LOC_UNSPECIFIED)
            val fmtObjList = ArrayList<Fmt>()
            fmtObjList += srcAny
            fmtObjList += fullFPool
            fmtObjList += dstAny.asReversed()
            fmtObjs = fmtObjList.toTypedArray()

            priObjs = setOf(srcRep.colorSpace?.primaries, dstRep.colorSpace?.primaries).toTypedArray()
            trcObjs = setOf(LINEAR, srcRep.colorSpace?.transfer, dstRep.colorSpace?.transfer)
                .filterNotNull().toTypedArray()
            conObjs = setOf(srcSpec.content, dstSpec.content)
                .sortedBy { if (it == Bitmap.Content.PROGRESSIVE_FRAME) 1 else 0 }.toTypedArray()
            resObjs = setOf(srcSpec.resolution, dstSpec.resolution).toTypedArray()
        }

        // Represent the source and destination specs as state numbers.
        private val srcState = state(srcSpec, srcAligned, false)
        private val dstState = state(dstSpec, dstAligned, true)

        private fun state(spec: Bitmap.Spec, isAligned: Boolean, lastMatchingFmt: Boolean): Int {
            val rep = spec.representation
            val fmtObj = Fmt(rep.pixelFormat, rep.range, rep.yuvCoefficients, rep.chromaLocation)
            return state(
                ali = isAligned,
                fmt = if (!lastMatchingFmt) fmtObjs.indexOf(fmtObj) else fmtObjs.lastIndexOf(fmtObj),
                pri = priObjs.indexOf(rep.colorSpace?.primaries),
                trc = trcObjs.indexOf(rep.colorSpace?.transfer ?: LINEAR),
                pmu = rep.alpha == PREMULTIPLIED,
                con = conObjs.indexOf(spec.content),
                res = resObjs.indexOf(spec.resolution)
            )
        }

        private lateinit var table: IntArray

        /** Call this function once after construction to run the pathfinder. */
        fun run(): Pair<MutableList<StageType>, MutableList<Bitmap.Spec>> {
            if (srcState == dstState)
                return Pair(mutableListOf(BLIT), mutableListOf(srcSpec, dstSpec))
            table = IntArray(2 * 8 * 2 * 4 * 2 * 2 * 2)
            if (!fillTable())
                throw IllegalArgumentException("Cannot find conversion pipeline between $srcSpec and $dstSpec.")
            return backtrackThroughTable()
        }

        private fun fillTable(): Boolean {
            val firstFullFFmt = fmtObjs.indexOfFirst(Fmt::isFullF)
            val fullGBRPFFmt = fmtObjs.indexOfFirst(Fmt::isFullGBRPF)
            val fullGBRAPFFmt = fmtObjs.indexOfFirst(Fmt::isFullGBRAPF)

            val fromStates = IntList(table.size /* large enough */)
            val newStates = IntList(fromStates.capacity)

            table[srcState] = -1
            fromStates += srcState

            for (i in 0..<16 /* infinite loop protection */) {
                if (fromStates.size == 0)
                    break

                for (j in 0..<fromStates.size) {
                    val state = fromStates[j]
                    val ali = ali(state)
                    val fmt = fmt(state)
                    val pri = pri(state)
                    val trc = trc(state)
                    val pmu = pmu(state)
                    val con = con(state)
                    val res = res(state)
                    val fmtObj = fmtObjs[fmt]
                    val priObj = priObjs[pri]
                    val trcObj = trcObjs[trc]

                    val firstToFmt = if (fmtObj.isFullF) firstFullFFmt else fmt + 1

                    fun link(
                        type: StageType, ali: Boolean, fmt: Int, pri: Int, trc: Int, pmu: Boolean, con: Int, res: Int
                    ) {
                        val toState = state(ali, fmt, pri, trc, pmu, con, res)
                        // Skip the link if toState has already been populated.
                        if (table[toState] == 0) {
                            table[toState] = (type.ordinal shl 10) or state
                            newStates += toState
                        }
                    }

                    // Note: The order in which we look at stages is important. For example, by first looking at the
                    // drop alpha stage, it gets the first chance to both populate new states and also add its new
                    // states to the next iteration's "next" list.

                    // (Un)premultiply or drop alpha channel stage
                    if (ali && fmtObj.isFullGBRAPF) {
                        if (fullGBRPFFmt != -1) {
                            link(UN_PREMUL_OR_DROP_ALPHA_CHANNEL, true, fullGBRPFFmt, pri, trc, false, con, res)
                            link(UN_PREMUL_OR_DROP_ALPHA_CHANNEL, false, fullGBRPFFmt, pri, trc, false, con, res)
                        }
                        link(UN_PREMUL_OR_DROP_ALPHA_CHANNEL, true, fmt, pri, trc, !pmu, con, res)
                        link(UN_PREMUL_OR_DROP_ALPHA_CHANNEL, false, fmt, pri, trc, !pmu, con, res)
                    }

                    // Blit stage
                    link(BLIT, !ali, fmt, pri, trc, pmu, con, res)

                    // Planar float stage & limited X2RGB10BE stage & SWS stage
                    // Note: We first give our planar float stage a chance, because converting to PF is preferred over
                    // letting SWS convert to the zimg-equivalent non-float pixel format and then passing that to zimg.
                    // In addition, SWS is useful to directly convert between interleaved pixel formats.
                    for (toFmt in firstToFmt..fmtObjs.lastIndex) {
                        val toFmtObj = fmtObjs[toFmt]
                        if (fmtObj.hasSameCompositionAs(toFmtObj) && (
                                    fmtObj.isSupportedByPFAsDirty && toFmtObj.isSupportedByPFAsClean ||
                                            fmtObj.isSupportedByPFAsClean && toFmtObj.isSupportedByPFAsDirty
                                    )
                        ) {
                            link(PLANAR_FLOAT, true, toFmt, pri, trc, pmu, con, res)
                            link(PLANAR_FLOAT, false, toFmt, pri, trc, pmu, con, res)
                        }
                    }
                    if (ali && fmtObj.isFullRGBAF)
                        for (toFmt in firstToFmt..fmtObjs.lastIndex) {
                            val toFmtObj = fmtObjs[toFmt]
                            if (toFmtObj.px.code == AV_PIX_FMT_X2RGB10BE && toFmtObj.range == LIMITED) {
                                link(LIMITED_X2RGB10BE, true, toFmt, pri, trc, pmu, con, res)
                                link(LIMITED_X2RGB10BE, false, toFmt, pri, trc, pmu, con, res)
                            }
                        }
                    for (toFmt in firstToFmt..fmtObjs.lastIndex) {
                        val toFmtObj = fmtObjs[toFmt]
                        if (fmtObj.hasSameCompositionAs(toFmtObj) && (
                                    fmtObj.isSupportedBySwsAsInput && toFmtObj.isSupportedBySwsAsOutput ||
                                            av_pix_fmt_swap_endianness(fmtObj.px.code) == toFmtObj.px.code &&
                                            sws_isSupportedEndiannessConversion(fmtObj.px.code) != 0
                                    )
                        ) {
                            link(SWS, true, toFmt, pri, trc, pmu, con, res)
                            link(SWS, false, toFmt, pri, trc, pmu, con, res)
                        }
                    }

                    // Zimg stage
                    // Note: By virtue of the max(), we disallow zimg to output in the source format,
                    // because that could lead to information loss.
                    if (ali && fmtObj.isSupportedByZimg)
                        for (toFmt in max(firstToFmt, firstFullFFmt)..fmtObjs.lastIndex) {
                            val toFmtObj = fmtObjs[toFmt]
                            if (toFmtObj.isSupportedByZimg &&
                                (fmtObj.px.hasAlpha == toFmtObj.px.hasAlpha || !toFmtObj.px.hasAlpha && !pmu)
                            )
                                for ((toPri, toPriObj) in priObjs.withIndex())
                                    if (pri == toPri || priObj!!.hasCode && toPriObj!!.hasCode)
                                        for (toCon in conObjs.indices)
                                            if (nearestNeighbor && !pmu) {
                                                for ((toTrc, toTrcObj) in trcObjs.withIndex())
                                                    if (pri == toPri && trc == toTrc || trcObj.hasCode && toTrcObj.hasCode)
                                                        for (toRes in resObjs.indices)
                                                            link(ZIMG, true, toFmt, toPri, toTrc, false, toCon, toRes)
                                            } else {
                                                link(ZIMG, true, toFmt, toPri, trc, pmu, toCon, res)
                                                if (!pmu)
                                                    for ((toTrc, toTO) in trcObjs.withIndex())
                                                        if (pri == toPri && trc == toTrc || trcObj.hasCode && toTO.hasCode)
                                                            link(ZIMG, true, toFmt, toPri, toTrc, false, toCon, res)
                                                if (nearestNeighbor || (!toFmtObj.px.hasAlpha || pmu) && trcObj == LINEAR)
                                                    for (toRes in resObjs.indices)
                                                        link(ZIMG, true, toFmt, toPri, trc, pmu, toCon, toRes)
                                            }
                        }

                    // SKCMS stage
                    if (SkcmsStage.supports(fmtObj.px))
                        for (toFmt in firstToFmt..fmtObjs.lastIndex) {
                            val toFmtObj = fmtObjs[toFmt]
                            if (SkcmsStage.supports(toFmtObj.px) && fmtObj.hasSameCompositionAsExAlpha(toFmtObj))
                                for (toPri in priObjs.indices)
                                    for ((toTrc, toTrcObj) in trcObjs.withIndex())
                                        if (pri == toPri && trc == toTrc || trcObj.hasCurve && toTrcObj.hasCurve)
                                            if (toFmtObj.px.hasAlpha) {
                                                link(SKCMS, true, toFmt, toPri, toTrc, pmu, con, res)
                                                link(SKCMS, true, toFmt, toPri, toTrc, !pmu, con, res)
                                                link(SKCMS, false, toFmt, toPri, toTrc, pmu, con, res)
                                                link(SKCMS, false, toFmt, toPri, toTrc, !pmu, con, res)
                                            } else {
                                                link(SKCMS, true, toFmt, toPri, toTrc, false, con, res)
                                                link(SKCMS, false, toFmt, toPri, toTrc, false, con, res)
                                            }
                        }

                    // Add alpha channel stage
                    if (ali && fullGBRAPFFmt != -1 && fmtObj.isFullGBRPF) {
                        link(ADD_ALPHA_CHANNEL, true, fullGBRAPFFmt, pri, trc, false, con, res)
                        link(ADD_ALPHA_CHANNEL, true, fullGBRAPFFmt, pri, trc, true, con, res)
                    }
                }

                fromStates.clearThenDrainFrom(newStates)

                if (table[dstState] != 0)
                    return true
            }

            return false
        }

        private fun backtrackThroughTable(): Pair<MutableList<StageType>, MutableList<Bitmap.Spec>> {
            val stageTypes = mutableListOf<StageType>()
            val specs = mutableListOf(dstSpec)
            var state = dstState
            while (state != srcState) {
                val entry = table[state]
                state = entry and 1023
                stageTypes += StageType.entries[entry shr 10]
                specs += spec(state)
            }
            stageTypes.reverse()
            specs.reverse()
            return Pair(stageTypes, specs)
        }

        private fun spec(state: Int): Bitmap.Spec {
            val fmt = fmtObjs[fmt(state)]
            val cs = priObjs[pri(state)]?.let { ColorSpace.of(it, trcObjs[trc(state)]) }
            val alpha = if (!fmt.px.hasAlpha) OPAQUE else if (pmu(state)) PREMULTIPLIED else STRAIGHT
            val rep = Bitmap.Representation(fmt.px, fmt.range, cs, fmt.coeffs, fmt.loc, alpha)
            return Bitmap.Spec(resObjs[res(state)], rep, srcSpec.scan, srcSpec.content)
        }

        companion object {

            private fun state(ali: Boolean, fmt: Int, pri: Int, trc: Int, pmu: Boolean, con: Int, res: Int): Int {
                var state = (fmt shl 6) or (pri shl 5) or (trc shl 3) or (con shl 1) or res
                if (ali) state = state or 512
                if (pmu) state = state or 4
                return state
            }

            private fun ali(state: Int) = state and 512 != 0
            private fun fmt(state: Int) = (state shr 6) and 7
            private fun pri(state: Int) = (state shr 5) and 1
            private fun trc(state: Int) = (state shr 3) and 3
            private fun pmu(state: Int) = state and 4 != 0
            private fun con(state: Int) = (state shr 1) and 1
            private fun res(state: Int) = state and 1

            private fun interleavedFloatEquiv(pixelFormat: Bitmap.PixelFormat) = Bitmap.PixelFormat.of(
                when (pixelFormat.components.size) {
                    1 -> AV_PIX_FMT_GRAYF32
                    3 -> AV_PIX_FMT_RGBF32
                    4 -> AV_PIX_FMT_RGBAF32
                    else -> throw IllegalArgumentException("Unsupported component count.")
                }
            )

            private fun planarFloatEquiv(pixelFormat: Bitmap.PixelFormat) = Bitmap.PixelFormat.of(
                when (pixelFormat.components.size) {
                    1 -> AV_PIX_FMT_GRAYF32
                    3 -> AV_PIX_FMT_GBRPF32
                    4 -> AV_PIX_FMT_GBRAPF32
                    else -> throw IllegalArgumentException("Unsupported component count.")
                }
            )

        }

        private class IntList(capacity: Int) {

            val capacity: Int get() = array.size
            var size: Int = 0; private set
            private var array = IntArray(capacity)

            operator fun get(idx: Int) =
                array[idx]

            operator fun plusAssign(elem: Int) {
                array[size++] = elem
            }

            fun clearThenDrainFrom(other: IntList) {
                array = other.array.also { other.array = array }
                size = other.size
                other.size = 0
            }

        }

        private data class Fmt(
            val px: Bitmap.PixelFormat, val range: Bitmap.Range, val coeffs: Bitmap.YUVCoefficients?, val loc: Int
        ) {

            val isFullF = range == FULL && px.code.let {
                it == AV_PIX_FMT_GRAYF32 || it == AV_PIX_FMT_RGBF32 || it == AV_PIX_FMT_RGBAF32 ||
                        it == AV_PIX_FMT_GBRPF32 || it == AV_PIX_FMT_GBRAPF32
            }
            val isFullRGBAF = px.code == AV_PIX_FMT_RGBAF32 && range == FULL
            val isFullGBRPF = px.code == AV_PIX_FMT_GBRPF32 && range == FULL
            val isFullGBRAPF = px.code == AV_PIX_FMT_GBRAPF32 && range == FULL
            val isSupportedByPFAsDirty: Boolean
            // Note: We intentionally only consider native-endian PF pixel formats as clean, as it makes no sense
            // to convert from a dirty pixel format to a PF one with an endianness that no other stage supports.
            val isSupportedByPFAsClean = px.code.let {
                it == AV_PIX_FMT_GRAYF32 || it == AV_PIX_FMT_GBRPF32 || it == AV_PIX_FMT_GBRAPF32
            }
            val isSupportedBySwsAsInput = sws_isSupportedInput(px.code) != 0
            val isSupportedBySwsAsOutput = sws_isSupportedOutput(px.code) != 0
            val isSupportedByZimg: Boolean

            init {
                val depth = px.components[0].depth
                val compSize = if (depth <= 8) 1 else if (depth <= 16) 2 else 4
                val numComps = px.components.size
                // Captures whether the pixel formats hide any unexpected surprises.
                val isCanonical = !px.isBitstream &&
                        px.components.all { it.shift == 0 && it.depth == depth } &&
                        if (px.isPlanar)
                            px.components.all { it.step == compSize && it.offset == 0 }
                        else
                            px.components.all {
                                it.plane == 0 && it.offset % compSize == 0 && (
                                        it.step == compSize * numComps && it.offset <= compSize * (numComps - 1) ||
                                                // Special case to support pixel formats like 0RGB.
                                                compSize == 1 && numComps == 3 && it.step == 4 && it.offset <= 3
                                        )
                            }
                isSupportedByPFAsDirty = isCanonical && numComps != 2 && px.family != YUV
                isSupportedByZimg = isCanonical && px.isPlanar && px.byteOrder == ByteOrder.nativeOrder()
            }

            fun hasSameCompositionAsExAlpha(other: Fmt): Boolean =
                px.family == other.px.family &&
                        range == other.range &&
                        coeffs == other.coeffs &&
                        loc == other.loc &&
                        (range == FULL || px.isFloat == other.px.isFloat)

            fun hasSameCompositionAs(other: Fmt): Boolean =
                hasSameCompositionAsExAlpha(other) && px.hasAlpha == other.px.hasAlpha

        }

    }


    /* **************************************************
       ********** SIMPLE STAGE IMPLEMENTATIONS **********
       ************************************************** */


    private object BlitStage : Stage {

        override fun process(src: Bitmap, dst: Bitmap) {
            dst.blit(src)
        }

        override fun close() {}

    }


    /** This stage adds an alpha channel to a properly aligned planar float32 bitmap in the native endianness. */
    private object AddAlphaChannelStage : Stage {

        override fun process(src: Bitmap, dst: Bitmap) {
            val (w, h) = src.spec.resolution
            if (!src.sharesStorageWith(dst))
                dst.reinterpretedView(src.spec).use { it.blit(src) }
            // Fill the alpha plane with ones.
            val dstSegA = dst.memorySegment(dst.spec.representation.pixelFormat.components.last().plane)
            val dstLs = dst.linesize(0)
            val stepsPerLine = ceilDiv(w, QUART_VLEN)
            val oneVec = FloatVector.broadcast(F, 1f)
            for (y in 0L..<h.toLong()) {
                var d = y * dstLs
                for (i in 0..<stepsPerLine) {
                    oneVec.intoMemorySegment(dstSegA, d, NBO)
                    d += VLEN
                }
            }
        }

        override fun close() {}

    }


    /**
     * This stage drops the alpha channel of an (unaligned) planar bitmap in the native endianness, and/or
     * (un)premultiplies properly aligned planar float32 RGBA bitmaps in the native endianness.
     */
    private class UnPremulOrDropAlphaChannelStage(private val promiseOpaque: Boolean) : Stage {

        override fun process(src: Bitmap, dst: Bitmap) {
            val inplace = src.sharesStorageWith(dst)
            val srcAlpha = src.spec.representation.alpha
            val dstAlpha = dst.spec.representation.alpha
            when {
                !promiseOpaque && srcAlpha == STRAIGHT && dstAlpha == PREMULTIPLIED ->
                    if (inplace) premultiplyDontPutAlpha(src, dst) else premultiply(src, dst)
                !promiseOpaque && srcAlpha == PREMULTIPLIED && dstAlpha == STRAIGHT ->
                    if (inplace) unpremultiplyDontPutAlpha(src, dst) else unpremultiply(src, dst)
                !promiseOpaque && srcAlpha == PREMULTIPLIED && dstAlpha == OPAQUE ->
                    unpremultiplyDontPutAlpha(src, dst)
                !src.sharesStorageWith(dst) ->
                    src.reinterpretedView(dst.spec).use(dst::blit)
            }
        }

        private fun premultiply(src: Bitmap, dst: Bitmap) = exec(src, dst, mul = true, putAlpha = true)
        private fun premultiplyDontPutAlpha(src: Bitmap, dst: Bitmap) = exec(src, dst, mul = true, putAlpha = false)
        private fun unpremultiply(src: Bitmap, dst: Bitmap) = exec(src, dst, mul = false, putAlpha = true)
        private fun unpremultiplyDontPutAlpha(src: Bitmap, dst: Bitmap) = exec(src, dst, mul = false, putAlpha = false)

        @Suppress("NOTHING_TO_INLINE")
        private inline fun exec(src: Bitmap, dst: Bitmap, mul: Boolean, putAlpha: Boolean) {
            val (srcSegR, srcSegG, srcSegB, srcSegA) =
                src.spec.representation.pixelFormat.components.map { src.memorySegment(it.plane) }
            val (dstSegR, dstSegG, dstSegB) =
                dst.spec.representation.pixelFormat.components.subList(0, 3).map { dst.memorySegment(it.plane) }
            val dstSegA = if (!putAlpha) null else
                dst.memorySegment(dst.spec.representation.pixelFormat.components.last().plane)
            val srcLs = src.linesize(0)
            val dstLs = dst.linesize(0)
            val (w, h) = src.spec.resolution
            val stepsPerLine = ceilDiv(w, QUART_VLEN)
            var zeroVec: FloatVector? = null
            var oneVec: FloatVector? = null
            if (!mul) {
                zeroVec = FloatVector.zero(F)
                oneVec = FloatVector.broadcast(F, 1f)
            }
            for (y in 0L..<h.toLong()) {
                var s = y * srcLs
                var d = y * dstLs
                repeat(stepsPerLine) {
                    val alphaVec = vec(F, srcSegA, s, NBO)
                    if (putAlpha)
                        alphaVec.intoMemorySegment(dstSegA, d, NBO)
                    val facVec = if (mul) alphaVec else oneVec!!.div(alphaVec, alphaVec.compare(NE, zeroVec!!))
                    vec(F, srcSegR, s, NBO).mul(facVec).intoMemorySegment(dstSegR, d, NBO)
                    vec(F, srcSegG, s, NBO).mul(facVec).intoMemorySegment(dstSegG, d, NBO)
                    vec(F, srcSegB, s, NBO).mul(facVec).intoMemorySegment(dstSegB, d, NBO)
                    s += VLEN
                    d += VLEN
                }
            }
        }

        override fun close() {}

        companion object {
            fun isNoOp(promiseOpaque: Boolean, srcSpec: Bitmap.Spec, dstSpec: Bitmap.Spec) =
                promiseOpaque || srcSpec.representation.alpha == STRAIGHT && dstSpec.representation.alpha == OPAQUE
        }

    }


    /**
     * This stage converts aligned full-range interleaved float32 RGBA to potentially unaligned limited-range X2RGB10BE.
     * It is mainly useful for 10-bit DeckLink output, as swscale doesn't support any of the 10-bit RGB pixel formats
     * that are also understood by the DeckLink API.
     */
    private object LimitedX2RGB10BEStage : Stage {

        override fun process(src: Bitmap, dst: Bitmap) {
            val srcSeg = src.memorySegment(0)
            val dstSeg = dst.memorySegment(0)
            val srcLs = src.linesize(0)
            val dstLs = dst.linesize(0)
            val (w, h) = src.spec.resolution
            val stepsPerLine = ceilDiv(w, SIXTEENTH_VLEN)
            val addVec = FloatVector.broadcast(F, 64.5f)
            val facVec = FloatVector.broadcast(F, 876f)
            val shlVec = IntVector.fromArray(I, IntArray(QUART_VLEN) { i -> 30 - 10 * ((i + 1) % 4) }, 0)
            for (y in 0L..<h.toLong()) {
                var s = y * srcLs
                var d = y * dstLs
                repeat(stepsPerLine) {
                    val vecI = vec(F, srcSeg, s, NBO).mul(facVec).min(facVec).add(addVec).max(addVec).convert(F2I, 0)
                        .lanewise(LSHL, shlVec)
                    for (p in 0..<SIXTEENTH_VLEN)
                        dstSeg.putIntBE(d + (p shl 2), (vecI.reinterpretShape(I128, p) as IntVector).reduceLanes(OR))
                    s += VLEN
                    d += QUART_VLEN
                }
            }
        }

        override fun close() {}

        private val I128 = I.withShape(VectorShape.forBitSize(128))

    }


    /** This stage can only convert between pixel formats, and only between ones with the same "composition". */
    private class SwsStage(srcSpec: Bitmap.Spec, dstSpec: Bitmap.Spec) : Stage {

        private val swsCtx = sws_getContext(
            srcSpec.resolution.widthPx, srcSpec.resolution.heightPx, srcSpec.representation.pixelFormat.code,
            dstSpec.resolution.widthPx, dstSpec.resolution.heightPx, dstSpec.representation.pixelFormat.code,
            0, null, null, null as DoublePointer?
        ).ffmpegThrowIfNull("Could not initialize SWS context")

        override fun close() {
            swsCtx.letIfNonNull(::sws_freeContext)
        }

        override fun process(src: Bitmap, dst: Bitmap) {
            val height = src.spec.resolution.heightPx
            sws_scale(swsCtx, src.frame.data(), src.frame.linesize(), 0, height, dst.frame.data(), dst.frame.linesize())
        }

    }


    /**
     * This stage converts between various interleaved pixel formats, no/straight/premultiplied alpha, primaries, and
     * transfer characteristics (parameters only need to be available parameters when src and dst primaries and transfer
     * characteristics actually differ). The conversion always goes through float32, so it's most efficient if either
     * src or dst is a float32 bitmap.
     */
    private class SkcmsStage(srcSpec: Bitmap.Spec, dstSpec: Bitmap.Spec, promiseOpaque: Boolean) : Stage {

        private val srcSkcmsPixelFmt = skcmsPixelFormat(srcSpec.representation.pixelFormat)
        private val dstSkcmsPixelFmt = skcmsPixelFormat(dstSpec.representation.pixelFormat)
        private val srcSkcmsAlphaFmt = skcmsAlphaFormat(promiseOpaque, srcSpec.representation.alpha)
        private val dstSkcmsAlphaFmt = skcmsAlphaFormat(promiseOpaque, dstSpec.representation.alpha)
        private val srcFlip = needsFlip(srcSpec.representation.pixelFormat)
        private val dstFlip = needsFlip(dstSpec.representation.pixelFormat)

        private val arena = Arena.ofShared()
        private var srcProfileSeg: MemorySegment? = null
        private var dstProfileSeg: MemorySegment? = null
        private var bufSeg: MemorySegment? = null

        init {
            setupSafely({
                srcProfileSeg = buildProfile(skcms_ICCProfile.allocate(arena), srcSpec.representation.colorSpace!!)
                dstProfileSeg = buildProfile(skcms_ICCProfile.allocate(arena), dstSpec.representation.colorSpace!!)
                if (srcFlip || dstFlip)
                    bufSeg = arena.allocateArray(JAVA_INT, srcSpec.resolution.widthPx.toLong())
            }, ::close)
        }

        override fun close() {
            arena.close()
        }

        override fun process(src: Bitmap, dst: Bitmap) {
            val (w, h) = src.spec.resolution
            val srcSeg = src.memorySegment(0)
            val dstSeg = dst.memorySegment(0)
            val srcLs = src.linesize(0)
            val dstLs = dst.linesize(0)
            // We have to do the conversion line-by-line because SKCMS expects contiguous buffers.
            for (y in 0L..<h) {
                val s = y * srcLs
                val d = y * dstLs
                if (srcFlip)
                    MemorySegment.copy(srcSeg, JAVA_INT, s, bufSeg, JAVA_INT_FLIPPED_BO, 0L, w.toLong())
                if (!skcms_Transform(
                        if (srcFlip) bufSeg else srcSeg.asSlice(s), srcSkcmsPixelFmt, srcSkcmsAlphaFmt, srcProfileSeg,
                        if (dstFlip) bufSeg else dstSeg.asSlice(d), dstSkcmsPixelFmt, dstSkcmsAlphaFmt, dstProfileSeg,
                        w.toLong()
                    )
                ) throw IllegalStateException("SKCMS transformation failed.")
                if (dstFlip)
                    MemorySegment.copy(bufSeg, JAVA_INT_FLIPPED_BO, 0L, dstSeg, JAVA_INT, d, w.toLong())
            }
        }

        companion object {

            private val JAVA_INT_FLIPPED_BO = JAVA_INT.withOrder(
                if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN
                else ByteOrder.LITTLE_ENDIAN
            )

            fun supports(pixelFormat: Bitmap.PixelFormat) = skcmsPixelFormat(pixelFormat) != -1

            private fun skcmsPixelFormat(pixelFormat: Bitmap.PixelFormat) = when (pixelFormat.code) {
                AV_PIX_FMT_RGB24 -> skcms_PixelFormat_RGB_888()
                AV_PIX_FMT_BGR24 -> skcms_PixelFormat_BGR_888()
                AV_PIX_FMT_RGB0 -> skcms_PixelFormat_RGBA_8888()
                AV_PIX_FMT_RGBA -> skcms_PixelFormat_RGBA_8888()
                AV_PIX_FMT_BGR0 -> skcms_PixelFormat_BGRA_8888()
                AV_PIX_FMT_BGRA -> skcms_PixelFormat_BGRA_8888()
                AV_PIX_FMT_0RGB -> skcms_PixelFormat_BGRA_8888()
                AV_PIX_FMT_ARGB -> skcms_PixelFormat_BGRA_8888()
                AV_PIX_FMT_0BGR -> skcms_PixelFormat_RGBA_8888()
                AV_PIX_FMT_ABGR -> skcms_PixelFormat_RGBA_8888()
                AV_PIX_FMT_RGB48LE -> skcms_PixelFormat_RGB_161616LE()
                AV_PIX_FMT_RGB48BE -> skcms_PixelFormat_RGB_161616BE()
                AV_PIX_FMT_BGR48LE -> skcms_PixelFormat_BGR_161616LE()
                AV_PIX_FMT_BGR48BE -> skcms_PixelFormat_BGR_161616BE()
                AV_PIX_FMT_RGBA64LE -> skcms_PixelFormat_RGBA_16161616LE()
                AV_PIX_FMT_RGBA64BE -> skcms_PixelFormat_RGBA_16161616BE()
                AV_PIX_FMT_BGRA64LE -> skcms_PixelFormat_BGRA_16161616LE()
                AV_PIX_FMT_BGRA64BE -> skcms_PixelFormat_BGRA_16161616BE()
                AV_PIX_FMT_RGBF32 -> skcms_PixelFormat_RGB_fff()
                AV_PIX_FMT_RGBAF32 -> skcms_PixelFormat_RGBA_ffff()
                else -> -1
            }

            private fun skcmsAlphaFormat(promiseOpaque: Boolean, alpha: Bitmap.Alpha) = when {
                alpha == OPAQUE || promiseOpaque -> skcms_AlphaFormat_Opaque()
                alpha == PREMULTIPLIED -> skcms_AlphaFormat_PremulAsEncoded()
                else -> skcms_AlphaFormat_Unpremul()
            }

            private fun needsFlip(pixelFormat: Bitmap.PixelFormat) = pixelFormat.code.let {
                it == AV_PIX_FMT_0RGB || it == AV_PIX_FMT_ARGB || it == AV_PIX_FMT_0BGR || it == AV_PIX_FMT_ABGR
            }

            private fun buildProfile(profileSeg: MemorySegment, colorSpace: ColorSpace): MemorySegment {
                profileSeg.fill(0)
                skcms_ICCProfile.`data_color_space$set`(profileSeg, skcms_Signature_RGB())
                skcms_ICCProfile.`pcs$set`(profileSeg, skcms_Signature_XYZ())
                skcms_ICCProfile.`has_toXYZD50$set`(profileSeg, true)

                val matrixArr = colorSpace.primaries.toXYZD50.values
                MemorySegment.copy(matrixArr, 0, skcms_ICCProfile.`toXYZD50$slice`(profileSeg), JAVA_FLOAT, 0L, 9)
                skcms_ICCProfile.`has_trc$set`(profileSeg, true)

                val curveArr = (if (colorSpace.transfer.hasCurve) colorSpace.transfer else LINEAR).toLinear.asArray()
                val trcSeg = skcms_ICCProfile.`trc$slice`(profileSeg)
                for (c in 0..<3) {
                    val parametricSeg = skcms_Curve.`parametric$slice`(trcSeg.asSlice(c * skcms_Curve.sizeof()))
                    MemorySegment.copy(curveArr, 0, parametricSeg, JAVA_FLOAT, 0L, 7)
                }

                return profileSeg
            }

        }

    }


    /* *******************************************************
       ********** PLANAR FLOAT STAGE IMPLEMENTATION **********
       ******************************************************* */

    /**
     * This stage may only be used to convert to and from a planar float32 pixel format. For both the src and dst
     * bitmaps, it neither expects native endianness nor proper alignment because the behavior of VideoReader bitmaps
     * in these regards cannot be controlled.
     *
     * Be aware that this class has been extensively profiled, so whenever you make any change, run performance tests
     * again to ensure you didn't make it worse!
     */
    private object PlanarFloatStage : Stage {

        private enum class ElemCat { BYTE, SHORT, HALF, FLOAT }

        override fun process(src: Bitmap, dst: Bitmap) {
            val srcPixFmt = src.spec.representation.pixelFormat
            val dstPixFmt = dst.spec.representation.pixelFormat
            val dstIsPF = dstPixFmt.isPlanar && dstPixFmt.isFloat && dstPixFmt.depth == 32

            val dirtyPixFmt = if (dstIsPF) srcPixFmt else dstPixFmt
            val dirtyDepth = dirtyPixFmt.depth
            val dirtyComps = dirtyPixFmt.components.size
            val dirtyElemCat = when (dirtyPixFmt.isFloat) {
                false -> if (dirtyDepth <= 8) ElemCat.BYTE else ElemCat.SHORT
                true -> if (dirtyDepth <= 16) ElemCat.HALF else ElemCat.FLOAT
            }
            val max = (1 shl dirtyDepth) - 1

            // To support interleaved pixel formats with dead space (like 0RGB), we need to know how many bytes each
            // pixel occupies (step, e.g. 4) and which byte contains the first actual color (offset, e.g. 1).
            val iStp = dirtyPixFmt.stepOfPlane(0)
            val iOf = dirtyPixFmt.components.minOf { it.offset }

            if (dstIsPF) {
                val fac = 1f / max
                if (dirtyPixFmt.isPlanar)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> convPBToPF(src, dst, fac)
                        ElemCat.SHORT -> convPSToPF(src, dst, fac)
                        ElemCat.HALF -> convPHToPF(src, dst)
                        ElemCat.FLOAT -> convPFToPF(src, dst)
                    }
                else if (dirtyComps == 3)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> if (iStp == 4) conv3BIn4BToPF(src, dst, fac, iOf) else conv3BToPF(src, dst, fac)
                        ElemCat.SHORT -> conv3SToPF(src, dst, fac)
                        ElemCat.HALF -> conv3HToPF(src, dst)
                        ElemCat.FLOAT -> conv3FToPF(src, dst)
                    }
                else if (dirtyComps == 4)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> conv4BToPF(src, dst, fac)
                        ElemCat.SHORT -> conv4SToPF(src, dst, fac)
                        ElemCat.HALF -> conv4HToPF(src, dst)
                        ElemCat.FLOAT -> conv4FToPF(src, dst)
                    }
                else
                    throw IllegalArgumentException("${dirtyComps}-component interleaved pixel format is not supported.")
            } else {
                val fac = max.toFloat()
                if (dirtyPixFmt.isPlanar)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> if (QUART_B != null) convPFToPB(src, dst, fac) else convPFToPBFix(src, dst, fac)
                        ElemCat.SHORT -> convPFToPS(src, dst, fac)
                        ElemCat.HALF -> convPFToPH(src, dst)
                        ElemCat.FLOAT -> convPFToPF(src, dst)
                    }
                else if (dirtyComps == 3)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> if (iStp == 4) convPFTo3BIn4B(src, dst, fac, iOf) else convPFTo3B(src, dst, fac)
                        ElemCat.SHORT -> convPFTo3S(src, dst, fac)
                        ElemCat.HALF -> convPFTo3H(src, dst)
                        ElemCat.FLOAT -> convPFTo3F(src, dst)
                    }
                else if (dirtyComps == 4)
                    when (dirtyElemCat) {
                        ElemCat.BYTE -> convPFTo4B(src, dst, fac)
                        ElemCat.SHORT -> convPFTo4S(src, dst, fac)
                        ElemCat.HALF -> convPFTo4H(src, dst)
                        ElemCat.FLOAT -> convPFTo4F(src, dst)
                    }
                else
                    throw IllegalArgumentException("${dirtyComps}-component interleaved pixel format is not supported.")
            }
        }

        override fun close() {}

        private fun convPBToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 1, 4, VLEN, 0, { srcSeg, s, dstSeg, d ->
                val srcVec = vec(B, srcSeg, s, NBO)
                srcVec.unsignedB2F(0).mul(f).intoMemorySegment(dstSeg, d, dstBO)
                srcVec.unsignedB2F(1).mul(f).intoMemorySegment(dstSeg, d + 1 * VLEN, dstBO)
                srcVec.unsignedB2F(2).mul(f).intoMemorySegment(dstSeg, d + 2 * VLEN, dstBO)
                srcVec.unsignedB2F(3).mul(f).intoMemorySegment(dstSeg, d + 3 * VLEN, dstBO)
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s)))
            })
        }

        private fun convPSToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 2, 4, HALF_VLEN, 0, { srcSeg, s, dstSeg, d ->
                val srcVec = vec(S, srcSeg, s, srcBO)
                srcVec.unsignedS2F(0).mul(f).intoMemorySegment(dstSeg, d, dstBO)
                srcVec.unsignedS2F(1).mul(f).intoMemorySegment(dstSeg, d + VLEN, dstBO)
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s, srcBO)))
            })
        }

        private fun convPHToPF(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 2, 4, -1, 0, { _, _, _, _ ->
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s, srcBO)))
            })
        }

        private fun convPFToPF(src: Bitmap, dst: Bitmap) {
            val srcLayout = JAVA_FLOAT.withOrder(src.spec.representation.pixelFormat.byteOrder)
            val dstLayout = JAVA_FLOAT.withOrder(dst.spec.representation.pixelFormat.byteOrder)
            val (w, h) = src.spec.resolution
            planewise(src, dst) { srcSeg, srcLs, dstSeg, dstLs ->
                if (srcLs == dstLs)
                    MemorySegment.copy(srcSeg, srcLayout, 0L, dstSeg, dstLayout, 0L, srcLs / 4L * h)
                else
                    for (y in 0L..<h.toLong())
                        MemorySegment.copy(srcSeg, srcLayout, y * srcLs, dstSeg, dstLayout, y * dstLs, w.toLong())
            }
        }

        private fun conv3BToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(src, dst, 3, QUART_VLEN, QUART_VLEN, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                val srcVec = vec(B, srcSeg, s, NBO).rearrange(UNZIP_3B)
                srcVec.unsignedB2F(0).mul(f).intoMemorySegment(dstSeg1, d, dstBO)
                srcVec.unsignedB2F(1).mul(f).intoMemorySegment(dstSeg2, d, dstBO)
                srcVec.unsignedB2F(2).mul(f).intoMemorySegment(dstSeg3, d, dstBO)
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                dstSeg1.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 0L)))
                dstSeg2.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 1L)))
                dstSeg3.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 2L)))
            })
        }

        private fun conv3BIn4BToPF(src: Bitmap, dst: Bitmap, fac: Float, off: Int) {
            val f = FloatVector.broadcast(F, fac)
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(src, dst, 4, QUART_VLEN, 0, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                val srcVec = vec(B, srcSeg, s, NBO).rearrange(UNZIP_4B)
                srcVec.unsignedB2F(off + 0).mul(f).intoMemorySegment(dstSeg1, d, dstBO)
                srcVec.unsignedB2F(off + 1).mul(f).intoMemorySegment(dstSeg2, d, dstBO)
                srcVec.unsignedB2F(off + 2).mul(f).intoMemorySegment(dstSeg3, d, dstBO)
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                dstSeg1.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + off + 0L)))
                dstSeg2.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + off + 1L)))
                dstSeg3.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + off + 2L)))
            })
        }

        private fun conv3SToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val fh = FloatVector.broadcast(HALVED_F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(src, dst, 6, EIGHTH_VLEN, QUART_VLEN, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                val srcVec = vec(S, srcSeg, s, srcBO).rearrange(UNZIP_3S)
                val vecRG = srcVec.unsignedS2F(0).mul(f)
                vecRG.reinterpretShape(HALVED_F, 0).intoMemorySegment(dstSeg1, d, dstBO)
                vecRG.reinterpretShape(HALVED_F, 1).intoMemorySegment(dstSeg2, d, dstBO)
                srcVec.reinterpretShape(HALVED_S, 1).unsignedS2F(0).mul(fh).intoMemorySegment(dstSeg3, d, dstBO)
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                dstSeg1.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 0L, srcBO)))
                dstSeg2.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 2L, srcBO)))
                dstSeg3.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 4L, srcBO)))
            })
        }

        private fun conv3HToPF(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(src, dst, 6, -1, 0, { _, _, _, _, _, _ ->
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                dstSeg1.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 0L, srcBO)))
                dstSeg2.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 2L, srcBO)))
                dstSeg3.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 4L, srcBO)))
            })
        }

        private fun conv3FToPF(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(src, dst, 12, -1, 0, { _, _, _, _, _, _ ->
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, d ->
                dstSeg1.putFloat(d, dstBO, srcSeg.getFloat(s + 0L, srcBO))
                dstSeg2.putFloat(d, dstBO, srcSeg.getFloat(s + 4L, srcBO))
                dstSeg3.putFloat(d, dstBO, srcSeg.getFloat(s + 8L, srcBO))
            })
        }

        private fun conv4BToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(src, dst, 4, QUART_VLEN, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                val srcVec = vec(B, srcSeg, s, NBO).rearrange(UNZIP_4B)
                srcVec.unsignedB2F(0).mul(f).intoMemorySegment(dstSeg1, d, dstBO)
                srcVec.unsignedB2F(1).mul(f).intoMemorySegment(dstSeg2, d, dstBO)
                srcVec.unsignedB2F(2).mul(f).intoMemorySegment(dstSeg3, d, dstBO)
                srcVec.unsignedB2F(3).mul(f).intoMemorySegment(dstSeg4, d, dstBO)
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                dstSeg1.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 0L)))
                dstSeg2.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 1L)))
                dstSeg3.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 2L)))
                dstSeg4.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getByte(s + 3L)))
            })
        }

        private fun conv4SToPF(src: Bitmap, dst: Bitmap, fac: Float) {
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(src, dst, 8, EIGHTH_VLEN, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                // Note: The vec().rearrange() part in the definition of both vecRG and vecBA is duplicated. If we
                // extract this part into its own variable and reuse it, the code gets roughly 7x slower. I presume
                // that's due to a lack of SIMD registers on my machine, or shortcomings of JDK 21 when it comes to
                // using the limited registers efficiently. In the future, we should definitely try extracting the
                // duplicated part into a srcVec variable again, and see whether the situation has improved.
                val vecRG = vec(S, srcSeg, s, srcBO).rearrange(UNZIP_4S).unsignedS2F(0).mul(f)
                vecRG.reinterpretShape(HALVED_F, 0).intoMemorySegment(dstSeg1, d, dstBO)
                vecRG.reinterpretShape(HALVED_F, 1).intoMemorySegment(dstSeg2, d, dstBO)
                val vecBA = vec(S, srcSeg, s, srcBO).rearrange(UNZIP_4S).unsignedS2F(1).mul(f)
                vecBA.reinterpretShape(HALVED_F, 0).intoMemorySegment(dstSeg3, d, dstBO)
                vecBA.reinterpretShape(HALVED_F, 1).intoMemorySegment(dstSeg4, d, dstBO)
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                dstSeg1.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 0L, srcBO)))
                dstSeg2.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 2L, srcBO)))
                dstSeg3.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 4L, srcBO)))
                dstSeg4.putFloat(d, dstBO, fac * toUnsignedInt(srcSeg.getShort(s + 6L, srcBO)))
            })
        }

        private fun conv4HToPF(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(src, dst, 8, -1, { _, _, _, _, _, _, _ ->
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                dstSeg1.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 0L, srcBO)))
                dstSeg2.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 2L, srcBO)))
                dstSeg3.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 4L, srcBO)))
                dstSeg4.putFloat(d, dstBO, float16ToFloat(srcSeg.getShort(s + 6L, srcBO)))
            })
        }

        private fun conv4FToPF(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(src, dst, 16, -1, { _, _, _, _, _, _, _ ->
            }, { srcSeg, s, dstSeg1, dstSeg2, dstSeg3, dstSeg4, d ->
                dstSeg1.putFloat(d, dstBO, srcSeg.getFloat(s + 0L, srcBO))
                dstSeg2.putFloat(d, dstBO, srcSeg.getFloat(s + 4L, srcBO))
                dstSeg3.putFloat(d, dstBO, srcSeg.getFloat(s + 8L, srcBO))
                dstSeg4.putFloat(d, dstBO, srcSeg.getFloat(s + 12L, srcBO))
            })
        }

        private fun convPFToPB(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 4, 1, QUART_VLEN, 0, { srcSeg, s, dstSeg, d ->
                vec(F, srcSeg, s, srcBO).pq(h, f).convertShape(F2B, QUART_B, 0).intoMemorySegment(dstSeg, d, NBO)
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putByte(d, quantize(fac, srcSeg.getFloat(s, srcBO)).toByte())
            })
        }

        private fun convPFToPBFix(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 4, 1, QUART_VLEN, QUART_VLEN, { srcSeg, s, dstSeg, d ->
                vec(F, srcSeg, s, srcBO).pq(h, f).convertShape(F2B, HALVED_B, 0).intoMemorySegment(dstSeg, d, NBO)
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putByte(d, quantize(fac, srcSeg.getFloat(s, srcBO)).toByte())
            })
        }

        private fun convPFToPS(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 4, 2, QUART_VLEN, 0, { srcSeg, s, dstSeg, d ->
                vec(F, srcSeg, s, srcBO).pq(h, f).convertShape(F2S, HALVED_S, 0).intoMemorySegment(dstSeg, d, dstBO)
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putShort(d, dstBO, quantize(fac, srcSeg.getFloat(s, srcBO)).toShort())
            })
        }

        private fun convPFToPH(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iteratePlanewise(src, dst, 4, 2, -1, 0, { _, _, _, _ ->
            }, { srcSeg, s, dstSeg, d ->
                dstSeg.putShort(d, dstBO, floatToFloat16(srcSeg.getFloat(s, srcBO)))
            })
        }

        private fun convPFTo3B(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(dst, src, 3, QUART_VLEN, QUART_VLEN, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                val vecR = vec(F, srcSeg1, s, srcBO).pq(h, f).convert(F2B, 0)
                val vecRG = vec(F, srcSeg2, s, srcBO).pq(h, f).convert(F2B, 0).unslice(1 * QUART_VLEN, vecR, 0)
                val vecRGB = vec(F, srcSeg3, s, srcBO).pq(h, f).convert(F2B, 0).unslice(2 * QUART_VLEN, vecRG, 0)
                vecRGB.rearrange(ZIP_3B).intoMemorySegment(dstSeg, d, NBO)
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                dstSeg.putByte(d + 0L, quantize(fac, srcSeg1.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + 1L, quantize(fac, srcSeg2.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + 2L, quantize(fac, srcSeg3.getFloat(s, srcBO)).toByte())
            })
        }

        private fun convPFTo3BIn4B(src: Bitmap, dst: Bitmap, fac: Float, off: Int) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val oP1 = off + 1
            val oP2 = off + 2
            iterate1PlaneAnd3FPlanes(dst, src, 4, QUART_VLEN, 0, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                val vecR = vec(F, srcSeg1, s, srcBO).pq(h, f).convert(F2B, 0).unslice(off * QUART_VLEN)
                val vecRG = vec(F, srcSeg2, s, srcBO).pq(h, f).convert(F2B, 0).unslice(oP1 * QUART_VLEN, vecR, 0)
                val vecRGB = vec(F, srcSeg3, s, srcBO).pq(h, f).convert(F2B, 0).unslice(oP2 * QUART_VLEN, vecRG, 0)
                vecRGB.rearrange(ZIP_4B).intoMemorySegment(dstSeg, d, NBO)
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                dstSeg.putByte(d + off + 0L, quantize(fac, srcSeg1.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + off + 1L, quantize(fac, srcSeg2.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + off + 2L, quantize(fac, srcSeg3.getFloat(s, srcBO)).toByte())
            })
        }

        private fun convPFTo3S(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(dst, src, 6, QUART_VLEN, QUART_VLEN, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                val vecR = vec(F, srcSeg1, s, srcBO).pq(h, f).convert(F2S, 0)
                val vecRG = vec(F, srcSeg2, s, srcBO).pq(h, f).convert(F2S, 0)
                    .unslice(QUART_VLEN, vecR, 0).rearrange(ZIP_2S_BLOCKS)
                val vecB = vec(F, srcSeg3, s, srcBO).pq(h, f).convert(F2S, 0)
                vecB.unslice(QUART_VLEN, vecRG, 0).rearrange(ZIP_3S).intoMemorySegment(dstSeg, d, dstBO)
                vecRG.unslice(QUART_VLEN, vecB.unslice(EIGHTH_VLEN), 1).rearrange(ZIP_3S)
                    .intoMemorySegment(dstSeg, d + 3 * QUART_VLEN, dstBO)
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                dstSeg.putShort(d + 0L, dstBO, quantize(fac, srcSeg1.getFloat(s, srcBO)).toShort())
                dstSeg.putShort(d + 2L, dstBO, quantize(fac, srcSeg2.getFloat(s, srcBO)).toShort())
                dstSeg.putShort(d + 4L, dstBO, quantize(fac, srcSeg3.getFloat(s, srcBO)).toShort())
            })
        }

        private fun convPFTo3H(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(dst, src, 6, -1, 0, { _, _, _, _, _, _ ->
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                dstSeg.putShort(d + 0L, dstBO, floatToFloat16(srcSeg1.getFloat(s, srcBO)))
                dstSeg.putShort(d + 2L, dstBO, floatToFloat16(srcSeg2.getFloat(s, srcBO)))
                dstSeg.putShort(d + 4L, dstBO, floatToFloat16(srcSeg3.getFloat(s, srcBO)))
            })
        }

        private fun convPFTo3F(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd3FPlanes(dst, src, 12, -1, 0, { _, _, _, _, _, _ ->
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, s ->
                dstSeg.putFloat(d + 0L, dstBO, srcSeg1.getFloat(s, srcBO))
                dstSeg.putFloat(d + 4L, dstBO, srcSeg2.getFloat(s, srcBO))
                dstSeg.putFloat(d + 8L, dstBO, srcSeg3.getFloat(s, srcBO))
            })
        }

        private fun convPFTo4B(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(dst, src, 4, QUART_VLEN, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                val vecR = vec(F, srcSeg1, s, srcBO).pq(h, f).convert(F2B, 0)
                val vecRG = vec(F, srcSeg2, s, srcBO).pq(h, f).convert(F2B, 0).unslice(1 * QUART_VLEN, vecR, 0)
                val vecRGB = vec(F, srcSeg3, s, srcBO).pq(h, f).convert(F2B, 0).unslice(2 * QUART_VLEN, vecRG, 0)
                val vecRGBA = vec(F, srcSeg4, s, srcBO).pq(h, f).convert(F2B, 0).unslice(3 * QUART_VLEN, vecRGB, 0)
                vecRGBA.rearrange(ZIP_4B).intoMemorySegment(dstSeg, d, NBO)
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                dstSeg.putByte(d + 0L, quantize(fac, srcSeg1.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + 1L, quantize(fac, srcSeg2.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + 2L, quantize(fac, srcSeg3.getFloat(s, srcBO)).toByte())
                dstSeg.putByte(d + 3L, quantize(fac, srcSeg4.getFloat(s, srcBO)).toByte())
            })
        }

        private fun convPFTo4S(src: Bitmap, dst: Bitmap, fac: Float) {
            val h = FloatVector.broadcast(F, 0.5f)
            val f = FloatVector.broadcast(F, fac)
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(dst, src, 8, QUART_VLEN, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                val vecR = vec(F, srcSeg1, s, srcBO).pq(h, f).convert(F2S, 0)
                val vecRG = vec(F, srcSeg2, s, srcBO).pq(h, f).convert(F2S, 0)
                    .unslice(QUART_VLEN, vecR, 0).rearrange(ZIP_2S_BLOCKS)
                val vecB = vec(F, srcSeg3, s, srcBO).pq(h, f).convert(F2S, 0)
                val vecBA = vec(F, srcSeg4, s, srcBO).pq(h, f).convert(F2S, 0)
                    .unslice(QUART_VLEN, vecB, 0).rearrange(ZIP_2S_BLOCKS)
                vecBA.unslice(QUART_VLEN, vecRG, 0).rearrange(ZIP_4S).intoMemorySegment(dstSeg, d, dstBO)
                vecRG.unslice(QUART_VLEN, vecBA, 1).rearrange(ZIP_4S).intoMemorySegment(dstSeg, d + VLEN, dstBO)
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                dstSeg.putShort(d + 0L, dstBO, quantize(fac, srcSeg1.getFloat(s, srcBO)).toShort())
                dstSeg.putShort(d + 2L, dstBO, quantize(fac, srcSeg2.getFloat(s, srcBO)).toShort())
                dstSeg.putShort(d + 4L, dstBO, quantize(fac, srcSeg3.getFloat(s, srcBO)).toShort())
                dstSeg.putShort(d + 6L, dstBO, quantize(fac, srcSeg4.getFloat(s, srcBO)).toShort())
            })
        }

        private fun convPFTo4H(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(dst, src, 8, -1, { _, _, _, _, _, _, _ ->
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                dstSeg.putShort(d + 0L, dstBO, floatToFloat16(srcSeg1.getFloat(s, srcBO)))
                dstSeg.putShort(d + 2L, dstBO, floatToFloat16(srcSeg2.getFloat(s, srcBO)))
                dstSeg.putShort(d + 4L, dstBO, floatToFloat16(srcSeg3.getFloat(s, srcBO)))
                dstSeg.putShort(d + 6L, dstBO, floatToFloat16(srcSeg4.getFloat(s, srcBO)))
            })
        }

        private fun convPFTo4F(src: Bitmap, dst: Bitmap) {
            val srcBO = src.spec.representation.pixelFormat.byteOrder
            val dstBO = dst.spec.representation.pixelFormat.byteOrder
            iterate1PlaneAnd4FPlanes(dst, src, 16, -1, { _, _, _, _, _, _, _ ->
            }, { dstSeg, d, srcSeg1, srcSeg2, srcSeg3, srcSeg4, s ->
                dstSeg.putFloat(d + 0L, dstBO, srcSeg1.getFloat(s, srcBO))
                dstSeg.putFloat(d + 4L, dstBO, srcSeg2.getFloat(s, srcBO))
                dstSeg.putFloat(d + 8L, dstBO, srcSeg3.getFloat(s, srcBO))
                dstSeg.putFloat(d + 12L, dstBO, srcSeg4.getFloat(s, srcBO))
            })
        }

        private val QUART_B = if (QUART_VLEN >= 8) B.withShape(VectorShape.forBitSize(QUART_VLEN * 8)) else null
        private val HALVED_B = B.withShape(VectorShape.forBitSize(HALF_VLEN * 8))
        private val HALVED_S = S.withShape(VectorShape.forBitSize(HALF_VLEN * 8))
        private val HALVED_F = F.withShape(VectorShape.forBitSize(HALF_VLEN * 8))

        private val UNZIP_3B = B.shuffleFromOp { i ->
            if (i < 3 * QUART_VLEN) i / QUART_VLEN + (i % QUART_VLEN) * 3 else i
        }
        private val UNZIP_3S = S.shuffleFromOp { i ->
            if (i < 3 * EIGHTH_VLEN) i / EIGHTH_VLEN + (i % EIGHTH_VLEN) * 3 else i
        }
        private val UNZIP_4B = B.shuffleFromOp { i -> i / QUART_VLEN + (i % QUART_VLEN) * 4 }
        private val UNZIP_4S = S.shuffleFromOp { i -> i / EIGHTH_VLEN + (i % EIGHTH_VLEN) * 4 }
        private val ZIP_3B = B.shuffleFromOp { i -> if (i < 3 * QUART_VLEN) i / 3 + (i % 3) * QUART_VLEN else i }
        private val ZIP_3S = S.shuffleFromOp { i -> if (i < 3 * EIGHTH_VLEN) i / 3 + (i % 3) * EIGHTH_VLEN else i }
        private val ZIP_4B = B.shuffleFromOp { i -> i / 4 + (i % 4) * QUART_VLEN }
        private val ZIP_4S = S.shuffleFromOp { i -> i / 4 + (i % 4) * EIGHTH_VLEN }
        private val ZIP_2S_BLOCKS = S.shuffleFromOp { i ->
            i / EIGHTH_VLEN % 2 * QUART_VLEN + i / QUART_VLEN * EIGHTH_VLEN + i % EIGHTH_VLEN
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun Vector<Byte>.unsignedB2F(part: Int) = convert(ZERO_EXTEND_B2I, part).convert(I2F, 0)
        @Suppress("NOTHING_TO_INLINE")
        private inline fun Vector<Short>.unsignedS2F(part: Int) = convert(ZERO_EXTEND_S2I, part).convert(I2F, 0)

        // "Prepare for Quantization"
        // Note: We add 0.5 here before quantization happens because Java only offers us the CVTTPS2DQ instruction,
        // which truncates the decimal places to obtain an integer. Having access to the CVTPS2DQ instruction, which
        // truly rounds to the nearest integer (subject to the CPU rounding mode), would of course be better.
        @Suppress("NOTHING_TO_INLINE")
        private inline fun Vector<Float>.pq(halfVec: FloatVector, facVec: FloatVector) =
            mul(facVec).min(facVec).add(halfVec).max(halfVec)

        @Suppress("NOTHING_TO_INLINE")
        private inline fun quantize(fac: Float, x: Float) = ((x * fac).coerceIn(0f, fac) + 0.5f).toInt()

        private inline fun planewise(
            srcBitmap: Bitmap,
            dstBitmap: Bitmap,
            action: (MemorySegment, Int, MemorySegment, Int) -> Unit
        ) {
            val srcComps = srcBitmap.spec.representation.pixelFormat.components
            val dstComps = dstBitmap.spec.representation.pixelFormat.components
            for (c in srcComps.indices) {
                val srcPlane = srcComps[c].plane
                val dstPlane = dstComps[c].plane
                val srcSeg = srcBitmap.memorySegment(srcPlane)
                val dstSeg = dstBitmap.memorySegment(dstPlane)
                val srcLs = srcBitmap.linesize(srcPlane)
                val dstLs = dstBitmap.linesize(dstPlane)
                action(srcSeg, srcLs, dstSeg, dstLs)
            }
        }

        private inline fun iteratePlanewise(
            srcBitmap: Bitmap,
            dstBitmap: Bitmap,
            bytesPerSrcPx: Int,
            bytesPerDstPx: Int,
            pxPerVec: Int,
            vecOvershootBytesInALine: Int,
            vecAction: (MemorySegment, Long, MemorySegment, Long) -> Unit,
            tradAction: (MemorySegment, Long, MemorySegment, Long) -> Unit
        ) {
            planewise(srcBitmap, dstBitmap) { srcSeg, srcLs, dstSeg, dstLs ->
                iterate(
                    srcBitmap.spec.resolution, srcLs, dstLs, bytesPerSrcPx, bytesPerDstPx, pxPerVec,
                    vecOvershootBytesInALine,
                    { s, d -> vecAction(srcSeg, s, dstSeg, d) },
                    { s, d -> tradAction(srcSeg, s, dstSeg, d) }
                )
            }
        }

        private inline fun iterate1PlaneAnd3FPlanes(
            aBitmap: Bitmap,
            bBitmap: Bitmap,
            bytesPerAPx: Int,
            pxPerVec: Int,
            vecOvershootBytesInALine: Int,
            vecAction: (MemorySegment, Long, MemorySegment, MemorySegment, MemorySegment, Long) -> Unit,
            tradAction: (MemorySegment, Long, MemorySegment, MemorySegment, MemorySegment, Long) -> Unit
        ) {
            val aSeg = aBitmap.memorySegment(0)
            val (bSeg1, bSeg2, bSeg3) = planarSegsOrderedLikeInterleavedComps(bBitmap, aBitmap)
            val aLs = aBitmap.linesize(0)
            val bLs = bBitmap.linesize(0)
            iterate(aBitmap.spec.resolution, aLs, bLs, bytesPerAPx, 4, pxPerVec, vecOvershootBytesInALine,
                { a, b -> vecAction(aSeg, a, bSeg1, bSeg2, bSeg3, b) },
                { a, b -> tradAction(aSeg, a, bSeg1, bSeg2, bSeg3, b) }
            )
        }

        private inline fun iterate1PlaneAnd4FPlanes(
            aBitmap: Bitmap,
            bBitmap: Bitmap,
            bytesPerAPx: Int,
            pxPerVec: Int,
            vecAction: (MemorySegment, Long, MemorySegment, MemorySegment, MemorySegment, MemorySegment, Long) -> Unit,
            tradAction: (MemorySegment, Long, MemorySegment, MemorySegment, MemorySegment, MemorySegment, Long) -> Unit
        ) {
            val aSeg = aBitmap.memorySegment(0)
            val (bSeg1, bSeg2, bSeg3, bSeg4) = planarSegsOrderedLikeInterleavedComps(bBitmap, aBitmap)
            val aLs = aBitmap.linesize(0)
            val bLs = bBitmap.linesize(0)
            iterate(aBitmap.spec.resolution, aLs, bLs, bytesPerAPx, 4, pxPerVec, 0,
                { a, b -> vecAction(aSeg, a, bSeg1, bSeg2, bSeg3, bSeg4, b) },
                { a, b -> tradAction(aSeg, a, bSeg1, bSeg2, bSeg3, bSeg4, b) }
            )
        }

        private inline fun iterate(
            resolution: Resolution,
            bytesPerALine: Int,
            bytesPerBLine: Int,
            bytesPerAPx: Int,
            bytesPerBPx: Int,
            pxPerVec: Int,
            vecOvershootBytesInALine: Int,
            vecAction: (Long, Long) -> Unit,
            tradAction: (Long, Long) -> Unit
        ) {
            val (w, h) = resolution
            val maxVecX = minOf(
                w - 1,
                (bytesPerALine - vecOvershootBytesInALine) / bytesPerAPx - pxPerVec,
                bytesPerBLine / bytesPerBPx - pxPerVec
            )
            val aBytesPerVec = pxPerVec * bytesPerAPx
            val bBytesPerVec = pxPerVec * bytesPerBPx
            for (y in 0L..<h.toLong()) {
                var aIdx = y * bytesPerALine
                var bIdx = y * bytesPerBLine
                var x = 0
                if (pxPerVec > 0)
                    while (x <= maxVecX) {
                        vecAction(aIdx, bIdx)
                        aIdx += aBytesPerVec
                        bIdx += bBytesPerVec
                        x += pxPerVec
                    }
                while (x < w) {
                    tradAction(aIdx, bIdx)
                    aIdx += bytesPerAPx
                    bIdx += bytesPerBPx
                    x++
                }
            }
        }

        private fun planarSegsOrderedLikeInterleavedComps(planar: Bitmap, interleaved: Bitmap): List<MemorySegment> {
            val pComps = planar.spec.representation.pixelFormat.components
            val iComps = interleaved.spec.representation.pixelFormat.components
            return iComps.indices.sortedBy { iComps[it].offset }.map { planar.memorySegment(pComps[it].plane) }
        }

    }


    /* ***********************************************
       ********** ZIMG STAGE IMPLEMENTATION **********
       *********************************************** */

    /** This stage processes color and alpha separately because zimg often premultiplies it when it really shouldn't. */
    private class ZimgStage(
        srcSpec: Bitmap.Spec,
        dstSpec: Bitmap.Spec,
        promiseOpaque: Boolean,
        approxTransfer: Boolean,
        nearestNeighbor: Boolean
    ) : Stage {

        private var colorProc: Processor? = null
        private var alphaProc: Processor? = null

        init {
            setupSafely({
                colorProc = Processor(cd(srcSpec), cd(dstSpec), approxTransfer, nearestNeighbor)
                if (dstSpec.representation.pixelFormat.hasAlpha)
                    alphaProc = Processor(ad(srcSpec), ad(dstSpec), approxTransfer, nearestNeighbor || promiseOpaque)
            }, ::close)
        }

        private fun cd(spec: Bitmap.Spec): Processor.Desc {
            val rep = spec.representation
            val pixFmt = rep.pixelFormat
            return Processor.Desc(
                resolution = spec.resolution,
                family = pixFmt.family,
                isFloat = pixFmt.isFloat,
                depth = pixFmt.depth,
                hChromaSub = pixFmt.hChromaSub,
                vChromaSub = pixFmt.vChromaSub,
                components = if (pixFmt.hasAlpha) pixFmt.components.dropLast(1) else pixFmt.components,
                range = rep.range,
                primaries = rep.colorSpace?.primaries,
                transfer = rep.colorSpace?.transfer ?: LINEAR,
                yuvCoefficients = rep.yuvCoefficients,
                chromaLocation = rep.chromaLocation,
                content = spec.content
            )
        }

        private fun ad(spec: Bitmap.Spec): Processor.Desc {
            val pixFmt = spec.representation.pixelFormat
            return Processor.Desc(
                resolution = spec.resolution,
                family = GRAY,
                isFloat = pixFmt.isFloat,
                depth = pixFmt.depth,
                hChromaSub = 0,
                vChromaSub = 0,
                components = pixFmt.components.takeLast(1),
                range = FULL,
                primaries = null,
                transfer = LINEAR,
                yuvCoefficients = null,
                chromaLocation = AVCHROMA_LOC_UNSPECIFIED,
                content = spec.content
            )
        }

        override fun close() {
            colorProc?.close()
            alphaProc?.close()
        }

        override fun process(src: Bitmap, dst: Bitmap) {
            colorProc!!.process(src, dst)
            alphaProc?.process(src, dst)
            // Lanczos scaling leads to ringing, which can produce values outside the input range, including values
            // below 0 and above 1. While such values are fine for color, they are illegal for alpha and, e.g., mess up
            // premultiplied alpha and make Skia produce strange and undesired artifacts. So if this processor applies
            // scaling and the destination bitmap uses floating point and thus supports out-of-bounds values, clamp the
            // destination alpha plane to the [0, 1] range.
            if (src.spec.resolution != dst.spec.resolution) {
                val dstPixFmt = dst.spec.representation.pixelFormat
                if (dstPixFmt.hasAlpha && dstPixFmt.isFloat) {
                    val depth = dstPixFmt.components.last().depth
                    require(depth == 32) { "Cannot clamp $depth-bit float bitmap." }
                    clampAlphaPlane(dst)
                }
            }
        }

        private fun clampAlphaPlane(dst: Bitmap) {
            val (w, h) = dst.spec.resolution
            val pixFmt = dst.spec.representation.pixelFormat
            val bo = pixFmt.byteOrder
            val plane = pixFmt.components.last().plane
            val seg = dst.memorySegment(plane)
            val ls = dst.linesize(plane)
            val maxVecX = min(w - 1, ls / 4 - F.length())
            val vec0 = FloatVector.zero(F)
            val vec1 = FloatVector.broadcast(F, 1f)
            for (y in 0L..<h.toLong()) {
                var idx = y * ls
                var x = 0
                while (x <= maxVecX) {
                    vec(F, seg, idx, bo).max(vec0).min(vec1).intoMemorySegment(seg, idx, bo)
                    idx += F.vectorByteSize()
                    x += F.length()
                }
                while (x < w) {
                    seg.putFloat(idx, bo, seg.getFloat(idx, bo).coerceIn(0f, 1f))
                    idx += 4
                    x++
                }
            }
        }

        private class Processor(
            private val srcDesc: Desc,
            private val dstDesc: Desc,
            private val approxTransfer: Boolean,
            private val nearestNeighbor: Boolean
        ) {

            class Desc(
                val resolution: Resolution,
                val family: Bitmap.PixelFormat.Family,
                val isFloat: Boolean,
                val depth: Int,
                val hChromaSub: Int,
                val vChromaSub: Int,
                val components: List<Bitmap.PixelFormat.Component>,
                val range: Bitmap.Range,
                val primaries: ColorSpace.Primaries?,
                val transfer: ColorSpace.Transfer,
                val yuvCoefficients: Bitmap.YUVCoefficients?,
                val chromaLocation: Int,
                val content: Bitmap.Content
            )

            private class Graph(val handle: MemorySegment, val srcOffset: Int, val dstOffset: Int, val step: Int)

            private val arena = Arena.ofShared()
            private val graphs = mutableListOf<Graph>()
            private var srcSeg: MemorySegment? = null
            private var dstSeg: MemorySegment? = null
            private var tmpMem: MemorySegment? = null

            init {
                setupSafely(::setup, ::close)
            }

            private fun setup() {
                // Build the zimg filter graph(s).
                val srcC = srcDesc.content
                val dstC = dstDesc.content
                if (srcC == Bitmap.Content.INTERLEAVED_FIELDS || srcC == Bitmap.Content.INTERLEAVED_FIELDS_REVERSED ||
                    dstC == Bitmap.Content.INTERLEAVED_FIELDS || dstC == Bitmap.Content.INTERLEAVED_FIELDS_REVERSED
                ) {
                    var srcTopPar = ZIMG_FIELD_TOP()
                    var dstTopPar = ZIMG_FIELD_TOP()
                    var srcBotPar = ZIMG_FIELD_BOTTOM()
                    var dstBotPar = ZIMG_FIELD_BOTTOM()
                    var srcTopOffset = if (srcC == Bitmap.Content.INTERLEAVED_FIELDS) 0 else 1
                    var dstTopOffset = if (dstC == Bitmap.Content.INTERLEAVED_FIELDS) 0 else 1
                    if (srcC == Bitmap.Content.PROGRESSIVE_FRAME) {
                        srcTopPar = ZIMG_FIELD_PROGRESSIVE()
                        srcBotPar = ZIMG_FIELD_PROGRESSIVE()
                        srcTopOffset = dstTopOffset
                    }
                    if (dstC == Bitmap.Content.PROGRESSIVE_FRAME) {
                        dstTopPar = ZIMG_FIELD_PROGRESSIVE()
                        dstBotPar = ZIMG_FIELD_PROGRESSIVE()
                        dstTopOffset = srcTopOffset
                    }
                    graphs += buildGraph(srcTopPar, srcTopOffset, dstTopPar, dstTopOffset, step = 2)
                    graphs += buildGraph(srcBotPar, 1 - srcTopOffset, dstBotPar, 1 - dstTopOffset, step = 2)
                } else
                    graphs += buildGraph(zimgFieldParity(srcC), 0, zimgFieldParity(dstC), 0, step = 1)

                // Populate the buffer structs.
                srcSeg = zimg_image_buffer_const.allocate(arena)
                dstSeg = zimg_image_buffer.allocate(arena)
                zimg_image_buffer_const.`version$set`(srcSeg, ZIMG_API_VERSION())
                zimg_image_buffer.`version$set`(dstSeg, ZIMG_API_VERSION())
                for (plane in 0L..<4L) {
                    ZIMG_BUF_CONST_MASK.set(srcSeg, plane, ZIMG_BUFFER_MAX())
                    ZIMG_BUF_MASK.set(dstSeg, plane, ZIMG_BUFFER_MAX())
                }

                // Find how much temporary memory we need, and allocate it.
                tmpMem = arena.allocate(graphs.maxOf(::getTmpSize), Bitmap.BYTE_ALIGNMENT.toLong())
            }

            private fun buildGraph(
                srcFieldParity: Int, srcOffset: Int, dstFieldParity: Int, dstOffset: Int, step: Int
            ): Graph {
                // Populate the format structs.
                val srcFmt = populateImageFormat(srcDesc, srcFieldParity, srcOffset, step)
                val dstFmt = populateImageFormat(dstDesc, dstFieldParity, dstOffset, step)
                // Populate the params struct.
                val params = zimg_graph_builder_params.allocate(arena)
                zimg_graph_builder_params_default(params, ZIMG_API_VERSION())
                val resampleFilter = if (nearestNeighbor) ZIMG_RESIZE_POINT() else ZIMG_RESIZE_LANCZOS()
                zimg_graph_builder_params.`resample_filter$set`(params, resampleFilter)
                zimg_graph_builder_params.`resample_filter_uv$set`(params, resampleFilter)
                zimg_graph_builder_params.`cpu_type$set`(params, ZIMG_CPU_AUTO_64B())
                zimg_graph_builder_params.`nominal_peak_luminance$set`(params, 203.0)
                zimg_graph_builder_params.`allow_approximate_gamma$set`(params, if (approxTransfer) 1 else 0)
                // Build the zimg filter graph.
                val handle = zimg_filter_graph_build(srcFmt, dstFmt, params)
                    .zimgThrowIfNull("Could not build zimg graph")
                return Graph(handle, srcOffset, dstOffset, step)
            }

            private fun populateImageFormat(desc: Desc, fieldParity: Int, offset: Int, step: Int): MemorySegment {
                var (width, height) = desc.resolution
                height = ceilDiv(height - offset, step)
                val depth = desc.depth
                val pixelType = when {
                    desc.isFloat -> when (depth) {
                        16 -> ZIMG_PIXEL_HALF()
                        32 -> ZIMG_PIXEL_FLOAT()
                        else -> throw IllegalArgumentException("For zimg, the float depth ($depth) must be 16 or 32.")
                    }
                    else -> when {
                        depth <= 8 -> ZIMG_PIXEL_BYTE()
                        depth <= 16 -> ZIMG_PIXEL_WORD()
                        else -> throw IllegalArgumentException("For zimg, the bit depth ($depth) must not exceed 16.")
                    }
                }
                val colorFam = when (desc.family) {
                    GRAY -> ZIMG_COLOR_GREY()
                    RGB -> ZIMG_COLOR_RGB()
                    YUV -> ZIMG_COLOR_YUV()
                }
                val matrix = when (desc.family) {
                    GRAY -> ZIMG_MATRIX_UNSPECIFIED()
                    RGB -> ZIMG_MATRIX_RGB()
                    YUV -> zimgMatrix(desc.yuvCoefficients!!)
                }
                val prim = when (val primaries = desc.primaries) {
                    null -> ZIMG_PRIMARIES_UNSPECIFIED()
                    ColorSpace.Primaries.XYZD50 -> ZIMG_PRIMARIES_XYZ_D50()
                    else -> zimgPrimaries(primaries)
                }
                val range = when (desc.range) {
                    FULL -> ZIMG_RANGE_FULL()
                    LIMITED -> ZIMG_RANGE_LIMITED()
                }

                val fmt = zimg_image_format.allocate(arena)
                zimg_image_format_default(fmt, ZIMG_API_VERSION())
                zimg_image_format.`width$set`(fmt, width)
                zimg_image_format.`height$set`(fmt, height)
                zimg_image_format.`pixel_type$set`(fmt, pixelType)
                if (desc.family == YUV) {
                    zimg_image_format.`subsample_w$set`(fmt, desc.hChromaSub)
                    zimg_image_format.`subsample_h$set`(fmt, desc.vChromaSub)
                }
                zimg_image_format.`color_family$set`(fmt, colorFam)
                zimg_image_format.`matrix_coefficients$set`(fmt, matrix)
                zimg_image_format.`transfer_characteristics$set`(fmt, zimgTransfer(desc.transfer))
                zimg_image_format.`color_primaries$set`(fmt, prim)
                zimg_image_format.`depth$set`(fmt, depth)
                zimg_image_format.`pixel_range$set`(fmt, range)
                zimg_image_format.`field_parity$set`(fmt, fieldParity)
                zimg_image_format.`chroma_location$set`(fmt, zimgChroma(desc.chromaLocation))
                zimg_image_format.`alpha$set`(fmt, ZIMG_ALPHA_NONE())
                return fmt
            }

            private fun getTmpSize(graph: Graph): Long {
                val out = arena.allocate(JAVA_LONG)
                zimg_filter_graph_get_tmp_size(graph.handle, out)
                    .zimgThrowIfErrnum("Could not obtain the temp zimg buffer size")
                return out.get(JAVA_LONG, 0L)
            }

            fun close() {
                for (graph in graphs)
                    zimg_filter_graph_free(graph.handle)
                arena?.close()
            }

            fun process(src: Bitmap, dst: Bitmap) {
                for (graph in graphs) {
                    for ((i, srcComp) in srcDesc.components.withIndex()) {
                        val srcPlane = srcComp.plane
                        val srcStride = src.linesize(srcPlane).toLong()
                        val srcData = src.memorySegment(srcPlane).asSlice(graph.srcOffset * srcStride)
                        ZIMG_BUF_CONST_DATA.set(srcSeg, i.toLong(), srcData)
                        ZIMG_BUF_CONST_STRIDE.set(srcSeg, i.toLong(), graph.step * srcStride)
                    }

                    for ((i, dstComp) in dstDesc.components.withIndex()) {
                        val dstPlane = dstComp.plane
                        val dstStride = dst.linesize(dstPlane).toLong()
                        val dstData = dst.memorySegment(dstPlane).asSlice(graph.dstOffset * dstStride)
                        ZIMG_BUF_DATA.set(dstSeg, i.toLong(), dstData)
                        ZIMG_BUF_STRIDE.set(dstSeg, i.toLong(), graph.step * dstStride)
                    }

                    zimg_filter_graph_process(graph.handle, srcSeg, dstSeg, tmpMem, NULL, NULL, NULL, NULL)
                        .zimgThrowIfErrnum("Error while converting a frame or field with zimg")
                }
            }

            private fun MemorySegment?.zimgThrowIfNull(message: String): MemorySegment =
                if (this == null || this == NULL)
                    throw ZimgException(zimgExcStr(message))
                else this

            private fun Int.zimgThrowIfErrnum(message: String): Int =
                if (this < 0)
                    throw ZimgException(zimgExcStr(message))
                else this

            private fun zimgExcStr(message: String): String {
                val cStr = arena.allocate(1024)
                val errnum = zimg_get_last_error(cStr, cStr.byteSize())
                return "$message: ${cStr.getUtf8String(0)} (zimg error number $errnum)."
            }

            companion object {

                private val ZIMG_BUF_CONST_DATA: VarHandle
                private val ZIMG_BUF_CONST_STRIDE: VarHandle
                private val ZIMG_BUF_CONST_MASK: VarHandle
                private val ZIMG_BUF_DATA: VarHandle
                private val ZIMG_BUF_STRIDE: VarHandle
                private val ZIMG_BUF_MASK: VarHandle

                init {
                    val planePath = arrayOf(groupElement("plane"), sequenceElement())
                    val bufC = zimg_image_buffer_const.`$LAYOUT`()
                    val buf = zimg_image_buffer.`$LAYOUT`()
                    ZIMG_BUF_CONST_DATA = bufC.varHandle(*planePath, groupElement("data")).withInvokeExactBehavior()
                    ZIMG_BUF_CONST_STRIDE = bufC.varHandle(*planePath, groupElement("stride")).withInvokeExactBehavior()
                    ZIMG_BUF_CONST_MASK = bufC.varHandle(*planePath, groupElement("mask")).withInvokeExactBehavior()
                    ZIMG_BUF_DATA = buf.varHandle(*planePath, groupElement("data")).withInvokeExactBehavior()
                    ZIMG_BUF_STRIDE = buf.varHandle(*planePath, groupElement("stride")).withInvokeExactBehavior()
                    ZIMG_BUF_MASK = buf.varHandle(*planePath, groupElement("mask")).withInvokeExactBehavior()
                }

                private fun zimgPrimaries(primaries: ColorSpace.Primaries): Int =
                    if (!primaries.hasCode) ZIMG_PRIMARIES_UNSPECIFIED() else when (primaries.code) {
                        AVCOL_PRI_BT709 -> ZIMG_PRIMARIES_BT709()
                        AVCOL_PRI_BT470M -> ZIMG_PRIMARIES_BT470_M()
                        AVCOL_PRI_BT470BG -> ZIMG_PRIMARIES_BT470_BG()
                        AVCOL_PRI_SMPTE170M -> ZIMG_PRIMARIES_ST170_M()
                        AVCOL_PRI_SMPTE240M -> ZIMG_PRIMARIES_ST240_M()
                        AVCOL_PRI_FILM -> ZIMG_PRIMARIES_FILM()
                        AVCOL_PRI_BT2020 -> ZIMG_PRIMARIES_BT2020()
                        AVCOL_PRI_SMPTE428 -> ZIMG_PRIMARIES_ST428()
                        AVCOL_PRI_SMPTE431 -> ZIMG_PRIMARIES_ST431_2()
                        AVCOL_PRI_SMPTE432 -> ZIMG_PRIMARIES_ST432_1()
                        AVCOL_PRI_EBU3213 -> ZIMG_PRIMARIES_EBU3213_E()
                        else -> throw IllegalArgumentException("Primaries not supported by zimg: $primaries")
                    }

                private fun zimgTransfer(transfer: ColorSpace.Transfer): Int =
                    if (!transfer.hasCode) ZIMG_TRANSFER_UNSPECIFIED() else when (transfer.code) {
                        AVCOL_TRC_BT709 -> ZIMG_TRANSFER_BT709()
                        AVCOL_TRC_GAMMA22 -> ZIMG_TRANSFER_BT470_M()
                        AVCOL_TRC_GAMMA28 -> ZIMG_TRANSFER_BT470_BG()
                        AVCOL_TRC_SMPTE170M -> ZIMG_TRANSFER_BT601()
                        AVCOL_TRC_SMPTE240M -> ZIMG_TRANSFER_ST240_M()
                        AVCOL_TRC_LINEAR -> ZIMG_TRANSFER_LINEAR()
                        AVCOL_TRC_LOG -> ZIMG_TRANSFER_LOG_100()
                        AVCOL_TRC_LOG_SQRT -> ZIMG_TRANSFER_LOG_316()
                        AVCOL_TRC_IEC61966_2_4 -> ZIMG_TRANSFER_IEC_61966_2_4()
                        AVCOL_TRC_IEC61966_2_1 -> ZIMG_TRANSFER_IEC_61966_2_1()
                        AVCOL_TRC_BT2020_10 -> ZIMG_TRANSFER_BT2020_10()
                        AVCOL_TRC_BT2020_12 -> ZIMG_TRANSFER_BT2020_12()
                        AVCOL_TRC_SMPTE2084 -> ZIMG_TRANSFER_ST2084()
                        AVCOL_TRC_SMPTE428 -> ZIMG_TRANSFER_ST428()
                        AVCOL_TRC_ARIB_STD_B67 -> ZIMG_TRANSFER_ARIB_B67()
                        else -> throw IllegalArgumentException("Transfer characteristics not supported by zimg: $transfer")
                    }

                private fun zimgMatrix(coeff: Bitmap.YUVCoefficients): Int =
                    when (coeff.code) {
                        AVCOL_SPC_BT709 -> ZIMG_MATRIX_BT709()
                        AVCOL_SPC_FCC -> ZIMG_MATRIX_FCC()
                        AVCOL_SPC_BT470BG -> ZIMG_MATRIX_BT470_BG()
                        AVCOL_SPC_SMPTE170M -> ZIMG_MATRIX_ST170_M()
                        AVCOL_SPC_SMPTE240M -> ZIMG_MATRIX_ST240_M()
                        AVCOL_SPC_YCGCO -> ZIMG_MATRIX_YCGCO()
                        AVCOL_SPC_BT2020_NCL -> ZIMG_MATRIX_BT2020_NCL()
                        AVCOL_SPC_BT2020_CL -> ZIMG_MATRIX_BT2020_CL()
                        AVCOL_SPC_CHROMA_DERIVED_NCL -> ZIMG_MATRIX_CHROMATICITY_DERIVED_NCL()
                        AVCOL_SPC_CHROMA_DERIVED_CL -> ZIMG_MATRIX_CHROMATICITY_DERIVED_CL()
                        AVCOL_SPC_ICTCP -> ZIMG_MATRIX_ICTCP()
                        else -> throw IllegalArgumentException("YUV coefficients not supported by zimg: $coeff")
                    }

                private fun zimgChroma(code: Int): Int =
                    when (code) {
                        AVCHROMA_LOC_UNSPECIFIED, AVCHROMA_LOC_LEFT -> ZIMG_CHROMA_LEFT()
                        AVCHROMA_LOC_CENTER -> ZIMG_CHROMA_CENTER()
                        AVCHROMA_LOC_TOPLEFT -> ZIMG_CHROMA_TOP_LEFT()
                        AVCHROMA_LOC_TOP -> ZIMG_CHROMA_TOP()
                        AVCHROMA_LOC_BOTTOMLEFT -> ZIMG_CHROMA_BOTTOM_LEFT()
                        AVCHROMA_LOC_BOTTOM -> ZIMG_CHROMA_BOTTOM()
                        else -> throw IllegalArgumentException("Chroma location code not supported by zimg: $code")
                    }

                private fun zimgFieldParity(content: Bitmap.Content): Int =
                    when (content) {
                        Bitmap.Content.PROGRESSIVE_FRAME -> ZIMG_FIELD_PROGRESSIVE()
                        Bitmap.Content.ONLY_TOP_FIELD -> ZIMG_FIELD_TOP()
                        Bitmap.Content.ONLY_BOT_FIELD -> ZIMG_FIELD_BOTTOM()
                        else -> throw IllegalArgumentException("Content does not define single field parity: $content")
                    }

            }

            private class ZimgException(message: String) : RuntimeException(message)

        }

        companion object {

            /** Returns a planar and native-endian pixel format that the given format can be losslessly converted to. */
            fun equiv(pixelFormat: Bitmap.PixelFormat) =
                Bitmap.PixelFormat.of(ZIMG_SUPP_EQUIV[pixelFormat.code])

            private val ZIMG_SUPP_EQUIV = IntArray(AV_PIX_FMT_NB).also { eq ->
                eq.fill(-2)
                eq[AV_PIX_FMT_YUV420P] = AV_PIX_FMT_YUV420P
                eq[AV_PIX_FMT_YUYV422] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_RGB24] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR24] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_YUV422P] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_YUV444P] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_YUV410P] = AV_PIX_FMT_YUV410P
                eq[AV_PIX_FMT_YUV411P] = AV_PIX_FMT_YUV411P
                eq[AV_PIX_FMT_GRAY8] = AV_PIX_FMT_GRAY8
                eq[AV_PIX_FMT_MONOWHITE] = AV_PIX_FMT_GRAY8
                eq[AV_PIX_FMT_MONOBLACK] = AV_PIX_FMT_GRAY8
                eq[AV_PIX_FMT_PAL8] = -1
                eq[AV_PIX_FMT_YUVJ420P] = AV_PIX_FMT_YUVJ420P
                eq[AV_PIX_FMT_YUVJ422P] = AV_PIX_FMT_YUVJ422P
                eq[AV_PIX_FMT_YUVJ444P] = AV_PIX_FMT_YUVJ444P
                eq[AV_PIX_FMT_UYVY422] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_UYYVYY411] = AV_PIX_FMT_YUV411P
                eq[AV_PIX_FMT_BGR8] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR4] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR4_BYTE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB8] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB4] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB4_BYTE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_NV12] = AV_PIX_FMT_YUV420P
                eq[AV_PIX_FMT_NV21] = AV_PIX_FMT_YUV420P
                eq[AV_PIX_FMT_ARGB] = AV_PIX_FMT_GBRAP
                eq[AV_PIX_FMT_RGBA] = AV_PIX_FMT_GBRAP
                eq[AV_PIX_FMT_ABGR] = AV_PIX_FMT_GBRAP
                eq[AV_PIX_FMT_BGRA] = AV_PIX_FMT_GBRAP
                eq[AV_PIX_FMT_GRAY16BE] = AV_PIX_FMT_GRAY16BE
                eq[AV_PIX_FMT_GRAY16LE] = AV_PIX_FMT_GRAY16LE
                eq[AV_PIX_FMT_YUV440P] = AV_PIX_FMT_YUV440P
                eq[AV_PIX_FMT_YUVJ440P] = AV_PIX_FMT_YUVJ440P
                eq[AV_PIX_FMT_YUVA420P] = AV_PIX_FMT_YUVA420P
                eq[AV_PIX_FMT_RGB48BE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_RGB48LE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_RGB565BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB565LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB555BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB555LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR565BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR565LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR555BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR555LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_VAAPI] = -1
                eq[AV_PIX_FMT_YUV420P16LE] = AV_PIX_FMT_YUV420P16
                eq[AV_PIX_FMT_YUV420P16BE] = AV_PIX_FMT_YUV420P16
                eq[AV_PIX_FMT_YUV422P16LE] = AV_PIX_FMT_YUV422P16
                eq[AV_PIX_FMT_YUV422P16BE] = AV_PIX_FMT_YUV422P16
                eq[AV_PIX_FMT_YUV444P16LE] = AV_PIX_FMT_YUV444P16
                eq[AV_PIX_FMT_YUV444P16BE] = AV_PIX_FMT_YUV444P16
                eq[AV_PIX_FMT_DXVA2_VLD] = -1
                eq[AV_PIX_FMT_RGB444LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB444BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR444LE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR444BE] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_YA8] = -1
                eq[AV_PIX_FMT_BGR48BE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_BGR48LE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_YUV420P9BE] = AV_PIX_FMT_YUV420P9
                eq[AV_PIX_FMT_YUV420P9LE] = AV_PIX_FMT_YUV420P9
                eq[AV_PIX_FMT_YUV420P10BE] = AV_PIX_FMT_YUV420P10
                eq[AV_PIX_FMT_YUV420P10LE] = AV_PIX_FMT_YUV420P10
                eq[AV_PIX_FMT_YUV422P10BE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_YUV422P10LE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_YUV444P9BE] = AV_PIX_FMT_YUV444P9
                eq[AV_PIX_FMT_YUV444P9LE] = AV_PIX_FMT_YUV444P9
                eq[AV_PIX_FMT_YUV444P10BE] = AV_PIX_FMT_YUV444P10
                eq[AV_PIX_FMT_YUV444P10LE] = AV_PIX_FMT_YUV444P10
                eq[AV_PIX_FMT_YUV422P9BE] = AV_PIX_FMT_YUV422P9
                eq[AV_PIX_FMT_YUV422P9LE] = AV_PIX_FMT_YUV422P9
                eq[AV_PIX_FMT_GBRP] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_GBRP9BE] = AV_PIX_FMT_GBRP9
                eq[AV_PIX_FMT_GBRP9LE] = AV_PIX_FMT_GBRP9
                eq[AV_PIX_FMT_GBRP10BE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_GBRP10LE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_GBRP16BE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_GBRP16LE] = AV_PIX_FMT_GBRP16
                eq[AV_PIX_FMT_YUVA422P] = AV_PIX_FMT_YUVA422P
                eq[AV_PIX_FMT_YUVA444P] = AV_PIX_FMT_YUVA444P
                eq[AV_PIX_FMT_YUVA420P9BE] = AV_PIX_FMT_YUVA420P9
                eq[AV_PIX_FMT_YUVA420P9LE] = AV_PIX_FMT_YUVA420P9
                eq[AV_PIX_FMT_YUVA422P9BE] = AV_PIX_FMT_YUVA422P9
                eq[AV_PIX_FMT_YUVA422P9LE] = AV_PIX_FMT_YUVA422P9
                eq[AV_PIX_FMT_YUVA444P9BE] = AV_PIX_FMT_YUVA444P9
                eq[AV_PIX_FMT_YUVA444P9LE] = AV_PIX_FMT_YUVA444P9
                eq[AV_PIX_FMT_YUVA420P10BE] = AV_PIX_FMT_YUVA420P10
                eq[AV_PIX_FMT_YUVA420P10LE] = AV_PIX_FMT_YUVA420P10
                eq[AV_PIX_FMT_YUVA422P10BE] = AV_PIX_FMT_YUVA422P10
                eq[AV_PIX_FMT_YUVA422P10LE] = AV_PIX_FMT_YUVA422P10
                eq[AV_PIX_FMT_YUVA444P10BE] = AV_PIX_FMT_YUVA444P10
                eq[AV_PIX_FMT_YUVA444P10LE] = AV_PIX_FMT_YUVA444P10
                eq[AV_PIX_FMT_YUVA420P16BE] = AV_PIX_FMT_YUVA420P16
                eq[AV_PIX_FMT_YUVA420P16LE] = AV_PIX_FMT_YUVA420P16
                eq[AV_PIX_FMT_YUVA422P16BE] = AV_PIX_FMT_YUVA422P16
                eq[AV_PIX_FMT_YUVA422P16LE] = AV_PIX_FMT_YUVA422P16
                eq[AV_PIX_FMT_YUVA444P16BE] = AV_PIX_FMT_YUVA444P16
                eq[AV_PIX_FMT_YUVA444P16LE] = AV_PIX_FMT_YUVA444P16
                eq[AV_PIX_FMT_VDPAU] = -1
                eq[AV_PIX_FMT_XYZ12LE] = AV_PIX_FMT_XYZ12
                eq[AV_PIX_FMT_XYZ12BE] = AV_PIX_FMT_XYZ12
                eq[AV_PIX_FMT_NV16] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_NV20LE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_NV20BE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_RGBA64BE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_RGBA64LE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_BGRA64BE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_BGRA64LE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_YVYU422] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_YA16BE] = -1
                eq[AV_PIX_FMT_YA16LE] = -1
                eq[AV_PIX_FMT_GBRAP] = AV_PIX_FMT_GBRAP
                eq[AV_PIX_FMT_GBRAP16BE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_GBRAP16LE] = AV_PIX_FMT_GBRAP16
                eq[AV_PIX_FMT_QSV] = -1
                eq[AV_PIX_FMT_MMAL] = -1
                eq[AV_PIX_FMT_D3D11VA_VLD] = -1
                eq[AV_PIX_FMT_CUDA] = -1
                eq[AV_PIX_FMT_0RGB] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_RGB0] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_0BGR] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_BGR0] = AV_PIX_FMT_GBRP
                eq[AV_PIX_FMT_YUV420P12BE] = AV_PIX_FMT_YUV420P12
                eq[AV_PIX_FMT_YUV420P12LE] = AV_PIX_FMT_YUV420P12
                eq[AV_PIX_FMT_YUV420P14BE] = AV_PIX_FMT_YUV420P14
                eq[AV_PIX_FMT_YUV420P14LE] = AV_PIX_FMT_YUV420P14
                eq[AV_PIX_FMT_YUV422P12BE] = AV_PIX_FMT_YUV422P12
                eq[AV_PIX_FMT_YUV422P12LE] = AV_PIX_FMT_YUV422P12
                eq[AV_PIX_FMT_YUV422P14BE] = AV_PIX_FMT_YUV422P14
                eq[AV_PIX_FMT_YUV422P14LE] = AV_PIX_FMT_YUV422P14
                eq[AV_PIX_FMT_YUV444P12BE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_YUV444P12LE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_YUV444P14BE] = AV_PIX_FMT_YUV444P14
                eq[AV_PIX_FMT_YUV444P14LE] = AV_PIX_FMT_YUV444P14
                eq[AV_PIX_FMT_GBRP12BE] = AV_PIX_FMT_GBRP12
                eq[AV_PIX_FMT_GBRP12LE] = AV_PIX_FMT_GBRP12
                eq[AV_PIX_FMT_GBRP14BE] = AV_PIX_FMT_GBRP14
                eq[AV_PIX_FMT_GBRP14LE] = AV_PIX_FMT_GBRP14
                eq[AV_PIX_FMT_YUVJ411P] = AV_PIX_FMT_YUVJ411P
                eq[AV_PIX_FMT_BAYER_BGGR8] = -1
                eq[AV_PIX_FMT_BAYER_RGGB8] = -1
                eq[AV_PIX_FMT_BAYER_GBRG8] = -1
                eq[AV_PIX_FMT_BAYER_GRBG8] = -1
                eq[AV_PIX_FMT_BAYER_BGGR16LE] = -1
                eq[AV_PIX_FMT_BAYER_BGGR16BE] = -1
                eq[AV_PIX_FMT_BAYER_RGGB16LE] = -1
                eq[AV_PIX_FMT_BAYER_RGGB16BE] = -1
                eq[AV_PIX_FMT_BAYER_GBRG16LE] = -1
                eq[AV_PIX_FMT_BAYER_GBRG16BE] = -1
                eq[AV_PIX_FMT_BAYER_GRBG16LE] = -1
                eq[AV_PIX_FMT_BAYER_GRBG16BE] = -1
                eq[AV_PIX_FMT_XVMC] = -1
                eq[AV_PIX_FMT_YUV440P10LE] = AV_PIX_FMT_YUV440P10
                eq[AV_PIX_FMT_YUV440P10BE] = AV_PIX_FMT_YUV440P10
                eq[AV_PIX_FMT_YUV440P12LE] = AV_PIX_FMT_YUV440P12
                eq[AV_PIX_FMT_YUV440P12BE] = AV_PIX_FMT_YUV440P12
                eq[AV_PIX_FMT_AYUV64LE] = AV_PIX_FMT_YUVA444P16
                eq[AV_PIX_FMT_AYUV64BE] = AV_PIX_FMT_YUVA444P16
                eq[AV_PIX_FMT_VIDEOTOOLBOX] = -1
                eq[AV_PIX_FMT_P010LE] = AV_PIX_FMT_YUV420P10
                eq[AV_PIX_FMT_P010BE] = AV_PIX_FMT_YUV420P10
                eq[AV_PIX_FMT_GBRAP12BE] = AV_PIX_FMT_GBRAP12
                eq[AV_PIX_FMT_GBRAP12LE] = AV_PIX_FMT_GBRAP12
                eq[AV_PIX_FMT_GBRAP10BE] = AV_PIX_FMT_GBRAP10
                eq[AV_PIX_FMT_GBRAP10LE] = AV_PIX_FMT_GBRAP10
                eq[AV_PIX_FMT_MEDIACODEC] = -1
                eq[AV_PIX_FMT_GRAY12BE] = AV_PIX_FMT_GRAY12
                eq[AV_PIX_FMT_GRAY12LE] = AV_PIX_FMT_GRAY12
                eq[AV_PIX_FMT_GRAY10BE] = AV_PIX_FMT_GRAY10
                eq[AV_PIX_FMT_GRAY10LE] = AV_PIX_FMT_GRAY10
                eq[AV_PIX_FMT_P016LE] = AV_PIX_FMT_YUV420P16
                eq[AV_PIX_FMT_P016BE] = AV_PIX_FMT_YUV420P16
                eq[AV_PIX_FMT_D3D11] = -1
                eq[AV_PIX_FMT_GRAY9BE] = AV_PIX_FMT_GRAY9
                eq[AV_PIX_FMT_GRAY9LE] = AV_PIX_FMT_GRAY9
                eq[AV_PIX_FMT_GBRPF32BE] = AV_PIX_FMT_GBRPF32
                eq[AV_PIX_FMT_GBRPF32LE] = AV_PIX_FMT_GBRPF32
                eq[AV_PIX_FMT_GBRAPF32BE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_GBRAPF32LE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_DRM_PRIME] = -1
                eq[AV_PIX_FMT_OPENCL] = -1
                eq[AV_PIX_FMT_GRAY14BE] = AV_PIX_FMT_GRAY14
                eq[AV_PIX_FMT_GRAY14LE] = AV_PIX_FMT_GRAY14
                eq[AV_PIX_FMT_GRAYF32BE] = AV_PIX_FMT_GRAYF32
                eq[AV_PIX_FMT_GRAYF32LE] = AV_PIX_FMT_GRAYF32
                eq[AV_PIX_FMT_YUVA422P12BE] = AV_PIX_FMT_YUVA422P12
                eq[AV_PIX_FMT_YUVA422P12LE] = AV_PIX_FMT_YUVA422P12
                eq[AV_PIX_FMT_YUVA444P12BE] = AV_PIX_FMT_YUVA444P12
                eq[AV_PIX_FMT_YUVA444P12LE] = AV_PIX_FMT_YUVA444P12
                eq[AV_PIX_FMT_NV24] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_NV42] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_VULKAN] = -1
                eq[AV_PIX_FMT_Y210BE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_Y210LE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_X2RGB10LE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_X2RGB10BE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_X2BGR10LE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_X2BGR10BE] = AV_PIX_FMT_GBRP10
                eq[AV_PIX_FMT_P210BE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_P210LE] = AV_PIX_FMT_YUV422P10
                eq[AV_PIX_FMT_P410BE] = AV_PIX_FMT_YUV444P10
                eq[AV_PIX_FMT_P410LE] = AV_PIX_FMT_YUV444P10
                eq[AV_PIX_FMT_P216BE] = AV_PIX_FMT_YUV422P16
                eq[AV_PIX_FMT_P216LE] = AV_PIX_FMT_YUV422P16
                eq[AV_PIX_FMT_P416BE] = AV_PIX_FMT_YUV444P16
                eq[AV_PIX_FMT_P416LE] = AV_PIX_FMT_YUV444P16
                eq[AV_PIX_FMT_VUYA] = AV_PIX_FMT_YUVA444P
                eq[AV_PIX_FMT_RGBAF16BE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_RGBAF16LE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_VUYX] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_P012LE] = AV_PIX_FMT_YUV420P12
                eq[AV_PIX_FMT_P012BE] = AV_PIX_FMT_YUV420P12
                eq[AV_PIX_FMT_Y212BE] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_Y212LE] = AV_PIX_FMT_YUV422P
                eq[AV_PIX_FMT_XV30BE] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_XV30LE] = AV_PIX_FMT_YUV444P
                eq[AV_PIX_FMT_XV36BE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_XV36LE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_RGBF32BE] = AV_PIX_FMT_GBRPF32
                eq[AV_PIX_FMT_RGBF32LE] = AV_PIX_FMT_GBRPF32
                eq[AV_PIX_FMT_RGBAF32BE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_RGBAF32LE] = AV_PIX_FMT_GBRAPF32
                eq[AV_PIX_FMT_P212BE] = AV_PIX_FMT_YUV422P12
                eq[AV_PIX_FMT_P212LE] = AV_PIX_FMT_YUV422P12
                eq[AV_PIX_FMT_P412BE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_P412LE] = AV_PIX_FMT_YUV444P12
                eq[AV_PIX_FMT_GBRAP14BE] = AV_PIX_FMT_GBRAP14
                eq[AV_PIX_FMT_GBRAP14LE] = AV_PIX_FMT_GBRAP14
                for (code in eq.indices)
                    if (eq[code] == -2)
                        throw NotImplementedError("@Developer: please add entry for $code to zimg-equiv pix fmt table.")
            }

        }

    }

}
