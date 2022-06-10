package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
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
    TextDecoration::class.java -> PRESET_TEXT_DECORATION
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
} as S


val PRESET_GLOBAL = Global(
    widthPx = 1920,
    heightPx = 1080,
    fps = FPS(24, 1),
    timecodeFormat = TimecodeFormat.SMPTE_NON_DROP_FRAME,
    runtimeFrames = Opt(false, 1),
    grounding = Color.BLACK,
    unitVGapPx = 32f,
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
    scrollPxPerFrame = 3f
)


val PRESET_CONTENT_STYLE = ContentStyle(
    name = "???",
    spineOrientation = SpineOrientation.VERTICAL,
    alignWithAxis = AlignWithAxis.BODY_CENTER,
    vMarginPx = 0f,
    bodyLayout = BodyLayout.GRID,
    gridFillingOrder = GridFillingOrder.L2R_T2B,
    gridElemBoxConform = BodyElementBoxConform.WIDTH,
    gridElemHJustifyPerCol = persistentListOf(HJustify.CENTER),
    gridElemVJustify = VJustify.MIDDLE,
    gridRowGapPx = 0f,
    gridColGapPx = 32f,
    flowDirection = FlowDirection.L2R,
    flowLineHJustify = LineHJustify.CENTER,
    flowElemBoxConform = BodyElementBoxConform.NOTHING,
    flowElemHJustify = HJustify.CENTER,
    flowElemVJustify = VJustify.MIDDLE,
    flowLineWidthPx = 1000f,
    flowLineGapPx = 0f,
    flowHGapPx = 32f,
    flowSeparator = "\u2022",
    paragraphsLineHJustify = LineHJustify.CENTER,
    paragraphsLineWidthPx = 600f,
    paragraphsParaGapPx = 8f,
    paragraphsLineGapPx = 0f,
    bodyLetterStyleName = "???",
    hasHead = false,
    headHJustify = HJustify.CENTER,
    headVJustify = VJustify.MIDDLE,
    headLetterStyleName = "???",
    headGapPx = 4f,
    hasTail = false,
    tailHJustify = HJustify.CENTER,
    tailVJustify = VJustify.MIDDLE,
    tailLetterStyleName = "???",
    tailGapPx = 4f
)


val PRESET_LETTER_STYLE = LetterStyle(
    name = "???",
    fontName = "Archivo Narrow Regular",
    heightPx = 32,
    foreground = Color.WHITE,
    leadingTopRem = 0f,
    leadingBottomRem = 0f,
    trackingEm = 0f,
    kerning = true,
    ligatures = true,
    uppercase = false,
    useUppercaseExceptions = true,
    useUppercaseSpacing = true,
    smallCaps = SmallCaps.OFF,
    superscript = Superscript.OFF,
    hOffsetRem = 0f,
    vOffsetRem = 0f,
    scaling = 1f,
    hScaling = 1f,
    hShearing = 0f,
    features = persistentListOf(),
    decorations = persistentListOf(),
    background = Opt(false, Color.WHITE),
    backgroundWidenLeftPx = 0f,
    backgroundWidenRightPx = 0f,
    backgroundWidenTopPx = 0f,
    backgroundWidenBottomPx = 0f
)


val PRESET_TEXT_DECORATION = TextDecoration(
    color = Opt(false, Color.WHITE),
    preset = TextDecorationPreset.UNDERLINE,
    offsetPx = 0f,
    thicknessPx = 2f,
    widenLeftPx = 0f,
    widenRightPx = 0f,
    clearingPx = Opt(false, 0f),
    clearingJoin = LineJoin.MITER,
    dashPatternPx = persistentListOf()
)


val PLACEHOLDER_PAGE_STYLE = PRESET_PAGE_STYLE.copy(
    name = l10n("project.placeholder")
)


val PLACEHOLDER_CONTENT_STYLE = PRESET_CONTENT_STYLE.copy(
    name = l10n("project.placeholder")
)


val PLACEHOLDER_LETTER_STYLE = PRESET_LETTER_STYLE.copy(
    name = l10n("project.placeholder"),
    foreground = Color.BLACK,
    background = Opt(true, Color.ORANGE)
)
