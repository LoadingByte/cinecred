package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.*
import com.loadingbyte.cinecred.project.BlockOrientation.*
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.GridStructure.*
import com.loadingbyte.cinecred.project.MatchExtent.*
import com.loadingbyte.cinecred.project.SpineAttachment.*
import java.awt.Color
import java.text.NumberFormat
import java.util.*
import kotlin.math.floor


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleConstraints(styleClass: Class<S>): List<StyleConstraint<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_CONSTRAINTS
    PageStyle::class.java -> PAGE_STYLE_CONSTRAINTS
    ContentStyle::class.java -> CONTENT_STYLE_CONSTRAINTS
    LetterStyle::class.java -> LETTER_STYLE_CONSTRAINTS
    Layer::class.java -> LAYER_CONSTRAINTS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleConstraint<S, *>>


private val GLOBAL_CONSTRAINTS: List<StyleConstraint<Global, *>> = listOf(
    ResolutionConstr(ERROR, Global::resolution.st()),
    FPSConstr(ERROR, Global::fps.st()),
    DynChoiceConstr(ERROR, Global::timecodeFormat.st()) { _, _, global ->
        val formats = TimecodeFormat.values().toSortedSet()
        if (!global.fps.supportsDropFrameTimecode)
            formats.remove(TimecodeFormat.SMPTE_DROP_FRAME)
        formats
    },
    IntConstr(ERROR, Global::runtimeFrames.st(), min = 1),
    ColorConstr(ERROR, Global::grounding.st(), allowAlpha = false),
    DoubleConstr(ERROR, Global::unitVGapPx.st(), min = 0.0, minInclusive = false)
)


private val PAGE_STYLE_CONSTRAINTS: List<StyleConstraint<PageStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), PageStyle::name.st()) { _, _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), PageStyle::name.st()) { _, styling, style ->
        styling.pageStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    IntConstr(ERROR, PageStyle::afterwardSlugFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardDurationFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeInFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeOutFrames.st(), min = 0),
    DoubleConstr(ERROR, PageStyle::scrollPxPerFrame.st(), min = 0.0, minInclusive = false),
    JudgeConstr(INFO, msg("project.styling.constr.fractionalScrollPx"), PageStyle::scrollPxPerFrame.st()) { _, _, sty ->
        val value = sty.scrollPxPerFrame
        floor(value) == value
    }
)


