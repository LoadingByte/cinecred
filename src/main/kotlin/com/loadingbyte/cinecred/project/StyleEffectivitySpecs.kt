package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.CAPITAL_SPACING_FONT_FEAT
import com.loadingbyte.cinecred.common.KERNING_FONT_FEAT
import com.loadingbyte.cinecred.common.LIGATURES_FONT_FEATS
import com.loadingbyte.cinecred.common.getSupportedFeatures
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.Effectivity.ALMOST_EFFECTIVE
import com.loadingbyte.cinecred.project.Effectivity.TOTALLY_INEFFECTIVE
import com.loadingbyte.cinecred.project.GridStructure.SQUARE_CELLS
import com.loadingbyte.cinecred.project.MatchExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.MatchExtent.OFF
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleEffectivitySpecs(styleClass: Class<S>): List<StyleEffectivitySpec<S>> = when (styleClass) {
    Global::class.java -> GLOBAL_EFFECTIVITY_SPECS
    PageStyle::class.java -> PAGE_STYLE_EFFECTIVITY_SPECS
    ContentStyle::class.java -> CONTENT_STYLE_EFFECTIVITY_SPECS
    LetterStyle::class.java -> LETTER_STYLE_EFFECTIVITY_SPECS
    Layer::class.java -> LAYER_EFFECTIVITY_SPECS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleEffectivitySpec<S>>


private val GLOBAL_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<Global>> = emptyList()


private val PAGE_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<PageStyle>> = listOf(
    StyleEffectivitySpec(
        PageStyle::cardDurationFrames.st(), PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st(),
        isTotallyIneffective = { _, _, style -> style.behavior != CARD }
    ),
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st(), PageStyle::scrollPxPerFrame.st(),
        isTotallyIneffective = { _, _, style -> style.behavior != SCROLL }
    ),
    StyleEffectivitySpec(
        PageStyle::afterwardSlugFrames.st(),
        isAlmostEffective = { _, _, style -> style.behavior == SCROLL && style.scrollMeltWithNext }
    )
)


