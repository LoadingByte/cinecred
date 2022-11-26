package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.colorFromHex
import com.loadingbyte.cinecred.common.enumFromName
import com.loadingbyte.cinecred.common.fpsFromFraction
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.awt.Color
import java.nio.file.Path
import java.util.*


fun readStyling(stylingFile: Path, ctx: StylingContext): Styling {
    val toml = Toml.read(stylingFile.toFile()) as MutableMap<String, Any>

    val rawStyling = RawStyling(
        readMap(toml["global"]),
        readMapList(toml["pageStyle"]),
        readMapList(toml["contentStyle"]),
        readMapList(toml["letterStyle"])
    )

    // If the styling file has been saved by an older version of the program and some aspects of the format have
    // changed since then, adjust the TOML map to reflect the new structure.
    migrate(rawStyling)

    return Styling(
        readStyle(rawStyling.global, Global::class.java),
        readStyles(ctx, rawStyling.pageStyles, PageStyle::class.java),
        readStyles(ctx, rawStyling.contentStyles, ContentStyle::class.java),
        readStyles(ctx, rawStyling.letterStyles, LetterStyle::class.java),
    )
}


private class RawStyling(
    val global: MutableMap<String, Any>,
    val pageStyles: MutableList<MutableMap<String, Any>>,
    val contentStyles: MutableList<MutableMap<String, Any>>,
    val letterStyles: MutableList<MutableMap<String, Any>>
)


@Suppress("UNCHECKED_CAST")
private fun readMap(map: Any?): MutableMap<String, Any> =
    if (map !is Map<*, *>) mutableMapOf()
    else map.toMutableMap() as MutableMap<String, Any>

private fun readMapList(mapList: Any?): MutableList<MutableMap<String, Any>> =
    if (mapList !is List<*>) mutableListOf()
    else mapList.filterIsInstanceTo(mutableListOf())


private fun migrate(rawStyling: RawStyling) {
    // 1.0.0 -> 1.1.0: Enum lists are now stored as string lists instead of space-delimited strings.
    for (contentStyle in rawStyling.contentStyles) {
        val gridElemHJustifyPerCol = contentStyle["gridElemHJustifyPerCol"]
        if (gridElemHJustifyPerCol is String)
            contentStyle["gridElemHJustifyPerCol"] = gridElemHJustifyPerCol.split(" ")
    }

    // 1.0.0 -> 1.1.0: Whether a content style has a head and/or tail is now stored explicitly.
    for (contentStyle in rawStyling.contentStyles) {
        if ("hasHead" !in contentStyle)
            contentStyle["hasHead"] = contentStyle.keys.any { it.startsWith("head") }
        if ("hasTail" !in contentStyle)
            contentStyle["hasTail"] = contentStyle.keys.any { it.startsWith("tail") }
    }

    // 1.1.0 -> 1.2.0: What has previously been called "background" is now called "grounding".
    rawStyling.global["background"]?.let { rawStyling.global["grounding"] = it }

    // 1.1.0 -> 1.2.0: "tracking" is renamed to "trackingEm".
    for (letterStyle in rawStyling.letterStyles)
        letterStyle["tracking"]?.let { letterStyle["trackingEm"] = it }

    // 1.1.0 -> 1.2.0: A custom text decoration system is replacing the simple underline & strikethrough checkboxes.
    for (letterStyle in rawStyling.letterStyles) {
        val ul = letterStyle["underline"] == true
        val st = letterStyle["strikethrough"] == true
        if (ul || st) letterStyle.compute("decorations") { _, oldDeco ->
            val deco = ArrayList(oldDeco as List<*>? ?: emptyList())
            if (ul)
                deco.add(mapOf("preset" to "UNDERLINE"))
            if (st)
                deco.add(mapOf("preset" to "STRIKETHROUGH"))
            deco
        }
    }

    // 1.1.0 -> 1.2.0: We now support multiple variants of small caps.
    for (letterStyle in rawStyling.letterStyles)
        if (letterStyle["smallCaps"] == true)
            letterStyle["smallCaps"] = "SMALL_CAPS"

    // 1.1.0 -> 1.2.0: Because we now support mixed super- and subscripts, the enum names have changed.
    for (letterStyle in rawStyling.letterStyles) {
        val ss = letterStyle["superscript"]
        if (ss is String && (ss.last() == '1' || ss.last() == '2'))
            letterStyle["superscript"] = when (ss) {
                "NONE" -> "OFF"
                "SUP_1" -> "SUP"
                "SUB_1" -> "SUB"
                "SUP_2" -> "SUP_SUP"
                "SUB_2" -> "SUB_SUB"
                else -> ss
            }
    }

    // 1.1.0 -> 1.2.0: The absence of a background is no longer just represented by an alpha value of 0.
    for (letterStyle in rawStyling.letterStyles)
        if (letterStyle["background"].let { it is String && it.startsWith("#00") })
            letterStyle.remove("background")

    // 1.2.0 -> 1.3.0: "spineOrientation" and "alignWithAxis" are renamed.
    for (contentStyle in rawStyling.contentStyles) {
        contentStyle["spineOrientation"]?.let { contentStyle["blockOrientation"] = it }
        contentStyle["alignWithAxis"]?.let { contentStyle["spineAttachment"] = it }
    }

    // 1.2.0 -> 1.3.0: Body elements and element boxes are renamed to cells.
    for (contentStyle in rawStyling.contentStyles) {
        contentStyle["gridElemHJustifyPerCol"]?.let { contentStyle["gridCellHJustifyPerCol"] = it }
        contentStyle["gridElemVJustify"]?.let { contentStyle["gridCellVJustify"] = it }
        contentStyle["flowElemHJustify"]?.let { contentStyle["flowCellHJustify"] = it }
        contentStyle["flowElemVJustify"]?.let { contentStyle["flowCellVJustify"] = it }
    }

    // 1.2.0 -> 1.3.0: The matching of body cell extents has been reworked.
    for (contentStyle in rawStyling.contentStyles) {
        val gridC = contentStyle["gridElemBoxConform"]
        val flowC = contentStyle["flowElemBoxConform"]
        if (gridC is String) {
            if (gridC == "SQUARE") contentStyle["gridStructure"] = "SQUARE_CELLS"
            if (gridC == "WIDTH" || gridC == "WIDTH_AND_HEIGHT") contentStyle["gridStructure"] = "EQUAL_WIDTH_COLS"
            if (gridC == "HEIGHT" || gridC == "WIDTH_AND_HEIGHT") contentStyle["gridMatchRowHeight"] = "WITHIN_BLOCK"
        }
        if (flowC is String) {
            if (flowC == "SQUARE") contentStyle["flowSquareCells"] = true
            if (flowC == "WIDTH" || flowC == "WIDTH_AND_HEIGHT") contentStyle["flowMatchCellWidth"] = "WITHIN_BLOCK"
            if (flowC == "HEIGHT" || flowC == "WIDTH_AND_HEIGHT") contentStyle["flowMatchCellHeight"] = "WITHIN_BLOCK"
        }
    }
}


