package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import java.nio.file.Path


fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = mapOf(
        "global" to writeGlobal(styling.global),
        "pageStyle" to styling.pageStyles.map(::writePageStyle),
        "contentStyle" to styling.contentStyles.map(::writeContentStyle),
        "letterStyle" to styling.letterStyles.map(::writeLetterStyle)
    )

    Toml.write(toml, stylingFile.toFile())
}


private fun writeGlobal(global: Global) = mapOf(
    "fps" to global.fps.toString2(),
    "widthPx" to global.widthPx,
    "heightPx" to global.heightPx,
    "background" to global.background.toHex24(),
    "unitVGapPx" to global.unitVGapPx
)


private fun writePageStyle(style: PageStyle): Map<String, Any> {
    val toml = mutableMapOf<String, Any>(
        "name" to style.name,
        "afterwardSlugFrames" to style.afterwardSlugFrames,
        "behavior" to style.behavior.name
    )
    when (style.behavior) {
        PageBehavior.CARD -> toml.putAll(
            "cardDurationFrames" to style.cardDurationFrames,
            "cardFadeInFrames" to style.cardFadeInFrames,
            "cardFadeOutFrames" to style.cardFadeOutFrames
        )
        PageBehavior.SCROLL -> toml.putAll(
            "scrollMeltWithPrev" to style.scrollMeltWithPrev,
            "scrollMeltWithNext" to style.scrollMeltWithNext,
            "scrollPxPerFrame" to style.scrollPxPerFrame
        )
    }
    return toml
}


private fun writeContentStyle(style: ContentStyle): Map<String, Any> {
    val toml = mutableMapOf(
        "name" to style.name,
        "spineOrientation" to style.spineOrientation.name,
        "alignWithAxis" to style.alignWithAxis.name,
        "vMarginPx" to style.vMarginPx,
        "bodyLetterStyleName" to style.bodyLetterStyleName,
        "bodyLayout" to style.bodyLayout.name
    )
    when (style.bodyLayout) {
        BodyLayout.GRID -> toml.putAll(
            "gridElemBoxConform" to style.gridElemBoxConform.name,
            "gridElemHJustifyPerCol" to style.gridElemHJustifyPerCol.toString2(),
            "gridElemVJustify" to style.gridElemVJustify.name,
            "gridRowGapPx" to style.gridRowGapPx,
            "gridColGapPx" to style.gridColGapPx
        )
        BodyLayout.FLOW -> toml.putAll(
            "flowElemBoxConform" to style.flowElemBoxConform.name,
            "flowLineHJustify" to style.flowLineHJustify.name,
            "flowElemHJustify" to style.flowElemHJustify.name,
            "flowElemVJustify" to style.flowElemVJustify.name,
            "flowLineWidthPx" to style.flowLineWidthPx,
            "flowLineGapPx" to style.flowLineGapPx,
            "flowHGapPx" to style.flowHGapPx,
            "flowSeparator" to style.flowSeparator
        )
        BodyLayout.PARAGRAPHS -> toml.putAll(
            "paragraphsLineHJustify" to style.paragraphsLineHJustify.name,
            "paragraphsLineWidthPx" to style.paragraphsLineWidthPx,
            "paragraphsParaGapPx" to style.paragraphsParaGapPx,
            "paragraphsLineGapPx" to style.paragraphsLineGapPx
        )
    }
    if (style.hasHead)
        toml.putAll(
            "headLetterStyleName" to style.headLetterStyleName,
            "headHJustify" to style.headHJustify.name,
            "headVJustify" to style.headVJustify.name,
            "headGapPx" to style.headGapPx,
        )
    if (style.hasTail)
        toml.putAll(
            "tailLetterStyleName" to style.tailLetterStyleName,
            "tailHJustify" to style.tailHJustify.name,
            "tailVJustify" to style.tailVJustify.name,
            "tailGapPx" to style.tailGapPx
        )
    return toml
}


private fun writeLetterStyle(style: LetterStyle) = mapOf(
    "name" to style.name,
    "fontName" to style.fontName,
    "heightPx" to style.heightPx,
    "tracking" to style.tracking,
    "superscript" to style.superscript.name,
    "foreground" to style.foreground.toHex32(),
    "background" to style.background.toHex32(),
    "underline" to style.underline,
    "strikethrough" to style.strikethrough
)


private fun <K, V> MutableMap<K, V>.putAll(vararg pairs: Pair<K, V>) {
    putAll(pairs)
}
