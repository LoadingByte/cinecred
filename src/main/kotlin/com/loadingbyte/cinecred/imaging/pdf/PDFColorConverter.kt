package com.loadingbyte.cinecred.imaging.pdf

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.ICCProfile
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.graphics.color.*
import java.io.IOException
import java.util.*
import kotlin.math.pow


/**
 * Definitions of the device color spaces; null means sGray, sRGB, and PDFBox's default CMYK color space.
 *
 * When something is wrong with one of these device color spaces, the conversion functions fall back to their null
 * equivalents instead of completely failing.
 */
class DeviceCS(val deviceGray: ICCProfile?, val deviceRGB: ICCProfile?, val deviceCMYK: ICCProfile?)


/* **************************************
   ********** COLOR CONVERSION **********
   ************************************** */


fun convertPDFColorToXYZAD50(pdColor: PDColor, devCS: DeviceCS?): FloatArray? =
    convertPDFColorToXYZAD50(pdColor.components, pdColor.colorSpace, devCS)

/** Converts an array of PDF pixel colors to an array of XYZA (D50) pixel colors (A is always set to 1). */
fun convertPDFColorToXYZAD50(inPx: FloatArray, pdCS: PDColorSpace, devCS: DeviceCS?): FloatArray? {
    val outPx = FloatArray(inPx.size / pdCS.numberOfComponents * 4)
    return if (convertColor(inPx, outPx, pdCS, devCS)) outPx else null
}


private fun convertColor(inPx: FloatArray, outPx: FloatArray, pdCS: PDColorSpace, devCS: DeviceCS?): Boolean =
    when (pdCS) {
        is PDDeviceGray -> convertColorDeviceGray(inPx, outPx, devCS)
        is PDDeviceRGB -> convertColorDeviceRGB(inPx, outPx, devCS)
        is PDDeviceCMYK -> convertColorDeviceCMYK(inPx, outPx, devCS)
        is PDCalGray -> convertColorCalGray(inPx, outPx, pdCS)
        is PDCalRGB -> convertColorCalRGB(inPx, outPx, pdCS)
        is PDLab -> convertColorLab(inPx, outPx, pdCS)
        is PDICCBased -> convertColorICCBased(inPx, outPx, pdCS, devCS)
        is PDIndexed -> convertColorIndexed(inPx, outPx, pdCS, devCS)
        is PDSeparation -> convertColorSeparation(inPx, outPx, pdCS, devCS)
        is PDDeviceN -> convertColorDeviceN(inPx, outPx, pdCS, devCS)
        else -> false
    }

