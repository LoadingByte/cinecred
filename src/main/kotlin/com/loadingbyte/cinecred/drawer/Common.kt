package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.REF_FRC
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.StyledString
import com.loadingbyte.cinecred.project.VJustify
import java.awt.Color
import java.awt.Font
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedCharacterIterator
import java.text.AttributedString


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
    fonts: Map<LetterStyle, Font>, styledStr: StyledString, x: Float, y: Float, justificationWidth: Float = Float.NaN
) {
    drawString(styledStr.toAttributedString(fonts).iterator, x, y, justificationWidth)
}


/**
 * Returns the start and end x coordinates of the drawn string.
 */
fun DeferredImage.drawJustifiedStyledString(
    fonts: Map<LetterStyle, Font>,
    styledStr: StyledString, hJustify: HJustify,
    areaX: Float, strY: Float, areaWidth: Float
): Pair<Float, Float> {
    val attrCharIter = styledStr.toAttributedString(fonts).iterator
    val strWidth = attrCharIter.getWidth()
    var outerStrX = 0f
    drawJustified(hJustify, areaX, areaWidth, strWidth) { strX ->
        drawString(attrCharIter, strX, strY)
        outerStrX = strX
    }
    return Pair(outerStrX, outerStrX + strWidth)
}


fun DeferredImage.drawJustifiedStyledString(
    fonts: Map<LetterStyle, Font>,
    styledStr: StyledString, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Float, areaWidth: Float, areaHeight: Float,
    referenceHeight: Float? = null
) {
    val attrCharIter = styledStr.toAttributedString(fonts).iterator
    val strHeight = styledStr.getHeight()
    val diff = if (referenceHeight == null) 0f else referenceHeight - strHeight
    drawJustified(
        hJustify, vJustify, areaX, areaY + diff / 2f, areaWidth, areaHeight - diff,
        attrCharIter.getWidth(), strHeight.toFloat()
    ) { strX, strY ->
        drawString(attrCharIter, strX, strY)
    }
}


fun StyledString.substring(startIdx: Int, endIdx: Int): StyledString {
    val result = mutableListOf<Pair<String, LetterStyle>>()
    var runStartIdx = 0
    for ((run, style) in this) {
        val runEndIdx = runStartIdx + run.length
        // Only look at the current run if the target region has already started.
        if (startIdx < runEndIdx) {
            var subrunStartIdx = 0
            var subrunEndIdx = run.length
            // If the target region's start index is inside the current run, cut off the unwanted start of the run.
            if (startIdx in runStartIdx..runEndIdx)
                subrunStartIdx = startIdx - runStartIdx
            // If the target region's end index is inside the current run, cut off the unwanted end of the run.
            if (endIdx in runStartIdx..runEndIdx)
                subrunEndIdx = endIdx - runStartIdx
            result.add(Pair(run.substring(subrunStartIdx, subrunEndIdx), style))
            // If this is the last run inside the target region, we are finished.
            if (endIdx <= runEndIdx)
                break
        }
        // Continue with the next run.
        runStartIdx = runEndIdx
    }
    return result
}


fun StyledString.trim(): StyledString {
    val joined = joinToString("") { it.first }
    val startIdx = joined.indexOfFirst { !it.isWhitespace() }
    val endIdx = joined.indexOfLast { !it.isWhitespace() }
    return if (startIdx > endIdx) emptyList() else substring(startIdx, endIdx)
}


fun StyledString.toAttributedString(fonts: Map<LetterStyle, Font>): AttributedString {
    val attrStr = AttributedString(joinToString("") { it.first })
    var runStartIdx = 0
    for ((run, style) in this) {
        val runEndIdx = runStartIdx + run.length
        attrStr.addAttribute(TextAttribute.FONT, fonts.getValue(style), runStartIdx, runEndIdx)
        attrStr.addAttribute(TextAttribute.FOREGROUND, style.foreground, runStartIdx, runEndIdx)
        if (style.background.alpha != 0)
            attrStr.addAttribute(TextAttribute.BACKGROUND, style.background, runStartIdx, runEndIdx)
        if (style.underline)
            attrStr.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, runStartIdx, runEndIdx)
        if (style.strikethrough)
            attrStr.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, runStartIdx, runEndIdx)
        runStartIdx = runEndIdx
    }
    return attrStr
}


fun StyledString.getWidth(fonts: Map<LetterStyle, Font>): Float =
    toAttributedString(fonts).iterator.getWidth()

fun AttributedCharacterIterator.getWidth(): Float =
    TextLayout(this, REF_FRC).advance


fun StyledString.getHeight(): Int =
    maxOf { it.second.heightPx }
