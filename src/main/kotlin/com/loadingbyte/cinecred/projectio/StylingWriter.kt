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
    "unitHGapPx" to unitHGapPx,
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
        "spineDir" to spineDir.name,
        "centerOn" to centerOn.name,
        "colsBodyLayoutColTypes" to colsBodyLayoutColTypes.toString2(),
        "colsBodyLayoutColGapPx" to colsBodyLayoutColGapPx,
        "flowBodyLayoutBodyWidthPx" to flowBodyLayoutBodyWidthPx,
        "flowBodyLayoutJustify" to flowBodyLayoutJustify.name,
        "flowBodyLayoutSeparator" to flowBodyLayoutSeparator,
        "flowBodyLayoutSeparatorSpacingPx" to flowBodyLayoutSeparatorSpacingPx,
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
    if (spineDir == SpineDir.VERTICAL) {
        toml.remove("centerOn")
        toml.remove("headVJustify")
        toml.remove("tailVJustify")
    }
    if (bodyLayout != BodyLayout.COLUMNS) {
        toml.remove("colsBodyLayoutColTypes")
        toml.remove("colsBodyLayoutColGapPx")
    }
    if (bodyLayout != BodyLayout.FLOW) {
        toml.remove("flowBodyLayoutBodyWidthPx")
        toml.remove("flowBodyLayoutJustify")
        toml.remove("flowBodyLayoutSeparator")
        toml.remove("flowBodyLayoutSeparatorSpacingPx")
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
