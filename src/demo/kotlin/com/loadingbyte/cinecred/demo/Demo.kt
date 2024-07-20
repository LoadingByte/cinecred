package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.FALLBACK_TRANSLATED_LOCALE
import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.BitmapConverter
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.VideoWriter
import org.bytedeco.ffmpeg.global.avcodec.AV_PROFILE_H264_HIGH
import org.bytedeco.ffmpeg.global.avutil.AVCHROMA_LOC_LEFT
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.w3c.dom.Node
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte
import java.nio.file.Path
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists


abstract class Demo(private val filename: String, protected val format: Format) {

    enum class Format(val ext: String, val fps: FPS) {
        PNG("png", FPS(1, 1)),
        STEP_GIF("gif", FPS(2, 3)),
        SLOW_STEP_GIF("gif", FPS(1, 3)),
        VIDEO_GIF("gif", FPS(30, 1)),
        MP4("mp4", FPS(30, 1))
    }

    open val isLocaleSensitive: Boolean get() = true
    protected abstract fun doGenerate()

    fun doGenerate(locale: Locale) {
        this.locale = locale
        doGenerate()
        close()
    }

    protected lateinit var locale: Locale
        private set

    private val gifFrames = mutableListOf<GIFFrame>()
    private var mp4Writer: VideoWriter? = null
    private var mp4Converter: BitmapConverter? = null
    private var mp4BGRBitmap: Bitmap? = null
    private var mp4YUVBitmap: Bitmap? = null

    protected fun write(image: BufferedImage, suffix: String = "") {
        require(suffix.isBlank() || format == Format.PNG)

        when (format) {
            Format.PNG -> ImageIO.write(image, "png", file(suffix).toFile())
            Format.STEP_GIF, Format.SLOW_STEP_GIF, Format.VIDEO_GIF -> writeGIF(image)
            Format.MP4 -> writeMP4(image)
        }
    }

    private fun writeGIF(image: BufferedImage) {
        // If this isn't the first image, find the dirty region that differs from the previous image.
        require(image.type == BufferedImage.TYPE_3BYTE_BGR)
        val lastFrame = gifFrames.lastOrNull()
        var dirty: Rectangle? = null
        if (lastFrame != null) {
            val currBuf = image.raster.dataBuffer as DataBufferByte
            val lastBuf = lastFrame.image.raster.dataBuffer as DataBufferByte
            val expectedBufSize = image.width * image.height * image.colorModel.numComponents
            require(currBuf.size == expectedBufSize && lastBuf.size == expectedBufSize) { "Subimage" }
            val currArr = currBuf.data
            val lastArr = lastBuf.data
            dirty = Rectangle(0, 0, -1, -1)
            val w = image.width
            var i = 0
            var x = 0
            var y = 0
            while (i < currBuf.size) {
                if (currArr[i] != lastArr[i] || currArr[i + 1] != lastArr[i + 1] || currArr[i + 2] != lastArr[i + 2])
                    dirty.add(x, y)
                i += 3
                if (++x == w) {
                    x = 0
                    y++
                }
            }
            // Rectangle.add() doesn't add a 1x1 pixel, but instead a 0x0 point, so we have to enlarge the rectangle now
            // to obtain the former.
            dirty.width += 1
            dirty.height += 1
            // If the image doesn't differ from the previous one, don't write it, but instead remember that the previous
            // image should be displayed longer.
            if (dirty.width == 0) {
                lastFrame.delay++
                return
            }
            // If every single pixel of the image differs from the previous one, we skip the dirty mechanism.
            if (dirty.width == image.width && dirty.height == image.height)
                dirty = null
        }

        // We are now at a point where we actually want to write this image. Writing is deferred, so we remember this
        // image so that it can be written later. Don't forget to also restrict the data to the dirty region.
        val dirtyImage = if (dirty == null) image else
            image.getSubimage(dirty.x, dirty.y, dirty.width, dirty.height)
        gifFrames.add(GIFFrame(dirtyImage, dirty, 1))
    }

