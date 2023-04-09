package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.DeferredVideo.Cache.Response
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform.getTranslateInstance
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.lang.ref.SoftReference
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.*


/**
 * The implementation assumes that no [DeferredImage] is drawn more than once and is optimized accordingly. If this
 * assumption is violated, the image will be materialized more often than it needs to be.
 */
class DeferredVideo(val resolution: Resolution) {

    // Convenient accessors:
    private val width get() = resolution.widthPx
    private val height get() = resolution.heightPx

    private val instructions = ArrayList<Instruction>()

    val numFrames: Int
        get() = instructions.maxOf { it.lastFrameIdx + 1 }

    fun copy(scaling: Double = 1.0): DeferredVideo {
        val copy = DeferredVideo(Resolution((width * scaling).roundToInt(), (height * scaling).roundToInt()))
        for (insn in instructions)
            copy.playDeferredImage(
                insn.firstFrameIdx,
                insn.image.copy(universeScaling = scaling),
                insn.shifts.mapToArray { shift -> shift * scaling },
                insn.alphas
            )
        return copy
    }

    fun playDeferredImage(firstFrameIdx: Int, image: DeferredImage, shifts: DoubleArray, alphas: DoubleArray) {
        require(shifts.size == alphas.size)
        instructions.add(Instruction(firstFrameIdx, image, shifts, alphas))
    }


    companion object {

        private inline fun Graphics2D.blend(alpha: Double, block: () -> Unit) {
            val blend = alpha != 1.0
            val prevComposite = composite
            if (blend) composite = AlphaComposite.SrcOver.derive(alpha.toFloat())
            block()
            if (blend) composite = prevComposite
        }

        private fun Graphics2D.drawDeferredImage(
            image: DeferredImage, layers: List<DeferredImage.Layer>, shift: Double, height: Int
        ) {
            translate(0.0, -shift)
            // If the chunk doesn't contain the whole page, cull the rest to improve performance.
            val culling = if (height < image.height.resolve()) null else
                Rectangle2D.Double(0.0, shift, image.width, height.toDouble())
            // Paint the deferred image onto the raster image.
            image.materialize(this, layers, culling)
        }

    }


    private class Instruction(
        val firstFrameIdx: Int, val image: DeferredImage, val shifts: DoubleArray, val alphas: DoubleArray
    ) {
        val lastFrameIdx = firstFrameIdx + shifts.size - 1
    }


    abstract class Graphics2DBackend(
        private val video: DeferredVideo,
        private val layers: List<DeferredImage.Layer>,
        draft: Boolean = false,
        sequentialAccess: Boolean = false,
        preloading: Boolean = false,
    ) {

        private val cache = object : Cache<BufferedImage>(video, draft, sequentialAccess, preloading) {
            override fun createRender(image: DeferredImage, shift: Double, height: Int): BufferedImage =
                createIntermediateImage(video.width, height).withG2 { g2 ->
                    g2.setHighQuality()
                    g2.drawDeferredImage(image, layers, shift, height)
                }
        }

        /**
         * This method must be overwritten by users of this class who exactly know onto what kind of [Graphics2D] object
         * they will later draw their frames. The new image must support alpha.
         */
        protected abstract fun createIntermediateImage(width: Int, height: Int): BufferedImage

        fun preloadFrame(frameIdx: Int) {
            cache.query(frameIdx)
        }

        fun materializeFrame(g2: Graphics2D, frameIdx: Int) {
            for (resp in cache.query(frameIdx)) when (resp) {
                is Response.Image -> @Suppress("NAME_SHADOWING") g2.withNewG2 { g2 ->
                    g2.setHighQuality()
                    g2.blend(resp.alpha) { g2.drawDeferredImage(resp.image, layers, resp.shift, video.height) }
                }
                is Response.AlignedRender ->
                    g2.blend(resp.alpha) { g2.drawImage(resp.render, 0, -resp.shift, null) }
                is Response.UnalignedRender ->
                    g2.blend(resp.alpha) { g2.drawImage(resp.render, getTranslateInstance(0.0, -resp.shift), null) }
            }
        }

    }


