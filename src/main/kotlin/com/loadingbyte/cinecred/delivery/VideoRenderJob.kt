package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project
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
    private val drawnPages: List<DrawnPage>,
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
        val pageImages = drawnPages.map { drawnPage ->
            drawImage(videoWidth, drawnPage.defImage.height.roundToInt()) { g2 ->
                drawnPage.defImage.materialize(g2, drawGuides = false)
            }
        }

        val totalNumFrames = getRuntimeFrames(project, drawnPages)

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

            fun writeStatic(image: BufferedImage, imgTopY: Float, numFrames: Int) {
                val scrolledImage = drawScrolledImage(videoWidth, videoHeight, image, imgTopY)
                for (frame in 0 until numFrames) {
                    videoWriter.writeFrame(scrolledImage)
                    updateProgress()
                }
            }

            fun writeFade(image: BufferedImage, imgTopY: Float, numFrames: Int, fadeOut: Boolean) {
                val scrolledImage = drawScrolledImage(videoWidth, videoHeight, image, imgTopY)
                for (frame in 0 until numFrames) {
                    // Choose alpha such that the fade sequence doesn't contain a fully empty or fully opaque frame.
                    var alpha = (frame + 1).toFloat() / (numFrames + 1)
                    if (fadeOut)
                        alpha = 1f - alpha
                    val fadedImage = drawImage(videoWidth, videoHeight) { g2 ->
                        g2.composite = AlphaComposite.SrcOver.derive(alpha)
                        g2.drawImage(scrolledImage, 0, 0, null)
                    }
                    videoWriter.writeFrame(fadedImage)
                    updateProgress()
                }
            }

            /**
             * Returns the `imgTopY` where the scroll actually stopped.
             */
            fun writeScroll(
                image: BufferedImage, imgTopYStart: Float, imgTopYStop: Float, scrollPxPerFrame: Float
            ): Float {
                // Choose imgTopY such that the scroll sequence never contains imgTopYStart nor imgTopYStop.
                var imgTopY = imgTopYStart
                while (imgTopY + scrollPxPerFrame < imgTopYStop) {
                    imgTopY += scrollPxPerFrame
                    val scrolledImage = drawScrolledImage(videoWidth, videoHeight, image, imgTopY)
                    videoWriter.writeFrame(scrolledImage)
                    updateProgress()
                }
                return imgTopY
            }

            val emptyImage = drawImage(videoWidth, videoHeight) {}

            // Write frames for each page as has been configured.
            for ((pageIdx, page) in project.pages.withIndex()) {
                val pageImage = pageImages[pageIdx]
                val stageInfo = drawnPages[pageIdx].stageInfo
                var prevScrollActualImgTopYStop: Float? = null
                for ((stageIdx, stage) in page.stages.withIndex())
                    when (val info = stageInfo[stageIdx]) {
                        is DrawnStageInfo.Card -> {
                            val imgTopY = scaling * (info.middleY - project.styling.global.heightPx / 2f)
                            if (stageIdx == 0)
                                writeFade(pageImage, imgTopY, stage.style.cardFadeInFrames, fadeOut = false)
                            writeStatic(pageImage, imgTopY, stage.style.cardDurationFrames)
                            if (stageIdx == page.stages.lastIndex)
                                writeFade(pageImage, imgTopY, stage.style.cardFadeOutFrames, fadeOut = true)
                            prevScrollActualImgTopYStop = null
                        }
                        is DrawnStageInfo.Scroll -> {
                            val imgTopYStart = prevScrollActualImgTopYStop
                                ?: scaling * (info.scrollStartY - project.styling.global.heightPx / 2f)
                            val imgTopYStop = scaling * (info.scrollStopY - project.styling.global.heightPx / 2f)
                            prevScrollActualImgTopYStop = writeScroll(
                                pageImage, imgTopYStart, imgTopYStop, scaling * stage.style.scrollPxPerFrame
                            )
                        }
                    }

                if (pageIdx != project.pages.lastIndex)
                    writeStatic(emptyImage, 0f, page.stages.last().style.afterwardSlugFrames)
            }

            // Write one final empty frame at the end.
            videoWriter.writeFrame(emptyImage)
            updateProgress()
        }
    }

    private inline fun drawImage(width: Int, height: Int, draw: (Graphics2D) -> Unit): BufferedImage {
        val imageType = if (alpha) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR
        return BufferedImage(width, height, imageType).withG2 { g2 ->
            g2.setHighQuality()
            if (!alpha) {
                g2.color = project.styling.global.background
                g2.fillRect(0, 0, width, height)
            }
            draw(g2)
        }
    }

    private fun drawScrolledImage(width: Int, height: Int, image: BufferedImage, imgTopY: Float) =
        drawImage(width, height) { g2 ->
            val translate = AffineTransform.getTranslateInstance(0.0, -imgTopY.toDouble())
            g2.drawImage(image, translate, null)
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

            private fun rgbSeqWithOptionalAlpha(
                label: String, fileExt: String, codecId: Int, codecOptions: Map<String, String> = emptyMap()
            ) = Format(
                label, isImageSeq = true, listOf(fileExt), codecId, AV_PIX_FMT_RGB24, AV_PIX_FMT_RGBA, codecOptions
            )

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
                // In the standard TIFF option, we use the PackBits compression algo, which is part of Baseline TIFF
                // and hence supported by every TIFF reader. This is actually also FFmpeg's implicit default.
                rgbSeqWithOptionalAlpha(
                    l10n("delivery.tiffPackBitsImgSeq"), "tiff", AV_CODEC_ID_TIFF,
                    mapOf("compression_algo" to "packbits")
                ),
                // For those who require more compression and know what they are doing (which can be expected when one
                // exports a TIFF image sequence), we also offer the more efficient, but not universally supported
                // Deflate compression.
                rgbSeqWithOptionalAlpha(
                    l10n("delivery.tiffDeflateImgSeq"), "tiff", AV_CODEC_ID_TIFF, mapOf("compression_algo" to "deflate")
                ),
                rgbSeqWithOptionalAlpha(l10n("delivery.dpxImgSeq"), "dpx", AV_CODEC_ID_DPX),
                rgbSeqWithOptionalAlpha(l10n("delivery.pngImgSeq"), "png", AV_CODEC_ID_PNG),
            )

        }

    }

}
