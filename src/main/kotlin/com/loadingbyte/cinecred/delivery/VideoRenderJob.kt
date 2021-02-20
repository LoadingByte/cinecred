package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.drawer.VideoDrawer
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
import org.apache.commons.io.FileUtils
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path


class VideoRenderJob(
    private val project: Project,
    private val drawnPages: List<DrawnPage>,
    private val scaling: Float,
    private val transparent: Boolean,
    private val format: Format,
    fileOrDir: Path
) : RenderJob {

    val fileOrPattern: Path = when {
        format.isImageSeq -> fileOrDir.resolve("${fileOrDir.fileName}.%07d.${format.fileExts[0]}")
        else -> fileOrDir
    }

    override fun generatesFile(file: Path) = when {
        format.isImageSeq -> file.startsWith(fileOrPattern.parent)
        else -> file == fileOrPattern
    }

    override fun render(progressCallback: (Float) -> Unit) {
        // If we have an image sequence, delete the sequence directory if it already exists.
        if (format.isImageSeq && Files.exists(fileOrPattern.parent))
            FileUtils.cleanDirectory(fileOrPattern.parent.toFile())
        // Make sure that the parent directory exists.
        Files.createDirectories(fileOrPattern.parent)

        val imageType = if (transparent) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR

        val videoDrawer = object : VideoDrawer(project, drawnPages, scaling, transparent) {
            override fun createIntermediateImage(width: Int, height: Int) = BufferedImage(width, height, imageType)
        }

        VideoWriter(
            fileOrPattern, videoDrawer.width, videoDrawer.height, project.styling.global.fps,
            format.codecId, if (transparent) format.alphaPixelFormat!! else format.pixelFormat,
            muxerOptions = emptyMap(),
            format.codecOptions
        ).use { videoWriter ->
            var prevProgress = 0f

            for (frameIdx in 0 until videoDrawer.numFrames) {
                val frame = BufferedImage(videoDrawer.width, videoDrawer.height, imageType).withG2 { g2 ->
                    g2.setHighQuality()
                    videoDrawer.drawFrame(g2, frameIdx)
                }
                videoWriter.writeFrame(frame)

                val progress = frameIdx.toFloat() / videoDrawer.numFrames
                if (progress >= prevProgress + 0.01f) {
                    prevProgress = progress
                    progressCallback(progress)
                }

                if (Thread.interrupted())
                    throw InterruptedException()
            }
        }
    }


    class Format private constructor(
        label: String,
        val isImageSeq: Boolean,
        fileExts: List<String>,
        val codecId: Int,
        val pixelFormat: Int,
        val alphaPixelFormat: Int? = null,
        val codecOptions: Map<String, String> = emptyMap(),
        val widthMod2: Boolean = false,
        val heightMod2: Boolean = false,
        val minWidth: Int? = null,
        val minHeight: Int? = null
    ) : RenderFormat(label, fileExts) {

        val supportsAlpha = alphaPixelFormat != null

        companion object {

            private fun muxerFileExts(codecId: Int, defaultFileExt: String) =
                listOf(defaultFileExt) +
                        MuxerFormat.ALL
                            .filter { codecId in it.supportedCodecIds }
                            .flatMap { it.extensions }
                            .filter { it != defaultFileExt }
                            .toSortedSet()

            private fun prores(label: String, profile: String) = Format(
                label, isImageSeq = false, muxerFileExts(AV_CODEC_ID_PRORES, "mov"), AV_CODEC_ID_PRORES,
                AV_PIX_FMT_YUV422P10LE, codecOptions = mapOf("profile" to profile), widthMod2 = true
            )

            private fun dnxhr(label: String, pixelFormat: Int, profile: String) = Format(
                label, isImageSeq = false, muxerFileExts(AV_CODEC_ID_DNXHD, "mxf"), AV_CODEC_ID_DNXHD,
                pixelFormat, codecOptions = mapOf("profile" to profile), minWidth = 256, minHeight = 120
            )

            private fun rgbSeqWithOptionalAlpha(
                label: String, fileExt: String, codecId: Int, codecOptions: Map<String, String> = emptyMap()
            ) = Format(
                label, isImageSeq = true, listOf(fileExt), codecId, AV_PIX_FMT_RGB24, AV_PIX_FMT_RGBA, codecOptions
            )

            val ALL = listOf(
                Format(
                    "H.264", isImageSeq = false, muxerFileExts(AV_CODEC_ID_H264, "mp4"), AV_CODEC_ID_H264,
                    AV_PIX_FMT_YUV420P, widthMod2 = true, heightMod2 = true
                ),
                prores("ProRes 422 Proxy", "0"),
                prores("ProRes 422 LT", "1"),
                prores("ProRes 422", "2"),
                prores("ProRes 422 HQ", "3"),
                dnxhr("DNxHR LB", AV_PIX_FMT_YUV422P, "dnxhr_lb"),
                dnxhr("DNxHR SQ", AV_PIX_FMT_YUV422P, "dnxhr_sq"),
                dnxhr("DNxHR HQ", AV_PIX_FMT_YUV422P, "dnxhr_hq"),
                dnxhr("DNxHR HQX", AV_PIX_FMT_YUV422P10LE, "dnxhr_hqx"),
                // In the standard TIFF option, we use the PackBits compression algo, which is part of Baseline TIFF
                // and hence supported by every TIFF reader. This is actually also FFmpeg's implicit default.
                rgbSeqWithOptionalAlpha(
                    l10n("delivery.packBits", l10n("delivery.imgSeq", "TIFF")),
                    "tiff", AV_CODEC_ID_TIFF, mapOf("compression_algo" to "packbits")
                ),
                // For those who require more compression and know what they are doing (which can be expected when one
                // exports a TIFF image sequence), we also offer the more efficient, but not universally supported
                // Deflate compression.
                rgbSeqWithOptionalAlpha(
                    l10n("delivery.deflate", l10n("delivery.imgSeq", "TIFF")),
                    "tiff", AV_CODEC_ID_TIFF, mapOf("compression_algo" to "deflate")
                ),
                rgbSeqWithOptionalAlpha(l10n("delivery.imgSeq", "DPX"), "dpx", AV_CODEC_ID_DPX),
                rgbSeqWithOptionalAlpha(l10n("delivery.imgSeq", "PNG"), "png", AV_CODEC_ID_PNG),
            )

        }

    }

}