private fun convertColorICCProfile(inPx: FloatArray, outPx: FloatArray, iccProfile: ICCProfile): Boolean {
    // Snap nearly-sRGB profiles to sRGB, and exploit our fast code path in convertColorDeviceRGB().
    if (iccProfile.similarColorSpace == ColorSpace.SRGB)
        return convertColorDeviceRGB(inPx, outPx, null)
    return try {
        iccProfile.convertICCArrayWithoutAlphaToXYZD50ArrayWithAlpha(inPx, outPx)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun convertColorDeviceGray(inPx: FloatArray, outPx: FloatArray, devCS: DeviceCS?): Boolean {
    devCS?.deviceGray?.let { if (convertColorICCProfile(inPx, outPx, it)) return true }
    var i = 0
    var o = 0
    while (i < inPx.size) {
        val g = sRGB_EOTF(inPx[i++].coerceIn(0f, 1f))
        // Output grays along the connection color space's D50 white point.
        outPx[o++] = g * 0.96422f
        outPx[o++] = g
        outPx[o++] = g * 0.82521f
        outPx[o++] = 1f
    }
    return true
}

private fun convertColorDeviceRGB(inPx: FloatArray, outPx: FloatArray, devCS: DeviceCS?): Boolean {
    devCS?.deviceRGB?.let { if (convertColorICCProfile(inPx, outPx, it)) return true }
    // Fire up the more heavy, but well optimized ICC profile machinery only for larger pixel buffers, and manually
    // apply sRGB conversion for small buffers to keep the overhead down when converting only a single or a few colors.
    // We've actually measured that this switch improves performance.
    if (outPx.size > 16 * 4)
        ICCProfile.of(ColorSpace.SRGB).convertICCArrayWithoutAlphaToXYZD50ArrayWithAlpha(inPx, outPx)
    else {
        var i = 0
        var o = 0
        while (i < inPx.size) {
            outPx[o++] = sRGB_EOTF(inPx[i++].coerceIn(0f, 1f))
            outPx[o++] = sRGB_EOTF(inPx[i++].coerceIn(0f, 1f))
            outPx[o++] = sRGB_EOTF(inPx[i++].coerceIn(0f, 1f))
            outPx[o++] = 1f
        }
        ColorSpace.Primaries.BT709.toXYZD50(outPx, alpha = true)
    }
    return true
}

private fun convertColorDeviceCMYK(inPx: FloatArray, outPx: FloatArray, devCS: DeviceCS?): Boolean {
    devCS?.deviceCMYK?.let { if (convertColorICCProfile(inPx, outPx, it)) return true }
    return convertColorICCProfile(inPx, outPx, DEVICE_CMYK_ICC_PROFILE)
}

private fun convertColorCalGray(inPx: FloatArray, outPx: FloatArray, pdCS: PDCalGray): Boolean {
    val g = getCalGrayInfo(pdCS).g
    var i = 0
    var o = 0
    while (i < inPx.size) {
        val aG = inPx[i++].coerceIn(0f, 1f).pow(g)
        // From what the PDF spec hints at and other implementations do, it seems like the calibrated gray space
        // is about producing grays along the configured white point, BUT that white point is later mapped to
        // the white point of the output color space. For us, this would mean placing the grays along the configured
        // white point, and then applying chromatic adaption to the connection color space's D50 white point. As a
        // shortcut, we directly to place the grays along D50, which is equivalent (and we confirmed that numerically).
        outPx[o++] = aG * 0.96422f
        outPx[o++] = aG
        outPx[o++] = aG * 0.82521f
        outPx[o++] = 1f
    }
    return true
}

private fun convertColorCalRGB(inPx: FloatArray, outPx: FloatArray, pdCS: PDCalRGB): Boolean {
    val info = getCalRGBInfo(pdCS)
    val gR = info.gR
    val gG = info.gG
    val gB = info.gB
    val toXYZD50 = (info.primaries ?: return false).toXYZD50
    var i = 0
    var o = 0
    while (i < inPx.size) {
        outPx[o++] = inPx[i++].coerceIn(0f, 1f).pow(gR)
        outPx[o++] = inPx[i++].coerceIn(0f, 1f).pow(gG)
        outPx[o++] = inPx[i++].coerceIn(0f, 1f).pow(gB)
        outPx[o++] = 1f
    }
    toXYZD50(outPx, alpha = true)
    return true
}

private fun convertColorLab(inPx: FloatArray, outPx: FloatArray, pdCS: PDLab): Boolean {
    val info = getLabInfo(pdCS)
    val aMin = info.aMin
    val aMax = info.aMax
    val bMin = info.bMin
    val bMax = info.bMax
    val DwToD50 = info.DwToD50 ?: return false
    var i = 0
    var o = 0
    while (i < inPx.size) {
        val m = (inPx[i + 0].coerceIn(0f, 100f) + 16f) / 116f
        val l = m + inPx[i + 1].coerceIn(aMin, aMax) / 500f
        val n = m - inPx[i + 2].coerceIn(bMin, bMax) / 200f
        outPx[o++] = if (l >= 6f / 29f) l * l * l else (108f / 841f) * (l - 4f / 29f)
        outPx[o++] = if (m >= 6f / 29f) m * m * m else (108f / 841f) * (m - 4f / 29f)
        outPx[o++] = if (n >= 6f / 29f) n * n * n else (108f / 841f) * (n - 4f / 29f)
        outPx[o++] = 1f
        i += 3
    }
    DwToD50(outPx, alpha = true)
    return true
}

private fun convertColorICCBased(inPx: FloatArray, outPx: FloatArray, pdCS: PDICCBased, devCS: DeviceCS?): Boolean {
    val awtCS = pdCS.getAWTColorSpace()
    return if (awtCS != null)
        convertColorICCProfile(inPx, outPx, ICCProfile.of(awtCS.profile))
    else
        convertColor(inPx, outPx, pdCS.getActivatedAlternateColorSpace()!!, devCS)
}

private fun convertColorIndexed(inPx: FloatArray, outPx: FloatArray, pdCS: PDIndexed, devCS: DeviceCS?): Boolean {
    val table = getIndexedTable(pdCS, devCS) ?: return false
    var i = 0
    var o = 0
    while (i < inPx.size) {
        val idx = (Math.round(inPx[i++]) * 4).coerceIn(0, table.size - 4)
        System.arraycopy(table, idx, outPx, o, 4)
        o += 4
    }
    return true
}

private fun convertColorSeparation(inPx: FloatArray, outPx: FloatArray, pdCS: PDSeparation, devCS: DeviceCS?): Boolean {
    if (pdCS.colorantName == "None")
        return false
    else {
        val altPdCS = pdCS.alternateColorSpace
        val altPixels = evalMultiple(pdCS.getTintTransform(), 1, altPdCS.numberOfComponents, inPx) ?: return false
        return convertColor(altPixels, outPx, altPdCS, devCS)
    }
}

private fun convertColorDeviceN(inPx: FloatArray, outPx: FloatArray, pdCS: PDDeviceN, devCS: DeviceCS?): Boolean {
    if (pdCS.attributes == null) {
        val altPdCS = pdCS.alternateColorSpace
        val altPixels =
            evalMultiple(pdCS.tintTransform, pdCS.numberOfComponents, altPdCS.numberOfComponents, inPx) ?: return false
        return convertColor(altPixels, outPx, altPdCS, devCS)
    } else {
        val inPxComps = pdCS.numberOfComponents
        val numPx = inPx.size / inPxComps
        val colorantToComponent = pdCS.getColorantToComponent()
        val processColorSpace = pdCS.getProcessColorSpace()
        val spotColorSpaces = pdCS.getSpotColorSpaces()
        outPx.fill(1f)
        val colorantOutPx = FloatArray(numPx * 4)
        for ((colorant, component) in colorantToComponent.withIndex()) {
            val colorantCS: PDColorSpace
            var ci: Int
            if (component >= 0) {
                colorantCS = processColorSpace
                ci = component
            } else {
                colorantCS = spotColorSpaces[colorant] ?: return false
                ci = 0
            }
            val colorantInPxComps = colorantCS.numberOfComponents
            val colorantInPx = FloatArray(numPx * colorantInPxComps)
            var i = colorant
            while (i < inPx.size) {
                colorantInPx[ci] = inPx[i]
                i += inPxComps
                ci += colorantInPxComps
            }
            if (!convertColor(colorantInPx, colorantOutPx, colorantCS, devCS))
                return false
            // Section 10.8.3 in the PDF specification describes how a DeviceN color space should be simulated on a
            // monitor: create a bitmap in linear XYZ for each colorant, then multiply those bitmaps together.
            // Notice that this loop also multiplies the alphas, but as those are always 1, it doesn't matter.
            // And by keeping this loop as simple as possible, we increase the chances of it being vectorized.
            for (o in colorantOutPx.indices)
                outPx[o] *= colorantOutPx[o]
        }
        return true
    }
}


/* ********************************************
   ********** COLOR SPACE CONVERSION **********
   ******************************************** */


fun convertPDFColorSpaceToColorSpace(pdCS: PDColorSpace, devCS: DeviceCS?): ColorSpace? =
    when (pdCS) {
        is PDDeviceGray -> convertSpaceDeviceGray(devCS)
        is PDDeviceRGB -> convertSpaceDeviceRGB(devCS)
        is PDCalGray -> convertSpaceCalGray(pdCS)
        is PDCalRGB -> convertSpaceCalRGB(pdCS)
        is PDICCBased -> convertSpaceICCBased(pdCS, devCS)
        else -> null
    }

private fun convertSpaceDeviceGray(devCS: DeviceCS?): ColorSpace =
    devCS?.deviceGray?.similarColorSpace ?: ColorSpace.of(ColorSpace.Primaries.XYZD65, ColorSpace.Transfer.SRGB)

private fun convertSpaceDeviceRGB(devCS: DeviceCS?): ColorSpace =
    devCS?.deviceRGB?.similarColorSpace ?: ColorSpace.SRGB

private fun convertSpaceCalGray(pdCS: PDCalGray): ColorSpace? {
    val info = getCalGrayInfo(pdCS)
    return ColorSpace.of(info.DwToD50 ?: return null, ColorSpace.Transfer.of(ColorSpace.Transfer.Curve(info.g)))
}

private fun convertSpaceCalRGB(pdCS: PDCalRGB): ColorSpace? {
    val info = getCalRGBInfo(pdCS)
    // Choose the median gamma.
    val g = floatArrayOf(info.gR, info.gG, info.gB).apply { sort() }[1]
    return ColorSpace.of(info.primaries ?: return null, ColorSpace.Transfer.of(ColorSpace.Transfer.Curve(g)))
}

private fun convertSpaceICCBased(pdCS: PDICCBased, devCS: DeviceCS?): ColorSpace? {
    val awtCS = pdCS.getAWTColorSpace()
    return if (awtCS != null)
        ICCProfile.of(awtCS.profile).similarColorSpace
    else
        convertPDFColorSpaceToColorSpace(pdCS.getActivatedAlternateColorSpace()!!, devCS)
}


/* *******************************
   ********** UTILITIES **********
   ******************************* */


private fun evalMultiple(func: PDFunction, inComps: Int, outComps: Int, ins: FloatArray): FloatArray? {
    val outs = FloatArray(ins.size / inComps * outComps)
    val tmp = FloatArray(inComps)
    var i = 0
    var o = 0
    while (i < ins.size) {
        System.arraycopy(ins, i, tmp, 0, inComps)
        val res = try {
            func.eval(tmp)
        } catch (_: IOException) {
            return null
        }
        System.arraycopy(res, 0, outs, o, outComps)
        i += inComps
        o += outComps
    }
    return outs
}


// Pretty much copied from zimg.
private fun sRGB_EOTF(x: Float) =
    if (x < 12.92f * 0.0030412825f)
        x / 12.92f
    else
        ((x + (1.0550107f - 1.0f)) / 1.0550107f).pow(2.4f)


private fun computeDwToD50(whitePoint: PDTristimulus): ColorSpace.Primaries? {
    // Note: The PDF specification mandates wY to always be 1.
    val wX = whitePoint.x
    val wZ = whitePoint.z
    // Convert from XYZ for xy.
    val wy = 1f / (wX + 1f + wZ)
    val wx = wX * wy
    return try {
        ColorSpace.Primaries.of(wx, wy)
    } catch (_: IllegalArgumentException) {
        null
    }
}


/* *********************************************************************************
   ********** CACHE EXPENSIVE-TO-GET PROPS OF PDCS + OTHER USEFUL OBJECTS **********
   ********************************************************************************* */


private val DEVICE_CMYK_ICC_PROFILE =
    ICCProfile.of(
        object : PDDeviceCMYK() {
            public override fun getICCProfile() = super.getICCProfile()
        }.iccProfile
    )


private fun getCalGrayInfo(pdCS: PDCalGray): CalGrayInfo = calGrayInfoCache.computeIfAbsent(pdCS) {
    CalGrayInfo(pdCS.gamma, computeDwToD50(pdCS.whitepoint))
}

private fun getCalRGBInfo(pdCS: PDCalRGB): CalRGBInfo = calRGBInfoCache.computeIfAbsent(pdCS) {
    val gamma = pdCS.gamma
    val m = pdCS.matrix
    val mT = ColorSpace.Primaries.Matrix(floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]))
    val pri = computeDwToD50(pdCS.whitepoint)?.let { ColorSpace.Primaries.of(it.toXYZD50 * mT) }
    CalRGBInfo(gamma.r, gamma.g, gamma.b, pri)
}

