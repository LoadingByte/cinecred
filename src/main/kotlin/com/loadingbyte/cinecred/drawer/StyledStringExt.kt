package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.*
import java.awt.BasicStroke
import java.awt.Font
import java.util.*


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


/**
 * Converts styled strings to formatted strings. As the conversion can be quite expensive and to leverage the
 * caching features provided by FormattedString, we want to do the actual conversion only once. Therefore, this
 * method caches formatted strings.
 */
fun StyledString.formatted(textCtx: TextContext): FormattedString =
    (textCtx as TextContextImpl).getFmtStr(this)


fun makeTextCtx(locale: Locale, uppercaseExceptions: List<String>, projectFonts: Map<String, Font>): TextContext =
    TextContextImpl(locale, uppercaseExceptions, projectFonts)

sealed class TextContext

private class TextContextImpl(
    val locale: Locale,
    private val uppercaseExceptions: List<String>,
    val projectFonts: Map<String, Font>
) : TextContext() {

    val uppercaseExceptionsRegex: Regex? by lazy { generateUppercaseExceptionsRegex(uppercaseExceptions) }

    private val fmtStrAttrsCache = HashMap<LetterStyle, Attrs>()
    private val fmtStrCache = HashMap<StyledString, FormattedString>()

    fun getFmtStrAttrs(letterStyle: LetterStyle) =
        fmtStrAttrsCache.getOrPut(letterStyle) { generateFmtStrAttrs(letterStyle, this) }

    fun getFmtStr(styledString: StyledString) =
        fmtStrCache.getOrPut(styledString) { generateFmtStr(styledString, this) }

    class Attrs(
        val std: FormattedString.Attribute,
        val smallCaps: FormattedString.Attribute?
    )

}


private fun generateUppercaseExceptionsRegex(uppercaseExceptions: List<String>): Regex? = uppercaseExceptions
    .filter { it.isNotBlank() && it != "_" && it != "#" }
    .also { if (it.isEmpty()) return null }
    .groupBy { charToKey(it.first(), 1, 2, 4) or charToKey(it.last(), 8, 16, 32) }
    .map { (key, patterns) ->
        val prefix = when {
            key and 2 != 0 -> "(\\s|^)"
            key and 4 != 0 -> "[\\p{Lu}\\p{Lt}]"
            else -> ""
        }
        val suffix = when {
            key and 16 != 0 -> "(\\s|$)"
            key and 32 != 0 -> "[\\p{Lu}\\p{Lt}]"
            else -> ""
        }
        val joinedPatterns = patterns
            // Note: By sorting the patterns in descending order, we ensure that in the case of long patterns which
            // contain shorter patterns (e.g., "_von und zu_" and "_von_"), the long pattern is matched if it applies,
            // and the short pattern is only matched if the long pattern doesn't apply. If we don't enforce this,
            // the shorter pattern might be matched, but the long pattern isn't even though it applies, which is
            // highly unexpected behavior.
            .sortedByDescending(String::length)
            .joinToString("|") { pattern ->
                when {
                    key and (1 or 8) == 0 -> pattern.substring(1, pattern.length - 1)
                    key and 1 == 0 -> pattern.drop(1)
                    key and 8 == 0 -> pattern.dropLast(1)
                    else -> pattern
                }.let(Regex::escape)
            }
        "$prefix($joinedPatterns)$suffix"
    }
    .joinToString("|")
    .toRegex()

private fun charToKey(char: Char, forOther: Int, forUnderscore: Int, forHash: Int) = when (char) {
    '_' -> forUnderscore
    '#' -> forHash
    else -> forOther
}


