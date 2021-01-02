package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.FontSpec
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.VJustify
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage


val CTRLINE_GUIDE_COLOR: Color = Color(0, 200, 200)
val BODY_ELEM_GUIDE_COLOR = Color(130, 50, 0, 255)
val BODY_WIDTH_GUIDE_COLOR = Color(120, 0, 0, 255)
val HEAD_TAIL_GUIDE_COLOR = Color(0, 100, 0, 255)


// This is a reference graphics context used to measure the size of fonts.
val REF_G2: Graphics2D = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    .createGraphics()
    .apply { setHighQuality() }
val REF_FRC: FontRenderContext = REF_G2.fontRenderContext


class RichFont(val spec: FontSpec, val awt: Font) {
    val metrics: FontMetrics = REF_G2.getFontMetrics(awt)
}


fun Graphics2D.setHighQuality() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}


fun Font.getStringWidth(str: String): Float =
    TextLayout(str, this, REF_FRC).advance


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
    val strWidth = font.awt.getStringWidth(string)
    var outerStrX = 0f
    drawJustified(hJustify, areaX, areaWidth, strWidth) { strX ->
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
        font.awt.getStringWidth(string), font.spec.heightPx.toFloat()
    ) { strX, strY ->
        drawString(font, string, strX, strY)
    }
}
