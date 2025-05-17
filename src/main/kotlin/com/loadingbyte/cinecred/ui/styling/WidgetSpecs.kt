package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.common.l10n
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
    UnitWidgetSpec(Global::resolution.st(), Global::unitVGapPx.st(), unit = "px"),
    UnitWidgetSpec(Global::fps.st(), unit = "fps"),
    TimecodeWidgetSpec(
        Global::runtimeFrames.st(),
        getFPS = { _, global -> global.fps },
        getTimecodeFormat = { _, global -> global.timecodeFormat }
    ),
    WidthWidgetSpec(Global::uppercaseExceptions.st(), WidthSpec.NARROW)
)


private val PAGE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PageStyle, *>> = listOf(
    ToggleButtonGroupWidgetSpec(PageStyle::behavior.st(), LABEL),
    TimecodeWidgetSpec(
        PageStyle::subsequentGapFrames.st(), PageStyle::cardRuntimeFrames.st(),
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st(), PageStyle::scrollRuntimeFrames.st(),
        getFPS = { styling, _ -> styling.global.fps },
        getTimecodeFormat = { styling, _ -> styling.global.timecodeFormat }
    ),
    UnitWidgetSpec(PageStyle::scrollPxPerFrame.st(), unit = "px"),
    UnionWidgetSpec(
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeInTransitionStyleName.st(),
        unionName = "cardFadeIn", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    UnionWidgetSpec(
        PageStyle::cardFadeOutFrames.st(), PageStyle::cardFadeOutTransitionStyleName.st(),
        unionName = "cardFadeOut", settingIcons = listOf(null, TRANSITION_ICON)
    )
)


private val CONTENT_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<ContentStyle, *>> = listOf(
    UnitWidgetSpec(
        ContentStyle::gridForceColWidthPx.st(), ContentStyle::gridForceRowHeightPx.st(),
        ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st(), ContentStyle::flowForceCellWidthPx.st(),
        ContentStyle::flowForceCellHeightPx.st(), ContentStyle::flowLineWidthPx.st(), ContentStyle::flowLineGapPx.st(),
        ContentStyle::flowHGapPx.st(), ContentStyle::paragraphsLineWidthPx.st(), ContentStyle::paragraphsParaGapPx.st(),
        ContentStyle::paragraphsLineGapPx.st(),
        ContentStyle::headForceWidthPx.st(), ContentStyle::headGapPx.st(), ContentStyle::headLeaderSpacingPx.st(),
        ContentStyle::tailForceWidthPx.st(), ContentStyle::tailGapPx.st(), ContentStyle::tailLeaderSpacingPx.st(),
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
        unionName = "vMarginPx", unionUnit = "px", settingIcons = listOf(GUILLEMET_UP_ICON, GUILLEMET_DOWN_ICON)
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
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowLineHJustify.st(), ICON),
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
    NumberWidgetSpec(ContentStyle::flowLineWidthPx.st(), step = 10.0),
    WidthWidgetSpec(ContentStyle::flowSeparator.st(), WidthSpec.NARROW),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowSeparatorVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), step = 10.0),
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    WidthWidgetSpec(ContentStyle::headForceWidthPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHarmonizeWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::headHarmonizeWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(ContentStyle::headHarmonizeWidth.st(), ContentStyle::headHarmonizeWidthAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    UnionWidgetSpec(ContentStyle::headVShelve.st(), ContentStyle::headVJustify.st(), unionName = "headVJustify"),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVShelve.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
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
        unionName = "headLeaderGapsPx", unionUnit = "px",
        settingIcons = listOf(LEADER_GAP_MARGIN_LEFT_ICON, LEADER_GAP_MARGIN_RIGHT_ICON, LEADER_GAP_SPACING_ICON)
    ),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    WidthWidgetSpec(ContentStyle::tailForceWidthPx.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHarmonizeWidth.st(), ICON),
    WidthWidgetSpec(ContentStyle::tailHarmonizeWidthAcrossStyles.st(), WidthSpec.SQUEEZE),
    UnionWidgetSpec(ContentStyle::tailHarmonizeWidth.st(), ContentStyle::tailHarmonizeWidthAcrossStyles.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    UnionWidgetSpec(ContentStyle::tailVShelve.st(), ContentStyle::tailVJustify.st(), unionName = "tailVJustify"),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVShelve.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON),
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
        unionName = "tailLeaderGapsPx", unionUnit = "px",
        settingIcons = listOf(LEADER_GAP_MARGIN_LEFT_ICON, LEADER_GAP_MARGIN_RIGHT_ICON, LEADER_GAP_SPACING_ICON)
    )
)


