package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.Transition
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.BodyLayout.FLOW
import com.loadingbyte.cinecred.project.BodyLayout.GRID
import com.loadingbyte.cinecred.project.GridStructure.FREE
import com.loadingbyte.cinecred.project.GridStructure.SQUARE_CELLS
import com.loadingbyte.cinecred.project.HarmonizeExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.HarmonizeExtent.OFF
import com.loadingbyte.cinecred.project.SpineAttachment.*
import java.util.*
import kotlin.math.floor


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleConstraints(styleClass: Class<S>): List<StyleConstraint<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_CONSTRAINTS
    PageStyle::class.java -> PAGE_STYLE_CONSTRAINTS
    ContentStyle::class.java -> CONTENT_STYLE_CONSTRAINTS
    LetterStyle::class.java -> LETTER_STYLE_CONSTRAINTS
    Layer::class.java -> LAYER_CONSTRAINTS
    TransitionStyle::class.java -> TRANSITION_STYLE_CONSTRAINTS
    PictureStyle::class.java -> PICTURE_STYLE_CONSTRAINTS
    TapeStyle::class.java -> TAPE_STYLE_CONSTRAINTS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleConstraint<S, *>>


private val GLOBAL_CONSTRAINTS: List<StyleConstraint<Global, *>> = listOf(
    ResolutionConstr(ERROR, Global::resolution.st()),
    FPSConstr(ERROR, Global::fps.st()),
    DynChoiceConstr(ERROR, Global::timecodeFormat.st()) { _, global ->
        global.fps.canonicalTimecodeFormats
    },
    IntConstr(ERROR, Global::runtimeFrames.st(), min = 1),
    ColorConstr(ERROR, Global::grounding.st(), allowAlpha = false),
    DoubleConstr(ERROR, Global::unitVGapPx.st(), min = 0.0, minInclusive = false)
)


private val PAGE_STYLE_CONSTRAINTS: List<StyleConstraint<PageStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), PageStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), PageStyle::name.st()) { styling, style ->
        styling.pageStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    IntConstr(ERROR, PageStyle::cardRuntimeFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeInFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeOutFrames.st(), min = 0),
    StyleNameConstr(
        WARN, PageStyle::cardFadeInTransitionStyleName.st(), PageStyle::cardFadeOutTransitionStyleName.st(),
        styleClass = TransitionStyle::class.java,
        choices = { styling, _ -> styling.transitionStyles }
    ),
    DoubleConstr(ERROR, PageStyle::scrollPxPerFrame.st(), min = 0.0, minInclusive = false),
    JudgeConstr(INFO, msg("project.styling.constr.fractionalScrollPx"), PageStyle::scrollPxPerFrame.st()) { _, style ->
        val value = style.scrollPxPerFrame
        floor(value) == value
    },
    IntConstr(ERROR, PageStyle::scrollRuntimeFrames.st(), min = 1)
)


