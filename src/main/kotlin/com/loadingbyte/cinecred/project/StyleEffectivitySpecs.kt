package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.CAPITAL_SPACING_FONT_FEAT
import com.loadingbyte.cinecred.common.KERNING_FONT_FEAT
import com.loadingbyte.cinecred.common.LIGATURES_FONT_FEATS
import com.loadingbyte.cinecred.common.getSupportedFeatures
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.Effectivity.*
import com.loadingbyte.cinecred.project.GridStructure.*
import com.loadingbyte.cinecred.project.MatchExtent.*
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleEffectivitySpecs(styleClass: Class<S>): List<StyleEffectivitySpec<S>> = when (styleClass) {
    Global::class.java -> GLOBAL_EFFECTIVITY_SPECS
    PageStyle::class.java -> PAGE_STYLE_EFFECTIVITY_SPECS
    ContentStyle::class.java -> CONTENT_STYLE_EFFECTIVITY_SPECS
    LetterStyle::class.java -> LETTER_STYLE_EFFECTIVITY_SPECS
    TextDecoration::class.java -> TEXT_DECORATION_EFFECTIVITY_SPECS
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
        ContentStyle::gridMatchColWidths.st(), ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        ContentStyle::gridMatchColUnderoccupancy.st(), ContentStyle::gridMatchRowHeight.st(),
        ContentStyle::gridMatchRowHeightAcrossStyles.st(), ContentStyle::gridCellHJustifyPerCol.st(),
        ContentStyle::gridCellVJustify.st(), ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { _, _, style -> style.bodyLayout != GRID }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowSquareCells.st(),
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
        ContentStyle::headLetterStyleName.st(), ContentStyle::headMatchWidth.st(),
        ContentStyle::headMatchWidthAcrossStyles.st(), ContentStyle::headHJustify.st(),
        ContentStyle::headVJustify.st(), ContentStyle::headGapPx.st(),
        isTotallyIneffective = { _, _, style -> !style.hasHead }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailMatchWidth.st(),
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
        ContentStyle::gridMatchColUnderoccupancy.st(),
        isAlmostEffective = { _, styling, style ->
            styling!!.contentStyles.all { o ->
                (o.name != style.name /* for duplicate names */ && o.name !in style.gridMatchColWidthsAcrossStyles) ||
                        style.gridCellHJustifyPerCol.size >= o.gridCellHJustifyPerCol.size
            }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridCellVJustify.st(),
        isAlmostEffective = { _, _, style ->
            style.gridCellHJustifyPerCol.size < 2 && style.gridStructure != SQUARE_CELLS &&
                    style.gridMatchRowHeight == OFF
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { _, _, style -> style.gridCellHJustifyPerCol.size < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowCellHJustify.st(),
        isAlmostEffective = { _, _, style -> !style.flowSquareCells && style.flowMatchCellWidth == OFF }
    ),
    StyleEffectivitySpec(
        ContentStyle::headMatchWidth.st(), ContentStyle::headMatchWidthAcrossStyles.st(),
        ContentStyle::headVJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::headHJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != VERTICAL && style.headMatchWidth != ACROSS_BLOCKS }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailMatchWidth.st(), ContentStyle::tailMatchWidthAcrossStyles.st(),
        ContentStyle::tailVJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailHJustify.st(),
        isAlmostEffective = { _, _, style -> style.blockOrientation != VERTICAL && style.tailMatchWidth != ACROSS_BLOCKS }
    )
)


private val LETTER_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<LetterStyle>> = listOf(
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
        LetterStyle::backgroundWidenLeftPx.st(), LetterStyle::backgroundWidenRightPx.st(),
        LetterStyle::backgroundWidenTopPx.st(), LetterStyle::backgroundWidenBottomPx.st(),
        isAlmostEffective = { _, _, style -> !style.background.isActive }
    )
)

private fun supportsNot(ctx: StylingContext, style: LetterStyle, feat: String): Boolean {
    val font = ctx.resolveFont(style.fontName)
    return if (font == null) true else feat !in font.getSupportedFeatures()
}


private val TEXT_DECORATION_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<TextDecoration>> = listOf(
    StyleEffectivitySpec(
        TextDecoration::offsetPx.st(), TextDecoration::thicknessPx.st(),
        isAlmostEffective = { _, _, style -> style.preset != TextDecorationPreset.OFF }
    ),
    StyleEffectivitySpec(
        TextDecoration::clearingJoin.st(),
        isAlmostEffective = { _, _, style -> !style.clearingPx.isActive || style.clearingPx.value <= 0f }
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
