package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
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
    if (mapList == null || mapList !is List<*>) persistentSetOf()
    else mapList.filterIsInstance<Map<*, *>>().map(readMap).toImmutableSet()


private fun readGlobal(map: Map<*, *>) = Global(
    map.get("fps", PRESET_GLOBAL.fps) { toFPS() },
    map.get("widthPx", PRESET_GLOBAL.widthPx) { toInt(nonNeg = true, non0 = true) },
    map.get("heightPx", PRESET_GLOBAL.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("background", PRESET_GLOBAL.background) { toColor(allowAlpha = false) },
    map.get("unitVGapPx", PRESET_GLOBAL.unitVGapPx) { toFiniteFloat(nonNeg = true, non0 = true) },
    map.getList("uppercaseExceptions", PRESET_GLOBAL.uppercaseExceptions).filter(String::isNotBlank).toImmutableList()
)


private fun readPageStyle(map: Map<*, *>) = PageStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("afterwardSlugFrames", PRESET_PAGE_STYLE.afterwardSlugFrames) { toInt(nonNeg = true) },
    map.get("behavior", PRESET_PAGE_STYLE.behavior) { toEnum() },
    map.get("cardDurationFrames", PRESET_PAGE_STYLE.cardDurationFrames) { toInt(nonNeg = true) },
    map.get("cardFadeInFrames", PRESET_PAGE_STYLE.cardFadeInFrames) { toInt(nonNeg = true) },
    map.get("cardFadeOutFrames", PRESET_PAGE_STYLE.cardFadeOutFrames) { toInt(nonNeg = true) },
    map.get("scrollMeltWithPrev", PRESET_PAGE_STYLE.scrollMeltWithPrev) { toBoolean() },
    map.get("scrollMeltWithNext", PRESET_PAGE_STYLE.scrollMeltWithNext) { toBoolean() },
    map.get("scrollPxPerFrame", PRESET_PAGE_STYLE.scrollPxPerFrame) { toFiniteFloat(nonNeg = true, non0 = true) }
)


