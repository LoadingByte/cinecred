package com.loadingbyte.cinecred.imaging

import org.bytedeco.ffmpeg.global.avutil.*
import java.awt.image.*
import java.nio.ByteOrder


/**
 * This class creates a [Bitmap.Representation] that fits an OS-native Java2D [ColorModel], and offers a way to very
 * efficiently convert bitmaps with that representation to [BufferedImage]s with that color model.
 */
class BitmapJ2DBridge(private val nativeCM: ColorModel) {

    val nativeRepresentation: Bitmap.Representation

    init {
        check(nativeCM.colorSpace.isCS_sRGB) { "Java2D's color space is expected to be sRGB." }

        val nativePixFmtCode = when {
            nativeCM is DirectColorModel -> {
                require(nativeCM.transferType == DataBuffer.TYPE_INT) { "Only int DirectColorModels supported." }
                val rm = nativeCM.redMask
                val gm = nativeCM.greenMask
                val bm = nativeCM.blueMask
                val am = nativeCM.alphaMask
                when {
                    am == 0 && rm == 0xFF0000 && gm == 0xFF00 && bm == 0xFF -> AV_PIX_FMT_0RGB
                    am == 0 && bm == 0xFF0000 && gm == 0xFF00 && rm == 0xFF -> AV_PIX_FMT_0BGR
                    am == 0xFF000000.toInt() && rm == 0xFF0000 && gm == 0xFF00 && bm == 0xFF -> AV_PIX_FMT_ARGB
                    am == 0xFF000000.toInt() && bm == 0xFF0000 && gm == 0xFF00 && rm == 0xFF -> AV_PIX_FMT_ABGR
                    rm == 0xFF000000.toInt() && gm == 0xFF0000 && bm == 0xFF00 && am == 0 -> AV_PIX_FMT_RGB0
                    bm == 0xFF000000.toInt() && gm == 0xFF0000 && rm == 0xFF00 && am == 0 -> AV_PIX_FMT_BGR0
                    rm == 0xFF000000.toInt() && gm == 0xFF0000 && bm == 0xFF00 && am == 0xFF -> AV_PIX_FMT_RGBA
                    bm == 0xFF000000.toInt() && gm == 0xFF0000 && rm == 0xFF00 && am == 0xFF -> AV_PIX_FMT_BGRA
                    else -> throw IllegalArgumentException("Unsupported DirectColorModel masks: $rm $gm $bm $am")
                }
            }
            nativeCM.javaClass.name == WIN_32_COLOR_MODEL_24 ->
                AV_PIX_FMT_BGR24
            nativeCM is ComponentColorModel -> {
                require(nativeCM.transferType == DataBuffer.TYPE_BYTE) { "Only byte ComponentColorModels supported." }
                val rs = nativeCM.getComponentSize(0)
                val gs = nativeCM.getComponentSize(1)
                val bs = nativeCM.getComponentSize(2)
                require(rs == 8 && gs == 8 && bs == 8) { "Each color model comp must have 8 bits, not: $rs $gs $bs" }
                // Note: Strictly speaking, we'd also need a sample model to know the order of components (e.g., RGBA
                // vs ABGR). However, the APIs that provide the native color model don't also provide a native sample
                // model. Still, they provide createCompatibleImage() methods, and from looking at their implementation
                // we see that the components are always in their natural order, i.e., RGBA.
                if (nativeCM.hasAlpha()) {
                    val als = nativeCM.getComponentSize(3)
                    require(als == 8) { "Alpha color model comp must have 8 bits, not: $als" }
                    AV_PIX_FMT_RGBA
                } else
                    AV_PIX_FMT_RGB24
            }
            else -> throw IllegalArgumentException("Unsupported color model type: ${nativeCM.javaClass.name}")
        }

        val nativeAlpha = when {
            !nativeCM.hasAlpha() -> Bitmap.Alpha.OPAQUE
            nativeCM.isAlphaPremultiplied -> Bitmap.Alpha.PREMULTIPLIED
            else -> Bitmap.Alpha.STRAIGHT
        }

        nativeRepresentation = Bitmap.Representation(
            Bitmap.PixelFormat.of(nativePixFmtCode), Bitmap.Range.FULL, ColorSpace.SRGB,
            yuvCoefficients = null, AVCHROMA_LOC_UNSPECIFIED, nativeAlpha
        )
    }

    fun toNativeImage(bitmap: Bitmap): BufferedImage {
        require(bitmap.spec.representation == nativeRepresentation) { "Representation mismatch." }
        val (w, h) = bitmap.spec.resolution

        val scanlineStride: Int
        val dataBuffer: DataBuffer
        when (nativeCM.transferType) {
            DataBuffer.TYPE_BYTE -> {
                scanlineStride = bitmap.linesize(0)
                val data = bitmap.getB(scanlineStride)
                dataBuffer = DataBufferByte(data, h * scanlineStride)
            }
            DataBuffer.TYPE_INT -> {
                scanlineStride = bitmap.linesize(0) / 4
                // This branch is only for DirectColorModels. In the constructor, we interpret their masks in big endian
                // byte order (i.e., 0xFF000000 refers to the first byte and 0xFF refers to the fourth byte), so now we
                // have to read the integers in big endian as well.
                val data = bitmap.getI(scanlineStride, byteOrder = ByteOrder.BIG_ENDIAN)
                dataBuffer = DataBufferInt(data, h * scanlineStride)
            }
            else -> throw IllegalStateException()
        }
        val raster = when {
            nativeCM is DirectColorModel ->
                Raster.createPackedRaster(dataBuffer, w, h, scanlineStride, nativeCM.masks, null)
            nativeCM.javaClass.name == WIN_32_COLOR_MODEL_24 ->
                Raster.createInterleavedRaster(dataBuffer, w, h, scanlineStride, 3, intArrayOf(2, 1, 0), null)
            nativeCM is ComponentColorModel ->
                if (nativeCM.hasAlpha())
                    Raster.createInterleavedRaster(dataBuffer, w, h, scanlineStride, 4, intArrayOf(0, 1, 2, 3), null)
                else
                    Raster.createInterleavedRaster(dataBuffer, w, h, scanlineStride, 3, intArrayOf(0, 1, 2), null)
            else -> throw IllegalStateException()
        }
        return BufferedImage(nativeCM, raster, nativeCM.isAlphaPremultiplied, null)
    }


    companion object {
        private const val WIN_32_COLOR_MODEL_24 = "sun.awt.Win32ColorModel24"
    }

}