private val CONTENT_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<ContentStyle>> = listOf(
    StyleEffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridFillingBalanced.st(), ContentStyle::gridStructure.st(),
        ContentStyle::gridForceColWidthPx.st(), ContentStyle::gridMatchColWidths.st(),
        ContentStyle::gridForceRowHeightPx.st(), ContentStyle::gridMatchRowHeight.st(),
        ContentStyle::gridMatchColWidthsAcrossStyles.st(), ContentStyle::gridMatchColUnderoccupancy.st(),
        ContentStyle::gridMatchRowHeightAcrossStyles.st(), ContentStyle::gridCellHJustifyPerCol.st(),
        ContentStyle::gridCellVJustify.st(), ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { _, _, style -> style.bodyLayout != GRID }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowSquareCells.st(),
        ContentStyle::flowForceCellWidthPx.st(), ContentStyle::flowForceCellHeightPx.st(),
        ContentStyle::flowMatchCellWidth.st(), ContentStyle::flowMatchCellWidthAcrossStyles.st(),
        ContentStyle::flowMatchCellHeight.st(), ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        ContentStyle::flowCellHJustify.st(), ContentStyle::flowCellVJustify.st(), ContentStyle::flowLineWidthPx.st(),
        ContentStyle::flowLineGapPx.st(), ContentStyle::flowHGapPx.st(), ContentStyle::flowSeparator.st(),
        isTotallyIneffective = { _, _, style -> style.bodyLayout != FLOW }
    ),
    StyleEffectivitySpec(
        ContentStyle::paragraphsLineHJustify.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        isTotallyIneffective = { _, _, style -> style.bodyLayout != PARAGRAPHS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headLetterStyleName.st(), ContentStyle::headForceWidthPx.st(), ContentStyle::headMatchWidth.st(),
        ContentStyle::headMatchWidthAcrossStyles.st(), ContentStyle::headHJustify.st(),
        ContentStyle::headVJustify.st(), ContentStyle::headGapPx.st(),
        isTotallyIneffective = { _, _, style -> !style.hasHead }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailForceWidthPx.st(), ContentStyle::tailMatchWidth.st(),
        ContentStyle::tailMatchWidthAcrossStyles.st(), ContentStyle::tailHJustify.st(),
        ContentStyle::tailVJustify.st(), ContentStyle::tailGapPx.st(),
        isTotallyIneffective = { _, _, style -> !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.gridMatchColWidths != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchColUnderoccupancy.st(),
        isTotallyIneffective = { _, _, style ->
            style.gridMatchColWidths != ACROSS_BLOCKS || style.gridMatchColWidthsAcrossStyles.isEmpty()
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchRowHeightAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.gridMatchRowHeight != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowMatchCellWidthAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.flowMatchCellWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.flowMatchCellHeight != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headMatchWidthAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.headMatchWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailMatchWidthAcrossStyles.st(),
        isTotallyIneffective = { _, _, style -> style.tailMatchWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::blockOrientation.st(),
        isAlmostEffective = { _, _, style -> !style.hasHead && !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridFillingBalanced.st(),
        isAlmostEffective = { _, _, style -> style.gridCellHJustifyPerCol.size < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchColWidths.st(), ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        ContentStyle::gridMatchColUnderoccupancy.st(),
        isAlmostEffective = { _, _, style -> style.gridForceColWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchColUnderoccupancy.st(),
        isAlmostEffective = { _, styling, style ->
            styling!!.contentStyles.all { o ->
                (o.name != style.name /* for duplicate names */ && o.name !in style.gridMatchColWidthsAcrossStyles) ||
                        style.gridCellHJustifyPerCol.size >= o.gridCellHJustifyPerCol.size
            }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridMatchRowHeight.st(), ContentStyle::gridMatchRowHeightAcrossStyles.st(),
        isAlmostEffective = { _, _, style -> style.gridForceRowHeightPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridCellVJustify.st(),
        isAlmostEffective = { _, _, style ->
            style.gridCellHJustifyPerCol.size < 2 && style.gridStructure != SQUARE_CELLS &&
                    !style.gridForceRowHeightPx.isActive && style.gridMatchRowHeight == OFF
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { _, _, style -> style.gridCellHJustifyPerCol.size < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowMatchCellWidth.st(), ContentStyle::flowMatchCellWidthAcrossStyles.st(),
        isAlmostEffective = { _, _, style -> style.flowForceCellWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowMatchCellHeight.st(), ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        isAlmostEffective = { _, _, style -> style.flowForceCellHeightPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowCellHJustify.st(),
        isAlmostEffective = { _, _, style ->
            !style.flowSquareCells && !style.flowForceCellWidthPx.isActive && style.flowMatchCellWidth == OFF
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::headForceWidthPx.st(), ContentStyle::headVJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::headMatchWidth.st(), ContentStyle::headMatchWidthAcrossStyles.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL || style.headForceWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::headHJustify.st(),
        isAlmostEffective = { _, _, style ->
            style.blockOrientation != VERTICAL && !style.headForceWidthPx.isActive &&
                    style.headMatchWidth != ACROSS_BLOCKS
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailForceWidthPx.st(), ContentStyle::tailVJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailMatchWidth.st(), ContentStyle::tailMatchWidthAcrossStyles.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL || style.tailForceWidthPx.isActive }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailHJustify.st(),
        isAlmostEffective = { _, _, style ->
            style.blockOrientation != VERTICAL && !style.tailForceWidthPx.isActive &&
                    style.tailMatchWidth != ACROSS_BLOCKS
        }
    )
)


private val LETTER_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<LetterStyle>> = listOf(
    StyleEffectivitySpec(
        LetterStyle::layers.st(),
        isTotallyIneffective = { _, _, style -> style.inheritLayersFromStyle.isActive }
    ),
    StyleEffectivitySpec(
        LetterStyle::kerning.st(),
        isAlmostEffective = { ctx, _, style -> supportsNot(ctx, style, KERNING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::ligatures.st(),
        isAlmostEffective = { ctx, _, style -> LIGATURES_FONT_FEATS.all { supportsNot(ctx, style, it) } }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { ctx, _, style -> supportsNot(ctx, style, CAPITAL_SPACING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { _, _, style -> !style.uppercase }
    ),
    StyleEffectivitySpec(
        LetterStyle::superscriptScaling.st(), LetterStyle::superscriptHOffsetRfh.st(),
        LetterStyle::superscriptVOffsetRfh.st(),
        isAlmostEffective = { _, _, style -> style.superscript != Superscript.CUSTOM }
    )
)

private fun supportsNot(ctx: StylingContext, style: LetterStyle, feat: String): Boolean {
    val font = ctx.resolveFont(style.fontName)
    return if (font == null) true else feat !in font.getSupportedFeatures()
}


private val LAYER_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<Layer>> = listOf(
    // Don't store whether a layer is collapsed, as we don't want to persist UI information.
    StyleEffectivitySpec(
        Layer::collapsed.st(),
        isTotallyIneffective = { _, _, _ -> true }
    ),
    StyleEffectivitySpec(
        Layer::color1.st(),
        isTotallyIneffective = { _, _, style -> style.coloring == LayerColoring.OFF }
    ),
    StyleEffectivitySpec(
        Layer::color2.st(), Layer::gradientAngleDeg.st(), Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st(),
        isTotallyIneffective = { _, _, style -> style.coloring != LayerColoring.GRADIENT }
    ),
    StyleEffectivitySpec(
        Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st(),
        Layer::stripeDashPatternRfh.st(),
        isTotallyIneffective = { _, _, style -> style.shape != LayerShape.STRIPE }
    ),
    StyleEffectivitySpec(
        Layer::cloneLayers.st(), Layer::anchorSiblingLayer.st(),
        isTotallyIneffective = { _, _, style -> style.shape != LayerShape.CLONE }
    ),
    StyleEffectivitySpec(
        Layer::dilationRfh.st(), Layer::dilationJoin.st(),
        isTotallyIneffective = { _, _, style -> style.shape != LayerShape.TEXT && style.shape != LayerShape.CLONE }
    ),
    StyleEffectivitySpec(
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(),
        isTotallyIneffective = { _, _, style -> style.offsetCoordinateSystem != CoordinateSystem.CARTESIAN }
    ),
    StyleEffectivitySpec(
        Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
        isTotallyIneffective = { _, _, style -> style.offsetCoordinateSystem != CoordinateSystem.POLAR }
    ),
    StyleEffectivitySpec(
        Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        isAlmostEffective = { _, _, style -> style.stripePreset != StripePreset.CUSTOM }
    ),
    StyleEffectivitySpec(
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        isAlmostEffective = { _, _, style -> style.stripePreset != StripePreset.BACKGROUND }
    ),
    StyleEffectivitySpec(
        Layer::stripeCornerRadiusRfh.st(),
        isAlmostEffective = { _, _, style -> style.stripeCornerJoin == LineJoin.MITER }
    ),
    StyleEffectivitySpec(
        Layer::dilationJoin.st(),
        isAlmostEffective = { _, _, style -> style.dilationRfh == 0.0 }
    ),
    StyleEffectivitySpec(
        Layer::contourThicknessRfh.st(), Layer::contourJoin.st(),
        isAlmostEffective = { _, _, style -> !style.contour }
    ),
    StyleEffectivitySpec(
        Layer::anchor.st(), Layer::anchorSiblingLayer.st(),
        isAlmostEffective = { _, _, style ->
            style.hScaling == 1.0 && style.vScaling == 1.0 && style.hShearing == 0.0 && style.vShearing == 0.0
        }
    ),
    StyleEffectivitySpec(
        Layer::anchorSiblingLayer.st(),
        isAlmostEffective = { _, _, style -> style.anchor != LayerAnchor.SIBLING }
    ),
    StyleEffectivitySpec(
        Layer::clearingRfh.st(),
        isAlmostEffective = { _, _, style -> style.clearingLayers.isEmpty() }
    ),
    StyleEffectivitySpec(
        Layer::clearingJoin.st(),
        isAlmostEffective = { _, _, style -> style.clearingLayers.isEmpty() || style.clearingRfh == 0.0 }
    )
)


class StyleEffectivitySpec<S : Style>(
    vararg settings: StyleSetting<S, *>,
    val isAlmostEffective: ((StylingContext, Styling?, S) -> Boolean)? = null,
    val isTotallyIneffective: ((StylingContext, Styling?, S) -> Boolean)? = null
) {
    val settings: List<StyleSetting<S, *>> = settings.toList()

    init {
        require(isAlmostEffective != null || isTotallyIneffective != null)
    }
}


enum class Effectivity { TOTALLY_INEFFECTIVE, ALMOST_EFFECTIVE, EFFECTIVE }

fun <S : Style> findIneffectiveSettings(
    ctx: StylingContext,
    styling: Styling,
    style: S
): Map<StyleSetting<S, *>, Effectivity> {
    val result = HashMap<StyleSetting<S, *>, Effectivity>()

    fun mark(settings: List<StyleSetting<S, *>>, tier: Effectivity) {
        for (setting in settings)
            result[setting] = minOf(result[setting] ?: tier, tier)
    }

    for (spec in getStyleEffectivitySpecs(style.javaClass)) {
        spec.isAlmostEffective?.let { if (it(ctx, styling, style)) mark(spec.settings, ALMOST_EFFECTIVE) }
        spec.isTotallyIneffective?.let { if (it(ctx, styling, style)) mark(spec.settings, TOTALLY_INEFFECTIVE) }
    }

    return result
}

fun <S : Style> isEffective(ctx: StylingContext, styling: Styling, style: S, setting: StyleSetting<S, *>): Boolean =
    getStyleEffectivitySpecs(style.javaClass).none { spec ->
        setting in spec.settings &&
                (spec.isAlmostEffective.let { it != null && it(ctx, styling, style) } ||
                        spec.isTotallyIneffective.let { it != null && it(ctx, styling, style) })
    }

/** Throws a [NullPointerException] if invoked on a setting where we need a [Styling] to determine its effectivity. */
fun <S : Style> isEffectiveUnsafe(ctx: StylingContext, style: S, setting: StyleSetting<S, *>): Boolean =
    getStyleEffectivitySpecs(style.javaClass).none { spec ->
        setting in spec.settings &&
                (spec.isAlmostEffective.let { it != null && it(ctx, null, style) } ||
                        spec.isTotallyIneffective.let { it != null && it(ctx, null, style) })
    }
