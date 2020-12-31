package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.ceil


class VideoRenderJob(
    private val project: Project,
    private val pageDefImages: List<DeferredImage>,
    private val scaling: Float,
    private val format: Format,
    private val file: Path
) : RenderJob {

    override val destination: String
        get() = file.toString()

    override fun render(progressCallback: (Float) -> Unit) {
        // Convert the deferred page images to raster images.
        val pageImages = pageDefImages.map { pageDefImage ->
            drawImage(pageDefImage.intWidth, pageDefImage.intHeight) { g2 ->
                pageDefImage.materialize(g2, drawGuides = false, DeferredImage.TextMode.PATH)
            }
        }

        val totalNumFrames = getDurationFrames(project, pageDefImages)

        fun ensureMultipleOf2(i: Int) = if (i % 2 == 1) i + 1 else i
        val videoWidth = ensureMultipleOf2(ceil(project.styling.global.widthPx * scaling).toInt())
        val videoHeight = ensureMultipleOf2(ceil(project.styling.global.heightPx * scaling).toInt())

        VideoWriter(
            file, videoWidth, videoHeight, project.styling.global.fps, format.codecId, format.pixelFormat,
            muxerOptions = emptyMap(), codecOptions = format.codecOptions
        ).use { videoWriter ->
            var prevProgress = 0f
            fun updateProgress() {
                val progress = videoWriter.frameCounter.toFloat() / totalNumFrames
                if (progress > prevProgress + 0.01f) {
                    prevProgress = progress
                    progressCallback(progress)
                }
                if (Thread.interrupted())
                    throw InterruptedException()
            }

            fun writeStatic(image: BufferedImage, numFrames: Int) {
                for (frame in 0 until numFrames) {
                    videoWriter.writeFrame(image)
                    updateProgress()
                }
            }

            fun writeFade(image: BufferedImage, numFrames: Int, fadeOut: Boolean) {
                for (frame in 0 until numFrames) {
                    // Choose alpha such that the fade sequence doesn't contain a fully empty or fully opaque frame.
                    var alpha = (frame + 1).toFloat() / (numFrames + 1)
                    if (fadeOut)
                        alpha = 1f - alpha
                    val fadedImage = drawImage(videoWidth, videoHeight) { g2 ->
                        g2.composite = AlphaComposite.SrcOver.derive(alpha)
                        g2.drawImage(image, 0, 0, null)
                    }
                    videoWriter.writeFrame(fadedImage)
                    updateProgress()
                }
            }

            fun writeScroll(image: BufferedImage, scrollPxPerFrame: Float) {
                // Choose y such that the scroll sequence doesn't contain a fully empty frame.
                var y = videoHeight.toFloat() - scrollPxPerFrame
                while (y + image.height > 0f) {
                    val scrolledImage = drawImage(videoWidth, videoHeight) { g2 ->
                        val translate = AffineTransform.getTranslateInstance(0.0, y.toDouble())
                        g2.drawImage(image, translate, null)
                    }
                    videoWriter.writeFrame(scrolledImage)
                    updateProgress()
                    y -= scrollPxPerFrame
                }
            }

            val emptyImage = drawImage(videoWidth, videoHeight) {}

            // Write frames for each page as has been configured.
            for ((pageIdx, page) in project.pages.withIndex()) {
                val pageImage = pageImages[pageIdx]
                when (page.style.behavior) {
                    PageBehavior.CARD -> {
                        writeFade(pageImage, page.style.cardFadeInFrames, fadeOut = false)
                        writeStatic(pageImage, page.style.cardDurationFrames)
                        writeFade(pageImage, page.style.cardFadeOutFrames, fadeOut = true)
                    }
                    PageBehavior.SCROLL ->
                        writeScroll(pageImage, scaling * page.style.scrollPxPerFrame)
                }

                if (pageIdx != project.pages.size - 1)
                    writeStatic(emptyImage, page.style.afterwardSlugFrames)
            }

            // Write one final empty frame at the end.
            videoWriter.writeFrame(emptyImage)
            updateProgress()
        }
    }

    private inline fun drawImage(width: Int, height: Int, draw: (Graphics2D) -> Unit): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val g2 = image.createGraphics()
        g2.setHighQuality()
        g2.color = project.styling.global.background
        g2.fillRect(0, 0, width, height)
        draw(g2)
        return image
    }


    class Format private constructor(
        val label: String,
        defaultFileExt: String,
        val codecId: Int,
        val pixelFormat: Int,
        val codecOptions: Map<String, String> = emptyMap()
    ) {

        val fileExts = listOf(defaultFileExt) +
                MuxerFormat.ALL
                    .filter { codecId in it.supportedCodecIds }
                    .flatMap { it.extensions }
                    .filter { it != defaultFileExt }
                    .toSortedSet()

        companion object {
            val ALL = listOf(
                Format("H.264", "mp4", AV_CODEC_ID_H264, AV_PIX_FMT_YUV420P),
                Format("ProRes 422 Proxy", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "0")),
                Format("ProRes 422 LT", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "1")),
                Format("ProRes 422", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "2")),
                Format("ProRes 422 HQ", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "3")),
                Format("DNxHR LB", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_lb")),
                Format("DNxHR SQ", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_sq")),
                Format("DNxHR HQ", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_hq")),
                Format("DNxHR HQX", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "dnxhr_hqx"))
            )
        }

    }

}
