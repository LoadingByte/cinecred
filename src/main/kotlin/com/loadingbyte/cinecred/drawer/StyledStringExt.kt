package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.FormattedString
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.StyledString
import java.awt.Font
import java.util.*


class TextContext(
    val locale: Locale,
    val fonts: Map<LetterStyle, Font>,
    val uppercaseExceptionsRegex: Regex?
) {

    private val fmtStrCache = HashMap<StyledString, FormattedString>()

    fun formattedInternal(styledString: StyledString): FormattedString =
        fmtStrCache.getOrPut(styledString) { styledString.generateFormattedString(this) }

}


/**
 * Converts styled strings to formatted strings. As the conversion can be quite expensive and to leverage the
 * caching features provided by FormattedString, we want to do the actual conversion only once. Therefore, this
 * method caches formatted strings.
 */
fun StyledString.formatted(textCtx: TextContext): FormattedString =
    textCtx.formattedInternal(this)


fun StyledString.substring(startIdx: Int, endIdx: Int): StyledString {
    val result = mutableListOf<Pair<String, LetterStyle>>()
    forEachRun { _, run, style, runStartIdx, runEndIdx ->
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
    val endIdx = joined.indexOfLast { !it.isWhitespace() } + 1
    return if (startIdx > endIdx) emptyList() else substring(startIdx, endIdx)
}


val StyledString.height: Int
    get() = maxOf { it.second.heightPx }


private fun StyledString.generateFormattedString(textCtx: TextContext): FormattedString {
    // 1. Apply uppercasing to the styled string (not small caps yet!).
    var uppercased = this

    // Only run the algorithm if at least one run needs uppercasing.
    if (any { (_, style) -> style.uppercase }) {
        val joined = joinToString("") { (run, _) -> run }

        // If later needed, precompute an uppercased version of the joined string that considers the
        // uppercase exceptions.
        var joinedUppercased: String? = null
        if (textCtx.uppercaseExceptionsRegex != null && any { (_, style) -> style.useUppercaseExceptions })
            joinedUppercased = buildString(joined.length) {
                for (match in textCtx.uppercaseExceptionsRegex.findAll(joined)) {
                    append(joined.substring(this@buildString.length, match.range.first).uppercase(textCtx.locale))
                    append(joined.substring(match.range.first, match.range.last + 1))
                }
                append(joined.substring(this@buildString.length).uppercase(textCtx.locale))  // remainder
            }

        // Now compute the uppercased styled string. For the quite common single-run styled strings, we have a
        // special case to achieve better performance.
        uppercased = if (size == 1)
            listOf(Pair(joinedUppercased ?: joined.uppercase(textCtx.locale), single().second))
        else {
            mapRuns { _, run, style, runStartIdx, runEndIdx ->
                buildString(run.length) {
                    if (!style.uppercase)
                        append(run)
                    else if (!style.useUppercaseExceptions)
                        append(run.uppercase(textCtx.locale))
                    else
                        append(joinedUppercased!!.substring(runStartIdx, runEndIdx))
                }
            }
        }
    }

    // 2. Prepare for small caps. For this, create the "smallCapsed" styled string, which has all runs with a small
    //    caps style uppercased. For each character in those uppercased runs, remember whether it should be rendered
    //    as a small cap letter or regular uppercase letter.
    val smallCapsed: StyledString
    var smallCapsMasks: Array<BooleanArray?>? = null

    if (!uppercased.any { (_, style) -> style.smallCaps.isEffective })
        smallCapsed = uppercased
    else {
        val masks = arrayOfNulls<BooleanArray?>(uppercased.size)
        smallCapsMasks = masks
        smallCapsed = uppercased.mapRuns { runIdx, run, style, _, _ ->
            if (!style.smallCaps.isEffective)
                run
            else {
                val smallCapsedRun = if (style.smallCaps.isEffective) run.uppercase(textCtx.locale) else run

                // Generate a boolean mask indicating which characters of the "smallCapsedRun" should be rendered
                // as small caps. This mask is especially interesting in the rare cases where the uppercasing operation
                // in the previous line yields a string with more characters than were in the original.
                masks[runIdx] = if (run.length == smallCapsedRun.length)
                    BooleanArray(run.length) { idx -> run[idx].isLowerCase() }
                else
                    BooleanArray(smallCapsedRun.length).also { mask ->
                        var prevEndFillIdx = 0
                        for (idxInRun in run.indices) {
                            val endFillIdx = run.substring(0, idxInRun + 1).uppercase(textCtx.locale).length
                            mask.fill(run[idxInRun].isLowerCase(), prevEndFillIdx, endFillIdx)
                            prevEndFillIdx = endFillIdx
                        }
                    }

                smallCapsedRun
            }
        }
    }

    // 3. Build a FormattedString by adding attributes to the "smallCapsed" string as indicated by the letter styles.
    val fmtStr = FormattedString(smallCapsed.joinToString("") { (run, _) -> run })
    smallCapsed.forEachRun { runIdx, _, style, runStartIdx, runEndIdx ->
        val font = textCtx.fonts.getValue(style)
        val heightHint = style.heightPx.toFloat()
        if (!style.smallCaps.isEffective)
            fmtStr.setFont(font, heightHint, runStartIdx, runEndIdx)
        else
            smallCapsMasks!![runIdx]!!.forEachAlternatingStrip { isStripSmallCaps, stripStartIdx, stripEndIdx ->
                val efFont = if (isStripSmallCaps) font.deriveFont(font.size2D * style.smallCaps.value / 100f) else font
                fmtStr.setFont(efFont, heightHint, runStartIdx + stripStartIdx, runStartIdx + stripEndIdx)
            }

        fmtStr.setForeground(style.foreground, runStartIdx, runEndIdx)
        if (style.background.alpha != 0) {
            val w = style.backgroundWidening
            val bg = FormattedString.Background(style.background, w.left, w.right, w.top, w.bottom)
            fmtStr.setBackground(bg, runStartIdx, runEndIdx)
        }
        if (style.underline)
            fmtStr.setUnderline(true, runStartIdx, runEndIdx)
        if (style.strikethrough)
            fmtStr.setStrikethrough(true, runStartIdx, runEndIdx)
    }
    return fmtStr
}

private inline fun BooleanArray.forEachAlternatingStrip(block: (Boolean, Int, Int) -> Unit) {
    var state = get(0)
    var stripStartIdx = 0

    while (true) {
        val stripEndIdx = indexOf(!state, stripStartIdx)

        if (stripEndIdx == -1) {
            if (stripStartIdx != size)
                block(state, stripStartIdx, size)
            break
        }

        block(state, stripStartIdx, stripEndIdx)
        state = !state
        stripStartIdx = stripEndIdx
    }
}

private fun BooleanArray.indexOf(elem: Boolean, startIdx: Int): Int {
    for (idx in startIdx until size) {
        if (this[idx] == elem) {
            return idx
        }
    }
    return -1
}


private inline fun StyledString.forEachRun(block: (Int, String, LetterStyle, Int, Int) -> Unit) {
    var runStartIdx = 0
    for (runIdx in indices) {
        val (run, style) = get(runIdx)
        val runEndIdx = runStartIdx + run.length
        block(runIdx, run, style, runStartIdx, runEndIdx)
        runStartIdx = runEndIdx
    }
}


private inline fun StyledString.mapRuns(transform: (Int, String, LetterStyle, Int, Int) -> String): StyledString {
    val result = ArrayList<Pair<String, LetterStyle>>(size)
    forEachRun { runIdx, run, style, runStartIdx, runEndIdx ->
        result.add(Pair(transform(runIdx, run, style, runStartIdx, runEndIdx), style))
    }
    return result
}
