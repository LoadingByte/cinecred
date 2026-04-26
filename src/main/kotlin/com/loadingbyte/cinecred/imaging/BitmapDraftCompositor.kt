package com.loadingbyte.cinecred.imaging

import jdk.incubator.vector.ByteVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.ShortVector
import jdk.incubator.vector.Vector
import jdk.incubator.vector.VectorOperators.*
import org.bytedeco.ffmpeg.global.avutil.*
import kotlin.math.max
import kotlin.math.roundToInt
import jdk.incubator.vector.ByteVector.SPECIES_PREFERRED as B
import jdk.incubator.vector.ByteVector.fromMemorySegment as vec
import jdk.incubator.vector.FloatVector.SPECIES_PREFERRED as F
import jdk.incubator.vector.FloatVector.fromMemorySegment as vec
import jdk.incubator.vector.ShortVector.SPECIES_PREFERRED as S
import java.lang.Byte.toUnsignedInt as uint
import java.nio.ByteOrder.nativeOrder as nBO


fun draftOverlayRepresentation(colorSpace: ColorSpace, hasAlpha: Boolean) = Bitmap.Representation(
    Bitmap.PixelFormat.of(if (hasAlpha) AV_PIX_FMT_GBRAPF32 else AV_PIX_FMT_GBRPF32),
    colorSpace,
    if (hasAlpha) Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE
)


/**
 * Overlays a planar float32 RGB(A) bitmap over a wide range of possible base bitmaps, and does it very quickly, albeit
 * with potentially reduced precision. In contrast to regular blending, this function blends in the shared color space
 * of the provided bitmaps. If that color space uses the sRGB transfer characteristics, the result will look very
 * similar to our regular blending, so it's fine for previews. But if the transfer characteristics is wildly different,
 * you should probably not use this function.
 */
fun draftComposite(overlay: Bitmap, base: Bitmap, x: Int = 0, y: Int = 0, alpha: Double = 1.0) {
    val fgRep = overlay.spec.representation
    val bgRep = base.spec.representation
    val isFgOpaque = !fgRep.pixelFormat.hasAlpha && alpha == 1.0

    require(bgRep.range == fgRep.range) { "Base and overlay ranges differ." }
    require(bgRep.colorSpace == fgRep.colorSpace) { "Base and overlay color spaces differ." }
    require(isPlanarFloat32RGB(fgRep.pixelFormat.code)) { "Overlay must be a planar float32 RGB." }
    require(bgRep.alpha != Bitmap.Alpha.STRAIGHT) { "If base has alpha, it must be premultiplied." }
    require(fgRep.alpha != Bitmap.Alpha.STRAIGHT) { "If overlay has alpha, it must be premultiplied." }

    var fg = overlay
    var bg = base
    val useViews = fg.spec.resolution != bg.spec.resolution || x != 0 || y != 0
    if (useViews) {
        val (fgW, fgH) = fg.spec.resolution
        val (bgW, bgH) = bg.spec.resolution
        val bgX = max(0, x)
        val bgY = max(0, y)
        val fgX = max(0, -x)
        val fgY = max(0, -y)
        val w = minOf(fgW, fgW - fgX, bgW - bgX)
        val h = minOf(fgH, fgH - fgY, bgH - bgY)
        if (w <= 0 || h <= 0)
            return
        fg = overlay.view(fgX, fgY, w, h, 1)
        bg = base.view(bgX, bgY, w, h, 1)
    }

    try {
        when {
            bgRep == fgRep && isFgOpaque -> bg.blit(fg)
            isPlanarFloat32RGB(bgRep.pixelFormat.code) -> draftCompositeOntoPF(fg, bg, alpha)
            isFgOpaque && draftCompositeOpaqueOntoIB(fg, bg) -> {}
            !isFgOpaque && draftCompositeOntoIB(fg, bg, alpha) -> {}
            else -> throw UnsupportedOperationException("Base has unsupported pixel format: ${bgRep.pixelFormat}")
        }
    } finally {
        if (useViews) {
            fg.close()
            bg.close()
        }
    }
}