private val CONTENT_STYLE_CONSTRAINTS: List<StyleConstraint<ContentStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), ContentStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), ContentStyle::name.st()) { styling, style ->
        styling.contentStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    DynChoiceConstr(WARN, ContentStyle::spineAttachment.st()) { _, style ->
        when (style.blockOrientation) {
            VERTICAL -> EnumSet.of(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
            HORIZONTAL -> when {
                !style.hasHead && !style.hasTail -> EnumSet.of(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
                style.hasHead && !style.hasTail -> EnumSet.of(
                    OVERALL_CENTER,
                    HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT, HEAD_GAP_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT
                )
                !style.hasHead && style.hasTail -> EnumSet.of(
                    OVERALL_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT,
                    TAIL_GAP_CENTER, TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT
                )
                else -> EnumSet.allOf(SpineAttachment::class.java)
            }
        }
    },
    DoubleConstr(ERROR, ContentStyle::vMarginTopPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::vMarginBottomPx.st(), min = 0.0),
    FixedChoiceConstr(
        WARN, ContentStyle::gridHarmonizeColWidths.st(), ContentStyle::headHarmonizeWidth.st(),
        ContentStyle::tailHarmonizeWidth.st(),
        choices = EnumSet.of(OFF, ACROSS_BLOCKS)
    ),
    StyleNameConstr(
        WARN, ContentStyle::bodyLetterStyleName.st(), ContentStyle::flowSeparatorLetterStyleName.st(),
        ContentStyle::headLetterStyleName.st(), ContentStyle::headLeaderLetterStyleName.st(),
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailLeaderLetterStyleName.st(),
        styleClass = LetterStyle::class.java,
        choices = { styling, _ -> styling.letterStyles }
    ),
    IntConstr(ERROR, ContentStyle::gridCols.st(), min = 1, max = 100),
    DynChoiceConstr(WARN, ContentStyle::gridStructure.st()) { _, style ->
        val forceColWidth = style.gridForceColWidthPx.isActive
        when {
            forceColWidth && style.gridForceRowHeightPx.isActive -> EnumSet.of(FREE)
            forceColWidth || style.gridCols < 2 -> EnumSet.of(FREE, SQUARE_CELLS)
            else -> EnumSet.allOf(GridStructure::class.java)
        }
    },
    DoubleConstr(ERROR, ContentStyle::gridForceColWidthPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::gridForceRowHeightPx.st(), min = 0.0),
    StyleNameConstr(
        ERROR, ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.bodyLayout == GRID && !o.gridForceColWidthPx.isActive && o.gridHarmonizeColWidths == ACROSS_BLOCKS
            }
        }
    ),
    DynChoiceConstr(WARN, ContentStyle::gridHarmonizeRowHeight.st()) { _, style ->
        if (style.gridStructure == SQUARE_CELLS) EnumSet.of(OFF, ACROSS_BLOCKS)
        else EnumSet.allOf(HarmonizeExtent::class.java)
    },
    StyleNameConstr(
        ERROR, ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.bodyLayout == GRID && !o.gridForceRowHeightPx.isActive && o.gridHarmonizeRowHeight == ACROSS_BLOCKS
            }
        }
    ),
    DynSizeConstr(ERROR, ContentStyle::gridCellHJustifyPerCol.st(), size = { _, style -> style.gridCols }),
    DoubleConstr(ERROR, ContentStyle::gridRowGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::gridColGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::flowForceCellWidthPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::flowForceCellHeightPx.st(), min = 0.0),
    DynChoiceConstr(
        WARN, ContentStyle::flowHarmonizeCellWidth.st(), ContentStyle::flowHarmonizeCellHeight.st()
    ) { _, style ->
        if (style.flowSquareCells) EnumSet.of(OFF, ACROSS_BLOCKS)
        else EnumSet.allOf(HarmonizeExtent::class.java)
    },
    StyleNameConstr(
        ERROR, ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.bodyLayout == FLOW && !o.flowForceCellWidthPx.isActive && o.flowHarmonizeCellWidth == ACROSS_BLOCKS
            }
        }
    ),
    StyleNameConstr(
        ERROR, ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.bodyLayout == FLOW && !o.flowForceCellHeightPx.isActive && o.flowHarmonizeCellHeight == ACROSS_BLOCKS
            }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::flowLineWidthPx.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, ContentStyle::flowLineGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::flowHGapPx.st(), min = 0.0),
    StyledStringConstr(WARN, ContentStyle::flowSeparator.st()) { _, style ->
        style.flowSeparatorLetterStyleName.orElse { style.bodyLetterStyleName }
    },
    DoubleConstr(ERROR, ContentStyle::paragraphsLineWidthPx.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, ContentStyle::paragraphsParaGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::paragraphsLineGapPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::headForceWidthPx.st(), min = 0.0),
    StyleNameConstr(
        ERROR, ContentStyle::headHarmonizeWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.blockOrientation == HORIZONTAL && !o.headForceWidthPx.isActive && o.headHarmonizeWidth == ACROSS_BLOCKS
            }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::headGapPx.st(), min = 0.0),
    StyledStringConstr(WARN, ContentStyle::headLeader.st()) { _, style ->
        style.headLeaderLetterStyleName.orElse { style.headLetterStyleName }
    },
    DoubleConstr(ERROR, ContentStyle::headLeaderMarginLeftPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::headLeaderMarginRightPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::headLeaderSpacingPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::tailForceWidthPx.st(), min = 0.0),
    StyleNameConstr(
        ERROR, ContentStyle::tailHarmonizeWidthAcrossStyles.st(),
        styleClass = ContentStyle::class.java, clustering = true,
        choices = { styling, _ ->
            styling.contentStyles.filter { o ->
                o.blockOrientation == HORIZONTAL && !o.tailForceWidthPx.isActive && o.tailHarmonizeWidth == ACROSS_BLOCKS
            }
        }
    ),
    DoubleConstr(ERROR, ContentStyle::tailGapPx.st(), min = 0.0),
    StyledStringConstr(WARN, ContentStyle::tailLeader.st()) { _, style ->
        style.tailLeaderLetterStyleName.orElse { style.tailLetterStyleName }
    },
    DoubleConstr(ERROR, ContentStyle::tailLeaderMarginLeftPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::tailLeaderMarginRightPx.st(), min = 0.0),
    DoubleConstr(ERROR, ContentStyle::tailLeaderSpacingPx.st(), min = 0.0)
)


