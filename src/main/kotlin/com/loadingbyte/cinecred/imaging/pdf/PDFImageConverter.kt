package com.loadingbyte.cinecred.imaging.pdf

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.ceilDiv
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.BitmapConverter
import com.loadingbyte.cinecred.imaging.ColorSpace
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage
import org.bytedeco.ffmpeg.global.avutil.*
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.min


/** Each pixel in the returned gray bitmap is either 0 or 65535 (= -1 in Java's signed representation). */
fun convert1BitPDFImageToShortMask(pdImage: PDImage): Bitmap? {
    val px = readRawShortPixelsFrom1BitImage(pdImage) ?: return null
    val rep = Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_GRAY16LE))
    val bitmap = Bitmap.allocate(Bitmap.Spec(Resolution(pdImage.width, pdImage.height), rep))
    bitmap.put(px, pdImage.width)
    return bitmap
}

fun convertPDFImageToXYZAD50(pdImage: PDImage, devCS: DeviceCS?): Pair<Bitmap, Boolean>? {
    val pdCS = pdImage.colorSpace
    val xyzaPx: FloatArray
    val promiseOpaque: Boolean
    when (pdImage) {
        is PDInlineImage -> {
            val (rawPx, _) = readRawFloatPixels(pdImage, null)
            xyzaPx = convertPDFColorToXYZAD50(rawPx ?: return null, pdCS, devCS) ?: return null
            promiseOpaque = true
        }
        is PDImageXObject -> {
            val (raw1Px, ckAlphaPx) = readRawFloatPixels(pdImage, pdImage.colorKeyMask)
            val (raw2Px, maskAlphaPx) = processMask(pdImage, raw1Px ?: return null, devCS)
            xyzaPx = convertPDFColorToXYZAD50(raw2Px, pdCS, devCS) ?: return null
            ckAlphaPx?.let { applyAlphaToXYZA(xyzaPx, it) }
            maskAlphaPx?.let { applyAlphaToXYZA(xyzaPx, it) }
            promiseOpaque = ckAlphaPx == null && maskAlphaPx == null
        }
        else -> return null
    }
    val rep = Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_RGBAF32), ColorSpace.XYZD50, Bitmap.Alpha.STRAIGHT)
    val bitmap = Bitmap.allocate(Bitmap.Spec(Resolution(pdImage.width, pdImage.height), rep))
    bitmap.put(xyzaPx, pdImage.width * 4)
    return Pair(bitmap, promiseOpaque)
}


private fun readRawFloatPixels(pdImage: PDImage, colorKey: COSArray?): Pair<FloatArray?, FloatArray?> {
    val width = pdImage.width
    val height = pdImage.height
    if (pdImage.isEmpty || width <= 0 || height <= 0)
        return Pair(null, null)

    val numComponents = pdImage.colorSpace.numberOfComponents
    val bitsPerComponent = pdImage.bitsPerComponent

    val overshoot = width * numComponents * bitsPerComponent % 8
    val padding = if (overshoot == 0) 0 else 8 - overshoot

    val decode = getDecodeArray(pdImage)
    val sampleMax = ((1 shl bitsPerComponent) - 1).toFloat()

    val ckRanges = if (colorKey != null && colorKey.size() >= numComponents * 2) colorKey.toFloatArray() else null

    val px = FloatArray(width * height * numComponents)
    val alphaPx = if (ckRanges == null) null else FloatArray(width * height)
    var i = 0
    var j = 0
    MemoryCacheImageInputStream(pdImage.createInputStream()).use { iis ->
        for (y in 0..<height) {
            for (x in 0..<width) {
                var ckApplies = ckRanges != null
                for (c in 0..<numComponents) {
                    val value = iis.readBits(bitsPerComponent).toFloat()
                    if (ckApplies && (value < ckRanges!![c * 2] || value > ckRanges[c * 2 + 1]))
                        ckApplies = false
                    val dMin = decode[c * 2]
                    val dMax = decode[c * 2 + 1]
                    px[i++] = dMin + value / sampleMax * (dMax - dMin)
                }
                if (alphaPx != null)
                    alphaPx[j++] = if (ckApplies) 0f else 1f
            }
            iis.readBits(padding)
        }
    }
    return Pair(px, alphaPx)
}

private fun readRawShortPixelsFrom1BitImage(pdImage: PDImage): ShortArray? =
    readRawPixelsFrom1BitImage(pdImage, ::ShortArray) { px, i -> px[i] = 65535.toShort() }

private fun readRawFloatPixelsFrom1BitImage(pdImage: PDImage): FloatArray? =
    readRawPixelsFrom1BitImage(pdImage, ::FloatArray) { px, i -> px[i] = 1f }

