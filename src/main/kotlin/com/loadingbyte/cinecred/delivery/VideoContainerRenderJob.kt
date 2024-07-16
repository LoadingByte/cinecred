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
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DNXHR_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRIMARIES
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRORES_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.RESOLUTION_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSFER
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.YUV
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Bitmap.YUVCoefficients.Companion.BT2020_CL
import com.loadingbyte.cinecred.imaging.Bitmap.YUVCoefficients.Companion.BT2020_NCL
import com.loadingbyte.cinecred.imaging.Bitmap.YUVCoefficients.Companion.BT709_NCL
import com.loadingbyte.cinecred.imaging.Bitmap.YUVCoefficients.Companion.ICTCP
import com.loadingbyte.cinecred.imaging.Bitmap.YUVCoefficients.Companion.SRGB_NCL
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.BT2020
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.BT709
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.DCI_P3
import com.loadingbyte.cinecred.imaging.ColorSpace.Primaries.Companion.DISPLAY_P3
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.BLENDING
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.BT1886
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.HLG
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.LINEAR
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.PQ
import com.loadingbyte.cinecred.imaging.ColorSpace.Transfer.Companion.SRGB
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.Project
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
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
        val allSettings = format.videoWriterSettings(config)
        for ((i, settings) in allSettings.withIndex())
            try {
                render(progressCallback, settings)
                break
            } catch (e: FFmpegException) {
                // If some VideoWriterSettings (e.g., VideoToolbox on macOS) fail, fall back to the next ones.
                if (i != allSettings.lastIndex)
                    LOGGER.warn("Falling back to next encoder since '${settings.codecName}' did not work: ${e.message}")
                else
                    throw e
            }
    }

    private fun render(progressCallback: (Int) -> Unit, settings: VideoWriterSettings) {
        val matte = config[CHANNELS] == ALPHA
        val colorSpace = if (matte) null else ColorSpace.of(config[PRIMARIES], config[TRANSFER])
        val yuv = if (matte) null else config[YUV]
        val ceiling = if (colorSpace?.transfer?.isHDR == true) null else 1f
        val scan = config[SCAN]
        val grounding = if (config[CHANNELS] == COLOR) project.styling.global.grounding else null
        val scaledVideo = video.copy(2.0.pow(config[RESOLUTION_SCALING_LOG2]), config[FPS_SCALING])

        val writerSpec = Bitmap.Spec(
            scaledVideo.resolution,
            Bitmap.Representation(
                settings.pixelFormat,
                if (matte) Bitmap.Range.FULL else Bitmap.Range.LIMITED,
                colorSpace ?: ColorSpace.of(BT709, LINEAR),
                yuv ?: BT709_NCL,
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
                Bitmap.PixelFormat.of(backendPxFmtCode), ColorSpace.of(BT709, BLENDING), Bitmap.Alpha.PREMULTIPLIED
            )
            val rgbRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(AV_PIX_FMT_GBRPF32), ColorSpace.XYZD50, Bitmap.Alpha.OPAQUE
            )
            backendSpec = writerSpec.copy(representation = backendRep)
            blackWriterBitmap = Bitmap.allocate(writerSpec)
            Bitmap.allocate(writerSpec.copy(representation = rgbRep)).zero()
                .use { BitmapConverter.convert(it, blackWriterBitmap) }
        }

        // We have a second thread materialize frames into a queue, and the current thread take frames from the queue
        // and submitting them to the VideoWriter. While this doesn't give us a huge performance boost over doing
        // everything sequentially in the same thread, we gain a bit when a slow encoder (like ProRes) meets an
        // expensive-to-materialize portion of the credits (like a blend).
        val queue = LinkedBlockingQueue<Bitmap>(32)
        val materializer = Thread({
            try {
                DeferredVideo.BitmapBackend(
                    scaledVideo, listOf(STATIC), listOf(TAPES), grounding, backendSpec, ceiling
                ).use { backend ->
                    for (frameIdx in 0..<scaledVideo.numFrames) {
                        val colorBitmap = backend.materializeFrame(frameIdx)!!
                        if (!matte)
                            queue.put(colorBitmap)
                        else {
                            val matteBitmap = Bitmap.allocate(writerSpec).zero()
                            matteBitmap.blit(blackWriterBitmap!!)
                            matteBitmap.blitComponent(colorBitmap, 3, 0)
                            colorBitmap.close()
                            queue.put(matteBitmap)
                        }
                        if (Thread.interrupted())
                            break
                    }
                }
            } catch (_: InterruptedException) {
                // Return
            }
        }, "VideoFrameMaterializer")
        VideoWriter(
            file, writerSpec, scaledVideo.fps, settings.codecName, settings.codecProfile, settings.codecOptions,
            emptyMap()
        ).use { videoWriter ->
            try {
                // Start the materializer only after the VideoWriter has been successfully created, to not waste compute
                // when the VideoWriter creation fails and we have to fall back to other VideoWriterSettings.
                materializer.start()
                for (frameIdx in 0..<scaledVideo.numFrames) {
                    queue.take().use(videoWriter::write)
                    progressCallback(MAX_RENDER_PROGRESS * (frameIdx + 1) / scaledVideo.numFrames)
                    if (Thread.interrupted())
                        throw InterruptedException()
                }
            } finally {
                materializer.interrupt()
                materializer.join(1000L)
                while (queue.poll()?.also(Bitmap::close) != null) continue
            }
        }

        blackWriterBitmap?.close()
    }


    companion object {

        val H264: RenderFormat = H26XFormat(
            "H.264", AV_CODEC_ID_H264, "libx264", FF_PROFILE_H264_HIGH, FF_PROFILE_H264_HIGH_10
        )
        val H265: RenderFormat = H26XFormat(
            "H.265", AV_CODEC_ID_H265, "libx265", FF_PROFILE_HEVC_MAIN, FF_PROFILE_HEVC_MAIN_10
        )

        val FORMATS = listOf(H264, H265, ProResFormat(), DNxHRFormat())

        private fun allChannelsAndColorPreset() =
            choice(CHANNELS, COLOR, COLOR_AND_ALPHA) * (
                    fixed(PRIMARIES, BT709) * (choice(TRANSFER) - fixed(TRANSFER, SRGB)) * fixed(YUV, BT709_NCL) +
                            fixed(PRIMARIES, BT709) * fixed(TRANSFER, SRGB) * fixed(YUV, SRGB_NCL) +
                            choice(PRIMARIES, DCI_P3, DISPLAY_P3) * choice(TRANSFER) * fixed(YUV, BT709_NCL) +
                            fixed(PRIMARIES, BT2020) * fixed(TRANSFER, BT1886) * choice(YUV, BT2020_NCL, BT2020_CL) +
                            fixed(PRIMARIES, BT2020) * choice(TRANSFER, LINEAR, SRGB) * fixed(YUV, BT2020_NCL) +
                            fixed(PRIMARIES, BT2020) * choice(TRANSFER, PQ, HLG) * choice(YUV, BT2020_NCL, ICTCP)
                    ) + fixed(CHANNELS, ALPHA)

        private fun opaqueChannelsAndColorPreset() =
            allChannelsAndColorPreset() - fixed(CHANNELS, COLOR_AND_ALPHA)

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

        abstract fun videoWriterSettings(config: Config): List<VideoWriterSettings>

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
        val codecName: String,
        val codecProfile: Int,
        val codecOptions: Map<String, String>,
        val pixelFormat: Bitmap.PixelFormat
    )


    private class H26XFormat(
        label: String,
        codecId: Int,
        private val codecName: String,
        private val codecProfile8: Int,
        private val codecProfile10: Int
    ) : Format(
        label, codecId, "mp4",
        opaqueChannelsAndColorPreset() * choice(DEPTH, 8, 10) * fixed(SCAN, Bitmap.Scan.PROGRESSIVE),
        widthMod2 = true,
        heightMod2 = true
    ) {
        override fun videoWriterSettings(config: Config): List<VideoWriterSettings> {
            val depth = config[DEPTH]
            val codecProfile = if (depth == 8) codecProfile8 else codecProfile10
            val pixelFormat = Bitmap.PixelFormat.of(if (depth == 8) AV_PIX_FMT_YUV420P else AV_PIX_FMT_YUV420P10)
            return listOf(VideoWriterSettings(codecName, codecProfile, emptyMap(), pixelFormat))
        }
    }


    private class ProResFormat : Format(
        "ProRes", AV_CODEC_ID_PRORES, "mov",
        allChannelsAndColorPreset() * fixed(DEPTH, 10) * choice(SCAN) * choice(PRORES_PROFILE) -
                fixed(CHANNELS, COLOR_AND_ALPHA) *
                choice(PRORES_PROFILE, PRORES_422_PROXY, PRORES_422_LT, PRORES_422, PRORES_422_HQ),
        widthMod2 = true
    ) {
        override fun videoWriterSettings(config: Config): List<VideoWriterSettings> {
            val profile = config[PRORES_PROFILE]
            val embedAlpha = config[CHANNELS] == COLOR_AND_ALPHA
            val is4444 = profile == PRORES_4444 || profile == PRORES_4444_XQ
            // prores_aw is faster, but only prores_ks supports 4444 alpha content that is universally compatible.
            val codecName = if (is4444 && embedAlpha) "prores_ks" else "prores_aw"
            val codecProfile = when (profile) {
                PRORES_422_PROXY -> FF_PROFILE_PRORES_PROXY
                PRORES_422_LT -> FF_PROFILE_PRORES_LT
                PRORES_422 -> FF_PROFILE_PRORES_STANDARD
                PRORES_422_HQ -> FF_PROFILE_PRORES_HQ
                PRORES_4444 -> FF_PROFILE_PRORES_4444
                PRORES_4444_XQ -> FF_PROFILE_PRORES_XQ
            }
            val pixelFormat = Bitmap.PixelFormat.of(
                if (!is4444) AV_PIX_FMT_YUV422P10 else if (!embedAlpha) AV_PIX_FMT_YUV444P10 else AV_PIX_FMT_YUVA444P10
            )
            val s = mutableListOf(VideoWriterSettings(codecName, codecProfile, emptyMap(), pixelFormat))
            if (SystemInfo.isMacOS) {
                val vtPx = Bitmap.PixelFormat.of(
                    if (!is4444) AV_PIX_FMT_P210 else if (!embedAlpha) AV_PIX_FMT_P410 else AV_PIX_FMT_AYUV64
                )
                s.add(0, VideoWriterSettings("prores_videotoolbox", codecProfile, mapOf("allow_sw" to "1"), vtPx))
            }
            return s
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
        override fun videoWriterSettings(config: Config): List<VideoWriterSettings> {
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
            return listOf(VideoWriterSettings("dnxhd", FF_PROFILE_UNKNOWN, mapOf("profile" to codecProfileOption), px))
        }
    }

}
