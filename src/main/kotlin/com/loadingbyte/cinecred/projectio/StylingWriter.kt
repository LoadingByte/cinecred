package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import java.nio.file.Path


fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = mapOf(
        "global" to styling.global.toToml(),
        "pageStyle" to styling.pageStyles.map { it.toToml() },
        "contentStyle" to styling.contentStyles.map { it.toToml() }
    )

    Toml.write(toml, stylingFile.toFile())
}


private fun Global.toToml() = mapOf(
    "fps" to fps.toString2(),
    "widthPx" to widthPx,
    "heightPx" to heightPx,
    "background" to background.toString2(),
    "unitVGapPx" to unitVGapPx
)


private fun PageStyle.toToml(): Map<String, Any> {
    val toml = mutableMapOf(
        "name" to name,
        "behavior" to behavior.name,
        "afterwardSlugFrames" to afterwardSlugFrames
    )
    toml += when (behavior) {
        PageBehavior.CARD ->
            mapOf(
                "cardDurationFrames" to cardDurationFrames,
                "cardFadeInFrames" to cardFadeInFrames,
                "cardFadeOutFrames" to cardFadeOutFrames
            )
        PageBehavior.SCROLL ->
            mapOf(
                "scrollPxPerFrame" to scrollPxPerFrame
            )
    }
    return toml
}


private fun ContentStyle.toToml(): Map<String, Any> {
    val toml = mutableMapOf(
        "name" to name,
        "spineOrientation" to spineOrientation.name,
        "alignWithAxis" to alignWithAxis.name,
        "vMarginPx" to vMarginPx,
        "bodyLayout" to bodyLayout.name,
        "bodyLayoutLineGapPx" to bodyLayoutLineGapPx,
        "bodyLayoutElemConform" to bodyLayoutElemConform.name,
        "bodyLayoutElemVJustify" to bodyLayoutElemVJustify.name,
        "bodyLayoutHorizontalGapPx" to bodyLayoutHorizontalGapPx,
        "bodyLayoutColsHJustify" to bodyLayoutColsHJustify.toString2(),
        "bodyLayoutLineHJustify" to bodyLayoutLineHJustify.name,
        "bodyLayoutBodyWidthPx" to bodyLayoutBodyWidthPx,
        "bodyLayoutElemHJustify" to bodyLayoutElemHJustify.name,
        "bodyLayoutSeparator" to bodyLayoutSeparator,
        "bodyLayoutParagraphGapPx" to bodyLayoutParagraphGapPx,
        "bodyFontSpec" to bodyFontSpec.toString2(),
        "headHJustify" to headHJustify.name,
        "headVJustify" to headVJustify.name,
        "headGapPx" to headGapPx,
        "headFontSpec" to headFontSpec.toString2(),
        "tailHJustify" to tailHJustify.name,
        "tailVJustify" to tailVJustify.name,
        "tailGapPx" to tailGapPx,
        "tailFontSpec" to tailFontSpec.toString2()
    )
    if (spineOrientation == SpineOrientation.VERTICAL) {
        toml.remove("headVJustify")
        toml.remove("tailVJustify")
    }
    if (bodyLayout == BodyLayout.GRID) {
        toml.remove("bodyLayoutLineHJustify")
        toml.remove("bodyLayoutBodyWidthPx")
        toml.remove("bodyLayoutElemHJustify")
        toml.remove("bodyLayoutSeparator")
        toml.remove("bodyLayoutParagraphGapPx")
    }
    if (bodyLayout == BodyLayout.FLOW) {
        toml.remove("bodyLayoutColsHJustify")
        toml.remove("bodyLayoutParagraphGapPx")
    }
    if (bodyLayout == BodyLayout.PARAGRAPHS) {
        toml.remove("bodyLayoutElemConform")
        toml.remove("bodyLayoutElemVJustify")
        toml.remove("bodyLayoutHorizontalGapPx")
        toml.remove("bodyLayoutColsHJustify")
        toml.remove("bodyLayoutElemHJustify")
        toml.remove("bodyLayoutSeparator")
    }
    if (!hasHead) {
        toml.remove("headHJustify")
        toml.remove("headVJustify")
        toml.remove("headFontSpec")
        toml.remove("headGapPx")
    }
    if (!hasTail) {
        toml.remove("tailHJustify")
        toml.remove("tailVJustify")
        toml.remove("tailFontSpec")
        toml.remove("tailGapPx")
    }
    return toml
}
