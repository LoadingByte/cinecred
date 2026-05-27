package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.ToggleButtonGroupWidgetSpec.Show.*
import javax.swing.Icon


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleWidgetSpecs(styleClass: Class<S>): List<StyleWidgetSpec<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_WIDGET_SPECS
    PageStyle::class.java -> PAGE_STYLE_WIDGET_SPECS
    ContentStyle::class.java -> CONTENT_STYLE_WIDGET_SPECS
    LetterStyle::class.java -> LETTER_STYLE_WIDGET_SPECS
    Layer::class.java -> LAYER_WIDGET_SPECS
    TransitionStyle::class.java -> emptyList()
    PictureStyle::class.java -> PICTURE_STYLE_WIDGET_SPECS
    TapeStyle::class.java -> TAPE_STYLE_WIDGET_SPECS
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as List<StyleWidgetSpec<S, *>>


private val GLOBAL_WIDGET_SPECS: List<StyleWidgetSpec<Global, *>> = listOf(
    UnitWidgetSpec(Global::unitVGapPx.st(), unit = "px"),
    LabelWidgetSpec(Global::resolution.st(), labelL10nKey = "resolution"),
    TimecodeWidgetSpec(
        Global::runtimeFrames.st(),
        getFPS = { _, global -> global.fps },
        getTimecodeFormat = { _, global -> global.timecodeFormat }
    ),
    OverrideWidgetSpec(
        Global::runtimeFrames.st(),
        getDefaultValue = { ctx, _ -> ctx.mostRecentRuntimeFrames }
    ),
    WidthWidgetSpec(Global::uppercaseExceptions.st(), WidthSpec.NARROW)
)