    private fun flushGIFFrames() {
        // Set up the GIF stream and writer.
        FileImageOutputStream(file("").toFile()).use { gifStream ->
            val gifWriter = ImageIO.getImageWritersBySuffix("gif").next().apply {
                output = gifStream
                prepareWriteSequence(null)
            }

            // Come up with a single IndexColorModel that best reproduces the colors across all images, and convert the
            // images to use that model.
            val quantizedImages = quantizeColors(gifFrames.map(GIFFrame::image))

            // Write each remembered image using the writer.
            for ((frame, quantizedImage) in gifFrames.zip(quantizedImages)) {
                val dirty = frame.dirty
                // Collect the image's metadata.
                // If we were to use quantizedImage's real type here (which is TYPE_BYTE_INDEXED), GIFImageWriter would
                // already add a localColorTable to the metadata. However, this results in extremely wrong colors for
                // some reason. We get around this issue by lying about quantizedImage's type.
                val typeSpec = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR)
                val md = gifWriter.getDefaultImageMetadata(typeSpec, null)
                val mdRoot = md.getAsTree(md.nativeMetadataFormatName)
                // If only a subregion of the image differs from the previous one, mark that in the metadata.
                if (dirty != null)
                    mdRoot.getOrAddChild("ImageDescriptor").apply {
                        setAttribute("imageLeftPosition", dirty.x.toString())
                        setAttribute("imageTopPosition", dirty.y.toString())
                        setAttribute("imageWidth", dirty.width.toString())
                        setAttribute("imageHeight", dirty.height.toString())
                    }
                mdRoot.getOrAddChild("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "doNotDispose")
                    setAttribute("delayTime", format.fps.run { frame.delay * 100 * denominator / numerator }.toString())
                }
                mdRoot.getOrAddChild("ApplicationExtensions")
                    .appendChild(IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0)
                    })
                md.setFromTree(md.nativeMetadataFormatName, mdRoot)
                // Now do write the image.
                gifWriter.writeToSequence(IIOImage(quantizedImage, null, md), null)
            }

            gifWriter.endWriteSequence()
        }
        gifFrames.clear()
    }

    private fun Node.getOrAddChild(name: String): IIOMetadataNode {
        var child = firstChild
        while (child != null) {
            if (child.nodeName.equals(name, ignoreCase = true))
                return child as IIOMetadataNode
            child = child.nextSibling
        }
        return IIOMetadataNode(name).also(::appendChild)
    }

    private fun writeMP4(image: BufferedImage) {
        // Set up the VideoWriter if this is the first image.
        if (mp4Writer == null) {
            val res = Resolution(image.width, image.height)
            val bgrSpec = Bitmap.Spec(res, BGR24_REPRESENTATION)
            val yuvRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(AV_PIX_FMT_YUV420P), Bitmap.Range.LIMITED, ColorSpace.SRGB,
                Bitmap.YUVCoefficients.SRGB_NCL, AVCHROMA_LOC_LEFT, Bitmap.Alpha.OPAQUE
            )
            val yuvSpec = Bitmap.Spec(res, yuvRep)
            mp4Writer = VideoWriter(
                file(""), yuvSpec, format.fps,
                "libx264", AV_PROFILE_H264_HIGH, codecOptions = mapOf("crf" to "17"), muxerOptions = emptyMap()
            )
            mp4Converter = BitmapConverter(bgrSpec, yuvSpec)
            mp4BGRBitmap = Bitmap.allocate(bgrSpec)
            mp4YUVBitmap = Bitmap.allocate(yuvSpec)
        }

        // Write the image.
        require(image.type == BufferedImage.TYPE_3BYTE_BGR)
        mp4BGRBitmap!!.put(
            (image.raster.dataBuffer as DataBufferByte).data,
            (image.raster.sampleModel as ComponentSampleModel).scanlineStride
        )
        mp4Converter!!.convert(mp4BGRBitmap!!, mp4YUVBitmap!!)
        mp4Writer!!.write(mp4YUVBitmap!!)
    }

    private fun file(suffix: String): Path {
        val localeSuffix = if (locale == FALLBACK_TRANSLATED_LOCALE) "" else "_" + locale.toLanguageTag()
        val file = Path("demoOutput").resolve(filename + suffix + localeSuffix + "." + format.ext)
        file.deleteIfExists()
        file.parent.createDirectoriesSafely()
        return file
    }

    private fun close() {
        if (gifFrames.isNotEmpty())
            flushGIFFrames()
        mp4Writer?.close()
        mp4Converter?.close()
        mp4BGRBitmap?.close()
        mp4YUVBitmap?.close()
    }


    private class GIFFrame(val image: BufferedImage, val dirty: Rectangle?, var delay: Int)

}
