package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.Resolution
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.Transparency
import java.awt.color.ICC_ColorSpace
import java.awt.image.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import javax.imageio.*
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageOutputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.roundToInt
import java.awt.color.ColorSpace as AWTColorSpace


/** Note: Once constructed, a bitmap writer has no varying state and is thus fully thread-safe. */
interface BitmapWriter {

    val representation: Bitmap.Representation

    /** @throws IOException */
    fun write(bitmap: Bitmap): ByteArray
    /** @throws IOException */
    fun write(bitmap: Bitmap, file: Path)

    /** @throws IOException */
    fun convertAndWrite(
        bitmap: Bitmap,
        promiseOpaque: Boolean = false, resolution: Resolution = bitmap.spec.resolution
    ): ByteArray =
        convertAndWrite(bitmap, promiseOpaque, resolution) { write(it) }

    /** @throws IOException */
    fun convertAndWrite(
        bitmap: Bitmap, file: Path,
        promiseOpaque: Boolean = false, resolution: Resolution = bitmap.spec.resolution
    ): Unit =
        convertAndWrite(bitmap, promiseOpaque, resolution) { write(it, file) }

    private inline fun <R> convertAndWrite(
        bitmap: Bitmap, promiseOpaque: Boolean, resolution: Resolution, write: (Bitmap) -> R
    ): R {
        val spec = bitmap.spec.copy(resolution = resolution, representation = representation)
        return if (bitmap.spec == spec)
            write(bitmap)
        else
            Bitmap.allocate(spec).use { convertedBitmap ->
                BitmapConverter.convert(bitmap, convertedBitmap, promiseOpaque = promiseOpaque)
                write(convertedBitmap)
            }
    }


    sealed class ImageIOBased(
        private val formatName: String,
        family: Bitmap.PixelFormat.Family,
        alpha: Bitmap.Alpha,
        colorSpace: ColorSpace?,
        private val depth: Int
    ) : BitmapWriter {

        private val isRGB = family == Bitmap.PixelFormat.Family.RGB
        private val hasAlpha = alpha != Bitmap.Alpha.OPAQUE
        private val isAlphaPremultiplied = alpha == Bitmap.Alpha.PREMULTIPLIED

        final override val representation: Bitmap.Representation
        protected val iccBytes: ByteArray?
        private val awtCM: ColorModel

        init {
            val pixFmtCode = when (family) {
                Bitmap.PixelFormat.Family.RGB -> when (depth) {
                    8 -> if (hasAlpha) AV_PIX_FMT_RGBA else AV_PIX_FMT_RGB24
                    16 -> if (hasAlpha) AV_PIX_FMT_RGBA64 else AV_PIX_FMT_RGB48
                    else -> throw IllegalArgumentException("Unsupported depth for $formatName: $depth")
                }
                Bitmap.PixelFormat.Family.GRAY -> when (depth) {
                    8 -> if (hasAlpha) AV_PIX_FMT_YA8 else AV_PIX_FMT_GRAY8
                    16 -> if (hasAlpha) AV_PIX_FMT_YA16 else AV_PIX_FMT_GRAY16
                    else -> throw IllegalArgumentException("Unsupported depth for $formatName: $depth")
                }
                Bitmap.PixelFormat.Family.YUV -> throw IllegalArgumentException("YUV is not supported in $formatName.")
            }
            representation = Bitmap.Representation(
                Bitmap.PixelFormat.of(pixFmtCode), Bitmap.Range.FULL, colorSpace, null, AVCHROMA_LOC_UNSPECIFIED, alpha
            )

            val awtCS: AWTColorSpace
            if (isRGB) {
                requireNotNull(colorSpace) { "A color space must be supplied when writing color bitmaps." }
                val iccProfile = ICCProfile.of(colorSpace)
                iccBytes = iccProfile.bytes
                awtCS = ICC_ColorSpace(iccProfile.awtProfile)
            } else {
                // Due to the way Skia implements it, we can't generate gray ICC profiles as of now. That's not a
                // problem however as our gray Bitmaps aren't allowed to have a ColorSpace anyway.
                iccBytes = null
                awtCS = AWTColorSpace.getInstance(AWTColorSpace.CS_GRAY)
            }
            awtCM = ComponentColorModel(
                awtCS, hasAlpha, isAlphaPremultiplied,
                if (hasAlpha) Transparency.TRANSLUCENT else Transparency.OPAQUE,
                if (depth == 8) DataBuffer.TYPE_BYTE else DataBuffer.TYPE_USHORT
            )
        }

        override fun write(bitmap: Bitmap): ByteArray {
            val image = toImage(bitmap)
            val baos = ByteArrayOutputStream()
            MemoryCacheImageOutputStream(baos).use { writeImage(image, it) }
            return baos.toByteArray()
        }

        override fun write(bitmap: Bitmap, file: Path) {
            val image = toImage(bitmap)
            FileImageOutputStream(file.toFile()).use { writeImage(image, it) }
        }

        private fun toImage(bitmap: Bitmap): BufferedImage {
            val (res, rep) = bitmap.spec
            require(rep == representation) { "Representation mismatch: Expected $representation, got $rep." }
            val (w, h) = res
            val scanlineStride = bitmap.linesize(0) / (depth / 8)
            val dataBuffer = when (depth) {
                8 -> DataBufferByte(bitmap.getB(scanlineStride), h * scanlineStride)
                16 -> DataBufferUShort(bitmap.getS(scanlineStride), h * scanlineStride)
                else -> throw IllegalStateException()
            }
            val bOffs = when (isRGB) {
                false -> if (hasAlpha) intArrayOf(0, 1) else intArrayOf(0)
                true -> if (hasAlpha) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 1, 2)
            }
            val raster = Raster.createInterleavedRaster(dataBuffer, w, h, scanlineStride, bOffs.size, bOffs, null)
            return BufferedImage(awtCM, raster, isAlphaPremultiplied, null)
        }