private fun isPlanarFloat32RGB(code: Int) = when (code) {
    AV_PIX_FMT_GBRPF32LE, AV_PIX_FMT_GBRPF32BE, AV_PIX_FMT_GBRAPF32LE, AV_PIX_FMT_GBRAPF32BE -> true
    else -> false
}


private fun draftCompositeOntoPF(fg: Bitmap, bg: Bitmap, alpha: Double) {
    val fgHasAlpha = fg.spec.representation.pixelFormat.hasAlpha
    val bgHasAlpha = bg.spec.representation.pixelFormat.hasAlpha
    when {
        fgHasAlpha && bgHasAlpha -> ontoPF_tt(fg, bg, alpha)
        fgHasAlpha && !bgHasAlpha -> ontoPF_tf(fg, bg, alpha)
        !fgHasAlpha && bgHasAlpha -> ontoPF_ft(fg, bg, alpha)
        else -> ontoPF_ff(fg, bg, alpha)
    }
}

private fun ontoPF_tt(fg: Bitmap, bg: Bitmap, alpha: Double) = ontoPF(fg, bg, alpha, true, true)
private fun ontoPF_tf(fg: Bitmap, bg: Bitmap, alpha: Double) = ontoPF(fg, bg, alpha, true, false)
private fun ontoPF_ft(fg: Bitmap, bg: Bitmap, alpha: Double) = ontoPF(fg, bg, alpha, false, true)
private fun ontoPF_ff(fg: Bitmap, bg: Bitmap, alpha: Double) = ontoPF(fg, bg, alpha, false, false)

@Suppress("NOTHING_TO_INLINE")
private inline fun ontoPF(fg: Bitmap, bg: Bitmap, alpha: Double, fgHasAlpha: Boolean, bgHasAlpha: Boolean) {
    val (w, h) = fg.spec.resolution

    val fgPixFmt = fg.spec.representation.pixelFormat
    val bgPixFmt = bg.spec.representation.pixelFormat
    val (fgR, fgG, fgB) = fgPixFmt.components.subList(0, 3).map { fg.memorySegment(it.plane) }
    val (bgR, bgG, bgB) = bgPixFmt.components.subList(0, 3).map { bg.memorySegment(it.plane) }
    val fgA = if (fgHasAlpha) fg.memorySegment(fgPixFmt.components[3].plane) else null
    val bgA = if (bgHasAlpha) bg.memorySegment(bgPixFmt.components[3].plane) else null
    val fgLs = fg.linesize(0)
    val bgLs = bg.linesize(0)
    val fgBO = fgPixFmt.byteOrder
    val bgBO = bgPixFmt.byteOrder

    val scaA = alpha.toFloat()
    val scaBgMulPrep = 1f - scaA
    val vec1 = FloatVector.broadcast(F, 1f)
    val vecA = FloatVector.broadcast(F, scaA)
    val vecBgMulPrep = if (!fgHasAlpha) FloatVector.broadcast(F, scaBgMulPrep) else null

    val maxVecX = F.loopBound(w)

    for (y in 0L..<h) {
        var f = y * fgLs
        var b = y * bgLs
        var x = 0

        while (x < maxVecX) {
            val vecFgA: FloatVector
            val vecBugMul: FloatVector
            if (fgHasAlpha) {
                vecFgA = vec(F, fgA, f, fgBO).mul(vecA)
                vecBugMul = vec1.sub(vecFgA)
            } else {
                vecFgA = vecA
                vecBugMul = vecBgMulPrep!!
            }
            if (bgHasAlpha)
                vec(F, bgA, b, bgBO).fmaFast(vecBugMul, vecFgA).intoMemorySegment(bgA, b, bgBO)
            vec(F, bgR, b, bgBO).fmaFast(vecBugMul, vec(F, fgR, f, fgBO).mul(vecA)).intoMemorySegment(bgR, b, bgBO)
            vec(F, bgG, b, bgBO).fmaFast(vecBugMul, vec(F, fgG, f, fgBO).mul(vecA)).intoMemorySegment(bgG, b, bgBO)
            vec(F, bgB, b, bgBO).fmaFast(vecBugMul, vec(F, fgB, f, fgBO).mul(vecA)).intoMemorySegment(bgB, b, bgBO)
            f += F.vectorByteSize()
            b += F.vectorByteSize()
            x += F.length()
        }

        while (x < w) {
            val scaFgA: Float
            val scaBgMul: Float
            if (fgHasAlpha) {
                scaFgA = fgA!!.getFloat(f, fgBO) * scaA
                scaBgMul = 1f - scaFgA
            } else {
                scaFgA = scaA
                scaBgMul = scaBgMulPrep
            }
            if (bgHasAlpha)
                bgA!!.putFloat(b, bgBO, Math.fma(bgA.getFloat(b, bgBO), scaBgMul, scaFgA))
            bgR.putFloat(b, bgBO, Math.fma(bgR.getFloat(b, bgBO), scaBgMul, fgR.getFloat(f, fgBO) * scaA))
            bgG.putFloat(b, bgBO, Math.fma(bgG.getFloat(b, bgBO), scaBgMul, fgG.getFloat(f, fgBO) * scaA))
            bgB.putFloat(b, bgBO, Math.fma(bgB.getFloat(b, bgBO), scaBgMul, fgB.getFloat(f, fgBO) * scaA))
            f += 4
            b += 4
            x++
        }
    }
}


