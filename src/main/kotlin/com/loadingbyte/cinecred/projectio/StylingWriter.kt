package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import java.nio.file.Path


fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = mapOf(
        "global" to writeGlobal(styling.global),
        "pageStyle" to styling.pageStyles.map(::writePageStyle),
        "contentStyle" to styling.contentStyles.map(::writeContentStyle)
    )

    Toml.write(toml, stylingFile.toFile())
}


private fun writeGlobal(global: Global) = mapOf(
    "fps" to global.fps.toString2(),
    "widthPx" to global.widthPx,
    "heightPx" to global.heightPx,
    "background" to global.background.toString2(),
    "unitVGapPx" to global.unitVGapPx
)


private fun writePageStyle(style: PageStyle): Map<String, Any> {
    val toml = mutableMapOf(
        "name" to style.name,
        "behavior" to style.behavior.name,
        "meltWithPrev" to style.meltWithPrev,
        "meltWithNext" to style.meltWithNext,
        "afterwardSlugFrames" to style.afterwardSlugFrames,
        "cardDurationFrames" to style.cardDurationFrames,
        "cardFadeInFrames" to style.cardFadeInFrames,
        "cardFadeOutFrames" to style.cardFadeOutFrames,
        "scrollPxPerFrame" to style.scrollPxPerFrame
    )
    if (style.behavior == PageBehavior.CARD) {
        toml.remove("meltWithPrev")
        toml.remove("meltWithNext")
        toml.remove("scrollPxPerFrame")
    }
    if (style.behavior == PageBehavior.SCROLL) {
        toml.remove("cardDurationFrames")
        toml.remove("cardFadeInFrames")
        toml.remove("cardFadeOutFrames")
        if (style.meltWithNext)
            toml.remove("afterwardSlugFrames")
    }
    return toml
}


private fun writeContentStyle(style: ContentStyle): Map<String, Any> {
    val toml = mutableMapOf(
        "name" to style.name,
        "spineOrientation" to style.spineOrientation.name,
        "alignWithAxis" to style.alignWithAxis.name,
        "vMarginPx" to style.vMarginPx,
        "bodyLayout" to style.bodyLayout.name,
        "bodyLayoutLineGapPx" to style.bodyLayoutLineGapPx,
        "bodyLayoutElemConform" to style.bodyLayoutElemConform.name,
        "bodyLayoutElemVJustify" to style.bodyLayoutElemVJustify.name,
        "bodyLayoutHorizontalGapPx" to style.bodyLayoutHorizontalGapPx,
        "bodyLayoutColsHJustify" to style.bodyLayoutColsHJustify.toString2(),
        "bodyLayoutLineHJustify" to style.bodyLayoutLineHJustify.name,
        "bodyLayoutBodyWidthPx" to style.bodyLayoutBodyWidthPx,
        "bodyLayoutElemHJustify" to style.bodyLayoutElemHJustify.name,
        "bodyLayoutSeparator" to style.bodyLayoutSeparator,
        "bodyLayoutParagraphGapPx" to style.bodyLayoutParagraphGapPx,
        "bodyFontSpec" to style.bodyFontSpec.toString2(),
        "headHJustify" to style.headHJustify.name,
        "headVJustify" to style.headVJustify.name,
        "headGapPx" to style.headGapPx,
        "headFontSpec" to style.headFontSpec.toString2(),
        "tailHJustify" to style.tailHJustify.name,
        "tailVJustify" to style.tailVJustify.name,
        "tailGapPx" to style.tailGapPx,
        "tailFontSpec" to style.tailFontSpec.toString2()
    )
    if (style.spineOrientation == SpineOrientation.VERTICAL) {
        toml.remove("headVJustify")
        toml.remove("tailVJustify")
    }
    if (style.bodyLayout == BodyLayout.GRID) {
        toml.remove("bodyLayoutLineHJustify")
        toml.remove("bodyLayoutBodyWidthPx")
        toml.remove("bodyLayoutElemHJustify")
        toml.remove("bodyLayoutSeparator")
        toml.remove("bodyLayoutParagraphGapPx")
        if (style.bodyLayoutColsHJustify.size < 2)
            toml.remove("bodyLayoutHorizontalGapPx")
    }
    if (style.bodyLayout == BodyLayout.FLOW) {
        toml.remove("bodyLayoutColsHJustify")
        toml.remove("bodyLayoutParagraphGapPx")
    }
    if (style.bodyLayout == BodyLayout.PARAGRAPHS) {
        toml.remove("bodyLayoutElemConform")
        toml.remove("bodyLayoutElemVJustify")
        toml.remove("bodyLayoutHorizontalGapPx")
        toml.remove("bodyLayoutColsHJustify")
        toml.remove("bodyLayoutElemHJustify")
        toml.remove("bodyLayoutSeparator")
    }
    if (!style.hasHead && !style.hasTail)
        toml.remove("spineOrientation")
    if (!style.hasHead) {
        toml.remove("headHJustify")
        toml.remove("headVJustify")
        toml.remove("headFontSpec")
        toml.remove("headGapPx")
    }
    if (!style.hasTail) {
        toml.remove("tailHJustify")
        toml.remove("tailVJustify")
        toml.remove("tailFontSpec")
        toml.remove("tailGapPx")
    }
    return toml
}
