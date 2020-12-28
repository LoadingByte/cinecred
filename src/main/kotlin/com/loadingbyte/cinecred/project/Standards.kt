package com.loadingbyte.cinecred.project

import java.awt.Color


val STANDARD_GLOBAL = Global(
    fps = FPS(24, 1),
    widthPx = 1920,
    heightPx = 1080,
    background = Color.BLACK,
    unitHGapPx = 1f,
    unitVGapPx = 30f
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


// TODO: Better standard font specs as soon as we start shipping fonts with the program.
val STANDARD_CONTENT_STYLE = ContentStyle(
    name = "gutter",
    spineDir = SpineDir.HORIZONTAL,
    centerOn = CenterOn.HEAD_GAP,
    bodyLayout = BodyLayout.COLUMNS,
    colsBodyLayoutColTypes = listOf(ColType.LEFT),
    colsBodyLayoutColGapPx = 30f,
    flowBodyLayoutBodyWidthPx = 800f,
    flowBodyLayoutJustify = FlowJustify.CENTER,
    flowBodyLayoutSeparator = "\u2022",
    flowBodyLayoutSeparatorSpacingPx = 15f,
    bodyFontSpec = FontSpec("SansSerif.bold", 30, 0f, Color.WHITE),
    hasHead = false,
    headHJustify = HJustify.RIGHT,
    headVJustify = VJustify.TOP_MIDDLE,
    headFontSpec = FontSpec("SansSerif.plain", 30, 0f, Color.WHITE),
    headGapPx = 30f,
    hasTail = false,
    tailHJustify = HJustify.LEFT,
    tailVJustify = VJustify.TOP_MIDDLE,
    tailFontSpec = FontSpec("SansSerif.plain", 30, 0f, Color.WHITE),
    tailGapPx = 30f
)
