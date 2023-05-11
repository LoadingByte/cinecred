package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.FormattedString
import com.loadingbyte.cinecred.project.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.util.*
import kotlin.math.abs


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


val StyledString.height: Double
    get() = maxOf { it.second.heightPx }


/**
 * Converts styled strings to formatted strings. As the conversion can be quite expensive and to leverage the
 * caching features provided by FormattedString, we want to do the actual conversion only once. Therefore, this
 * method caches formatted strings.
 */
fun StyledString.formatted(textCtx: TextContext): FormattedString =
    (textCtx as TextContextImpl).getFmtStr(this)


fun makeTextCtx(styling: Styling, stylingCtx: StylingContext): TextContext =
    TextContextImpl(styling, stylingCtx)

sealed interface TextContext

private class TextContextImpl(private val styling: Styling, val stylingCtx: StylingContext) : TextContext {

    val locale: Locale
        get() = styling.global.locale

    val uppercaseExceptionsRegex: Regex? by lazy {
        generateUppercaseExceptionsRegex(styling.global.uppercaseExceptions)
    }

    private val fmtStrFontsCache = IdentityHashMap<LetterStyle, Fonts>()
    private val fmtStrDesignCache = IdentityHashMap<LetterStyle, FormattedString.Design>()
    private val fmtStrCache = IdentityHashMap<StyledString, FormattedString>()

    fun getFmtStrFonts(letterStyle: LetterStyle): Fonts =
        fmtStrFontsCache.getOrPut(letterStyle) {
            generateFmtStrFonts(letterStyle, this) ?: getFmtStrFonts(PLACEHOLDER_LETTER_STYLE)
        }

    fun getFmtStrDesign(letterStyle: LetterStyle): FormattedString.Design =
        fmtStrDesignCache.getOrPut(letterStyle) {
            if (letterStyle.inheritLayersFromStyle.isActive) {
                val refStyle = styling.letterStyles.find { o -> o.name == letterStyle.inheritLayersFromStyle.value }
                    ?: letterStyle.copy(
                        inheritLayersFromStyle = Opt(false, ""),
                        layers = PLACEHOLDER_LETTER_STYLE.layers
                    )
                getFmtStrDesign(refStyle)
            } else
                generateFmtStrDesign(letterStyle.layers, getFmtStrFonts(letterStyle).std)
        }

    fun getFmtStr(styledString: StyledString): FormattedString =
        fmtStrCache.computeIfAbsent(styledString) { generateFmtStr(styledString, this) }

