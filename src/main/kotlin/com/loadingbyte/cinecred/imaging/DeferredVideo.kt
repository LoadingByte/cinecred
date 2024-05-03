package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.bytedeco.ffmpeg.global.avcodec.*
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.lang.ref.SoftReference
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
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
    private val roundShifts: Boolean,
    private var frozen: Boolean,
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

    constructor(resolution: Resolution, fps: FPS) : this(resolution, fps, 1.0, 1, false, false, ArrayList())

    fun copy(resolutionScaling: Double = 1.0, fpsScaling: Int = 1, roundShifts: Boolean = false): DeferredVideo {
        require(resolutionScaling > 0.0)
        require(fpsScaling >= 1)
        frozen = true
        return DeferredVideo(
            origResolution,
            origFPS,
            this.resolutionScaling * resolutionScaling,
            this.fpsScaling * fpsScaling,
            this.roundShifts || roundShifts,
            frozen = true,
            flows
        )
    }

    /** Note that [numFrames] can be negative. */
    fun playBlank(numFrames: Int) {
        if (numFrames == 0) return
        mutate()
        flows.add(Flow.Blank(numFrames))
    }

    fun playStatic(image: DeferredImage, numFrames: Int, shift: Double, alpha: Double) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.Static(numFrames, shift, alpha))
    }

    fun playFade(image: DeferredImage, numFrames: Int, shift: Double, startAlpha: Double, stopAlpha: Double) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.Fade(numFrames, shift, startAlpha, stopAlpha))
    }

    /** Note that [startShift] is exclusive, so the first displayed frame has shift `startShift + 1 * speed`. */
    fun playScroll(
        image: DeferredImage, numFrames: Int, speed: Double, startShift: Double, stopShift: Double, alpha: Double
    ) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.Scroll(numFrames, speed, startShift, stopShift, alpha))
    }

    private fun playPhase(image: DeferredImage, phase: Phase) {
        val curFlow = flows.lastOrNull() as? Flow.DefImg
        if (image == curFlow?.image)
            curFlow.phases.add(phase)
        else
            flows.add(Flow.DefImg(image, mutableListOf(phase)))
    }

    private fun mutate() {
        check(!frozen) { "This DeferredVideo is frozen because a copy was made or a backend was created." }
        numFrames = -1
    }

    fun collectTapeSpans(layers: List<DeferredImage.Layer>): List<TapeSpan> {
        val tapeTracker = TapeTracker<Unit>(this, layers)
        return tapeTracker.collectSpanFirstFrameIndices().flatMap(tapeTracker::query).map { resp ->
            TapeSpan(resp.embeddedTape, resp.firstFrameIdx, resp.lastFrameIdx, resp.timecode)
        }
    }

    private val instructions: List<Instruction> by lazy {
        frozen = true
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
                val shift = phase.shift(fpsScaling, frameIdx) * resolutionScaling
                insnShifts[i] = if (roundShifts) round(shift) else shift
                insnAlphas[i] = phase.alpha(fpsScaling, frameIdx)
                i++
            }
        return Instruction(firstFrameIdx, firstFrameIdx + insnNumFrames - 1, image, insnShifts, insnAlphas)
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

        class Scroll(
            private val numFrames: Int,
            private val speed: Double,
            private val startShift: Double,
            private val stopShift: Double,
            private val alpha: Double
        ) : Phase {

            override fun numFrames(fpsScaling: Int): Int {
                var n = numFrames * fpsScaling
                if (fpsScaling != 1)
                    while (shift(fpsScaling, n + 1) < stopShift - 0.001)
                        n++
                return n
            }

            override fun shift(fpsScaling: Int, frameIdx: Int) =
                startShift + speed * ((frameIdx + 1) / fpsScaling.toDouble())

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


    class BitmapBackend(
        video: DeferredVideo,
        private val staticLayers: List<DeferredImage.Layer>,
        tapeLayers: List<DeferredImage.Layer>,
        private val grounding: Color?,
        private val userSpec: Bitmap.Spec,
        private val cache: DeferredImage.CanvasMaterializationCache? = null,
        private val randomAccessDraftMode: Boolean = false
    ) : AutoCloseable {

        init {
            require(video.resolution == userSpec.resolution)
            require(userSpec.content.let { it != Bitmap.Content.ONLY_TOP_FIELD && it != Bitmap.Content.ONLY_BOT_FIELD })
        }

        private val numFrames = video.numFrames
        private val progressiveVideo = run {
            val interlaced = userSpec.scan != Bitmap.Scan.PROGRESSIVE
            if (!interlaced && !(randomAccessDraftMode && !video.roundShifts)) video else
                video.copy(fpsScaling = if (interlaced) 2 else 1, roundShifts = true)
        }
        private val userPixelFormat get() = userSpec.representation.pixelFormat
        private val canvasRepresentation = Canvas.compatibleRepresentation(
            ColorSpace.of(userSpec.representation.colorSpace!!.primaries, ColorSpace.Transfer.BLENDING)
        )

        private val workWidth = closestWorkResolution(userSpec.resolution.widthPx, userPixelFormat.hChromaSub)
        private val workHeight = closestWorkResolution(userSpec.resolution.heightPx, userPixelFormat.vChromaSub)
        private val workResolution = Resolution(workWidth, workHeight)

        private val userWorkSpec = userSpec.copy(resolution = workResolution)
        private val canvasWorkSpec = Bitmap.Spec(workResolution, canvasRepresentation)

        private val canvas2user = BitmapConverter(
            canvasWorkSpec, userWorkSpec, promiseOpaque = grounding != null, approxTransfer = randomAccessDraftMode
        )

        override fun close() {
            canvas2user.close()
            blankCanvasBitmap.close()
            blankUserBitmap.close()
            pageCache.close()
            for (userData in tapeTracker.collectUserData())
                userData.close()
        }

        fun preloadFrame(frameIdx: Int) {
            check(randomAccessDraftMode) { "Frame preloading is only supported in random access mode." }
            pageCache.query(frameIdx)
        }

        private var lastFrameIdx = -1

        /** The returned bitmap is permitted to be [Bitmap.close]d. */
        fun materializeFrame(frameIdx: Int): Bitmap? {
            if (!randomAccessDraftMode) {
                require(frameIdx >= lastFrameIdx) { "Backend is not in random access mode." }
                lastFrameIdx = frameIdx
            }
            if (frameIdx !in 0..<numFrames)
                return null

            val (bitmap, writable, shift) = when (userSpec.scan) {
                Bitmap.Scan.PROGRESSIVE -> obtainDynamicProgressiveFrame(frameIdx)
                else -> obtainDynamicInterlacedFrame(frameIdx)
            }
            val (width, height) = userSpec.resolution
            return if (writable && width == workWidth && height == workHeight) bitmap else
                bitmap.view(0, shift, width, height, 1).also { if (writable) bitmap.close() }
        }

        private data class Frame(val bitmap: Bitmap, val writable: Boolean, val shift: Int)

        /* ******************************************************
           ********** OBTAIN STATIC PROGRESSIVE FRAMES **********
           ****************************************************** */

        private class Render(val transparentCanvasBitmap: Bitmap, val userBitmap: Bitmap) : AutoCloseable {
            override fun close() {
                transparentCanvasBitmap.close()
                userBitmap.close()
            }
        }

        private val blankCanvasBitmap: Bitmap
        private val blankUserBitmap: Bitmap
        private val pageCache: PageCache<Render>

        init {
            blankCanvasBitmap = Bitmap.allocate(canvasWorkSpec)
            blankUserBitmap = Bitmap.allocate(userWorkSpec)
            if (grounding != null) {
                Canvas.forBitmap(blankCanvasBitmap).use { canvas -> canvas.fill(Canvas.Shader.Solid(grounding)) }
                canvas2user.convert(blankCanvasBitmap, blankUserBitmap)
            } else {
                blankCanvasBitmap.zero()
                blankUserBitmap.zero()
            }

            pageCache = object : PageCache<Render>(
                progressiveVideo,
                sequentialAccess = !randomAccessDraftMode,
                preloading = true
            ) {
                override fun createRenders(
                    image: DeferredImage, baseShift: Int, microShifts: DoubleArray, height: Int
                ): List<Render> {
                    val h = closestWorkResolution(height, userPixelFormat.vChromaSub)
                    val resolution = Resolution(workWidth, h)
                    val renderCanvasSpec = Bitmap.Spec(resolution, canvasRepresentation)
                    val renderUserSpec = Bitmap.Spec(resolution, userSpec.representation)
                    val transparentCanvasBitmaps = microShifts.map { ms ->
                        val bmp = Bitmap.allocate(renderCanvasSpec)
                        Canvas.forBitmap(bmp.zero()).use { canvas -> materialize(canvas, image, -(baseShift + ms)) }
                        bmp
                    }
                    BitmapConverter(
                        renderCanvasSpec, renderUserSpec,
                        promiseOpaque = grounding != null, approxTransfer = randomAccessDraftMode
                    ).use { converter ->
                        return transparentCanvasBitmaps.map { transparentCanvasBitmap ->
                            val userBitmap = Bitmap.allocate(renderUserSpec)
                            if (grounding == null)
                                converter.convert(transparentCanvasBitmap, userBitmap)
                            else
                                Bitmap.allocate(renderCanvasSpec).use { groundedCanvasBitmap ->
                                    Canvas.forBitmap(groundedCanvasBitmap).use { canvas ->
                                        canvas.fill(Canvas.Shader.Solid(grounding))
                                        canvas.drawImageFast(transparentCanvasBitmap)
                                    }
                                    converter.convert(groundedCanvasBitmap, userBitmap)
                                }
                            Render(transparentCanvasBitmap, userBitmap)
                        }
                    }
                }
            }
        }

        private fun obtainStaticProgressiveFrame(progressiveFrameIdx: Int, useCanvasRep: Boolean): Frame {
            val responses = pageCache.query(progressiveFrameIdx)
            val r = responses.singleOrNull()
            return when {
                responses.isEmpty() -> {
                    val bitmap = if (useCanvasRep) blankCanvasBitmap else blankUserBitmap
                    Frame(bitmap, writable = false, shift = 0)
                }
                r is PageCache.Response.Render && r.alpha == 1.0 -> when {
                    !useCanvasRep -> Frame(r.render.userBitmap, writable = false, shift = r.shift)
                    grounding == null -> Frame(r.render.transparentCanvasBitmap, writable = false, shift = r.shift)
                    else -> {
                        val bitmap = Bitmap.allocate(canvasWorkSpec)
                        Canvas.forBitmap(bitmap).use { canvas ->
                            canvas.fill(Canvas.Shader.Solid(grounding))
                            canvas.drawImageFast(r.render.transparentCanvasBitmap, y = -r.shift)
                        }
                        Frame(bitmap, writable = true, shift = 0)
                    }
                }
                else -> {
                    val canvasBitmap = Bitmap.allocate(canvasWorkSpec)
                    Canvas.forBitmap(canvasBitmap).use { canvas ->
                        if (grounding == null) canvasBitmap.zero() else canvas.fill(Canvas.Shader.Solid(grounding))
                        for (resp in pageCache.query(progressiveFrameIdx))
                            when (resp) {
                                is PageCache.Response.Image ->
                                    canvas.compositeLayer(alpha = resp.alpha) {
                                        materialize(canvas, resp.image, -resp.shift)
                                    }
                                is PageCache.Response.Render ->
                                    canvas.drawImageFast(
                                        resp.render.transparentCanvasBitmap, alpha = resp.alpha, y = -resp.shift
                                    )
                            }
                    }
                    val bitmap = if (useCanvasRep) canvasBitmap else canvas2userAndClose(canvasBitmap)
                    Frame(bitmap, writable = true, shift = 0)
                }
            }
        }

        private fun materialize(canvas: Canvas, defImg: DeferredImage, y: Double) {
            val shiftedSrc = if (y == 0.0) defImg else
                DeferredImage(canvas.width, canvas.height.toY()).apply { drawDeferredImage(defImg, y = y.toY()) }
            shiftedSrc.materialize(canvas, cache, staticLayers)
        }

        /* *****************************************************
           ********** OBTAIN STATIC INTERLACED FRAMES **********
           ***************************************************** */

        private data class InterlacedFrame(val bitmap: Bitmap, val fstSrcParity: Int, val fstDstParity: Int)

        private fun obtainStaticInterlacedFrame(frameIdx: Int, useCanvasRep: Boolean): InterlacedFrame {
            val fstSrcParity = if (userSpec.scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST) 0 else 1
            val sndSrcParity = 1 - fstSrcParity
            val fstDstParity = if (userSpec.content == Bitmap.Content.INTERLEAVED_FIELDS) fstSrcParity else sndSrcParity
            val sndDstParity = 1 - fstDstParity
            // It is important to query the earlier frame first, because as sequentialAccess is true, the cache is
            // free to discard it as soon as a later frame is queried.
            val (fst, fstWritable, fstShift) = obtainStaticProgressiveFrame(frameIdx * 2, useCanvasRep)
            val (snd, sndWritable, sndShift) = obtainStaticProgressiveFrame(frameIdx * 2 + 1, useCanvasRep)
            val interleaved = Bitmap.allocate(if (useCanvasRep) canvasWorkSpec else userWorkSpec)
            interleaved.blit(fst, 0, fstShift + fstSrcParity, workWidth, workHeight - 1, 0, fstDstParity, 2)
            interleaved.blit(snd, 0, sndShift + sndSrcParity, workWidth, workHeight - 1, 0, sndDstParity, 2)
            if (fstWritable) fst.close()
            if (sndWritable) snd.close()
            return InterlacedFrame(interleaved, fstSrcParity, fstDstParity)
        }

        /* ********************************************************
           ********** OBTAIN DYNAMIC FRAMES (WITH TAPES) **********
           ******************************************************** */

        private val tapeTracker = TapeTracker<TapeUserData>(progressiveVideo, tapeLayers)

        private fun obtainDynamicProgressiveFrame(frameIdx: Int): Frame {
            val tapeResponses = tapeTracker.query(frameIdx)
            val compInCanvasRep = shouldCompInCanvasRep(tapeResponses)
            val static = obtainStaticProgressiveFrame(frameIdx, compInCanvasRep)
            if (tapeResponses.isEmpty())
                return static
            val composite = if (static.writable) static.bitmap else
                Bitmap.allocate(if (compInCanvasRep) canvasWorkSpec else userWorkSpec)
                    .apply { blit(static.bitmap, 0, static.shift, workWidth, workHeight, 0, 0, 1) }
            for (resp in tapeResponses) {
                val userData = takeTapeUserData(resp)
                userData.frameOverlayer!!.overlay(composite, userData.read(resp.timecode), resp.x, resp.y, resp.alpha)
                dropTapeUserData(resp, frameIdx)
            }
            val userComposite = if (!compInCanvasRep) composite else canvas2userAndClose(composite)
            return Frame(userComposite, writable = true, shift = 0)
        }

        private fun obtainDynamicInterlacedFrame(frameIdx: Int): Frame {
            val fstTapeResponses = tapeTracker.query(frameIdx * 2)
            val sndTapeResponses = tapeTracker.query(frameIdx * 2 + 1)
            val compInCanvasRep = shouldCompInCanvasRep(fstTapeResponses) || shouldCompInCanvasRep(sndTapeResponses)
            val (composite, fstSrcParity, fstDstParity) = obtainStaticInterlacedFrame(frameIdx, compInCanvasRep)
            if (fstTapeResponses.isEmpty() && sndTapeResponses.isEmpty())
                return Frame(composite, writable = true, shift = 0)
            val sndSrcParity = 1 - fstSrcParity
            val sndDstParity = 1 - fstDstParity
            overlayInterlacedTapes(composite, frameIdx * 2, fstTapeResponses, fstSrcParity, fstDstParity)
            overlayInterlacedTapes(composite, frameIdx * 2 + 1, sndTapeResponses, sndSrcParity, sndDstParity)
            val userComposite = if (!compInCanvasRep) composite else canvas2userAndClose(composite)
            return Frame(userComposite, writable = true, shift = 0)
        }

        private fun overlayInterlacedTapes(
            composite: Bitmap, frameIdx: Int, tapeResponses: List<TapeTracker.Response<TapeUserData>>,
            srcParity: Int, dstParity: Int
        ) {
            for (resp in tapeResponses) {
                // Find which tape field to overlay onto the currently written output field.
                val overlayParity = if (resp.y.mod(2) == srcParity) 0 else 1
                // Find the y coordinate inside the currently written output field where the tape field should begin.
                val y = if (srcParity == 0) ceilDiv(resp.y, 2) else floorDiv(resp.y, 2)

                val userData = takeTapeUserData(resp)
                val frame = userData.read(resp.timecode)
                val field: Bitmap
                if (frame.spec.scan == Bitmap.Scan.PROGRESSIVE) {
                    // When the tape is progressive, treat the progressive frames as if they consisted of two fields.
                    val intSpec = frame.spec.copy(
                        scan = Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST, content = Bitmap.Content.INTERLEAVED_FIELDS
                    )
                    frame.reinterpretedView(intSpec).use { intFrame ->
                        field = if (overlayParity == 0) intFrame.topFieldView() else intFrame.botFieldView()
                    }
                } else {
                    // When the tape is interlaced, bring overlay fields into action alternatingly.
                    val fileSeq = resp.embeddedTape.tape.fileSeq
                    if (userData.topField == null) {
                        // If the tape has just appeared, immediately bring in both fields from the tape's first frame.
                        userData.topField = frame.topFieldView()
                        userData.botField = frame.botFieldView()
                    } else {
                        // Push the first field of "frame" if "resp.timecode" lies in the first field's time range.
                        // Otherwise, push the second field of "frame".
                        val tff = frame.spec.scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                        val timecodeRefersToFirstField =
                            if (fileSeq) resp.fileSeqFirstField else userData.didReadFirstField()
                        if (tff == timecodeRefersToFirstField)
                            userData.topField = frame.topFieldView()
                        else
                            userData.botField = frame.botFieldView()
                    }
                    field = if (overlayParity == 0) userData.topField!! else userData.botField!!
                }
                // Perform the overlaying.
                composite.view(0, dstParity, workWidth, workHeight - 1, 2).use { compField ->
                    (if (overlayParity == 0) userData.topFieldOverlayer!! else userData.botFieldOverlayer!!)
                        .overlay(compField, field, resp.x, y, resp.alpha)
                }
                if (frame.spec.scan == Bitmap.Scan.PROGRESSIVE)
                    field.close()
                dropTapeUserData(resp, frameIdx)
            }
        }

        private fun shouldCompInCanvasRep(tapeResponses: List<TapeTracker.Response<TapeUserData>>): Boolean {
            // Alpha compositing can only be done using a canvas.
            if (tapeResponses.any { it.alpha != 1.0 || it.embeddedTape.tape.spec.representation.pixelFormat.hasAlpha })
                return true
            // Overlaying onto a chroma-subsampled bitmap is only possible if the overlay coincides with the subsampling
            // grid. Otherwise, resort to overlaying onto the non-subsampled canvas bitmap, and then converting the
            // whole thing to the final subsampled representation.
            if (tapeResponses.isEmpty() || !userPixelFormat.hasChromaSub)
                return false
            val hn = 1 shl userPixelFormat.hChromaSub
            val vn = 1 shl userPixelFormat.vChromaSub
            return tapeResponses.any { resp ->
                val (rw, rh) = resp.embeddedTape.resolution
                resp.x % hn != 0 || resp.y % vn != 0 || rw % hn != 0 || rh % vn != 0
            }
        }

        private fun takeTapeUserData(resp: TapeTracker.Response<TapeUserData>): TapeUserData {
            resp.userData?.let { return it }
            return TapeUserData(canvasRepresentation, userSpec, resp, randomAccessDraftMode).also { resp.userData = it }
        }

        private fun dropTapeUserData(resp: TapeTracker.Response<TapeUserData>, frameIdx: Int) {
            // Close the reader early and dereference the user data if we no longer need it. Notice that in case of
            // errors, the close() function on this object will be called and will close all remaining readers.
            if (frameIdx >= /* use >= instead of == just to be sure */ resp.lastFrameIdx) {
                resp.userData?.close()
                resp.userData = null
            }
        }

        private class TapeUserData(
            canvasRep: Bitmap.Representation,
            userSpec: Bitmap.Spec,
            resp: TapeTracker.Response<*>,
            usePreview: Boolean
        ) {

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

            private lateinit var source: Source
            private lateinit var readSpec: Bitmap.Spec
            private lateinit var reader: Tape.SequentialReader
            private lateinit var previewTape: Tape

            init {
                setupSafely({
                    val embeddedTape = resp.embeddedTape
                    if (usePreview)
                        try {
                            previewTape = embeddedTape.tape
                            readSpec = embeddedTape.tape.getPreviewFrame(resp.timecode /* random tc */)!!.bitmap.spec
                            source = Source.PREVIEW
                        } catch (_: Exception) {
                            readSpec = Bitmap.Spec(embeddedTape.resolution, canvasRep)
                            source = Source.UNAVAILABLE
                        }
                    else {
                        reader = embeddedTape.tape.SequentialReader(resp.timecode /* the span's first timecode */)
                        readSpec = embeddedTape.tape.spec
                        source = Source.READER
                    }
                }, ::close)
            }

            // When this bitmap is initialized, it will exactly match readSpec,
            // so it'll be a drop-in replacement for the actual tape frames.
            private val missingMediaBitmap by lazy {
                val mmBitmap = Bitmap.allocate(readSpec)
                val rep = Canvas.compatibleRepresentation(ColorSpace.BLENDING)
                Bitmap.allocate(Bitmap.Spec(readSpec.resolution, rep)).use { canvasBitmap ->
                    Canvas.forBitmap(canvasBitmap.zero()).use { canvas ->
                        val (w, h) = readSpec.resolution
                        val colors = listOf(Tape.MISSING_MEDIA_TOP_COLOR, Tape.MISSING_MEDIA_BOT_COLOR)
                        canvas.fillShape(Rectangle(w, h), Canvas.Shader.LinearGradient(Point(), Point(0, h), colors))
                    }
                    BitmapConverter.convert(canvasBitmap, mmBitmap, promiseOpaque = true)
                }
                mmBitmap
            }

            val frameOverlayer: Overlayer?
            val topFieldOverlayer: Overlayer?
            val botFieldOverlayer: Overlayer?

            init {
                val embeddedTapeFrameRes = resp.embeddedTape.resolution
                val embeddedTapeFieldRes = Resolution(embeddedTapeFrameRes.widthPx, embeddedTapeFrameRes.heightPx / 2)

                val userSpecIsProgressive = userSpec.scan == Bitmap.Scan.PROGRESSIVE
                val readFramesAreProgressive = readSpec.scan == Bitmap.Scan.PROGRESSIVE
                if (userSpecIsProgressive) {
                    val compositedOverlayRes = if (readFramesAreProgressive) embeddedTapeFrameRes else
                    // If the tape is interlaced, make it have even height in the final output.
                        Resolution(embeddedTapeFrameRes.widthPx, embeddedTapeFrameRes.heightPx / 2 * 2)
                    frameOverlayer = Overlayer(canvasRep, compositedOverlayRes, usePreview)
                    topFieldOverlayer = null
                    botFieldOverlayer = null
                } else {
                    require(readFramesAreProgressive || readSpec.resolution.heightPx % 2 == 0) {
                        "The interlaced tape '${resp.embeddedTape.tape.fileOrDir.name}' must have even height."
                    }
                    frameOverlayer = null
                    topFieldOverlayer = Overlayer(canvasRep, embeddedTapeFieldRes, usePreview)
                    botFieldOverlayer = Overlayer(canvasRep, embeddedTapeFieldRes, usePreview)
                }
            }

            fun read(timecode: Timecode): Bitmap = when (source) {
                Source.READER -> reader.read(timecode).bitmap
                Source.PREVIEW -> try {
                    previewTape.getPreviewFrame(timecode)!!.bitmap
                } catch (_: Exception) {
                    missingMediaBitmap
                }
                Source.UNAVAILABLE -> missingMediaBitmap
            }

            fun didReadFirstField(): Boolean {
                check(source == Source.READER) { "Interlaced processing is not yet supported in previews." }
                return reader.didReadFirstField()
            }

            fun close() {
                if (source == Source.READER)
                    reader.close()
                frameOverlayer?.close()
                topFieldOverlayer?.close()
                botFieldOverlayer?.close()
                topField?.close()
                botField?.close()
            }

            private enum class Source { READER, PREVIEW, UNAVAILABLE }

        }

        private class Overlayer(
            private val canvasRep: Bitmap.Representation,
            private val compositedOverlayRes: Resolution,
            private val usingPreview: Boolean
        ) {

            private var raw2user: BitmapConverter? = null
            private var userOverlayBitmap: Bitmap? = null

            fun overlay(base: Bitmap, overlay: Bitmap, x: Int, y: Int, alpha: Double) {
                val (cw, ch) = compositedOverlayRes
                if (base.spec.representation == canvasRep) {
                    val (ow, oh) = overlay.spec.resolution
                    val transform = AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble())
                        .apply { scale(cw / ow.toDouble(), ch / oh.toDouble()) }
                    Canvas.forBitmap(base).use { canvas ->
                        canvas.drawImage(overlay, nearestNeighbor = usingPreview, alpha = alpha, transform = transform)
                    }
                } else {
                    check(alpha == 1.0)
                    if (raw2user == null)
                        initForBlit(base.spec, overlay.spec)
                    val userOverlayBitmap = userOverlayBitmap!!
                    raw2user!!.convert(overlay, userOverlayBitmap)
                    if (userOverlayBitmap.spec.representation.pixelFormat.isFloat)
                        userOverlayBitmap.clampFloatColors(
                            promiseOpaque = !overlay.spec.representation.pixelFormat.hasAlpha
                        )
                    base.blitLeniently(userOverlayBitmap, 0, 0, cw, ch, x, y)
                }
            }

            private fun initForBlit(userSpec: Bitmap.Spec, rawOverlaySpec: Bitmap.Spec) {
                val userOverlaySpec = userSpec.copy(resolution = compositedOverlayRes)
                raw2user = BitmapConverter(
                    rawOverlaySpec, userOverlaySpec,
                    srcAligned = usingPreview, approxTransfer = usingPreview, nearestNeighbor = usingPreview
                )
                userOverlayBitmap = Bitmap.allocate(userOverlaySpec)
            }

            fun close() {
                raw2user?.close()
                userOverlayBitmap?.close()
            }

        }

        /* *************************************
           ********** GENERIC HELPERS **********
           ************************************* */

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

        private fun canvas2userAndClose(src: Bitmap): Bitmap {
            val dst = Bitmap.allocate(userWorkSpec)
            canvas2user.convert(src, dst)
            src.close()
            return dst
        }

    }


    /**
     * This class can be queried for the excerpt of a page visible at a certain frame index. In the background, it
     * caches large rendered chunks of each page, so that it can quickly respond to a query by slicing a cached chunk.
     * The consumer needs to implement the method [createRenders], which renders a certain chunk of a given page.
     *
     * In practice, the response to a query is not necessarily a rendered excerpt of a single page. If pages overlap
     * in time, there could be multiple excerpts. And if it is not possible to cache page chunks because the frames
     * exhibit a large variety of fractional page shifts, this class might give up and directly return deferred images,
     * which then need to be rendered by the consumer.
     *
     * This class is thread-safe.
     *
     * @param sequentialAccess If true, cached page chunks that lie before the currently queried frame are freed.
     * @param preloading If true, renders near the last queried frame are precomputed in a background thread.
     */
    private abstract class PageCache<R : AutoCloseable>(
        private val video: DeferredVideo,
        private val sequentialAccess: Boolean,
        private val preloading: Boolean
    ) {

        private val chunkSpacing: Int
        private val chunks = mutableListOf<Chunk<R>>()
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
                class CountedMicroShift(val microShift: Double, var count: Int)

                // Find the min and max shifts in the current instruction. Also collect all distinct micro shifts:
                // a micro shift is the deviation from an integer shift, and hence lies between 0 and 1.
                var minShift = 0.0
                var maxShift = 0.0
                val countedMicroShifts = if (video.roundShifts) null else mutableListOf<CountedMicroShift>()
                for (shift in insn.shifts) {
                    minShift = min(minShift, shift)
                    maxShift = max(maxShift, shift)
                    if (countedMicroShifts != null) {
                        val microShift = shift - floor(shift)
                        // Check whether this micro shift is already present in our collection.
                        val cms = countedMicroShifts.find { abs(it.microShift - microShift) < EPS }
                        // If it is, increase its multiplicity. Otherwise, add the new micro shift to the collection.
                        if (cms != null) cms.count++ else countedMicroShifts.add(CountedMicroShift(microShift, 1))
                    }
                }

                // This is the smallest vertical area that covers the requests of all instructions.
                val minY = floor(minShift).toInt()
                val maxY = ceil(maxShift).toInt() + video.height

                val microShifts = when {
                    // If all shifts are integers, the only micro shift that occurs is 0.
                    countedMicroShifts == null ->
                        doubleArrayOf(0.0)
                    // If we don't exceed the maximum number of allowed micro shifts, use all of them.
                    countedMicroShifts.size <= MAX_MICRO_SHIFTS ->
                        DoubleArray(countedMicroShifts.size) { countedMicroShifts[it].microShift }
                    // Otherwise, use only the most frequently occurring micro shifts. For the others, we later resort
                    // to directly drawing deferred images, which is of course slower.
                    else -> {
                        countedMicroShifts.sortByDescending { it.count }
                        DoubleArray(MAX_MICRO_SHIFTS) { countedMicroShifts[it].microShift }
                    }
                }

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

        protected abstract fun createRenders(
            image: DeferredImage, baseShift: Int, microShifts: DoubleArray, height: Int
        ): List<R>

        fun close() {
            for (chunk in chunks)
                chunk.microShiftedRenders.getAndSet(null)?.get()?.forEach { it.close() }
        }

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
                    val renders = chunks[i].microShiftedRenders.getAndSet(null)
                    // Stop when we encounter a chunk that was once loaded but has since been explicitly nulled before.
                    if (renders != null) renders.get()?.forEach { it.close() } else break
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
            // Determine the micro shift for the given shift and select the corresponding cached render that can be
            // passed to the consumer with only integer shifting.
            val microShift = shift - floor(shift)
            return when (val imageIdx = chunk.microShifts.indexOfFirst { abs(it - microShift) < EPS }) {
                // If no cached render could be found for the micro shift at hand, directly pass the deferred image
                // to the consumer. This is slower than using cached renders, but it's our only option.
                -1 -> Response.Image(chunk.image, shift, alpha)
                // Otherwise, pass the found cached render.
                else -> Response.Render(microShiftedRenders[imageIdx], floor(shift).toInt() - chunk.shift, alpha)
            }
        }

        private fun queueChunkPreloading(chunk: Chunk<R>) {
            if (chunk.microShiftedRenders.get().let { it == null || it.refersTo(null) })
                PRELOADING_EXECUTOR.submit(throwableAwareTask { loadChunk(chunk) })
        }

        // Apart from code design, there is an important reason for why this method returns the renders: to ensure that
        // there is a strong reference to them. Otherwise, the garbage collector could discard them immediately when the
        // method returns.
        private fun loadChunk(chunk: Chunk<R>): List<R> {
            // If the chunk was already materialized and the renders are still in the cache, return them.
            chunk.microShiftedRenders.get()?.get()?.let { return it }
            // Otherwise, materialize the renders now and cache them.
            chunk.renderingLock.withLock {
                val microShiftedRenders = createRenders(chunk.image, chunk.shift, chunk.microShifts, chunk.height)
                chunk.microShiftedRenders.set(SoftReference(microShiftedRenders))
                return microShiftedRenders
            }
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
            class Render<R>(val render: R, val shift: Int, val alpha: Double) : Response<R>
        }


        private class Chunk<R>(
            val shift: Int,
            val height: Int,
            val image: DeferredImage,
            val microShifts: DoubleArray
        ) {
            val microShiftedRenders = AtomicReference<SoftReference<List<R>>?>()
            val renderingLock = ReentrantLock()
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

                    add(Response(span, timecode, fileSeqFirstField, x, y, alpha))
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
