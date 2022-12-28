package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.l10n
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
    ToggleButtonGroupWidgetSpec(ContentStyle::blockOrientation.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(
        ContentStyle::spineAttachment.st(), ICON,
        getDynIcon = { _, _, style, spineAtt -> spineAtt.icon(style.blockOrientation, style.hasHead, style.hasTail) }
    ),
    NewSectionWidgetSpec(ContentStyle::bodyLayout.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ChoiceWidgetSpec(
        ContentStyle::gridMatchColWidthsAcrossStyles.st(), ContentStyle::gridMatchRowHeightAcrossStyles.st(),
        ContentStyle::flowMatchCellWidthAcrossStyles.st(), ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        ContentStyle::headMatchWidthAcrossStyles.st(), ContentStyle::tailMatchWidthAcrossStyles.st(),
        getNoItemsMsg = { l10n("ui.styling.content.msg.noMatchStylesAvailable") }
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingBalanced.st(), ICON, ::gridFillingBalancedIcon),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridStructure.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridMatchColWidths.st(), ICON),
    WidthWidgetSpec(ContentStyle::gridMatchColWidthsAcrossStyles.st(), WidthSpec.SQUEEZE),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridMatchColUnderoccupancy.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridMatchRowHeight.st(), ICON),
    WidthWidgetSpec(ContentStyle::gridMatchRowHeightAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(
        ContentStyle::gridMatchColWidths.st(), ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        ContentStyle::gridMatchColUnderoccupancy.st(),
        unionName = "gridMatchColWidths"
    ),
    UnionWidgetSpec(
        ContentStyle::gridMatchRowHeight.st(), ContentStyle::gridMatchRowHeightAcrossStyles.st(),
        unionName = "gridMatchRowHeight"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridCellHJustifyPerCol.st(), ICON),
    ListWidgetSpec(ContentStyle::gridCellHJustifyPerCol.st(), newElemIsLastElem = true, elemsPerRow = 3),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridCellVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowLineHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowSquareCells.st(), ICON, ::flowSquareCellsIcon),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowMatchCellWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::flowMatchCellWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowMatchCellHeight.st(), ICON),
    WidthWidgetSpec(ContentStyle::flowMatchCellHeightAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(
        ContentStyle::flowMatchCellWidth.st(), ContentStyle::flowMatchCellWidthAcrossStyles.st(),
        unionName = "flowMatchCellWidth"
    ),
    UnionWidgetSpec(
        ContentStyle::flowMatchCellHeight.st(), ContentStyle::flowMatchCellHeightAcrossStyles.st(),
        unionName = "flowMatchCellHeight"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowCellHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowCellVJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::flowLineWidthPx.st(), step = 10.0),
    WidthWidgetSpec(ContentStyle::flowSeparator.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), step = 10.0),
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headMatchWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::headMatchWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(
        ContentStyle::headMatchWidth.st(), ContentStyle::headMatchWidthAcrossStyles.st(),
        unionName = "headMatchWidth"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailMatchWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::tailMatchWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(
        ContentStyle::tailMatchWidth.st(), ContentStyle::tailMatchWidthAcrossStyles.st(),
        unionName = "tailMatchWidth"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON)
)


