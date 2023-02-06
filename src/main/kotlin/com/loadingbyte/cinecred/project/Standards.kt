package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.common.l10n
import kotlinx.collections.immutable.persistentListOf
import java.awt.Color
import java.util.*


@Suppress("UNCHECKED_CAST")
fun <S : Style> getPreset(styleClass: Class<S>): S = when (styleClass) {
    Global::class.java -> PRESET_GLOBAL
    PageStyle::class.java -> PRESET_PAGE_STYLE
    ContentStyle::class.java -> PRESET_CONTENT_STYLE
    LetterStyle::class.java -> PRESET_LETTER_STYLE
    Layer::class.java -> PRESET_LAYER
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as S


val PRESET_GLOBAL = Global(
    resolution = Resolution(2048, 858),
    fps = FPS(24, 1),
    timecodeFormat = TimecodeFormat.SMPTE_NON_DROP_FRAME,
    runtimeFrames = Opt(false, 0),
    grounding = Color.BLACK,
    unitVGapPx = 32.0,
    locale = Locale.ENGLISH,
    uppercaseExceptions = persistentListOf(
        "_af_", "_auf_", "_d\u2019#", "_da_", "_Da#", "_dai_", "_Dai#", "_dal_", "_Dal#", "_dalla_",
        "_Dalla#", "_das_", "_de_", "_de\u2019_", "_dei_", "_Dei#", "_del_", "_Del#", "_della_",
        "_Della#", "_der_", "_des_", "_di_", "_Di#", "_do_", "_dos_", "_du_", "_e_", "_Mc#", "_Mac#",
        "_of_", "_ten_", "_thoe_", "_til_", "_to_", "_tot_", "_van_", "_von_", "_von und zu_", "_zu_"
    )
)


val PRESET_PAGE_STYLE = PageStyle(
    name = "???",
    behavior = PageBehavior.SCROLL,
    afterwardSlugFrames = 24,
    cardDurationFrames = 96,
    cardFadeInFrames = 12,
    cardFadeOutFrames = 12,
    scrollMeltWithPrev = false,
    scrollMeltWithNext = false,
    scrollPxPerFrame = 3.0
)


val PRESET_CONTENT_STYLE = ContentStyle(
    name = "???",
    blockOrientation = BlockOrientation.VERTICAL,
    spineAttachment = SpineAttachment.BODY_CENTER,
    vMarginPx = 0.0,
    bodyLetterStyleName = "",
    bodyLayout = BodyLayout.GRID,
    gridFillingOrder = GridFillingOrder.L2R_T2B,
    gridFillingBalanced = true,
    gridStructure = GridStructure.FREE,
    gridMatchColWidths = MatchExtent.OFF,
    gridMatchColWidthsAcrossStyles = persistentListOf(),
    gridMatchColUnderoccupancy = GridColUnderoccupancy.LEFT_OMIT,
    gridMatchRowHeight = MatchExtent.OFF,
    gridMatchRowHeightAcrossStyles = persistentListOf(),
    gridCellHJustifyPerCol = persistentListOf(HJustify.CENTER),
    gridCellVJustify = VJustify.MIDDLE,
    gridRowGapPx = 0.0,
    gridColGapPx = 32.0,
    flowDirection = FlowDirection.L2R,
    flowLineHJustify = LineHJustify.CENTER,
    flowSquareCells = false,
    flowMatchCellWidth = MatchExtent.OFF,
    flowMatchCellWidthAcrossStyles = persistentListOf(),
    flowMatchCellHeight = MatchExtent.OFF,
    flowMatchCellHeightAcrossStyles = persistentListOf(),
    flowCellHJustify = HJustify.CENTER,
    flowCellVJustify = VJustify.MIDDLE,
    flowLineWidthPx = 1000.0,
    flowLineGapPx = 0.0,
    flowHGapPx = 32.0,
    flowSeparator = "\u2022",
    paragraphsLineHJustify = LineHJustify.CENTER,
    paragraphsLineWidthPx = 600.0,
    paragraphsParaGapPx = 8.0,
    paragraphsLineGapPx = 0.0,
    hasHead = false,
    headLetterStyleName = "",
    headMatchWidth = MatchExtent.OFF,
    headMatchWidthAcrossStyles = persistentListOf(),
    headHJustify = HJustify.CENTER,
    headVJustify = VJustify.TOP,
    headGapPx = 4.0,
    hasTail = false,
    tailLetterStyleName = "",
    tailMatchWidth = MatchExtent.OFF,
    tailMatchWidthAcrossStyles = persistentListOf(),
    tailHJustify = HJustify.CENTER,
    tailVJustify = VJustify.TOP,
    tailGapPx = 4.0
)


val PRESET_LAYER = Layer(
    color = Color.WHITE,
    shape = LayerShape.STRIPE,
    stripePreset = StripePreset.UNDERLINE,
    stripeHeightRfh = 0.05,
    stripeOffsetRfh = 0.0,
    stripeWidenLeftRfh = 0.0,
    stripeWidenRightRfh = 0.0,
    stripeWidenTopRfh = 0.0,
    stripeWidenBottomRfh = 0.0,
    stripeCornerJoin = LineJoin.MITER,
    stripeCornerRadiusRfh = 0.2,
    stripeDashPatternRfh = persistentListOf(),
    cloneLayers = persistentListOf(),
    dilationRfh = 0.0,
    dilationJoin = LineJoin.MITER,
    contour = false,
    contourThicknessRfh = 0.02,
    contourJoin = LineJoin.MITER,
    hOffsetRfh = 0.0,
    vOffsetRfh = 0.0,
    hScaling = 1.0,
    vScaling = 1.0,
    hShearing = 0.0,
    vShearing = 0.0,
    anchorInStripe = false,
    clearingLayers = persistentListOf(),
    clearingRfh = 0.0,
    clearingJoin = LineJoin.MITER,
    blurRadiusRfh = 0.0
)


val PRESET_LETTER_STYLE = LetterStyle(
    name = "???",
    fontName = "Archivo Narrow Regular",
    heightPx = 32.0,
    leadingTopRh = 0.0,
    leadingBottomRh = 0.0,
    trackingEm = 0.0,
    kerning = true,
    ligatures = true,
    uppercase = false,
    useUppercaseExceptions = true,
    useUppercaseSpacing = true,
    smallCaps = SmallCaps.OFF,
    superscript = Superscript.OFF,
    superscriptScaling = 1.0,
    superscriptHOffsetRfh = 0.0,
    superscriptVOffsetRfh = 0.0,
    hScaling = 1.0,
    features = persistentListOf(),
    inheritLayersFromStyle = Opt(false, ""),
    layers = persistentListOf(PRESET_LAYER.copy(shape = LayerShape.TEXT))
)


val PLACEHOLDER_PAGE_STYLE = PRESET_PAGE_STYLE.copy(
    name = l10n("project.placeholder")
)


val PLACEHOLDER_CONTENT_STYLE = PRESET_CONTENT_STYLE.copy(
    name = l10n("project.placeholder"),
    blockOrientation = BlockOrientation.HORIZONTAL,
    hasHead = true,
    headGapPx = 32.0,
    hasTail = true,
    tailGapPx = 32.0
)


val PLACEHOLDER_LETTER_STYLE = PRESET_LETTER_STYLE.copy(
    name = l10n("project.placeholder"),
    layers = persistentListOf(
        PRESET_LAYER.copy(
            color = Color.ORANGE,
            shape = LayerShape.STRIPE,
            stripePreset = StripePreset.BACKGROUND
        ),
        PRESET_LAYER.copy(
            color = Color.BLACK,
            shape = LayerShape.TEXT
        )
    )
)