        private fun writeImage(image: BufferedImage, ios: ImageOutputStream) {
            // Note: We do not use ImageIO.write() for two reasons:
            //   - We need to support custom metadata and params.
            //   - ImageIO.write() eventually uses the com.sun class FileImageOutputStreamSpi,
            //     which swallows IO exceptions. Eventually, another exception for the same error
            //     will be thrown, but the error message is lost.
            val writer = ImageIO.getImageWritersByFormatName(formatName).next()
            try {
                writer.output = ios
                writer.write(null, IIOImage(image, null, configureMetadata(writer, image)), configureParam(writer))
            } finally {
                writer.dispose()
            }
        }

        protected open fun configureParam(writer: ImageWriter): ImageWriteParam? = null
        protected open fun configureMetadata(writer: ImageWriter, image: BufferedImage): IIOMetadata? = null

    }


    class JPEG(
        family: Bitmap.PixelFormat.Family,
        colorSpace: ColorSpace?
    ) : ImageIOBased("jpeg", family, Bitmap.Alpha.OPAQUE, colorSpace, depth = 8)


    class PNG(
        family: Bitmap.PixelFormat.Family,
        transparent: Boolean,
        colorSpace: ColorSpace?,
        depth: Int = 8
    ) : ImageIOBased(
        "png", family,
        if (transparent) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE,
        colorSpace, depth
    ) {

        private val mdRoot: IIOMetadataNode

        init {
            val mdFormatName = ImageIO.getImageWritersByFormatName("png").next()
                .getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB),
                    null
                )
                .nativeMetadataFormatName
            mdRoot = IIOMetadataNode(mdFormatName)
            if (colorSpace == ColorSpace.SRGB) {
                mdRoot.appendChild(IIOMetadataNode("sRGB").apply {
                    setAttribute("renderingIntent", "Relative colorimetric")
                })
                // The PNG specification recommends that we also write the cHRM and gAMA chunks with the following
                // exact values, but not the iCCP chunk: https://www.w3.org/TR/2003/REC-PNG-20031110/#11sRGB
                mdRoot.appendChild(IIOMetadataNode("cHRM").apply {
                    setAttribute("redX", "64000")
                    setAttribute("redY", "33000")
                    setAttribute("greenX", "30000")
                    setAttribute("greenY", "60000")
                    setAttribute("blueX", "15000")
                    setAttribute("blueY", "6000")
                    setAttribute("whitePointX", "31270")
                    setAttribute("whitePointY", "32900")
                })
                mdRoot.appendChild(IIOMetadataNode("gAMA").apply {
                    setAttribute("value", "45455")
                })
            } else if (colorSpace != null) {
                mdRoot.appendChild(IIOMetadataNode("iCCP").apply {
                    setAttribute("profileName", "ICC Profile")
                    setAttribute("compressionMethod", "deflate")
                    val baos = ByteArrayOutputStream()
                    DeflaterOutputStream(baos).use { it.write(iccBytes!!) }
                    userObject = baos.toByteArray()
                })
                // Also populate the chroma and gamma chunks as a fallback.
                colorSpace.primaries.chromaticities?.let { c ->
                    mdRoot.appendChild(IIOMetadataNode("cHRM").apply {
                        setAttribute("redX", (c.rx * 100000f).roundToInt().toString())
                        setAttribute("redY", (c.ry * 100000f).roundToInt().toString())
                        setAttribute("greenX", (c.gx * 100000f).roundToInt().toString())
                        setAttribute("greenY", (c.gy * 100000f).roundToInt().toString())
                        setAttribute("blueX", (c.bx * 100000f).roundToInt().toString())
                        setAttribute("blueY", (c.by * 100000f).roundToInt().toString())
                        setAttribute("whitePointX", (c.wx * 100000f).roundToInt().toString())
                        setAttribute("whitePointY", (c.wy * 100000f).roundToInt().toString())
                    })
                }
                if (colorSpace.transfer.hasCurve)
                    mdRoot.appendChild(IIOMetadataNode("gAMA").apply {
                        setAttribute("value", (colorSpace.transfer.fromLinear.g * 100000f).roundToInt().toString())
                    })
            }
            if (colorSpace != null && colorSpace.primaries.hasCode && colorSpace.transfer.hasCode)
                mdRoot.appendChild(IIOMetadataNode("UnknownChunks")).appendChild(IIOMetadataNode("UnknownChunk").apply {
                    setAttribute("type", "cICP")
                    userObject = byteArrayOf(
                        colorSpace.primaries.code.toByte(),
                        colorSpace.transfer.code.toByte(),
                        AVCOL_SPC_RGB.toByte(),
                        1
                    )
                })
        }

        override fun configureMetadata(writer: ImageWriter, image: BufferedImage): IIOMetadata =
            writer.getDefaultImageMetadata(ImageTypeSpecifier(image), null).apply {
                setFromTree(nativeMetadataFormatName, mdRoot)
            }

    }


    class TIFF(
        family: Bitmap.PixelFormat.Family,
        transparent: Boolean,
        colorSpace: ColorSpace?,
        depth: Int = 8,
        private val compression: Compression = Compression.DEFLATE
    ) : ImageIOBased(
        "tiff", family,
        // Note: In TIFF, only associated (= premultiplied) alpha seems to be widely compatible.
        if (transparent) Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE,
        colorSpace, depth
    ) {

        override fun configureParam(writer: ImageWriter): ImageWriteParam =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionType = compression.label
            }

        enum class Compression(val label: String) {
            // Note: The labels are passed into ImageIO and should not be changed!
            LZW("LZW"),
            PACK_BITS("PackBits"),
            DEFLATE("Deflate")
        }

    }

}