private val CONTENT_STYLE_CONSTRAINTS: List<StyleConstraint<ContentStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), ContentStyle::name.st()) { _, _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), ContentStyle::name.st()) { _, styling, style ->
        styling.contentStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    DynChoiceConstr(WARN, ContentStyle::spineAttachment.st()) { _, _, style ->
        when (style.blockOrientation) {
            VERTICAL -> sortedSetOf(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
            HORIZONTAL -> when {
                !style.hasHead && !style.hasTail -> sortedSetOf(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
                style.hasHead && !style.hasTail -> sortedSetOf(
                    OVERALL_CENTER,
                    HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT, HEAD_GAP_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT
                )
                !style.hasHead && style.hasTail -> sortedSetOf(
                    OVERALL_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT,
                    TAIL_GAP_CENTER, TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT
                )
                else -> SpineAttachment.values().toSortedSet()
            }
        }
    },
    DoubleConstr(ERROR, ContentStyle::vMarginPx.st(), min = 0.0),
    FixedChoiceConstr(
        WARN, ContentStyle::gridMatchColWidths.st(), ContentStyle::headMatchWidth.st(),
        ContentStyle::tailMatchWidth.st(),
        choices = sortedSetOf(OFF, ACROSS_BLOCKS)
    ),
    StyleNameConstr(
        WARN, ContentStyle::bodyLetterStyleName.st(), ContentStyle::headLetterStyleName.st(),
        ContentStyle::tailLetterStyleName.st(),
        styleClass = LetterStyle::class.java,
        choices = { _, styling, _ -> styling.letterStyles }
    ),
    DynChoiceConstr(WARN, ContentStyle::gridStructure.st()) { _, _, style ->
        if (style.gridCellHJustifyPerCol.size < 2) sortedSetOf(FREE, SQUARE_CELLS)
        else GridStructure.values().toSortedSet()
    },
    StyleNameConstr(
        ERROR, ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.bodyLayout == GRID && o.gridMatchColWidths == ACROSS_BLOCKS }
        }
    ),
    DynChoiceConstr(WARN, ContentStyle::gridMatchRowHeight.st()) { _, _, style ->
        if (style.gridStructure == SQUARE_CELLS) sortedSetOf(OFF, ACROSS_BLOCKS)
        else MatchExtent.values().toSortedSet()
    },
    StyleNameConstr(
        ERROR, ContentStyle::gridMatchRowHeightAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.bodyLayout == GRID && o.gridMatchRowHeight == ACROSS_BLOCKS }
        }
    ),
    MinSizeConstr(ERROR, ContentStyle::gridCellHJustifyPerCol.st(), 1),
    DoubleConstr(ERROR, ContentStyle::gridRowGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::gridColGapPx.st(), min = 0.0),
    DynChoiceConstr(WARN, ContentStyle::flowMatchCellWidth.st(), ContentStyle::flowMatchCellHeight.st()) { _, _, sty ->
        if (sty.flowSquareCells) sortedSetOf(OFF, ACROSS_BLOCKS)
        else MatchExtent.values().toSortedSet()
    },
    StyleNameConstr(
        ERROR, ContentStyle::flowMatchCellWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.bodyLayout == FLOW && o.flowMatchCellWidth == ACROSS_BLOCKS }
        }
    ),
    StyleNameConstr(
        ERROR, ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.bodyLayout == FLOW && o.flowMatchCellHeight == ACROSS_BLOCKS }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::flowLineWidthPx.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, ContentStyle::flowLineGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::flowHGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::paragraphsLineWidthPx.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, ContentStyle::paragraphsParaGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::paragraphsLineGapPx.st(), min = 0.0),
    StyleNameConstr(
        ERROR, ContentStyle::headMatchWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.blockOrientation == HORIZONTAL && o.headMatchWidth == ACROSS_BLOCKS }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::headGapPx.st(), min = 0.0),
    StyleNameConstr(
        ERROR, ContentStyle::tailMatchWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { _, styling, _ ->
            styling.contentStyles.filter { o -> o.blockOrientation == HORIZONTAL && o.tailMatchWidth == ACROSS_BLOCKS }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::tailGapPx.st(), min = 0.0)
)


private val LETTER_STYLE_CONSTRAINTS: List<StyleConstraint<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), LetterStyle::name.st()) { _, _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), LetterStyle::name.st()) { _, styling, style ->
        styling.letterStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    FontNameConstr(WARN, LetterStyle::fontName.st()),
    DoubleConstr(ERROR, LetterStyle::heightPx.st(), min = 1.0),
    JudgeConstr(
        WARN, msg("project.styling.constr.excessiveLeading"),
        LetterStyle::heightPx.st(), LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st()
    ) { _, _, style ->
        // The formula is written this way to exactly match the computation in StyledStringExt.
        style.heightPx - style.leadingTopRh * style.heightPx - style.leadingBottomRh * style.heightPx >= 1.0
    },
    JudgeConstr(INFO, msg("project.styling.constr.fakeSmallCaps"), LetterStyle::smallCaps.st()) { ctx, _, style ->
        val font = ctx.resolveFont(style.fontName) ?: return@JudgeConstr true
        when (style.smallCaps) {
            SmallCaps.OFF -> true
            SmallCaps.SMALL_CAPS -> SMALL_CAPS_FONT_FEAT in font.getSupportedFeatures()
            SmallCaps.PETITE_CAPS -> PETITE_CAPS_FONT_FEAT in font.getSupportedFeatures()
        }
    },
    DoubleConstr(ERROR, LetterStyle::superscriptScaling.st(), min = 0.0, minInclusive = false),
    FontFeatureConstr(WARN, LetterStyle::features.st()) { ctx, _, style ->
        val font = ctx.resolveFont(style.fontName) ?: return@FontFeatureConstr Collections.emptySortedSet()
        TreeSet(font.getSupportedFeatures()).apply { removeAll(MANAGED_FONT_FEATS) }
    },
    StyleNameConstr(
        WARN, LetterStyle::inheritLayersFromStyle.st(),
        styleClass = LetterStyle::class.java,
        choices = { _, styling, _ -> styling.letterStyles.filter { o -> !o.inheritLayersFromStyle.isActive } }
    ),
    // This constraint is imposed upon us by Java. Source: sun.font.AttributeValues.i_validate()
    DoubleConstr(ERROR, LetterStyle::hScaling.st(), min = 0.5, max = 10.0, maxInclusive = false)
)