private val LETTER_STYLE_CONSTRAINTS: List<StyleConstraint<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), LetterStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), LetterStyle::name.st()) { styling, style ->
        styling.letterStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    FontConstr(WARN, LetterStyle::font.st()),
    DoubleConstr(ERROR, LetterStyle::heightPx.st(), min = 1.0),
    JudgeConstr(
        WARN, msg("project.styling.constr.excessiveLeading"),
        LetterStyle::heightPx.st(), LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st()
    ) { _, style ->
        // The formula is written this way to exactly match the computation in StyledStringFormatter.
        style.heightPx - style.leadingTopRh * style.heightPx - style.leadingBottomRh * style.heightPx >= 1.0
    },
    JudgeConstr(INFO, msg("project.styling.constr.fakeSmallCaps"), LetterStyle::smallCaps.st()) { _, style ->
        val font = style.font.font ?: return@JudgeConstr true
        when (style.smallCaps) {
            SmallCaps.OFF -> true
            SmallCaps.SMALL_CAPS -> SMALL_CAPS_FONT_FEAT in font.getSupportedFeatures()
            SmallCaps.PETITE_CAPS -> PETITE_CAPS_FONT_FEAT in font.getSupportedFeatures()
        }
    },
    DoubleConstr(ERROR, LetterStyle::superscriptScaling.st(), min = 0.0, minInclusive = false),
    FontFeatureConstr(WARN, LetterStyle::features.st()) { _, style ->
        val font = style.font.font ?: return@FontFeatureConstr Collections.emptySortedSet()
        TreeSet(font.getSupportedFeatures()).apply { removeAll(MANAGED_FONT_FEATS) }
    },
    StyleNameConstr(
        WARN, LetterStyle::inheritLayersFromStyle.st(),
        styleClass = LetterStyle::class.java,
        choices = { styling, _ -> styling.letterStyles.filter { o -> !o.inheritLayersFromStyle.isActive } }
    ),
    // This constraint is imposed upon us by Java. Source: sun.font.AttributeValues.i_validate()
    DoubleConstr(ERROR, LetterStyle::hScaling.st(), min = 0.5, max = 10.0, maxInclusive = false)
)


private const val BLUR_RADIUS_LIMIT = 200

