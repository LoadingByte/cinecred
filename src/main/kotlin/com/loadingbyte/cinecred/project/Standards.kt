package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.Font
import com.loadingbyte.cinecred.imaging.Transition
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.*


@Suppress("UNCHECKED_CAST")
fun <S : Style> getPreset(styleClass: Class<S>): S = when (styleClass) {
    Global::class.java -> PRESET_GLOBAL
    PageStyle::class.java -> PRESET_PAGE_STYLE
    ContentStyle::class.java -> PRESET_CONTENT_STYLE
    LetterStyle::class.java -> PRESET_LETTER_STYLE
    Layer::class.java -> PRESET_LAYER
    TransitionStyle::class.java -> PRESET_TRANSITION_STYLE
    PictureStyle::class.java -> PRESET_PICTURE_STYLE
    TapeStyle::class.java -> PRESET_TAPE_STYLE
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as S


val PRESET_GLOBAL = Global(
    resolution = Resolution(2048, 858),
    fps = FPS(24, 1),
    timecodeFormat = TimecodeFormat.SMPTE_NON_DROP_FRAME,
    runtimeFrames = Override(null),
    blankFirstFrame = false,
    blankLastFrame = false,
    grounding = Color4f.BLACK,
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
    subsequentGapFrames = 24,
    behavior = PageBehavior.SCROLL,
    cardRuntimeFrames = 120,
    cardFadeInFrames = 12,
    cardFadeInTransitionStyleName = "",
    cardFadeOutFrames = 12,
    cardFadeOutTransitionStyleName = "",
    scrollMeltWithPrev = false,
    scrollMeltWithNext = false,
    scrollPxPerFrame = 3.0,
    scrollRuntimeFrames = Override(null)
)


val PRESET_CONTENT_STYLE = ContentStyle(
    name = "???",
    blockOrientation = BlockOrientation.VERTICAL,
    spineAttachment = SpineAttachment.BODY_CENTER,
    vMarginTopPx = 0.0,
    vMarginBottomPx = 0.0,
    bodyLetterStyleName = "",
    bodyLayout = BodyLayout.GRID,
    sort = Sort.OFF,
    gridCols = 1,
    gridFillingOrder = GridFillingOrder.L2R_T2B,
    gridFillingBalanced = true,
    gridStructure = GridStructure.FREE,
    gridForceColWidthPx = Opt(false, 0.0),
    gridForceRowHeightPx = Opt(false, 0.0),
    gridHarmonizeColWidths = HarmonizeExtent.OFF,
    gridHarmonizeColWidthsAcrossStyles = persistentListOf(),
    gridHarmonizeColUnderoccupancy = GridColUnderoccupancy.LEFT_OMIT,
    gridHarmonizeRowHeight = HarmonizeExtent.OFF,
    gridHarmonizeRowHeightAcrossStyles = persistentListOf(),
    gridCellHJustifyPerCol = persistentListOf(HJustify.CENTER),
    gridCellVJustify = VJustify.MIDDLE,
    gridTextVJustifyFragments = VTextFragment.FIRST_LINE,
    gridTextVJustify = VJustifyText.BASELINE,
    gridRowGapPx = 0.0,
    gridColGapPx = 32.0,
    flowDirection = FlowDirection.L2R,
    flowRowHJustify = HJustifyCrumbsStack.CENTER,
    flowSquareCells = false,
    flowForceCellWidthPx = Opt(false, 0.0),
    flowForceCellHeightPx = Opt(false, 0.0),
    flowHarmonizeCellWidth = HarmonizeExtent.OFF,
    flowHarmonizeCellWidthAcrossStyles = persistentListOf(),
    flowHarmonizeCellHeight = HarmonizeExtent.OFF,
    flowHarmonizeCellHeightAcrossStyles = persistentListOf(),
    flowCellHJustify = HJustify.CENTER,
    flowCellVJustify = VJustify.MIDDLE,
    flowTextVJustifyFragments = VTextFragment.FIRST_LINE,
    flowTextVJustify = VJustifyText.BASELINE,
    flowRowWidthPx = 1000.0,
    flowRowGapPx = 0.0,
    flowHGapPx = 32.0,
    flowSeparator = "\u2022",
    flowSeparatorLetterStyleName = Opt(false, ""),
    flowSeparatorVJustify = VJustifyText.MIDDLE,
    paragraphsLineHJustify = HJustifyCrumbsStack.CENTER,
    paragraphsLineWidthPx = 600.0,
    paragraphsParaGapPx = 8.0,
    paragraphsLineGapPx = 0.0,
    hasHead = false,
    headLetterStyleName = "",
    headForceWidthPx = Opt(false, 0.0),
    headHarmonizeWidth = HarmonizeExtent.OFF,
    headHarmonizeWidthAcrossStyles = persistentListOf(),
    headHJustify = HJustify.CENTER,
    headVJustifyBodyFragment = VBodyFragment.FIRST_ROW_FIRST_LINE,
    headVJustifyHeadFragment = VTextFragment.FIRST_LINE,
    headVJustify = VJustifyText.MIDDLE,
    headGapPx = 4.0,
    headLeader = "",
    headLeaderLetterStyleName = Opt(false, ""),
    headLeaderHJustify = HJustifyCrumbs.FULL,
    headLeaderVJustify = VJustifyText.BASELINE,
    headLeaderMarginLeftPx = 0.0,
    headLeaderMarginRightPx = 0.0,
    headLeaderSpacingPx = 2.0,
    hasTail = false,
    tailLetterStyleName = "",
    tailForceWidthPx = Opt(false, 0.0),
    tailHarmonizeWidth = HarmonizeExtent.OFF,
    tailHarmonizeWidthAcrossStyles = persistentListOf(),
    tailHJustify = HJustify.CENTER,
    tailVJustifyBodyFragment = VBodyFragment.FIRST_ROW_FIRST_LINE,
    tailVJustifyTailFragment = VTextFragment.FIRST_LINE,
    tailVJustify = VJustifyText.MIDDLE,
    tailGapPx = 4.0,
    tailLeader = "",
    tailLeaderLetterStyleName = Opt(false, ""),
    tailLeaderHJustify = HJustifyCrumbs.FULL,
    tailLeaderVJustify = VJustifyText.BASELINE,
    tailLeaderMarginLeftPx = 0.0,
    tailLeaderMarginRightPx = 0.0,
    tailLeaderSpacingPx = 2.0
)


val PRESET_LETTER_STYLE
    get() = LetterStyle(
        name = "???",
        font = FontRef(Font.bundled("Archivo Narrow Regular")!!),
        variations = FontVariations(persistentMapOf()),
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
        layers = persistentListOf(PRESET_LAYER.copy(shape = LayerShape.TEXT, name = l10n("project.LayerShape.TEXT")))
    )


val PRESET_LAYER = Layer(
    name = "",
    collapsed = true,
    coloring = LayerColoring.PLAIN,
    color1 = Color4f.WHITE,
    color2 = Color4f.GRAY,
    gradientAngleDeg = 0.0,
    gradientExtentRfh = 1.0,
    gradientShiftRfh = 0.0,
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
    offsetCoordinateSystem = CoordinateSystem.CARTESIAN,
    hOffsetRfh = 0.0,
    vOffsetRfh = 0.0,
    offsetAngleDeg = 0.0,
    offsetDistanceRfh = 0.0,
    hScaling = 1.0,
    vScaling = 1.0,
    hShearing = 0.0,
    vShearing = 0.0,
    anchor = LayerAnchor.INDIVIDUAL,
    anchorSiblingLayer = 0,
    clearingLayers = persistentListOf(),
    clearingRfh = 0.0,
    clearingJoin = LineJoin.MITER,
    blurRadiusRfh = 0.0
)


val PRESET_TRANSITION_STYLE = TransitionStyle(
    name = "???",
    graph = Transition.LINEAR
)


val PRESET_PICTURE_STYLE = PictureStyle(
    name = "???",
    volatile = false,
    picture = PictureRef(""),
    widthPx = Override(null),
    heightPx = Override(null),
    cropLeftPx = 0.0,
    cropRightPx = 0.0,
    cropTopPx = 0.0,
    cropBottomPx = 0.0,
    cropBlankSpace = false,
    hFlip = false,
    vFlip = false,
    rotationDeg = 0.0
)


val PRESET_TAPE_STYLE = TapeStyle(
    name = "???",
    volatile = false,
    tape = TapeRef(""),
    widthPx = Override(null),
    heightPx = Override(null),
    cropLeftPx = 0,
    cropRightPx = 0,
    cropTopPx = 0,
    cropBottomPx = 0,
    hFlip = false,
    vFlip = false,
    rotationDeg = 0,
    slice = TapeSlice(TimecodeFormat.SMPTE_NON_DROP_FRAME, null, null),
    loop = false,
    temporallyJustify = HJustify.LEFT,
    leftTemporalMarginFrames = 0,
    rightTemporalMarginFrames = 0,
    fadeInFrames = 0,
    fadeInTransitionStyleName = "",
    fadeOutFrames = 0,
    fadeOutTransitionStyleName = "",
    range = Override(null),
    primaries = Override(null),
    transfer = Override(null),
    yuvCoefficients = Override(null),
    alpha = Override(null),
    scan = Override(null)
)


val PLACEHOLDER_PAGE_STYLE
    get() = PRESET_PAGE_STYLE.copy(
        name = l10n("project.placeholder")
    )


val PLACEHOLDER_CONTENT_STYLE
    get() = PRESET_CONTENT_STYLE.copy(
        name = l10n("project.placeholder"),
        blockOrientation = BlockOrientation.HORIZONTAL,
        hasHead = true,
        headGapPx = 32.0,
        hasTail = true,
        tailGapPx = 32.0
    )


val PLACEHOLDER_LETTER_STYLE
    get() = PRESET_LETTER_STYLE.copy(
        name = l10n("project.placeholder"),
        layers = persistentListOf(
            PRESET_LAYER.copy(
                color1 = Color4f.ORANGE,
                shape = LayerShape.STRIPE,
                stripePreset = StripePreset.BACKGROUND
            ),
            PRESET_LAYER.copy(
                color1 = Color4f.BLACK,
                shape = LayerShape.TEXT
            )
        )
    )
