package com.loadingbyte.cinecred.project

import java.awt.Color


val STANDARD_GLOBAL = Global(
    fps = FPS(24, 1),
    widthPx = 1920,
    heightPx = 1080,
    background = Color.BLACK,
    unitVGapPx = 32f
)


val STANDARD_PAGE_STYLE = PageStyle(
    name = "scroll",
    behavior = PageBehavior.SCROLL,
    afterwardSlugFrames = 24,
    cardDurationFrames = 96,
    cardFadeInFrames = 12,
    cardFadeOutFrames = 12,
    scrollPxPerFrame = 3f
)


val STANDARD_CONTENT_STYLE = ContentStyle(
    name = "gutter",
    vMarginPx = 0f,
    centerOn = CenterOn.HEAD_GAP,
    spineDir = SpineDir.HORIZONTAL,
    bodyLayout = BodyLayout.COLUMNS,
    colsBodyLayoutColJustifies = listOf(HJustify.LEFT),
    colsBodyLayoutColGapPx = 32f,
    flowBodyLayoutBodyWidthPx = 1000f,
    flowBodyLayoutJustify = FlowJustify.CENTER,
    flowBodyLayoutSeparator = "\u2022",
    flowBodyLayoutSeparatorSpacingPx = 14f,
    bodyFontSpec = FontSpec("Archivo Narrow Bold", 32, 0f, Color.WHITE),
    hasHead = false,
    headHJustify = HJustify.RIGHT,
    headVJustify = VJustify.TOP_MIDDLE,
    headFontSpec = FontSpec("Archivo Narrow Regular", 24, 0f, Color.WHITE),
    headGapPx = 24f,
    hasTail = false,
    tailHJustify = HJustify.LEFT,
    tailVJustify = VJustify.TOP_MIDDLE,
    tailFontSpec = FontSpec("Archivo Narrow Regular", 24, 0f, Color.WHITE),
    tailGapPx = 24f
)
