package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.Toml
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.awt.Color
import java.nio.file.Path
import java.util.*


fun readStyling(stylingFile: Path): Styling {
    val toml = Toml.read(stylingFile.toFile())

    val globalMap = toml["global"]
    val global =
        if (globalMap == null || globalMap !is Map<*, *>) PRESET_GLOBAL
        else readStyle(globalMap, PRESET_GLOBAL)

    val pageStyles = readMapList(toml["pageStyle"]) { readStyle(it, PRESET_PAGE_STYLE) }
    val contentStyles = readMapList(toml["contentStyle"]) { readStyle(it, PRESET_CONTENT_STYLE) }
    val letterStyles = readMapList(toml["letterStyle"]) { readStyle(it, PRESET_LETTER_STYLE) }

    return Styling(global, pageStyles, contentStyles, letterStyles)
}


private fun <E> readMapList(mapList: Any?, readMap: (Map<*, *>) -> E) =
    if (mapList == null || mapList !is List<*>) persistentListOf()
    else mapList.filterIsInstance<Map<*, *>>().map(readMap).toImmutableList()


private fun <S : Style> readStyle(map: Map<*, *>, stylePreset: S): S {
    val styleClass = stylePreset.javaClass

    val settingValues = getStyleSettings(styleClass).map { setting ->
        try {
            if (ImmutableList::class.java.isAssignableFrom(setting.type))
                (map[setting.name]!! as List<*>)
                    .map { convert(setting.genericArg!!, it.toString()) }
                    .toImmutableList()
            else
                convert(setting.type, map[setting.name]!!.toString())
        } catch (_: RuntimeException) {
            // Also catches NullPointerException & ClassCastException
            setting.get(stylePreset)
        }
    }

    return newStyle(styleClass, settingValues)
}


private fun convert(type: Class<*>, str: String): Any = when (type) {
    Int::class.java -> str.toInt()
    Float::class.java -> str.toFloat()
    Boolean::class.java -> str.toBoolean()
    String::class.java -> str
    Locale::class.java -> Locale.forLanguageTag(str)
    Color::class.java -> str.toColor()
    FPS::class.java -> str.toFPS()
    else -> when {
        Enum::class.java.isAssignableFrom(type) ->
            @Suppress("UNCHECKED_CAST")
            java.lang.Enum.valueOf(type as Class<Enum<*>>, str.toUpperCase())
        else -> throw UnsupportedOperationException("Reading objects of type ${type.name} is not supported.")
    }
}
