package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.ToggleButtonGroupWidgetSpec.Show.*
import kotlinx.collections.immutable.ImmutableList
import javax.swing.Icon


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleWidgetSpecs(styleClass: Class<S>): List<StyleWidgetSpec<S>> = when (styleClass) {
    Global::class.java -> GLOBAL_WIDGET_SPECS as List<StyleWidgetSpec<S>>
    PageStyle::class.java -> PAGE_STYLE_WIDGET_SPECS as List<StyleWidgetSpec<S>>
    ContentStyle::class.java -> CONTENT_STYLE_WIDGET_SPECS as List<StyleWidgetSpec<S>>
    LetterStyle::class.java -> LETTER_STYLE_WIDGET_SPECS as List<StyleWidgetSpec<S>>
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
}


private val GLOBAL_WIDGET_SPECS: List<StyleWidgetSpec<Global>> = listOf(
    NumberStepWidgetSpec(Global::widthPx.st(), 10),
    NumberStepWidgetSpec(Global::heightPx.st(), 10),
    TimecodeWidgetSpec(Global::runtimeFrames.st(), { _, global -> global.fps }, { _, global -> global.timecodeFormat }),
    WidthWidgetSpec(Global::uppercaseExceptions.st(), WidthSpec.NARROW)
)


private val PAGE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PageStyle>> = listOf(
    ToggleButtonGroupWidgetSpec(PageStyle::behavior.st(), LABEL)
)


private val CONTENT_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<ContentStyle>> = listOf(
    ToggleButtonGroupWidgetSpec(ContentStyle::spineOrientation.st(), ICON_AND_LABEL),
    NewSectionWidgetSpec(ContentStyle::bodyLayout.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemBoxConform.st(), ICON),
    ToggleButtonGroupListWidgetSpec(ContentStyle::gridElemHJustifyPerCol.st(), ICON, groupsPerRow = 4),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowLineHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemBoxConform.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemVJustify.st(), ICON),
    NumberStepWidgetSpec(ContentStyle::flowLineWidthPx.st(), 10f),
    WidthWidgetSpec(ContentStyle::flowSeparator.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberStepWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), 10f),
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON)
)


private val LETTER_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<LetterStyle>> = listOf(
    NumberStepWidgetSpec(LetterStyle::tracking.st(), 0.01f),
    WidthWidgetSpec(LetterStyle::backgroundWidenLeft.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenRight.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenTop.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::backgroundWidenBottom.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        LetterStyle::backgroundWidenLeft.st(), LetterStyle::backgroundWidenRight.st(),
        LetterStyle::backgroundWidenTop.st(), LetterStyle::backgroundWidenBottom.st(),
        unionName = "backgroundWiden",
        settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON, BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    )
)


sealed class StyleWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, *>
) {
    val settings: List<StyleSetting<S, *>> = settings.toList()
}


class NewSectionWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any?>
) : StyleWidgetSpec<S>(setting)


class WidthWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any?>,
    val widthSpec: WidthSpec
) : StyleWidgetSpec<S>(setting)


class NumberStepWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val stepSize: Number
) : StyleWidgetSpec<S>(setting)


class ToggleButtonGroupWidgetSpec<S : Style>(
    setting: StyleSetting<S, Enum<*>>,
    val show: Show
) : StyleWidgetSpec<S>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }
}


class ToggleButtonGroupListWidgetSpec<S : Style>(
    setting: StyleSetting<S, ImmutableList<Enum<*>>>,
    val show: ToggleButtonGroupWidgetSpec.Show,
    val groupsPerRow: Int
) : StyleWidgetSpec<S>(setting)


class TimecodeWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val getFPS: (Styling, S) -> FPS,
    val getTimecodeFormat: (Styling, S) -> TimecodeFormat
) : StyleWidgetSpec<S>(setting)


class UnionWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any?>,
    val unionName: String,
    val settingIcons: List<Icon>
) : StyleWidgetSpec<S>(*settings) {
    init {
        require(settings.size == settingIcons.size)
    }
}
