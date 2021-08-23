package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.FormattedString
import com.loadingbyte.cinecred.common.Y
import com.loadingbyte.cinecred.common.Y.Companion.toY
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.VJustify
import java.awt.Color


val STAGE_GUIDE_COLOR = Color(182, 70, 250)
val AXIS_GUIDE_COLOR = Color(0, 200, 200)
val BODY_ELEM_GUIDE_COLOR = Color(130, 50, 0)
val BODY_WIDTH_GUIDE_COLOR = Color(120, 0, 0)
val HEAD_TAIL_GUIDE_COLOR = Color(0, 100, 0)


inline fun DeferredImage.drawJustified(
    hJustify: HJustify,
    areaX: Float,
    areaWidth: Float,
    objWidth: Float,
    draw: DeferredImage.(Float) -> Unit
) {
    val objX = when (hJustify) {
        HJustify.LEFT -> areaX
        HJustify.CENTER -> areaX + (areaWidth - objWidth) / 2f
        HJustify.RIGHT -> areaX + (areaWidth - objWidth)
    }
    draw(objX)
}


inline fun DeferredImage.drawJustified(
    hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Y,
    areaWidth: Float, areaHeight: Y,
    objWidth: Float, objHeight: Y,
    draw: DeferredImage.(Float, Y) -> Unit
) {
    val objY = when (vJustify) {
        VJustify.TOP -> areaY
        VJustify.MIDDLE -> areaY + (areaHeight - objHeight) / 2f
        VJustify.BOTTOM -> areaY + (areaHeight - objHeight)
    }
    drawJustified(hJustify, areaX, areaWidth, objWidth) { objX -> draw(objX, objY) }
}


fun DeferredImage.drawJustifiedString(
    fmtStr: FormattedString, hJustify: HJustify,
    areaX: Float, strY: Y, areaWidth: Float
) {
    drawJustified(hJustify, areaX, areaWidth, fmtStr.width) { strX ->
        drawString(fmtStr, strX, strY)
    }
}


fun DeferredImage.drawJustifiedString(
    fmtStr: FormattedString, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Y, areaWidth: Float, areaHeight: Y,
    referenceHeight: Y? = null
) {
    val strHeight = fmtStr.height.toY()
    val diff = if (referenceHeight == null) 0f.toY() else referenceHeight - strHeight
    drawJustified(
        hJustify, vJustify, areaX, areaY + diff / 2f, areaWidth, areaHeight - diff,
        fmtStr.width, strHeight
    ) { strX, strY ->
        drawString(fmtStr, strX, strY)
    }
}
