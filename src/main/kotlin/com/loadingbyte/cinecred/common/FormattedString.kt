package com.loadingbyte.cinecred.common

import java.awt.Color
import java.awt.Font
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.text.BreakIterator
import java.util.*


class FormattedString private constructor(
    val string: String,
    private val attrStr: AttributedString,
    private val startLim: Int,
    private val endLim: Int
) {

    constructor(string: String) : this(string, AttributedString(string), 0, string.length)

    val width: Float
        get() = fontFgBgTextLayout.advance

    val height: Float by lazy {
        var height = 0f
        forEachRunOf(CustomAttribute.HEIGHT_HINT) { runHeight, _, _ ->
            if (runHeight != null && runHeight as Float > height)
                height = runHeight
        }
        height
    }

    /**
     * A [TextLayout] generated using the set font, foreground and background color attributes. Neither any other
     * attributes nor the non-standard attributes (like background widening) are considered by the TextLayout.
     */
    val fontFgBgTextLayout: TextLayout by lazy {
        val attrs = arrayOf(TextAttribute.FONT, TextAttribute.FOREGROUND, TextAttribute.BACKGROUND)
        TextLayout(attrStr.getIterator(attrs, startLim, endLim), REF_FRC)
    }

    fun sub(startIdx: Int, endIdx: Int): FormattedString =
        FormattedString(string.substring(startIdx, endIdx), attrStr, startIdx, endIdx)

    fun trim(): FormattedString {
        val startIdx = string.indexOfFirst { !it.isWhitespace() }
        val endIdx = string.indexOfLast { !it.isWhitespace() } + 1
        return if (startIdx >= endIdx) FormattedString("") else sub(startIdx, endIdx)
    }

    fun getForeground(atIdx: Int): Color? = getAttribute(TextAttribute.FOREGROUND, atIdx) as Color?
    fun getUnderline(atIdx: Int): Boolean = getAttribute(TextAttribute.UNDERLINE, atIdx) == TextAttribute.UNDERLINE_ON
    fun getStrikethrough(atIdx: Int): Boolean = getAttribute(TextAttribute.STRIKETHROUGH, atIdx) == true

    fun setFont(font: Font, heightHint: Float, startIdx: Int, endIdx: Int) {
        setAttribute(TextAttribute.FONT, font, startIdx, endIdx)
        setAttribute(CustomAttribute.HEIGHT_HINT, heightHint, startIdx, endIdx)
    }

    fun setForeground(foreground: Color, startIdx: Int, endIdx: Int) {
        setAttribute(TextAttribute.FOREGROUND, foreground, startIdx, endIdx)
    }

    fun setBackground(background: Background, startIdx: Int, endIdx: Int) {
        setAttribute(CustomAttribute.CUSTOM_BACKGROUND, background, startIdx, endIdx)
        // Needed because the TextLayout created by "fontFgBgTextLayout" is promised to include background color.
        setAttribute(TextAttribute.BACKGROUND, background.color, startIdx, endIdx)
    }

    fun setUnderline(underline: Boolean, startIdx: Int, endIdx: Int) {
        setAttribute(TextAttribute.UNDERLINE, if (underline) TextAttribute.UNDERLINE_ON else null, startIdx, endIdx)
    }

    fun setStrikethrough(strikethrough: Boolean, startIdx: Int, endIdx: Int) {
        setAttribute(TextAttribute.STRIKETHROUGH, strikethrough, startIdx, endIdx)
    }

    private fun getAttribute(attr: AttributedCharacterIterator.Attribute, atIdx: Int): Any? {
        val attrCharIter = attrStr.getIterator(arrayOf(attr), startLim + atIdx, startLim + atIdx + 1)
        return attrCharIter.getAttribute(attr)
    }

    private fun setAttribute(attr: AttributedCharacterIterator.Attribute, value: Any?, startIdx: Int, endIdx: Int) {
        attrStr.addAttribute(attr, value, startLim + startIdx, startLim + endIdx)
    }

    fun forEachFontRun(action: (font: Font?, runStartIdx: Int, runEndIdx: Int) -> Unit) {
        forEachRunOf(TextAttribute.FONT) { v, s, e -> action(v as Font?, s, e) }
    }

    fun forEachForegroundRun(action: (foreground: Color?, runStartIdx: Int, runEndIdx: Int) -> Unit) {
        forEachRunOf(TextAttribute.FOREGROUND) { v, s, e -> action(v as Color?, s, e) }
    }

    fun forEachBackgroundRun(action: (background: Background?, runStartIdx: Int, runEndIdx: Int) -> Unit) {
        forEachRunOf(CustomAttribute.CUSTOM_BACKGROUND) { v, s, e -> action(v as Background?, s, e) }
    }

    private inline fun forEachRunOf(attr: AttributedCharacterIterator.Attribute?, action: (Any?, Int, Int) -> Unit) {
        val attrCharIter = attrStr.getIterator(arrayOf(attr), startLim, endLim)
        while (attrCharIter.index != attrCharIter.endIndex) {
            val runEndIdx = attrCharIter.getRunLimit(attr)
            action(attrCharIter.getAttribute(attr), attrCharIter.index, runEndIdx)
            attrCharIter.index = runEndIdx
        }
    }

    fun breakLines(wrappingWidth: Float, locale: Locale): List<Int> {
        // Employ a LineBreakMeasurer to find the best spots to insert a newline.
        val breaks = mutableListOf(0)
        val attrCharIter = attrStr.getIterator(arrayOf(TextAttribute.FONT), startLim, endLim)
        val lineMeasurer = LineBreakMeasurer(attrCharIter, BreakIterator.getLineInstance(locale), REF_FRC)
        while (lineMeasurer.position != attrCharIter.endIndex) {
            val lineEndPos = lineMeasurer.nextOffset(wrappingWidth)
            lineMeasurer.position = lineEndPos
            breaks.add(lineEndPos)
        }
        return breaks
    }
    }


    data class Background(
        val color: Color,
        val widenLeft: Float,
        val widenRight: Float,
        val widenTop: Float,
        val widenBottom: Float
    )


    private class CustomAttribute(name: String) : AttributedCharacterIterator.Attribute(name) {
        companion object {
            val HEIGHT_HINT = CustomAttribute("height_hint")
            val CUSTOM_BACKGROUND = CustomAttribute("custom_background")
            val DECORATIONS = CustomAttribute("decorations")
        }
    }

}
