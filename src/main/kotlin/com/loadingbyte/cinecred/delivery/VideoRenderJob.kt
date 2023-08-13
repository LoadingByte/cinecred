package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.imaging.VideoContainerFormat
import com.loadingbyte.cinecred.imaging.VideoWriter
import com.loadingbyte.cinecred.project.Project
import org.apache.commons.io.FileUtils
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import kotlin.io.path.exists


class VideoRenderJob(
    private val project: Project,
    private val video: DeferredVideo,
    private val transparentGrounding: Boolean,
    private val resolutionScaling: Double,
    private val fpsScaling: Int,
    private val scan: Bitmap.Scan,
    private val colorSpace: ColorSpace,
    private val format: Format,
    private val fileOrPattern: Path
) : RenderJob {

    override val prefix: Path
        get() = when {
            format.fileSeq -> fileOrPattern.parent
            else -> fileOrPattern
        }

    override fun render(progressCallback: (Int) -> Unit) {
        // If we have an image sequence, delete the sequence directory if it already exists.
        if (format.fileSeq && fileOrPattern.parent.exists())
            FileUtils.cleanDirectory(fileOrPattern.parent.toFile())
        // Make sure that the parent directory exists.
        fileOrPattern.parent.createDirectoriesSafely()

        val grounding = if (transparentGrounding) null else project.styling.global.grounding
        val scaledVideo = video.copy(resolutionScaling, fpsScaling)

        // If we're embedding tapes whose bit depth exceeds 8, automatically increase the render's bit depth to ensure
        // that no information is lost.
        val deeperPixelFormat = if (transparentGrounding) format.deeperAlphaPixelFormat else format.deeperPixelFormat
        val pixelFormat = if (deeperPixelFormat != null &&
            video.collectTapeSpans(listOf(TAPES)).any { it.embeddedTape.tape.exceeds8Bit }
        ) deeperPixelFormat else if (transparentGrounding) format.alphaPixelFormat!! else format.pixelFormat

        val spec = Bitmap.Spec(
            scaledVideo.resolution,
            Bitmap.Representation(
                pixelFormat,
                // RGB image sequences are always in full range.
                if (pixelFormat.isRGB) AVCOL_RANGE_JPEG else colorSpace.range,
                colorSpace.primaries,
                colorSpace.transferCharacteristic,
                if (pixelFormat.isRGB) AVCOL_SPC_RGB else colorSpace.yCbCrCoefficients,
                if (pixelFormat.hasChromaSub) AVCHROMA_LOC_LEFT else AVCHROMA_LOC_UNSPECIFIED,
                isAlphaPremultiplied = false
            ),
            scan,
            if (scan == Bitmap.Scan.PROGRESSIVE) Bitmap.Content.PROGRESSIVE_FRAME else Bitmap.Content.INTERLEAVED_FIELDS
        )

        VideoWriter(
            fileOrPattern, spec, scaledVideo.fps,
            if (transparentGrounding) format.alphaCodecName!! else format.codecName,
            format.codecProfile, format.codecOptions, muxerOptions = emptyMap()
        ).use { videoWriter ->
            DeferredVideo.BitmapBackend(scaledVideo, listOf(STATIC), listOf(TAPES), grounding, spec).use { backend ->
                var frameIdx = 0
                while (true) {
                    (backend.materializeNextFrame() ?: break).use(videoWriter::write)
                    progressCallback(MAX_RENDER_PROGRESS * (frameIdx++ + 1) / scaledVideo.numFrames)
                    if (Thread.interrupted())
                        throw InterruptedException()
                }
            }
        }
    }


    enum class ColorSpace(
        val range: Int,
        val primaries: Int,
        val transferCharacteristic: Int,
        val yCbCrCoefficients: Int
    ) {
        REC_709(AVCOL_RANGE_MPEG, AVCOL_PRI_BT709, AVCOL_TRC_BT709, AVCOL_SPC_BT709),
        SRGB(AVCOL_RANGE_JPEG, AVCOL_PRI_BT709, AVCOL_TRC_IEC61966_2_1, AVCOL_SPC_BT470BG)
    }


    abstract class Format private constructor(
        fileSeq: Boolean,
        fileExts: Set<String>,
        defaultFileExt: String,
        val codecName: String,
        val alphaCodecName: String? = null,
        val codecProfile: Int = FF_PROFILE_UNKNOWN,
        val codecOptions: Map<String, String> = emptyMap(),
        val pixelFormat: Bitmap.PixelFormat,
        val alphaPixelFormat: Bitmap.PixelFormat? = null,
        val deeperPixelFormat: Bitmap.PixelFormat? = null,
        val deeperAlphaPixelFormat: Bitmap.PixelFormat? = null,
        val widthMod2: Boolean = false,
        val heightMod2: Boolean = false,
        val minWidth: Int? = null,
        val minHeight: Int? = null,
        val interlacing: Boolean = false
    ) : RenderFormat(fileSeq, fileExts, defaultFileExt, supportsAlpha = alphaCodecName != null) {

        companion object {

            private fun muxerFileExts(codecId: Int) =
                VideoContainerFormat.WRITER
                    .filter { codecId in it.supportedCodecIds }
                    .flatMapTo(HashSet()) { it.extensions }

            private fun h264() = object : Format(
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_H264),
                defaultFileExt = "mp4",
                codecName = "libx264",
                // Use the main profile so that renders play back on virtually all devices.
                codecProfile = FF_PROFILE_H264_MAIN,
                pixelFormat = Bitmap.PixelFormat.of(AV_PIX_FMT_YUV420P),
                widthMod2 = true,
                heightMod2 = true
            ) {
                override val label get() = "H.264"
                override val notice get() = l10n("delivery.reducedQuality")
            }

            private fun prores(label: String, pixelFormat: Int, alphaPixelFormat: Int?, profile: Int) = object : Format(
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_PRORES),
                defaultFileExt = "mov",
                // prores_aw is faster, but only prores_ks supports 4444 alpha content that is universally compatible.
                codecName = "prores_aw",
                alphaCodecName = if (alphaPixelFormat == null) null else "prores_ks",
                codecProfile = profile,
                pixelFormat = Bitmap.PixelFormat.of(pixelFormat),
                alphaPixelFormat = alphaPixelFormat?.let(Bitmap.PixelFormat::of),
                widthMod2 = true,
                interlacing = true
            ) {
                override val label get() = label
                override val notice get() = null
            }

            private fun dnxhr(label: String, pixelFormat: Int, profile: String) = object : Format(
                fileSeq = false,
                fileExts = muxerFileExts(AV_CODEC_ID_DNXHD),
                defaultFileExt = "mxf",
                codecName = "dnxhd",
                pixelFormat = Bitmap.PixelFormat.of(pixelFormat),
                codecOptions = mapOf("profile" to profile),
                minWidth = 256,
                minHeight = 120
            ) {
                override val label get() = label
                override val notice get() = null
            }

            private fun rgbSeqWithOptionalAlpha(
                fileExt: String,
                deeperPixelFormat: Int,
                deeperAlphaPixelFormat: Int,
                codecOptions: Map<String, String> = emptyMap(),
                labelSuffix: String = "",
                l10nNotice: String? = null
            ) = object : Format(
                fileSeq = true,
                fileExts = setOf(fileExt),
                defaultFileExt = fileExt,
                codecName = fileExt,
                alphaCodecName = fileExt,
                codecOptions = codecOptions,
                pixelFormat = Bitmap.PixelFormat.of(AV_PIX_FMT_RGB24),
                alphaPixelFormat = Bitmap.PixelFormat.of(AV_PIX_FMT_RGBA),
                deeperPixelFormat = Bitmap.PixelFormat.of(deeperPixelFormat),
                deeperAlphaPixelFormat = Bitmap.PixelFormat.of(deeperAlphaPixelFormat),
                interlacing = true
            ) {
                override val label get() = l10n("delivery.imgSeq", fileExt.uppercase()) + labelSuffix
                override val notice get() = l10nNotice?.let(::l10n)
            }

            val ALL = listOf(
                h264(),
                prores("ProRes 422 Proxy", AV_PIX_FMT_YUV422P10, null, FF_PROFILE_PRORES_PROXY),
                prores("ProRes 422 LT", AV_PIX_FMT_YUV422P10, null, FF_PROFILE_PRORES_LT),
                prores("ProRes 422", AV_PIX_FMT_YUV422P10, null, FF_PROFILE_PRORES_STANDARD),
                prores("ProRes 422 HQ", AV_PIX_FMT_YUV422P10, null, FF_PROFILE_PRORES_HQ),
                prores("ProRes 4444", AV_PIX_FMT_YUV444P10, AV_PIX_FMT_YUVA444P10, FF_PROFILE_PRORES_4444),
                prores("ProRes 4444 XQ", AV_PIX_FMT_YUV444P10, AV_PIX_FMT_YUVA444P10, FF_PROFILE_PRORES_XQ),
                dnxhr("DNxHR LB", AV_PIX_FMT_YUV422P, "dnxhr_lb"),
                dnxhr("DNxHR SQ", AV_PIX_FMT_YUV422P, "dnxhr_sq"),
                dnxhr("DNxHR HQ", AV_PIX_FMT_YUV422P, "dnxhr_hq"),
                dnxhr("DNxHR HQX", AV_PIX_FMT_YUV422P10, "dnxhr_hqx"),
                dnxhr("DNxHR 444", AV_PIX_FMT_YUV444P10, "dnxhr_444"),
                // The PNG encoder really only supports big endian when using more than 8 bit per channel.
                rgbSeqWithOptionalAlpha("png", AV_PIX_FMT_RGB48BE, AV_PIX_FMT_RGBA64BE),
                // In the standard TIFF option, we use the PackBits compression algo, which is part of Baseline TIFF
                // and hence supported by every TIFF reader. This is actually also FFmpeg's implicit default.
                rgbSeqWithOptionalAlpha(
                    fileExt = "tiff",
                    // The TIFF encoder really only supports little endian when using more than 8 bit per channel.
                    AV_PIX_FMT_RGB48LE, AV_PIX_FMT_RGBA64LE,
                    codecOptions = mapOf("compression_algo" to "packbits"),
                    labelSuffix = " (PackBits)",
                    l10nNotice = "delivery.packBits"
                ),
                // For those who require more compression and know what they are doing (which can be expected when one
                // exports a TIFF image sequence), we also offer the more efficient, but not universally supported
                // Deflate compression.
                rgbSeqWithOptionalAlpha(
                    fileExt = "tiff",
                    AV_PIX_FMT_RGB48LE, AV_PIX_FMT_RGBA64LE,
                    codecOptions = mapOf("compression_algo" to "deflate"),
                    labelSuffix = " (Deflate)",
                    l10nNotice = "delivery.deflate"
                ),
                rgbSeqWithOptionalAlpha("dpx", AV_PIX_FMT_RGB48, AV_PIX_FMT_RGBA64)
            )

        }

    }

}
