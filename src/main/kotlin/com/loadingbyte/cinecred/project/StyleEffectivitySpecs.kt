package com.loadingbyte.cinecred.project


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
        isTotallyIneffective = { style -> style.behavior != PageBehavior.CARD }
    ),
    StyleEffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st(), PageStyle::scrollPxPerFrame.st(),
        isTotallyIneffective = { style -> style.behavior != PageBehavior.SCROLL }
    ),
    StyleEffectivitySpec(
        PageStyle::afterwardSlugFrames.st(),
        isAlmostEffective = { style -> style.behavior == PageBehavior.SCROLL && style.scrollMeltWithNext }
    )
)


private val CONTENT_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<ContentStyle>> = listOf(
    StyleEffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridElemBoxConform.st(),
        ContentStyle::gridElemHJustifyPerCol.st(), ContentStyle::gridElemVJustify.st(), ContentStyle::gridRowGapPx.st(),
        ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.GRID }
    ),
    StyleEffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowElemBoxConform.st(),
        ContentStyle::flowElemHJustify.st(), ContentStyle::flowElemVJustify.st(), ContentStyle::flowLineWidthPx.st(),
        ContentStyle::flowLineGapPx.st(), ContentStyle::flowHGapPx.st(), ContentStyle::flowSeparator.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.FLOW }
    ),
    StyleEffectivitySpec(
        ContentStyle::paragraphsLineHJustify.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.PARAGRAPHS }
    ),
    StyleEffectivitySpec(
        ContentStyle::headLetterStyleName.st(), ContentStyle::headHJustify.st(), ContentStyle::headVJustify.st(),
        ContentStyle::headGapPx.st(),
        isTotallyIneffective = { style -> !style.hasHead }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailHJustify.st(), ContentStyle::tailVJustify.st(),
        ContentStyle::tailGapPx.st(),
        isTotallyIneffective = { style -> !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::spineOrientation.st(),
        isAlmostEffective = { style -> !style.hasHead && !style.hasTail }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridElemVJustify.st(),
        isAlmostEffective = { style ->
            style.gridElemHJustifyPerCol.size < 2 && style.gridElemBoxConform.let {
                it != BodyElementBoxConform.HEIGHT && it != BodyElementBoxConform.WIDTH_AND_HEIGHT &&
                        it != BodyElementBoxConform.SQUARE
            }
        }
    ),
    StyleEffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { style -> style.gridElemHJustifyPerCol.size < 2 }
    ),
    StyleEffectivitySpec(
        ContentStyle::headVJustify.st(),
        isAlmostEffective = { style -> style.spineOrientation != SpineOrientation.HORIZONTAL }
    ),
    StyleEffectivitySpec(
        ContentStyle::tailVJustify.st(),
        isAlmostEffective = { style -> style.spineOrientation != SpineOrientation.HORIZONTAL }
    )
)


private val LETTER_STYLE_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<LetterStyle>> = listOf(
    StyleEffectivitySpec(
        LetterStyle::useUppercaseExceptions.st(),
        isAlmostEffective = { style -> !style.uppercase }
    ),
    StyleEffectivitySpec(
        LetterStyle::backgroundWidenLeftPx.st(), LetterStyle::backgroundWidenRightPx.st(),
        LetterStyle::backgroundWidenTopPx.st(), LetterStyle::backgroundWidenBottomPx.st(),
        isAlmostEffective = { style -> style.background.alpha == 0 }
    )
)


private val TEXT_DECORATION_EFFECTIVITY_SPECS: List<StyleEffectivitySpec<TextDecoration>> = listOf(
    StyleEffectivitySpec(
        TextDecoration::offsetPx.st(), TextDecoration::thicknessPx.st(),
        isAlmostEffective = { style -> style.preset != TextDecorationPreset.OFF }
    ),
    StyleEffectivitySpec(
        TextDecoration::clearingJoin.st(),
        isAlmostEffective = { style -> !style.clearingPx.isActive || style.clearingPx.value <= 0f }
    )
)


class StyleEffectivitySpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val isAlmostEffective: ((S) -> Boolean)? = null,
    val isTotallyIneffective: ((S) -> Boolean)? = null
) {
    val settings: List<StyleSetting<S, Any>> = settings.toList()

    init {
        require(isAlmostEffective != null || isTotallyIneffective != null)
    }
}


enum class Effectivity { TOTALLY_INEFFECTIVE, ALMOST_EFFECTIVE, EFFECTIVE }

fun <S : Style> findIneffectiveSettings(style: S): Map<StyleSetting<*, *>, Effectivity> {
    val result = HashMap<StyleSetting<*, *>, Effectivity>()

    fun mark(settings: List<StyleSetting<*, *>>, tier: Effectivity) {
        for (setting in settings)
            result[setting] = minOf(result[setting] ?: tier, tier)
    }

    for (spec in getStyleEffectivitySpecs(style.javaClass)) {
        spec.isAlmostEffective?.let { if (it(style)) mark(spec.settings, Effectivity.ALMOST_EFFECTIVE) }
        spec.isTotallyIneffective?.let { if (it(style)) mark(spec.settings, Effectivity.TOTALLY_INEFFECTIVE) }
    }

    return result
}
