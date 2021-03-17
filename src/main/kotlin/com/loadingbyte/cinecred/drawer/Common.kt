package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.StyledString
import com.loadingbyte.cinecred.project.VJustify
import java.awt.Color


val CARD_GUIDE_COLOR = Color(150, 0, 250)
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


fun DeferredImage.drawStyledString(
    textCtx: TextContext, styledStr: StyledString, x: Float, y: Float, justificationWidth: Float = Float.NaN
) {
    drawString(styledStr.toAttributedString(textCtx).iterator, x, y, justificationWidth)
}


fun DeferredImage.drawJustifiedStyledString(
    textCtx: TextContext,
    styledStr: StyledString, hJustify: HJustify,
    areaX: Float, strY: Float, areaWidth: Float
) {
    val attrCharIter = styledStr.toAttributedString(textCtx).iterator
    val strWidth = attrCharIter.getWidth()
    drawJustified(hJustify, areaX, areaWidth, strWidth) { strX ->
        drawString(attrCharIter, strX, strY)
    }
}


fun DeferredImage.drawJustifiedStyledString(
    textCtx: TextContext,
    styledStr: StyledString, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Float, areaWidth: Float, areaHeight: Float,
    referenceHeight: Float? = null
) {
    val attrCharIter = styledStr.toAttributedString(textCtx).iterator
    val strHeight = styledStr.getHeight()
    val diff = if (referenceHeight == null) 0f else referenceHeight - strHeight
    drawJustified(
        hJustify, vJustify, areaX, areaY + diff / 2f, areaWidth, areaHeight - diff,
        attrCharIter.getWidth(), strHeight.toFloat()
    ) { strX, strY ->
        drawString(attrCharIter, strX, strY)
    }
}
