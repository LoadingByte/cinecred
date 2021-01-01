package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import java.nio.file.Path


fun readStyling(stylingFile: Path): Styling {
    val toml = Toml.read(stylingFile.toFile())

    val globalMap = toml["global"]
    val global =
        if (globalMap == null || globalMap !is Map<*, *>) STANDARD_GLOBAL
        else globalMap.toGlobal()

    val pageStyleMaps = toml["pageStyle"]
    var pageStyles =
        if (pageStyleMaps == null || pageStyleMaps !is List<*>) emptyList()
        else pageStyleMaps.filterIsInstance<Map<*, *>>().map { it.toPageStyle() }
    if (pageStyles.isEmpty())
        pageStyles = listOf(STANDARD_PAGE_STYLE)

    val contentStyleMaps = toml["contentStyle"]
    var contentStyles =
        if (contentStyleMaps == null || contentStyleMaps !is List<*>) emptyList()
        else contentStyleMaps.filterIsInstance<Map<*, *>>().map { it.toContentStyle() }
    if (contentStyles.isEmpty())
        contentStyles = listOf(STANDARD_CONTENT_STYLE)

    return Styling(global, pageStyles, contentStyles)
}


private fun Map<*, *>.toGlobal() = Global(
    get("fps", STANDARD_GLOBAL.fps) { toFPS() },
    get("widthPx", STANDARD_GLOBAL.widthPx) { toInt(nonNegative = true, nonZero = true) },
    get("heightPx", STANDARD_GLOBAL.heightPx) { toInt(nonNegative = true, nonZero = true) },
    get("background", STANDARD_GLOBAL.background) { toColor() },
    get("unitVGapPx", STANDARD_GLOBAL.unitVGapPx) { toFiniteFloat(nonNegative = true, nonZero = true) }
)


private fun Map<*, *>.toPageStyle() = PageStyle(
    get("name", STANDARD_PAGE_STYLE.name) { this },
    get("behavior", STANDARD_PAGE_STYLE.behavior) { toEnum() },
    get("afterwardSlugFrames", STANDARD_PAGE_STYLE.afterwardSlugFrames) { toInt(nonNegative = true) },
    get("cardDurationFrames", STANDARD_PAGE_STYLE.cardDurationFrames) { toInt(nonNegative = true) },
    get("cardFadeInFrames", STANDARD_PAGE_STYLE.cardFadeInFrames) { toInt(nonNegative = true) },
    get("cardFadeOutFrames", STANDARD_PAGE_STYLE.cardFadeOutFrames) { toInt(nonNegative = true) },
    get("scrollPxPerFrame", STANDARD_PAGE_STYLE.scrollPxPerFrame) { toFiniteFloat(nonNegative = true, nonZero = true) }
)


private fun Map<*, *>.toContentStyle(): ContentStyle {
    return ContentStyle(
        get("name", STANDARD_CONTENT_STYLE.name) { this },
        get("vMarginPx", STANDARD_CONTENT_STYLE.vMarginPx) { toFiniteFloat(nonNegative = true) },
        get("centerOn", STANDARD_CONTENT_STYLE.centerOn) { toEnum() },
        get("spineDir", STANDARD_CONTENT_STYLE.spineDir) { toEnum() },
        get("bodyLayout", STANDARD_CONTENT_STYLE.bodyLayout) { toEnum() },
        get("bodyLayoutLineGapPx", STANDARD_CONTENT_STYLE.bodyLayoutLineGapPx) { toFiniteFloat(nonNegative = true) },
        get("bodyLayoutElemConform", STANDARD_CONTENT_STYLE.bodyLayoutElemConform) { toEnum() },
        get("bodyLayoutElemVJustify", STANDARD_CONTENT_STYLE.bodyLayoutElemVJustify) { toEnum() },
        get("bodyLayoutHorizontalGapPx", STANDARD_CONTENT_STYLE.bodyLayoutHorizontalGapPx) {
            toFiniteFloat(nonNegative = true)
        },
        get("bodyLayoutColsHJustify", STANDARD_CONTENT_STYLE.bodyLayoutColsHJustify) { toEnumList() },
        get("bodyLayoutLineHJustify", STANDARD_CONTENT_STYLE.bodyLayoutLineHJustify) { toEnum() },
        get("bodyLayoutBodyWidthPx", STANDARD_CONTENT_STYLE.bodyLayoutBodyWidthPx) {
            toFiniteFloat(nonNegative = true, nonZero = true)
        },
        get("bodyLayoutElemHJustify", STANDARD_CONTENT_STYLE.bodyLayoutElemHJustify) { toEnum() },
        get("bodyLayoutSeparator", STANDARD_CONTENT_STYLE.bodyLayoutSeparator) { this },
        get("bodyLayoutParagraphGapPx", STANDARD_CONTENT_STYLE.bodyLayoutParagraphGapPx) {
            toFiniteFloat(nonNegative = true)
        },
        get("bodyFontSpec", STANDARD_CONTENT_STYLE.bodyFontSpec) { toFontSpec() },
        keys.any { "head" in (it as String) },
        get("headHJustify", STANDARD_CONTENT_STYLE.headHJustify) { toEnum() },
        get("headVJustify", STANDARD_CONTENT_STYLE.headVJustify) { toEnum() },
        get("headGapPx", STANDARD_CONTENT_STYLE.headGapPx) { toFiniteFloat(nonNegative = true) },
        get("headFontSpec", STANDARD_CONTENT_STYLE.headFontSpec) { toFontSpec() },
        keys.any { "tail" in (it as String) },
        get("tailHJustify", STANDARD_CONTENT_STYLE.tailHJustify) { toEnum() },
        get("tailVJustify", STANDARD_CONTENT_STYLE.tailVJustify) { toEnum() },
        get("tailGapPx", STANDARD_CONTENT_STYLE.tailGapPx) { toFiniteFloat(nonNegative = true) },
        get("tailFontSpec", STANDARD_CONTENT_STYLE.tailFontSpec) { toFontSpec() }
    )
}


private inline fun <R> Map<*, *>.get(key: String, default: R, convert: String.() -> R): R =
    try {
        get(key)?.toString()?.trim()?.let(convert) ?: default
    } catch (_: RuntimeException) {
        default
    }
