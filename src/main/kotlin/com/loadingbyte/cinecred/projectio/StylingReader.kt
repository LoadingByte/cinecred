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
        if (globalMap == null || globalMap !is Map<*, *>) PRESET_GLOBAL
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
    map.get("fps", PRESET_GLOBAL.fps) { toFPS() },
    map.get("widthPx", PRESET_GLOBAL.widthPx) { toInt(nonNeg = true, non0 = true) },
    map.get("heightPx", PRESET_GLOBAL.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("background", PRESET_GLOBAL.background) { toColor(allowAlpha = false) },
    map.get("unitVGapPx", PRESET_GLOBAL.unitVGapPx) { toFiniteFloat(nonNeg = true, non0 = true) }
)


private fun readPageStyle(map: Map<*, *>) = PageStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("behavior", PRESET_PAGE_STYLE.behavior) { toEnum() },
    map.get("meltWithPrev", PRESET_PAGE_STYLE.meltWithPrev) { toBoolean() },
    map.get("meltWithNext", PRESET_PAGE_STYLE.meltWithNext) { toBoolean() },
    map.get("afterwardSlugFrames", PRESET_PAGE_STYLE.afterwardSlugFrames) { toInt(nonNeg = true) },
    map.get("cardDurationFrames", PRESET_PAGE_STYLE.cardDurationFrames) { toInt(nonNeg = true) },
    map.get("cardFadeInFrames", PRESET_PAGE_STYLE.cardFadeInFrames) { toInt(nonNeg = true) },
    map.get("cardFadeOutFrames", PRESET_PAGE_STYLE.cardFadeOutFrames) { toInt(nonNeg = true) },
    map.get("scrollPxPerFrame", PRESET_PAGE_STYLE.scrollPxPerFrame) { toFiniteFloat(nonNeg = true, non0 = true) }
)


private fun readContentStyle(map: Map<*, *>) = ContentStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("spineOrientation", PRESET_CONTENT_STYLE.spineOrientation) { toEnum() },
    map.get("alignWithAxis", PRESET_CONTENT_STYLE.alignWithAxis) { toEnum() },
    map.get("vMarginPx", PRESET_CONTENT_STYLE.vMarginPx) { toFiniteFloat(nonNeg = true) },
    map.get("bodyLayout", PRESET_CONTENT_STYLE.bodyLayout) { toEnum() },
    map.get("bodyLayoutLineGapPx", PRESET_CONTENT_STYLE.bodyLayoutLineGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("bodyLayoutElemConform", PRESET_CONTENT_STYLE.bodyLayoutElemConform) { toEnum() },
    map.get("bodyLayoutElemVJustify", PRESET_CONTENT_STYLE.bodyLayoutElemVJustify) { toEnum() },
    map.get("bodyLayoutHorizontalGapPx", PRESET_CONTENT_STYLE.bodyLayoutHorizontalGapPx) {
        toFiniteFloat(nonNeg = true)
    },
    map.get("bodyLayoutColsHJustify", PRESET_CONTENT_STYLE.bodyLayoutColsHJustify) {
        toEnumList<HJustify>().toImmutableList()
    },
    map.get("bodyLayoutLineHJustify", PRESET_CONTENT_STYLE.bodyLayoutLineHJustify) { toEnum() },
    map.get("bodyLayoutBodyWidthPx", PRESET_CONTENT_STYLE.bodyLayoutBodyWidthPx) {
        toFiniteFloat(nonNeg = true, non0 = true)
    },
    map.get("bodyLayoutElemHJustify", PRESET_CONTENT_STYLE.bodyLayoutElemHJustify) { toEnum() },
    map.get("bodyLayoutSeparator", PRESET_CONTENT_STYLE.bodyLayoutSeparator) { this },
    map.get("bodyLayoutParagraphGapPx", PRESET_CONTENT_STYLE.bodyLayoutParagraphGapPx) {
        toFiniteFloat(nonNeg = true)
    },
    map.get("bodyLetterStyleName", PRESET_CONTENT_STYLE.bodyLetterStyleName) { this },
    map.keys.any { "head" in (it as String) },
    map.get("headHJustify", PRESET_CONTENT_STYLE.headHJustify) { toEnum() },
    map.get("headVJustify", PRESET_CONTENT_STYLE.headVJustify) { toEnum() },
    map.get("headGapPx", PRESET_CONTENT_STYLE.headGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("headLetterStyleName", PRESET_CONTENT_STYLE.headLetterStyleName) { this },
    map.keys.any { "tail" in (it as String) },
    map.get("tailHJustify", PRESET_CONTENT_STYLE.tailHJustify) { toEnum() },
    map.get("tailVJustify", PRESET_CONTENT_STYLE.tailVJustify) { toEnum() },
    map.get("tailGapPx", PRESET_CONTENT_STYLE.tailGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("tailLetterStyleName", PRESET_CONTENT_STYLE.tailLetterStyleName) { this }
)


private fun readLetterStyle(map: Map<*, *>) = LetterStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("fontName", PRESET_LETTER_STYLE.fontName) { this },
    map.get("heightPx", PRESET_LETTER_STYLE.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("tracking", PRESET_LETTER_STYLE.tracking) { toFiniteFloat() },
    map.get("superscript", PRESET_LETTER_STYLE.superscript) { toEnum() },
    map.get("foreground", PRESET_LETTER_STYLE.foreground) { toColor() },
    map.get("background", PRESET_LETTER_STYLE.background) { toColor() },
    map.get("underline", PRESET_LETTER_STYLE.underline) { toBoolean() },
    map.get("strikethrough", PRESET_LETTER_STYLE.strikethrough) { toBoolean() }
)


private inline fun <R> Map<*, *>.get(key: String, default: R, convert: String.() -> R): R =
    try {
        get(key)?.toString()?.trim()?.let(convert) ?: default
    } catch (_: RuntimeException) {
        default
    }