private fun draftCompositeOntoIB(fg: Bitmap, bg: Bitmap, alpha: Double): Boolean {
    val bgPixFmtCode = bg.spec.representation.pixelFormat.code
    if (fg.spec.representation.pixelFormat.hasAlpha)
        when (bgPixFmtCode) {
            AV_PIX_FMT_RGB24 -> onto3B_t_rgb(fg, bg, alpha)
            AV_PIX_FMT_BGR24 -> onto3B_t_bgr(fg, bg, alpha)
            AV_PIX_FMT_RGBA -> onto4B_t_rgba(fg, bg, alpha)
            AV_PIX_FMT_BGRA -> onto4B_t_bgra(fg, bg, alpha)
            AV_PIX_FMT_ARGB -> onto4B_t_argb(fg, bg, alpha)
            AV_PIX_FMT_ABGR -> onto4B_t_abgr(fg, bg, alpha)
            AV_PIX_FMT_RGB0 -> onto4B_t_rgb0(fg, bg, alpha)
            AV_PIX_FMT_BGR0 -> onto4B_t_bgr0(fg, bg, alpha)
            AV_PIX_FMT_0RGB -> onto4B_t_0rgb(fg, bg, alpha)
            AV_PIX_FMT_0BGR -> onto4B_t_0bgr(fg, bg, alpha)
            else -> return false
        }
    else
        when (bgPixFmtCode) {
            AV_PIX_FMT_RGB24 -> onto3B_f_rgb(fg, bg, alpha)
            AV_PIX_FMT_BGR24 -> onto3B_f_bgr(fg, bg, alpha)
            AV_PIX_FMT_RGBA -> onto4B_f_rgba(fg, bg, alpha)
            AV_PIX_FMT_BGRA -> onto4B_f_bgra(fg, bg, alpha)
            AV_PIX_FMT_ARGB -> onto4B_f_argb(fg, bg, alpha)
            AV_PIX_FMT_ABGR -> onto4B_f_abgr(fg, bg, alpha)
            AV_PIX_FMT_RGB0 -> onto4B_f_rgb0(fg, bg, alpha)
            AV_PIX_FMT_BGR0 -> onto4B_f_bgr0(fg, bg, alpha)
            AV_PIX_FMT_0RGB -> onto4B_f_0rgb(fg, bg, alpha)
            AV_PIX_FMT_0BGR -> onto4B_f_0bgr(fg, bg, alpha)
            else -> return false
        }
    return true
}

