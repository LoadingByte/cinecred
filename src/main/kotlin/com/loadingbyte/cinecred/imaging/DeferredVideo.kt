package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.DeferredImage.EmbeddedTape.Align.*
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.Point
import java.awt.Rectangle
import java.lang.ref.SoftReference
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*


/**
 * The implementation assumes that once a [DeferredImage] has stopped playing, it will never play again. The code is
 * optimized accordingly. If this assumption is violated, the image will be materialized more often than it needs to be.
 */
class DeferredVideo private constructor(
    private val origResolution: Resolution,
    private val origFPS: FPS,
    private val resolutionScaling: Double,
    private val resolutionPaddingH: Double,
    private val resolutionPaddingV: Double,
    private val fpsScaling: Int,
    private val roundShifts: Boolean,
    private var frozen: Boolean,
    private val flows: MutableList<Flow>
) {

    val resolution: Resolution = Resolution(
        (origResolution.widthPx * resolutionScaling + 2 * resolutionPaddingH).roundToInt(),
        (origResolution.heightPx * resolutionScaling + 2 * resolutionPaddingV).roundToInt()
    )
    val fps: FPS = FPS(origFPS.numerator * fpsScaling, origFPS.denominator)

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

    constructor(resolution: Resolution, fps: FPS) : this(resolution, fps, 1.0, 0.0, 0.0, 1, false, false, ArrayList())

    fun copy(
        resolutionScaling: Double = 1.0,
        resolutionPaddingH: Double = 0.0,
        resolutionPaddingV: Double = 0.0,
        fpsScaling: Int = 1,
        roundShifts: Boolean = false
    ): DeferredVideo {
        require(resolutionScaling > 0.0)
        require(fpsScaling >= 1)
        frozen = true
        return DeferredVideo(
            origResolution,
            origFPS,
            this.resolutionScaling * resolutionScaling,
            this.resolutionPaddingH + resolutionPaddingH,
            this.resolutionPaddingV + resolutionPaddingV,
            this.fpsScaling * fpsScaling,
            this.roundShifts || roundShifts,
            frozen = true,
            flows
        )
    }

    fun sub(firstImg: DeferredImage?, lastImg: DeferredImage?): DeferredVideo {
        frozen = true
        return DeferredVideo(
            origResolution, origFPS, resolutionScaling, resolutionPaddingH, resolutionPaddingV, fpsScaling, roundShifts,
            frozen = true,
            flows.subList(
                if (firstImg == null) 0 else flows.indexOfFirst { it is Flow.DefImg && it.image == firstImg },
                if (lastImg == null) flows.size else flows.indexOfFirst { it is Flow.DefImg && it.image == lastImg } + 1
            )
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

    fun playFade(image: DeferredImage, numFrames: Int, shift: Double, transition: Transition, fadeIn: Boolean) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.Fade(numFrames, shift, transition, fadeIn))
    }

    /** Note that [startShift] is exclusive, so the first displayed frame has shift `startShift + 1 * speed`. */
    fun playScroll(
        image: DeferredImage, numFrames: Int, speed: Double, startShift: Double, stopShift: Double, alpha: Double
    ) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.Scroll(numFrames, speed, startShift, stopShift, alpha))
    }

    /** Note that if [speedUp] is true, [startShift] is exclusive, otherwise [stopShift] is exclusive. */
    fun playScrollRamp(
        image: DeferredImage,
        numFrames: Int, startShift: Double, stopShift: Double, transition: Transition, speedUp: Boolean, alpha: Double
    ) {
        if (numFrames <= 0) return
        mutate()
        playPhase(image, Phase.ScrollRamp(numFrames, startShift, stopShift, transition, speedUp, alpha))
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
        return buildList {
            for (frameIdx in tapeTracker.collectSpanFirstFrameIndices())
                for (resp in tapeTracker.query(frameIdx))
                    if (resp.firstFrameIdx == frameIdx)
                        add(TapeSpan(resp.embeddedTape, resp.firstFrameIdx, resp.lastFrameIdx, resp.timecode))
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
                    val insnImage = DeferredImage(
                        width = flow.image.width * resolutionScaling + 2 * resolutionPaddingH,
                        height = flow.image.height * resolutionScaling + 2 * resolutionPaddingV
                    )
                    insnImage.drawDeferredImage(
                        flow.image,
                        x = resolutionPaddingH,
                        y = resolutionPaddingV.toY(),
                        universeScaling = resolutionScaling
                    )
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
        val embeddedTape: DeferredImage.EmbeddedTape,
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
            private val transition: Transition,
            private val fadeIn: Boolean
        ) : Phase {
            override fun numFrames(fpsScaling: Int) = numFrames * fpsScaling
            override fun shift(fpsScaling: Int, frameIdx: Int) = shift
            // Alpha is chosen such that the fade sequence contains neither an alpha of 0 nor 1.
            override fun alpha(fpsScaling: Int, frameIdx: Int) = transition.y(
                (if (fadeIn) 0.0 else 1.0) + (if (fadeIn) 1.0 else -1.0) * (frameIdx + 1) / (numFrames(fpsScaling) + 1)
            )
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

        class ScrollRamp(
            private val numFrames: Int,
            private val startShift: Double,
            private val stopShift: Double,
            private val transition: Transition,
            private val speedUp: Boolean,
            private val alpha: Double
        ) : Phase {

            override fun numFrames(fpsScaling: Int) = numFrames * fpsScaling

            override fun shift(fpsScaling: Int, frameIdx: Int): Double {
                val effNumFrames = (numFrames * fpsScaling).toDouble()
                return startShift + (stopShift - startShift) * when (speedUp) {
                    true -> transition.yIntegral((frameIdx + 1) / effNumFrames) / transition.yIntegral(1.0)
                    false -> 1.0 - transition.yIntegral(1.0 - frameIdx / effNumFrames) / transition.yIntegral(1.0)
                }
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


    /** This class is not thread-safe. */
    class BitmapBackend(
        video: DeferredVideo,
        private val staticLayers: List<DeferredImage.Layer>,
        tapeLayers: List<DeferredImage.Layer>,
        private val grounding: Color4f?,
        private val userSpec: Bitmap.Spec,
        private val canvasCeiling: Float? = 1f,
        private val cache: DeferredImage.CanvasMaterializationCache? = null,
        private val randomAccessDraftMode: Boolean = false,
        private val blendInUserColorSpace: Boolean = false
    ) : AutoCloseable {

        init {
            require(video.resolution == userSpec.resolution)
            require(userSpec.content.let { it != Bitmap.Content.ONLY_TOP_FIELD && it != Bitmap.Content.ONLY_BOT_FIELD })
        }

        private val numFrames = video.numFrames
        private val progressiveVideo = video.copy(
            fpsScaling = if (userSpec.scan == Bitmap.Scan.PROGRESSIVE) 1 else 2,
            roundShifts = randomAccessDraftMode
        )
        private val userPixelFormat get() = userSpec.representation.pixelFormat
        private val canvasRepresentation = Canvas.compatibleRepresentation(
            ColorSpace.of(userSpec.representation.colorSpace!!.primaries, ColorSpace.Transfer.BLENDING)
        )

        private val workWidth = closestWorkResolution(userSpec.resolution.widthPx, userPixelFormat.hChromaSub)
        private val workHeight = closestWorkResolution(userSpec.resolution.heightPx, userPixelFormat.vChromaSub)

        private val userIWorkSpec =
            userSpec.copy(resolution = Resolution(workWidth, workHeight))
        private val userPWorkSpec =
            userIWorkSpec.copy(scan = Bitmap.Scan.PROGRESSIVE, content = Bitmap.Content.PROGRESSIVE_FRAME)
        private val canvasIWorkSpec =
            userIWorkSpec.copy(representation = canvasRepresentation)
        private val canvasPWorkSpec =
            canvasIWorkSpec.copy(scan = Bitmap.Scan.PROGRESSIVE, content = Bitmap.Content.PROGRESSIVE_FRAME)

        private val canvasP2userP = BitmapConverter(
            canvasPWorkSpec, userPWorkSpec, promiseOpaque = grounding != null, approxTransfer = randomAccessDraftMode
        )
        private val canvasI2userI = BitmapConverter(
            canvasIWorkSpec, userIWorkSpec, promiseOpaque = grounding != null, approxTransfer = randomAccessDraftMode
        )

        override fun close() {
            canvasP2userP.close()
            canvasI2userI.close()
            blankCanvasPBitmap.close()
            blankUserPBitmap.close()
            pageCache.close()
            for (userData in tapeTracker.collectUserData())
                userData.close()
        }

        fun preloadFrame(frameIdx: Int) {
            check(randomAccessDraftMode) { "Frame preloading is only supported in random access mode." }
            pageCache.query(frameIdx)
        }

        private var lastFrameIdx = -1

        /** The returned bitmap is permitted to be [Bitmap.close]d, but must not be modified. */
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

        private class Render(val transparCanvasOrDraftBitmap: Bitmap, val userBitmap: Bitmap) : AutoCloseable {
            override fun close() {
                transparCanvasOrDraftBitmap.close()
                userBitmap.close()
            }
        }

        private val blankCanvasPBitmap: Bitmap
        private val blankUserPBitmap: Bitmap
        private val pageCache: PageCache<Render>

        init {
            blankCanvasPBitmap = Bitmap.allocate(canvasPWorkSpec)
            blankUserPBitmap = Bitmap.allocate(userPWorkSpec)
            if (grounding != null) {
                Canvas.forBitmap(blankCanvasPBitmap.zero(), canvasCeiling).use { canvas ->
                    canvas.fill(Canvas.Shader.Solid(grounding))
                }
                canvasP2userP.convert(blankCanvasPBitmap, blankUserPBitmap)
            } else {
                blankCanvasPBitmap.zero()
                blankUserPBitmap.zero()
            }

            pageCache = object : PageCache<Render>(
                progressiveVideo,
                userSpec.representation.pixelFormat.vChromaSub,
                sequentialAccess = !randomAccessDraftMode,
                preloading = true
            ) {
                override fun createRenders(
                    image: DeferredImage, baseShift: Int, microShifts: DoubleArray, height: Int
                ): List<Render> {
                    // IMPORTANT: This method will be called from different threads at the same time when stuff is
                    // pre-rendered in the background. As such, we can't use any BitmapConverters or other stateful
                    // objects in this method!

                    val resolution = Resolution(workWidth, height)

                    // Materialize each micro shift to a transparent canvas bitmap.
                    val renderCanvasSpec = Bitmap.Spec(resolution, canvasRepresentation)
                    val transparentCanvasBitmaps = microShifts.map { ms ->
                        val bmp = Bitmap.allocate(renderCanvasSpec)
                        Canvas.forBitmap(bmp.zero(), canvasCeiling).use { materialize(it, image, -(baseShift + ms)) }
                        bmp
                    }

                    // Set up the conversion from the transparent canvas bitmap to the user bitmap.
                    val renderUserSpec = Bitmap.Spec(resolution, userSpec.representation)
                    val renderCanvas2user = BitmapConverter(
                        renderCanvasSpec, renderUserSpec,
                        promiseOpaque = grounding != null, approxTransfer = randomAccessDraftMode
                    )

                    // When using the draft compositor, set up the conversion from the transparent canvas bitmap to the
                    // transparent draft bitmap.
                    var renderDraftSpec: Bitmap.Spec? = null
                    var renderCanvas2draft: BitmapConverter? = null
                    if (blendInUserColorSpace) {
                        val rep = draftOverlayRepresentation(userSpec.representation.colorSpace!!, hasAlpha = true)
                        renderDraftSpec = Bitmap.Spec(resolution, rep)
                        renderCanvas2draft = BitmapConverter(renderCanvasSpec, renderDraftSpec, approxTransfer = true)
                    }

                    try {
                        // Note: Despite this map operation potentially being expensive if there are lots of bitmaps,
                        // we cannot parallelize it because BitmapConverter.convert() is not thread-safe.
                        return transparentCanvasBitmaps.map { transparentCanvasBitmap ->
                            // Obtain the user bitmap.
                            val userBitmap = Bitmap.allocate(renderUserSpec)
                            if (grounding == null)
                                renderCanvas2user.convert(transparentCanvasBitmap, userBitmap)
                            else
                                Bitmap.allocate(renderCanvasSpec).use { groundedCanvasBitmap ->
                                    Canvas.forBitmap(groundedCanvasBitmap, canvasCeiling).use { canvas ->
                                        canvas.fill(Canvas.Shader.Solid(grounding))
                                        canvas.drawImageFast(transparentCanvasBitmap)
                                    }
                                    renderCanvas2user.convert(groundedCanvasBitmap, userBitmap)
                                }

                            // When using the draft compositor, obtain the transparent draft bitmap. We also don't need
                            // the transparent canvas bitmap anymore, so close it.
                            val transparentCanvasOrDraftBitmap =
                                if (!blendInUserColorSpace) transparentCanvasBitmap else {
                                    val transparentDraftBitmap = Bitmap.allocate(renderDraftSpec!!)
                                    renderCanvas2draft!!.convert(transparentCanvasBitmap, transparentDraftBitmap)
                                    transparentCanvasBitmap.close()
                                    transparentDraftBitmap
                                }

                            Render(transparentCanvasOrDraftBitmap, userBitmap)
                        }
                    } finally {
                        renderCanvas2user.close()
                        renderCanvas2draft?.close()
                    }
                }
            }
        }

        private fun obtainStaticProgressiveFrame(progressiveFrameIdx: Int, useCanvasRep: Boolean): Frame {
            val responses = pageCache.query(progressiveFrameIdx)
            val r = responses.singleOrNull()
            return when {
                responses.isEmpty() -> {
                    val bitmap = if (useCanvasRep) blankCanvasPBitmap else blankUserPBitmap
                    Frame(bitmap, writable = false, shift = 0)
                }
                r is PageCache.Response.Render && r.alpha == 1.0 -> when {
                    !useCanvasRep -> Frame(r.render.userBitmap, writable = false, shift = r.shift)
                    grounding == null -> Frame(r.render.transparCanvasOrDraftBitmap, writable = false, shift = r.shift)
                    else -> {
                        val bitmap = Bitmap.allocate(canvasPWorkSpec)
                        Canvas.forBitmap(bitmap, canvasCeiling).use { canvas ->
                            canvas.fill(Canvas.Shader.Solid(grounding))
                            canvas.drawImageFast(r.render.transparCanvasOrDraftBitmap, y = -r.shift)
                        }
                        Frame(bitmap, writable = true, shift = 0)
                    }
                }
                blendInUserColorSpace -> {
                    val bitmap = Bitmap.allocate(userPWorkSpec)
                    bitmap.blit(blankUserPBitmap)
                    for (resp in pageCache.query(progressiveFrameIdx)) {
                        check(resp is PageCache.Response.Render)  // In draft mode, there are no micro shifts.
                        draftComposite(resp.render.transparCanvasOrDraftBitmap, bitmap, 0, -resp.shift, resp.alpha)
                    }
                    Frame(bitmap, writable = true, shift = 0)
                }
                else -> {
                    val canvasBitmap = Bitmap.allocate(canvasPWorkSpec)
                    Canvas.forBitmap(canvasBitmap, canvasCeiling).use { canvas ->
                        if (grounding == null) canvasBitmap.zero() else canvas.fill(Canvas.Shader.Solid(grounding))
                        for (resp in pageCache.query(progressiveFrameIdx))
                            when (resp) {
                                is PageCache.Response.Image ->
                                    canvas.compositeLayer(alpha = resp.alpha) {
                                        materialize(canvas, resp.image, -resp.shift)
                                    }
                                is PageCache.Response.Render ->
                                    canvas.drawImageFast(
                                        resp.render.transparCanvasOrDraftBitmap, alpha = resp.alpha, y = -resp.shift
                                    )
                            }
                    }
                    val bitmap = if (useCanvasRep) canvasBitmap else Bitmap.allocate(userPWorkSpec)
                        .also { canvasP2userP.convert(canvasBitmap, it); canvasBitmap.close() }
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
            val interleaved = Bitmap.allocate(if (useCanvasRep) canvasIWorkSpec else userIWorkSpec)
            // It is important to query the earlier frame first, and also to immediately blit it, because the cache is
            // free to close it as soon as a later frame is queried (since sequentialAccess is true).
            val (fst, fstWritable, fstShift) = obtainStaticProgressiveFrame(frameIdx * 2, useCanvasRep)
            interleaved.blit(fst, 0, fstShift + fstSrcParity, workWidth, workHeight - 1, 0, fstDstParity, 2)
            val (snd, sndWritable, sndShift) = obtainStaticProgressiveFrame(frameIdx * 2 + 1, useCanvasRep)
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
                Bitmap.allocate(if (compInCanvasRep) canvasPWorkSpec else userPWorkSpec)
                    .apply { blit(static.bitmap, 0, static.shift, workWidth, workHeight, 0, 0, 1) }
            for (resp in tapeResponses) {
                val userData = takeTapeUserData(resp)
                userData.read(resp.timecode).use { overlay ->
                    userData.frameOverlayer!!.overlay(composite, overlay, resp.x, resp.y, resp.alpha)
                }
                dropTapeUserData(resp, frameIdx)
            }
            val userComposite = if (!compInCanvasRep) composite else Bitmap.allocate(userPWorkSpec)
                .also { canvasP2userP.convert(composite, it); composite.close() }
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
            val userComposite = if (!compInCanvasRep) composite else Bitmap.allocate(userIWorkSpec)
                .also { canvasI2userI.convert(composite, it); composite.close() }
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
                frame.close()
                dropTapeUserData(resp, frameIdx)
            }
        }

        private fun shouldCompInCanvasRep(tapeResponses: List<TapeTracker.Response<TapeUserData>>): Boolean {
            // If requested, use the fast draft compositor for everything, including alpha compositing.
            if (blendInUserColorSpace)
                return false
            // Alpha compositing can only be done using a canvas.
            if (tapeResponses.any { it.alpha != 1.0 || it.embeddedTape.tape.spec.representation.pixelFormat.hasAlpha })
                return true
            // Blitting onto a chroma-subsampled bitmap is only possible if the overlay coincides with the subsampling
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
            return TapeUserData(
                canvasRepresentation, canvasCeiling, userSpec, resp, randomAccessDraftMode, blendInUserColorSpace
            ).also { resp.userData = it }
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
            canvasCeiling: Float?,
            userSpec: Bitmap.Spec,
            resp: TapeTracker.Response<*>,
            usePreview: Boolean,
            blendInUserColorSpace: Boolean
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
            private lateinit var reader: Tape.SequentialReader
            private lateinit var previewTape: Tape

            private var readConverter: BitmapConverter? = null
            private var readConvertedSpec: Bitmap.Spec? = null
            private lateinit var readCrop: Rectangle
            private lateinit var readReorder: Reorder
            private lateinit var readSpec: Bitmap.Spec

            init {
                setupSafely({
                    val embeddedTape = resp.embeddedTape
                    var origSpec: Bitmap.Spec
                    if (usePreview)
                        try {
                            source = Source.PREVIEW
                            previewTape = embeddedTape.tape
                            origSpec = previewTape.getPreviewFrame(resp.timecode /* random */).get()!!.bitmap.spec
                            val tapeRes = previewTape.spec.resolution
                            val tapeCrop = embeddedTape.crop
                            val cropMulX = origSpec.resolution.widthPx / tapeRes.widthPx.toDouble()
                            val cropMulY = origSpec.resolution.heightPx / tapeRes.heightPx.toDouble()
                            readCrop = Rectangle(
                                floor(cropMulX * tapeCrop.x).toInt(), floor(cropMulY * tapeCrop.y).toInt(),
                                ceil(cropMulX * tapeCrop.width).toInt(), ceil(cropMulY * tapeCrop.height).toInt()
                            )
                        } catch (_: Exception) {
                            source = Source.UNAVAILABLE
                            val rep = Picture.Raster.compatibleRepresentation(
                                userSpec.representation.colorSpace!!, Bitmap.Alpha.OPAQUE
                            )
                            origSpec = Bitmap.Spec(embeddedTape.resolution, rep)
                            readCrop = embeddedTape.resolution.run { Rectangle(widthPx, heightPx) }
                        }
                    else {
                        source = Source.READER
                        reader = embeddedTape.tape.SequentialReader(resp.timecode /* the span's first timecode */)
                        origSpec = embeddedTape.tape.spec
                        readCrop = embeddedTape.crop
                    }

                    var convertedSpec = origSpec
                    // If the pixel format directly from the source has chroma subsampling, insert a converter stage
                    // to an otherwise equivalent non-subsampled pixel format. This is necessary for interlaced export,
                    // cropping, and reordering, but for simplicity, we simply always do it.
                    val pixFmt = origSpec.representation.pixelFormat
                    if (pixFmt.hasChromaSub) {
                        val alpha = pixFmt.hasAlpha
                        val le = pixFmt.byteOrder == ByteOrder.LITTLE_ENDIAN
                        val convertedPixFmtCode = when (pixFmt.depth) {
                            8 -> when (alpha) {
                                true -> AV_PIX_FMT_YUVA444P
                                else -> AV_PIX_FMT_YUV444P
                            }
                            9 -> when (alpha) {
                                true -> if (le) AV_PIX_FMT_YUVA444P9LE else AV_PIX_FMT_YUVA444P9BE
                                else -> if (le) AV_PIX_FMT_YUV444P9LE else AV_PIX_FMT_YUV444P9BE
                            }
                            10 -> when (alpha) {
                                true -> if (le) AV_PIX_FMT_YUVA444P10LE else AV_PIX_FMT_YUVA444P10BE
                                else -> if (le) AV_PIX_FMT_YUV444P10LE else AV_PIX_FMT_YUV444P10BE
                            }
                            12 -> when (alpha) {
                                true -> if (le) AV_PIX_FMT_YUVA444P12LE else AV_PIX_FMT_YUVA444P12BE
                                else -> if (le) AV_PIX_FMT_YUV444P12LE else AV_PIX_FMT_YUV444P12BE
                            }
                            14 -> when (alpha) {
                                true -> if (le) AV_PIX_FMT_YUVA444P16LE else AV_PIX_FMT_YUVA444P16BE  // fallback
                                else -> if (le) AV_PIX_FMT_YUV444P14LE else AV_PIX_FMT_YUV444P14BE
                            }
                            16 -> when (alpha) {
                                true -> if (le) AV_PIX_FMT_YUVA444P16LE else AV_PIX_FMT_YUVA444P16BE
                                else -> if (le) AV_PIX_FMT_YUV444P16LE else AV_PIX_FMT_YUV444P16BE
                            }
                            else -> if (le) AV_PIX_FMT_YUVA444P16LE else AV_PIX_FMT_YUVA444P16BE  // fallback
                        }
                        convertedSpec = convertedSpec.copy(
                            representation = convertedSpec.representation.copy(
                                pixelFormat = Bitmap.PixelFormat.of(convertedPixFmtCode),
                                chromaLocation = AVCHROMA_LOC_UNSPECIFIED
                            )
                        )
                    }
                    // If the source yields reversed interleaved fields, and we produce progressive output, flip the
                    // field coding order now so that we can later reinterpret the interlaced frame as progressive.
                    if (origSpec.content == Bitmap.Content.INTERLEAVED_FIELDS_REVERSED &&
                        userSpec.scan == Bitmap.Scan.PROGRESSIVE
                    )
                        convertedSpec = convertedSpec.copy(content = Bitmap.Content.INTERLEAVED_FIELDS)
                    if (convertedSpec !== origSpec) {
                        readConverter = BitmapConverter(origSpec, convertedSpec)
                        readConvertedSpec = convertedSpec
                    }

                    readReorder = when (embeddedTape.rotation) {
                        0 -> Reorder(flipH = embeddedTape.flipH, flipV = embeddedTape.flipV, transpose = false)
                        90 -> Reorder(flipH = !embeddedTape.flipV, flipV = embeddedTape.flipH, transpose = true)
                        180 -> Reorder(flipH = !embeddedTape.flipH, flipV = !embeddedTape.flipV, transpose = false)
                        else -> Reorder(flipH = embeddedTape.flipV, flipV = !embeddedTape.flipH, transpose = true)
                    }

                    readSpec = convertedSpec.copy(
                        resolution = when (embeddedTape.rotation % 180 == 0) {
                            true -> Resolution(readCrop.width, readCrop.height)
                            else -> Resolution(readCrop.height, readCrop.width)
                        },
                        scan = when {
                            // When an interlaced tape is rotated onto its side, there's little synergy between
                            // continuing to treat the now vertical fields as appearing at different times and
                            // interlaced export, so for simplicity, we just treat the tape as progressive.
                            readReorder.transpose -> Bitmap.Scan.PROGRESSIVE
                            // When vertically flipping an interlaced tape, what previously was the top field becomes
                            // the bottom field (and vice versa), so we when previously the top field came first, now
                            // the bottom field comes first (and vice versa).
                            readReorder.flipV -> when (convertedSpec.scan) {
                                Bitmap.Scan.PROGRESSIVE -> Bitmap.Scan.PROGRESSIVE
                                Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST -> Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST
                                Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST -> Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                            }
                            else -> convertedSpec.scan
                        },
                        content = when {
                            readReorder.transpose -> Bitmap.Content.PROGRESSIVE_FRAME
                            else -> convertedSpec.content
                        }
                    )
                }, ::close)
            }

            // When this bitmap is initialized, it will exactly match readSpec,
            // so it'll be a drop-in replacement for the actual tape frames.
            private val missingMediaBitmap by lazy {
                val mmBitmap = Bitmap.allocate(readSpec)
                val rep = Canvas.compatibleRepresentation(ColorSpace.SRGB)
                Bitmap.allocate(Bitmap.Spec(readSpec.resolution, rep)).use { canvasBitmap ->
                    Canvas.forBitmap(canvasBitmap.zero(), canvasCeiling).use { canvas ->
                        val (w, h) = readSpec.resolution
                        val colors = listOf(Color4f.MISSING_MEDIA_TOP, Color4f.MISSING_MEDIA_BOT)
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
                val userRep = userSpec.representation

                if (userSpec.scan == Bitmap.Scan.PROGRESSIVE) {
                    val compositedOverlayRes = resp.embeddedTape.resolution
                    frameOverlayer = when (blendInUserColorSpace) {
                        true -> UserColorSpaceOverlayer(userRep.colorSpace!!, readSpec, compositedOverlayRes)
                        else -> QualityOverlayer(
                            canvasRep, canvasCeiling, userRep, readSpec, compositedOverlayRes, usePreview
                        )
                    }
                    topFieldOverlayer = null
                    botFieldOverlayer = null
                } else {
                    require(!blendInUserColorSpace) { "Interlaced processing does not support user CS blending." }
                    val compositedOverlayRes = resp.embeddedTape.resolution.run { Resolution(widthPx, heightPx / 2) }
                    val topFieldOverlaySpec: Bitmap.Spec
                    val botFieldOverlaySpec: Bitmap.Spec
                    if (readSpec.scan == Bitmap.Scan.PROGRESSIVE) {
                        topFieldOverlaySpec = Bitmap.Spec(
                            readSpec.resolution.run { Resolution(widthPx, heightPx / 2) }, readSpec.representation,
                            Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST, Bitmap.Content.ONLY_TOP_FIELD
                        )
                        botFieldOverlaySpec = topFieldOverlaySpec.copy(content = Bitmap.Content.ONLY_BOT_FIELD)
                    } else
                        Bitmap.allocate(readSpec).use { dummyBitmap ->
                            // It is easier to rely on Bitmap's spec inference than to construct these specs ourselves.
                            topFieldOverlaySpec = dummyBitmap.topFieldView().use { it.spec }
                            botFieldOverlaySpec = dummyBitmap.botFieldView().use { it.spec }
                        }
                    frameOverlayer = null
                    topFieldOverlayer = QualityOverlayer(
                        canvasRep, canvasCeiling, userRep, topFieldOverlaySpec, compositedOverlayRes, usePreview
                    )
                    botFieldOverlayer = QualityOverlayer(
                        canvasRep, canvasCeiling, userRep, botFieldOverlaySpec, compositedOverlayRes, usePreview
                    )
                }
            }

            fun read(timecode: Timecode): Bitmap {
                val origBitmap = when (source) {
                    Source.READER -> reader.read(timecode).bitmap
                    Source.PREVIEW -> try {
                        previewTape.getPreviewFrame(timecode).get()!!.bitmap
                    } catch (_: Exception) {
                        return missingMediaBitmap.value
                    }
                    Source.UNAVAILABLE -> return missingMediaBitmap.value
                }
                var convertedBitmap = origBitmap
                readConverter?.let { conv ->
                    convertedBitmap = Bitmap.allocate(readConvertedSpec!!)
                    conv.convert(origBitmap, convertedBitmap)
                }
                val croppedBitmap = readCrop.run { convertedBitmap.view(x, y, width, height, 1) }
                if (convertedBitmap != origBitmap) convertedBitmap.close()
                val (flipH, flipV, transpose) = readReorder
                if (!flipH && !flipV && !transpose)
                    return croppedBitmap
                else {
                    val reorderedBitmap = Bitmap.allocate(readSpec)
                    reorderedBitmap.blitReordered(croppedBitmap, flipH, flipV, transpose)
                    croppedBitmap.close()
                    return reorderedBitmap
                }
            }

            fun didReadFirstField(): Boolean {
                check(source == Source.READER) { "Interlaced processing is not yet supported in previews." }
                return reader.didReadFirstField()
            }

            fun close() {
                if (source == Source.READER)
                    reader.close()
                readConverter?.close()
                frameOverlayer?.close()
                topFieldOverlayer?.close()
                botFieldOverlayer?.close()
                topField?.close()
                botField?.close()
            }

            private enum class Source { READER, PREVIEW, UNAVAILABLE }
            private data class Reorder(val flipH: Boolean, val flipV: Boolean, val transpose: Boolean)

        }

        private interface Overlayer : AutoCloseable {

            fun overlay(base: Bitmap, overlay: Bitmap, x: Int, y: Int, alpha: Double)

            companion object {

                fun makeOverlayConvAndDst(
                    overlaySpec: Bitmap.Spec, dstRes: Resolution, dstRep: Bitmap.Representation, usingPreview: Boolean
                ): Pair<BitmapConverter, Bitmap> {
                    val dstSpec = overlaySpec.copy(resolution = dstRes, representation = dstRep)
                    val conv = BitmapConverter(
                        overlaySpec, dstSpec,
                        // srcAligned is false due to potential cropping of the overlay.
                        srcAligned = false, approxTransfer = usingPreview, nearestNeighbor = usingPreview
                    )
                    val dstBitmap = Bitmap.allocate(dstSpec)
                    return Pair(conv, dstBitmap)
                }

                fun renderPreviewText(
                    resolution: Resolution, canvasCS: ColorSpace, draftOverlayCS: ColorSpace
                ): Pair<Bitmap, Bitmap> {
                    val canvasBitmap =
                        Bitmap.allocate(Bitmap.Spec(resolution, Canvas.compatibleRepresentation(canvasCS)))
                    Canvas.forBitmap(canvasBitmap.zero()).use { canvas ->
                        val (w, h) = resolution
                        val previewIndicator = Tape.previewIndicator(0.0, 0.0, w.toDouble(), h.toDouble())
                        canvas.fillShape(previewIndicator, Canvas.Shader.Solid(Color4f.TAPE_PREVIEW))
                    }

                    val draftOverlayRep = draftOverlayRepresentation(draftOverlayCS, hasAlpha = true)
                    val draftOverlayBitmap = Bitmap.allocate(Bitmap.Spec(resolution, draftOverlayRep))
                    BitmapConverter.convert(canvasBitmap, draftOverlayBitmap)

                    return Pair(canvasBitmap, draftOverlayBitmap)
                }

                fun computeUserCeiling(canvasCS: ColorSpace, userCS: ColorSpace, canvasCeiling: Float?) =
                    if (canvasCeiling == null) canvasCeiling else
                        Color4f(canvasCeiling, canvasCeiling, canvasCeiling, canvasCS).convert(userCS).rgb().min()

            }

        }

        private abstract class QualityOverlayer(
            private val canvasRep: Bitmap.Representation,
            protected val canvasCeiling: Float?,
            overlaySpec: Bitmap.Spec,
            compositedOverlayRes: Resolution,
            usingPreview: Boolean
        ) : Overlayer {

            private val overlay2canvas: BitmapConverter
            private val canvasBitmap: Bitmap

            init {
                Overlayer.makeOverlayConvAndDst(overlaySpec, compositedOverlayRes, canvasRep, usingPreview)
                    .run { overlay2canvas = first; canvasBitmap = second }
            }

            final override fun overlay(base: Bitmap, overlay: Bitmap, x: Int, y: Int, alpha: Double) {
                if (base.spec.representation == canvasRep) {
                    val promiseOpaque = !overlay.spec.representation.pixelFormat.hasAlpha
                    overlay2canvas.convert(overlay, canvasBitmap)
                    manipulateCanvasOverlay(canvasBitmap)
                    canvasBitmap.clampFloatColors(canvasCeiling, promiseOpaque)
                    Canvas.forBitmap(base, canvasCeiling).use { canvas ->
                        canvas.drawImageFast(canvasBitmap, promiseOpaque, alpha, x, y)
                    }
                } else {
                    check(alpha == 1.0 && !overlay.spec.representation.pixelFormat.hasAlpha)
                    overlayOpaque(base, overlay, x, y)
                }
            }

            protected open fun manipulateCanvasOverlay(canvasBitmap: Bitmap) {}
            protected abstract fun overlayOpaque(base: Bitmap, overlay: Bitmap, x: Int, y: Int)

            override fun close() {
                overlay2canvas.close()
                canvasBitmap.close()
            }

            companion object {
                operator fun invoke(
                    canvasRep: Bitmap.Representation,
                    canvasCeiling: Float?,
                    userRep: Bitmap.Representation,
                    overlaySpec: Bitmap.Spec,
                    compositedOverlayRes: Resolution,
                    usingPreview: Boolean
                ) =
                    if (!usingPreview)
                        QualityReaderOverlayer(canvasRep, canvasCeiling, userRep, overlaySpec, compositedOverlayRes)
                    else
                        QualityPreviewOverlayer(canvasRep, canvasCeiling, userRep, overlaySpec, compositedOverlayRes)
            }

        }

        private class QualityReaderOverlayer(
            canvasRep: Bitmap.Representation,
            canvasCeiling: Float?,
            userRep: Bitmap.Representation,
            overlaySpec: Bitmap.Spec,
            compositedOverlayRes: Resolution
        ) : QualityOverlayer(canvasRep, canvasCeiling, overlaySpec, compositedOverlayRes, usingPreview = false) {

            private val overlay2user: BitmapConverter
            private val userBitmap: Bitmap

            private val userCeiling =
                Overlayer.computeUserCeiling(canvasRep.colorSpace!!, userRep.colorSpace!!, canvasCeiling)

            init {
                Overlayer.makeOverlayConvAndDst(overlaySpec, compositedOverlayRes, userRep, usingPreview = false)
                    .run { overlay2user = first; userBitmap = second }
            }

            // Note: This function is only called when shouldCompInCanvasRep() returned false. If the base bitmap is
            // chroma-subsampled, it only returns false if the overlay coincides with the subsampling grid. Hence, this
            // function can safely blit.
            override fun overlayOpaque(base: Bitmap, overlay: Bitmap, x: Int, y: Int) {
                overlay2user.convert(overlay, userBitmap)
                if (userBitmap.spec.representation.pixelFormat.isFloat)
                    userBitmap.clampFloatColors(userCeiling, promiseOpaque = true)
                val (w, h) = userBitmap.spec.resolution
                base.blitLeniently(userBitmap, 0, 0, w, h, x, y)
            }

            override fun close() {
                super.close()
                overlay2user.close()
                userBitmap.close()
            }

        }

        private class QualityPreviewOverlayer(
            canvasRep: Bitmap.Representation,
            canvasCeiling: Float?,
            userRep: Bitmap.Representation,
            overlaySpec: Bitmap.Spec,
            compositedOverlayRes: Resolution
        ) : QualityOverlayer(canvasRep, canvasCeiling, overlaySpec, compositedOverlayRes, usingPreview = true) {

            private val overlay2prep: BitmapConverter
            private val prepBitmap: Bitmap

            private val prep2user: BitmapConverter
            private val userBitmap: Bitmap
            private var userBlit = false

            private val textCanvasBitmap: Bitmap
            private val textPrepBitmap: Bitmap

            private val userCeiling =
                Overlayer.computeUserCeiling(canvasRep.colorSpace!!, userRep.colorSpace!!, canvasCeiling)

            init {
                val prepRep = overlaySpec.representation.copy(
                    colorSpace = userRep.colorSpace,
                    alpha = if (overlaySpec.representation.pixelFormat.hasAlpha)
                        Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE
                )
                Overlayer.makeOverlayConvAndDst(overlaySpec, compositedOverlayRes, prepRep, usingPreview = true)
                    .run { overlay2prep = first; prepBitmap = second }

                val userSpec = Bitmap.Spec(compositedOverlayRes, userRep)
                prep2user = BitmapConverter(prepBitmap.spec, userSpec)
                userBitmap = Bitmap.allocate(userSpec)

                val topField = overlaySpec.content == Bitmap.Content.ONLY_TOP_FIELD
                val botField = overlaySpec.content == Bitmap.Content.ONLY_BOT_FIELD
                val (w, h) = compositedOverlayRes
                val renderRes = if (topField || botField) Resolution(w, h * 2) else compositedOverlayRes
                var (cB, pB) = Overlayer.renderPreviewText(renderRes, canvasRep.colorSpace!!, userRep.colorSpace!!)
                if (topField || botField) {
                    cB = ripField(cB, topField)
                    pB = ripField(pB, topField)
                }
                textCanvasBitmap = cB
                textPrepBitmap = pB
            }

            private fun ripField(bitmap: Bitmap, topField: Boolean): Bitmap {
                val spec = bitmap.spec.copy(
                    scan = Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST, content = Bitmap.Content.INTERLEAVED_FIELDS
                )
                return bitmap.use {
                    bitmap.reinterpretedView(spec).use { if (topField) it.topFieldView() else it.botFieldView() }
                }
            }

            override fun manipulateCanvasOverlay(canvasBitmap: Bitmap) {
                Canvas.forBitmap(canvasBitmap, canvasCeiling).use { canvas ->
                    canvas.drawImageFast(textCanvasBitmap)
                }
            }

            override fun overlayOpaque(base: Bitmap, overlay: Bitmap, x: Int, y: Int) {
                overlay2prep.convert(overlay, prepBitmap)
                draftComposite(textPrepBitmap, prepBitmap)
                // Manually clamp only if the base bitmap is float; if it's int, the blit quantization will clamp.
                if (base.spec.representation.pixelFormat.isFloat)
                    prepBitmap.clampFloatColors(userCeiling, promiseOpaque = true)
                if (!userBlit)
                    try {
                        // Note: Since prepBitmap is opaque and alpha is 1, this call just does a fast blit, but no
                        // actual alpha compositing; hence, this doesn't suffer from lower "draft" quality.
                        draftComposite(prepBitmap, base, x, y)
                    } catch (_: RuntimeException) {
                        userBlit = true
                    }
                if (userBlit) {
                    val (w, h) = userBitmap.spec.resolution
                    prep2user.convert(prepBitmap, userBitmap)
                    base.blitLeniently(userBitmap, 0, 0, w, h, x, y)
                }
            }

            override fun close() {
                super.close()
                textCanvasBitmap.close()
                overlay2prep.close()
                prepBitmap.close()
                textPrepBitmap.close()
                prep2user.close()
                userBitmap.close()
            }

        }

        private class UserColorSpaceOverlayer(
            userColorSpace: ColorSpace,
            overlaySpec: Bitmap.Spec,
            compositedOverlayRes: Resolution
        ) : Overlayer {

            private val overlay2prep: BitmapConverter
            private val prepBitmap: Bitmap
            private val textBitmap: Bitmap

            init {
                val prepRep = overlaySpec.representation.copy(
                    colorSpace = userColorSpace,
                    alpha = if (overlaySpec.representation.pixelFormat.hasAlpha)
                        Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE
                )
                Overlayer.makeOverlayConvAndDst(overlaySpec, compositedOverlayRes, prepRep, usingPreview = true)
                    .run { overlay2prep = first; prepBitmap = second }

                Overlayer.renderPreviewText(compositedOverlayRes, userColorSpace, userColorSpace)
                    .run { first.close(); textBitmap = second }
            }

            override fun overlay(base: Bitmap, overlay: Bitmap, x: Int, y: Int, alpha: Double) {
                overlay2prep.convert(overlay, prepBitmap)
                draftComposite(textBitmap, prepBitmap)
                draftComposite(prepBitmap, base, x, y, alpha)
            }

            override fun close() {
                overlay2prep.close()
                prepBitmap.close()
                textBitmap.close()
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

    }


    /**
     * This class can be queried for the excerpt of a page visible at a certain frame index. In the background, it
     * caches large rendered chunks of each page, so that it can quickly respond to a query by slicing a cached chunk.
     * The consumer needs to implement the method [createRenders], which renders a certain chunk of a given page.
     * That method must be safe to be called from different threads at the same time!
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
        private val vChromaSub: Int,
        private val sequentialAccess: Boolean,
        private val preloading: Boolean
    ) {

        private val chunkSpacing: Int
        private val chunks = mutableListOf<Chunk<R>>()
        private val firstChunkIndices = IntArray(video.instructions.size)
        private val lastChunkIndices = IntArray(video.instructions.size)

        init {
            val yMask = -(1 shl vChromaSub)

            // We make the chunks larger than the spacing between two chunks so that a chunk can be scrolled for some
            // time and then immediately the next chunk can be swapped in, without the need to stitch the two chunks.
            // We further add 2^vChromaSub to the height to make room for the micro shift (which is between 0 and
            // 2^vChromaSub) at the bottom.
            val maxChunkHeight = max(video.height + MIN_CHUNK_BUFFER, MAX_CHUNK_PIXELS / video.width) and yMask
            chunkSpacing = (maxChunkHeight - (video.height + (1 shl vChromaSub))) and yMask

            // Declare chunks for all instructions.
            for ((insnIdx, insn) in video.instructions.withIndex()) {
                class CountedMicroShift(val microShift: Double, var count: Int)

                // Find the min and max shifts in the current instruction. Also collect all distinct micro shifts:
                // a micro shift is the deviation from the preceding integer shift aligned with the vertical chroma
                // subsampling, and hence lies between 0 and 2^vChromaSub.
                var minShift = 0.0
                var maxShift = 0.0
                val countedMicroShifts =
                    if (video.roundShifts && vChromaSub == 0) null else mutableListOf<CountedMicroShift>()
                for (shift in insn.shifts) {
                    minShift = min(minShift, shift)
                    maxShift = max(maxShift, shift)
                    if (countedMicroShifts != null) {
                        val microShift = shift.mod((1 shl vChromaSub).toDouble())
                        // Check whether this micro shift is already present in our collection.
                        val cms = countedMicroShifts.find { abs(it.microShift - microShift) < EPS }
                        // If it is, increase its multiplicity. Otherwise, add the new micro shift to the collection.
                        if (cms != null) cms.count++ else countedMicroShifts.add(CountedMicroShift(microShift, 1))
                    }
                }

                // This is the smallest vertical area that covers the requests of all instructions.
                val minY = floor(minShift).toInt() and yMask
                val maxY = (ceil(maxShift).toInt() + video.height + yMask.inv()) and yMask

                val microShifts = when {
                    // If all shifts are integers, the only micro shift that occurs is 0.
                    countedMicroShifts == null ->
                        doubleArrayOf(0.0)
                    // Otherwise, use only the most frequently occurring micro shifts up to a maximum count, and also
                    // drop micro shifts that occur rarely. For the others, we later resort to directly drawing deferred
                    // images, which would of course be slower for often occurring images.
                    else -> {
                        countedMicroShifts.sortByDescending { it.count }
                        val firstRareIdx = countedMicroShifts.indexOfFirst { it.count < MIN_MICRO_SHIFT_OCCURRENCES }
                            .let { if (it != -1) it else countedMicroShifts.size }
                        DoubleArray(min(MAX_MICRO_SHIFTS, firstRareIdx)) { countedMicroShifts[it].microShift }
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
            // Get the cached renders for the current chunk, or render them now if they are not cached, or await their
            // rendering if another thread is already doing that right now.
            chunk.semaphore.acquire()
            var microShiftedRenders = chunk.microShiftedRenders.get()?.get()
            if (microShiftedRenders != null) chunk.semaphore.release() else microShiftedRenders = loadChunk(chunk)
            // Determine the micro shift for the given shift and select the corresponding cached render that can be
            // passed to the consumer with only integer shifting.
            val microShift = shift.mod((1 shl vChromaSub).toDouble())
            return when (val imageIdx = chunk.microShifts.indexOfFirst { abs(it - microShift) < EPS }) {
                // If no cached render could be found for the micro shift at hand, directly pass the deferred image
                // to the consumer. This is slower than using cached renders, but it's our only option.
                -1 -> Response.Image(chunk.image, shift, alpha)
                // Otherwise, pass the found cached render.
                else -> Response.Render(
                    microShiftedRenders[imageIdx], round(shift - microShift).toInt() - chunk.shift, alpha
                )
            }
        }

        private fun queueChunkPreloading(chunk: Chunk<R>) {
            // If the chunk is being rendered right now, return. If not, we acquired the exclusive right to render it.
            if (!chunk.semaphore.tryAcquire())
                return
            // If the chunk has already been rendered previously, immediately release the rendering right.
            // Otherwise, start rendering in another thread.
            if (chunk.microShiftedRenders.get().let { it != null && !it.refersTo(null) })
                chunk.semaphore.release()
            else
                GLOBAL_THREAD_POOL.submit(throwableAwareTask { loadChunk(chunk) })
        }

        // Apart from code design, there is an important reason for why this method returns the renders: to ensure that
        // there is a strong reference to them. Otherwise, the garbage collector could discard them immediately when the
        // method returns.
        private fun loadChunk(chunk: Chunk<R>): List<R> {
            try {
                val microShiftedRenders = createRenders(chunk.image, chunk.shift, chunk.microShifts, chunk.height)
                chunk.microShiftedRenders.set(SoftReference(microShiftedRenders))
                return microShiftedRenders
            } finally {
                chunk.semaphore.release()
            }
        }


        companion object {
            private const val EPS = 0.001
            private const val MIN_CHUNK_BUFFER = 200
            private const val MAX_CHUNK_PIXELS = 20_000_000
            private const val MAX_MICRO_SHIFTS = 16
            private const val MIN_MICRO_SHIFT_OCCURRENCES = 5
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
            val semaphore = Semaphore(1)
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
                    val firstPotFrameIdx =
                        insn.firstFrameIdx + relFirstFrameIdx + placed.embeddedTape.leftMarginFrames * video.fpsScaling
                    val lastPotFrameIdx =
                        insn.firstFrameIdx + relLastFrameIdx - placed.embeddedTape.rightMarginFrames * video.fpsScaling
                    var firstFrameIdx = firstPotFrameIdx
                    var lastFrameIdx = lastPotFrameIdx
                    val extraneousFrames = (lastFrameIdx - firstFrameIdx + 1) - rangeFrames
                    if (extraneousFrames > 0)
                        when (placed.embeddedTape.align) {
                            START -> lastFrameIdx -= extraneousFrames
                            END -> firstFrameIdx += extraneousFrames
                            MIDDLE -> {
                                val half = extraneousFrames / 2
                                firstFrameIdx += half
                                lastFrameIdx -= extraneousFrames - half  // account for odd numbers
                            }
                        }
                    if (firstFrameIdx > lastFrameIdx)
                        continue
                    add(Span(insn, placed, firstFrameIdx, lastFrameIdx))
                    // If the video is looping, fill up the remaining potential time with copies of the tape.
                    if (placed.embeddedTape.loop && extraneousFrames > 0) {
                        val spanFrames = lastFrameIdx - firstFrameIdx + 1
                        val pS = DeferredImage.PlacedTape(placed.embeddedTape.withAlign(START), placed.x, placed.y)
                        val pE = DeferredImage.PlacedTape(placed.embeddedTape.withAlign(END), placed.x, placed.y)
                        while (firstFrameIdx > firstPotFrameIdx) {
                            firstFrameIdx -= spanFrames
                            add(Span(insn, pE, max(firstFrameIdx, firstPotFrameIdx), firstFrameIdx + (spanFrames - 1)))
                        }
                        while (lastFrameIdx < lastPotFrameIdx) {
                            lastFrameIdx += spanFrames
                            add(Span(insn, pS, lastFrameIdx - (spanFrames - 1), min(lastFrameIdx, lastPotFrameIdx)))
                        }
                    }
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
                            START -> start + Timecode.Frames(pastFrames / video.fpsScaling)
                            END -> endExcl - Timecode.Frames(futureFrames / video.fpsScaling + 1)
                            MIDDLE -> {
                                tmp = (((start + endExcl) as Timecode.Frames).frames - 1) * video.fpsScaling +
                                        frameIdx * 2 - (span.firstFrameIdx + span.lastFrameIdx)
                                Timecode.Frames(tmp / (2 * video.fpsScaling))
                            }
                        }
                        else -> when (span.embeddedTape.align) {
                            START -> start + Timecode.Frames(pastFrames).toClock(video.fps)
                            END -> endExcl - Timecode.Frames(futureFrames).toClock(video.fps) -
                                    Timecode.Frames(1).toClock(video.origFPS)
                            MIDDLE ->
                                (start + endExcl - Timecode.Frames(1).toClock(video.origFPS)) / 2 +
                                        Timecode.Frames(frameIdx).toClock(video.fps) -
                                        Timecode.Frames(span.firstFrameIdx + span.lastFrameIdx).toClock(video.fps) / 2
                        }
                    }
                    val fileSeqFirstField = span.embeddedTape.tape.fileSeq && when (span.embeddedTape.align) {
                        START -> (pastFrames * 2 / video.fpsScaling) % 2 == 0
                        END -> (futureFrames * 2 / video.fpsScaling) % 2 == 1
                        MIDDLE -> (tmp / video.fpsScaling) % 2 == 0
                    }

                    val relFrameIdx = frameIdx - span.insn.firstFrameIdx
                    val x = span.placed.x.roundToInt()
                    val y = (span.placed.y - span.insn.shifts[relFrameIdx]).roundToInt()

                    var alpha = span.insn.alphas[relFrameIdx]
                    val fadeInFrames = span.embeddedTape.fadeInFrames * video.fpsScaling
                    val fadeOutFrames = span.embeddedTape.fadeOutFrames * video.fpsScaling
                    if (pastFrames < fadeInFrames || futureFrames < fadeOutFrames)
                        alpha *= minOf(
                            span.embeddedTape.fadeInTransition.y((pastFrames + 1) / (fadeInFrames + 1).toDouble()),
                            span.embeddedTape.fadeOutTransition.y((futureFrames + 1) / (fadeOutFrames + 1).toDouble())
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
             * interlaced output video.
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