private val LAYER_CONSTRAINTS: List<StyleConstraint<Layer, *>> = listOf(
    JudgeConstr(
        WARN, msg("project.styling.constr.unusedHiddenLayer"),
        Layer::coloring.st(), Layer::color1.st(), Layer::color2.st()
    ) { styling, style ->
        val visible = when (style.coloring) {
            LayerColoring.OFF -> false
            LayerColoring.PLAIN -> style.color1.a != 0f
            LayerColoring.GRADIENT -> style.color1.a != 0f || style.color2.a != 0f
        }
        if (visible)
            return@JudgeConstr true
        val layers = (styling.getParentStyle(style) as LetterStyle).layers
        val ord = layers.indexOfFirst { it === style } + 1
        layers.any { layer ->
            layer.shape == LayerShape.CLONE && layer.cloneLayers.contains(ord) || layer.clearingLayers.contains(ord)
        }
    },
    DoubleConstr(ERROR, Layer::gradientAngleDeg.st(), min = -90.0, max = 90.0),
    DoubleConstr(ERROR, Layer::gradientExtentRfh.st(), min = 0.0),
    DoubleConstr(ERROR, Layer::stripeHeightRfh.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, Layer::stripeCornerRadiusRfh.st(), min = 0.0, minInclusive = false),
    DoubleConstr(ERROR, Layer::stripeDashPatternRfh.st(), min = 0.0, minInclusive = false),
    JudgeConstr(WARN, msg("project.styling.constr.cycleBackToSelf"), Layer::cloneLayers.st()) { styling, style ->
        if (style.shape != LayerShape.CLONE || style.cloneLayers.isEmpty())
            return@JudgeConstr true
        val layers = (styling.getParentStyle(style) as LetterStyle).layers
        !canWalkBackToSelf(layers, layers.indexOfFirst { it === style })
    },
    SiblingOrdinalConstr(ERROR, Layer::cloneLayers.st()) { _, _, styleOrdinal, _, siblingOrdinal ->
        siblingOrdinal != styleOrdinal
    },
    MinSizeConstr(WARN, Layer::cloneLayers.st(), minSize = 1),
    DoubleConstr(ERROR, Layer::dilationRfh.st(), min = 0.0),
    DoubleConstr(ERROR, Layer::contourThicknessRfh.st(), min = 0.0, minInclusive = false),
    DynChoiceConstr(WARN, Layer::anchor.st()) { _, style ->
        if (style.shape == LayerShape.CLONE && style.cloneLayers.size >= 2) EnumSet.allOf(LayerAnchor::class.java)
        else EnumSet.of(LayerAnchor.INDIVIDUAL, LayerAnchor.GLOBAL)
    },
    SiblingOrdinalConstr(WARN, Layer::anchorSiblingLayer.st()) { _, style, _, sibling, siblingOrdinal ->
        siblingOrdinal in style.cloneLayers && sibling.shape != LayerShape.CLONE
    },
    JudgeConstr(WARN, msg("project.styling.constr.cycleBackToSelf"), Layer::clearingLayers.st()) { styling, style ->
        if (style.clearingLayers.isEmpty())
            return@JudgeConstr true
        val layers = (styling.getParentStyle(style) as LetterStyle).layers
        !canWalkBackToSelf(layers, layers.indexOfFirst { it === style })
    },
    SiblingOrdinalConstr(ERROR, Layer::clearingLayers.st()) { _, _, styleOrdinal, _, siblingOrdinal ->
        siblingOrdinal != styleOrdinal
    },
    DoubleConstr(ERROR, Layer::clearingRfh.st(), min = 0.0),
    DoubleConstr(ERROR, Layer::blurRadiusRfh.st(), min = 0.0),
    JudgeConstr(
        ERROR, msg("project.styling.constr.excessiveBlurRadius", BLUR_RADIUS_LIMIT),
        Layer::blurRadiusRfh.st()
    ) { styling, style ->
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


private val TRANSITION_STYLE_CONSTRAINTS: List<StyleConstraint<TransitionStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), TransitionStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), TransitionStyle::name.st()) { styling, style ->
        styling.transitionStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    TransitionConstr(ERROR, TransitionStyle::graph.st())
)


private val PICTURE_STYLE_CONSTRAINTS: List<StyleConstraint<PictureStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), PictureStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), PictureStyle::name.st()) { styling, style ->
        styling.pictureStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    PictureConstr(WARN, PictureStyle::picture.st()),
    // To avoid OOM crashes due to absurdly large pictures, limit their size to a reasonable range.
    DoubleConstr(ERROR, PictureStyle::widthPx.st(), min = 0.0, minInclusive = false, max = 10_000.0),
    DoubleConstr(ERROR, PictureStyle::heightPx.st(), min = 0.0, minInclusive = false, max = 10_000.0)
)


