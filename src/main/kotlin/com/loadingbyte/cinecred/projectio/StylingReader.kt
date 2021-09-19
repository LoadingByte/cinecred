package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.toImmutableList
import java.awt.Color
import java.nio.file.Path
import java.util.*


fun readStyling(stylingFile: Path): Styling {
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
        rawStyling.pageStyles.map { readStyle(it, PageStyle::class.java) }.toImmutableList(),
        rawStyling.contentStyles.map { readStyle(it, ContentStyle::class.java) }.toImmutableList(),
        rawStyling.letterStyles.map { readStyle(it, LetterStyle::class.java) }.toImmutableList()
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
}


private fun <S : Style> readStyle(map: Map<String, Any>, styleClass: Class<S>): S {
    val settingValues = getStyleSettings(styleClass).map { setting ->
        try {
            when (setting) {
                is DirectStyleSetting ->
                    convert(setting.type, map.getValue(setting.name))
                is OptStyleSetting ->
                    Opt(true, convert(setting.type, map.getValue(setting.name)))
                is ListStyleSetting ->
                    (map.getValue(setting.name) as List<*>)
                        .filterNotNull()
                        .map { convert(setting.type, it) }
                        .toImmutableList()
            }
        } catch (_: RuntimeException) {
            // Catches IllegalArgumentException, NullPointerException, and ClassCastException.
            setting.get(getPreset(styleClass))
        }
    }

    return newStyle(styleClass, settingValues)
}


private fun convert(type: Class<*>, raw: Any?): Any = when (type) {
    Int::class.javaPrimitiveType, Int::class.javaObjectType -> (raw as Number).toInt()
    Float::class.javaPrimitiveType, Float::class.javaObjectType -> (raw as Number).toFloat()
    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> raw as Boolean
    String::class.java -> raw as String
    Locale::class.java -> Locale.forLanguageTag(raw as String)
    Color::class.java -> (raw as String).hexToColor()
    FPS::class.java -> (raw as String).toFPS()
    else -> when {
        Enum::class.java.isAssignableFrom(type) -> (raw as String).toEnum(type)
        Style::class.java.isAssignableFrom(type) ->
            @Suppress("UNCHECKED_CAST")
            readStyle(raw as Map<String, Any>, type as Class<Style>)
        else -> throw UnsupportedOperationException("Reading objects of type ${type.name} is not supported.")
    }
}
