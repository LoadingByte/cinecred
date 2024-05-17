package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.common.ceilDiv
import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.Transparency
import java.awt.color.ICC_ColorSpace
import java.awt.image.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.Byte.toUnsignedInt
import java.lang.Short.toUnsignedInt
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.*
import java.util.zip.DeflaterOutputStream
import javax.imageio.*
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import javax.imageio.stream.ImageOutputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.io.path.outputStream
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
            representation = Bitmap.Representation(Bitmap.PixelFormat.of(pixFmtCode), colorSpace, alpha)

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
        hasAlpha: Boolean,
        colorSpace: ColorSpace?,
        depth: Int = 8
    ) : ImageIOBased(
        "png", family,
        if (hasAlpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE,
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
            } else
                mdRoot.appendChild(IIOMetadataNode("gAMA").apply {
                    setAttribute("value", "100000")
                })
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
        hasAlpha: Boolean,
        colorSpace: ColorSpace?,
        depth: Int = 8,
        private val compression: Compression
    ) : ImageIOBased(
        "tiff", family,
        // Note: In TIFF, only associated (= premultiplied) alpha seems to be widely compatible.
        if (hasAlpha) Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE,
        colorSpace, depth
    ) {

        override fun configureParam(writer: ImageWriter): ImageWriteParam =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionType = when (compression) {
                    Compression.NONE -> null
                    Compression.PACK_BITS -> "PackBits"
                    Compression.LZW -> "LZW"
                    Compression.DEFLATE -> "ZLib"
                }
            }

        enum class Compression { NONE, PACK_BITS, LZW, DEFLATE }

    }


    class DPX(
        family: Bitmap.PixelFormat.Family,
        private val hasAlpha: Boolean,
        private val colorSpace: ColorSpace?,
        private val depth: Int,
        private val compression: Compression
    ) : BitmapWriter {

        enum class Compression { NONE, RLE }

        private val isGray = family == Bitmap.PixelFormat.Family.GRAY
        override val representation: Bitmap.Representation

        init {
            when (family) {
                Bitmap.PixelFormat.Family.GRAY ->
                    require(!hasAlpha) { "Gray with additional alpha is not supported in DPX." }
                Bitmap.PixelFormat.Family.RGB -> {
                    requireNotNull(colorSpace) { "A color space must be supplied when writing color bitmaps." }
                    if (depth == 10) require(!hasAlpha) { "10-bit color with alpha is not supported in DPX." }
                }
                Bitmap.PixelFormat.Family.YUV ->
                    throw IllegalArgumentException("YUV is not supported in DPX.")
            }
            val pixFmtCode = when (depth) {
                8 -> if (isGray) AV_PIX_FMT_GRAY8 else if (hasAlpha) AV_PIX_FMT_RGBA else AV_PIX_FMT_RGB24
                10 -> if (isGray) AV_PIX_FMT_GRAY10 else AV_PIX_FMT_GBRP10
                12 -> if (isGray) AV_PIX_FMT_GRAY12 else if (hasAlpha) AV_PIX_FMT_GBRAP12 else AV_PIX_FMT_GBRP12
                16 -> if (isGray) AV_PIX_FMT_GRAY16 else if (hasAlpha) AV_PIX_FMT_RGBA64 else AV_PIX_FMT_RGB48
                else -> throw IllegalArgumentException("Unsupported depth for DPX: $depth")
            }
            val alpha = if (hasAlpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
            representation = Bitmap.Representation(Bitmap.PixelFormat.of(pixFmtCode), colorSpace, alpha)
        }

        override fun write(bitmap: Bitmap): ByteArray =
            ByteArrayOutputStream().also { write(bitmap, it) }.toByteArray()

        override fun write(bitmap: Bitmap, file: Path) {
            // Don't use a buffered stream because our write() method only writes large chunks anyway.
            file.outputStream().use { write(bitmap, it) }
        }

        private fun write(bitmap: Bitmap, os: OutputStream) {
            val rep = bitmap.spec.representation
            require(rep == representation) { "Representation mismatch: Expected $representation, got $rep." }
            when (compression) {
                Compression.NONE -> writeUncompressed(bitmap, os)
                Compression.RLE -> writeRunLengthEncoded(bitmap, os)
            }
        }

        private fun writeUncompressed(bitmap: Bitmap, os: OutputStream) {
            val (w, h) = bitmap.spec.resolution
            val strideInts = when (depth) {
                8 -> if (isGray) ceilDiv(w, 4) else if (hasAlpha) w else ceilDiv(w * 3, 4)
                10 -> if (isGray) ceilDiv(w, 3) else w
                12, 16 -> if (isGray) ceilDiv(w, 2) else if (hasAlpha) w * 2 else ceilDiv(w * 3, 2)
                else -> throw IllegalStateException()
            }
            writeHeader(w, h, h * strideInts * 4, os)
            if (depth == 8 || depth == 16)
                os.write(bitmap.getB(strideInts * 4))
            else if (isGray) {
                val line = ByteBuffer.allocate(strideInts * 4).order(ByteOrder.nativeOrder())
                val seg = bitmap.memorySegment(0)
                val ls = bitmap.linesize(0)
                if (depth == 10)
                    for (y in 0..<h) {
                        var s = y * ls.toLong()
                        line.clear()
                        repeat(w / 3) {
                            val v0 = seg.getShort(s).toInt(); s += 2L
                            val v1 = seg.getShort(s).toInt(); s += 2L
                            val v2 = seg.getShort(s).toInt(); s += 2L
                            line.putInt((v0 shl 22) or ((v1 and 0x3FF) shl 12) or ((v2 and 0x3FF) shl 2))
                        }
                        when (w % 3) {
                            1 -> line.putInt(seg.getShort(s).toInt() shl 22)
                            2 -> {
                                val v0 = seg.getShort(s).toInt(); s += 2L
                                val v1 = seg.getShort(s).toInt()
                                line.putInt((v0 shl 22) or ((v1 and 0x3FF) shl 12))
                            }
                        }
                        os.write(line.array())
                    }
                else
                    for (y in 0..<h) {
                        var s = y * ls.toLong()
                        line.clear()
                        repeat(w) {
                            line.putShort((seg.getShort(s).toInt() shl 4).toShort()); s += 2L
                        }
                        os.write(line.array())
                    }
            } else {
                val line = ByteBuffer.allocate(strideInts * 4).order(ByteOrder.nativeOrder())
                val segR = bitmap.memorySegment(2)
                val segG = bitmap.memorySegment(0)
                val segB = bitmap.memorySegment(1)
                val segA = if (hasAlpha) bitmap.memorySegment(3) else null
                val ls = bitmap.linesize(0)
                if (depth == 10)
                    for (y in 0..<h) {
                        var s = y * ls.toLong()
                        line.clear()
                        repeat(w) {
                            val r = segR.getShort(s).toInt()
                            val g = segG.getShort(s).toInt()
                            val b = segB.getShort(s).toInt()
                            line.putInt((r shl 22) or ((g and 0x3FF) shl 12) or ((b and 0x3FF) shl 2))
                            s += 2L
                        }
                        os.write(line.array())
                    }
                else
                    for (y in 0..<h) {
                        var s = y * ls.toLong()
                        line.clear()
                        repeat(w) {
                            line.putShort((segR.getShort(s).toInt() shl 4).toShort())
                            line.putShort((segG.getShort(s).toInt() shl 4).toShort())
                            line.putShort((segB.getShort(s).toInt() shl 4).toShort())
                            if (segA != null)
                                line.putShort((segA.getShort(s).toInt() shl 4).toShort())
                            s += 2L
                        }
                        os.write(line.array())
                    }
            }
        }

        private fun writeRunLengthEncoded(bitmap: Bitmap, os: OutputStream) {
            val (w, h) = bitmap.spec.resolution
            val c = if (isGray) 1 else if (hasAlpha) 4 else 3
            val pixels = ShortArray(w * c)
            val stream = ShortArray(w * c * 2 /* more than long enough to be on the safe side */)
            val packed = ByteBuffer.allocate(h * stream.size * 2).order(ByteOrder.nativeOrder())
            for (y in 0..<h) {
                readLine(bitmap, y, pixels)
                val streamLen = runLengthEncode(w, c, pixels, stream)
                packLine(stream, streamLen, packed)
            }
            writeHeader(w, h, packed.position(), os)
            os.write(packed.array(), 0, packed.position())
        }

        private fun readLine(bitmap: Bitmap, y: Int, pixels: ShortArray) {
            val ls = bitmap.linesize(0)
            if (depth == 8) {
                val seg = bitmap.memorySegment(0).asSlice(y * ls.toLong())
                for (i in pixels.indices)
                    pixels[i] = toUnsignedInt(seg.getByte(i.toLong())).toShort()
            } else if (depth == 16 || isGray)
                MemorySegment.copy(bitmap.memorySegment(0), JAVA_SHORT, y * ls.toLong(), pixels, 0, pixels.size)
            else {
                val segR = bitmap.memorySegment(2)
                val segG = bitmap.memorySegment(0)
                val segB = bitmap.memorySegment(1)
                val segA = if (hasAlpha) bitmap.memorySegment(3) else null
                var s = y * ls.toLong()
                var p = 0
                repeat(bitmap.spec.resolution.widthPx) {
                    pixels[p++] = segR.getShort(s)
                    pixels[p++] = segG.getShort(s)
                    pixels[p++] = segB.getShort(s)
                    if (segA != null)
                        pixels[p++] = segA.getShort(s)
                    s += 2L
                }
            }
        }

        private fun runLengthEncode(w: Int, c: Int, pixels: ShortArray, stream: ShortArray): Int {
            val lim = (1 shl (depth - 1)) - 1
            var i = c
            var o = 0
            var oFlag = -1
            val runPx = pixels.copyOf(c)
            var runLen = 1
            for (x in 1..w) {
                if (x != w && Arrays.equals(pixels, i, i + c, runPx, 0, c) && runLen != lim)
                    runLen++
                else {
                    if (runLen <= 2) {
                        var cnt = 0
                        if (oFlag != -1 && ((toUnsignedInt(stream[oFlag]) shr 1) + runLen).also { cnt = it } <= lim)
                            stream[oFlag] = (cnt shl 1).toShort()
                        else {
                            oFlag = o++
                            stream[oFlag] = (runLen shl 1).toShort()
                        }
                        repeat(runLen) {
                            System.arraycopy(runPx, 0, stream, o, c)
                            o += c
                        }
                    } else {
                        oFlag = -1
                        stream[o++] = ((runLen shl 1) or 1).toShort()
                        System.arraycopy(runPx, 0, stream, o, c)
                        o += c
                    }
                    if (x != w) {
                        System.arraycopy(pixels, i, runPx, 0, c)
                        runLen = 1
                    }
                }
                i += c
            }
            return o
        }

        private fun packLine(stream: ShortArray, streamLen: Int, packed: ByteBuffer) {
            when (depth) {
                8 ->
                    for (s in 0..<streamLen)
                        packed.put(stream[s].toByte())
                10 -> {
                    var s = 0
                    repeat(streamLen / 3) {
                        val v0 = stream[s++].toInt()
                        val v1 = stream[s++].toInt()
                        val v2 = stream[s++].toInt()
                        packed.putInt((v0 shl 22) or ((v1 and 0x3FF) shl 12) or ((v2 and 0x3FF) shl 2))
                    }
                    when (streamLen % 3) {
                        1 -> packed.putInt(stream[s].toInt() shl 22)
                        2 -> {
                            val v0 = stream[s++].toInt()
                            val v1 = stream[s].toInt()
                            packed.putInt((v0 shl 22) or ((v1 and 0x3FF) shl 12))
                        }
                    }
                }
                12 ->
                    for (s in 0..<streamLen)
                        packed.putShort((stream[s].toInt() shl 4).toShort())
                16 -> {
                    packed.asShortBuffer().put(stream, 0, streamLen)
                    packed.position(packed.position() * streamLen * 2)
                }
            }
            packed.position(ceilDiv(packed.position(), 4) * 4)
        }

        private fun writeHeader(w: Int, h: Int, dataBytes: Int, os: OutputStream) {
            val trc: Byte =
                if (isGray) 2 else if (!colorSpace!!.transfer.hasCode) 0 else when (colorSpace.transfer.code) {
                    AVCOL_TRC_LINEAR -> 2
                    AVCOL_TRC_BT709 -> 6
                    AVCOL_TRC_SMPTE170M -> 7
                    else -> 0
                }
            val pri: Byte = if (isGray || !colorSpace!!.primaries.hasCode) 0 else when (colorSpace.primaries.code) {
                AVCOL_PRI_BT709 -> 6
                AVCOL_PRI_BT470BG -> 7
                AVCOL_PRI_SMPTE170M, AVCOL_PRI_SMPTE240M -> 8
                else -> 0
            }

            val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.nativeOrder())

            /* File information header */
            buf.putInt(0, ('S'.code shl 24) or ('D'.code shl 16) or ('P'.code shl 8) or 'X'.code)  // native endianness
            buf.putInt(4, HEADER_SIZE)  // offset to image data
            buf.put(8, "V1.0".toByteArray())  // DPX version
            buf.putInt(16, HEADER_SIZE + dataBytes)  // file size
            buf.putInt(20, 1)  // new image
            buf.putInt(24, HEADER_SIZE)  // generic header size
            buf.put(160, "Cinecred $VERSION".toByteArray())  // creator
            buf.putInt(660, -1)  // unencrypted

            /* Image information header */
            buf.putShort(768, 0)  // orientation
            buf.putShort(770, 1)  // number of image planes
            buf.putInt(772, w)  // pixels per line
            buf.putInt(776, h)  // number of lines
            buf.put(800, if (isGray) 6 else if (hasAlpha) 51 else 50)  // descriptor
            buf.put(801, trc)
            buf.put(802, pri)
            buf.put(803, depth.toByte())  // bit depth
            buf.putShort(804, if (depth == 10 || depth == 12) 1 else 0)  // packing method
            buf.putShort(806, if (compression == Compression.RLE) 1 else 0)  // encoding method
            buf.putInt(808, HEADER_SIZE)  // offset to image data

            /* Image source information header */
            buf.putInt(1628, 1)  // pixel aspect ratio horizontal
            buf.putInt(1632, 1)  // pixel aspect ratio vertical

            os.write(buf.array())
        }

        companion object {
            private const val HEADER_SIZE = 1664
        }

    }

}
