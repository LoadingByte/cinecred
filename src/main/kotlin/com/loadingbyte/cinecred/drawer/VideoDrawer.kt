package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.anyBetween
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.BACKGROUND
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.FOREGROUND
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GROUNDING
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.*


open class VideoDrawer(
    private val project: Project,
    drawnPages: List<DrawnPage>,
    scaling: Double = 1.0,
    private val transparentGrounding: Boolean = false,
    private val mode: Mode
) {

    enum class Mode {
        /** Calls to [drawFrame] are not permitted. */
        NO_DRAWING,
        /** Calls to [drawFrame] are expected to always increase the frame index. The cache is managed accordingly. */
        SEQUENTIAL,
        /** Quality is reduced and images near the last drawn frame are precomputed in a background thread. */
        PREVIEW
    }


    /* ************************************
       ********** INITIALIZATION **********
       ************************************ */

    // Note: A pageIdx of -1 indicates an empty frame.
    private class Insn(val pageIdx: Int, val imgTopY: Double, val alpha: Double)

    private val insns = ArrayList<Insn>()

    init {
        // Write frames for each page as has been configured.
        for ((pageIdx, page) in project.pages.withIndex()) {
            for ((stageIdx, stage) in page.stages.withIndex())
                when (val stageInfo = drawnPages[pageIdx].stageInfo[stageIdx]) {
                    is DrawnStageInfo.Card -> {
                        val imgTopY = scaling * (stageInfo.middleY.resolve() - project.styling.global.heightPx / 2.0)
                        if (stageIdx == 0)
                            writeFade(pageIdx, imgTopY, stage.style.cardFadeInFrames, fadeOut = false)
                        writeStatic(pageIdx, imgTopY, stage.style.cardDurationFrames)
                        if (stageIdx == page.stages.lastIndex)
                            writeFade(pageIdx, imgTopY, stage.style.cardFadeOutFrames, fadeOut = true)
                    }
                    is DrawnStageInfo.Scroll -> {
                        val imgTopYStart =
                            scaling * (stageInfo.scrollStartY.resolve() - project.styling.global.heightPx / 2.0)
                        writeScroll(
                            pageIdx, imgTopYStart, stageInfo.frames, stageInfo.initialAdvance,
                            scrollPxPerFrame = scaling * stage.style.scrollPxPerFrame
                        )
                    }
                }

            if (pageIdx != project.pages.lastIndex)
                writeStatic(-1, 0.0, page.stages.last().style.afterwardSlugFrames)
        }
    }

    private fun writeStatic(pageIdx: Int, imgTopY: Double, numFrames: Int) {
        for (frame in 0 until numFrames)
            insns.add(Insn(pageIdx, imgTopY, 1.0))
    }

    private fun writeFade(pageIdx: Int, imgTopY: Double, numFrames: Int, fadeOut: Boolean) {
        for (frame in 0 until numFrames) {
            // Choose alpha such that the fade sequence doesn't contain a fully empty or fully opaque frame.
            var alpha = (frame + 1).toDouble() / (numFrames + 1)
            if (fadeOut)
                alpha = 1.0 - alpha
            insns.add(Insn(pageIdx, imgTopY, alpha))
        }
    }

    private fun writeScroll(
        pageIdx: Int, imgTopYStart: Double, numFrames: Int, initialAdvance: Double, scrollPxPerFrame: Double
    ) {
        for (frame in 0 until numFrames) {
            val imgTopY = imgTopYStart + (frame + initialAdvance) * scrollPxPerFrame
            insns.add(Insn(pageIdx, imgTopY, 1.0))
        }
    }

    val numFrames = insns.size

    val width = (scaling * project.styling.global.widthPx).roundToInt()
    val height = (scaling * project.styling.global.heightPx).roundToInt()


    /* ************************************
       ********** RASTER DRAWING **********
       ************************************ */

    private class Chunk(
        val shift: Int,
        val height: Int,
        val cull: Boolean,
        val defImage: DeferredImage,
        val microShifts: DoubleArray?
    ) {
        val lock = ReentrantLock()
        var microShiftedImages: SoftReference<List<BufferedImage>>? = null
    }

    private val chunkSpacing: Int
    private val chunks = mutableListOf<Chunk>()
    private val firstChunkInd = IntArray(drawnPages.size)
    private val lastChunkInd = IntArray(drawnPages.size)

    init {
        // We make the chunks larger than the spacing between two chunks so that a chunk can be scrolled for some time and
        // then immediately the next chunk can be swapped in, without the need to stitch the two chunks together.
        // We add an extra 1 to the height to make room for the micro shift (which is between 0 and 1) at the bottom.
        val maxChunkHeight = max(height + MIN_CHUNK_BUFFER, MAX_CHUNK_PIXELS / width)
        chunkSpacing = maxChunkHeight - height - 1

        if (mode != Mode.NO_DRAWING) {
            // Declare all chunks.
            var insnIdx = 0
            for ((pageIdx, drawnPage) in drawnPages.withIndex()) {
                val defImage = drawnPage.defImage.copy(universeScaling = scaling)
                val totalHeight = ceil(defImage.height.resolve()).toInt()

                // Collect all distinct micro shifts exhibited by the instructions for the current page. A micro shift
                // is the deviation from an integer shift, and hence lies between 0 and 1.
                // In preview mode, we do not cache images for all micro shifts, so instead, we just use only the 0
                // micro shift.
                val microShifts = if (mode == Mode.PREVIEW) doubleArrayOf(0.0) else {
                    var microShifts: DoubleArray? = DoubleArray(MAX_MICRO_SHIFTS)
                    var numMicroShifts = 0
                    // Skip pauses in between pages.
                    while (insnIdx < insns.size && insns[insnIdx].pageIdx == -1)
                        insnIdx++
                    while (insnIdx < insns.size && insns[insnIdx].pageIdx == pageIdx) {
                        if (microShifts != null) {
                            val shift = insns[insnIdx].imgTopY
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
                        insnIdx++
                    }
                    microShifts?.copyOf(numMicroShifts)
                }

                // Declare chunks to cover exactly the whole deferred image (but not any more space), and remember the
                // first and last chunk indices for the current page.
                firstChunkInd[pageIdx] = chunks.size
                var chunkShift = -chunkSpacing
                do {
                    chunkShift += chunkSpacing
                    val chunkHeight = min(maxChunkHeight, totalHeight - chunkShift)
                    val cull = chunkHeight < totalHeight
                    chunks.add(Chunk(chunkShift, chunkHeight, cull, defImage, microShifts))
                } while (chunkShift + chunkHeight < totalHeight)
                lastChunkInd[pageIdx] = chunks.size - 1
            }
        }
    }

    private fun drawShiftedPage(g2: Graphics2D, pageIdx: Int, shift: Double) {
        val chunkIdx = firstChunkInd[pageIdx] + (shift.toInt() / chunkSpacing).coerceIn(0, lastChunkInd[pageIdx])

        if (mode == Mode.SEQUENTIAL) {
            // In sequential mode, free the previous chunk's cached images.
            // Note: We do not need to lock here as the sequential mode doesn't do any multithreaded preloading.
            chunks.getOrNull(chunkIdx - 1)?.let { chunk -> chunk.microShiftedImages?.clear() }
        } else if (mode == Mode.PREVIEW) {
            // In preview mode, queue preloading of the surrounding chunks in a background thread.
            chunks.getOrNull(chunkIdx + 1)?.let(::queueChunkPreloading)
            chunks.getOrNull(chunkIdx - 1)?.let(::queueChunkPreloading)
        }

        val chunk = chunks[chunkIdx]
        // Get the cached images, or compute them now if they are not cached.
        val microShiftedImages = loadChunk(chunk)
        if (microShiftedImages == null) {
            // If the cache is disabled for this chunk, directly draw the deferred image to the graphics object. This is
            // slower than using cached raster images, but it's our only option.
            val culling = Rectangle2D.Double(0.0, shift, width.toDouble(), height.toDouble())
            g2.withNewG2 {
                g2.setHighQuality()
                g2.translate(0.0, -shift)
                chunk.defImage.materialize(g2, layers = listOf(BACKGROUND, FOREGROUND), culling)
            }
        } else if (mode == Mode.PREVIEW) {
            // In preview mode, we only cache a single image without micro shift, so fractionally shift now.
            val image = microShiftedImages.single()
            g2.drawImage(image, AffineTransform.getTranslateInstance(0.0, -(shift - chunk.shift)), null)
        } else {
            // In non-preview mode, determine the micro shift for the given shift, select the corresponding cached
            // image, and paint it to the graphics object with only integer shifting.
            val microShift = shift - floor(shift)
            val imageIdx = chunk.microShifts!!.indexOfFirst { abs(it - microShift) < EPS }
            val image = microShiftedImages[imageIdx]
            g2.drawImage(image, 0, -(floor(shift).toInt() - chunk.shift), null)
        }
    }

    private fun queueChunkPreloading(chunk: Chunk) {
        if (chunk.microShifts != null &&
            chunk.lock.withLock(chunk::microShiftedImages).let { it == null || it.refersTo(null) }
        ) PRELOADING_EXECUTOR.submit { loadChunk(chunk) }
    }

    // Note: Apart from code design, there is an important reason for why this method returns the materialized images:
    // to ensure that there is a strong reference to them. Otherwise, the garbage collector could discard them
    // immediately when the method returns.
    private fun loadChunk(chunk: Chunk): List<BufferedImage>? {
        // If the chunk should not be materialized into images, return null.
        if (chunk.microShifts == null)
            return null
        // The following operations access and modify the mutable microShiftedImages, so if we're in the multithreaded
        // preview mode, we must be sure to hold the chunk lock before proceeding.
        return if (mode == Mode.PREVIEW) chunk.lock.withLock { doLoadChunk(chunk) } else doLoadChunk(chunk)
    }

    private fun doLoadChunk(chunk: Chunk): List<BufferedImage> {
        // If the chunk was already materialized and the images are still in the cache, return them.
        chunk.microShiftedImages?.get()?.let { return it }
        // Otherwise, materialize the images now and cache them.
        val microShiftedImages = chunk.microShifts!!.map { microShift ->
            createIntermediateImage(width, chunk.height).withG2 { g2 ->
                g2.setHighQuality()
                val layers = mutableListOf(BACKGROUND, FOREGROUND)
                if (!transparentGrounding) {
                    // If the final image should not have an alpha channel, the intermediate images, which also don't
                    // have alpha, have to have the proper grounding, as otherwise their grounding would be black.
                    layers.add(0, GROUNDING)
                }
                g2.translate(0.0, -(chunk.shift + microShift))
                // If the chunk doesn't contain the whole page, cull the rest to improve performance.
                val culling = if (!chunk.cull) null else
                    Rectangle2D.Double(0.0, chunk.shift.toDouble(), width.toDouble(), chunk.height.toDouble())
                // Paint the deferred image onto the raster image.
                chunk.defImage.materialize(g2, layers, culling)
            }
        }
        chunk.microShiftedImages = SoftReference(microShiftedImages)
        return microShiftedImages
    }

    /**
     * This method must be overwritten by users of this class who exactly know onto what kind of
     * graphics object they will later draw their frames. The returned image should support alpha if and only if
     * [transparentGrounding] is true.
     * Even though this method is not abstract, it throws an [UnsupportedOperationException]. Not having it abstract
     * makes it easier to create a video drawer just for the purpose of computing the overall number of frames.
     */
    protected open fun createIntermediateImage(width: Int, height: Int): BufferedImage {
        throw UnsupportedOperationException()
    }

    fun drawFrame(g2: Graphics2D, frameIdx: Int) {
        check(mode != Mode.NO_DRAWING) { "VideoDrawer is in the ${Mode.NO_DRAWING} mode." }
        require(frameIdx in 0..numFrames) { "Frame #$frameIdx exceeds number of available frames ($numFrames)." }

        if (!transparentGrounding) {
            g2.color = project.styling.global.grounding
            g2.fillRect(0, 0, width, height)
        }

        val insn = insns[frameIdx]
        // Note: pageIdx == -1 means that the frame should be empty.
        if (insn.pageIdx != -1) {
            val blend = insn.alpha != 1.0
            val prevComposite = g2.composite
            if (blend) g2.composite = AlphaComposite.SrcOver.derive(insn.alpha.toFloat())
            drawShiftedPage(g2, insn.pageIdx, shift = insn.imgTopY)
            if (blend) g2.composite = prevComposite
        }
    }


    companion object {

        private const val EPS = 0.001
        private const val MIN_CHUNK_BUFFER = 200
        private const val MAX_CHUNK_PIXELS = 20_000_000
        private const val MAX_MICRO_SHIFTS = 16

        private val PRELOADING_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "VideoPreviewPreloader").apply { isDaemon = true }
        }

    }

}