private val TAPE_STYLE_CONSTRAINTS: List<StyleConstraint<TapeStyle, *>> = listOf(
    JudgeConstr(WARN, msg("blank"), TapeStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(WARN, msg("project.styling.constr.duplicateStyleName"), TapeStyle::name.st()) { styling, style ->
        styling.tapeStyles.all { o -> o === style || !ROOT_CASE_INSENSITIVE_COLLATOR.equals(o.name, style.name) }
    },
    TapeConstr(WARN, TapeStyle::tape.st()),
    // To avoid OOM crashes due to absurdly large tapes, limit their size to a reasonable range.
    IntConstr(ERROR, TapeStyle::widthPx.st(), min = 1, max = 10_000),
    IntConstr(ERROR, TapeStyle::heightPx.st(), min = 1, max = 10_000),
    TapeSliceConstr(
        WARN, TapeStyle::slice.st(),
        getFPS = { styling, style ->
            val tape = style.tape.tape
            if (tape == null)
                null
            else if (tape.fileSeq)
                styling.global.fps
            else
                try {
                    tape.fps
                } catch (_: IllegalStateException) {
                    null
                }
        },
        getTimecodeFormats = { styling, style ->
            val tape = style.tape.tape
            if (tape == null)
                EnumSet.allOf(TimecodeFormat::class.java)
            else if (tape.fileSeq)
                styling.global.fps.canonicalTimecodeFormats
            else
                try {
                    // See coerceTimecode() in BodyDrawer.kt for reasoning behind these choices.
                    tape.fps?.canonicalTimecodeFormats
                        ?: EnumSet.of(TimecodeFormat.EXACT_FRAMES_IN_SECOND, TimecodeFormat.CLOCK)
                } catch (_: IllegalStateException) {
                    EnumSet.allOf(TimecodeFormat::class.java)
                }
        },
        getRange = { _, style ->
            try {
                style.tape.tape?.availableRange?.run { start..endExclusive }
            } catch (_: IllegalStateException) {
                null
            }
        }
    ),
    IntConstr(ERROR, TapeStyle::leftTemporalMarginFrames.st(), min = 0),
    IntConstr(ERROR, TapeStyle::rightTemporalMarginFrames.st(), min = 0),
    IntConstr(ERROR, TapeStyle::fadeInFrames.st(), min = 0),
    IntConstr(ERROR, TapeStyle::fadeOutFrames.st(), min = 0),
    StyleNameConstr(
        WARN, TapeStyle::fadeInTransitionStyleName.st(), TapeStyle::fadeOutTransitionStyleName.st(),
        styleClass = TransitionStyle::class.java,
        choices = { styling, _ -> styling.transitionStyles }
    )
)


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
    val min: Double? = null,
    val minInclusive: Boolean = true,
    val max: Double? = null,
    val maxInclusive: Boolean = true
) : StyleConstraint<S, StyleSetting<S, Double>>(setting)


class FixedChoiceConstr<S : Style, SUBJ : Enum<SUBJ>>(
    val severity: Severity,
    vararg settings: StyleSetting<S, SUBJ>,
    val choices: EnumSet<SUBJ>
) : StyleConstraint<S, StyleSetting<S, SUBJ>>(*settings)


class DynChoiceConstr<S : Style, SUBJ : Enum<SUBJ>>(
    val severity: Severity,
    vararg settings: StyleSetting<S, SUBJ>,
    val choices: (Styling, S) -> EnumSet<SUBJ>
) : StyleConstraint<S, StyleSetting<S, SUBJ>>(*settings)


class StyleNameConstr<S : Style, R : ListedStyle>(
    val severity: Severity,
    vararg settings: StyleSetting<S, String>,
    val styleClass: Class<R>,
    val clustering: Boolean = false,
    val choices: (Styling, S) -> List<R>
) : StyleConstraint<S, StyleSetting<S, String>>(*settings) {
    init {
        // This is due to limitations in our current implementation of StylingConsistency and StyleUsage.
        require(settings.all { st -> ListedStyle::class.java.isAssignableFrom(st.declaringClass) })
    }
}


class ColorConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Color4f>,
    val allowAlpha: Boolean
) : StyleConstraint<S, StyleSetting<S, Color4f>>(setting)


class ResolutionConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Resolution>
) : StyleConstraint<S, StyleSetting<S, Resolution>>(setting)


class FPSConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FPS>
) : StyleConstraint<S, StyleSetting<S, FPS>>(setting)


class FontConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FontRef>
) : StyleConstraint<S, StyleSetting<S, FontRef>>(setting)


class PictureConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, PictureRef>
) : StyleConstraint<S, StyleSetting<S, PictureRef>>(setting)


class TapeConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, TapeRef>
) : StyleConstraint<S, StyleSetting<S, TapeRef>>(setting)


class FontFeatureConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FontFeature>,
    val getAvailableTags: (Styling, S) -> SequencedSet<String>
) : StyleConstraint<S, StyleSetting<S, FontFeature>>(setting)


class TransitionConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Transition>
) : StyleConstraint<S, StyleSetting<S, Transition>>(setting)


class TapeSliceConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, TapeSlice>,
    val getFPS: (Styling, S) -> FPS?,
    val getTimecodeFormats: (Styling, S) -> EnumSet<TimecodeFormat>,
    val getRange: (Styling, S) -> ClosedRange<Timecode>?
) : StyleConstraint<S, StyleSetting<S, TapeSlice>>(setting)


class StyledStringConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, String>,
    val getLetterStyleName: (Styling, S) -> String
) : StyleConstraint<S, StyleSetting<S, String>>(setting)


class JudgeConstr<S : Style>(
    val severity: Severity,
    val getMsg: () -> String,
    vararg settings: StyleSetting<S, Any>,
    val judge: (Styling, S) -> Boolean
) : StyleConstraint<S, StyleSetting<S, Any>>(*settings)


class MinSizeConstr<S : Style>(
    val severity: Severity,
    setting: ListStyleSetting<S, Any>,
    val minSize: Int
) : StyleConstraint<S, ListStyleSetting<S, Any>>(setting)


class DynSizeConstr<S : Style>(
    val severity: Severity,
    setting: ListStyleSetting<S, Any>,
    val size: (Styling, S) -> Int
) : StyleConstraint<S, ListStyleSetting<S, Any>>(setting)