private val PAGE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PageStyle, *>> = listOf(
    UnitWidgetSpec(PageStyle::scrollPxPerFrame.st(), unit = "px"),
    ToggleButtonGroupWidgetSpec(PageStyle::behavior.st(), LABEL),
    TimecodeWidgetSpec(
        PageStyle::subsequentGapFrames.st(), PageStyle::cardRuntimeFrames.st(),
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st(), PageStyle::scrollRuntimeFrames.st(),
        getFPS = { styling, _ -> styling.global.fps },
        getTimecodeFormat = { styling, _ -> styling.global.timecodeFormat }
    ),
    UnionWidgetSpec(
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeInTransitionStyleName.st(),
        unionName = "cardFadeIn", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    UnionWidgetSpec(
        PageStyle::cardFadeOutFrames.st(), PageStyle::cardFadeOutTransitionStyleName.st(),
        unionName = "cardFadeOut", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    LabelWidgetSpec(
        PageStyle::scrollRuntimeFrames.st(),
        labelL10nKey = "ui.styling.global.runtimeFrames", descL10nKey = "ui.styling.global.runtimeFrames.desc"
    ),
    OverrideWidgetSpec(
        PageStyle::scrollRuntimeFrames.st(),
        getDefaultValue = { ctx, style -> ctx.mostRecentScrollStyleRuntimeFrames.getOrDefault(style.name, 0) }
    )
)


private val CONTENT_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<ContentStyle, *>> = listOf(
    UnitWidgetSpec(
        ContentStyle::vMarginTopPx.st(), ContentStyle::vMarginBottomPx.st(),
        ContentStyle::gridForceColWidthPx.st(), ContentStyle::gridForceRowHeightPx.st(),
        ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st(), ContentStyle::flowForceCellWidthPx.st(),
        ContentStyle::flowForceCellHeightPx.st(), ContentStyle::flowRowWidthPx.st(), ContentStyle::flowRowGapPx.st(),
        ContentStyle::flowCellHGapPx.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        ContentStyle::headForceWidthPx.st(), ContentStyle::headGapPx.st(),
        ContentStyle::headLeaderMarginLeftPx.st(), ContentStyle::headLeaderMarginRightPx.st(),
        ContentStyle::headLeaderSpacingPx.st(),
        ContentStyle::tailForceWidthPx.st(), ContentStyle::tailGapPx.st(),
        ContentStyle::tailLeaderMarginLeftPx.st(), ContentStyle::tailLeaderMarginRightPx.st(),
        ContentStyle::tailLeaderSpacingPx.st(),
        unit = "px"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::blockOrientation.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(
        ContentStyle::spineAttachment.st(), ICON,
        getDynIcon = { _, style, spineAtt -> spineAtt.icon(style.blockOrientation, style.hasHead, style.hasTail) }
    ),
    WidthWidgetSpec(ContentStyle::vMarginTopPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::vMarginBottomPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        ContentStyle::vMarginTopPx.st(), ContentStyle::vMarginBottomPx.st(),
        unionName = "vMarginPx", settingIcons = listOf(GUILLEMET_UP_ICON, GUILLEMET_DOWN_ICON)
    ),
    NewSectionWidgetSpec(ContentStyle::bodyLayout.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ChoiceWidgetSpec(
        ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(), ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(),
        ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(), ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(),
        ContentStyle::headHarmonizeWidthAcrossStyles.st(), ContentStyle::tailHarmonizeWidthAcrossStyles.st(),
        getNoItemsMsg = { l10n("ui.styling.content.msg.noHarmonizationStylesAvailable") }
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::sort.st(), ICON),
    NumberWidgetSpec(ContentStyle::gridCols.st(), sensitivity = 0.02),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingBalanced.st(), ICON, ::gridFillingBalancedIcon),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridStructure.st(), ICON),
    WidthWidgetSpec(ContentStyle::gridForceColWidthPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::gridForceRowHeightPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridHarmonizeColWidths.st(), ICON),
    WidthWidgetSpec(ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(), WidthSpec.SQUEEZE),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridHarmonizeColUnderoccupancy.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridHarmonizeRowHeight.st(), ICON),
    WidthWidgetSpec(ContentStyle::gridHarmonizeRowHeightAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(
        ContentStyle::gridHarmonizeColWidths.st(), ContentStyle::gridHarmonizeColWidthsAcrossStyles.st(),
        ContentStyle::gridHarmonizeColUnderoccupancy.st()
    ),
    UnionWidgetSpec(ContentStyle::gridHarmonizeRowHeight.st(), ContentStyle::gridHarmonizeRowHeightAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridCellHJustifyPerCol.st(), ICON),
    SimpleListWidgetSpec(
        ContentStyle::gridCellHJustifyPerCol.st(),
        newElement = HJustify.CENTER, newElementIsLastElement = true, elementsPerRow = 3
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridCellVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridTextVJustifyFragments.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridTextVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::gridTextVJustifyFragments.st(), ContentStyle::gridTextVJustify.st(),
        unionName = "gridTextVJustify"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowRowHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowSquareCells.st(), ICON, ::flowSquareCellsIcon),
    WidthWidgetSpec(ContentStyle::flowForceCellWidthPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::flowForceCellHeightPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowHarmonizeCellWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowHarmonizeCellHeight.st(), ICON),
    WidthWidgetSpec(ContentStyle::flowHarmonizeCellHeightAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(ContentStyle::flowHarmonizeCellWidth.st(), ContentStyle::flowHarmonizeCellWidthAcrossStyles.st()),
    UnionWidgetSpec(ContentStyle::flowHarmonizeCellHeight.st(), ContentStyle::flowHarmonizeCellHeightAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowCellHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowCellVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::flowCellHJustify.st(), ContentStyle::flowCellVJustify.st(),
        unionName = "flowCellJustify"
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowTextVJustifyFragments.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowTextVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::flowTextVJustifyFragments.st(), ContentStyle::flowTextVJustify.st(),
        unionLabelL10nKey = "ui.styling.content.gridTextVJustify"
    ),
    NumberWidgetSpec(ContentStyle::flowRowWidthPx.st(), sensitivity = 1.0),
    LabelWidgetSpec(ContentStyle::flowRowGapPx.st(), labelL10nKey = "ui.styling.content.gridRowGapPx"),
    WidthWidgetSpec(ContentStyle::flowSeparator.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowSeparatorVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), sensitivity = 1.0),
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    WidthWidgetSpec(ContentStyle::headForceWidthPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHarmonizeWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::headHarmonizeWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(ContentStyle::headHarmonizeWidth.st(), ContentStyle::headHarmonizeWidthAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustifyBodyFragment.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustifyHeadFragment.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::headVJustifyHeadFragment.st(), ContentStyle::headVJustifyBodyFragment.st(),
        ContentStyle::headVJustify.st(),
        unionName = "headVJustify",
        settingIcons = listOf(null, ARROW_LEFT_RIGHT_ICON, null),
        settingGaps = listOf("3", "unrel")
    ),
    WidthWidgetSpec(ContentStyle::headLeader.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::headLeaderHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headLeaderVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::headLeaderHJustify.st(), ContentStyle::headLeaderVJustify.st(),
        unionName = "headLeaderJustify"
    ),
    WidthWidgetSpec(ContentStyle::headLeaderMarginLeftPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::headLeaderMarginRightPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::headLeaderSpacingPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        ContentStyle::headLeaderMarginLeftPx.st(), ContentStyle::headLeaderMarginRightPx.st(),
        ContentStyle::headLeaderSpacingPx.st(),
        unionName = "headLeaderGapsPx",
        settingIcons = listOf(LEADER_GAP_MARGIN_LEFT_ICON, LEADER_GAP_MARGIN_RIGHT_ICON, LEADER_GAP_SPACING_ICON)
    ),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    WidthWidgetSpec(ContentStyle::tailForceWidthPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHarmonizeWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::tailHarmonizeWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(ContentStyle::tailHarmonizeWidth.st(), ContentStyle::tailHarmonizeWidthAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustifyBodyFragment.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustifyTailFragment.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::tailVJustifyBodyFragment.st(), ContentStyle::tailVJustifyTailFragment.st(),
        ContentStyle::tailVJustify.st(),
        unionName = "tailVJustify",
        settingIcons = listOf(null, ARROW_LEFT_RIGHT_ICON, null),
        settingGaps = listOf("3", "unrel")
    ),
    WidthWidgetSpec(ContentStyle::tailLeader.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailLeaderHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailLeaderVJustify.st(), ICON),
    UnionWidgetSpec(
        ContentStyle::tailLeaderHJustify.st(), ContentStyle::tailLeaderVJustify.st(),
        unionName = "tailLeaderJustify"
    ),
    WidthWidgetSpec(ContentStyle::tailLeaderMarginLeftPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::tailLeaderMarginRightPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(ContentStyle::tailLeaderSpacingPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        ContentStyle::tailLeaderMarginLeftPx.st(), ContentStyle::tailLeaderMarginRightPx.st(),
        ContentStyle::tailLeaderSpacingPx.st(),
        unionName = "tailLeaderGapsPx",
        settingIcons = listOf(LEADER_GAP_MARGIN_LEFT_ICON, LEADER_GAP_MARGIN_RIGHT_ICON, LEADER_GAP_SPACING_ICON)
    )
)


private val LETTER_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<LetterStyle, *>> = listOf(
    UnitWidgetSpec(
        LetterStyle::heightPx.st(), LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st(),
        LetterStyle::superscript.st(), LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st(),
        unit = "px"
    ),
    UnitWidgetSpec(LetterStyle::trackingEm.st(), unit = "em"),
    WidthWidgetSpec(LetterStyle::heightPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        LetterStyle::heightPx.st(),
        unionLabelL10nKey = "height",
        settingIcons = listOf(FONT_HEIGHT_TOTAL_ICON)
    ),
    WidthWidgetSpec(LetterStyle::leadingTopRh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(LetterStyle::leadingBottomRh.st(), WidthSpec.LITTLE),
    MultiplierWidgetSpec(
        LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st(),
        getMultiplier = { _, style -> style.heightPx }
    ),
    UnionWidgetSpec(
        LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st(),
        unionName = "leadingRh",
        settingIcons = listOf(FONT_HEIGHT_LEADING_TOP_ICON, FONT_HEIGHT_LEADING_BOTTOM_ICON)
    ),
    NewSectionWidgetSpec(LetterStyle::trackingEm.st()),
    NumberWidgetSpec(LetterStyle::trackingEm.st(), sensitivity = 0.001),
    UnionWidgetSpec(
        LetterStyle::uppercase.st(), LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st(),
        settingLabels = listOf(1, 2)
    ),
    ToggleButtonGroupWidgetSpec(LetterStyle::smallCaps.st(), ICON),
    ToggleButtonGroupWidgetSpec(LetterStyle::superscript.st(), ICON),
    WidthWidgetSpec(LetterStyle::superscriptScaling.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::superscriptScaling.st(), sensitivity = 0.002),
    WidthWidgetSpec(LetterStyle::superscriptHOffsetRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::superscriptVOffsetRfh.st(), WidthSpec.TINY),
    MultiplierWidgetSpec(
        LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st(),
        getMultiplier = { _, style -> style.heightPx * (1.0 - style.leadingTopRh - style.leadingBottomRh) }
    ),
    UnionWidgetSpec(
        LetterStyle::superscript.st(), LetterStyle::superscriptScaling.st(),
        LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st(),
        settingIcons = listOf(null, ZOOM_ICON, ARROW_LEFT_RIGHT_ICON, ARROW_UP_DOWN_ICON),
        settingNewlines = listOf(1)
    ),
    NumberWidgetSpec(LetterStyle::hScaling.st(), sensitivity = 0.002),
    SimpleListWidgetSpec(LetterStyle::features.st(), newElement = FontFeature("", 1)),
    NewSectionWidgetSpec(LetterStyle::layers.st()),
    LayerListWidgetSpec(
        LetterStyle::layers.st(),
        newElement = PRESET_LAYER,
        advancedSettings = setOf(
            Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(), Layer::stripeWidenTopRfh.st(),
            Layer::stripeWidenBottomRfh.st(), Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st(),
            Layer::stripeDashPatternRfh.st(), Layer::dilationRfh.st(), Layer::dilationJoin.st(), Layer::contour.st(),
            Layer::contourThicknessRfh.st(), Layer::contourJoin.st(), Layer::offsetCoordinateSystem.st(),
            Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(), Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
            Layer::hScaling.st(), Layer::vScaling.st(), Layer::hShearing.st(), Layer::vShearing.st(),
            Layer::anchor.st(), Layer::anchorSiblingLayer.st(), Layer::clearingLayers.st(), Layer::clearingRfh.st(),
            Layer::clearingJoin.st(), Layer::blurRadiusRfh.st()
        )
    )
)


private val LAYER_WIDGET_SPECS: List<StyleWidgetSpec<Layer, *>> = listOf(
    UnitWidgetSpec(
        Layer::coloring.st(), Layer::color1.st(), Layer::color2.st(),
        Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st(),
        Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        Layer::stripeCornerRadiusRfh.st(), Layer::stripeDashPatternRfh.st(),
        Layer::dilationRfh.st(), Layer::contourThicknessRfh.st(),
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(), Layer::offsetDistanceRfh.st(),
        Layer::clearingRfh.st(), Layer::blurRadiusRfh.st(),
        unit = "px"
    ),
    UnitWidgetSpec(Layer::gradientAngleDeg.st(), Layer::offsetAngleDeg.st(), unit = "°"),
    MultiplierWidgetSpec(
        Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st(),
        Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(), Layer::stripeWidenLeftRfh.st(),
        Layer::stripeWidenRightRfh.st(), Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        Layer::stripeCornerRadiusRfh.st(), Layer::stripeDashPatternRfh.st(), Layer::dilationRfh.st(),
        Layer::contourThicknessRfh.st(), Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(), Layer::offsetDistanceRfh.st(),
        Layer::clearingRfh.st(), Layer::blurRadiusRfh.st(),
        getMultiplier = { styling, style ->
            val letterStyle = styling.getParentStyle(style) as LetterStyle
            letterStyle.heightPx * (1.0 - letterStyle.leadingTopRh - letterStyle.leadingBottomRh)
        }
    ),
    ToggleButtonGroupWidgetSpec(Layer::coloring.st(), ICON),
    WidthWidgetSpec(Layer::color1.st(), WidthSpec.TINIER),
    WidthWidgetSpec(Layer::color2.st(), WidthSpec.TINIER),
    WidthWidgetSpec(Layer::gradientAngleDeg.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::gradientExtentRfh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::gradientShiftRfh.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        Layer::coloring.st(), Layer::color1.st(), Layer::color2.st(),
        Layer::gradientAngleDeg.st(), Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st(),
        settingIcons = listOf(null, null, null, ANGLE_ICON, SIZE_HEIGHT_ICON, ARROW_DIAGONAL_ICON)
    ),
    ToggleButtonGroupWidgetSpec(Layer::shape.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(Layer::stripePreset.st(), ICON),
    WidthWidgetSpec(Layer::stripeHeightRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeOffsetRfh.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        settingIcons = listOf(null, SIZE_HEIGHT_ICON, ARROW_UP_DOWN_ICON)
    ),
    WidthWidgetSpec(Layer::stripeWidenLeftRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenRightRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenTopRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenBottomRfh.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        unionName = "stripeWidenRfh",
        settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON, BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    ),
    ToggleButtonGroupWidgetSpec(Layer::stripeCornerJoin.st(), ICON),
    WidthWidgetSpec(Layer::stripeCornerRadiusRfh.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st(), settingLabels = listOf(1)),
    WidthWidgetSpec(Layer::stripeDashPatternRfh.st(), WidthSpec.LITTLE),
    SimpleListWidgetSpec(
        Layer::stripeDashPatternRfh.st(),
        newElement = 0.1, newElementIsLastElement = true, elementsPerRow = 2
    ),
    WidthWidgetSpec(Layer::dilationRfh.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(Layer::dilationJoin.st(), ICON),
    UnionWidgetSpec(Layer::dilationRfh.st(), Layer::dilationJoin.st(), unionName = "dilation"),
    WidthWidgetSpec(Layer::contourThicknessRfh.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(Layer::contourJoin.st(), ICON),
    UnionWidgetSpec(Layer::contour.st(), Layer::contourThicknessRfh.st(), Layer::contourJoin.st()),
    ToggleButtonGroupWidgetSpec(Layer::offsetCoordinateSystem.st(), ICON),
    WidthWidgetSpec(Layer::hOffsetRfh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vOffsetRfh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::offsetAngleDeg.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::offsetDistanceRfh.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(),
        Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
        Layer::offsetCoordinateSystem.st(),
        unionName = "offset",
        settingIcons = listOf(ARROW_LEFT_RIGHT_ICON, ARROW_UP_DOWN_ICON, ANGLE_ICON, ARROW_DIAGONAL_ICON, null),
        settingGaps = listOf(null, "0", null, "unrel")
    ),
    WidthWidgetSpec(Layer::hScaling.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vScaling.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::hScaling.st(), sensitivity = 0.002),
    NumberWidgetSpec(Layer::vScaling.st(), sensitivity = 0.002),
    UnionWidgetSpec(
        Layer::hScaling.st(), Layer::vScaling.st(),
        unionName = "scaling", settingIcons = listOf(BEARING_LEFT_RIGHT_ICON, BEARING_TOP_BOTTOM_ICON)
    ),
    WidthWidgetSpec(Layer::hShearing.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vShearing.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::hShearing.st(), sensitivity = 0.005),
    NumberWidgetSpec(Layer::vShearing.st(), sensitivity = 0.005),
    UnionWidgetSpec(
        Layer::hShearing.st(), Layer::vShearing.st(),
        unionName = "shearing", settingIcons = listOf(SHEARING_HORIZONTAL_ICON, SHEARING_VERTICAL_ICON)
    ),
    UnionWidgetSpec(Layer::anchor.st(), Layer::anchorSiblingLayer.st()),
    WidthWidgetSpec(Layer::clearingRfh.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::clearingRfh.st(), sensitivity = 0.02),
    ToggleButtonGroupWidgetSpec(Layer::clearingJoin.st(), ICON),
    UnionWidgetSpec(
        Layer::clearingLayers.st(), Layer::clearingRfh.st(), Layer::clearingJoin.st(),
        unionName = "clearing"
    ),
    NumberWidgetSpec(Layer::blurRadiusRfh.st(), sensitivity = 0.05)
)


private val PICTURE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PictureStyle, *>> = listOf(
    UnitWidgetSpec(
        PictureStyle::widthPx.st(), PictureStyle::heightPx.st(),
        PictureStyle::cropLeftPx.st(), PictureStyle::cropRightPx.st(),
        PictureStyle::cropTopPx.st(), PictureStyle::cropBottomPx.st(),
        unit = "px"
    ),
    UnitWidgetSpec(PictureStyle::rotationDeg.st(), unit = "°"),
    WidthWidgetSpec(PictureStyle::widthPx.st(), WidthSpec.NARROW),
    WidthWidgetSpec(PictureStyle::heightPx.st(), WidthSpec.NARROW),
    UnionWidgetSpec(
        PictureStyle::widthPx.st(), PictureStyle::heightPx.st(),
        unionLabelL10nKey = "resolution", settingIcons = listOf(SIZE_WIDTH_ICON, SIZE_HEIGHT_ICON)
    ),
    OverrideWidgetSpec(PictureStyle::widthPx.st()) { _, style ->
        basicEmbeddedPic(style, null, style.heightPx.value)?.widthBeforeRotation ?: 0.0
    },
    OverrideWidgetSpec(PictureStyle::heightPx.st()) { _, style ->
        basicEmbeddedPic(style, style.widthPx.value, null)?.heightBeforeRotation ?: 0.0
    },
    WidthWidgetSpec(PictureStyle::cropLeftPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(PictureStyle::cropRightPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(PictureStyle::cropTopPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(PictureStyle::cropBottomPx.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        PictureStyle::cropLeftPx.st(), PictureStyle::cropRightPx.st(),
        PictureStyle::cropTopPx.st(), PictureStyle::cropBottomPx.st(),
        unionName = "crop",
        settingIcons = listOf(BEARING_RIGHT_ICON, BEARING_LEFT_ICON, BEARING_BOTTOM_ICON, BEARING_TOP_ICON)
    ),
    UnionWidgetSpec(
        PictureStyle::hFlip.st(), PictureStyle::vFlip.st(),
        unionName = "flip", settingIcons = listOf(FLIP_ICON, FLIP_ICON.getRotatedIcon(90.0))
    ),
    NumberWidgetSpec(PictureStyle::rotationDeg.st(), sensitivity = 1.0),
)

private fun basicEmbeddedPic(style: PictureStyle, width: Double?, height: Double?) =
    try {
        style.picture.loader?.picture?.let { picture ->
            DeferredImage.EmbeddedPicture(
                picture, width, height,
                style.cropLeftPx, style.cropRightPx, style.cropTopPx, style.cropBottomPx, style.cropBlankSpace
            )
        }
    } catch (_: RuntimeException) {
        // Catches IllegalStateException and IllegalArgumentException.
        null
    }


private val TAPE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<TapeStyle, *>> = listOf(
    UnitWidgetSpec(
        TapeStyle::widthPx.st(), TapeStyle::heightPx.st(),
        TapeStyle::cropLeftPx.st(), TapeStyle::cropRightPx.st(),
        TapeStyle::cropTopPx.st(), TapeStyle::cropBottomPx.st(),
        unit = "px"
    ),
    UnitWidgetSpec(TapeStyle::rotationDeg.st(), unit = "°"),
    WidthWidgetSpec(TapeStyle::widthPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(TapeStyle::heightPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        TapeStyle::widthPx.st(), TapeStyle::heightPx.st(),
        unionLabelL10nKey = "resolution", settingIcons = listOf(SIZE_WIDTH_ICON, SIZE_HEIGHT_ICON)
    ),
    OverrideWidgetSpec(TapeStyle::widthPx.st()) { _, style ->
        basicEmbeddedTape(style, null, style.heightPx.value)?.resolutionBeforeRotation?.widthPx ?: 0
    },
    OverrideWidgetSpec(TapeStyle::heightPx.st()) { _, style ->
        basicEmbeddedTape(style, style.widthPx.value, null)?.resolutionBeforeRotation?.heightPx ?: 0
    },
    WidthWidgetSpec(TapeStyle::cropLeftPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(TapeStyle::cropRightPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(TapeStyle::cropTopPx.st(), WidthSpec.TINY),
    WidthWidgetSpec(TapeStyle::cropBottomPx.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        TapeStyle::cropLeftPx.st(), TapeStyle::cropRightPx.st(),
        TapeStyle::cropTopPx.st(), TapeStyle::cropBottomPx.st(),
        unionName = "crop",
        settingIcons = listOf(BEARING_RIGHT_ICON, BEARING_LEFT_ICON, BEARING_BOTTOM_ICON, BEARING_TOP_ICON)
    ),
    UnionWidgetSpec(
        TapeStyle::hFlip.st(), TapeStyle::vFlip.st(),
        unionLabelL10nKey = "ui.styling.picture.flip", settingIcons = listOf(FLIP_ICON, FLIP_ICON.getRotatedIcon(90.0))
    ),
    LabelWidgetSpec(TapeStyle::rotationDeg.st(), labelL10nKey = "ui.styling.picture.rotationDeg"),
    NumberWidgetSpec(TapeStyle::rotationDeg.st(), sensitivity = 1.0),
    ToggleButtonGroupWidgetSpec(TapeStyle::temporallyJustify.st(), ICON),
    TimecodeWidgetSpec(
        TapeStyle::leftTemporalMarginFrames.st(), TapeStyle::rightTemporalMarginFrames.st(),
        TapeStyle::fadeInFrames.st(), TapeStyle::fadeOutFrames.st(),
        getFPS = { styling, _ -> styling.global.fps },
        getTimecodeFormat = { styling, _ -> styling.global.timecodeFormat }
    ),
    UnionWidgetSpec(
        TapeStyle::leftTemporalMarginFrames.st(), TapeStyle::rightTemporalMarginFrames.st(),
        unionName = "temporalMarginFrames", settingIcons = listOf(GUILLEMET_LEFT_ICON, GUILLEMET_RIGHT_ICON)
    ),
    UnionWidgetSpec(
        TapeStyle::fadeInFrames.st(), TapeStyle::fadeInTransitionStyleName.st(),
        unionLabelL10nKey = "ui.styling.page.cardFadeIn", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    UnionWidgetSpec(
        TapeStyle::fadeOutFrames.st(), TapeStyle::fadeOutTransitionStyleName.st(),
        unionLabelL10nKey = "ui.styling.page.cardFadeOut", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    NewSectionWidgetSpec(TapeStyle::range.st()),
    WidthWidgetSpec(TapeStyle::range.st(), WidthSpec.WIDE),
    OverrideWidgetSpec(
        TapeStyle::range.st(),
        getDefaultValue = { _, style ->
            when (tapeSpec(style)?.representation?.range) {
                Bitmap.Range.FULL, null -> Range.FULL
                Bitmap.Range.LIMITED -> Range.LIMITED
            }
        }
    ),
    LabelWidgetSpec(TapeStyle::primaries.st(), labelL10nKey = "gamut"),
    WidthWidgetSpec(TapeStyle::primaries.st(), WidthSpec.WIDE),
    NumberWidgetSpec(
        TapeStyle::primaries.st(),
        toString = { code ->
            if (code == -1) "" else try {
                "CICP $code \u2013 ${ColorSpace.Primaries.of(code).name}"
            } catch (_: IllegalArgumentException) {
                "CICP $code"
            }
        }
    ),
    OverrideWidgetSpec(
        TapeStyle::primaries.st(),
        getDefaultValue = { _, style ->
            tapeSpec(style)?.representation?.colorSpace?.primaries?.code ?: -1
        }
    ),
    LabelWidgetSpec(TapeStyle::transfer.st(), label = "EOTF"),
    WidthWidgetSpec(TapeStyle::transfer.st(), WidthSpec.WIDE),
    NumberWidgetSpec(
        TapeStyle::transfer.st(),
        toString = { code ->
            if (code == -1) "" else try {
                "CICP $code \u2013 ${ColorSpace.Transfer.of(code).name}"
            } catch (_: IllegalArgumentException) {
                "CICP $code"
            }
        }
    ),
    OverrideWidgetSpec(
        TapeStyle::transfer.st(),
        getDefaultValue = { _, style ->
            tapeSpec(style)?.representation?.colorSpace?.transfer?.canonCode ?: -1
        }
    ),
    LabelWidgetSpec(TapeStyle::yuvCoefficients.st(), label = "YUV"),
    WidthWidgetSpec(TapeStyle::yuvCoefficients.st(), WidthSpec.WIDE),
    NumberWidgetSpec(
        TapeStyle::yuvCoefficients.st(),
        toString = { code ->
            if (code == -1) "" else try {
                "CICP $code \u2013 ${Bitmap.YUVCoefficients.of(code).name}"
            } catch (_: IllegalArgumentException) {
                "CICP $code"
            }
        }
    ),
    OverrideWidgetSpec(
        TapeStyle::yuvCoefficients.st(),
        getDefaultValue = { _, style ->
            tapeSpec(style)?.representation?.yuvCoefficients?.code ?: -1
        }
    ),
    WidthWidgetSpec(TapeStyle::alpha.st(), WidthSpec.WIDE),
    OverrideWidgetSpec(
        TapeStyle::alpha.st(),
        getDefaultValue = { _, style ->
            when (tapeSpec(style)?.representation?.alpha) {
                Bitmap.Alpha.OPAQUE, Bitmap.Alpha.STRAIGHT, null -> Alpha.STRAIGHT
                Bitmap.Alpha.PREMULTIPLIED -> Alpha.PREMULTIPLIED
            }
        }
    ),
    LabelWidgetSpec(TapeStyle::scan.st(), labelL10nKey = "scan"),
    WidthWidgetSpec(TapeStyle::scan.st(), WidthSpec.WIDE),
    OverrideWidgetSpec(
        TapeStyle::scan.st(),
        getDefaultValue = { _, style ->
            val spec = tapeSpec(style)
            when (spec?.scan) {
                Bitmap.Scan.PROGRESSIVE, null -> Scan.PROGRESSIVE
                Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST -> Scan.INTERLACED_TOP_FIELD_FIRST
                Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST -> Scan.INTERLACED_BOT_FIELD_FIRST
            }
        }
    )
)

private fun tapeSpec(style: TapeStyle) =
    try {
        style.tape.tape?.spec
    } catch (_: IllegalStateException) {
        null
    }

private fun basicEmbeddedTape(style: TapeStyle, width: Int?, height: Int?) =
    try {
        style.tape.tape?.let { tape ->
            DeferredImage.EmbeddedTape(
                tape, width, height,
                style.cropLeftPx, style.cropRightPx, style.cropTopPx, style.cropBottomPx
            )
        }
    } catch (_: RuntimeException) {
        // Catches IllegalStateException and IllegalArgumentException.
        null
    }


sealed class StyleWidgetSpec<S : Style, SS : StyleSetting<S, *>>(
    vararg settings: SS
) {
    val settings: List<SS> = settings.toList()
}


class NewSectionWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(setting)


class LabelWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>,
    val label: String? = null,
    val labelL10nKey: String? = null,
    val descL10nKey: String? = null
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(setting)


class UnitWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val unit: String
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(*settings)


class WidthWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>,
    val widthSpec: WidthSpec
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(setting)


class NumberWidgetSpec<S : Style, SUBJ : Number>(
    setting: StyleSetting<S, SUBJ>,
    val sensitivity: Double? = null,
    val toString: ((SUBJ) -> String)? = null
) : StyleWidgetSpec<S, StyleSetting<S, SUBJ>>(setting)


class ToggleButtonGroupWidgetSpec<S : Style, SUBJ : Any>(
    setting: StyleSetting<S, SUBJ>,
    val show: Show,
    val getFixedIcon: ((SUBJ) -> Icon)? = null,
    val getDynIcon: ((Styling, S, SUBJ) -> Icon)? = null
) : StyleWidgetSpec<S, StyleSetting<S, SUBJ>>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }

    init {
        require(getFixedIcon == null || getDynIcon == null)
        if (getFixedIcon != null || getDynIcon != null)
            require(show == ICON || show == ICON_AND_LABEL)
    }
}


class MultiplierWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Double>,
    val getMultiplier: (Styling, S) -> Double
) : StyleWidgetSpec<S, StyleSetting<S, Double>>(*settings)


class TimecodeWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Number>,
    val getFPS: (Styling, S) -> FPS,
    val getTimecodeFormat: (Styling, S) -> TimecodeFormat
) : StyleWidgetSpec<S, StyleSetting<S, Number>>(*settings)


class UnionWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val unionName: String? = null,
    val unionLabel: String? = null,
    val unionLabelL10nKey: String? = null,
    val unionDescL10nKey: String? = null,
    val settingLabels: List<Int> = emptyList(),
    val settingIcons: List<Icon?>? = null,
    val settingGaps: List<String?>? = null,
    val settingNewlines: List<Int> = emptyList()
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(*settings) {
    init {
        require(settingLabels.all(settings.indices::contains))
        require(settingNewlines.all(settings.indices::contains))
        if (settingIcons != null)
            require(settings.size == settingIcons.size)
        if (settingGaps != null)
            require(settings.size == settingGaps.size + 1)
    }
}


class OverrideWidgetSpec<S : Style, SUBJ : Any>(
    setting: OverrideStyleSetting<S, SUBJ>,
    val getDefaultValue: (Context, S) -> SUBJ
) : StyleWidgetSpec<S, OverrideStyleSetting<S, SUBJ>>(setting) {
    data class Context(
        val mostRecentRuntimeFrames: Int,
        val mostRecentScrollStyleRuntimeFrames: Map<String, Int>
    )
}


class SimpleListWidgetSpec<S : Style, SUBJ : Any>(
    setting: ListStyleSetting<S, SUBJ>,
    val newElement: SUBJ? = null,
    val newElementIsLastElement: Boolean = false,
    val elementsPerRow: Int = 1
) : StyleWidgetSpec<S, ListStyleSetting<S, SUBJ>>(setting)


class LayerListWidgetSpec<S : Style, SUBJ : LayerStyle>(
    setting: ListStyleSetting<S, SUBJ>,
    val newElement: SUBJ,
    val advancedSettings: Set<StyleSetting<SUBJ, Any>>
) : StyleWidgetSpec<S, ListStyleSetting<S, SUBJ>>(setting)


class ChoiceWidgetSpec<S : Style>(
    vararg settings: ListStyleSetting<S, Any>,
    val getNoItemsMsg: (() -> String)? = null
) : StyleWidgetSpec<S, ListStyleSetting<S, Any>>(*settings)