private fun onto3B_t_rgb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, true, true, false, 0, 1, 2)
private fun onto3B_t_bgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, true, true, false, 2, 1, 0)
private fun onto3B_f_rgb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, true, false, false, 0, 1, 2)
private fun onto3B_f_bgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, true, false, false, 2, 1, 0)
private fun onto4B_t_rgba(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, true, 0, 1, 2)
private fun onto4B_t_bgra(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, true, 2, 1, 0)
private fun onto4B_t_argb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, true, 3, 0, 1)
private fun onto4B_t_abgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, true, 3, 2, 1)
private fun onto4B_t_rgb0(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, false, 0, 1, 2)
private fun onto4B_t_bgr0(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, false, 2, 1, 0)
private fun onto4B_t_0rgb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, false, 3, 0, 1)
private fun onto4B_t_0bgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, true, false, 3, 2, 1)
private fun onto4B_f_rgba(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, true, 0, 1, 2)
private fun onto4B_f_bgra(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, true, 2, 1, 0)
private fun onto4B_f_argb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, true, 3, 0, 1)
private fun onto4B_f_abgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, true, 3, 2, 1)
private fun onto4B_f_rgb0(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, false, 0, 1, 2)
private fun onto4B_f_bgr0(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, false, 2, 1, 0)
private fun onto4B_f_0rgb(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, false, 3, 0, 1)
private fun onto4B_f_0bgr(fg: Bitmap, bg: Bitmap, alpha: Double) = onto4B(fg, bg, alpha, false, false, false, 3, 2, 1)

