package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.commons.io.FileUtils
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt


class VideoRenderJob(
    private val project: Project,
    private val pageDefImages: List<DeferredImage>,
    private val scaling: Float,
    private val alpha: Boolean,
    private val format: Format,
    fileOrDir: Path
) : RenderJob {

    val fileOrPattern: Path = when {
        format.isImageSeq -> fileOrDir.resolve("${fileOrDir.fileName}.%07d.${format.fileExts[0]}")
        else -> fileOrDir
    }

    override fun render(progressCallback: (Float) -> Unit) {
        // If we have an image sequence, delete the sequence directory if it already exists.
        if (format.isImageSeq && Files.exists(fileOrPattern.parent))
            FileUtils.cleanDirectory(fileOrPattern.parent.toFile())
        // Make sure that the parent directory exists.
        Files.createDirectories(fileOrPattern.parent)

        fun ensureMultipleOf2(i: Int) = if (i % 2 == 1) i + 1 else i
        val videoWidth = ensureMultipleOf2((project.styling.global.widthPx * scaling).roundToInt())
        val videoHeight = ensureMultipleOf2((project.styling.global.heightPx * scaling).roundToInt())

        // Convert the deferred page images to raster images.
        val pageImages = pageDefImages.map { pageDefImage ->
            drawImage(videoWidth, pageDefImage.height.roundToInt()) { g2 ->
                pageDefImage.materialize(g2, drawGuides = false)
            }
        }

        val totalNumFrames = getRuntimeFrames(project, pageDefImages)

        VideoWriter(
            fileOrPattern, videoWidth, videoHeight, project.styling.global.fps, format.codecId,
            if (alpha) format.alphaPixelFormat!! else format.pixelFormat,
            muxerOptions = emptyMap(),
            format.codecOptions
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

                if (pageIdx != project.pages.lastIndex)
                    writeStatic(emptyImage, page.style.afterwardSlugFrames)
            }

            // Write one final empty frame at the end.
            videoWriter.writeFrame(emptyImage)
            updateProgress()
        }
    }

    private inline fun drawImage(width: Int, height: Int, draw: (Graphics2D) -> Unit): BufferedImage {
        val imageType = if (alpha) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR
        val image = BufferedImage(width, height, imageType)
        // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
        val g2 = GraphicsUtil.createGraphics(image)
        try {
            g2.setHighQuality()
            if (!alpha) {
                g2.color = project.styling.global.background
                g2.fillRect(0, 0, width, height)
            }
            draw(g2)
        } finally {
            g2.dispose()
        }
        return image
    }


    class Format private constructor(
        label: String,
        val isImageSeq: Boolean,
        fileExts: List<String>,
        val codecId: Int,
        val pixelFormat: Int,
        val alphaPixelFormat: Int?,
        val codecOptions: Map<String, String>
    ) : RenderFormat(label, fileExts) {

        val supportsAlpha = alphaPixelFormat != null

        companion object {

            private fun muxed(
                label: String, defaultFileExt: String, codecId: Int, pixelFormat: Int,
                codecOptions: Map<String, String> = emptyMap()
            ) = Format(label, isImageSeq = false,
                listOf(defaultFileExt) +
                        MuxerFormat.ALL
                            .filter { codecId in it.supportedCodecIds }
                            .flatMap { it.extensions }
                            .filter { it != defaultFileExt }
                            .toSortedSet(),
                codecId, pixelFormat, alphaPixelFormat = null, codecOptions)

            private fun seqSuppAlpha(
                label: String, fileExt: String, codecId: Int, pixelFormat: Int, alphaPixelFormat: Int
            ) = Format(label, isImageSeq = true, listOf(fileExt), codecId, pixelFormat, alphaPixelFormat, emptyMap())

            val ALL = listOf(
                muxed("H.264", "mp4", AV_CODEC_ID_H264, AV_PIX_FMT_YUV420P),
                muxed("ProRes 422 Proxy", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "0")),
                muxed("ProRes 422 LT", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "1")),
                muxed("ProRes 422", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "2")),
                muxed("ProRes 422 HQ", "mov", AV_CODEC_ID_PRORES, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "3")),
                muxed("DNxHR LB", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_lb")),
                muxed("DNxHR SQ", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_sq")),
                muxed("DNxHR HQ", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_hq")),
                muxed("DNxHR HQX", "mxf", AV_CODEC_ID_DNXHD, AV_PIX_FMT_YUV422P10LE, mapOf("profile" to "dnxhr_hqx")),
                seqSuppAlpha(l10n("delivery.tiffImgSeq"), "tiff", AV_CODEC_ID_PNG, AV_PIX_FMT_RGB24, AV_PIX_FMT_RGBA),
                seqSuppAlpha(l10n("delivery.dpxImgSeq"), "dpx", AV_CODEC_ID_DPX, AV_PIX_FMT_RGB24, AV_PIX_FMT_RGBA),
                seqSuppAlpha(l10n("delivery.pngImgSeq"), "png", AV_CODEC_ID_PNG, AV_PIX_FMT_RGB24, AV_PIX_FMT_RGBA),
            )

        }

    }

}