private const val BLUR_RADIUS_LIMIT = 200

private val LAYER_CONSTRAINTS: List<StyleConstraint<Layer, *>> = listOf(
    DoubleConstr(ERROR, Layer::stripeHeightRfh.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, Layer::stripeCornerRadiusRfh.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, Layer::stripeDashPatternRfh.st(), min = 0.0, minInclusive = false),
    JudgeConstr(WARN, msg("project.styling.constr.cycleBackToSelf"), Layer::cloneLayers.st()) { _, styling, style ->
        if (style.shape != LayerShape.CLONE || style.cloneLayers.isEmpty())
            return@JudgeConstr true
        val layers = (styling.getParentStyle(style) as LetterStyle).layers
        !canWalkBackToSelf(layers, layers.indexOfFirst { it === style })
    },
    SiblingOrdinalConstr(ERROR, Layer::cloneLayers.st()) { _, _, _, styleOrdinal, _, siblingOrdinal ->
        siblingOrdinal != styleOrdinal
    },
    MinSizeConstr(WARN, Layer::cloneLayers.st(), minSize = 1),
    DoubleConstr(ERROR, Layer::dilationRfh.st(), min = 0.0),
    DoubleConstr(ERROR, Layer::contourThicknessRfh.st(), min = 0.0, minInclusive = false),
    JudgeConstr(WARN, msg("project.styling.constr.cycleBackToSelf"), Layer::clearingLayers.st()) { _, styling, style ->
        if (style.clearingLayers.isEmpty())
            return@JudgeConstr true
        val layers = (styling.getParentStyle(style) as LetterStyle).layers
        !canWalkBackToSelf(layers, layers.indexOfFirst { it === style })
    },
    SiblingOrdinalConstr(ERROR, Layer::clearingLayers.st()) { _, _, _, styleOrdinal, _, siblingOrdinal ->
        siblingOrdinal != styleOrdinal
    },
    DoubleConstr(ERROR, Layer::clearingRfh.st(), min = 0.0),
    DoubleConstr(ERROR, Layer::blurRadiusRfh.st(), min = 0.0),
    JudgeConstr(
        ERROR, msg("project.styling.constr.excessiveBlurRadius", BLUR_RADIUS_LIMIT),
        Layer::blurRadiusRfh.st()
    ) { _, styling, style ->
        val letterStyle = styling.getParentStyle(style) as LetterStyle
        val fontHeightPx = letterStyle.heightPx * (1.0 - letterStyle.leadingTopRh - letterStyle.leadingBottomRh)
        style.blurRadiusRfh * fontHeightPx <= BLUR_RADIUS_LIMIT
    }
)

private fun canWalkBackToSelf(layers: List<Layer>, ownLayerIdx: Int): Boolean {
    val visitingLayers = BooleanArray(layers.size)
    var walkedBackToSelf = false

    fun visit(layerIdx: Int) {
        if (visitingLayers[layerIdx]) {
            if (layerIdx == ownLayerIdx)
                walkedBackToSelf = true
            return
        }
        visitingLayers[layerIdx] = true
        val layer = layers[layerIdx]
        if (layer.shape == LayerShape.CLONE)
            for (refLayerOrdinal in layer.cloneLayers)
                visit(refLayerOrdinal - 1)
        for (refLayerOrdinal in layer.clearingLayers)
            visit(refLayerOrdinal - 1)
        visitingLayers[layerIdx] = false
    }

    visit(ownLayerIdx)
    return walkedBackToSelf
}


sealed class StyleConstraint<S : Style, SS : StyleSetting<S, *>>(
    vararg settings: SS
) {
    val settings: List<SS> = settings.toList()
}


class IntConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Int>,
    val min: Int? = null,
    val max: Int? = null,
) : StyleConstraint<S, StyleSetting<S, Int>>(setting)


class DoubleConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Double>,
    val finite: Boolean = true,
    val min: Double? = null,
    val minInclusive: Boolean = true,
    val max: Double? = null,
    val maxInclusive: Boolean = true
) : StyleConstraint<S, StyleSetting<S, Double>>(setting)


class FixedChoiceConstr<S : Style, SUBJ : Any>(
    val severity: Severity,
    vararg settings: StyleSetting<S, SUBJ>,
    val choices: SortedSet<SUBJ>
) : StyleConstraint<S, StyleSetting<S, SUBJ>>(*settings)


class DynChoiceConstr<S : Style, SUBJ : Any>(
    val severity: Severity,
    vararg settings: StyleSetting<S, SUBJ>,
    val choices: (StylingContext, Styling, S) -> SortedSet<SUBJ>
) : StyleConstraint<S, StyleSetting<S, SUBJ>>(*settings)


class StyleNameConstr<S : Style, R : ListedStyle>(
    val severity: Severity,
    vararg settings: StyleSetting<S, String>,
    val styleClass: Class<R>,
    val clustering: Boolean = false,
    val choices: (StylingContext, Styling, S) -> List<R>
) : StyleConstraint<S, StyleSetting<S, String>>(*settings) {
    init {
        // This is due to limitations in our current implementation of StylingConsistency and StyleUsage.
        require(settings.all { st -> ListedStyle::class.java.isAssignableFrom(st.declaringClass) })
    }
}


class ColorConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Color>,
    val allowAlpha: Boolean
) : StyleConstraint<S, StyleSetting<S, Color>>(setting)


class ResolutionConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Resolution>
) : StyleConstraint<S, StyleSetting<S, Resolution>>(setting)


class FPSConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FPS>
) : StyleConstraint<S, StyleSetting<S, FPS>>(setting)


class FontNameConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, String>
) : StyleConstraint<S, StyleSetting<S, String>>(setting)


class FontFeatureConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FontFeature>,
    val getAvailableTags: (StylingContext, Styling, S) -> SortedSet<String>
) : StyleConstraint<S, StyleSetting<S, FontFeature>>(setting)


class JudgeConstr<S : Style>(
    val severity: Severity,
    val getMsg: () -> String,
    vararg settings: StyleSetting<S, Any>,
    val judge: (StylingContext, Styling, S) -> Boolean
) : StyleConstraint<S, StyleSetting<S, Any>>(*settings)


class MinSizeConstr<S : Style>(
    val severity: Severity,
    setting: ListStyleSetting<S, Any>,
    val minSize: Int
) : StyleConstraint<S, ListStyleSetting<S, Any>>(setting)


class SiblingOrdinalConstr<S : NestedStyle>(
    val severity: Severity,
    setting: StyleSetting<S, Int>,
    val permitSibling: (StylingContext, Styling, S, Int, S, Int) -> Boolean
) : StyleConstraint<S, StyleSetting<S, Int>>(setting)


class ConstraintViolation(
    val rootStyle: Style,
    val leafStyle: Style,
    val leafSetting: StyleSetting<*, *>,
    /** -1 -> global; >=0 -> w.r.t. the element from [StyleSetting.extractSubjects] with that index */
    val leafSubjectIndex: Int,
    val severity: Severity,
    val msg: String?
)