@Suppress("NOTHING_TO_INLINE")
private inline fun onto4B(
    fg: Bitmap, bg: Bitmap, alpha: Double,
    bgIs3B: Boolean, fgHasAlpha: Boolean, bgHasAlpha: Boolean,
    // These parameters specify the order of channels in the interleaved 4B format.
    // For example, if bgC0=2, that means that the first byte (C0) is blue (2).
    // bgC3 is implicit.
    bgC0: Int, bgC1: Int, bgC2: Int
) {
    val (w, h) = fg.spec.resolution

    val fgPixFmt = fg.spec.representation.pixelFormat
    val (fgR, fgG, fgB) = fgPixFmt.components.subList(0, 3).map { fg.memorySegment(it.plane) }
    val fgA = if (fgHasAlpha) fg.memorySegment(fgPixFmt.components[3].plane) else null
    val bgS = bg.memorySegment(0)
    val fgLs = fg.linesize(0)
    val bgLs = bg.linesize(0)
    val fgBO = fgPixFmt.byteOrder

    val offR = if (bgC0 == 0) 0 else if (bgC1 == 0) 1 else if (bgC2 == 0) 2 else 3
    val offG = if (bgC0 == 1) 0 else if (bgC1 == 1) 1 else if (bgC2 == 1) 2 else 3
    val offB = if (bgC0 == 2) 0 else if (bgC1 == 2) 1 else if (bgC2 == 2) 2 else 3
    val offA = if (bgC0 == 3) 0 else if (bgC1 == 3) 1 else if (bgC2 == 3) 2 else 3

    val fScaA = (alpha * (256 * 255)).toFloat()
    val fScaAPre255 = (alpha * 256).toFloat()
    val fVec0 = FloatVector.broadcast(F, 0f)
    val fVecA = FloatVector.broadcast(F, fScaA)
    val fVecAPre255 = if (fgHasAlpha) FloatVector.broadcast(F, fScaAPre255) else null

    val scaA = fScaA.roundToInt()
    val scaBgMulPrep = 256 - fScaAPre255.toInt()
    val vec8 = ShortVector.broadcast(S, 8.toShort())
    val vec255 = if (fgHasAlpha) ShortVector.broadcast(S, 255.toShort()) else null
    val vec256 = if (fgHasAlpha) ShortVector.broadcast(S, 256.toShort()) else null
    val vecA = if (!fgHasAlpha) ShortVector.broadcast(S, scaA.toShort()) else null
    val vecBgMulPrep = if (!fgHasAlpha) ShortVector.broadcast(S, scaBgMulPrep.toShort()) else null

    val vlen = B.vectorByteSize()
    val vlen2 = vlen / 2
    val vlen4 = vlen / 4
    val vlen8 = vlen / 8
    val maxVecX = F.loopBound(w - if (bgIs3B) vlen / 16 else 0)
    val repeatPart0 = if (bgIs3B) S.shuffleFromOp { i -> i / 3 } else S.shuffleFromOp { i -> i / 4 }
    val repeatPart1 = if (bgIs3B) S.shuffleFromOp { i -> i / 3 + vlen8 } else S.shuffleFromOp { i -> i / 4 + vlen8 }
    val zip2SBlocks = S.shuffleFromOp { i -> i / vlen8 % 2 * vlen4 + i / vlen4 * vlen8 + i % vlen8 }
    val zip3S = if (bgIs3B) S.shuffleFromOp { i -> if (i < 3 * vlen8) i / 3 + (i % 3) * vlen8 else i } else null
    val zip4S = if (!bgIs3B) S.shuffleFromOp { i -> i / 4 + (i % 4) * vlen8 } else null
    val blendBgMask = if (bgIs3B) B.indexInRange(0, 3 * vlen4) else null

    for (y in 0L..<h) {
        var f = y * fgLs
        var b = y * bgLs
        var x = 0

        while (x < maxVecX) {
            val vecFgA: Vector<Short>
            val vecBgMulPart0: ShortVector
            val vecBgMulPart1: ShortVector
            if (fgHasAlpha) {
                // Note: We do not need to clamp the alpha, as all our code ensures it's never outside the 0..1 range.
                val tmp = vec(F, fgA, f, fgBO).mul(fVecAPre255).convert(F2S, 0)
                vecFgA = tmp.mul(vec255)
                vecBgMulPart0 = vec256!!.sub(tmp.rearrange(repeatPart0))
                vecBgMulPart1 = vec256.sub(tmp.rearrange(repeatPart1))
            } else {
                vecFgA = vecA!!
                vecBgMulPart0 = vecBgMulPrep!!
                vecBgMulPart1 = vecBgMulPrep
            }
            val vecFgR = vec(F, fgR, f, fgBO).mul(fVecA).min(fVecA).max(fVec0).convert(F2S, 0)
            val vecFgG = vec(F, fgG, f, fgBO).mul(fVecA).min(fVecA).max(fVec0).convert(F2S, 0)
            val vecFgB = vec(F, fgB, f, fgBO).mul(fVecA).min(fVecA).max(fVec0).convert(F2S, 0)
            // Note: We deliberately condition on off* instead of bgC*, because if we did the latter, the Kotlin
            // compiler would generate a switch statement instead of actually inlining vecFgR/G/B/A into the below
            // right-hand sides for vecFg01/23, and we don't want to rely on the C2 compiler to be smart enough to
            // optimize that away.
            val vecFg0 = if (offR == 0) vecFgR else if (offG == 0) vecFgG else if (offB == 0) vecFgB else vecFgA
            val vecFg1 = if (offR == 1) vecFgR else if (offG == 1) vecFgG else if (offB == 1) vecFgB else vecFgA
            val vecFg2 = if (offR == 2) vecFgR else if (offG == 2) vecFgG else if (offB == 2) vecFgB else vecFgA
            val vecFg3 = if (offR == 3) vecFgR else if (offG == 3) vecFgG else if (offB == 3) vecFgB else vecFgA
            // Like BitmapConverter.PlanarFloatStage.convPFTo3/4S().
            val vecFg01 = vecFg1.unslice(vlen4, vecFg0, 0).rearrange(zip2SBlocks)
            val vecFgInterleavedPart0: Vector<Short>
            val vecFgInterleavedPart1: Vector<Short>
            if (bgIs3B) {
                vecFgInterleavedPart0 = vecFg2.unslice(vlen4, vecFg01, 0).rearrange(zip3S)
                vecFgInterleavedPart1 = vecFg01.unslice(vlen4, vecFg2.unslice(vlen8), 1).rearrange(zip3S)
            } else {
                val vecFg23 = vecFg3.unslice(vlen4, vecFg2, 0).rearrange(zip2SBlocks)
                vecFgInterleavedPart0 = vecFg23.unslice(vlen4, vecFg01, 0).rearrange(zip4S)
                vecFgInterleavedPart1 = vecFg01.unslice(vlen4, vecFg23, 1).rearrange(zip4S)
            }
            val vecBg = vec(B, bgS, b, nBO())
            val vecBgPart0 =
                vecBg.convert(ZERO_EXTEND_B2S, 0)
                    .mul(vecBgMulPart0).add(vecFgInterleavedPart0).lanewise(LSHR, vec8).convert(S2B, 0)
            val vecBgPart1 =
                (if (bgIs3B) vecBg.slice(3 * vlen8) else vecBg).convert(ZERO_EXTEND_B2S, if (bgIs3B) 0 else 1)
                    .mul(vecBgMulPart1).add(vecFgInterleavedPart1).lanewise(LSHR, vec8).convert(S2B, 0)
            val newVecBg =
                if (bgIs3B) vecBg.blend(vecBgPart1.unslice(3 * vlen8, vecBgPart0, 0), blendBgMask)
                else vecBgPart1.unslice(vlen2, vecBgPart0, 0)
            newVecBg.intoMemorySegment(bgS, b, nBO())
            f += vlen
            b += if (bgIs3B) 3 * vlen4 else vlen
            x += vlen4
        }

        while (x < w) {
            val scaFgA: Int
            val scaBgMul: Int
            if (fgHasAlpha) {
                // Note: We do not need to clamp the alpha, as all our code ensures it's never outside the 0..1 range.
                val tmp = (fgA!!.getFloat(f, fgBO) * fScaAPre255).toInt()
                scaFgA = tmp * 255
                scaBgMul = 256 - tmp
            } else {
                scaFgA = scaA
                scaBgMul = scaBgMulPrep
            }
            val scaFgR = (fgR.getFloat(f, fgBO) * fScaA).coerceIn(0f, fScaA).toInt()
            val scaFgG = (fgG.getFloat(f, fgBO) * fScaA).coerceIn(0f, fScaA).toInt()
            val scaFgB = (fgB.getFloat(f, fgBO) * fScaA).coerceIn(0f, fScaA).toInt()
            if (bgHasAlpha)
                bgS.putByte(b + offA, ((uint(bgS.getByte(b + offA)) * scaBgMul + scaFgA) ushr 8).toByte())
            bgS.putByte(b + offR, ((uint(bgS.getByte(b + offR)) * scaBgMul + scaFgR) ushr 8).toByte())
            bgS.putByte(b + offG, ((uint(bgS.getByte(b + offG)) * scaBgMul + scaFgG) ushr 8).toByte())
            bgS.putByte(b + offB, ((uint(bgS.getByte(b + offB)) * scaBgMul + scaFgB) ushr 8).toByte())
            f += 4
            b += if (bgIs3B) 3 else 4
            x++
        }
    }
}


