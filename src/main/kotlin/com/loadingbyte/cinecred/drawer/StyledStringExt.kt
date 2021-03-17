package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.REF_FRC
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.StyledString
import java.awt.Font
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.util.*


class TextContext(val fonts: Map<LetterStyle, Pair<Font, Font>>, val uppercaseExceptionsRegex: Regex?)


private inline fun StyledString.forEachRun(block: (String, LetterStyle, Int, Int) -> Unit) {
    var runStartIdx = 0
    for ((run, style) in this) {
        val runEndIdx = runStartIdx + run.length
        block(run, style, runStartIdx, runEndIdx)
        runStartIdx = runEndIdx
    }
}


fun StyledString.substring(startIdx: Int, endIdx: Int): StyledString {
    val result = mutableListOf<Pair<String, LetterStyle>>()
    forEachRun { run, style, runStartIdx, runEndIdx ->
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
                return result
        }
    }
    return result
}


fun StyledString.trim(): StyledString {
    val joined = joinToString("") { it.first }
    val startIdx = joined.indexOfFirst { !it.isWhitespace() }
    val endIdx = joined.indexOfLast { !it.isWhitespace() }
    return if (startIdx > endIdx) emptyList() else substring(startIdx, endIdx)
}


fun StyledString.getWidth(textCtx: TextContext): Float =
    toAttributedString(textCtx).iterator.getWidth()

fun AttributedCharacterIterator.getWidth(): Float =
    TextLayout(this, REF_FRC).advance


fun StyledString.getHeight(): Int =
    maxOf { it.second.heightPx }


fun StyledString.toAttributedString(textCtx: TextContext): AttributedString {
    var str = joinToString("") { (run, _) -> run }

    // 1. Apply uppercasing to "str" (not small caps!).
    if (size == 1 && single().second.uppercase) {
        // We implement uppercasing of the quite common single-run styled strings separately for performance benefits.
        str = if (!single().second.useUppercaseExceptions || textCtx.uppercaseExceptionsRegex == null)
            str.toUpperCase(Locale.US)
        else
            buildString(str.length) {
                for (match in textCtx.uppercaseExceptionsRegex.findAll(str)) {
                    append(str.substring(this@buildString.length, match.range.first).toUpperCase(Locale.US))
                    append(str.substring(match.range.first, match.range.last + 1))
                }
                append(str.substring(this@buildString.length).toUpperCase(Locale.US))  // remainder
            }
    } else if (size > 1 && any { (_, style) -> style.uppercase }) {
        // This is the general implementation. First, create a mask that records the indices of chars which
        // shouldn't be uppercased because they are part of user-configured "do not uppercase" patterns.
        val mask = BooleanArray(str.length)
        if (textCtx.uppercaseExceptionsRegex != null)
            for (match in textCtx.uppercaseExceptionsRegex.findAll(str))
                mask.fill(true, match.range.first, match.range.last + 1)
        // Then do the uppercasing for each run with a uppercasing letter style.
        str = buildString(str.length) {
            forEachRun { run, style, runStartIdx, runEndIdx ->
                if (!style.uppercase)
                    append(run)
                else if (!style.useUppercaseExceptions)
                    append(run.toUpperCase(Locale.US))
                else
                    for (idx in runStartIdx until runEndIdx)
                        append(if (mask[idx]) str[idx] else str[idx].toUpperCase())
            }
        }
    }

    var effectiveStr = str

    // 2. Apply small caps to "effectiveStr".
    if (size == 1 && get(0).second.smallCaps)
        effectiveStr = effectiveStr.toUpperCase()
    else if (size > 1 && any { (_, style) -> style.smallCaps })
        effectiveStr = buildString(effectiveStr.length) {
            forEachRun { _, style, runStartIdx, runEndIdx ->
                val run = effectiveStr.substring(runStartIdx, runEndIdx)
                append(if (style.smallCaps) run.toUpperCase() else run)
            }
        }

    // 3. Add attributes to the "effectiveStr" as indicated by the letter styles.
    val attrStr = AttributedString(effectiveStr)
    forEachRun { _, style, runStartIdx, runEndIdx ->
        val (stdFont, smallCapsFont) = textCtx.fonts.getValue(style)
        if (!style.smallCaps)
            attrStr.addAttribute(TextAttribute.FONT, stdFont, runStartIdx, runEndIdx)
        else
            str.forEachAlternatingStrip(runStartIdx, runEndIdx, Char::isLowerCase) { isLowerCase, startIdx, endIdx ->
                attrStr.addAttribute(TextAttribute.FONT, if (isLowerCase) smallCapsFont else stdFont, startIdx, endIdx)
            }

        attrStr.addAttribute(TextAttribute.FOREGROUND, style.foreground, runStartIdx, runEndIdx)
        if (style.background.alpha != 0)
            attrStr.addAttribute(TextAttribute.BACKGROUND, style.background, runStartIdx, runEndIdx)
        if (style.underline)
            attrStr.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, runStartIdx, runEndIdx)
        if (style.strikethrough)
            attrStr.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, runStartIdx, runEndIdx)
    }
    return attrStr
}

private inline fun String.forEachAlternatingStrip(
    startIdx: Int, endIdx: Int, predicate: (Char) -> Boolean, block: (Boolean, Int, Int) -> Unit
) {
    var state = predicate(get(startIdx))
    var stripStartIdx = startIdx

    while (true) {
        val stripEndIdx = if (state)
            indexOfFirst(stripStartIdx, endIdx) { !predicate(it) }
        else
            indexOfFirst(stripStartIdx, endIdx, predicate)

        if (stripEndIdx == -1) {
            if (stripEndIdx != endIdx)
                block(state, stripStartIdx, endIdx)
            break
        }

        block(state, stripStartIdx, stripEndIdx)
        state = !state
        stripStartIdx = stripEndIdx
    }
}

private inline fun String.indexOfFirst(startIdx: Int, endIdx: Int, predicate: (Char) -> Boolean): Int {
    for (idx in startIdx until endIdx) {
        if (predicate(this[idx])) {
            return idx
        }
    }
    return -1
}