fun verifyConstraints(ctx: StylingContext, styling: Styling): List<ConstraintViolation> {
    val violations = mutableListOf<ConstraintViolation>()

    fun log(
        rootStyle: Style, leafStyle: Style, leafSetting: StyleSetting<*, *>, leafSubjectIndex: Int,
        severity: Severity, msg: String?
    ) {
        violations.add(ConstraintViolation(rootStyle, leafStyle, leafSetting, leafSubjectIndex, severity, msg))
    }

    fun <S : Style> verifyStyle(rootStyle: Style, style: S, styleIdx: Int = 0, siblings: List<S> = emptyList()) {
        val ignoreSettings = findIneffectiveSettings(ctx, styling, style).keys

        for (cst in getStyleConstraints(style.javaClass))
            when (cst) {
                is IntConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (cst.min != null && value < cst.min || cst.max != null && value > cst.max) {
                            val minRestr = cst.min?.let { "\u2265 $it" }
                            val maxRestr = cst.max?.let { "\u2264 $it" }
                            val restr = combineNumberRestrictions(minRestr, maxRestr)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.number", restr))
                        }
                    }
                is DoubleConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (cst.finite && !value.isFinite() ||
                            cst.min != null && (if (cst.minInclusive) value < cst.min else value <= cst.min) ||
                            cst.max != null && (if (cst.maxInclusive) value > cst.max else value >= cst.max)
                        ) {
                            fun fmt(f: Double) = NumberFormat.getCompactNumberInstance().format(f)
                            val minRestr = cst.min?.let { (if (cst.minInclusive) "\u2265 " else "> ") + fmt(it) }
                            val maxRestr = cst.max?.let { (if (cst.maxInclusive) "\u2264 " else "< ") + fmt(it) }
                            val restr = combineNumberRestrictions(minRestr, maxRestr)
                            val key = when {
                                cst.finite -> "project.styling.constr.finiteNumber"
                                else -> "project.styling.constr.number"
                            }
                            log(rootStyle, style, st, idx, cst.severity, l10n(key, restr))
                        }
                    }
                is FixedChoiceConstr<S, *> ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (value !in cst.choices)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.choice"))
                    }
                is DynChoiceConstr<S, *> -> {
                    val choices = cst.choices(ctx, styling, style)
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (value !in choices)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.choice"))
                    }
                }
                is StyleNameConstr<S, *> -> {
                    val choices = cst.choices(ctx, styling, style)
                    forEachRelevantSetting(cst, ignoreSettings) { st ->
                        val refs = st.extractSubjects(style)
                        refs.forEachIndexed { idx, ref ->
                            val refUnavailable = choices.none { choice ->
                                (!cst.clustering || choice.name != (style as ListedStyle).name) && choice.name == ref
                            }
                            if (refUnavailable) {
                                val msg =
                                    if (st is ListStyleSetting) l10n("project.styling.constr.styles")
                                    else l10n("project.styling.constr.style")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                        if (cst.clustering) {
                            // When the clustering flag is set, check that the constraint is configured as is expected.
                            // We only do these checks now as specifically the second one is only relevant if there is
                            // at least one effective setting.
                            check(cst.styleClass == style.javaClass)
                            check(choices.isEmpty() || choices.any { choice -> choice === style })
                            // Note: The emptiness check is a small performance improvement for the most likely case.
                            if (refs.isNotEmpty())
                                for (choice in choices)
                                    if (choice.name in refs) {
                                        val refsOfChoice = st.extractSubjects(style.javaClass.cast(choice))
                                        if ((style as ListedStyle).name !in refsOfChoice ||
                                            refs.any { ref -> ref != choice.name && ref !in refsOfChoice }
                                        ) {
                                            val leafSubjectIndex = refs.indexOf(choice.name)
                                            val msg = l10n("project.styling.constr.missingStyleReference", choice.name)
                                            log(rootStyle, style, st, leafSubjectIndex, cst.severity, msg)
                                        }
                                    }
                        }
                    }
                }
                is ColorConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, color ->
                        if (!cst.allowAlpha && color.alpha != 255)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.colorAlpha"))
                    }
                is ResolutionConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, resolution ->
                        if (resolution.run { widthPx <= 0 || heightPx <= 0 }) {
                            val msg = l10n("project.styling.constr.negativeResolution")
                            log(rootStyle, style, st, idx, cst.severity, msg)
                        } else {
                            val aspectRatio = resolution.widthPx.toDouble() / resolution.heightPx
                            val aspectRatioLimit = 32
                            if (aspectRatio !in 1.0 / aspectRatioLimit..aspectRatioLimit / 1.0) {
                                val msg = l10n("project.styling.constr.extremeAspectRatio", aspectRatioLimit)
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                    }
                is FPSConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, fps ->
                        if (fps.run { numerator <= 0 || denominator <= 0 || !frac.isFinite() })
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.fps"))
                    }
                is FontNameConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, fontName ->
                        if (ctx.resolveFont(fontName) == null)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.font"))
                    }
                is FontFeatureConstr -> {
                    val availableTags = cst.getAvailableTags(ctx, styling, style)
                    forEachRelevantSetting(cst, ignoreSettings) { st ->
                        val remainingTags = HashSet(availableTags)
                        st.extractSubjects(style).forEachIndexed { idx, feat ->
                            if (feat.tag !in availableTags) {
                                val msg = l10n("project.styling.constr.unsupportedFontFeatTag")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            } else if (!remainingTags.remove(feat.tag)) {
                                val msg = l10n("project.styling.constr.duplicateFontFeatTag")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                            if (feat.value < 0) {
                                val msg = l10n("project.styling.constr.fontFeatValue")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                    }
                }
                is JudgeConstr ->
                    if (!cst.judge(ctx, styling, style))
                        for ((idx, setting) in cst.settings.filter { it !in ignoreSettings }.withIndex())
                            log(rootStyle, style, setting, -1, cst.severity, if (idx == 0) cst.getMsg() else null)
                is MinSizeConstr ->
                    forEachRelevantSetting(cst, ignoreSettings) { st ->
                        if (st.extractSubjects(style).size < cst.minSize) {
                            val msg = l10n("project.styling.constr.minSize", cst.minSize)
                            log(rootStyle, style, st, -1, cst.severity, msg)
                        }
                    }
                is SiblingOrdinalConstr -> {
                    val largestOrdinal = siblings.size
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, ord ->
                        if (ord !in 1..largestOrdinal) {
                            val msg = l10n("project.styling.constr.siblingOrdinalOutOfBounds", largestOrdinal)
                            log(rootStyle, style, st, idx, cst.severity, msg)
                        } else if (!cst.permitSibling(ctx, styling, style, styleIdx + 1, siblings[ord - 1], ord)) {
                            val msg = l10n("project.styling.constr.siblingOrdinalIllegal")
                            log(rootStyle, style, st, idx, cst.severity, msg)
                        }
                    }
                }
            }

        for (setting in getStyleSettings(style.javaClass))
            if (NestedStyle::class.java.isAssignableFrom(setting.type) && setting !in ignoreSettings) {
                val nestedStyles = setting.extractSubjects(style).requireIsInstance<NestedStyle>()
                for ((idx, nestedStyle) in nestedStyles.withIndex())
                    verifyStyle(rootStyle, nestedStyle, idx, nestedStyles)
            }
    }

    verifyStyle(styling.global, styling.global)
    for (styleClass in ListedStyle.CLASSES)
        for (style in styling.getListedStyles(styleClass))
            verifyStyle(style, style)

    return violations
}

