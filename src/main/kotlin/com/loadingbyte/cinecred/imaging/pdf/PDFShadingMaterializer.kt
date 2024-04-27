package com.loadingbyte.cinecred.imaging.pdf

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.ColorSpace
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBoolean
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdmodel.graphics.shading.*
import org.bytedeco.ffmpeg.global.avutil.AVCHROMA_LOC_UNSPECIFIED
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBAF32
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.geom.NoninvertibleTransformException
import java.io.IOException
import kotlin.math.*


/**
 * Converts a PDF shading to a bitmap with float32 XYZA (D50) colors.
 *
 * @param transform A transform from the shading's coordinate system to the canvas' coordinate system.
 * @param bounds The region of the canvas covered by the returned bitmap.
 */
fun materializePDFShadingAsXYZAD50(
    shading: PDShading, bounds: Rectangle, transform: AffineTransform, devCS: DeviceCS?
): Pair<Bitmap, Boolean>? {
    val (colorPx, alphaPx) = try {
        when (shading) {
            is PDShadingType1 -> convType1(shading, bounds, transform)
            // 2 and 3 are switched around because 3 inherits from 2.
            is PDShadingType3 -> convType3(shading, bounds, transform)
            is PDShadingType2 -> convType2(shading, bounds, transform)
            // 6 and 7 are missing because they inherit from 4.
            is PDShadingType4, is PDShadingType5 -> convType4567(shading, bounds, transform)
            else -> null
        }
    } catch (_: IOException) {
        // PDFunction calls might trigger IOExceptions, in which case we'll paint nothing at all.
        null
    } ?: return null

    val xyzaPx = convertPDFColorToXYZAD50(colorPx, shading.colorSpace, devCS) ?: return null
    if (alphaPx != null) {
        var i = 3
        for (alpha in alphaPx) {
            xyzaPx[i] = alpha
            i += 4
        }
    }

    val rep = Bitmap.Representation(
        Bitmap.PixelFormat.of(AV_PIX_FMT_RGBAF32), Bitmap.Range.FULL, ColorSpace.XYZD50,
        yuvCoefficients = null, AVCHROMA_LOC_UNSPECIFIED, Bitmap.Alpha.PREMULTIPLIED
    )
    val bitmap = Bitmap.allocate(Bitmap.Spec(Resolution(bounds.width, bounds.height), rep))
    bitmap.put(xyzaPx, bounds.width * 4)
    return Pair(bitmap, alphaPx == null)
}


/** Arbitrary function-based shading */
private fun convType1(shading: PDShadingType1, bounds: Rectangle, tr: AffineTransform): Pair<FloatArray, FloatArray?>? {
    val (domX0, domX1, domY0, domY1) = shading.domain?.toDoubleArray() ?: doubleArrayOf(0.0, 1.0, 0.0, 1.0)
    val combinedTransform = shading.matrix.createAffineTransform().apply { preConcatenate(tr) }
    return sampleRaster(shading, bounds, combinedTransform) { x, y ->
        if (x < domX0 || x > domX1 || y < domY0 || y > domY1)
            return@sampleRaster null
        shading.evalFunction(floatArrayOf(x.toFloat(), y.toFloat()))
    }
}

/** Axial shading */
private fun convType2(shading: PDShadingType2, bounds: Rectangle, tr: AffineTransform): Pair<FloatArray, FloatArray?>? {
    val (x0, y0, x1, y1) = shading.coords.toDoubleArray()
    val (dom0, dom1) = shading.domain?.toDoubleArray() ?: doubleArrayOf(0.0, 1.0)
    val (ext0, ext1) = shading.extend?.toBooleanArray() ?: booleanArrayOf(false, false)
    val x1x0 = x1 - x0
    val y1y0 = y1 - y0
    val d1d0 = dom1 - dom0
    val denom = x1x0 * x1x0 + y1y0 * y1y0
    return sampleRaster(shading, bounds, tr) { x, y ->
        // The PDF spec specifies that nothing should be painted if x0=x1 and y0=y1.
        if (denom == 0.0)
            return@sampleRaster null
        var s = (x1x0 * (x - x0) + y1y0 * (y - y0)) / denom
        if (s < 0.0)
            if (ext0) s = 0.0 else return@sampleRaster null
        else if (s > 1.0)
            if (ext1) s = 1.0 else return@sampleRaster null
        shading.evalFunction((dom0 + d1d0 * s).toFloat())
    }
}

