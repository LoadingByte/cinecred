package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.CAPITAL_SPACING_FONT_FEAT
import com.loadingbyte.cinecred.common.KERNING_FONT_FEAT
import com.loadingbyte.cinecred.common.LIGATURES_FONT_FEATS
import com.loadingbyte.cinecred.common.getSupportedFeatures
import com.loadingbyte.cinecred.project.AppendageVShelve.OVERALL_MIDDLE
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.Effectivity.ALMOST_EFFECTIVE
import com.loadingbyte.cinecred.project.Effectivity.TOTALLY_INEFFECTIVE
import com.loadingbyte.cinecred.project.GridStructure.SQUARE_CELLS
import com.loadingbyte.cinecred.project.HarmonizeExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.HarmonizeExtent.OFF
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleEffectivitySpecs(styleClass: Class<S>): List<StyleEffectivitySpec<S>> = when (styleClass) {
    Global::class.java -> GLOBAL_EFFECTIVITY_SPECS
    PageStyle::class.java -> PAGE_STYLE_EFFECTIVITY_SPECS
    ContentStyle::class.java -> CONTENT_STYLE_EFFECTIVITY_SPECS
    LetterStyle::class.java -> LETTER_STYLE_EFFECTIVITY_SPECS
    Layer::class.java -> LAYER_EFFECTIVITY_SPECS
    TransitionStyle::class.java -> emptyList()
    PictureStyle::class.java -> PICTURE_STYLE_EFFECTIVITY_SPECS
    TapeStyle::class.java -> TAPE_STYLE_EFFECTIVITY_SPECS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleEffectivitySpec<S>>


private val GLOBAL_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<Global>> = emptyList()


private val PAGE_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<PageStyle>> = listOf(
    // The next two specs are for phasing out the deprecated page style settings.
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(),
        isTotallyIneffective = { _, style -> !style.scrollMeltWithPrev }
    ),
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithNext.st(),
        isTotallyIneffective = { _, style -> !style.scrollMeltWithNext }
    ),
    // From here on, the regular specs start.
    StyleEffectivitySpec(
        PageStyle::cardRuntimeFrames.st(),
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeInTransitionStyleName.st(),
        PageStyle::cardFadeOutFrames.st(), PageStyle::cardFadeOutTransitionStyleName.st(),
        isTotallyIneffective = { _, style -> style.behavior != CARD }
    ),
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st(), PageStyle::scrollPxPerFrame.st(),
        PageStyle::scrollRuntimeFrames.st(),
        isTotallyIneffective = { _, style -> style.behavior != SCROLL }
    ),
    StyleEffectivitySpec(
        PageStyle::subsequentGapFrames.st(),
        isAlmostEffective = { _, style -> style.behavior == SCROLL && style.scrollMeltWithNext }
    ),
    StyleEffectivitySpec(
        PageStyle::cardFadeInTransitionStyleName.st(),
        isAlmostEffective = { _, style -> style.cardFadeInFrames == 0 }
    ),
    StyleEffectivitySpec(
        PageStyle::cardFadeOutTransitionStyleName.st(),
        isAlmostEffective = { _, style -> style.cardFadeOutFrames == 0 }
    )
)