    class Fonts(
        val std: FormattedString.Font,
        val fakeSmallCaps: FormattedString.Font?
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


private fun generateFmtStrFonts(
    style: LetterStyle,
    textCtx: TextContextImpl
): TextContextImpl.Fonts? {
    // If the styling context doesn't contain a font with the specified name, we create a font object to find a
    // fallback font that (hopefully) best matches the specified font.
    val baseAWTFont = textCtx.stylingCtx.resolveFont(style.fontName) ?: Font(style.fontName, 0, 1)

    // Leading
    val leadingTopPx = style.leadingTopRh * style.heightPx
    val leadingBottomPx = style.leadingBottomRh * style.heightPx

    // Font height
    // This formula exactly matches the computation in StyleConstraints.
    val fontHeightPx = style.heightPx - leadingTopPx - leadingBottomPx
    if (fontHeightPx < 1.0)
        return null

    // Superscript
    var ssScaling = 1.0
    var ssHOffset = 0.0
    var ssVOffset = 0.0
    var ssOffsetUnit = FormattedString.Font.Unit.PIXEL
    if (style.superscript == Superscript.CUSTOM) {
        ssScaling = style.superscriptScaling
        ssHOffset = style.superscriptHOffsetRfh * fontHeightPx
        ssVOffset = style.superscriptVOffsetRfh * fontHeightPx
    } else if (style.superscript != Superscript.OFF) {
        ssOffsetUnit = FormattedString.Font.Unit.UNSCALED_EM

        val ssMetrics = baseAWTFont.getSuperscriptMetrics()
            ?: SuperscriptMetrics(2 / 3.0, 0.0, 0.375, 2 / 3.0, 0.0, -0.375)

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
            Superscript.OFF, Superscript.CUSTOM -> { /* can never happen */ }
            Superscript.SUP -> sup()
            Superscript.SUB -> sub()
            Superscript.SUP_SUP -> { sup(); sup() }
            Superscript.SUP_SUB -> { sup(); sub() }
            Superscript.SUB_SUP -> { sub(); sup() }
            Superscript.SUB_SUB -> { sub(); sub() }
        }
        // @formatter:on
    }

    // User-defined OpenType features
    val features = style.features.mapTo(mutableListOf()) { FormattedString.Font.Feature(it.tag, it.value) }

    // Uppercase spacing
    if (style.uppercase && style.useUppercaseSpacing)
        features.add(FormattedString.Font.Feature(CAPITAL_SPACING_FONT_FEAT, 1))

    // Small caps
    var fakeSCScaling = Double.NaN
    when (style.smallCaps) {
        SmallCaps.OFF -> {}
        SmallCaps.SMALL_CAPS ->
            if (SMALL_CAPS_FONT_FEAT in baseAWTFont.getSupportedFeatures())
                features.add(FormattedString.Font.Feature(SMALL_CAPS_FONT_FEAT, 1))
            else
                fakeSCScaling = getSmallCapsScaling(baseAWTFont, 1.1, 0.8)
        SmallCaps.PETITE_CAPS ->
            if (PETITE_CAPS_FONT_FEAT in baseAWTFont.getSupportedFeatures())
                features.add(FormattedString.Font.Feature(PETITE_CAPS_FONT_FEAT, 1))
            else
                fakeSCScaling = getSmallCapsScaling(baseAWTFont, 1.0, 0.725)
    }

    val stdFont = FormattedString.Font(
        baseAWTFont, fontHeightPx, leadingTopPx, leadingBottomPx, ssScaling, style.hScaling,
        ssHOffset, ssOffsetUnit, ssVOffset, ssOffsetUnit,
        style.trackingEm, style.kerning, style.ligatures, features
    )
    val fakeSmallCapsFont = if (fakeSCScaling.isNaN()) null else FormattedString.Font(
        baseAWTFont, fontHeightPx, leadingTopPx, leadingBottomPx, ssScaling * fakeSCScaling, style.hScaling,
        ssHOffset, ssOffsetUnit, ssVOffset, ssOffsetUnit,
        style.trackingEm, style.kerning, style.ligatures, features
    )

    return TextContextImpl.Fonts(stdFont, fakeSmallCapsFont)
}

private fun getSmallCapsScaling(font: Font, multiplier: Double, fallback: Double): Double {
    val extraLM = font.getExtraLineMetrics()
    return if (extraLM == null) fallback else extraLM.xHeightEm / extraLM.capHeightEm * multiplier
}


private fun generateFmtStrDesign(layers: List<Layer>, stdFont: FormattedString.Font): FormattedString.Design {
    val fh = stdFont.fontHeightPx
    val lm = stdFont.unscaledAWTFont.lineMetrics

    val fmtStrLayers = layers.map { layer ->
        val coloring = when (layer.coloring) {
            LayerColoring.OFF ->
                FormattedString.Layer.Coloring.Plain(
                    color = Color(0, 0, 0, 0)
                )
            LayerColoring.PLAIN ->
                FormattedString.Layer.Coloring.Plain(
                    color = layer.color1
                )
            LayerColoring.GRADIENT ->
                FormattedString.Layer.Coloring.Gradient(
                    color1 = layer.color1,
                    color2 = layer.color2,
                    angleDeg = layer.gradientAngleDeg,
                    extentPx = layer.gradientExtentRfh * fh,
                    shiftPx = layer.gradientShiftRfh * fh
                )
        }

        val shape = when (layer.shape) {
            LayerShape.TEXT ->
                FormattedString.Layer.Shape.Text
            LayerShape.STRIPE -> {
                val heightPx: Double
                val offsetPx: Double
                when (layer.stripePreset) {
                    StripePreset.BACKGROUND -> {
                        offsetPx = fh / 2.0 * (1.0 + layer.stripeWidenBottomRfh - layer.stripeWidenTopRfh) -
                                stdFont.fontHeightAboveBaselinePx
                        heightPx = abs(fh * (1.0 + layer.stripeWidenTopRfh + layer.stripeWidenBottomRfh))
                    }
                    StripePreset.UNDERLINE -> {
                        offsetPx = lm.underlineOffset + lm.underlineThickness / 2.0
                        heightPx = lm.underlineThickness.toDouble()
                    }
                    StripePreset.STRIKETHROUGH -> {
                        offsetPx = lm.strikethroughOffset + lm.strikethroughThickness / 2.0
                        heightPx = lm.strikethroughThickness.toDouble()
                    }
                    StripePreset.CUSTOM -> {
                        heightPx = layer.stripeHeightRfh * fh
                        offsetPx = layer.stripeOffsetRfh * fh
                    }
                }
                val dashPatternPx = if (layer.stripeDashPatternRfh.isEmpty()) null else
                    layer.stripeDashPatternRfh.mapToDoubleArray { it * fh }
                FormattedString.Layer.Shape.Stripe(
                    heightPx = heightPx,
                    offsetPx = offsetPx,
                    widenLeftPx = layer.stripeWidenLeftRfh * fh,
                    widenRightPx = layer.stripeWidenRightRfh * fh,
                    cornerJoin = getLineJoinNumber(layer.stripeCornerJoin),
                    cornerRadiusPx = layer.stripeCornerRadiusRfh * fh,
                    dashPatternPx = dashPatternPx
                )
            }
            LayerShape.CLONE ->
                FormattedString.Layer.Shape.Clone(
                    layers = layer.cloneLayers.mapToIntArray { it - 1 },
                    anchorSiblingLayer = if (layer.anchor == LayerAnchor.SIBLING) layer.anchorSiblingLayer - 1 else -1
                )
        }

        val dilation = if (layer.shape == LayerShape.STRIPE || layer.dilationRfh == 0.0) null else
            FormattedString.Layer.Dilation(
                radiusPx = layer.dilationRfh * fh,
                join = getLineJoinNumber(layer.dilationJoin)
            )

        val contour = if (!layer.contour) null else
            FormattedString.Layer.Contour(
                thicknessPx = layer.contourThicknessRfh * fh,
                join = getLineJoinNumber(layer.contourJoin)
            )

        val clearing = if (layer.clearingLayers.isEmpty()) null else
            FormattedString.Layer.Clearing(
                layers = layer.clearingLayers.mapToIntArray { it - 1 },
                radiusPx = layer.clearingRfh * fh,
                join = getLineJoinNumber(layer.clearingJoin)
            )

        FormattedString.Layer(
            coloring = coloring,
            shape = shape,
            dilation = dilation,
            contour = contour,
            hOffsetPx = layer.hOffsetRfh * fh,
            vOffsetPx = layer.vOffsetRfh * fh,
            hScaling = layer.hScaling,
            vScaling = layer.vScaling,
            hShearing = layer.hShearing,
            vShearing = layer.vShearing,
            anchorGlobal = layer.anchor == LayerAnchor.GLOBAL,
            clearing = clearing,
            blurRadiusPx = layer.blurRadiusRfh * fh
        )
    }

    return FormattedString.Design(
        masterFont = stdFont,
        layers = fmtStrLayers
    )
}

private fun getLineJoinNumber(lineJoin: LineJoin): Int = when (lineJoin) {
    LineJoin.MITER -> BasicStroke.JOIN_MITER
    LineJoin.ROUND -> BasicStroke.JOIN_ROUND
    LineJoin.BEVEL -> BasicStroke.JOIN_BEVEL
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

    // 2. Prepare for fake small caps. For this, create the "smallCapsed" styled string, which has all runs with a small
    //    caps style uppercased. For each character in those uppercased runs, remember whether it should be rendered
    //    as a small cap letter or regular uppercase letter.
    val smallCapsed: StyledString
    var smallCapsMasks: Array<BooleanArray?>? = null

    if (uppercased.all { (_, style) -> textCtx.getFmtStrFonts(style).fakeSmallCaps == null })
        smallCapsed = uppercased
    else {
        val masks = arrayOfNulls<BooleanArray?>(uppercased.size)
        smallCapsMasks = masks
        smallCapsed = uppercased.mapRuns { runIdx, run, style, _, _ ->
            if (textCtx.getFmtStrFonts(style).fakeSmallCaps == null)
                run
            else {
                val smallCapsedRun = run.uppercase(textCtx.locale)

                // Generate a boolean mask indicating which characters of the "smallCapsedRun" should be rendered
                // as small caps. This mask is especially interesting in the rare cases where the uppercasing operation
                // in the previous line yields a string with more characters than were in the original.
                val mask = BooleanArray(smallCapsedRun.length)
                masks[runIdx] = mask
                if (run.length == smallCapsedRun.length) {
                    var idxInRun = 0
                    while (idxInRun < run.length) {
                        val code = run.codePointAt(idxInRun)
                        val skip = Character.charCount(code)
                        mask.fill(Character.isLowerCase(code), idxInRun, idxInRun + skip)
                        idxInRun += skip
                    }
                } else {
                    var idxInRun = 0
                    var prevEndFillIdx = 0
                    while (idxInRun < run.length) {
                        val code = run.codePointAt(idxInRun)
                        val endFillIdx = run.substring(0, idxInRun + 1).uppercase(textCtx.locale).length
                        mask.fill(Character.isLowerCase(code), prevEndFillIdx, endFillIdx)
                        idxInRun += Character.charCount(code)
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
        val fonts = textCtx.getFmtStrFonts(style)
        val design = textCtx.getFmtStrDesign(style)
        if (fonts.fakeSmallCaps == null)
            fmtStrBuilder.append(run, FormattedString.Attribute(fonts.std, design))
        else
            smallCapsMasks!![runIdx]!!.forEachAlternatingStrip { isStripSmallCaps, stripStartIdx, stripEndIdx ->
                val font = if (isStripSmallCaps) fonts.fakeSmallCaps else fonts.std
                fmtStrBuilder.append(run.substring(stripStartIdx, stripEndIdx), FormattedString.Attribute(font, design))
            }
    }
    return fmtStrBuilder.build()
}

private inline fun BooleanArray.forEachAlternatingStrip(block: (Boolean, Int, Int) -> Unit) {
    var state = get(0)
    var stripStartIdx = 0

    while (true) {
        val stripEndIdx = indexOfAfter(!state, stripStartIdx)

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