private fun <S : NamedStyle> readStyles(
    ctx: StylingContext,
    maps: List<Map<*, *>>,
    styleClass: Class<S>
): ImmutableList<S> {
    val styles = maps.map { readStyle(it, styleClass) }
    val updates = ensureConsistency(ctx, styles)
    return styles.map { style -> updates.getOrDefault(style, style) }.toImmutableList()
}

private fun <S : Style> readStyle(map: Map<*, *>, styleClass: Class<S>): S {
    val notarizedSettingValues = buildList {
        for (setting in getStyleSettings(styleClass))
            try {
                add(readSetting(setting, map[setting.name]!!))
            } catch (_: RuntimeException) {
                // Catches IllegalArgumentException, NullPointerException, and ClassCastException.
            }
    }
    return getPreset(styleClass).copy(notarizedSettingValues)
}

private fun <S : Style, SUBJ : Any> readSetting(
    setting: StyleSetting<S, SUBJ>,
    raw: Any
): NotarizedStyleSettingValue<S> =
    when (setting) {
        is DirectStyleSetting ->
            setting.notarize(convert(setting.type, raw))
        is OptStyleSetting ->
            setting.notarize(Opt(true, convert(setting.type, raw)))
        is ListStyleSetting ->
            setting.notarize((raw as List<*>).filterNotNull().map { convert(setting.type, it) }.toImmutableList())
    }


@Suppress("UNCHECKED_CAST")
private fun <T> convert(type: Class<T>, raw: Any): T = convertUntyped(type, raw) as T

private fun convertUntyped(type: Class<*>, raw: Any): Any = when (type) {
    Int::class.javaPrimitiveType, Int::class.javaObjectType -> (raw as Number).toInt()
    Float::class.javaPrimitiveType, Float::class.javaObjectType -> (raw as Number).toFloat()
    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> raw as Boolean
    String::class.java -> raw as String
    Locale::class.java -> Locale.forLanguageTag(raw as String)
    Color::class.java -> colorFromHex(raw as String)
    FPS::class.java -> fpsFromFraction(raw as String)
    FontFeature::class.java -> fontFeatureFromKV(raw as String)
    else -> when {
        Enum::class.java.isAssignableFrom(type) -> enumFromName(raw as String, type)
        Style::class.java.isAssignableFrom(type) -> readStyle(raw as Map<*, *>, type.asSubclass(Style::class.java))
        else -> throw UnsupportedOperationException("Reading objects of type ${type.name} is not supported.")
    }
}

private fun fontFeatureFromKV(kv: String): FontFeature {
    val parts = kv.split("=")
    require(parts.size == 2)
    return FontFeature(parts[0], parts[1].toInt())
}
