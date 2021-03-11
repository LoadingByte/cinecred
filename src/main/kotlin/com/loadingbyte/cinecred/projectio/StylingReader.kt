package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.nio.file.Path


fun readStyling(stylingFile: Path): Styling {
    val toml = Toml.read(stylingFile.toFile())

    val globalMap = toml["global"]
    val global =
        if (globalMap == null || globalMap !is Map<*, *>) STANDARD_GLOBAL
        else readGlobal(globalMap)

    val pageStyles = readMapList(toml["pageStyle"], ::readPageStyle)
    val contentStyles = readMapList(toml["contentStyle"], ::readContentStyle)
    val letterStyles = readMapList(toml["letterStyle"], ::readLetterStyle)

    return Styling(global, pageStyles, contentStyles, letterStyles)
}


private fun <E> readMapList(mapList: Any?, readMap: (Map<*, *>) -> E) =
    if (mapList == null || mapList !is List<*>) persistentListOf()
    else mapList.filterIsInstance<Map<*, *>>().map(readMap).toImmutableList()


private fun readGlobal(map: Map<*, *>) = Global(
    map.get("fps", STANDARD_GLOBAL.fps) { toFPS() },
    map.get("widthPx", STANDARD_GLOBAL.widthPx) { toInt(nonNeg = true, non0 = true) },
    map.get("heightPx", STANDARD_GLOBAL.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("background", STANDARD_GLOBAL.background) { toColor(allowAlpha = false) },
    map.get("unitVGapPx", STANDARD_GLOBAL.unitVGapPx) { toFiniteFloat(nonNeg = true, non0 = true) }
)


private fun readPageStyle(map: Map<*, *>) = PageStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("behavior", STANDARD_PAGE_STYLE.behavior) { toEnum() },
    map.get("meltWithPrev", STANDARD_PAGE_STYLE.meltWithPrev) { toBoolean() },
    map.get("meltWithNext", STANDARD_PAGE_STYLE.meltWithNext) { toBoolean() },
    map.get("afterwardSlugFrames", STANDARD_PAGE_STYLE.afterwardSlugFrames) { toInt(nonNeg = true) },
    map.get("cardDurationFrames", STANDARD_PAGE_STYLE.cardDurationFrames) { toInt(nonNeg = true) },
    map.get("cardFadeInFrames", STANDARD_PAGE_STYLE.cardFadeInFrames) { toInt(nonNeg = true) },
    map.get("cardFadeOutFrames", STANDARD_PAGE_STYLE.cardFadeOutFrames) { toInt(nonNeg = true) },
    map.get("scrollPxPerFrame", STANDARD_PAGE_STYLE.scrollPxPerFrame) { toFiniteFloat(nonNeg = true, non0 = true) }
)


private fun readContentStyle(map: Map<*, *>) = ContentStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("spineOrientation", STANDARD_CONTENT_STYLE.spineOrientation) { toEnum() },
    map.get("alignWithAxis", STANDARD_CONTENT_STYLE.alignWithAxis) { toEnum() },
    map.get("vMarginPx", STANDARD_CONTENT_STYLE.vMarginPx) { toFiniteFloat(nonNeg = true) },
    map.get("bodyLayout", STANDARD_CONTENT_STYLE.bodyLayout) { toEnum() },
    map.get("bodyLayoutLineGapPx", STANDARD_CONTENT_STYLE.bodyLayoutLineGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("bodyLayoutElemConform", STANDARD_CONTENT_STYLE.bodyLayoutElemConform) { toEnum() },
    map.get("bodyLayoutElemVJustify", STANDARD_CONTENT_STYLE.bodyLayoutElemVJustify) { toEnum() },
    map.get("bodyLayoutHorizontalGapPx", STANDARD_CONTENT_STYLE.bodyLayoutHorizontalGapPx) {
        toFiniteFloat(nonNeg = true)
    },
    map.get("bodyLayoutColsHJustify", STANDARD_CONTENT_STYLE.bodyLayoutColsHJustify) {
        toEnumList<HJustify>().toImmutableList()
    },
    map.get("bodyLayoutLineHJustify", STANDARD_CONTENT_STYLE.bodyLayoutLineHJustify) { toEnum() },
    map.get("bodyLayoutBodyWidthPx", STANDARD_CONTENT_STYLE.bodyLayoutBodyWidthPx) {
        toFiniteFloat(nonNeg = true, non0 = true)
    },
    map.get("bodyLayoutElemHJustify", STANDARD_CONTENT_STYLE.bodyLayoutElemHJustify) { toEnum() },
    map.get("bodyLayoutSeparator", STANDARD_CONTENT_STYLE.bodyLayoutSeparator) { this },
    map.get("bodyLayoutParagraphGapPx", STANDARD_CONTENT_STYLE.bodyLayoutParagraphGapPx) {
        toFiniteFloat(nonNeg = true)
    },
    map.get("bodyLetterStyleName", STANDARD_CONTENT_STYLE.bodyLetterStyleName) { this },
    map.keys.any { "head" in (it as String) },
    map.get("headHJustify", STANDARD_CONTENT_STYLE.headHJustify) { toEnum() },
    map.get("headVJustify", STANDARD_CONTENT_STYLE.headVJustify) { toEnum() },
    map.get("headGapPx", STANDARD_CONTENT_STYLE.headGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("headLetterStyleName", STANDARD_CONTENT_STYLE.headLetterStyleName) { this },
    map.keys.any { "tail" in (it as String) },
    map.get("tailHJustify", STANDARD_CONTENT_STYLE.tailHJustify) { toEnum() },
    map.get("tailVJustify", STANDARD_CONTENT_STYLE.tailVJustify) { toEnum() },
    map.get("tailGapPx", STANDARD_CONTENT_STYLE.tailGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("tailLetterStyleName", STANDARD_CONTENT_STYLE.tailLetterStyleName) { this }
)


private fun readLetterStyle(map: Map<*, *>) = LetterStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("fontName", STANDARD_LETTER_STYLE.fontName) { this },
    map.get("heightPx", STANDARD_LETTER_STYLE.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("tracking", STANDARD_LETTER_STYLE.tracking) { toFiniteFloat() },
    map.get("superscript", STANDARD_LETTER_STYLE.superscript) { toEnum() },
    map.get("foreground", STANDARD_LETTER_STYLE.foreground) { toColor() },
    map.get("background", STANDARD_LETTER_STYLE.background) { toColor() },
    map.get("underline", STANDARD_LETTER_STYLE.underline) { toBoolean() },
    map.get("strikethrough", STANDARD_LETTER_STYLE.strikethrough) { toBoolean() }
)


private inline fun <R> Map<*, *>.get(key: String, default: R, convert: String.() -> R): R =
    try {
        get(key)?.toString()?.trim()?.let(convert) ?: default
    } catch (_: RuntimeException) {
        default
    }
