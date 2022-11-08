package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.CAPITAL_SPACING_FONT_FEAT
import com.loadingbyte.cinecred.common.KERNING_FONT_FEAT
import com.loadingbyte.cinecred.common.LIGATURES_FONT_FEATS
import com.loadingbyte.cinecred.common.getSupportedFeatures
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BodyCellConform.*
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL
import com.loadingbyte.cinecred.project.TextDecorationPreset.OFF


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
        isTotallyIneffective = { _, style -> style.behavior != CARD }
    ),
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st(), PageStyle::scrollPxPerFrame.st(),
        isTotallyIneffective = { _, style -> style.behavior != SCROLL }
    ),
    StyleEffectivitySpec(
        PageStyle::afterwardSlugFrames.st(),
        isAlmostEffective = { _, style -> style.behavior == SCROLL && style.scrollMeltWithNext }
    )
)


private val CONTENT_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<ContentStyle>> = listOf(
    StyleEffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridCellConform.st(),
        ContentStyle::gridCellHJustifyPerCol.st(), ContentStyle::gridCellVJustify.st(), ContentStyle::gridRowGapPx.st(),
        ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != GRID }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowCellConform.st(),
        ContentStyle::flowCellHJustify.st(), ContentStyle::flowCellVJustify.st(), ContentStyle::flowLineWidthPx.st(),
        ContentStyle::flowLineGapPx.st(), ContentStyle::flowHGapPx.st(), ContentStyle::flowSeparator.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != FLOW }
    ),
    StyleEffectivitySpec(
        ContentStyle::paragraphsLineHJustify.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        isTotallyIneffective = { _, style -> style.bodyLayout != PARAGRAPHS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headLetterStyleName.st(), ContentStyle::headHJustify.st(), ContentStyle::headVJustify.st(),
        ContentStyle::headGapPx.st(),
        isTotallyIneffective = { _, style -> !style.hasHead }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailHJustify.st(), ContentStyle::tailVJustify.st(),
        ContentStyle::tailGapPx.st(),
        isTotallyIneffective = { _, style -> !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::blockOrientation.st(),
        isAlmostEffective = { _, style -> !style.hasHead && !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridCellVJustify.st(),
        isAlmostEffective = { _, style ->
            style.gridCellHJustifyPerCol.size < 2 &&
                    style.gridCellConform.let { it != HEIGHT && it != WIDTH_AND_HEIGHT && it != SQUARE }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { _, style -> style.gridCellHJustifyPerCol.size < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowCellHJustify.st(),
        isAlmostEffective = { _, style ->
            style.flowCellConform.let { it != WIDTH && it != WIDTH_AND_HEIGHT && it != SQUARE }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::headVJustify.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailVJustify.st(),
        isAlmostEffective = { _, style -> style.blockOrientation != HORIZONTAL }
    )
)


private val LETTER_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<LetterStyle>> = listOf(
    StyleEffectivitySpec(
        LetterStyle::kerning.st(),
        isAlmostEffective = { ctx, style -> supportsNot(ctx, style, KERNING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::ligatures.st(),
        isAlmostEffective = { ctx, style -> LIGATURES_FONT_FEATS.all { supportsNot(ctx, style, it) } }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { ctx, style -> supportsNot(ctx, style, CAPITAL_SPACING_FONT_FEAT) }
    ),
    StyleEffectivitySpec(
        LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st(),
        isAlmostEffective = { _, style -> !style.uppercase }
    ),
    StyleEffectivitySpec(
        LetterStyle::backgroundWidenLeftPx.st(), LetterStyle::backgroundWidenRightPx.st(),
        LetterStyle::backgroundWidenTopPx.st(), LetterStyle::backgroundWidenBottomPx.st(),
        isAlmostEffective = { _, style -> !style.background.isActive }
    )
)

private fun supportsNot(ctx: StylingContext, style: LetterStyle, feat: String): Boolean {
    val font = ctx.resolveFont(style.fontName)
    return if (font == null) true else feat !in font.getSupportedFeatures()
}


private val TEXT_DECORATION_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<TextDecoration>> = listOf(
    StyleEffectivitySpec(
        TextDecoration::offsetPx.st(), TextDecoration::thicknessPx.st(),
        isAlmostEffective = { _, style -> style.preset != OFF }
    ),
    StyleEffectivitySpec(
        TextDecoration::clearingJoin.st(),
        isAlmostEffective = { _, style -> !style.clearingPx.isActive || style.clearingPx.value <= 0f }
    )
)


class StyleEffectivitySpec<S : Style>(
    vararg settings: StyleSetting<S, *>,
    val isAlmostEffective: ((StylingContext, S) -> Boolean)? = null,
    val isTotallyIneffective: ((StylingContext, S) -> Boolean)? = null
) {
    val settings: List<StyleSetting<S, *>> = settings.toList()

    init {
        require(isAlmostEffective != null || isTotallyIneffective != null)
    }
}


enum class Effectivity { TOTALLY_INEFFECTIVE, ALMOST_EFFECTIVE, EFFECTIVE }

fun <S : Style> findIneffectiveSettings(ctx: StylingContext, style: S): Map<StyleSetting<S, *>, Effectivity> {
    val result = HashMap<StyleSetting<S, *>, Effectivity>()

    fun mark(settings: List<StyleSetting<S, *>>, tier: Effectivity) {
        for (setting in settings)
            result[setting] = minOf(result[setting] ?: tier, tier)
    }

    for (spec in getStyleEffectivitySpecs(style.javaClass)) {
        spec.isAlmostEffective?.let { if (it(ctx, style)) mark(spec.settings, Effectivity.ALMOST_EFFECTIVE) }
        spec.isTotallyIneffective?.let { if (it(ctx, style)) mark(spec.settings, Effectivity.TOTALLY_INEFFECTIVE) }
    }

    return result
}