private val LETTER_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<LetterStyle, *>> = listOf(
    WidthWidgetSpec(LetterStyle::heightPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        LetterStyle::heightPx.st(),
        unionUnit = "px",
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
        unionName = "leadingRh", unionUnit = "px",
        settingIcons = listOf(FONT_HEIGHT_LEADING_TOP_ICON, FONT_HEIGHT_LEADING_BOTTOM_ICON)
    ),
    UnitWidgetSpec(LetterStyle::trackingEm.st(), unit = "em"),
    NewSectionWidgetSpec(LetterStyle::trackingEm.st()),
    NumberWidgetSpec(LetterStyle::trackingEm.st(), step = 0.01),
    UnionWidgetSpec(
        LetterStyle::uppercase.st(), LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st(),
        settingLabels = listOf(1, 2)
    ),
    ToggleButtonGroupWidgetSpec(LetterStyle::smallCaps.st(), ICON),
    ToggleButtonGroupWidgetSpec(LetterStyle::superscript.st(), ICON),
    WidthWidgetSpec(LetterStyle::superscriptScaling.st(), WidthSpec.TINY),
    NumberWidgetSpec(LetterStyle::superscriptScaling.st(), step = 0.01),
    WidthWidgetSpec(LetterStyle::superscriptHOffsetRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(LetterStyle::superscriptVOffsetRfh.st(), WidthSpec.TINY),
    MultiplierWidgetSpec(
        LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st(),
        getMultiplier = { _, style -> style.heightPx * (1.0 - style.leadingTopRh - style.leadingBottomRh) }
    ),
    UnionWidgetSpec(
        LetterStyle::superscript.st(), LetterStyle::superscriptScaling.st(),
        LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st(),
        unionUnit = "px",
        settingIcons = listOf(null, ZOOM_ICON, ARROW_LEFT_RIGHT_ICON, ARROW_UP_DOWN_ICON),
        settingNewlines = listOf(1)
    ),
    NumberWidgetSpec(LetterStyle::hScaling.st(), step = 0.01),
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
        unionUnit = "px", settingIcons = listOf(null, null, null, ANGLE_ICON, SIZE_HEIGHT_ICON, ARROW_DIAGONAL_ICON)
    ),
    ToggleButtonGroupWidgetSpec(Layer::shape.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(Layer::stripePreset.st(), ICON),
    WidthWidgetSpec(Layer::stripeHeightRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeOffsetRfh.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st(),
        unionUnit = "px", settingIcons = listOf(null, SIZE_HEIGHT_ICON, ARROW_UP_DOWN_ICON)
    ),
    WidthWidgetSpec(Layer::stripeWidenLeftRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenRightRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenTopRfh.st(), WidthSpec.TINY),
    WidthWidgetSpec(Layer::stripeWidenBottomRfh.st(), WidthSpec.TINY),
    UnionWidgetSpec(
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st(),
        unionName = "stripeWidenRfh", unionUnit = "px",
        settingIcons = listOf(BEARING_LEFT_ICON, BEARING_RIGHT_ICON, BEARING_TOP_ICON, BEARING_BOTTOM_ICON)
    ),
    ToggleButtonGroupWidgetSpec(Layer::stripeCornerJoin.st(), ICON),
    WidthWidgetSpec(Layer::stripeCornerRadiusRfh.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st(),
        settingLabels = listOf(1), settingUnits = listOf(null, "px")
    ),
    UnitWidgetSpec(Layer::stripeDashPatternRfh.st(), unit = "px"),
    WidthWidgetSpec(Layer::stripeDashPatternRfh.st(), WidthSpec.LITTLE),
    SimpleListWidgetSpec(
        Layer::stripeDashPatternRfh.st(),
        newElement = 0.1, newElementIsLastElement = true, elementsPerRow = 2
    ),
    WidthWidgetSpec(Layer::dilationRfh.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(Layer::dilationJoin.st(), ICON),
    UnionWidgetSpec(
        Layer::dilationRfh.st(), Layer::dilationJoin.st(),
        unionName = "dilation", unionUnit = "px"
    ),
    WidthWidgetSpec(Layer::contourThicknessRfh.st(), WidthSpec.LITTLE),
    ToggleButtonGroupWidgetSpec(Layer::contourJoin.st(), ICON),
    UnionWidgetSpec(
        Layer::contour.st(), Layer::contourThicknessRfh.st(), Layer::contourJoin.st(),
        unionUnit = "px"
    ),
    ToggleButtonGroupWidgetSpec(Layer::offsetCoordinateSystem.st(), ICON),
    WidthWidgetSpec(Layer::hOffsetRfh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vOffsetRfh.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::offsetAngleDeg.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::offsetDistanceRfh.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(),
        Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
        Layer::offsetCoordinateSystem.st(),
        unionName = "offset", unionUnit = "px",
        settingIcons = listOf(ARROW_LEFT_RIGHT_ICON, ARROW_UP_DOWN_ICON, ANGLE_ICON, ARROW_DIAGONAL_ICON, null),
        settingGaps = listOf(null, "0", null, "unrel")
    ),
    WidthWidgetSpec(Layer::hScaling.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vScaling.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::hScaling.st(), step = 0.01),
    NumberWidgetSpec(Layer::vScaling.st(), step = 0.01),
    UnionWidgetSpec(
        Layer::hScaling.st(), Layer::vScaling.st(),
        unionName = "scaling", settingIcons = listOf(BEARING_LEFT_RIGHT_ICON, BEARING_TOP_BOTTOM_ICON)
    ),
    WidthWidgetSpec(Layer::hShearing.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(Layer::vShearing.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::hShearing.st(), step = 0.05),
    NumberWidgetSpec(Layer::vShearing.st(), step = 0.05),
    UnionWidgetSpec(
        Layer::hShearing.st(), Layer::vShearing.st(),
        unionName = "shearing", settingIcons = listOf(SHEARING_HORIZONTAL_ICON, SHEARING_VERTICAL_ICON)
    ),
    UnionWidgetSpec(Layer::anchor.st(), Layer::anchorSiblingLayer.st()),
    WidthWidgetSpec(Layer::clearingRfh.st(), WidthSpec.LITTLE),
    NumberWidgetSpec(Layer::clearingRfh.st(), step = 0.1),
    ToggleButtonGroupWidgetSpec(Layer::clearingJoin.st(), ICON),
    UnionWidgetSpec(
        Layer::clearingLayers.st(), Layer::clearingRfh.st(), Layer::clearingJoin.st(),
        unionName = "clearing", unionUnit = "px"
    ),
    UnitWidgetSpec(Layer::blurRadiusRfh.st(), unit = "px")
)


private val PICTURE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<PictureStyle, *>> = listOf(
    WidthWidgetSpec(PictureStyle::widthPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(PictureStyle::heightPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        PictureStyle::widthPx.st(), PictureStyle::heightPx.st(),
        unionName = "resolution", unionUnit = "px", settingIcons = listOf(SIZE_WIDTH_ICON, SIZE_HEIGHT_ICON)
    ),
    ZeroInitWidgetSpec(PictureStyle::widthPx.st()) { style ->
        try {
            style.picture.loader?.picture?.width
        } catch (_: IllegalStateException) {
            null
        }
    },
    ZeroInitWidgetSpec(PictureStyle::heightPx.st()) { style ->
        try {
            style.picture.loader?.picture?.height
        } catch (_: IllegalStateException) {
            null
        }
    }
)


private val TAPE_STYLE_WIDGET_SPECS: List<StyleWidgetSpec<TapeStyle, *>> = listOf(
    WidthWidgetSpec(TapeStyle::widthPx.st(), WidthSpec.LITTLE),
    WidthWidgetSpec(TapeStyle::heightPx.st(), WidthSpec.LITTLE),
    UnionWidgetSpec(
        TapeStyle::widthPx.st(), TapeStyle::heightPx.st(),
        unionName = "resolution", unionUnit = "px", settingIcons = listOf(SIZE_WIDTH_ICON, SIZE_HEIGHT_ICON)
    ),
    ZeroInitWidgetSpec(TapeStyle::widthPx.st()) { style ->
        try {
            style.tape.tape?.run { spec.resolution.widthPx }
        } catch (_: IllegalStateException) {
            null
        }
    },
    ZeroInitWidgetSpec(TapeStyle::heightPx.st()) { style ->
        try {
            style.tape.tape?.run { spec.resolution.heightPx }
        } catch (_: IllegalStateException) {
            null
        }
    },
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
        unionName = "fadeIn", settingIcons = listOf(null, TRANSITION_ICON)
    ),
    UnionWidgetSpec(
        TapeStyle::fadeOutFrames.st(), TapeStyle::fadeOutTransitionStyleName.st(),
        unionName = "fadeOut", settingIcons = listOf(null, TRANSITION_ICON)
    )
)


sealed class StyleWidgetSpec<S : Style, SS : StyleSetting<S, *>>(
    vararg settings: SS
) {
    val settings: List<SS> = settings.toList()
}


class NewSectionWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(setting)


class UnitWidgetSpec<S : Style>(
    vararg settings: StyleSetting<S, Any>,
    val unit: String
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(*settings)


class WidthWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>,
    val widthSpec: WidthSpec
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(setting)


class NumberWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val step: Number? = null
) : StyleWidgetSpec<S, StyleSetting<S, Number>>(setting)


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
    val unionUnit: String? = null,
    val settingLabels: List<Int> = emptyList(),
    val settingUnits: List<String?>? = null,
    val settingIcons: List<Icon?>? = null,
    val settingGaps: List<String?>? = null,
    val settingNewlines: List<Int> = emptyList()
) : StyleWidgetSpec<S, StyleSetting<S, Any>>(*settings) {
    init {
        require(settingLabels.all(settings.indices::contains))
        require(settingNewlines.all(settings.indices::contains))
        if (settingUnits != null)
            require(settings.size == settingUnits.size)
        if (settingIcons != null)
            require(settings.size == settingIcons.size)
        if (settingGaps != null)
            require(settings.size == settingGaps.size + 1)
    }
}


class ZeroInitWidgetSpec<S : Style, SUBJ : Number>(
    setting: OptStyleSetting<S, SUBJ>,
    val getInitialValue: (S) -> SUBJ?
) : StyleWidgetSpec<S, OptStyleSetting<S, SUBJ>>(setting)


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
