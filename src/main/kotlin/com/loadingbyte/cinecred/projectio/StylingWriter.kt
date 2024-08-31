package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.writeToml
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.project.*
import java.io.IOException
import java.nio.file.Path
import java.util.*


/** @throws IOException */
fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = buildMap {
        put("global", writeStyle(styling, styling.global))
        if (styling.pageStyles.isNotEmpty())
            put("pageStyle", styling.pageStyles.map { writeStyle(styling, it) })
        if (styling.contentStyles.isNotEmpty())
            put("contentStyle", styling.contentStyles.map { writeStyle(styling, it) })
        if (styling.letterStyles.isNotEmpty())
            put("letterStyle", styling.letterStyles.map { writeStyle(styling, it) })
    }

    stylingFile.parent.createDirectoriesSafely()
    writeToml(stylingFile, toml)
}


private fun <S : Style> writeStyle(styling: Styling, style: S): Map<String, Any> {
    // The order of the settings should be preserved, hence we use a linked map.
    val toml = LinkedHashMap<String, Any>()
    val excludedSettings = findIneffectiveSettings(styling, style).keys
    val preset = getPreset(style.javaClass)
    for (setting in getStyleSettings(style.javaClass))
        if (setting !in excludedSettings)
            when (setting) {
                is DirectStyleSetting ->
                    toml[setting.name] = convert(styling, setting.get(style))
                is OptStyleSetting -> {
                    val opt = setting.get(style)
                    if (opt.isActive)
                        toml[setting.name] = convert(styling, opt.value)
                }
                is ListStyleSetting -> {
                    val list = setting.get(style)
                    // If the list is empty, we can skip writing an empty list only if the preset list is empty too.
                    // Otherwise, the previously empty list would suddenly be filled with the preset list when reading
                    // the style later on.
                    if (list.isNotEmpty() || setting.get(preset).isNotEmpty())
                        toml[setting.name] = list.map { convert(styling, it) }
                }
            }
    return toml
}


private fun convert(styling: Styling, value: Any): Any = when (value) {
    is Int, is Double, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color4f -> value.convert(ColorSpace.XYZD50).rgba().asList()
    is Resolution -> value.toString()
    is FPS -> value.toString()
    is FontFeature -> "${value.tag}=${value.value}"
    is Style -> writeStyle(styling, value)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
