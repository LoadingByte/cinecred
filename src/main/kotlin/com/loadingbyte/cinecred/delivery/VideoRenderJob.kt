package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import io.humble.video.*
import io.humble.video.Codec.ID.CODEC_ID_H264
import io.humble.video.Codec.ID.CODEC_ID_PRORES
import io.humble.video.PixelFormat.Type.PIX_FMT_YUV420P
import io.humble.video.PixelFormat.Type.PIX_FMT_YUV422P10LE
import io.humble.video.awt.MediaPictureConverter
import io.humble.video.awt.MediaPictureConverterFactory
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.Closeable
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

        // Invert the FPS to obtain the framerate.
        val fps = project.styling.global.fps
        val totalNumFrames = getDurationFrames(project, pageDefImages)
        val framerate = Rational.make(fps.denominator, fps.numerator)
        val videoWidth = ceil(project.styling.global.widthPx * scaling).toInt()
        val videoHeight = ceil(project.styling.global.heightPx * scaling).toInt()

        VideoWriter(videoWidth, videoHeight, framerate, file, format).use { videoWriter ->
            var prevProgress = 0f
            fun updateProgress() {
                val progress = videoWriter.frameCounter.toFloat() / totalNumFrames
                if (progress > prevProgress + 0.01f) {
                    prevProgress = progress
                    progressCallback(progress)
                }
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
        val codecId: Codec.ID,
        val pixelFormat: PixelFormat.Type,
        val properties: Map<String, String> = emptyMap()
    ) {

        val fileExts = listOf(defaultFileExt) +
                MuxerFormat.getFormats()
                    .filter { codecId in it.supportedCodecs }
                    .flatMap { it.extensions?.split(",") ?: emptyList() }
                    .filter { it != defaultFileExt }
                    .toSortedSet()

        companion object {
            val ALL = listOf(
                Format("H.264", "mp4", CODEC_ID_H264, PIX_FMT_YUV420P),
                Format("ProRes 422 Proxy", "mov", CODEC_ID_PRORES, PIX_FMT_YUV422P10LE, mapOf("profile" to "0")),
                Format("ProRes 422 LT", "mov", CODEC_ID_PRORES, PIX_FMT_YUV422P10LE, mapOf("profile" to "1")),
                Format("ProRes 422", "mov", CODEC_ID_PRORES, PIX_FMT_YUV422P10LE, mapOf("profile" to "2")),
                Format("ProRes 422 HQ", "mov", CODEC_ID_PRORES, PIX_FMT_YUV422P10LE, mapOf("profile" to "3")),
                // TODO: Currently, humble-video is based on ffmpeg 2.8.15, which does not support DNxHR.
                // Until humble-video is updated, we sadly also can't support DNxHR.
                // Refer to this issue: https://github.com/artclarke/humble-video/issues/124
                // Also note that we could use this fork of the original xuggler, but it doesn't come with pre-built
                // binaries for all OSes and the effort to build all of them by ourselves would be tremendous:
                // https://github.com/olivierayache/xuggle-xuggler/
                /*Format("DNxHR LB", "mxf", CODEC_ID_DNXHD, PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_lb")),
                Format("DNxHR SQ", "mxf", CODEC_ID_DNXHD, PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_sq")),
                Format("DNxHR HQ", "mxf", CODEC_ID_DNXHD, PIX_FMT_YUV422P, mapOf("profile" to "dnxhr_hq")),
                Format("DNxHR HQX", "mxf", CODEC_ID_DNXHD, PIX_FMT_YUV422P10LE, mapOf("profile" to "dnxhr_hqx"))*/
            )
        }

    }


    /**
     * This class provides a nice interface to ffmpeg and hides all its nastiness.
     */
    private class VideoWriter(width: Int, height: Int, framerate: Rational, file: Path, format: Format) : Closeable {

        private val muxer: Muxer
        private val encoder: Encoder
        private val picture: MediaPicture
        private val packet = MediaPacket.make()

        private var converter: MediaPictureConverter? = null
        var frameCounter = 0L
            private set

        init {
            muxer = Muxer.make(file.toString(), null, null)
            encoder = Encoder.make(Codec.findEncodingCodec(format.codecId)).apply {
                this.width = width
                this.height = height
                pixelFormat = format.pixelFormat
                timeBase = framerate
                for ((key, value) in format.properties)
                    setProperty(key, value)
            }

            // An annoyance of some formats is that they need global (rather than per-stream) headers,
            // and in that case you have to tell the encoder.
            if (muxer.format.getFlag(ContainerFormat.Flag.GLOBAL_HEADER))
                encoder.setFlag(Coder.Flag.FLAG_GLOBAL_HEADER, true)

            encoder.open(null, null)
            muxer.addNewStream(encoder)
            muxer.open(null, null)

            picture = MediaPicture.make(width, height, format.pixelFormat)
            picture.timeBase = framerate
        }

        fun writeFrame(frame: BufferedImage) {
            if (muxer.state != Muxer.State.STATE_OPENED)
                throw IllegalStateException("Cannot write to VideoWriter because it has been closed.")

            if (converter == null)
                converter = MediaPictureConverterFactory.createConverter(frame, picture)

            converter!!.toPicture(picture, frame, frameCounter++)
            flushPicture(picture)
        }

        override fun close() {
            flushPicture(null)
            muxer.close()
        }

        private fun flushPicture(picture: MediaPicture?) {
            do {
                encoder.encode(packet, picture)
                if (packet.isComplete) muxer.write(packet, false)
            } while (packet.isComplete)
        }

    }

}