private fun getLabInfo(pdCS: PDLab): LabInfo = labInfoCache.computeIfAbsent(pdCS) {
    val aRange = pdCS.aRange
    val bRange = pdCS.bRange
    LabInfo(aRange.min, aRange.max, bRange.min, bRange.max, computeDwToD50(pdCS.whitepoint)?.toXYZD50)
}

private fun getIndexedTable(pdCS: PDIndexed, devCS: DeviceCS?): FloatArray? = indexedTableCache.computeIfAbsent(pdCS) {
    val baseCS = pdCS.baseColorSpace
    val colorTable = pdCS.getColorTable()
    val inPx = FloatArray(colorTable.size * baseCS.numberOfComponents)
    var i = 0
    for (color in colorTable)
        for (comp in color)
            inPx[i++] = comp
    val outPx = FloatArray(colorTable.size * 4)
    if (convertColor(inPx, outPx, baseCS, devCS)) outPx else null
}

private class CalGrayInfo(val g: Float, val DwToD50: ColorSpace.Primaries?)
private class CalRGBInfo(val gR: Float, val gG: Float, val gB: Float, val primaries: ColorSpace.Primaries?)
private class LabInfo(
    val aMin: Float, val aMax: Float, val bMin: Float, val bMax: Float, val DwToD50: ColorSpace.Primaries.Matrix?
)

private val calGrayInfoCache = Collections.synchronizedMap(WeakHashMap<PDCalGray, CalGrayInfo>())
private val calRGBInfoCache = Collections.synchronizedMap(WeakHashMap<PDCalRGB, CalRGBInfo>())
private val labInfoCache = Collections.synchronizedMap(WeakHashMap<PDLab, LabInfo>())
private val indexedTableCache = Collections.synchronizedMap(WeakHashMap<PDIndexed, FloatArray>())
