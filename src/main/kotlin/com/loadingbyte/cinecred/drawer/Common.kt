package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.FontSpec
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.VJustify
import java.awt.Color


val CTRLINE_GUIDE_COLOR: Color = Color(0, 200, 200)
val BODY_ELEM_GUIDE_COLOR = Color(130, 50, 0, 255)
val BODY_WIDTH_GUIDE_COLOR = Color(120, 0, 0, 255)
val HEAD_TAIL_GUIDE_COLOR = Color(0, 100, 0, 255)


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
    areaX: Float, areaY: Float,
    areaWidth: Float, areaHeight: Float,
    objWidth: Float, objHeight: Float,
    draw: DeferredImage.(Float, Float) -> Unit
) {
    val objY = when (vJustify) {
        VJustify.TOP -> areaY
        VJustify.MIDDLE -> areaY + (areaHeight - objHeight) / 2f
        VJustify.BOTTOM -> areaY + (areaHeight - objHeight)
    }
    drawJustified(hJustify, areaX, areaWidth, objWidth) { objX -> draw(objX, objY) }
}


/**
 * Returns the start and end x coordinates of the drawn string.
 */
fun DeferredImage.drawJustifiedString(
    font: RichFont, string: String, hJustify: HJustify,
    areaX: Float, strY: Float, areaWidth: Float
): Pair<Float, Float> {
    val strWidth = font.metrics.stringWidth(string)
    var outerStrX = 0f
    drawJustified(hJustify, areaX, areaWidth, strWidth.toFloat()) { strX ->
        drawString(font, string, strX, strY)
        outerStrX = strX
    }
    return Pair(outerStrX, outerStrX + strWidth)
}


fun DeferredImage.drawJustifiedString(
    font: RichFont, otherFont: FontSpec?, string: String,
    hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Float, areaWidth: Float, areaHeight: Float
) {
    val diff = if (otherFont == null) 0 else otherFont.heightPx - font.spec.heightPx
    drawJustified(
        hJustify, vJustify, areaX, areaY + diff / 2f, areaWidth, areaHeight - diff,
        font.metrics.stringWidth(string).toFloat(), font.spec.heightPx.toFloat()
    ) { strX, strY ->
        drawString(font, string, strX, strY)
    }
}
