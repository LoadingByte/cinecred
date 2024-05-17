package com.loadingbyte.cinecred.delivery

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.delivery.RenderFormat.Channels.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Config
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.choice
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.fixed
import com.loadingbyte.cinecred.delivery.RenderFormat.DNxHRProfile.*
import com.loadingbyte.cinecred.delivery.RenderFormat.ProResProfile.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.CHANNELS
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.COLOR_PRESET
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DNXHR_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRORES_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.RESOLUTION_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.Project
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import kotlin.math.pow


class VideoContainerRenderJob private constructor(
    private val format: Format,
    private val config: Config,
    private val project: Project,
    private val video: DeferredVideo,
    private val file: Path
) : RenderJob {

    override val prefix: Path
        get() = file

    override fun render(progressCallback: (Int) -> Unit) {
        // Make sure that the parent directory exists.
        file.parent.createDirectoriesSafely()

        val matte = config[CHANNELS] == ALPHA
        val colorPreset = if (matte) null else config[COLOR_PRESET]
        val scan = config[SCAN]
        val settings = format.videoWriterSettings(config)
        val grounding = if (config[CHANNELS] == COLOR) project.styling.global.grounding else null
        val scaledVideo = video.copy(2.0.pow(config[RESOLUTION_SCALING_LOG2]), config[FPS_SCALING])

        val writerSpec = Bitmap.Spec(
            scaledVideo.resolution,
            Bitmap.Representation(
                settings.pixelFormat,
                colorPreset?.yuvRange ?: Bitmap.Range.FULL,
                colorPreset?.colorSpace ?: ColorSpace.of(ColorSpace.Primaries.BT709, ColorSpace.Transfer.LINEAR),
                colorPreset?.yuvCoefficients ?: Bitmap.YUVCoefficients.of(AVCOL_SPC_BT709),
                if (settings.pixelFormat.hasChromaSub) AVCHROMA_LOC_LEFT else AVCHROMA_LOC_UNSPECIFIED,
                if (config[CHANNELS] == COLOR_AND_ALPHA) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
            ),
            scan,
            if (scan == Bitmap.Scan.PROGRESSIVE) Bitmap.Content.PROGRESSIVE_FRAME else Bitmap.Content.INTERLEAVED_FIELDS
        )

        var backendSpec = writerSpec
        var blackWriterBitmap: Bitmap? = null
        if (matte) {
            val backendPxFmtCode = when (val depth = writerSpec.representation.pixelFormat.components[0].depth) {
                8 -> AV_PIX_FMT_GBRAP
                10 -> AV_PIX_FMT_GBRAP10
                12 -> AV_PIX_FMT_GBRAP12
                16 -> AV_PIX_FMT_GBRAP16
                else -> throw IllegalArgumentException("No color format for depth $depth.")
            }
            val backendRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(backendPxFmtCode), ColorSpace.BLENDING, Bitmap.Alpha.PREMULTIPLIED
            )
            val rgbRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(AV_PIX_FMT_GBRPF32), ColorSpace.XYZD50, Bitmap.Alpha.OPAQUE
            )
            backendSpec = writerSpec.copy(representation = backendRep)
            blackWriterBitmap = Bitmap.allocate(writerSpec)
            Bitmap.allocate(writerSpec.copy(representation = rgbRep)).zero()
                .use { BitmapConverter.convert(it, blackWriterBitmap) }
        }

        DeferredVideo.BitmapBackend(scaledVideo, listOf(STATIC), listOf(TAPES), grounding, backendSpec).use { backend ->
            for ((i, codecName) in settings.codecNames.withIndex())
                try {
                    VideoWriter(
                        file, writerSpec, scaledVideo.fps, codecName, settings.codecProfile, settings.codecOptions,
                        emptyMap()
                    ).use { videoWriter ->
                        for (frameIdx in 0..<scaledVideo.numFrames) {
                            backend.materializeFrame(frameIdx)!!.use { colorBitmap ->
                                if (!matte)
                                    videoWriter.write(colorBitmap)
                                else
                                    Bitmap.allocate(writerSpec).zero().use { matteBitmap ->
                                        matteBitmap.blit(blackWriterBitmap!!)
                                        matteBitmap.blitComponent(colorBitmap, 3, 0)
                                        videoWriter.write(matteBitmap)
                                    }
                            }
                            progressCallback(MAX_RENDER_PROGRESS * (frameIdx + 1) / scaledVideo.numFrames)
                            if (Thread.interrupted())
                                throw InterruptedException()
                        }
                    }
                } catch (e: FFmpegException) {
                    // The VideoToolbox codecs might fail on macOS. If that happens, fall back to CPU-based encoding.
                    if (i != settings.codecNames.lastIndex)
                        LOGGER.warn("Falling back to next encoder because '$codecName' did not work: ${e.message}")
                    else
                        throw e
                }
        }

        blackWriterBitmap?.close()
    }


    companion object {

        val H264: RenderFormat = H26XFormat(
            "H.264", AV_CODEC_ID_H264, "libx264", "h264_videotoolbox", FF_PROFILE_H264_HIGH, FF_PROFILE_H264_HIGH_10
        )
        val H265: RenderFormat = H26XFormat(
            "H.265", AV_CODEC_ID_H265, "libx265", "hevc_videotoolbox", FF_PROFILE_HEVC_MAIN, FF_PROFILE_HEVC_MAIN_10
        )

        val FORMATS = listOf(H264, H265, ProResFormat(), DNxHRFormat())

        private fun allChannelsAndColorPreset() =
            choice(CHANNELS, COLOR, COLOR_AND_ALPHA) * choice(COLOR_PRESET) + fixed(CHANNELS, ALPHA)

        private fun opaqueChannelsAndColorPreset() =
            choice(CHANNELS, COLOR) * choice(COLOR_PRESET) + fixed(CHANNELS, ALPHA)

    }


    private abstract class Format(
        label: String,
        codecId: Int,
        defaultFileExt: String,
        configAssortment: Config.Assortment,
        widthMod2: Boolean = false,
        heightMod2: Boolean = false,
        minWidth: Int? = null,
        minHeight: Int? = null
    ) : RenderFormat(
        label,
        fileSeq = false,
        fileExts = VideoContainerFormat.WRITER
            .filter { codecId in it.supportedCodecIds }
            .flatMapTo(HashSet()) { it.extensions },
        defaultFileExt,
        configAssortment * choice(RESOLUTION_SCALING_LOG2) * choice(FPS_SCALING),
        widthMod2, heightMod2, minWidth, minHeight
    ) {

        abstract fun videoWriterSettings(config: Config): VideoWriterSettings

        override fun createRenderJob(
            config: Config,
            project: Project,
            pageDefImages: List<DeferredImage>?,
            video: DeferredVideo?,
            fileOrDir: Path,
            filenamePattern: String?
        ) = VideoContainerRenderJob(this, config, project, video!!, fileOrDir)

    }


    private class VideoWriterSettings(
        val codecNames: List<String>,
        val codecProfile: Int,
        val codecOptions: Map<String, String>,
        val pixelFormat: Bitmap.PixelFormat
    )


    private class H26XFormat(
        label: String,
        codecId: Int,
        private val codecName: String,
        private val macCodecName: String,
        private val codecProfile8: Int,
        private val codecProfile10: Int
    ) : Format(
        label, codecId, "mp4",
        opaqueChannelsAndColorPreset() * choice(DEPTH, 8, 10) * fixed(SCAN, Bitmap.Scan.PROGRESSIVE),
        widthMod2 = true,
        heightMod2 = true
    ) {
        override fun videoWriterSettings(config: Config): VideoWriterSettings {
            val depth = config[DEPTH]
            val codecNames = if (SystemInfo.isMacOS) listOf(macCodecName, codecName) else listOf(codecName)
            val codecProfile = if (depth == 8) codecProfile8 else codecProfile10
            val pixelFormatCode = if (depth == 8) AV_PIX_FMT_YUV420P else AV_PIX_FMT_YUV420P10
            return VideoWriterSettings(codecNames, codecProfile, emptyMap(), Bitmap.PixelFormat.of(pixelFormatCode))
        }
    }


    private class ProResFormat : Format(
        "ProRes", AV_CODEC_ID_PRORES, "mov",
        allChannelsAndColorPreset() * fixed(DEPTH, 10) * choice(SCAN) * choice(PRORES_PROFILE) -
                fixed(CHANNELS, COLOR_AND_ALPHA) *
                choice(PRORES_PROFILE, PRORES_422_PROXY, PRORES_422_LT, PRORES_422, PRORES_422_HQ),
        widthMod2 = true
    ) {
        override fun videoWriterSettings(config: Config): VideoWriterSettings {
            val profile = config[PRORES_PROFILE]
            val embedAlpha = config[CHANNELS] == COLOR_AND_ALPHA
            val is4444 = profile == PRORES_4444 || profile == PRORES_4444_XQ
            // prores_aw is faster, but only prores_ks supports 4444 alpha content that is universally compatible.
            val codecName = if (is4444 && embedAlpha) "prores_ks" else "prores_aw"
            val codecNames = if (SystemInfo.isMacOS) listOf("prores_videotoolbox", codecName) else listOf(codecName)
            val codecProfile = when (profile) {
                PRORES_422_PROXY -> FF_PROFILE_PRORES_PROXY
                PRORES_422_LT -> FF_PROFILE_PRORES_LT
                PRORES_422 -> FF_PROFILE_PRORES_STANDARD
                PRORES_422_HQ -> FF_PROFILE_PRORES_HQ
                PRORES_4444 -> FF_PROFILE_PRORES_4444
                PRORES_4444_XQ -> FF_PROFILE_PRORES_XQ
            }
            val pixelFormatCode =
                if (!is4444) AV_PIX_FMT_YUV422P10 else if (!embedAlpha) AV_PIX_FMT_YUV444P10 else AV_PIX_FMT_YUVA444P10
            return VideoWriterSettings(codecNames, codecProfile, emptyMap(), Bitmap.PixelFormat.of(pixelFormatCode))
        }
    }


    private class DNxHRFormat : Format(
        "DNxHR", AV_CODEC_ID_DNXHD, "mxf",
        opaqueChannelsAndColorPreset() * fixed(SCAN, Bitmap.Scan.PROGRESSIVE) *
                (choice(DNXHR_PROFILE, DNXHR_LB, DNXHR_SQ, DNXHR_HQ) * fixed(DEPTH, 8) +
                        choice(DNXHR_PROFILE, DNXHR_HQX, DNXHR_444) * fixed(DEPTH, 10)),
        minWidth = 256,
        minHeight = 120
    ) {
        override fun videoWriterSettings(config: Config): VideoWriterSettings {
            val profile = config[DNXHR_PROFILE]
            val codecProfileOption = when (profile) {
                DNXHR_LB -> "dnxhr_lb"
                DNXHR_SQ -> "dnxhr_sq"
                DNXHR_HQ -> "dnxhr_hq"
                DNXHR_HQX -> "dnxhr_hqx"
                DNXHR_444 -> "dnxhr_444"
            }
            val pixelFormatCode = when (profile) {
                DNXHR_HQX -> AV_PIX_FMT_YUV422P10
                DNXHR_444 -> AV_PIX_FMT_YUV444P10
                else -> AV_PIX_FMT_YUV422P
            }
            val px = Bitmap.PixelFormat.of(pixelFormatCode)
            return VideoWriterSettings(listOf("dnxhd"), FF_PROFILE_UNKNOWN, mapOf("profile" to codecProfileOption), px)
        }
    }

}