    class VideoWriterBackend(
        private val videoWriter: VideoWriter,
        video: DeferredVideo,
        private val layers: List<DeferredImage.Layer>,
        private val grounding: Color?,
        sequentialAccess: Boolean = false,
        preloading: Boolean = false
    ) {

        private class Render(val bufImage: BufferedImage, val prepFrame: VideoWriter.PreparedFrame)

        private val workWidth = VideoWriter.closestWorkResolution(video.width)
        private val workHeight = VideoWriter.closestWorkResolution(video.height)

        private val cache = object : Cache<Render>(video, draft = false, sequentialAccess, preloading) {
            override fun createRender(image: DeferredImage, shift: Double, height: Int): Render {
                val h = VideoWriter.closestWorkResolution(height)
                val transparentBufImage = drawImage(workWidth, h, grounding = null) { g2 ->
                    g2.drawDeferredImage(image, layers, shift, h)
                }
                val groundedBufImage = if (grounding == null) transparentBufImage else
                    drawImage(workWidth, h, grounding) { g2 -> g2.drawImage(transparentBufImage, 0, 0, null) }
                return Render(transparentBufImage, videoWriter.prepareFrame(groundedBufImage))
            }
        }

        private val blankPrepFrame = videoWriter.prepareFrame(drawImage(workWidth, workHeight, grounding) {})

        fun writeFrame(frameIdx: Int) {
            val responses = cache.query(frameIdx)
            val r = responses.singleOrNull()
            when {
                responses.isEmpty() -> videoWriter.writeFrame(blankPrepFrame, 0)
                r is Response.AlignedRender && r.alpha == 1.0 -> videoWriter.writeFrame(r.render.prepFrame, r.shift)
                else -> videoWriter.writeFrame(drawResponsesImage(responses))
            }
        }

        private fun drawResponsesImage(responses: List<Response<Render>>) =
            drawImage(workWidth, workHeight, grounding) { g2 ->
                for (resp in responses) when (resp) {
                    is Response.Image ->
                        g2.blend(resp.alpha) { g2.drawDeferredImage(resp.image, layers, resp.shift, workHeight) }
                    is Response.AlignedRender ->
                        g2.blend(resp.alpha) { g2.drawImage(resp.render.bufImage, 0, -resp.shift, null) }
                    is Response.UnalignedRender -> throw IllegalStateException()
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
            for ((insnIdx, insn) in video.instructions.withIndex())
                if (frameIdx in insn.firstFrameIdx..insn.lastFrameIdx)
                    add(queryForInstruction(frameIdx, insnIdx, insn))
        }

        private fun queryForInstruction(frameIdx: Int, insnIdx: Int, insn: Instruction): Response<R> {
            val relFrameIdx = frameIdx - insn.firstFrameIdx
            val shift = insn.shifts[relFrameIdx]
            val alpha = insn.alphas[relFrameIdx]

            val firstChunkIdx = firstChunkIndices[insnIdx]
            val lastChunkIdx = lastChunkIndices[insnIdx]
            val chunkIdx = (firstChunkIdx + ((floor(shift).toInt() - chunks[firstChunkIdx].shift) / chunkSpacing))
                .coerceIn(firstChunkIdx, lastChunkIdx)  // Should not be necessary, but better be safe.

            // In sequential mode, free the previous chunk's cached renders.
            if (sequentialAccess)
                chunks.getOrNull(chunkIdx - 1)?.let { chunk -> chunk.withLock { chunk.microShiftedRenders?.clear() } }

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
            ) PRELOADING_EXECUTOR.submit { loadChunk(chunk) }
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

            fun <T> withLock(block: () -> T): T = if (lock == null) block() else lock.withLock(block)
        }

    }

}