// This is a special case version of the above for when both alpha is 1 and the foreground bitmap has no alpha.
private fun draftCompositeOpaqueOntoIB(fg: Bitmap, bg: Bitmap): Boolean {
    val bgPixFmtCode = bg.spec.representation.pixelFormat.code
    when (bgPixFmtCode) {
        AV_PIX_FMT_RGB24 -> opaqueOnto3B_rgb(fg, bg)
        AV_PIX_FMT_BGR24 -> opaqueOnto3B_bgr(fg, bg)
        AV_PIX_FMT_RGBA, AV_PIX_FMT_RGB0 -> opaqueOnto4B_rgba(fg, bg)
        AV_PIX_FMT_BGRA, AV_PIX_FMT_BGR0 -> opaqueOnto4B_bgra(fg, bg)
        AV_PIX_FMT_ARGB, AV_PIX_FMT_0RGB -> opaqueOnto4B_argb(fg, bg)
        AV_PIX_FMT_ABGR, AV_PIX_FMT_0BGR -> opaqueOnto4B_abgr(fg, bg)
        else -> return false
    }
    return true
}

private fun opaqueOnto3B_rgb(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, true, 0, 1, 2)
private fun opaqueOnto3B_bgr(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, true, 2, 1, 0)
private fun opaqueOnto4B_rgba(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, false, 0, 1, 2)
private fun opaqueOnto4B_bgra(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, false, 2, 1, 0)
private fun opaqueOnto4B_argb(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, false, 3, 0, 1)
private fun opaqueOnto4B_abgr(fg: Bitmap, bg: Bitmap) = opaqueOntoIB(fg, bg, false, 3, 2, 1)

