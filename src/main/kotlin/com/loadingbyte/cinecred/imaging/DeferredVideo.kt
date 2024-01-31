package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.DeferredVideo.Cache.Response
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.bytedeco.ffmpeg.global.avcodec.*
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.sumOf
import kotlin.concurrent.withLock
import kotlin.io.path.name
import kotlin.math.*


/**
 * The implementation assumes that once a [DeferredImage] has stopped playing, it will never play again. The code is
 * optimized accordingly. If this assumption is violated, the image will be materialized more often than it needs to be.
 */
class DeferredVideo private constructor(
    private val origResolution: Resolution,
    private val origFPS: FPS,
    private val resolutionScaling: Double,
    private val fpsScaling: Int,
    private val flows: MutableList<Flow>
) {

    val resolution: Resolution = if (resolutionScaling == 1.0) origResolution else Resolution(
        (origResolution.widthPx * resolutionScaling).roundToInt(),
        (origResolution.heightPx * resolutionScaling).roundToInt()
    )
    val fps: FPS = if (fpsScaling == 1) origFPS else origFPS.copy(numerator = origFPS.numerator * fpsScaling)

    // Convenient accessors:
    private val width get() = resolution.widthPx
    private val height get() = resolution.heightPx

    var numFrames: Int = -1
        get() {
            if (field == -1)
                field = flows.sumOf { it.numFrames(fpsScaling) }
            return field
        }
        private set

    constructor(resolution: Resolution, fps: FPS) : this(resolution, fps, 1.0, 1, ArrayList())

    fun copy(resolutionScaling: Double = 1.0, fpsScaling: Int = 1): DeferredVideo {
        require(resolutionScaling > 0.0)
        require(fpsScaling >= 1)
        return DeferredVideo(
            origResolution, origFPS,
            this.resolutionScaling * resolutionScaling, this.fpsScaling * fpsScaling,
            ArrayList(flows)
        )
    }

    /** Note that [numFrames] can be negative. */
    fun playBlank(numFrames: Int) {
        if (numFrames == 0) return
        flows.add(Flow.Blank(numFrames))
        this.numFrames = -1
    }

    fun playStatic(image: DeferredImage, numFrames: Int, shift: Double, alpha: Double) {
        if (numFrames <= 0) return
        playPhase(image, Phase.Static(numFrames, shift, alpha))
        this.numFrames = -1
    }

    fun playFade(image: DeferredImage, numFrames: Int, shift: Double, startAlpha: Double, stopAlpha: Double) {
        if (numFrames <= 0) return
        playPhase(image, Phase.Fade(numFrames, shift, startAlpha, stopAlpha))
        this.numFrames = -1
    }

    fun playScroll(
        image: DeferredImage, numFrames: Int, speed: Double, startShift: Double, stopShift: Double,
        initialAdvance: Double, alpha: Double
    ) {
        if (numFrames <= 0) return
        val newSection = Phase.Scroll.Section(numFrames, speed, startShift, stopShift, initialAdvance)
        val curFlow = flows.lastOrNull() as? Flow.DefImg
        if (image == curFlow?.image)
            (curFlow.phases.lastOrNull() as? Phase.Scroll)?.let { it.addSection(newSection); return }
        playPhase(image, Phase.Scroll(alpha).apply { addSection(newSection) })
        this.numFrames = -1
    }

    private fun playPhase(image: DeferredImage, phase: Phase) {
        val curFlow = flows.lastOrNull() as? Flow.DefImg
        if (image == curFlow?.image)
            curFlow.phases.add(phase)
        else
            flows.add(Flow.DefImg(image, mutableListOf(phase)))
    }

    fun collectTapeSpans(layers: List<DeferredImage.Layer>): List<TapeSpan> {
        val tapeTracker = TapeTracker<Unit>(this, layers)
        return tapeTracker.collectSpanFirstFrameIndices().flatMap(tapeTracker::query).map { resp ->
            TapeSpan(resp.embeddedTape, resp.firstFrameIdx, resp.lastFrameIdx, resp.timecode)
        }
    }

    private val instructions: List<Instruction> by lazy {
        val list = mutableListOf<Instruction>()
        var firstFrameIdx = 0
        for (flow in flows)
            when (flow) {
                is Flow.Blank ->
                    firstFrameIdx += flow.numFrames(fpsScaling)
                is Flow.DefImg -> {
                    val insnImage = flow.image.copy(universeScaling = resolutionScaling)
                    val insn = makeInstruction(firstFrameIdx, insnImage, flow.phases)
                    firstFrameIdx = insn.lastFrameIdx + 1
                    list.add(insn)
                }
            }
        // This ordering is expected by the Cache. We must sort here because playBlank() accepts negative numFrames.
        list.sortBy(Instruction::firstFrameIdx)
        list
    }

    private fun makeInstruction(firstFrameIdx: Int, image: DeferredImage, phases: List<Phase>): Instruction {
        val numFramesPerPhase = IntArray(phases.size) { phaseIdx -> phases[phaseIdx].numFrames(fpsScaling) }
        val insnNumFrames = numFramesPerPhase.sum()
        val insnShifts = DoubleArray(insnNumFrames)
        val insnAlphas = DoubleArray(insnNumFrames)
        var i = 0
        for ((phaseIdx, phase) in phases.withIndex())
            for (frameIdx in 0..<numFramesPerPhase[phaseIdx]) {
                insnShifts[i] = phase.shift(fpsScaling, frameIdx) * resolutionScaling
                insnAlphas[i] = phase.alpha(fpsScaling, frameIdx)
                i++
            }
        return Instruction(firstFrameIdx, firstFrameIdx + insnNumFrames - 1, image, insnShifts, insnAlphas)
    }


    companion object {

        private fun materialize(
            dst: BufferedImage, src: DeferredImage,
            layers: List<DeferredImage.Layer>, y: Double, alpha: Double
        ) {
            val shiftedSrc = if (y == 0.0) src else
                DeferredImage(dst.width.toDouble(), dst.height.toDouble().toY())
                    .apply { drawDeferredImage(src, y = y.toY()) }
            shiftedSrc.materialize(dst, layers, alphaComposite(alpha))
        }

        private fun transfer(
            dst: BufferedImage, src: BufferedImage,
            ix: Int = 0, iy: Int = 0, dy: Double = 0.0, res: Resolution? = null, alpha: Double = 1.0,
            draft: Boolean = false
        ) {
            dst.withG2 { g2 ->
                if (!draft)
                    g2.setHighQuality()
                alphaComposite(alpha)?.let(g2::setComposite)
                if (dy != 0.0)
                    g2.translate(0.0, dy)
                if (res == null) {
                    g2.drawImage(src, ix, iy, null)
                } else
                    g2.drawImage(src, ix, iy, res.widthPx, res.heightPx, null)
            }
        }

        private fun alphaComposite(alpha: Double) =
            if (alpha == 1.0) null else AlphaComposite.SrcOver.derive(alpha.toFloat())

    }


    class TapeSpan(
        val embeddedTape: Tape.Embedded,
        val firstFrameIdx: Int,
        val lastFrameIdx: Int,
        val firstReadTimecode: Timecode
    )


    private sealed interface Flow {

        fun numFrames(fpsScaling: Int): Int

        class Blank(private val numFrames: Int) : Flow {
            override fun numFrames(fpsScaling: Int) = numFrames * fpsScaling
        }

        class DefImg(val image: DeferredImage, val phases: MutableList<Phase>) : Flow {
            override fun numFrames(fpsScaling: Int) = phases.sumOf { it.numFrames(fpsScaling) }
        }

    }


    private interface Phase {

        fun numFrames(fpsScaling: Int): Int
        fun shift(fpsScaling: Int, frameIdx: Int): Double
        fun alpha(fpsScaling: Int, frameIdx: Int): Double

        class Static(
            private val numFrames: Int,
            private val shift: Double,
            private val alpha: Double
        ) : Phase {
            override fun numFrames(fpsScaling: Int) = numFrames * fpsScaling
            override fun shift(fpsScaling: Int, frameIdx: Int) = shift
            override fun alpha(fpsScaling: Int, frameIdx: Int) = alpha
        }

        class Fade(
            private val numFrames: Int,
            private val shift: Double,
            private val startAlpha: Double,
            private val stopAlpha: Double
        ) : Phase {
            override fun numFrames(fpsScaling: Int) = numFrames * fpsScaling
            override fun shift(fpsScaling: Int, frameIdx: Int) = shift
            // Choose alpha such that the fade sequence contains neither full startAlpha nor full stopAlpha.
            override fun alpha(fpsScaling: Int, frameIdx: Int) =
                startAlpha + (stopAlpha - startAlpha) * (frameIdx + 1) / (numFrames(fpsScaling) + 1)
        }

        class Scroll(private val alpha: Double) : Phase {

            class Section(
                val numFrames: Int,
                val speed: Double,
                val startShift: Double,
                val stopShift: Double,
                val initialAdvance: Double
            ) {
                var startFrames = 0.0
            }

            private val sections = mutableListOf<Section>()

            fun addSection(section: Section) {
                section.startFrames = sections.sumOf(Section::numFrames) - section.initialAdvance
                sections.add(section)
            }

            override fun numFrames(fpsScaling: Int): Int {
                var n = sections.sumOf(Section::numFrames) * fpsScaling
                if (fpsScaling != 1) {
                    val stopShift = sections.last().stopShift
                    while (shift(fpsScaling, n + 1) < stopShift - 0.001)
                        n++
                }
                return n
            }

            override fun shift(fpsScaling: Int, frameIdx: Int): Double {
                val unscaledFrameIdx = (frameIdx - (fpsScaling - 1)) / fpsScaling.toDouble()
                val sec = sections.firstOrNull { unscaledFrameIdx < it.startFrames + it.numFrames } ?: sections.last()
                return sec.startShift + sec.speed * (unscaledFrameIdx - sec.startFrames)
            }

            override fun alpha(fpsScaling: Int, frameIdx: Int) = alpha

        }

    }


    private class Instruction(
        val firstFrameIdx: Int,
        val lastFrameIdx: Int,
        val image: DeferredImage,
        val shifts: DoubleArray,
        val alphas: DoubleArray
    )


    abstract class BufferedImageBackend(
        private val video: DeferredVideo,
        private val staticLayers: List<DeferredImage.Layer>,
        tapeLayers: List<DeferredImage.Layer>,
        private val draft: Boolean = false,
        sequentialAccess: Boolean = false,
        preloading: Boolean = false
    ) {

        private val cache = object : Cache<BufferedImage>(video, draft, sequentialAccess, preloading) {
            override fun createRender(image: DeferredImage, shift: Double, height: Int): BufferedImage {
                val render = createIntermediateImage(video.width, height)
                DeferredImage(render.width.toDouble(), render.height.toDouble().toY())
                    .apply { drawDeferredImage(image, y = (-shift).toY()) }
                    .materialize(render, staticLayers)
                return render
            }
        }

        private val tapeTracker = TapeTracker<Unit>(video, tapeLayers)

        /**
         * This method must be overwritten by users of this class who exactly know onto what kind of [Graphics2D] object
         * they will later draw their frames. The new image must support alpha.
         */
        protected abstract fun createIntermediateImage(width: Int, height: Int): BufferedImage

        fun preloadFrame(frameIdx: Int) {
            cache.query(frameIdx)
        }

        fun materializeFrame(bufImage: BufferedImage, frameIdx: Int) {
            // Get the static pages from the cache and put them onto the buffered image.
            for (resp in cache.query(frameIdx))
                when (resp) {
                    is Response.Image ->
                        materialize(bufImage, resp.image, staticLayers, -resp.shift, resp.alpha)
                    is Response.AlignedRender ->
                        transfer(bufImage, resp.render, iy = -resp.shift, alpha = resp.alpha, draft = draft)
                    is Response.UnalignedRender ->
                        transfer(bufImage, resp.render, dy = -resp.shift, alpha = resp.alpha, draft = draft)
                }

            // Get the correct preview frame for each visible tape and put them onto the buffered image.
            for (resp in tapeTracker.query(frameIdx)) {
                require(draft) { "The BufferedImageBackend can only embed tapes when in draft mode." }
                val previewFrame = try {
                    resp.embeddedTape.tape.getPreviewFrame(resp.timecode)
                } catch (_: Exception) {
                    // In case of errors, draw a missing media placeholder instead.
                    bufImage.withG2 { g2 ->
                        val (w, h) = resp.resolution
                        alphaComposite(resp.alpha)?.let(g2::setComposite)
                        g2.paint = GradientPaint(
                            0f, resp.y.toFloat(), Tape.MISSING_MEDIA_TOP_COLOR,
                            0f, (resp.y + h).toFloat(), Tape.MISSING_MEDIA_BOT_COLOR
                        )
                        g2.fillRect(resp.x, resp.y, w, h)
                    }
                    continue
                }
                if (previewFrame != null)
                    transfer(
                        bufImage, previewFrame,
                        ix = resp.x, iy = resp.y, res = resp.resolution, alpha = resp.alpha, draft = draft
                    )
            }
        }

    }


    class BitmapBackend(
        video: DeferredVideo,
        private val staticLayers: List<DeferredImage.Layer>,
        tapeLayers: List<DeferredImage.Layer>,
        private val grounding: Color?,
        private val spec: Bitmap.Spec
    ) : AutoCloseable {

        init {
            require(video.resolution == spec.resolution)
            require(spec.content != Bitmap.Content.ONLY_TOP_FIELD && spec.content != Bitmap.Content.ONLY_BOT_FIELD)
        }

        private val numFrames = video.numFrames
        private val pixelFormat get() = spec.representation.pixelFormat
        private val renderSpec = spec.copy(scan = Bitmap.Scan.PROGRESSIVE, content = Bitmap.Content.PROGRESSIVE_FRAME)

        private val workWidth = closestWorkResolution(spec.resolution.widthPx, pixelFormat.hChromaSub)
        private val workHeight = closestWorkResolution(spec.resolution.heightPx, pixelFormat.vChromaSub)
        private val workResolution = Resolution(workWidth, workHeight)
        private val workResolutionSpec = spec.copy(resolution = workResolution)
        private val workResolutionRenderSpec = renderSpec.copy(resolution = workResolution)
        private val workResolutionRenderConverter = Image2BitmapConverter(workResolutionRenderSpec)

        private val blankBitmap = Bitmap.allocate(workResolutionRenderSpec).also {
            workResolutionRenderConverter.convert(drawImage(workWidth, workHeight, grounding) {}, it)
        }

        private val cache: Cache<Render>
        private val tapeTracker: TapeTracker<TapeUserData>

        init {
            val effectiveVideo = if (spec.scan == Bitmap.Scan.PROGRESSIVE) video else video.copy(fpsScaling = 2)
            cache = object : Cache<Render>(effectiveVideo, draft = false, sequentialAccess = true, preloading = false) {
                override fun createRender(image: DeferredImage, shift: Double, height: Int): Render {
                    val h = closestWorkResolution(height, pixelFormat.vChromaSub)
                    val transparentBufImage = BufferedImage(workWidth, h, BufferedImage.TYPE_4BYTE_ABGR)
                    materialize(transparentBufImage, image, staticLayers, -shift, 1.0)
                    val groundedBufImage = if (grounding == null) transparentBufImage else
                        drawImage(workWidth, h, grounding) { g2 -> g2.drawImage(transparentBufImage, 0, 0, null) }
                    val groundedBitmap = Bitmap.allocate(renderSpec.copy(resolution = Resolution(workWidth, h)))
                    (if (h == workHeight) workResolutionRenderConverter else Image2BitmapConverter(groundedBitmap.spec))
                        .convert(groundedBufImage, groundedBitmap)
                    return Render(transparentBufImage, groundedBitmap)
                }
            }
            tapeTracker = TapeTracker(effectiveVideo, tapeLayers)
        }

        private var frameIdx = -1

        /** The returned bitmap is permitted to be [Bitmap.close]d. */
        fun materializeNextFrame(): Bitmap? {
            if (++frameIdx >= numFrames)
                return null

            val out: Bitmap
            val (width, height) = spec.resolution
            if (spec.scan == Bitmap.Scan.PROGRESSIVE) {
                val (bitmap, writable, shift) = obtainBitmap(frameIdx)
                val tapeResponses = tapeTracker.query(frameIdx)
                out = when {
                    writable -> bitmap
                    tapeResponses.isEmpty() -> bitmap.view(0, shift, width, height, 1)
                    else -> Bitmap.allocate(workResolutionSpec)
                        .apply { blit(bitmap, 0, shift, workWidth, workHeight, 0, 0, 1) }
                }
                for (resp in tapeResponses) {
                    val userData = takeTapeUserData(resp)
                    val frame = userData.reader.read(resp.timecode).bitmap
                    userData.compositor.compose(out, frame, resp.x, resp.y, 1, 0, resp.alpha)
                    dropTapeUserData(resp, frameIdx)
                }
            } else {
                val fstSrcParity = if (spec.scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST) 0 else 1
                val sndSrcParity = 1 - fstSrcParity
                val fstDstParity = if (spec.content == Bitmap.Content.INTERLEAVED_FIELDS) fstSrcParity else sndSrcParity
                val sndDstParity = 1 - fstDstParity
                // It is important to query the earlier frame first, because as sequentialAccess is true, the cache is
                // free to discard it as soon as a later frame is queried.
                val (fst, fstWritable, fstShift) = obtainBitmap(frameIdx * 2)
                val (snd, sndWritable, sndShift) = obtainBitmap(frameIdx * 2 + 1)
                out = Bitmap.allocate(workResolutionSpec)
                out.blit(fst, 0, fstShift + fstSrcParity, workWidth, workHeight - 1, 0, fstDstParity, 2)
                out.blit(snd, 0, sndShift + sndSrcParity, workWidth, workHeight - 1, 0, sndDstParity, 2)
                if (fstWritable) fst.close()
                if (sndWritable) snd.close()
                overlayInterlacedTapes(out, frameIdx * 2, fstSrcParity, fstDstParity)
                overlayInterlacedTapes(out, frameIdx * 2 + 1, sndSrcParity, sndDstParity)
            }

            return if (width == workWidth && height == workHeight) out else
                out.view(0, 0, width, height, 1).also { out.close() }
        }

        private data class Obtained(val bitmap: Bitmap, val writable: Boolean, val shift: Int)

        private fun obtainBitmap(frameIdx: Int): Obtained {
            val responses = cache.query(frameIdx)
            val r = responses.singleOrNull()
            return when {
                responses.isEmpty() ->
                    Obtained(blankBitmap, writable = false, shift = 0)
                r is Response.AlignedRender && r.alpha == 1.0 ->
                    Obtained(r.render.bitmap, writable = false, shift = r.shift)
                else -> {
                    val bufImage = drawImage(workWidth, workHeight, grounding) {}
                    for (resp in cache.query(frameIdx))
                        when (resp) {
                            is Response.Image ->
                                materialize(bufImage, resp.image, staticLayers, -resp.shift, resp.alpha)
                            is Response.AlignedRender ->
                                transfer(bufImage, resp.render.bufImage, iy = -resp.shift, alpha = resp.alpha)
                            is Response.UnalignedRender -> throw IllegalStateException()
                        }
                    val bitmap = Bitmap.allocate(workResolutionRenderSpec)
                    workResolutionRenderConverter.convert(bufImage, bitmap)
                    Obtained(bitmap, writable = true, shift = 0)
                }
            }
        }

        private inline fun drawImage(
            width: Int, height: Int, grounding: Color?, block: (Graphics2D) -> Unit
        ): BufferedImage {
            val type = if (grounding == null) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR
            return BufferedImage(width, height, type).withG2 { g2 ->
                g2.setHighQuality()
                if (grounding != null) {
                    g2.color = grounding
                    g2.fillRect(0, 0, width, height)
                }
                block(g2)
            }
        }

        private fun overlayInterlacedTapes(out: Bitmap, frameIdx: Int, srcParity: Int, dstParity: Int) {
            for (resp in tapeTracker.query(frameIdx)) {
                // Find which tape field to overlay onto the currently written output field.
                val overlayParity = if (resp.y.mod(2) == srcParity) 0 else 1
                // Find the y coordinate inside the currently written output field where the tape field should begin.
                val y = if (srcParity == 0) ceilDiv(resp.y, 2) else floorDiv(resp.y, 2)

                val userData = takeTapeUserData(resp)
                if (userData.reader.spec.scan == Bitmap.Scan.PROGRESSIVE) {
                    // When the tape is progressive, treat the progressive frames as if they consist of two fields.
                    val frame = userData.reader.read(resp.timecode).bitmap
                    userData.compositor.compose(out, frame, resp.x, dstParity + y * 2, 2, overlayParity, resp.alpha)
                } else {
                    // When the tape is interlaced, bring overlay fields into action alternatingly.
                    val fileSeq = resp.embeddedTape.tape.fileSeq
                    val frame = userData.reader.read(resp.timecode).bitmap
                    if (userData.topField == null) {
                        // If the tape has just appeared, immediately bring in both fields from the tape's first frame.
                        userData.topField = frame.topFieldView()
                        userData.botField = frame.botFieldView()
                    } else {
                        // Push the first field of "frame" if "resp.timecode" lies in the first field's time range.
                        // Otherwise, push the second field of "frame".
                        val tff = userData.reader.spec.scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                        val timecodeRefersToFirstField =
                            if (fileSeq) resp.fileSeqFirstField else userData.reader.didReadFirstField()
                        if (tff == timecodeRefersToFirstField)
                            userData.topField = frame.topFieldView()
                        else
                            userData.botField = frame.botFieldView()
                    }
                    // Perform the overlaying.
                    out.view(0, dstParity, spec.resolution.widthPx, spec.resolution.heightPx - 1, 2).use { field ->
                        if (overlayParity == 0)
                            userData.topFieldCompositor.compose(field, userData.topField!!, resp.x, y, 1, 0, resp.alpha)
                        else
                            userData.botFieldCompositor.compose(field, userData.botField!!, resp.x, y, 1, 0, resp.alpha)
                    }
                }
                dropTapeUserData(resp, frameIdx)
            }
        }

        /**
         * This function rounds numbers up to the next multiple of chromaSub (which is given in log2).
         *
         * When chroma subsampling is enabled, the respective image dimension must have such a multiple size, or
         * otherwise zimg would throw an error because it can't evenly divide the chroma samples along that dimension.
         * Hence, while working on the image, its resolution should be a multiple. This function needs to be called on
         * the original image resolution to obtain one that is accepted by zimg.
         *
         * Notice that all of this only affects the working stage. The final encode can exhibit odd resolutions if the
         * codec permits that.
         */
        private fun closestWorkResolution(size: Int, chromaSub: Int): Int {
            if (chromaSub < 1)
                return size
            val n = 1 shl chromaSub
            return size + n - (size and (n - 1))
        }

        private fun takeTapeUserData(resp: TapeTracker.Response<TapeUserData>): TapeUserData {
            resp.userData?.let { return it }
            return TapeUserData(spec, resp).also { resp.userData = it }
        }

        private fun dropTapeUserData(resp: TapeTracker.Response<TapeUserData>, frameIdx: Int) {
            // Close the reader early and dereference the user data if we no longer need it. Notice that in case of
            // errors, the close() function on this object will be called and will close all remaining readers.
            if (frameIdx >= /* use >= instead of == just to be sure */ resp.lastFrameIdx) {
                resp.userData?.run { reader.close() }
                resp.userData = null
            }
        }

        override fun close() {
            for (userData in tapeTracker.collectUserData())
                userData.reader.close()
        }

        private class Render(val bufImage: BufferedImage, val bitmap: Bitmap)

        private class TapeUserData(canvasSpec: Bitmap.Spec, resp: TapeTracker.Response<*>) {

            val reader = resp.embeddedTape.tape.SequentialReader(resp.timecode /* the span's first timecode */)
            lateinit var compositor: BitmapCompositor; private set
            lateinit var topFieldCompositor: BitmapCompositor; private set
            lateinit var botFieldCompositor: BitmapCompositor; private set

            var topField: Bitmap? = null
                set(value) {
                    field?.close()
                    field = value
                }
            var botField: Bitmap? = null
                set(value) {
                    field?.close()
                    field = value
                }

            init {
                setupSafely({
                    val canvasRepr = canvasSpec.representation
                    val tapeSpec = reader.spec
                    val tapeIsProgressive = tapeSpec.scan == Bitmap.Scan.PROGRESSIVE
                    if (canvasSpec.scan == Bitmap.Scan.PROGRESSIVE || tapeIsProgressive) {
                        // If the tape is interlaced, make it have even height in the final output.
                        val composedOverlayRes = if (tapeIsProgressive) resp.resolution else
                            resp.resolution.copy(heightPx = resp.resolution.heightPx / 2 * 2)
                        compositor = BitmapCompositor(canvasRepr, tapeSpec, composedOverlayRes)
                    } else {
                        val tapeFrameRes = tapeSpec.resolution
                        require(tapeFrameRes.heightPx % 2 == 0) {
                            "The interlaced tape '${resp.embeddedTape.tape.fileOrDir.name}' must have even height."
                        }
                        val tapeFieldSpec =
                            tapeSpec.copy(resolution = tapeFrameRes.copy(heightPx = tapeFrameRes.heightPx / 2))
                        val composedFieldRes = resp.resolution.copy(heightPx = resp.resolution.heightPx / 2)
                        topFieldCompositor = BitmapCompositor(
                            canvasRepr, tapeFieldSpec.copy(content = Bitmap.Content.ONLY_TOP_FIELD), composedFieldRes
                        )
                        botFieldCompositor = BitmapCompositor(
                            canvasRepr, tapeFieldSpec.copy(content = Bitmap.Content.ONLY_BOT_FIELD), composedFieldRes
                        )
                    }
                }, reader::close)
            }

        }

    }


    private abstract class Cache<R>(
        private val video: DeferredVideo,
        /** If true, the cache generates [Response.UnalignedRender] responses. */
        private val draft: Boolean,
        /** If true, queries are expected with increasing frame indices. The cache is managed accordingly. */
        private val sequentialAccess: Boolean,
        /** If true, renders near the last queried frame are precomputed in a background thread. */
        private val preloading: Boolean
    ) {

        private val chunkSpacing: Int
        private val chunks = mutableListOf<Chunk>()
        private val firstChunkIndices = IntArray(video.instructions.size)
        private val lastChunkIndices = IntArray(video.instructions.size)

        init {
            // We make the chunks larger than the spacing between two chunks so that a chunk can be scrolled for some
            // time and then immediately the next chunk can be swapped in, without the need to stitch the two chunks.
            // We further add 1 to the height to make room for the micro shift (which is between 0 and 1) at the bottom.
            val maxChunkHeight = max(video.height + MIN_CHUNK_BUFFER, MAX_CHUNK_PIXELS / video.width)
            chunkSpacing = maxChunkHeight - (video.height + 1)

            // Declare chunks for all instructions.
            for ((insnIdx, insn) in video.instructions.withIndex()) {
                // Find the min and max shifts in the current instruction. Also collect all distinct micro shifts:
                // a micro shift is the deviation from an integer shift, and hence lies between 0 and 1. In draft mode,
                // we do not cache renders for all micro shifts, so instead, we just use only the 0 micro shift.
                var minShift = 0.0
                var maxShift = 0.0
                var microShifts: DoubleArray? = if (draft) doubleArrayOf(0.0) else DoubleArray(MAX_MICRO_SHIFTS)
                var numMicroShifts = 0  // Only for when draft is false.
                for (shift in insn.shifts) {
                    minShift = min(minShift, shift)
                    maxShift = max(maxShift, shift)
                    if (!draft && microShifts != null) {
                        val microShift = shift - floor(shift)
                        // Check whether this micro shift is not already present in our collection.
                        if (!microShifts.anyBetween(0, numMicroShifts) { abs(it - microShift) < EPS }) {
                            // If we have exceeded the maximum number of allowed micro shifts, there's probably a
                            // very odd scroll speed at play. Since we can't cache that many micro shifts, we
                            // disable caching and later resort to directly drawing deferred images.
                            if (numMicroShifts == MAX_MICRO_SHIFTS)
                                microShifts = null
                            else
                                microShifts[numMicroShifts++] = microShift
                        }
                    }
                }
                if (!draft && microShifts != null)
                    microShifts = microShifts.copyOf(numMicroShifts)

                // This is the smallest vertical area that covers the requests of all instructions.
                val minY = floor(minShift).toInt()
                val maxY = ceil(maxShift).toInt() + video.height

                // Declare chunks to cover exactly the area between min/maxY (but not any more space), and remember the
                // first and last chunk indices for the current page.
                firstChunkIndices[insnIdx] = chunks.size
                var chunkShift = minY - chunkSpacing
                do {
                    chunkShift += chunkSpacing
                    val chunkHeight = min(maxChunkHeight, maxY - chunkShift)
                    chunks.add(Chunk(chunkShift, chunkHeight, insn.image, microShifts))
                } while (chunkShift + chunkHeight < maxY)
                lastChunkIndices[insnIdx] = chunks.size - 1
            }
        }

        protected abstract fun createRender(image: DeferredImage, shift: Double, height: Int): R

        fun query(frameIdx: Int): List<Response<R>> = buildList {
            var first = true
            for ((insnIdx, insn) in video.instructions.withIndex())
                if (frameIdx in insn.firstFrameIdx..insn.lastFrameIdx)
                    add(queryForInstruction(frameIdx, insnIdx, insn, first.also { first = false }))
        }

        private fun queryForInstruction(frameIdx: Int, insnIdx: Int, insn: Instruction, first: Boolean): Response<R> {
            val relFrameIdx = frameIdx - insn.firstFrameIdx
            val shift = insn.shifts[relFrameIdx]
            val alpha = insn.alphas[relFrameIdx]

            val firstChunkIdx = firstChunkIndices[insnIdx]
            val lastChunkIdx = lastChunkIndices[insnIdx]
            val chunkIdx = (firstChunkIdx + ((floor(shift).toInt() - chunks[firstChunkIdx].shift) / chunkSpacing))
                .coerceIn(firstChunkIdx, lastChunkIdx)  // Should not be necessary, but better be safe.

            // In sequential mode, lower frame indices will no longer be queried. Also, recall that instructions are
            // ordered by firstFrameIdx. Hence, if this is the first instruction that encompasses the current frame
            // index, we know that previous instructions will no longer be queried. So we can free all data cached for
            // the previous instructions.
            // Because the chunks are in the same order as the instructions, it is similarly safe to free the previous
            // chunks' cached renders. In addition to freeing chunks of the previous instructions, this also frees
            // completed chunks of this instruction.
            // As a final note, we cannot do all of this if "first" is false because instructions may overlap.
            if (sequentialAccess && first)
                for (i in chunkIdx - 1 downTo 0) {
                    val chunk = chunks[i]
                    chunk.withLock { chunk.microShiftedRenders.also { chunk.microShiftedRenders = null } }
                    // Stop when we encounter a chunk that was once loaded but has since been explicitly nulled before.
                        ?: break
                }

            // In preloading mode, queue preloading of the surrounding chunks in a background thread.
            if (preloading) {
                chunks.getOrNull(chunkIdx + 1)?.let(::queueChunkPreloading)
                if (!sequentialAccess)
                    chunks.getOrNull(chunkIdx - 1)?.let(::queueChunkPreloading)
            }

            val chunk = chunks[chunkIdx]
            // Get the cached renders for the current chunk, or compute them now if they are not cached.
            val microShiftedRenders = loadChunk(chunk)
            return when {
                // If the cache is disabled for this chunk, directly pass the deferred image to the consumer.
                // This is slower than using cached renders, but it's our only option.
                microShiftedRenders == null ->
                    Response.Image(chunk.image, shift, alpha)
                // In draft mode, we only cache a single render, and it doesn't have micro shift, so the consumer
                // has to fractionally shift it.
                draft ->
                    Response.UnalignedRender(microShiftedRenders.single(), shift - chunk.shift, alpha)
                // In non-draft mode, determine the micro shift for the given shift, select the corresponding cached
                // render, and pass it to the consumer with only integer shifting.
                else -> {
                    val microShift = shift - floor(shift)
                    val imageIdx = chunk.microShifts!!.indexOfFirst { abs(it - microShift) < EPS }
                    Response.AlignedRender(microShiftedRenders[imageIdx], floor(shift).toInt() - chunk.shift, alpha)
                }
            }
        }

        private fun queueChunkPreloading(chunk: Chunk) {
            if (chunk.microShifts != null &&
                chunk.withLock(chunk::microShiftedRenders).let { it == null || it.refersTo(null) }
            ) PRELOADING_EXECUTOR.submit(throwableAwareTask { loadChunk(chunk) })
        }

        // Apart from code design, there is an important reason for why this method returns the renders: to ensure that
        // there is a strong reference to them. Otherwise, the garbage collector could discard them immediately when the
        // method returns.
        private fun loadChunk(chunk: Chunk): List<R>? {
            // If the chunk should not cache renders, return null.
            if (chunk.microShifts == null)
                return null
            // The following operations access and modify the mutable microShiftedRenders variable, so we must be sure
            // to hold the chunk lock before proceeding.
            return chunk.withLock { doLoadChunk(chunk) }
        }

        private fun doLoadChunk(chunk: Chunk): List<R> {
            // If the chunk was already materialized and the renders are still in the cache, return them.
            chunk.microShiftedRenders?.get()?.let { return it }
            // Otherwise, materialize the renders now and cache them.
            val microShiftedRenders = chunk.microShifts!!.map { microShift ->
                createRender(chunk.image, shift = chunk.shift + microShift, chunk.height)
            }
            chunk.microShiftedRenders = SoftReference(microShiftedRenders)
            return microShiftedRenders
        }


        companion object {

            private const val EPS = 0.001
            private const val MIN_CHUNK_BUFFER = 200
            private const val MAX_CHUNK_PIXELS = 20_000_000
            private const val MAX_MICRO_SHIFTS = 16

            private val PRELOADING_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "DeferredVideoPreloader").apply { isDaemon = true }
            }

        }


        sealed interface Response<R> {
            class Image<R>(val image: DeferredImage, val shift: Double, val alpha: Double) : Response<R>
            class AlignedRender<R>(val render: R, val shift: Int, val alpha: Double) : Response<R>
            class UnalignedRender<R>(val render: R, val shift: Double, val alpha: Double) : Response<R>
        }


        private inner class Chunk(
            val shift: Int,
            val height: Int,
            val image: DeferredImage,
            val microShifts: DoubleArray?
        ) {
            private val lock = if (preloading) ReentrantLock() else null
            var microShiftedRenders: SoftReference<List<R>>? = null

            inline fun <T> withLock(block: () -> T): T = if (lock == null) block() else lock.withLock(block)
        }

    }


    private class TapeTracker<U>(private val video: DeferredVideo, layers: List<DeferredImage.Layer>) {

        private val spans: List<Span<U>> = buildList {
            for (insn in video.instructions)
                for (placed in insn.image.collectPlacedTapes(layers)) {
                    val placedH = placed.embeddedTape.resolution.heightPx
                    // Notice that we add/subtract an epsilon to avoid inaccuracies when placed.y is a whole number.
                    val relFirstFrameIdx = findRelFirstFrameIdx(insn.shifts, placed.y - video.height + 0.001)
                    if (relFirstFrameIdx < 0)
                        continue
                    val relLastFrameIdx = findRelLastFrameIdx(insn.shifts, placed.y + placedH - 0.001)
                    if (relLastFrameIdx < 0)
                        continue
                    val rangeFrames = when (val rangeDiff = placed.embeddedTape.range.run { endExclusive - start }) {
                        is Timecode.Frames -> rangeDiff.frames * video.fpsScaling
                        is Timecode.Clock -> rangeDiff.toFramesCeil(video.fps).frames
                        else -> throw IllegalStateException("Wrong timecode format: ${rangeDiff.javaClass.simpleName}")
                    }
                    var firstFrameIdx =
                        insn.firstFrameIdx + relFirstFrameIdx + placed.embeddedTape.leftMarginFrames * video.fpsScaling
                    var lastFrameIdx =
                        insn.firstFrameIdx + relLastFrameIdx - placed.embeddedTape.rightMarginFrames * video.fpsScaling
                    val extraneousFrames = (lastFrameIdx - firstFrameIdx + 1) - rangeFrames
                    if (extraneousFrames > 0)
                        when (placed.embeddedTape.align) {
                            Tape.Embedded.Align.START -> lastFrameIdx -= extraneousFrames
                            Tape.Embedded.Align.END -> firstFrameIdx += extraneousFrames
                            Tape.Embedded.Align.MIDDLE -> {
                                val half = extraneousFrames / 2
                                firstFrameIdx += half
                                lastFrameIdx -= extraneousFrames - half  // account for odd numbers
                            }
                        }
                    if (firstFrameIdx > lastFrameIdx)
                        continue
                    add(Span(insn, placed, firstFrameIdx, lastFrameIdx))
                }
        }

        private fun findRelFirstFrameIdx(shifts: DoubleArray, appearanceShift: Double): Int {
            var idx = shifts.binarySearch(appearanceShift)
            when {
                // The span doesn't start before the last shift.
                idx == -shifts.size - 1 -> return -1
                // -idx-1 marks the first shift larger than appearanceShift, which is where the span starts.
                idx < 0 -> return -idx - 1
                // If appearanceShift is found exactly, search for the first shift larger than it.
                else -> {
                    do {
                        idx++
                        if (idx >= shifts.size)
                            return -1
                    } while (shifts[idx] == appearanceShift)
                    return idx
                }
            }
        }

        private fun findRelLastFrameIdx(shifts: DoubleArray, disappearanceShift: Double): Int {
            val idx = shifts.binarySearch(disappearanceShift)
            return when {
                // The span stops even before the first shift.
                idx == 0 || idx == -0 - 1 -> -1
                // The span doesn't stop before the last shift.
                idx == -shifts.size - 1 -> shifts.lastIndex
                // -idx-1 respectively idx marks the first shift that no longer shows the span.
                idx < 0 -> -idx - 2
                else -> idx - 1
            }
        }

        fun query(frameIdx: Int): List<Response<U>> = buildList {
            for (span in spans)
                if (frameIdx in span.firstFrameIdx..span.lastFrameIdx) {
                    val pastFrames = frameIdx - span.firstFrameIdx
                    val futureFrames = span.lastFrameIdx - frameIdx

                    val start = span.embeddedTape.range.start
                    val endExcl = span.embeddedTape.range.endExclusive
                    var tmp = 0
                    val timecode = when {
                        span.embeddedTape.tape.fileSeq -> when (span.embeddedTape.align) {
                            Tape.Embedded.Align.START -> start + Timecode.Frames(pastFrames / video.fpsScaling)
                            Tape.Embedded.Align.END -> endExcl - Timecode.Frames(futureFrames / video.fpsScaling + 1)
                            Tape.Embedded.Align.MIDDLE -> {
                                tmp = (((start + endExcl) as Timecode.Frames).frames - 1) * video.fpsScaling +
                                        frameIdx * 2 - (span.firstFrameIdx + span.lastFrameIdx)
                                Timecode.Frames(tmp / (2 * video.fpsScaling))
                            }
                        }
                        else -> when (span.embeddedTape.align) {
                            Tape.Embedded.Align.START -> start + Timecode.Frames(pastFrames).toClock(video.fps)
                            Tape.Embedded.Align.END -> endExcl - Timecode.Frames(futureFrames).toClock(video.fps) -
                                    Timecode.Frames(1).toClock(video.origFPS)
                            Tape.Embedded.Align.MIDDLE ->
                                (start + endExcl - Timecode.Frames(1).toClock(video.origFPS)) / 2 +
                                        Timecode.Frames(frameIdx).toClock(video.fps) -
                                        Timecode.Frames(span.firstFrameIdx + span.lastFrameIdx).toClock(video.fps) / 2
                        }
                    }
                    val fileSeqFirstField = span.embeddedTape.tape.fileSeq && when (span.embeddedTape.align) {
                        Tape.Embedded.Align.START -> (pastFrames * 2 / video.fpsScaling) % 2 == 0
                        Tape.Embedded.Align.END -> (futureFrames * 2 / video.fpsScaling) % 2 == 1
                        Tape.Embedded.Align.MIDDLE -> (tmp / video.fpsScaling) % 2 == 0
                    }

                    val relFrameIdx = frameIdx - span.insn.firstFrameIdx
                    val x = span.placed.x.roundToInt()
                    val y = (span.placed.y - span.insn.shifts[relFrameIdx]).roundToInt()

                    var alpha = span.insn.alphas[relFrameIdx]
                    val leftFadeFrames = span.embeddedTape.leftFadeFrames * video.fpsScaling
                    val rightFadeFrames = span.embeddedTape.rightFadeFrames * video.fpsScaling
                    if (pastFrames < leftFadeFrames || futureFrames < rightFadeFrames)
                        alpha *= minOf(
                            1.0,
                            (pastFrames + 1) / (leftFadeFrames + 1).toDouble(),
                            (futureFrames + 1) / (rightFadeFrames + 1).toDouble()
                        )

                    add(Response(span, timecode, fileSeqFirstField, x, y, span.embeddedTape.resolution, alpha))
                }
        }

        fun collectSpanFirstFrameIndices(): SortedSet<Int> = spans.mapTo(TreeSet(), Span<U>::firstFrameIdx)
        fun collectUserData(): List<U> = spans.mapNotNull(Span<U>::userData)

        class Response<U>(
            private val _span: Span<U>,
            val timecode: Timecode,
            /**
             * For file sequence tapes, [timecode] is a [Timecode.Frames] which refers to a frame, but looses
             * information on which field inside that frame it refers to. This boolean retrofits that information.
             *
             * This field is only relevant when we're reading an interlaced image sequence and composite it onto an
             * interlaced output video. As the former can never happen at this moment in time, this is really useless
             * code at the moment, but we've kept it anyway in view of possible changes in the future.
             */
            val fileSeqFirstField: Boolean,
            val x: Int,
            val y: Int,
            val resolution: Resolution,
            val alpha: Double
        ) {
            val firstFrameIdx get() = _span.firstFrameIdx
            val lastFrameIdx get() = _span.lastFrameIdx
            val embeddedTape get() = _span.embeddedTape
            var userData: U? by _span::userData
        }

        private class Span<U>(
            val insn: Instruction,
            val placed: DeferredImage.PlacedTape,
            val firstFrameIdx: Int,
            val lastFrameIdx: Int
        ) {
            var userData: U? = null
            val embeddedTape get() = placed.embeddedTape
        }

    }

}