private val CONTENT_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<ContentStyle>> = listOf(
    StyleEffectivitySpec(
        ContentStyle::sort.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout.let { it != GRID && it != FLOW } }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridCols.st(),
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridFillingBalanced.st(), ContentStyle::gridStructure.st(),
        ContentStyle::gridForceColWidthPx.st(), ContentStyle::gridHarmonizeColWidths.st(),
        ContentStyle::gridForceRowHeightPx.st(), ContentStyle::gridHarmonizeRowHeight.st(),
        ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(), ContentStyle::gridHarmonizeColUnderoccupancy.st(),
        ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(), ContentStyle::gridCellHJustifyPerCol.st(),
        ContentStyle::gridCellVJustify.st(), ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != GRID }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowSquareCells.st(),
        ContentStyle::flowForceCellWidthPx.st(), ContentStyle::flowForceCellHeightPx.st(),
        ContentStyle::flowHarmonizeCellWidth.st(), ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(),
        ContentStyle::flowHarmonizeCellHeight.st(), ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(),
        ContentStyle::flowCellHJustify.st(), ContentStyle::flowCellVJustify.st(), ContentStyle::flowLineWidthPx.st(),
        ContentStyle::flowLineGapPx.st(), ContentStyle::flowHGapPx.st(), ContentStyle::flowSeparator.st(),
        ContentStyle::flowSeparatorLetterStyleName.st(), ContentStyle::flowSeparatorVJustify.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != FLOW }
    ),
    StyleEffectivitySpec(
        ContentStyle::paragraphsLineHJustify.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != PARAGRAPHS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headLetterStyleName.st(), ContentStyle::headForceWidthPx.st(),
        ContentStyle::headHarmonizeWidth.st(), ContentStyle::headHarmonizeWidthAcrossStyles.st(),
        ContentStyle::headHJustify.st(), ContentStyle::headVShelve.st(), ContentStyle::headVJustify.st(),
        ContentStyle::headGapPx.st(), ContentStyle::headLeader.st(),
        isTotallyIneffective = { _, style -> !style.hasHead }
    ),
    StyleEffectivitySpec(
        ContentStyle::headLeaderLetterStyleName.st(), ContentStyle::headLeaderHJustify.st(),
        ContentStyle::headLeaderVJustify.st(), ContentStyle::headLeaderMarginLeftPx.st(),
        ContentStyle::headLeaderMarginRightPx.st(), ContentStyle::headLeaderSpacingPx.st(),
        isTotallyIneffective = { _, style ->
            style.blockOrientation != HORIZONTAL || !style.hasHead || style.headLeader.isBlank()
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailForceWidthPx.st(),
        ContentStyle::tailHarmonizeWidth.st(), ContentStyle::tailHarmonizeWidthAcrossStyles.st(),
        ContentStyle::tailHJustify.st(), ContentStyle::tailVShelve.st(), ContentStyle::tailVJustify.st(),
        ContentStyle::tailGapPx.st(), ContentStyle::tailLeader.st(),
        isTotallyIneffective = { _, style -> !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLeaderLetterStyleName.st(), ContentStyle::tailLeaderHJustify.st(),
        ContentStyle::tailLeaderVJustify.st(), ContentStyle::tailLeaderMarginLeftPx.st(),
        ContentStyle::tailLeaderMarginRightPx.st(), ContentStyle::tailLeaderSpacingPx.st(),
        isTotallyIneffective = { _, style ->
            style.blockOrientation != HORIZONTAL || !style.hasTail || style.tailLeader.isBlank()
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.gridHarmonizeColWidths != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeColUnderoccupancy.st(),
        isTotallyIneffective = { _, style ->
            style.gridHarmonizeColWidths != ACROSS_BLOCKS || style.gridHarmonizeColWidthsAcrossStyles.isEmpty()
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.gridHarmonizeRowHeight != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.flowHarmonizeCellWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.flowHarmonizeCellHeight != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headHarmonizeWidthAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.headHarmonizeWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailHarmonizeWidthAcrossStyles.st(),
        isTotallyIneffective = { _, style -> style.tailHarmonizeWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::blockOrientation.st(),
        isAlmostEffective = { _, style -> !style.hasHead && !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridFillingBalanced.st(),
        isAlmostEffective = { _, style -> style.gridCols < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeColWidths.st(), ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(),
        ContentStyle::gridHarmonizeColUnderoccupancy.st(),
        isAlmostEffective = { _, style -> style.gridForceColWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeColUnderoccupancy.st(),
        isAlmostEffective = { styling, style ->
            styling!!.contentStyles.all { o ->
                (o.name != style.name /* for duplicate names */ && o.name !in style.gridHarmonizeColWidthsAcrossStyles) ||
                        style.gridCols >= o.gridCols
            }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridHarmonizeRowHeight.st(), ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(),
        isAlmostEffective = { _, style -> style.gridForceRowHeightPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridCellVJustify.st(),
        isAlmostEffective = { _, style ->
            style.gridCols < 2 && style.gridStructure != SQUARE_CELLS &&
                    !style.gridForceRowHeightPx.isActive && style.gridHarmonizeRowHeight == OFF
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { _, style -> style.gridCols < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowHarmonizeCellWidth.st(), ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(),
        isAlmostEffective = { _, style -> style.flowForceCellWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowHarmonizeCellHeight.st(), ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(),
        isAlmostEffective = { _, style -> style.flowForceCellHeightPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowCellHJustify.st(),
        isAlmostEffective = { _, style ->
            !style.flowSquareCells && !style.flowForceCellWidthPx.isActive && style.flowHarmonizeCellWidth == OFF
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowSeparatorLetterStyleName.st(), ContentStyle::flowSeparatorVJustify.st(),
        isAlmostEffective = { _, style -> style.flowSeparator.isBlank() }
    ),
    StyleEffectivitySpec(
        ContentStyle::headForceWidthPx.st(), ContentStyle::headVShelve.st(), ContentStyle::headLeader.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::headHarmonizeWidth.st(), ContentStyle::headHarmonizeWidthAcrossStyles.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL || style.headForceWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::headHJustify.st(),
        isAlmostEffective = { _, style ->
            style.blockOrientation != VERTICAL && !style.headForceWidthPx.isActive &&
                    style.headHarmonizeWidth != ACROSS_BLOCKS
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::headVJustify.st(), ContentStyle::headLeaderVJustify.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL || style.headVShelve == OVERALL_MIDDLE }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailForceWidthPx.st(), ContentStyle::tailVShelve.st(), ContentStyle::tailLeader.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailHarmonizeWidth.st(), ContentStyle::tailHarmonizeWidthAcrossStyles.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL || style.tailForceWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailHJustify.st(),
        isAlmostEffective = { _, style ->
            style.blockOrientation != VERTICAL && !style.tailForceWidthPx.isActive &&
                    style.tailHarmonizeWidth != ACROSS_BLOCKS
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailVJustify.st(), ContentStyle::tailLeaderVJustify.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL || style.tailVShelve == OVERALL_MIDDLE }
    )
)


private val LETTER_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<LetterStyle>> = listOf(
    StyleEffectivitySpec(
        LetterStyle::layers.st(),
        isTotallyIneffective = { _, style -> style.inheritLayersFromStyle.isActive }
    ),
    StyleEffectivitySpec(
        LetterStyle::kerning.st(),
        isAlmostEffective = { _, style -> supportsNot(style, KERNING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::ligatures.st(),
        isAlmostEffective = { _, style -> LIGATURES_FONT_FEATS.all { supportsNot(style, it) } }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { _, style -> supportsNot(style, CAPITAL_SPACING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { _, style -> !style.uppercase }
    ),
    StyleEffectivitySpec(
        LetterStyle::superscriptScaling.st(), LetterStyle::superscriptHOffsetRfh.st(),
        LetterStyle::superscriptVOffsetRfh.st(),
        isAlmostEffective = { _, style -> style.superscript != Superscript.CUSTOM }
    )
)

private fun supportsNot(style: LetterStyle, feat: String): Boolean {
    val font = style.font.font
    return if (font == null) true else feat !in font.getSupportedFeatures()
}


private val LAYER_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<Layer>> = listOf(
    StyleEffectivitySpec(
        Layer::color1.st(),
        isTotallyIneffective = { _, style -> style.coloring == LayerColoring.OFF }
    ),
    StyleEffectivitySpec(
        Layer::color2.st(), Layer::gradientAngleDeg.st(), Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st(),
        isTotallyIneffective = { _, style -> style.coloring != LayerColoring.GRADIENT }
    ),
    StyleEffectivitySpec(
        Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st(),
        Layer::stripeDashPatternRfh.st(),
        isTotallyIneffective = { _, style -> style.shape != LayerShape.STRIPE }
    ),
    StyleEffectivitySpec(
        Layer::cloneLayers.st(), Layer::anchorSiblingLayer.st(),
        isTotallyIneffective = { _, style -> style.shape != LayerShape.CLONE }
    ),
    StyleEffectivitySpec(
        Layer::dilationRfh.st(), Layer::dilationJoin.st(),
        isTotallyIneffective = { _, style -> style.shape != LayerShape.TEXT && style.shape != LayerShape.CLONE }
    ),
    StyleEffectivitySpec(
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(),
        isTotallyIneffective = { _, style -> style.offsetCoordinateSystem != CoordinateSystem.CARTESIAN }
    ),
    StyleEffectivitySpec(
        Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
        isTotallyIneffective = { _, style -> style.offsetCoordinateSystem != CoordinateSystem.POLAR }
    ),
    StyleEffectivitySpec(
        Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        isAlmostEffective = { _, style -> style.stripePreset != StripePreset.CUSTOM }
    ),
    StyleEffectivitySpec(
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        isAlmostEffective = { _, style -> style.stripePreset != StripePreset.BACKGROUND }
    ),
    StyleEffectivitySpec(
        Layer::stripeCornerRadiusRfh.st(),
        isAlmostEffective = { _, style -> style.stripeCornerJoin == LineJoin.MITER }
    ),
    StyleEffectivitySpec(
        Layer::dilationJoin.st(),
        isAlmostEffective = { _, style -> style.dilationRfh == 0.0 }
    ),
    StyleEffectivitySpec(
        Layer::contourThicknessRfh.st(), Layer::contourJoin.st(),
        isAlmostEffective = { _, style -> !style.contour }
    ),
    StyleEffectivitySpec(
        Layer::anchor.st(), Layer::anchorSiblingLayer.st(),
        isAlmostEffective = { _, style ->
            style.hScaling == 1.0 && style.vScaling == 1.0 && style.hShearing == 0.0 && style.vShearing == 0.0
        }
    ),
    StyleEffectivitySpec(
        Layer::anchorSiblingLayer.st(),
        isAlmostEffective = { _, style -> style.anchor != LayerAnchor.SIBLING }
    ),
    StyleEffectivitySpec(
        Layer::clearingRfh.st(),
        isAlmostEffective = { _, style -> style.clearingLayers.isEmpty() }
    ),
    StyleEffectivitySpec(
        Layer::clearingJoin.st(),
        isAlmostEffective = { _, style -> style.clearingLayers.isEmpty() || style.clearingRfh == 0.0 }
    )
)


private val PICTURE_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<PictureStyle>> = listOf(
    StyleEffectivitySpec(
        PictureStyle::cropBlankSpace.st(),
        isAlmostEffective = { _, style -> style.picture.loader.let { it == null || it.isRaster } }
    )
)


private val TAPE_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<TapeStyle>> = listOf(
    StyleEffectivitySpec(
        TapeStyle::fadeInTransitionStyleName.st(),
        isAlmostEffective = { _, style -> style.fadeInFrames == 0 }
    ),
    StyleEffectivitySpec(
        TapeStyle::fadeOutTransitionStyleName.st(),
        isAlmostEffective = { _, style -> style.fadeOutFrames == 0 }
    )
)


class StyleEffectivitySpec<S : Style>(
    vararg settings: StyleSetting<S, *>,
    val isAlmostEffective: ((Styling?, S) -> Boolean)? = null,
    val isTotallyIneffective: ((Styling?, S) -> Boolean)? = null
) {
    val settings: List<StyleSetting<S, *>> = settings.toList()

    init {
        require(isAlmostEffective != null || isTotallyIneffective != null)
    }
}


enum class Effectivity { TOTALLY_INEFFECTIVE, ALMOST_EFFECTIVE, EFFECTIVE }

fun <S : Style> findIneffectiveSettings(styling: Styling, style: S): Map<StyleSetting<S, *>, Effectivity> {
    val result = HashMap<StyleSetting<S, *>, Effectivity>()

    if (style is PopupStyle)
        @Suppress("UNCHECKED_CAST")
        result[PopupStyle::volatile.st() as StyleSetting<S, *>] = TOTALLY_INEFFECTIVE
    if (style is LayerStyle)
        @Suppress("UNCHECKED_CAST")
        result[LayerStyle::collapsed.st() as StyleSetting<S, *>] = TOTALLY_INEFFECTIVE

    fun mark(settings: List<StyleSetting<S, *>>, tier: Effectivity) {
        for (setting in settings)
            result[setting] = minOf(result[setting] ?: tier, tier)
    }

    for (spec in getStyleEffectivitySpecs(style.javaClass)) {
        spec.isAlmostEffective?.let { if (it(styling, style)) mark(spec.settings, ALMOST_EFFECTIVE) }
        spec.isTotallyIneffective?.let { if (it(styling, style)) mark(spec.settings, TOTALLY_INEFFECTIVE) }
    }

    return result
}

fun <S : Style> isEffective(styling: Styling, style: S, setting: StyleSetting<S, *>): Boolean =
    getStyleEffectivitySpecs(style.javaClass).none { spec ->
        setting in spec.settings &&
                (spec.isAlmostEffective.let { it != null && it(styling, style) } ||
                        spec.isTotallyIneffective.let { it != null && it(styling, style) })
    } && isImplicitlyEffective(setting)

/** Throws a [NullPointerException] if invoked on a setting where we need a [Styling] to determine its effectivity. */
fun <S : Style> isEffectiveUnsafe(style: S, setting: StyleSetting<S, *>): Boolean =
    getStyleEffectivitySpecs(style.javaClass).none { spec ->
        setting in spec.settings &&
                (spec.isAlmostEffective.let { it != null && it(null, style) } ||
                        spec.isTotallyIneffective.let { it != null && it(null, style) })
    } && isImplicitlyEffective(setting)

private fun isImplicitlyEffective(setting: StyleSetting<*, *>): Boolean =
    setting != PopupStyle::volatile.st() && setting != LayerStyle::collapsed.st()
