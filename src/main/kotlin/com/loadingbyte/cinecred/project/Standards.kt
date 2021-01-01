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
    bodyLayout = BodyLayout.GRID,
    bodyLayoutLineGapPx = 0f,
    bodyLayoutElemConform = BodyElementConform.WIDTH,
    bodyLayoutElemVJustify = VJustify.MIDDLE,
    bodyLayoutHorizontalGapPx = 32f,
    bodyLayoutColsHJustify = listOf(HJustify.LEFT),
    bodyLayoutLineHJustify = LineHJustify.CENTER,
    bodyLayoutBodyWidthPx = 1000f,
    bodyLayoutElemHJustify = HJustify.CENTER,
    bodyLayoutSeparator = "\u2022",
    bodyLayoutParagraphGapPx = 8f,
    bodyFontSpec = FontSpec("Archivo Narrow Bold", 32, Color.WHITE),
    hasHead = false,
    headHJustify = HJustify.RIGHT,
    headVJustify = VJustify.TOP,
    headFontSpec = FontSpec("Archivo Narrow Regular", 24, Color.WHITE),
    headGapPx = 24f,
    hasTail = false,
    tailHJustify = HJustify.LEFT,
    tailVJustify = VJustify.TOP,
    tailFontSpec = FontSpec("Archivo Narrow Regular", 24, Color.WHITE),
    tailGapPx = 24f
)