private fun readContentStyle(map: Map<*, *>) = ContentStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("spineOrientation", PRESET_CONTENT_STYLE.spineOrientation) { toEnum() },
    map.get("alignWithAxis", PRESET_CONTENT_STYLE.alignWithAxis) { toEnum() },
    map.get("vMarginPx", PRESET_CONTENT_STYLE.vMarginPx) { toFiniteFloat(nonNeg = true) },
    map.get("bodyLetterStyleName", PRESET_CONTENT_STYLE.bodyLetterStyleName) { this },
    map.get("bodyLayout", PRESET_CONTENT_STYLE.bodyLayout) { toEnum() },
    map.get("gridFillingOrder", PRESET_CONTENT_STYLE.gridFillingOrder) { toEnum() },
    map.get("gridElemBoxConform", PRESET_CONTENT_STYLE.gridElemBoxConform) { toEnum() },
    map.get("gridElemHJustifyPerCol", PRESET_CONTENT_STYLE.gridElemHJustifyPerCol) {
        toEnumList<HJustify>().toImmutableList()
    },
    map.get("gridElemVJustify", PRESET_CONTENT_STYLE.gridElemVJustify) { toEnum() },
    map.get("gridRowGapPx", PRESET_CONTENT_STYLE.gridRowGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("gridColGapPx", PRESET_CONTENT_STYLE.gridColGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("flowDirection", PRESET_CONTENT_STYLE.flowDirection) { toEnum() },
    map.get("flowLineHJustify", PRESET_CONTENT_STYLE.flowLineHJustify) { toEnum() },
    map.get("flowElemBoxConform", PRESET_CONTENT_STYLE.flowElemBoxConform) { toEnum() },
    map.get("flowElemHJustify", PRESET_CONTENT_STYLE.flowElemHJustify) { toEnum() },
    map.get("flowElemVJustify", PRESET_CONTENT_STYLE.flowElemVJustify) { toEnum() },
    map.get("flowLineWidthPx", PRESET_CONTENT_STYLE.flowLineWidthPx) { toFiniteFloat(nonNeg = true, non0 = true) },
    map.get("flowLineGapPx", PRESET_CONTENT_STYLE.flowLineGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("flowHGapPx", PRESET_CONTENT_STYLE.flowHGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("flowSeparator", PRESET_CONTENT_STYLE.flowSeparator) { this },
    map.get("paragraphsLineHJustify", PRESET_CONTENT_STYLE.paragraphsLineHJustify) { toEnum() },
    map.get("paragraphsLineWidthPx", PRESET_CONTENT_STYLE.paragraphsLineWidthPx) {
        toFiniteFloat(
            nonNeg = true,
            non0 = true
        )
    },
    map.get("paragraphsParaGapPx", PRESET_CONTENT_STYLE.paragraphsParaGapPx) { toFiniteFloat(nonNeg = true) },
    map.get("paragraphsLineGapPx", PRESET_CONTENT_STYLE.paragraphsLineGapPx) { toFiniteFloat(nonNeg = true) },
    map.keys.any { (it as String).startsWith("head") },
    map.get("headLetterStyleName", PRESET_CONTENT_STYLE.headLetterStyleName) { this },
    map.get("headHJustify", PRESET_CONTENT_STYLE.headHJustify) { toEnum() },
    map.get("headVJustify", PRESET_CONTENT_STYLE.headVJustify) { toEnum() },
    map.get("headGapPx", PRESET_CONTENT_STYLE.headGapPx) { toFiniteFloat(nonNeg = true) },
    map.keys.any { (it as String).startsWith("tail") },
    map.get("tailLetterStyleName", PRESET_CONTENT_STYLE.tailLetterStyleName) { this },
    map.get("tailHJustify", PRESET_CONTENT_STYLE.tailHJustify) { toEnum() },
    map.get("tailVJustify", PRESET_CONTENT_STYLE.tailVJustify) { toEnum() },
    map.get("tailGapPx", PRESET_CONTENT_STYLE.tailGapPx) { toFiniteFloat(nonNeg = true) }
)


private fun readLetterStyle(map: Map<*, *>) = LetterStyle(
    map.get("name", "!! NAME MISSING !!") { this },
    map.get("fontName", PRESET_LETTER_STYLE.fontName) { this },
    map.get("heightPx", PRESET_LETTER_STYLE.heightPx) { toInt(nonNeg = true, non0 = true) },
    map.get("tracking", PRESET_LETTER_STYLE.tracking) { toFiniteFloat() },
    map.get("foreground", PRESET_LETTER_STYLE.foreground) { toColor() },
    map.get("background", PRESET_LETTER_STYLE.background) { toColor() },
    map.get("underline", PRESET_LETTER_STYLE.underline) { toBoolean() },
    map.get("strikethrough", PRESET_LETTER_STYLE.strikethrough) { toBoolean() },
    map.get("smallCaps", PRESET_LETTER_STYLE.smallCaps) { toBoolean() },
    map.get("uppercase", PRESET_LETTER_STYLE.uppercase) { toBoolean() },
    map.get("useUppercaseExceptions", PRESET_LETTER_STYLE.useUppercaseExceptions) { toBoolean() },
    map.get("superscript", PRESET_LETTER_STYLE.superscript) { toEnum() }
)


private inline fun <R> Map<*, *>.get(key: String, default: R, convert: String.() -> R): R =
    try {
        get(key)?.let { convert(it.toString().trim()) } ?: default
    } catch (_: RuntimeException) {
        default
    }

@Suppress("UNCHECKED_CAST")
private inline fun <reified E> Map<*, *>.getList(key: String, default: List<E>): List<E> {
    val value = get(key)
    return if (value is List<*> && value.all { it is E })
        value as List<E>
    else
        default
}