class SiblingOrdinalConstr<S : NestedStyle>(
    val severity: Severity,
    setting: StyleSetting<S, Int>,
    val permitSibling: (Styling, S, Int, S, Int) -> Boolean
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

fun verifyConstraints(styling: Styling): List<ConstraintViolation> {
    val violations = mutableListOf<ConstraintViolation>()

    fun log(
        rootStyle: Style, leafStyle: Style, leafSetting: StyleSetting<*, *>, leafSubjectIndex: Int,
        severity: Severity, msg: String?
    ) {
        violations.add(ConstraintViolation(rootStyle, leafStyle, leafSetting, leafSubjectIndex, severity, msg))
    }

    fun <S : Style> verifyStyle(rootStyle: Style, style: S, styleIdx: Int = 0, siblings: List<S> = emptyList()) {
        val ignoreSettings = findIneffectiveSettings(styling, style).keys

        for (cst in getStyleConstraints(style.javaClass))
            when (cst) {
                is IntConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        val min = cst.min
                        val max = cst.max
                        if (min != null && value < min)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberGTE", min))
                        if (max != null && value > max)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberLTE", max))
                    }
                is DoubleConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        val min = cst.min
                        val max = cst.max
                        if (!value.isFinite())
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberFinite"))
                        if (min != null && cst.minInclusive && value < min)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberGTE", min))
                        if (min != null && !cst.minInclusive && value <= min)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberGT", min))
                        if (max != null && cst.maxInclusive && value > max)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberLTE", max))
                        if (max != null && !cst.maxInclusive && value >= max)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.numberLT", max))
                    }
                is FixedChoiceConstr<S, *> ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (value !in cst.choices)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.choice"))
                    }
                is DynChoiceConstr<S, *> -> {
                    val choices = cst.choices(styling, style)
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, value ->
                        if (value !in choices)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.choice"))
                    }
                }
                is StyleNameConstr<S, *> -> {
                    val choices = cst.choices(styling, style)
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
                        if (!cst.allowAlpha && color.a != 1f)
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
                                val msg = l10n(
                                    "project.styling.constr.extremeAspectRatio",
                                    "1:$aspectRatioLimit", "$aspectRatioLimit:1"
                                )
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                    }
                is FPSConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, fps ->
                        if (fps.run { numerator <= 0 || denominator <= 0 })
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.fps"))
                    }
                is FontConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, fontRef ->
                        if (fontRef.font == null)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.font"))
                    }
                is PictureConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, picRef ->
                        if (picRef.loader == null)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.picture"))
                    }
                is TapeConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, tapeRef ->
                        val tape = tapeRef.tape
                        if (tape == null)
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.tape"))
                        else try {
                            tape.spec
                        } catch (_: IllegalStateException) {
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.tapeCorrupt"))
                        }
                    }
                is FontFeatureConstr -> {
                    val availableTags = cst.getAvailableTags(styling, style)
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
                is TransitionConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, transition ->
                        if (transition.ctrl1X !in 0.0..1.0 || transition.ctrl1Y !in 0.0..1.0 ||
                            transition.ctrl2X !in 0.0..1.0 || transition.ctrl2Y !in 0.0..1.0
                        )
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.transition"))
                    }
                is TapeSliceConstr -> {
                    val fps = cst.getFPS(styling, style)
                    val formats = cst.getTimecodeFormats(styling, style)
                    val range = cst.getRange(styling, style)
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, sl ->
                        for (tcOpt in arrayOf(sl.inPoint, sl.outPoint)) if (tcOpt.isActive) {
                            val tc = tcOpt.value
                            if (tc.format !in formats) {
                                val msg = l10n("project.styling.constr.timecodeFormatDisallowed")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                            try {
                                if (fps != null) tc.toFrames(fps)
                            } catch (_: IllegalArgumentException) {
                                val msg = l10n("project.styling.constr.timecodeNonexistent")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                            if (range != null && (lessThan(tc, range.start, fps) == true ||
                                        lessThan(range.endInclusive, tc, fps) == true)
                            ) {
                                val msg = l10n("project.styling.constr.timecodeExceedsRange")
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                        if (sl.inPoint.isActive && sl.outPoint.isActive &&
                            lessThan(sl.inPoint.value, sl.outPoint.value, fps) == false
                        )
                            log(rootStyle, style, st, idx, cst.severity, l10n("project.styling.constr.tapeSpanLT"))
                    }
                }
                is StyledStringConstr ->
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, string ->
                        if (string.isNotBlank()) {
                            val letterStyleName = cst.getLetterStyleName(styling, style)
                            val letterStyle = styling.letterStyles.find { l -> l.name == letterStyleName }
                            if (letterStyle != null && format(string, letterStyle, styling).missesGlyphs) {
                                val msg = l10n("project.styling.constr.missingGlyphs", l10nQuoted(letterStyleName))
                                log(rootStyle, style, st, idx, cst.severity, msg)
                            }
                        }
                    }
                is JudgeConstr ->
                    if (!cst.judge(styling, style))
                        for ((idx, setting) in cst.settings.filter { it !in ignoreSettings }.withIndex())
                            log(rootStyle, style, setting, -1, cst.severity, if (idx == 0) cst.getMsg() else null)
                is MinSizeConstr ->
                    forEachRelevantSetting(cst, ignoreSettings) { st ->
                        if (st.extractSubjects(style).size < cst.minSize) {
                            val msg = l10n("project.styling.constr.minSize", cst.minSize)
                            log(rootStyle, style, st, -1, cst.severity, msg)
                        }
                    }
                is DynSizeConstr -> {
                    val size = cst.size(styling, style)
                    forEachRelevantSetting(cst, ignoreSettings) { st ->
                        if (st.extractSubjects(style).size != size)
                            log(rootStyle, style, st, -1, cst.severity, l10n("project.styling.constr.size", size))
                    }
                }
                is SiblingOrdinalConstr -> {
                    val largestOrdinal = siblings.size
                    style.forEachRelevantSubject(cst, ignoreSettings) { st, idx, ord ->
                        if (ord !in 1..largestOrdinal) {
                            val msg = l10n("project.styling.constr.siblingOrdinalOutOfBounds", largestOrdinal)
                            log(rootStyle, style, st, idx, cst.severity, msg)
                        } else if (!cst.permitSibling(styling, style, styleIdx + 1, siblings[ord - 1], ord)) {
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

private fun lessThan(tc1: Timecode, tc2: Timecode, fps: FPS?): Boolean? =
    if (tc1 is Timecode.Frames && tc2 is Timecode.Frames || tc1 is Timecode.Clock && tc2 is Timecode.Clock)
        tc1 < tc2
    else
        try {
            if (fps == null) null else tc1.toClock(fps) < tc2.toClock(fps)
        } catch (_: IllegalArgumentException) {
            null
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


private fun msg(key: String): () -> String = { l10n(key) }
private fun msg(key: String, vararg args: Any): () -> String = { l10n(key, *args) }
