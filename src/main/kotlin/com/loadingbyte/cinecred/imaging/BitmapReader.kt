package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.Resolution
import org.bytedeco.ffmpeg.global.avutil.*
import org.w3c.dom.Node
import java.awt.color.ICC_ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.Byte.toUnsignedInt
import java.nio.file.Path
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream


/**
 * Provides methods to read bitmaps from a byte array or from disk, and returns them in either interleaved or planar
 * float32 RBG(A) format with full range, linear transfer characteristics, and premultiplied alpha.
 */
object BitmapReader {

    /** @throws IOException */
    fun read(bytes: ByteArray, planar: Boolean): Bitmap =
        read(MemoryCacheImageInputStream(ByteArrayInputStream(bytes)), planar)

    /** @throws IOException */
    fun read(file: Path, planar: Boolean): Bitmap =
        read(FileImageInputStream(file.toFile()), planar)

    private fun read(iis: ImageInputStream, planar: Boolean): Bitmap {
        // Read a BufferredImage with an arbitrary color space.
        var (img, iccProfile) = iis.use {
            val reader = ImageIO.getImageReaders(iis).next()
            try {
                reader.setInput(iis, true, false /* needed by our PNG logic */)
                when (reader.formatName.lowercase()) {
                    "jpeg" -> Pair(readJPEG(reader), null)
                    "png" -> readPNG(reader)
                    else -> Pair(reader.read(0), null)
                }
            } finally {
                reader.dispose()
            }
        }

        // If the image is indexed, explode it.
        img.colorModel.let { cm -> if (cm is IndexColorModel) img = cm.convertToIntDiscrete(img.raster, true) }

        val w = img.width
        val h = img.height
        val cm = img.colorModel
        val cs = cm.colorSpace
        val hasAlpha = cm.hasAlpha()
        val res = Resolution(w, h)
        var actuallyOpaque = true

        // If we don't yet have an ICC profile but the image object has one (which is true for practically all
        // image objects returned by ImageIO), extract that profile.
        if (iccProfile == null && cs is ICC_ColorSpace)
            iccProfile = ICCProfile.of(cs.profile)
        // If the image has an ICC profile, use a fast code path to convert the image data to XYZD50.
        // Otherwise, fall back to a slow code path that uses standard AWT APIs.
        if (iccProfile != null) {
            // This is the only representation accepted by the ICCProfile's conversion method. And the bitmap
            // must also have contiguous memory, i.e., without padding.
            val contiguousRep = Bitmap.Representation(
                Bitmap.PixelFormat.of(if (hasAlpha) AV_PIX_FMT_RGBAF32 else AV_PIX_FMT_RGBF32),
                ColorSpace.XYZD50,
                if (hasAlpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
            )
            Bitmap.allocateContiguous(Bitmap.Spec(res, contiguousRep)).use { contiguous ->
                // Use skcms. If it fails, exit the outer if block and run our custom AWT-based conversion code.
                if (!iccProfile.convertICCBufferedImageToXYZD50Bitmap(img, contiguous, fallBackToAWT = false))
                    return@use
                // If the image had an alpha channel, check whether it really has transparent parts.
                if (hasAlpha) {
                    val contiguousSeg = contiguous.memorySegment(0)
                    var i = 12L
                    while (i < contiguousSeg.byteSize()) {
                        if (contiguousSeg.getFloat(i) < 0.999f) {
                            actuallyOpaque = false
                            break
                        }
                        i += 16L
                    }
                }
                // Transpose from interleaved to planar, and/or premultiply alpha.
                val bitmap = Bitmap.allocate(Bitmap.Spec(res, representation(planar, actuallyOpaque)))
                BitmapConverter.convert(contiguous, bitmap, actuallyOpaque)
                return bitmap
            }
        }

        // This is a slow fallback code path that uses standard AWT APIs to individually retrieve each pixel
        // from image and then individually convert that pixel to XYZD50.
        val alphaCompIdx = cm.numComponents - 1
        var a = 0
        if (!planar) {
            var arr: FloatArray
            if (!hasAlpha) {
                arr = FloatArray(h * w * 3)
                img.forEachPixelAsNormalizedComponents { normComps ->
                    val xyz = cs.toCIEXYZ(normComps)
                    arr[a++] = xyz[0]
                    arr[a++] = xyz[1]
                    arr[a++] = xyz[2]
                }
            } else {
                arr = FloatArray(h * w * 4)
                img.forEachPixelAsNormalizedComponents { normComps ->
                    val xyz = cs.toCIEXYZ(normComps)
                    val alpha = normComps[alphaCompIdx]
                    // Premultiply the pixel's color.
                    arr[a++] = alpha * xyz[0]
                    arr[a++] = alpha * xyz[1]
                    arr[a++] = alpha * xyz[2]
                    arr[a++] = alpha
                    if (alpha < 0.999f) actuallyOpaque = false
                }
                // Drop the alpha component if it is 1 everywhere.
                if (actuallyOpaque) {
                    val arr2 = FloatArray(h * w * 3)
                    a = 0
                    for (i in arr.indices) if (i and 3 != 3) arr2[a++] = arr[i]
                    arr = arr2
                }
            }
            val bitmap = Bitmap.allocate(Bitmap.Spec(res, representation(planar = false, actuallyOpaque)))
            bitmap.put(arr, w * if (actuallyOpaque) 3 else 4)
            return bitmap
        } else {
            val arrX = FloatArray(h * w)
            val arrY = FloatArray(h * w)
            val arrZ = FloatArray(h * w)
            var arrA: FloatArray? = null
            if (!hasAlpha)
                img.forEachPixelAsNormalizedComponents { normComps ->
                    val xyz = cs.toCIEXYZ(normComps)
                    arrX[a] = xyz[0]
                    arrY[a] = xyz[1]
                    arrZ[a] = xyz[2]
                    a++
                }
            else {
                arrA = FloatArray(h * w)
                img.forEachPixelAsNormalizedComponents { normComps ->
                    val xyz = cs.toCIEXYZ(normComps)
                    val alpha = normComps[alphaCompIdx]
                    // Premultiply the pixel's color.
                    arrX[a] = alpha * xyz[0]
                    arrY[a] = alpha * xyz[1]
                    arrZ[a] = alpha * xyz[2]
                    arrA[a] = alpha
                    if (alpha < 0.999f) actuallyOpaque = false
                    a++
                }
            }
            val bitmap = Bitmap.allocate(Bitmap.Spec(res, representation(planar = true, actuallyOpaque)))
            // GBR is responsible for this weird plane order.
            bitmap.put(arrX, w, 2)
            bitmap.put(arrY, w, 0)
            bitmap.put(arrZ, w, 1)
            if (!actuallyOpaque)
                bitmap.put(arrA!!, w, 3)
            return bitmap
        }
    }

    private fun representation(planar: Boolean, opaque: Boolean): Bitmap.Representation {
        val pixelFormatCode = when {
            !planar && opaque -> AV_PIX_FMT_RGBF32
            !planar && !opaque -> AV_PIX_FMT_RGBAF32
            planar && opaque -> AV_PIX_FMT_GBRPF32
            else -> AV_PIX_FMT_GBRAPF32
        }
        return Bitmap.Representation(
            Bitmap.PixelFormat.of(pixelFormatCode),
            ColorSpace.XYZD50,
            if (opaque) Bitmap.Alpha.OPAQUE else Bitmap.Alpha.PREMULTIPLIED
        )
    }

    private fun readJPEG(reader: ImageReader): BufferedImage {
        // Tricky hack: for JPEG images encoded as YUV (which applies to most JPEGs), JPEGImageReader prefers
        // converting to sRGB over keeping the JPEG's ICC profile. We don't want this as the conversion to sRGB
        // could clip colors. Hence, we explicitly direct the reader to generate an image with the ICC profile.
        val param = reader.defaultReadParam
        val imageTypes = reader.getImageTypes(0).asSequence().toList()
        if (imageTypes.size == 3)
            param.destinationType = imageTypes[1]
        return reader.read(0, param)
    }

    private fun readPNG(reader: ImageReader): Pair<BufferedImage, ICCProfile?> {
        val iioImage = reader.readAll(0, null)
        val img = iioImage.renderedImage as BufferedImage

        // Find the embedded ICC profile, if any.
        val md = iioImage.metadata
        val mdRoot = md.getAsTree(md.nativeMetadataFormatName)
        mdRoot.getChild("UnknownChunks")?.let { unknownChunksNode ->
            var child = unknownChunksNode.firstChild
            while (child != null) {
                if ((child as IIOMetadataNode).getAttribute("type") == "cICP") {
                    val tags = child.userObject as ByteArray
                    if (tags.size >= 2)
                        try {
                            // Note: We ignore the full range flag.
                            val primaries = ColorSpace.Primaries.of(toUnsignedInt(tags[0]))
                            val transfer = ColorSpace.Transfer.of(toUnsignedInt(tags[1]))
                            return Pair(img, ICCProfile.of(ColorSpace.of(primaries, transfer)))
                        } catch (_: IllegalArgumentException) {
                        }
                }
                child = child.nextSibling
            }
        }
        mdRoot.getChild("iCCP")?.let { iccNode ->
            val compressedBytes = iccNode.userObject as ByteArray
            try {
                val bytes = InflaterInputStream(ByteArrayInputStream(compressedBytes)).use { it.readAllBytes() }
                return Pair(img, ICCProfile.of(bytes))
            } catch (_: ZipException) {
            }
        }
        if (mdRoot.getChild("sRGB") == null) {
            val chromaticities = mdRoot.getChild("cHRM")?.let { chromaNode ->
                try {
                    ColorSpace.Primaries.Chromaticities(
                        rx = chromaNode.getAttribute("redX").toInt() * 0.00001f,
                        ry = chromaNode.getAttribute("redY").toInt() * 0.00001f,
                        gx = chromaNode.getAttribute("greenX").toInt() * 0.00001f,
                        gy = chromaNode.getAttribute("greenY").toInt() * 0.00001f,
                        bx = chromaNode.getAttribute("blueX").toInt() * 0.00001f,
                        by = chromaNode.getAttribute("blueY").toInt() * 0.00001f,
                        wx = chromaNode.getAttribute("whitePointX").toInt() * 0.00001f,
                        wy = chromaNode.getAttribute("whitePointY").toInt() * 0.00001f
                    )
                } catch (_: NumberFormatException) {
                    null
                }
            }
            val gamma = mdRoot.getChild("gAMA")?.let { gammaNode ->
                try {
                    0.00001f / gammaNode.getAttribute("value").toInt()
                } catch (_: NumberFormatException) {
                    null
                }
            }
            if (chromaticities != null || gamma != null) {
                val primaries = try {
                    chromaticities?.let(ColorSpace.Primaries::of)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: ColorSpace.Primaries.BT709
                val transfer = if (gamma != null) ColorSpace.Transfer.of(ColorSpace.Transfer.Curve(gamma)) else
                    ColorSpace.Transfer.SRGB
                return Pair(img, ICCProfile.of(ColorSpace.of(primaries, transfer)))
            }
        }
        return Pair(img, ICCProfile.of(ColorSpace.SRGB))
    }

    private fun Node.getChild(name: String): IIOMetadataNode? {
        var child = firstChild
        while (child != null) {
            if (child.nodeName == name)
                return child as IIOMetadataNode
            child = child.nextSibling
        }
        return null
    }

}
