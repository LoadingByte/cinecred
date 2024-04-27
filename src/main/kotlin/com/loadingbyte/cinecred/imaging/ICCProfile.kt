package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.natives.skcms.skcms_CICP
import com.loadingbyte.cinecred.natives.skcms.skcms_Curve
import com.loadingbyte.cinecred.natives.skcms.skcms_ICCProfile
import com.loadingbyte.cinecred.natives.skcms.skcms_h.*
import com.loadingbyte.cinecred.natives.skiacapi.skiacapi_h.*
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBAF32
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBF32
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.*
import java.lang.Byte.toUnsignedInt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ConcurrentHashMap


/*
 * Implementation notes on color conversion:
 *   - We can't use ColorConvertOp because (a) it prefers perceptual over relative colorimetric transformations and
 *     (b) it may sometimes use Graphics2D drawing, which is limited to sRGB and thereby reduces the color fidelity.
 *   - Instead of directly accessing the JDK's bundled lcms, which could be accessed efficiently by obtaining a
 *     sun.java2d.cmm.ColorTransform object and then calling colorConvert(short[], short[]) on the whole pixel buffer
 *     at once, we prefer skcms because it's roughly 10x faster.
 */
class ICCProfile private constructor(
    val bytes: ByteArray,
    awtProf: ICC_Profile?,
    colorSpace: ColorSpace?
) {

    private val skcmsHandle: MemorySegment? by lazy {
        val arena = Arena.ofShared()
        // Note: The skcms documentation says that this buffer must not be deallocated.
        val bytesSeg = arena.allocateArray(bytes)
        val profileSeg = skcms_ICCProfile.allocate(arena)
        val prioSeg = arena.allocateArray(JAVA_INT, 1, 0 /* prefer relative colorimetric over perceptual */)
        if (skcms_ParseWithA2BPriority(bytesSeg, bytesSeg.byteSize(), prioSeg, 2, profileSeg)) {
            CLEANER.register(this, CleanerAction(arena))
            profileSeg
        } else {
            arena.close()
            null
        }
    }

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    private class CleanerAction(private val arena: Arena) : Runnable {
        override fun run() = arena.close()
    }

    // Note: This line indeed throws an IllegalArgumentException if the profile is corrupt, and that is intentional.
    private val awtCS: ICC_ColorSpace by lazy { ICC_ColorSpace(awtProfile) }

    /** @throws IllegalArgumentException If AWT cannot parse the [bytes]. */
    val awtProfile: ICC_Profile by awtProf?.let(::lazyOf) ?: lazy { ICC_Profile.getInstance(bytes) }

    val similarColorSpace: ColorSpace? by colorSpace?.let(::lazyOf) ?: lazy {
        val skcmsHandle = this.skcmsHandle ?: return@lazy null
        // If the profile has a CICP tag with well-known color space IDs, try reading that first.
        if (skcms_ICCProfile.`has_CICP$get`(skcmsHandle)) {
            val cicpSeg = skcms_ICCProfile.`CICP$slice`(skcmsHandle)
            try {
                val pri = ColorSpace.Primaries.of(toUnsignedInt(skcms_CICP.`color_primaries$get`(cicpSeg)))
                val trc = ColorSpace.Transfer.of(toUnsignedInt(skcms_CICP.`transfer_characteristics$get`(cicpSeg)))
                return@lazy ColorSpace.of(pri, trc)
            } catch (_: IllegalArgumentException) {
                // Continue with the traditional non-CICP path.
            }
        }
        // Ensures that the profile has a toXYZD50 matrix and exactly one parametric transfer function.
        if (!skcms_MakeUsableAsDestinationWithSingleCurve(skcmsHandle))
            return@lazy null
        // Return standard objects when we detect the sRGB color space.
        if (skcms_ApproximatelyEqualProfiles(skcmsHandle, skcms_sRGB_profile()))
            return@lazy ColorSpace.SRGB
        val pri = when (skcms_ICCProfile.`data_color_space$get`(skcmsHandle)) {
            skcms_Signature_Gray() -> Arena.ofConfined().use { arena ->
                val chadSeg = arena.allocateArray(JAVA_FLOAT, 9)
                if (!skcms_GetCHAD(skcmsHandle, chadSeg)) ColorSpace.Primaries.XYZD65 /* CHAD is often missing */ else
                    ColorSpace.Primaries.of(ColorSpace.Primaries.Matrix(chadSeg.toArray(JAVA_FLOAT)))
            }
            skcms_Signature_RGB() -> ColorSpace.Primaries.of(
                ColorSpace.Primaries.Matrix(skcms_ICCProfile.`toXYZD50$slice`(skcmsHandle).toArray(JAVA_FLOAT))
            )
            else -> return@lazy null
        }
        val curveArr = skcms_Curve.`parametric$slice`(skcms_ICCProfile.`trc$slice`(skcmsHandle)).toArray(JAVA_FLOAT)
        val trc = ColorSpace.Transfer.of(ColorSpace.Transfer.Curve(curveArr))
        ColorSpace.of(pri, trc)
    }

    /**
     * Converts a contiguous array of pixels that are in the G, RGB, or CMYK arrangement (so no alpha) and that have
     * been encoded with this ICC profile to the XYZD50 color space, and then stores the result into the output buffer
     * in the XYZA arrangement. The added alpha component is set to 1.
     *
     * Note: The number of pixels is derived from [dst]. It is not an error if [src] is larger than would be necessary
     * the accomodate that number of pixels.
     *
     * @throws IllegalArgumentException If the profile is corrupt, or the source buffer is too small.
     */
    fun convertICCArrayWithoutAlphaToXYZD50ArrayWithAlpha(src: FloatArray, dst: FloatArray) {
        skcmsHandle?.let { skcmsHandle ->
            // No support for gray color spaces as skcms doesn't have a gray float pixel format. And also no support for
            // CMYK as we observed that skcms sometimes converts it wrongly. In these cases, we use AWT instead.
            if (componentCountOfSkcmsProfile(skcmsHandle) != 3)
                return@let
            val nPixels = dst.size / 4
            // Verify source array size beforehand to prevent segfault.
            require(src.size >= nPixels * 3) { "Source pixel array does not match destination pixel array." }
            Arena.ofConfined().use { arena ->
                val srcSeg = arena.allocateArray(src)
                val dstSeg = arena.allocateArray(JAVA_FLOAT, dst.size.toLong())
                if (skcms_Transform(
                        srcSeg, skcms_PixelFormat_RGB_fff(), skcms_AlphaFormat_Opaque(), skcmsHandle,
                        dstSeg, skcms_PixelFormat_RGBA_ffff(), skcms_AlphaFormat_Opaque(), skcms_XYZD50_profile(),
                        nPixels.toLong()
                    )
                ) {
                    MemorySegment.copy(dstSeg, JAVA_FLOAT, 0L, dst, 0, dst.size)
                    return
                }
            }
        }

        val awtCS = this.awtCS
        val nComps = awtCS.numComponents
        // toCIEXYZ() casts the floats in "comps" to shorts, which may overflow. So we have to clamp beforehand.
        val min = FloatArray(nComps, awtCS::getMinValue)
        val max = FloatArray(nComps, awtCS::getMaxValue)
        val tmp = FloatArray(nComps)
        var s = 0
        var d = 0
        while (d < dst.size) {
            for (c in 0..<nComps)
                tmp[c] = src[s++].coerceIn(min[c], max[c])
            System.arraycopy(awtCS.toCIEXYZ(tmp), 0, dst, d, 3)
            dst[d + 3] = 1f
            d += 4
        }
    }

    /**
     * Converts an arbitrary [BufferedImage] whose colors have been encoded with this ICC profile (a fact that is not
     * verified) to a [contiguous][Bitmap.isContiguous] full-range [Bitmap] of the same resolution with XYZD50
     * primaries, linear transfer characteristics, and either the RGBF32 or RGBAF32 pixel format (irrespective of
     * whether the [src] image has alpha).
     *
     * @param fallBackToAWT If true, fall back to AWT code if necessary; otherwise, return false in that case.
     * @return True, unless falling back to AWT is necessary yet prohibited by [fallBackToAWT].
     * @throws IllegalArgumentException If the profile is corrupt or the image or bitmap are in the wrong format.
     */
    fun convertICCBufferedImageToXYZD50Bitmap(src: BufferedImage, dst: Bitmap, fallBackToAWT: Boolean): Boolean {
        val le = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

        // Get some information about the source image.
        val w = src.width
        val h = src.height
        val cm = src.colorModel
        val nComps = cm.numComponents
        val nColorComps = cm.numColorComponents
        val srcHasAlpha = cm.hasAlpha()
        val alphaComp = nComps - 1
        val tt = cm.transferType
        val raster = src.raster
        val sm = raster.sampleModel
        val db = raster.dataBuffer

        // Check that the destination bitmap is suitable to write to.
        val (res, rep) = dst.spec
        require(
            dst.isContiguous && res.widthPx == w && res.heightPx == h && rep.range == Bitmap.Range.FULL &&
                    rep.colorSpace == ColorSpace.XYZD50 && rep.alpha != Bitmap.Alpha.PREMULTIPLIED
        )
        val dstHasAlpha = when (rep.pixelFormat.code) {
            AV_PIX_FMT_RGBF32 -> false
            AV_PIX_FMT_RGBAF32 -> true
            else -> throw IllegalArgumentException()
        }

        // First try to use skcms.
        skcmsHandle?.let { skcmsHandle ->
            // No support for CMYK as we observed that skcms sometimes converts it wrongly. We use AWT instead.
            if (nColorComps != 1 && nColorComps != 3)
                return@let
            require(nColorComps == componentCountOfSkcmsProfile(skcmsHandle)) {
                "Color component count mismatch between image and ICC profile."
            }
            var srcSkcmsPixelFormat = -1
            var imgLineBytes = -1
            var imgAndSrcPixelBytes = -1
            var imgIntPacksBytes = false
            var img2srcReverseByteGroups = -1
            when (cm) {
                is DirectColorModel -> if (tt == DataBuffer.TYPE_INT) {
                    imgLineBytes = 4 * (sm as SinglePixelPackedSampleModel).scanlineStride
                    imgAndSrcPixelBytes = 4
                    imgIntPacksBytes = true
                    val rm = cm.redMask
                    val gm = cm.greenMask
                    val bm = cm.blueMask
                    val am = cm.alphaMask
                    when {
                        rm == 0xFF000000.toInt() && gm == 0xFF0000 && bm == 0xFF00 && (am == 0 || am == 0xFF) ->
                            srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_8888()
                        bm == 0xFF000000.toInt() && gm == 0xFF0000 && rm == 0xFF00 && (am == 0 || am == 0xFF) ->
                            srcSkcmsPixelFormat = skcms_PixelFormat_BGRA_8888()
                        (am == 0 || am == 0xFF000000.toInt()) && rm == 0xFF0000 && gm == 0xFF00 && bm == 0xFF -> {
                            srcSkcmsPixelFormat = skcms_PixelFormat_BGRA_8888()
                            img2srcReverseByteGroups = 4
                        }
                        (am == 0 || am == 0xFF000000.toInt()) && bm == 0xFF0000 && gm == 0xFF00 && rm == 0xFF -> {
                            srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_8888()
                            img2srcReverseByteGroups = 4
                        }
                    }
                }
                is ComponentColorModel -> {
                    val depth = DataBuffer.getDataTypeSize(tt)
                    imgLineBytes = (depth / 8) * (sm as ComponentSampleModel).scanlineStride
                    imgAndSrcPixelBytes = (depth / 8) * nComps
                    if (cm.componentSize.all { it == depth } &&
                        sm.pixelStride == nComps && sm.bankIndices.all { it == 0 }
                    ) {
                        val bo = sm.bandOffsets
                        val arrangement = when {
                            bo.size == 1 -> Arrangement.G
                            bo.size == 3 && bo[0] == 0 && bo[1] == 1 && bo[2] == 2 -> Arrangement.RGB
                            bo.size == 3 && bo[0] == 2 && bo[1] == 1 && bo[2] == 0 -> Arrangement.BGR
                            bo.size == 4 && bo[0] == 0 && bo[1] == 1 && bo[2] == 2 && bo[3] == 3 -> Arrangement.RGBA
                            bo.size == 4 && bo[0] == 2 && bo[1] == 1 && bo[2] == 0 && bo[3] == 3 -> Arrangement.BGRA
                            bo.size == 4 && bo[0] == 3 && bo[1] == 0 && bo[2] == 1 && bo[3] == 2 -> Arrangement.ARGB
                            bo.size == 4 && bo[0] == 3 && bo[1] == 2 && bo[2] == 1 && bo[3] == 0 -> Arrangement.ABGR
                            else -> null
                        }
                        when {
                            depth == 8 -> when (arrangement) {
                                Arrangement.G -> srcSkcmsPixelFormat = skcms_PixelFormat_G_8()
                                Arrangement.RGB -> srcSkcmsPixelFormat = skcms_PixelFormat_RGB_888()
                                Arrangement.BGR -> srcSkcmsPixelFormat = skcms_PixelFormat_BGR_888()
                                Arrangement.RGBA -> srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_8888()
                                Arrangement.BGRA -> srcSkcmsPixelFormat = skcms_PixelFormat_BGRA_8888()
                                // Fake these not directly supported arrangements by just reversing the byte order.
                                Arrangement.ARGB -> {
                                    srcSkcmsPixelFormat = skcms_PixelFormat_BGRA_8888()
                                    img2srcReverseByteGroups = 4
                                }
                                Arrangement.ABGR -> {
                                    srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_8888()
                                    img2srcReverseByteGroups = 4
                                }
                                null -> {}
                            }
                            depth == 16 && tt == DataBuffer.TYPE_USHORT -> when (arrangement) {
                                Arrangement.RGB -> srcSkcmsPixelFormat =
                                    if (le) skcms_PixelFormat_RGB_161616LE() else skcms_PixelFormat_RGB_161616BE()
                                Arrangement.BGR -> srcSkcmsPixelFormat =
                                    if (le) skcms_PixelFormat_BGR_161616LE() else skcms_PixelFormat_BGR_161616BE()
                                Arrangement.RGBA -> srcSkcmsPixelFormat =
                                    if (le) skcms_PixelFormat_RGBA_16161616LE() else skcms_PixelFormat_RGBA_16161616BE()
                                Arrangement.BGRA -> srcSkcmsPixelFormat =
                                    if (le) skcms_PixelFormat_BGRA_16161616LE() else skcms_PixelFormat_BGRA_16161616BE()
                                // Note: Because we reverse the byte order to turn ARGB into BGRA, the byte order of
                                // each 16-bit component is also reversed on accident, so we have to reverse it back
                                // by choosing the opposite endianness here.
                                Arrangement.ARGB -> {
                                    srcSkcmsPixelFormat = if (le)
                                        skcms_PixelFormat_BGRA_16161616BE() else
                                        skcms_PixelFormat_BGRA_16161616LE()
                                    img2srcReverseByteGroups = 8
                                }
                                Arrangement.ABGR -> {
                                    srcSkcmsPixelFormat = if (le)
                                        skcms_PixelFormat_RGBA_16161616BE() else
                                        skcms_PixelFormat_RGBA_16161616LE()
                                    img2srcReverseByteGroups = 8
                                }
                                null, Arrangement.G -> {}
                            }
                            depth == 32 && tt == DataBuffer.TYPE_FLOAT -> when (arrangement) {
                                Arrangement.RGB -> srcSkcmsPixelFormat = skcms_PixelFormat_RGB_fff()
                                Arrangement.BGR -> srcSkcmsPixelFormat = skcms_PixelFormat_BGR_fff()
                                Arrangement.RGBA -> srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_ffff()
                                Arrangement.BGRA -> srcSkcmsPixelFormat = skcms_PixelFormat_BGRA_ffff()
                                // We've never seen these in the wild.
                                null, Arrangement.G, Arrangement.ARGB, Arrangement.ABGR -> {}
                            }
                        }
                    }
                }
            }
            val dstSkcmsPixelFormat: Int
            val dstSkcmsAlphaFormat: Int
            if (!dstHasAlpha) {
                dstSkcmsPixelFormat = skcms_PixelFormat_RGB_fff()
                dstSkcmsAlphaFormat = skcms_AlphaFormat_Opaque()
            } else {
                dstSkcmsPixelFormat = skcms_PixelFormat_RGBA_ffff()
                dstSkcmsAlphaFormat = skcms_AlphaFormat_Unpremul()
            }
            val srcSkcmsAlphaFormat: Int
            val srcSeg: MemorySegment
            Arena.ofConfined().use { arena ->
                // If the image's format is directly supported by skcms, directly pass the image buffer.
                // Otherwise, use AWT to obtain the image data as floats and then pass those.
                if (srcSkcmsPixelFormat >= 0) {
                    srcSkcmsAlphaFormat = when {
                        !srcHasAlpha -> skcms_AlphaFormat_Opaque()
                        cm.isAlphaPremultiplied -> skcms_AlphaFormat_PremulAsEncoded()
                        else -> skcms_AlphaFormat_Unpremul()
                    }
                    val srcLineBytes = w * imgAndSrcPixelBytes
                    srcSeg = arena.allocate(h * srcLineBytes.toLong())
                    val imgSeg = when (db) {
                        is DataBufferByte -> MemorySegment.ofArray(db.data)
                        is DataBufferUShort -> MemorySegment.ofArray(db.data)
                        is DataBufferInt -> MemorySegment.ofArray(db.data)
                        is DataBufferFloat -> MemorySegment.ofArray(db.data)
                        else -> throw IllegalStateException()
                    }
                    val imgOffsetBytes = db.offset * (DataBuffer.getDataTypeSize(db.dataType) / 8L)
                    val baseLayout = when {
                        img2srcReverseByteGroups == 4 || imgIntPacksBytes -> JAVA_INT
                        img2srcReverseByteGroups == 8 -> JAVA_LONG
                        else -> JAVA_BYTE
                    }
                    // When we reinterpret the BufferedImage's actual layout to implement byte order reversing, we must
                    // set the alignment to 1 as byte array heap memory segments have that alignment.
                    val imgLayout = baseLayout.withByteAlignment(1L)
                    // Chose the byte order that realizes the configured reversing semantics.
                    val srcLayoutBO = when {
                        imgIntPacksBytes ->
                            if (img2srcReverseByteGroups == -1) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                        img2srcReverseByteGroups != -1 -> if (le) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                        else -> ByteOrder.nativeOrder()
                    }
                    val srcLayout = baseLayout.withOrder(srcLayoutBO)
                    val srcLineElems = srcLineBytes / srcLayout.byteSize()
                    // If possible, copy the whole source image buffer in one go. Otherwise, copy it line by line.
                    if (imgLineBytes == srcLineBytes)
                        MemorySegment.copy(imgSeg, imgLayout, imgOffsetBytes, srcSeg, srcLayout, 0L, h * srcLineElems)
                    else
                        for (y in 0..<h)
                            MemorySegment.copy(
                                imgSeg, imgLayout, imgOffsetBytes + y * imgLineBytes,
                                srcSeg, srcLayout, y * srcLineBytes.toLong(), srcLineElems
                            )
                } else {
                    // No support for gray color spaces here as skcms doesn't have a gray float pixel format.
                    if (nColorComps != 3)
                        return@let
                    if (!srcHasAlpha) {
                        srcSkcmsPixelFormat = skcms_PixelFormat_RGB_fff()
                        srcSkcmsAlphaFormat = skcms_AlphaFormat_Opaque()
                    } else {
                        srcSkcmsPixelFormat = skcms_PixelFormat_RGBA_ffff()
                        srcSkcmsAlphaFormat = skcms_AlphaFormat_Unpremul()
                    }
                    srcSeg = arena.allocate(h.toLong() * w * nComps * 4L)
                    var s = 0L
                    src.forEachPixelAsNormalizedComponents { normComps ->
                        MemorySegment.copy(normComps, 0, srcSeg, JAVA_FLOAT, s, nComps)
                        s += nComps * 4
                    }
                }
                if (skcms_Transform(
                        srcSeg, srcSkcmsPixelFormat, srcSkcmsAlphaFormat, skcmsHandle,
                        dst.memorySegment(0), dstSkcmsPixelFormat, dstSkcmsAlphaFormat, skcms_XYZD50_profile(),
                        h * w.toLong()
                    )
                ) return true
            }
        }

        // If skcms has failed, fall back to AWT's lcms.
        if (!fallBackToAWT)
            return false
        val awtCS = this.awtCS
        val dstSeg = dst.memorySegment(0)
        var d = 0L
        src.forEachPixelAsNormalizedComponents { normComps ->
            MemorySegment.copy(awtCS.toCIEXYZ(normComps), 0, dstSeg, JAVA_FLOAT, d, 3)
            d += 12L
            if (srcHasAlpha) {
                dstSeg.putFloat(d, normComps[alphaComp])
                d += 4L
            }
        }
        return true
    }

    private enum class Arrangement { G, RGB, BGR, RGBA, BGRA, ARGB, ABGR }


    companion object {

        fun of(bytes: ByteArray): ICCProfile =
            ICCProfile(bytes, null, null)

        fun of(awtProfile: ICC_Profile): ICCProfile =
            awtCache.computeIfAbsent(awtProfile) { ICCProfile(awtProfile.data, awtProfile, null) }

        fun of(colorSpace: ColorSpace): ICCProfile =
            csCache.computeIfAbsent(colorSpace) {
                val c = colorSpace.transfer.toLinear
                val m = colorSpace.primaries.toXYZD50.values
                val dataHandle = SkICC_SkWriteICCProfile(
                    c.g, c.a, c.b, c.c, c.d, c.e, c.f, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]
                )
                val bytes = SkData_data(dataHandle).reinterpret(SkData_size(dataHandle)).toArray(JAVA_BYTE)
                SkData_unref(dataHandle)
                ICCProfile(bytes, null, colorSpace)
            }

        private val awtCache = Collections.synchronizedMap(WeakHashMap<ICC_Profile, ICCProfile>())
        // Note: We don't need to use weak keys here because color spaces are never garbage-collected.
        private val csCache = ConcurrentHashMap<ColorSpace, ICCProfile>()

        private fun componentCountOfSkcmsProfile(skcmsHandle: MemorySegment) =
            when (skcms_ICCProfile.`data_color_space$get`(skcmsHandle)) {
                skcms_Signature_Gray() -> 1
                skcms_Signature_RGB(), skcms_Signature_Lab(), skcms_Signature_XYZ() -> 3
                skcms_Signature_CMYK() -> 4
                else -> -1
            }

    }

}