private inline fun <A> readRawPixelsFrom1BitImage(pdImage: PDImage, make: (Int) -> A, set: (A, Int) -> Unit): A? {
    val width = pdImage.width
    val height = pdImage.height
    if (pdImage.isEmpty || width <= 0 || height <= 0)
        return null

    if (pdImage.colorSpace.numberOfComponents != 1 || pdImage.bitsPerComponent != 1)
        return null

    val invert = if (getDecodeArray(pdImage).let { it[0] < it[1] }) 0 else -1

    val px = make(width * height)
    val buf = ByteArray(ceilDiv(width, 8))
    var i = 0
    pdImage.createInputStream().use { stream ->
        for (y in 0..<height) {
            val read = IOUtils.populateBuffer(stream, buf).toInt()
            var x = 0
            for (r in 0..<read) {
                var value = (buf[r].toInt() xor invert) shl 24
                repeat(min(8, width - x)) {
                    if (value < 0)
                        set(px, i)
                    value = value shl 1
                    x++
                    i++
                }
            }
            if (read != buf.size)
                break
        }
    }
    return px
}

private fun getDecodeArray(pdImage: PDImage): FloatArray {
    val cosDecode = pdImage.decode
    if (cosDecode != null)
        if (cosDecode.size() == pdImage.colorSpace.numberOfComponents * 2)
            return cosDecode.toFloatArray()
        else if (pdImage.isStencil && cosDecode.size() >= 2 && cosDecode[0] is COSNumber && cosDecode[1] is COSNumber) {
            val decode0 = (cosDecode[0] as COSNumber).floatValue()
            val decode1 = (cosDecode[1] as COSNumber).floatValue()
            if (decode0 in 0f..1f && decode1 in 0f..1f)
                return floatArrayOf(decode0, decode1)
        }
    return pdImage.colorSpace.getDefaultDecode(pdImage.bitsPerComponent)
}


private fun processMask(pdImage: PDImageXObject, rawPx: FloatArray, devCS: DeviceCS?): Pair<FloatArray, FloatArray?> {
    val pdCS = pdImage.colorSpace
    val numComponents = pdCS.numberOfComponents

    val mask: PDImage
    val isHardMask: Boolean
    val softMask = pdImage.softMask
    val hardMask = pdImage.mask
    if (softMask != null) {
        mask = softMask
        isHardMask = false
    } else if (hardMask != null) {
        mask = hardMask
        isHardMask = true
    } else
        return Pair(rawPx, null)

    var maskPx: FloatArray
    var matte: FloatArray? = null
    if (isHardMask)
        maskPx = readRawFloatPixelsFrom1BitImage(mask) ?: return Pair(rawPx, null)
    else {
        maskPx = readRawFloatPixels(mask, null).first ?: return Pair(rawPx, null)
        val rawMatte = (mask.cosObject.getItem(COSName.MATTE) as? COSArray)?.toFloatArray()
        if (rawMatte != null && rawMatte.size >= numComponents)
            matte = convertPDFColorToXYZAD50(rawMatte, pdCS, devCS)
    }

    // Rescale the mask image if it doesn't match the main image's size.
    val imgW = pdImage.width
    val imgH = pdImage.height
    val maskW = mask.width
    val maskH = mask.height
    if (maskW != imgW || maskH != imgH)
        maskPx = rescaleGrayF32(maskPx, maskW, maskH, imgW, imgH, !mask.interpolate && (maskW < imgW || maskH < imgH))

    if (isHardMask)
        for (i in maskPx.indices)
            maskPx[i] = 1f - maskPx[i]
    else if (matte != null) {
        var r = 0
        for (alpha in maskPx)
            if (alpha < 0.001f)
                repeat(numComponents) { rawPx[r++] = 0f }
            else
                for (c in 0..<numComponents) {
                    rawPx[r] = (rawPx[r] - matte[c]) / alpha + matte[c]
                    r++
                }
    }

    return Pair(rawPx, maskPx)
}

private fun rescaleGrayF32(image: FloatArray, oldW: Int, oldH: Int, newW: Int, newH: Int, nn: Boolean): FloatArray {
    val rep = Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_GRAYF32))
    Bitmap.allocate(Bitmap.Spec(Resolution(oldW, oldH), rep)).use { oldBitmap ->
        Bitmap.allocate(Bitmap.Spec(Resolution(newW, newH), rep)).use { newBitmap ->
            oldBitmap.put(image, oldW)
            BitmapConverter.convert(oldBitmap, newBitmap, nearestNeighbor = nn)
            return newBitmap.getF(newW)
        }
    }
}


private fun applyAlphaToXYZA(xyzaPx: FloatArray, alphaPx: FloatArray) {
    var i = 3
    for (alpha in alphaPx) {
        xyzaPx[i] *= alpha
        i += 4
    }
}
