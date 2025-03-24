package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.project.*
import java.io.IOException
import java.nio.file.Path
import java.util.*


/** @throws IOException */
fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = buildMap {
        put("version", VERSION)
        put("global", writeStyle(styling, styling.global))
        putStyles("pageStyle", styling, styling.pageStyles)
        putStyles("contentStyle", styling, styling.contentStyles)
        putStyles("letterStyle", styling, styling.letterStyles)
        putStyles("pictureStyle", styling, styling.pictureStyles)
        putStyles("tapeStyle", styling, styling.tapeStyles)
    }

    stylingFile.parent.createDirectoriesSafely()
    writeToml(stylingFile, toml)
}


private fun MutableMap<String, Any>.putStyles(key: String, styling: Styling, styles: List<Style>) {
    val filteredStyles = styles.filter { style -> style !is PopupStyle || !style.volatile }
    if (filteredStyles.isNotEmpty())
        put(key, filteredStyles.map { writeStyle(styling, it) })
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
                    convert(styling, setting.get(style))?.let { toml[setting.name] = it }
                is OptStyleSetting -> {
                    val opt = setting.get(style)
                    if (opt.isActive)
                        convert(styling, opt.value)?.let { toml[setting.name] = it }
                }
                is ListStyleSetting -> {
                    val list = setting.get(style)
                    // If the list is empty, we can skip writing an empty list only if the preset list is empty too.
                    // Otherwise, the previously empty list would suddenly be filled with the preset list when reading
                    // the style later on.
                    if (list.isNotEmpty() || setting.get(preset).isNotEmpty())
                        toml[setting.name] = list.mapNotNull { convert(styling, it) }
                }
            }
    return toml
}


private fun convert(styling: Styling, value: Any): Any? = when (value) {
    is Int, is Double, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color4f -> value.convert(ColorSpace.XYZD50).rgba().asList()
    is Resolution -> value.toString()
    is FPS -> value.toString()
    is FontRef -> value.name
    is PictureRef -> value.name
    is TapeRef -> value.name
    is FontFeature -> "${value.tag}=${value.value}"
    is TapeSlice -> listOf(value.inPoint, value.outPoint).joinToString("-") {
        val v = it.value
        if (!it.isActive) "" else if (v is Timecode.Clock) "${v.numerator}/${v.denominator}" else v.toString()
    }.let { if (it.length == 1) null else it }
    is Style -> writeStyle(styling, value)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