@Suppress("NOTHING_TO_INLINE")
private inline fun opaqueOntoIB(fg: Bitmap, bg: Bitmap, bgIs3B: Boolean, bgC0: Int, bgC1: Int, bgC2: Int) {
    val (w, h) = fg.spec.resolution

    val fgPixFmt = fg.spec.representation.pixelFormat
    val (fgR, fgG, fgB) = fgPixFmt.components.subList(0, 3).map { fg.memorySegment(it.plane) }
    val bgS = bg.memorySegment(0)
    val fgLs = fg.linesize(0)
    val bgLs = bg.linesize(0)
    val fgBO = fgPixFmt.byteOrder

    val offR = if (bgC0 == 0) 0 else if (bgC1 == 0) 1 else if (bgC2 == 0) 2 else 3
    val offG = if (bgC0 == 1) 0 else if (bgC1 == 1) 1 else if (bgC2 == 1) 2 else 3
    val offB = if (bgC0 == 2) 0 else if (bgC1 == 2) 1 else if (bgC2 == 2) 2 else 3
    val offA = if (bgC0 == 3) 0 else if (bgC1 == 3) 1 else if (bgC2 == 3) 2 else 3

    val fVec0 = FloatVector.broadcast(F, 0f)
    val fVec255 = FloatVector.broadcast(F, 255f)
    val vec255 = if (!bgIs3B) ByteVector.broadcast(B, 255.toByte()) else null

    val vlen = B.vectorByteSize()
    val vlen4 = vlen / 4
    val maxVecX = F.loopBound(w - if (bgIs3B) vlen / 16 else 0)
    val zip3B = if (bgIs3B) B.shuffleFromOp { i -> if (i < 3 * vlen4) i / 3 + (i % 3) * vlen4 else i } else null
    val zip4B = if (!bgIs3B) B.shuffleFromOp { i -> i / 4 + (i % 4) * vlen4 } else null

    for (y in 0L..<h) {
        var f = y * fgLs
        var b = y * bgLs
        var x = 0

        while (x < maxVecX) {
            val vecFgR = vec(F, fgR, f, fgBO).mul(fVec255).min(fVec255).max(fVec0).convert(F2B, 0)
            val vecFgG = vec(F, fgG, f, fgBO).mul(fVec255).min(fVec255).max(fVec0).convert(F2B, 0)
            val vecFgB = vec(F, fgB, f, fgBO).mul(fVec255).min(fVec255).max(fVec0).convert(F2B, 0)
            val vecFg0 = if (offR == 0) vecFgR else if (offG == 0) vecFgG else if (offB == 0) vecFgB else vec255
            val vecFg1 = if (offR == 1) vecFgR else if (offG == 1) vecFgG else if (offB == 1) vecFgB else vec255
            val vecFg2 = if (offR == 2) vecFgR else if (offG == 2) vecFgG else if (offB == 2) vecFgB else vec255
            val vecFg3 = if (offR == 3) vecFgR else if (offG == 3) vecFgG else if (offB == 3) vecFgB else vec255
            // Like BitmapConverter.PlanarFloatStage.convPFTo3/4B().
            val vecFg012 = vecFg2!!.unslice(2 * vlen4, vecFg1!!.unslice(vlen4, vecFg0, 0), 0)
            (if (bgIs3B) vecFg012.rearrange(zip3B) else vecFg3!!.unslice(3 * vlen4, vecFg012, 0).rearrange(zip4B))
                .intoMemorySegment(bgS, b, nBO())
            f += vlen
            b += if (bgIs3B) 3 * vlen4 else vlen
            x += vlen4
        }

        while (x < w) {
            if (!bgIs3B)
                bgS.putByte(b + offA, 255.toByte())
            bgS.putByte(b + offR, (fgR.getFloat(f, fgBO) * 255).coerceIn(0f, 255f).toInt().toByte())
            bgS.putByte(b + offG, (fgG.getFloat(f, fgBO) * 255).coerceIn(0f, 255f).toInt().toByte())
            bgS.putByte(b + offB, (fgB.getFloat(f, fgBO) * 255).coerceIn(0f, 255f).toInt().toByte())
            f += 4
            b += if (bgIs3B) 3 else 4
            x++
        }
    }
}