private inline fun <S : Style, SS : StyleSetting<S, SUBJ>, SUBJ : Any> forEachRelevantSetting(
    constraint: StyleConstraint<S, SS>,
    ignoreSettings: Set<StyleSetting<*, *>>,
    action: (SS) -> Unit
) {
    for (setting in constraint.settings)
        if (setting !in ignoreSettings)
            action(setting)
}

private inline fun <S : Style, SS : StyleSetting<S, SUBJ>, SUBJ : Any> S.forEachRelevantSubject(
    constraint: StyleConstraint<S, SS>,
    ignoreSettings: Set<StyleSetting<*, *>>,
    action: (SS, idx: Int, value: SUBJ) -> Unit
) {
    for (setting in constraint.settings)
        if (setting !in ignoreSettings)
            setting.extractSubjects(this).forEachIndexed { idx, subject -> action(setting, idx, subject) }
}

private fun combineNumberRestrictions(minRestr: String?, maxRestr: String?): String =
    when {
        minRestr != null && maxRestr != null -> " $minRestr & $maxRestr"
        minRestr != null -> " $minRestr"
        maxRestr != null -> " $maxRestr"
        else -> ""
    }


private fun msg(key: String): () -> String = { l10n(key) }
private fun msg(key: String, vararg args: Any?): () -> String = { l10n(key, *args) }