/** Radial shading */
private fun convType3(shading: PDShadingType3, bounds: Rectangle, tr: AffineTransform): Pair<FloatArray, FloatArray?>? {
    val (x0, y0, r0, x1, y1, r1) = shading.coords.toDoubleArray()
    val (dom0, dom1) = shading.domain?.toDoubleArray() ?: doubleArrayOf(0.0, 1.0)
    val (ext0, ext1) = shading.extend?.toBooleanArray() ?: booleanArrayOf(false, false)
    val x1x0 = x1 - x0
    val y1y0 = y1 - y0
    val r1r0 = r1 - r0
    val r0pow2 = r0 * r0
    val d1d0 = dom1 - dom0
    val denom = x1x0 * x1x0 + y1y0 * y1y0 - r1r0 * r1r0
    return sampleRaster(shading, bounds, tr) { x, y ->
        val p = -(x - x0) * x1x0 - (y - y0) * y1y0 - r0 * r1r0
        val q = (x - x0).pow(2.0) + (y - y0).pow(2.0) - r0pow2
        val root = sqrt(p * p - denom * q)
        var root0 = (-p + root) / denom
        var root1 = (-p - root) / denom
        if (denom > 0)
            root0 = root1.also { root1 = root0 }
        if (root0.isNaN() && root1.isNaN())
            return@sampleRaster null
        val root0InRange = root0 in 0.0..1.0
        val root1InRange = root1 in 0.0..1.0
        var s = when {
            root0InRange && root1InRange -> max(root0, root1)
            root0InRange -> root0
            root1InRange -> root1
            ext0 && ext1 -> max(root0, root1)
            ext0 -> root0
            ext1 -> root1
            else -> return@sampleRaster null
        }
        if (s < 0.0)
            if (ext0 && r0 > 0.0) s = 0.0 else return@sampleRaster null
        else if (s > 1.0)
            if (ext1 && r1 > 0.0) s = 1.0 else return@sampleRaster null
        shading.evalFunction((dom0 + d1d0 * s).toFloat())
    }
}

/** Gouraud-shaded triangle mesh / Coons patch mesh / Tensor-product patch mesh */
private fun convType4567(shading: PDShading, bounds: Rectangle, tr: AffineTransform): Pair<FloatArray, FloatArray?> {
    val background = shading.background?.toFloatArray()
    val nc = shading.colorSpace.numberOfComponents
    val bx = bounds.x
    val by = bounds.y
    val bw = bounds.width
    val bh = bounds.height
    val colorPx = FloatArray(bh * bw * nc)
    var alphaPx: FloatArray? = null
    if (background == null)
        alphaPx = FloatArray(bh * bw)
    else
        for (i in 0..<bh * bw)
            System.arraycopy(background, 0, colorPx, i * nc, nc)
    val shadedTriangles = when (shading) {
        is PDShadingType6 -> shading.collectTrianglesOfPatches(tr, 12)
        is PDShadingType7 -> shading.collectTrianglesOfPatches(tr, 16)
        else -> shading.collectTriangles(tr)
    }
    for (tri in shadedTriangles) {
        val degree = shadedTriangleGetDeg(tri)
        if (degree == 2) {
            val line = shadedTriangleGetLine(tri)
            for (p in lineGetLinePoints(line))
                tryPutPx(bw, bh, nc, colorPx, alphaPx, shading.tryEvalFn(lineCalcColor(line, p)), p.x - bx, p.y - by)
        } else {
            val corner = shadedTriangleGetCorner(tri)
            val color = shadedTriangleGetColor(tri)
            val c0x = corner[0].x.roundToInt()
            val c0y = corner[0].y.roundToInt()
            val c1x = corner[1].x.roundToInt()
            val c1y = corner[1].y.roundToInt()
            val c2x = corner[2].x.roundToInt()
            val c2y = corner[2].y.roundToInt()
            val dx0 = max(minOf(c0x, c1x, c2x) - bx, 0)
            val dy0 = max(minOf(c0y, c1y, c2y) - by, 0)
            val dx1 = min(maxOf(c0x, c1x, c2x) - bx, bw - 1)
            val dy1 = min(maxOf(c0y, c1y, c2y) - by, bh - 1)
            for (dx in dx0..dx1)
                for (dy in dy0..dy1) {
                    val p = Point(bx + dx, by + dy)
                    if (shadedTriangleContains(tri, p))
                        putPx(bw, nc, colorPx, alphaPx, shading.tryEvalFn(shadedTriangleCalcColor(tri, p)), dx, dy)
                }
            // "Fatten" the triangle by drawing the borders with Bresenham's line algorithm.
            // Inspiration: Raph Levien in http://bugs.ghostscript.com/show_bug.cgi?id=219588
            val c0 = Point(c0x, c0y)
            val c1 = Point(c1x, c1y)
            val c2 = Point(c2x, c2y)
            val l0 = newLine(c0, c1, color[0], color[1])
            val l1 = newLine(c1, c2, color[1], color[2])
            val l2 = newLine(c2, c0, color[2], color[0])
            for (p in lineGetLinePoints(l0))
                tryPutPx(bw, bh, nc, colorPx, alphaPx, shading.tryEvalFn(lineCalcColor(l0, p)), p.x - bx, p.y - by)
            for (p in lineGetLinePoints(l1))
                tryPutPx(bw, bh, nc, colorPx, alphaPx, shading.tryEvalFn(lineCalcColor(l1, p)), p.x - bx, p.y - by)
            for (p in lineGetLinePoints(l2))
                tryPutPx(bw, bh, nc, colorPx, alphaPx, shading.tryEvalFn(lineCalcColor(l2, p)), p.x - bx, p.y - by)
        }
    }
    return Pair(colorPx, alphaPx)
}