private fun generateFmtStrAttrs(
    style: LetterStyle,
    textCtx: TextContextImpl
): TextContextImpl.Attrs {
    val baseAWTFont =
        textCtx.projectFonts[style.fontName] ?: getBundledFont(style.fontName) ?: getSystemFont(style.fontName)

    var ssScaling = 1f
    var ssHOffset = 0f
    var ssVOffset = 0f
    if (style.superscript != Superscript.OFF) {
        val ssMetrics = baseAWTFont.getSuperscriptMetrics()
            ?: SuperscriptMetrics(2 / 3f, 0f, 0.375f, 2 / 3f, 0f, -0.375f)

        fun sup() {
            ssHOffset += ssMetrics.supHOffsetEm * ssScaling
            ssVOffset += ssMetrics.supVOffsetEm * ssScaling
            ssScaling *= ssMetrics.supScaling
        }

        fun sub() {
            ssHOffset += ssMetrics.subHOffsetEm * ssScaling
            ssVOffset += ssMetrics.subVOffsetEm * ssScaling
            ssScaling *= ssMetrics.subScaling
        }

        // @formatter:off
        when (style.superscript) {
            Superscript.OFF -> { /* can never happen */ }
            Superscript.SUP -> sup()
            Superscript.SUB -> sub()
            Superscript.SUP_SUP -> { sup(); sup() }
            Superscript.SUP_SUB -> { sup(); sub() }
            Superscript.SUB_SUP -> { sub(); sup() }
            Superscript.SUB_SUB -> { sub(); sub() }
        }
        // @formatter:on
    }

    val features = mutableListOf<String>()

    if (style.uppercase && style.useUppercaseSpacing)
        features.add("cpsp")

    var fakeSCScaling = Float.NaN
    when (style.smallCaps) {
        SmallCaps.OFF -> {
        }
        SmallCaps.SMALL_CAPS ->
            if (baseAWTFont.supportsFeature("smcp")) features.add("smcp") else
                fakeSCScaling = getSmallCapsScaling(baseAWTFont, 1.1f, 0.8f)
        SmallCaps.PETITE_CAPS ->
            if (baseAWTFont.supportsFeature("pcap")) features.add("pcap") else
                fakeSCScaling = getSmallCapsScaling(baseAWTFont, 1f, 0.725f)
    }

    val stdFont = FormattedString.Font(
        style.foreground, baseAWTFont, style.heightPx.toFloat(), style.scaling * ssScaling, style.hScaling,
        style.hShearing, style.hOffsetRem + ssHOffset, style.vOffsetRem + ssVOffset,
        style.kerning, style.ligatures, features, style.trackingEm
    )
    val smallCapsFont = if (fakeSCScaling.isNaN()) null else stdFont.scaled(fakeSCScaling)

    val lm = baseAWTFont.deriveFont(stdFont.unscaledPointSize).lineMetrics
    val deco = style.decorations.mapTo(HashSet()) { td ->
        var offset = td.offsetPx
        var thickness = td.thicknessPx
        when (td.preset) {
            TextDecorationPreset.OFF -> {
            }
            TextDecorationPreset.UNDERLINE -> {
                offset = lm.underlineOffset
                thickness = lm.underlineThickness
            }
            TextDecorationPreset.STRIKETHROUGH -> {
                offset = lm.strikethroughOffset
                thickness = lm.strikethroughThickness
            }
        }
        FormattedString.Decoration(
            color = if (td.color.isActive) td.color.value else style.foreground,
            offset, thickness, td.widenLeftPx, td.widenRightPx,
            clear = td.clearingPx.isActive,
            clearRadiusPx = td.clearingPx.value,
            clearJoin = when (td.clearingJoin) {
                LineJoin.MITER -> BasicStroke.JOIN_MITER
                LineJoin.ROUND -> BasicStroke.JOIN_ROUND
                LineJoin.BEVEL -> BasicStroke.JOIN_BEVEL
            },
            dashPatternPx = if (td.dashPatternPx.isEmpty()) null else td.dashPatternPx.toFloatArray()
        )
    }

    val bg = if (style.background.alpha == 0) null else
        FormattedString.Background(
            style.background,
            style.backgroundWidenLeftPx, style.backgroundWidenRightPx,
            style.backgroundWidenTopPx, style.backgroundWidenBottomPx
        )

    val stdAttr = FormattedString.Attribute(stdFont, deco, bg)
    val smallCapsAttr = if (smallCapsFont == null) null else FormattedString.Attribute(smallCapsFont, deco, bg)
    return TextContextImpl.Attrs(stdAttr, smallCapsAttr)
}

private fun getSmallCapsScaling(font: Font, multiplier: Float, fallback: Float): Float {
    val extraLM = font.getExtraLineMetrics()
    return if (extraLM == null) fallback else extraLM.xHeightEm / extraLM.capHeightEm * multiplier
}


private fun generateFmtStr(str: StyledString, textCtx: TextContextImpl): FormattedString {
    // 1. Apply uppercasing to the styled string (not small caps yet!).
    var uppercased = str

    // Only run the algorithm if at least one run needs uppercasing.
    if (str.any { (_, style) -> style.uppercase }) {
        val joined = str.joinToString("") { (run, _) -> run }

        // If later needed, precompute an uppercased version of the joined string that considers the
        // uppercase exceptions.
        var joinedUppercased: String? = null
        val uppercaseExceptionsRegex = textCtx.uppercaseExceptionsRegex
        if (uppercaseExceptionsRegex != null && str.any { (_, style) -> style.useUppercaseExceptions })
            joinedUppercased = buildString(joined.length) {
                for (match in uppercaseExceptionsRegex.findAll(joined)) {
                    append(joined.substring(this@buildString.length, match.range.first).uppercase(textCtx.locale))
                    append(joined.substring(match.range.first, match.range.last + 1))
                }
                append(joined.substring(this@buildString.length).uppercase(textCtx.locale))  // remainder
            }

        // Now compute the uppercased styled string. For the quite common single-run styled strings, we have a
        // special case to achieve better performance.
        uppercased = if (str.size == 1)
            listOf(Pair(joinedUppercased ?: joined.uppercase(textCtx.locale), str.single().second))
        else {
            str.mapRuns { _, run, style, runStartIdx, runEndIdx ->
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

    if (uppercased.all { (_, style) -> textCtx.getFmtStrAttrs(style).smallCaps == null })
        smallCapsed = uppercased
    else {
        val masks = arrayOfNulls<BooleanArray?>(uppercased.size)
        smallCapsMasks = masks
        smallCapsed = uppercased.mapRuns { runIdx, run, style, _, _ ->
            if (textCtx.getFmtStrAttrs(style).smallCaps == null)
                run
            else {
                val smallCapsedRun = run.uppercase(textCtx.locale)

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
    val fmtStrBuilder = FormattedString.Builder(textCtx.locale)
    smallCapsed.forEachRun { runIdx, run, style, _, _ ->
        val attrs = textCtx.getFmtStrAttrs(style)
        if (attrs.smallCaps == null)
            fmtStrBuilder.append(run, attrs.std)
        else
            smallCapsMasks!![runIdx]!!.forEachAlternatingStrip { isStripSmallCaps, stripStartIdx, stripEndIdx ->
                val attr = if (isStripSmallCaps) attrs.smallCaps else attrs.std
                fmtStrBuilder.append(run.substring(stripStartIdx, stripEndIdx), attr)
            }
    }
    return fmtStrBuilder.build()
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