private val LETTER_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<LetterStyle>> = listOf(
    NewSectionWidgetSpec(LetterStyle::leadingTopRem.st()),
    WidthWidgetSpec(LetterStyle::leadingTopRem.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::leadingBottomRem.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::leadingTopRem.st(), step = 0.01),
    NumberWidgetSpec(LetterStyle::leadingBottomRem.st(), step = 0.01),
    UnionWidgetSpec(
        LetterStyle::leadingTopRem.st(), LetterStyle::leadingBottomRem.st(),
        unionName = "leadingRem", settingIcons = listOf(BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    ),
    NumberWidgetSpec(LetterStyle::trackingEm.st(), step = 0.01),
    ToggleButtonGroupWidgetSpec(LetterStyle::smallCaps.st(), ICON),
    ToggleButtonGroupWidgetSpec(LetterStyle::superscript.st(), ICON),
    WidthWidgetSpec(LetterStyle::hOffsetRem.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::vOffsetRem.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::hOffsetRem.st(), step = 0.01),
    NumberWidgetSpec(LetterStyle::vOffsetRem.st(), step = 0.01),
    UnionWidgetSpec(
        LetterStyle::hOffsetRem.st(), LetterStyle::vOffsetRem.st(),
        unionName = "offsetRem", settingIcons = listOf(ARROW_LEFT_RIGHT_ICON, ARROW_UP_DOWN_ICON)
    ),
    NumberWidgetSpec(LetterStyle::scaling.st(), step = 0.01),
    NumberWidgetSpec(LetterStyle::hScaling.st(), step = 0.01),
    NumberWidgetSpec(LetterStyle::hShearing.st(), step = 0.05),
    ListWidgetSpec(LetterStyle::features.st(), newElem = FontFeature("", 1), elemsPerRow = 2),
    NewSectionWidgetSpec(LetterStyle::decorations.st()),
    ListWidgetSpec(
        LetterStyle::decorations.st(),
        newElem = PRESET_TEXT_DECORATION, elemsPerRow = 1, rowSeparators = true, movButtons = true
    ),
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
    ToggleButtonGroupWidgetSpec(TextDecoration::preset.st(), ICON),
    WidthWidgetSpec(TextDecoration::widenLeftPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(TextDecoration::widenRightPx.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        TextDecoration::widenLeftPx.st(), TextDecoration::widenRightPx.st(),
        unionName = "widenPx", settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON)
    ),
    NumberWidgetSpec(TextDecoration::clearingPx.st(), step = 0.1),
    ToggleButtonGroupWidgetSpec(TextDecoration::clearingJoin.st(), ICON),
    WidthWidgetSpec(TextDecoration::dashPatternPx.st(), WidthSpec.TINY),
    ListWidgetSpec(TextDecoration::dashPatternPx.st(), newElem = 2.0, newElemIsLastElem = true, elemsPerRow = 2)
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
    val step: Number? = null
) : StyleWidgetSpec<S>(setting)


class ToggleButtonGroupWidgetSpec<S : Style, SUBJ : Any>(
    setting: StyleSetting<S, SUBJ>,
    val show: Show,
    val getFixedIcon: ((SUBJ) -> Icon)? = null,
    val getDynIcon: ((StylingContext, Styling, S, SUBJ) -> Icon)? = null
) : StyleWidgetSpec<S>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }

    init {
        require(getFixedIcon == null || getDynIcon == null)
        if (getFixedIcon != null || getDynIcon != null)
            require(show == ICON || show == ICON_AND_LABEL)
    }
}


class TimecodeWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Number>,
    val getFPS: (StylingContext, Styling, S) -> FPS,
    val getTimecodeFormat: (StylingContext, Styling, S) -> TimecodeFormat
) : StyleWidgetSpec<S>(*settings)


class UnionWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val unionName: String,
    val settingIcons: List<Icon>? = null
) : StyleWidgetSpec<S>(*settings) {
    init {
        if (settingIcons != null)
            require(settings.size == settingIcons.size)
    }
}


class ListWidgetSpec<S : Style, SUBJ : Any>(
    setting: ListStyleSetting<S, SUBJ>,
    val newElem: SUBJ? = null,
    val newElemIsLastElem: Boolean = false,
    val elemsPerRow: Int = 1,
    val rowSeparators: Boolean = false,
    val movButtons: Boolean = false
) : StyleWidgetSpec<S>(setting)


class ChoiceWidgetSpec<S : Style>(
    vararg settings: ListStyleSetting<S, Any>,
    val getNoItemsMsg: (() -> String)? = null
) : StyleWidgetSpec<S>(*settings)
