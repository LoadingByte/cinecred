package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.throwableAwareTask
import com.loadingbyte.cinecred.delivery.RenderFormat.Channels.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Config
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.choice
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.fixed
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.CHANNELS
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.COLOR_PRESET
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DPX_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.RESOLUTION_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TIFF_COMPRESSION
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.GRAY
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.RGB
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.Project
import org.apache.commons.io.FileUtils
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.math.pow


class ImageSequenceRenderJob private constructor(
    private val format: Format,
    private val config: Config,
    private val project: Project,
    private val video: DeferredVideo,
    private val dir: Path,
    private val filenamePattern: String
) : RenderJob {

    override val prefix: Path
        get() = dir

    override fun render(progressCallback: (Int) -> Unit) {
        if (dir.exists())
            FileUtils.cleanDirectory(dir.toFile())
        dir.createDirectoriesSafely()

        val embedAlpha = config[CHANNELS] == COLOR_AND_ALPHA
        val matte = config[CHANNELS] == ALPHA
        val family = if (matte) GRAY else RGB
        val colorSpace = if (matte) null else config[COLOR_PRESET].colorSpace
        val scan = config[SCAN]
        val grounding = if (config[CHANNELS] == COLOR) project.styling.global.grounding else null
        val scaledVideo = video.copy(2.0.pow(config[RESOLUTION_SCALING_LOG2]), config[FPS_SCALING])

        val bitmapWriter = when (format) {
            PNG -> BitmapWriter.PNG(family, embedAlpha, colorSpace, config[DEPTH])
            TIFF -> BitmapWriter.TIFF(family, embedAlpha, colorSpace, config[DEPTH], config[TIFF_COMPRESSION])
            DPX -> BitmapWriter.DPX(family, embedAlpha, colorSpace, config[DEPTH], config[DPX_COMPRESSION])
            else -> throw IllegalArgumentException()
        }

        val backendRep = if (!matte) bitmapWriter.representation else {
            val pxFmtCode = when (bitmapWriter.representation.pixelFormat.code) {
                AV_PIX_FMT_GRAY8 -> AV_PIX_FMT_GBRAP
                AV_PIX_FMT_GRAY10BE -> AV_PIX_FMT_GBRAP10BE
                AV_PIX_FMT_GRAY10LE -> AV_PIX_FMT_GBRAP10LE
                AV_PIX_FMT_GRAY12BE -> AV_PIX_FMT_GBRAP12BE
                AV_PIX_FMT_GRAY12LE -> AV_PIX_FMT_GBRAP12LE
                AV_PIX_FMT_GRAY16BE -> AV_PIX_FMT_GBRAP16BE
                AV_PIX_FMT_GRAY16LE -> AV_PIX_FMT_GBRAP16LE
                AV_PIX_FMT_GRAYF32BE -> AV_PIX_FMT_GBRAPF32BE
                AV_PIX_FMT_GRAYF32LE -> AV_PIX_FMT_GBRAPF32LE
                else -> throw IllegalArgumentException("No color format of ${bitmapWriter.representation.pixelFormat}.")
            }
            Bitmap.Representation(Bitmap.PixelFormat.of(pxFmtCode), ColorSpace.BLENDING, Bitmap.Alpha.PREMULTIPLIED)
        }
        val backendSpec = Bitmap.Spec(
            scaledVideo.resolution, backendRep, scan,
            if (scan == Bitmap.Scan.PROGRESSIVE) Bitmap.Content.PROGRESSIVE_FRAME else Bitmap.Content.INTERLEAVED_FIELDS
        )

        DeferredVideo.BitmapBackend(scaledVideo, listOf(STATIC), listOf(TAPES), grounding, backendSpec).use { backend ->
            val numFrames = scaledVideo.numFrames
            val numWorkers = Runtime.getRuntime().availableProcessors() - 1
            val executor = Executors.newFixedThreadPool(numWorkers) { Thread(it, "ImageSequenceWriter") }
            try {
                val done = CountDownLatch(numFrames)
                val backlog = Semaphore(numWorkers * 5)
                for (frameIdx in 0..<numFrames) {
                    val colorBitmap = backend.materializeFrame(frameIdx)!!
                    val bitmap = if (!matte) colorBitmap else colorBitmap.use(Bitmap::alphaPlaneView)
                    val file = dir.resolve(filenamePattern.format(frameIdx + 1))
                    backlog.acquire()
                    executor.submit(throwableAwareTask {
                        try {
                            bitmapWriter.write(bitmap, file)
                            bitmap.close()
                            backlog.release()
                            done.countDown()
                            if (!Thread.interrupted())
                                progressCallback(MAX_RENDER_PROGRESS * (numFrames - done.count.toInt()) / numFrames)
                        } catch (_: InterruptedException) {
                            // Return.
                        }
                    })
                    if (Thread.interrupted())
                        throw InterruptedException()
                }
                done.await()
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }


    companion object {

        private val PNG = Format(
            "png",
            channelsTimesColorPreset() * choice(DEPTH, 8, 16)
        )
        private val TIFF = Format(
            "tiff",
            channelsTimesColorPreset() * choice(DEPTH, 8, 16) * choice(TIFF_COMPRESSION)
        )
        private val DPX = Format(
            "dpx",
            channelsTimesColorPreset() * choice(DEPTH, 8, 10, 12, 16) * choice(DPX_COMPRESSION) -
                    fixed(DEPTH, 10) * fixed(CHANNELS, COLOR_AND_ALPHA)
        )

        val FORMATS = listOf<RenderFormat>(PNG, TIFF, DPX)

        private fun channelsTimesColorPreset() =
            choice(CHANNELS, COLOR, COLOR_AND_ALPHA) * choice(COLOR_PRESET) + fixed(CHANNELS, ALPHA)

    }


    private class Format(fileExt: String, configAssortment: Config.Assortment) : RenderFormat(
        fileExt.uppercase(), fileSeq = true, setOf(fileExt), fileExt,
        configAssortment * choice(RESOLUTION_SCALING_LOG2) * choice(FPS_SCALING) * choice(SCAN)
    ) {
        override fun createRenderJob(
            config: Config,
            project: Project,
            pageDefImages: List<DeferredImage>?,
            video: DeferredVideo?,
            fileOrDir: Path,
            filenamePattern: String?
        ) = ImageSequenceRenderJob(this, config, project, video!!, fileOrDir, filenamePattern!!)
    }

}
