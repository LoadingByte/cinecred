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
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists


class VideoRenderJob(
    private val project: Project,
    private val drawnPages: List<DrawnPage>,
    private val scaling: Float,
    private val transparentGrounding: Boolean,
    private val format: Format,
    fileOrDir: Path
) : RenderJob {

    val fileOrPattern: Path = when {
        format.fileSeq -> fileOrDir.resolve("${fileOrDir.fileName}.%07d.${format.fileExts.single()}")
        else -> fileOrDir
    }

    override fun generatesFile(file: Path) = when {
        format.fileSeq -> file.startsWith(fileOrPattern.parent)
        else -> file == fileOrPattern
    }

    override fun render(progressCallback: (Float) -> Unit) {
        // If we have an image sequence, delete the sequence directory if it already exists.
        if (format.fileSeq && fileOrPattern.parent.exists())
            FileUtils.cleanDirectory(fileOrPattern.parent.toFile())
        // Make sure that the parent directory exists.
        fileOrPattern.parent.createDirectories()

        val imageType = if (transparentGrounding) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR

        val videoDrawer = object : VideoDrawer(project, drawnPages, scaling, transparentGrounding) {
            override fun createIntermediateImage(width: Int, height: Int) = BufferedImage(width, height, imageType)
        }

        VideoWriter(
            fileOrPattern, videoDrawer.width, videoDrawer.height, project.styling.global.fps,
            format.codecId, if (transparentGrounding) format.alphaPixelFormat!! else format.pixelFormat,
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
        fileSeq: Boolean,
        fileExts: Set<String>,
        defaultFileExt: String,
        val codecId: Int,
        val pixelFormat: Int,
        val alphaPixelFormat: Int? = null,
        val codecOptions: Map<String, String> = emptyMap(),
        val widthMod2: Boolean = false,
        val heightMod2: Boolean = false,
        val minWidth: Int? = null,
        val minHeight: Int? = null
    ) : RenderFormat(label, fileSeq, fileExts, defaultFileExt, supportsAlpha = alphaPixelFormat != null) {

        companion object {

            private fun muxerFileExts(codecId: Int) =
                MuxerFormat.ALL
                    .filter { codecId in it.supportedCodecIds }
                    .flatMapTo(HashSet()) { it.extensions }

            private fun h264() = Format(
                label = "H.264",
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_H264),
                defaultFileExt = "mp4",
                codecId = AV_CODEC_ID_H264,
                pixelFormat = AV_PIX_FMT_YUV420P,
                widthMod2 = true,
                heightMod2 = true
            )

            private fun prores(label: String, profile: String) = Format(
                label = label,
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_PRORES),
                defaultFileExt = "mov",
                codecId = AV_CODEC_ID_PRORES,
                pixelFormat = AV_PIX_FMT_YUV422P10LE,
                codecOptions = mapOf("profile" to profile),
                widthMod2 = true
            )

            private fun dnxhr(label: String, pixelFormat: Int, profile: String) = Format(
                label = label,
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_DNXHD),
                defaultFileExt = "mxf",
                codecId = AV_CODEC_ID_DNXHD,
                pixelFormat = pixelFormat,
                codecOptions = mapOf("profile" to profile),
                minWidth = 256,
                minHeight = 120
            )

            private fun rgbSeqWithOptionalAlpha(
                label: String,
                fileExt: String,
                codecId: Int,
                codecOptions: Map<String, String> = emptyMap()
            ) = Format(
                label = label,
                fileSeq = true,
                fileExts = setOf(fileExt),
                defaultFileExt = fileExt,
                codecId = codecId,
                pixelFormat = AV_PIX_FMT_RGB24,
                alphaPixelFormat = AV_PIX_FMT_RGBA,
                codecOptions = codecOptions
            )

            val ALL = listOf(
                h264(),
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
                    label = l10n("delivery.packBits", l10n("delivery.imgSeq", "TIFF")),
                    fileExt = "tiff",
                    codecId = AV_CODEC_ID_TIFF,
                    codecOptions = mapOf("compression_algo" to "packbits")
                ),
                // For those who require more compression and know what they are doing (which can be expected when one
                // exports a TIFF image sequence), we also offer the more efficient, but not universally supported
                // Deflate compression.
                rgbSeqWithOptionalAlpha(
                    label = l10n("delivery.deflate", l10n("delivery.imgSeq", "TIFF")),
                    fileExt = "tiff",
                    codecId = AV_CODEC_ID_TIFF,
                    codecOptions = mapOf("compression_algo" to "deflate")
                ),
                rgbSeqWithOptionalAlpha(
                    label = l10n("delivery.imgSeq", "DPX"),
                    fileExt = "dpx",
                    codecId = AV_CODEC_ID_DPX
                ),
                rgbSeqWithOptionalAlpha(
                    label = l10n("delivery.imgSeq", "PNG"),
                    fileExt = "png",
                    codecId = AV_CODEC_ID_PNG
                )
            )

        }

    }

}