private inline fun sampleRaster(
    shading: PDShading,
    bounds: Rectangle,
    transform: AffineTransform,
    colorAt: (Double, Double) -> FloatArray?
): Pair<FloatArray, FloatArray?>? {
    val background = shading.background?.toFloatArray()
    val nc = shading.colorSpace.numberOfComponents
    val bx = bounds.x
    val by = bounds.y
    val bw = bounds.width
    val bh = bounds.height
    val inverseTransform = try {
        transform.createInverse()
    } catch (_: NoninvertibleTransformException) {
        return null
    }
    val colorPx = FloatArray(bh * bw * nc)
    val alphaPx = if (background != null) null else FloatArray(bh * bw)
    val point = DoubleArray(2)
    for (dy in 0..<bh) {
        for (dx in 0..<bw) {
            point[0] = (bx + dx).toDouble()
            point[1] = (by + dy).toDouble()
            inverseTransform.transform(point, 0, point, 0, 1)
            putPx(bw, nc, colorPx, alphaPx, colorAt(point[0], point[1]) ?: background ?: continue, dx, dy)
        }
    }
    return Pair(colorPx, alphaPx)
}

private fun putPx(bw: Int, nc: Int, colorPx: FloatArray, alphaPx: FloatArray?, color: FloatArray, dx: Int, dy: Int) {
    val i = dy * bw + dx
    System.arraycopy(color, 0, colorPx, i * nc, nc)
    alphaPx?.set(i, 1f)
}

private fun tryPutPx(
    bw: Int, bh: Int, nc: Int, colorPx: FloatArray, alphaPx: FloatArray?, color: FloatArray, dx: Int, dy: Int
) {
    if (dx in 0..<bw && dy in 0..<bh) {
        val i = dy * bw + dx
        System.arraycopy(color, 0, colorPx, i * nc, nc)
        alphaPx?.set(i, 1f)
    }
}

private fun PDShading.tryEvalFn(input: FloatArray): FloatArray =
    if (function != null) evalFunction(input) else input

private fun COSArray.toDoubleArray(): DoubleArray {
    val arr = DoubleArray(size())
    for (i in arr.indices)
        getObject(i).let { if (it is COSNumber) arr[i] = it.floatValue().toDouble() }
    return arr
}

private fun COSArray.toBooleanArray(): BooleanArray {
    val arr = BooleanArray(size())
    for (i in arr.indices)
        getObject(i).let { if (it is COSBoolean) arr[i] = it.value }
    return arr
}

private operator fun DoubleArray.component6() = get(5)
