package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.ToggleButtonGroupWidgetSpec.Show.*
import javax.swing.Icon


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleWidgetSpecs(styleClass: Class<S>): List<StyleWidgetSpec<S>> = when (styleClass) {
    Global::class.java -> GLOBAL_WIDGET_SPECS
    PageStyle::class.java -> PAGE_STYLE_WIDGET_SPECS
    ContentStyle::class.java -> CONTENT_STYLE_WIDGET_SPECS
    LetterStyle::class.java -> LETTER_STYLE_WIDGET_SPECS
    TextDecoration::class.java -> TEXT_DECORATION_WIDGET_SPECS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleWidgetSpec<S>>


private val GLOBAL_WIDGET_SPECS: List<StyleWidgetSpec<Global>> = listOf(
    NumberWidgetSpec(Global::widthPx.st(), step = 10),
    NumberWidgetSpec(Global::heightPx.st(), step = 10),
    TimecodeWidgetSpec(
        Global::runtimeFrames.st(),
        getFPS = { _, _, global -> global.fps },
        getTimecodeFormat = { _, _, global -> global.timecodeFormat }
    ),
    WidthWidgetSpec(Global::uppercaseExceptions.st(), WidthSpec.NARROW)
)


private val PAGE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PageStyle>> = listOf(
    ToggleButtonGroupWidgetSpec(PageStyle::behavior.st(), LABEL),
    TimecodeWidgetSpec(
        PageStyle::afterwardSlugFrames.st(), PageStyle::cardDurationFrames.st(),
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st(),
        getFPS = { _, styling, _ -> styling.global.fps },
        getTimecodeFormat = { _, styling, _ -> styling.global.timecodeFormat }
    )
)


private val CONTENT_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<ContentStyle>> = listOf(
    ToggleButtonGroupWidgetSpec(ContentStyle::spineOrientation.st(), ICON_AND_LABEL),
    NewSectionWidgetSpec(ContentStyle::bodyLayout.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemBoxConform.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemHJustifyPerCol.st(), ICON),
    ListWidgetSpec(ContentStyle::gridElemHJustifyPerCol.st(), groupsPerRow = 3),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowLineHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemBoxConform.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemVJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::flowLineWidthPx.st(), step = 10f),
    WidthWidgetSpec(ContentStyle::flowSeparator.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), step = 10f),
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON)
)


private val LETTER_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<LetterStyle>> = listOf(
    NewSectionWidgetSpec(LetterStyle::leadingTopRem.st()),
    WidthWidgetSpec(LetterStyle::leadingTopRem.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::leadingBottomRem.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::leadingTopRem.st(), step = 0.01f),
    NumberWidgetSpec(LetterStyle::leadingBottomRem.st(), step = 0.01f),
    UnionWidgetSpec(
        LetterStyle::leadingTopRem.st(), LetterStyle::leadingBottomRem.st(),
        unionName = "leadingRem", settingIcons = listOf(BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    ),
    NumberWidgetSpec(LetterStyle::trackingEm.st(), step = 0.01f),
    WidthWidgetSpec(LetterStyle::hOffsetRem.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::vOffsetRem.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::hOffsetRem.st(), step = 0.01f),
    NumberWidgetSpec(LetterStyle::vOffsetRem.st(), step = 0.01f),
    UnionWidgetSpec(
        LetterStyle::hOffsetRem.st(), LetterStyle::vOffsetRem.st(),
        unionName = "offsetRem", settingIcons = listOf(ARROW_LEFT_RIGHT_ICON, ARROW_TOP_BOTTOM_ICON)
    ),
    NumberWidgetSpec(LetterStyle::scaling.st(), step = 0.01f),
    NumberWidgetSpec(LetterStyle::hScaling.st(), step = 0.01f),
    NumberWidgetSpec(LetterStyle::hShearing.st(), step = 0.05f),
    ListWidgetSpec(LetterStyle::features.st(), groupsPerRow = 2),
    NewSectionWidgetSpec(LetterStyle::decorations.st()),
    NewSectionWidgetSpec(LetterStyle::background.st()),
    WidthWidgetSpec(LetterStyle::backgroundWidenLeftPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenRightPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenTopPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenBottomPx.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        LetterStyle::backgroundWidenLeftPx.st(), LetterStyle::backgroundWidenRightPx.st(),
        LetterStyle::backgroundWidenTopPx.st(), LetterStyle::backgroundWidenBottomPx.st(),
        unionName = "backgroundWidenPx",
        settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON, BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    )
)


private val TEXT_DECORATION_WIDGET_SPECS: List<StyleWidgetSpec<TextDecoration>> = listOf(
    WidthWidgetSpec(TextDecoration::widenLeftPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(TextDecoration::widenRightPx.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        TextDecoration::widenLeftPx.st(), TextDecoration::widenRightPx.st(),
        unionName = "widenPx", settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON)
    ),
    NumberWidgetSpec(TextDecoration::clearingPx.st(), step = 0.1f),
    WidthWidgetSpec(TextDecoration::dashPatternPx.st(), WidthSpec.TINY),
    NumberWidgetSpec(TextDecoration::dashPatternPx.st(), default = 2f),
    ListWidgetSpec(TextDecoration::dashPatternPx.st(), groupsPerRow = 2)
)


sealed class StyleWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, *>
) {
    val settings: List<StyleSetting<S, *>> = settings.toList()
}


class NewSectionWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>
) : StyleWidgetSpec<S>(setting)


class WidthWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>,
    val widthSpec: WidthSpec
) : StyleWidgetSpec<S>(setting)


class NumberWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val default: Number? = null,
    val step: Number? = null
) : StyleWidgetSpec<S>(setting)


class ToggleButtonGroupWidgetSpec<S : Style>(
    setting: StyleSetting<S, Enum<*>>,
    val show: Show
) : StyleWidgetSpec<S>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }
}


class TimecodeWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Number>,
    val getFPS: (StylingContext, Styling, S) -> FPS,
    val getTimecodeFormat: (StylingContext, Styling, S) -> TimecodeFormat
) : StyleWidgetSpec<S>(*settings)


class UnionWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val unionName: String,
    val settingIcons: List<Icon>
) : StyleWidgetSpec<S>(*settings) {
    init {
        require(settings.size == settingIcons.size)
    }
}


class ListWidgetSpec<S : Style>(
    setting: ListStyleSetting<S, Any>,
    val groupsPerRow: Int
) : StyleWidgetSpec<S>(setting)
