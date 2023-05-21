package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.*
import java.awt.Color
import java.io.IOException
import java.nio.file.Path
import java.util.*


/** @throws IOException */
fun writeStyling(stylingFile: Path, ctx: StylingContext, styling: Styling) {
    val toml = mapOf(
        "global" to writeStyle(ctx, styling, styling.global),
        "pageStyle" to styling.pageStyles.map { writeStyle(ctx, styling, it) },
        "contentStyle" to styling.contentStyles.map { writeStyle(ctx, styling, it) },
        "letterStyle" to styling.letterStyles.map { writeStyle(ctx, styling, it) }
    )

    stylingFile.parent.createDirectoriesSafely()
    writeToml(stylingFile, toml)
}


private fun <S : Style> writeStyle(ctx: StylingContext, styling: Styling, style: S): Map<String, Any> {
    // The order of the settings should be preserved, hence we use a linked map.
    val toml = LinkedHashMap<String, Any>()
    val excludedSettings = findIneffectiveSettings(ctx, styling, style).keys
    val preset = getPreset(style.javaClass)
    for (setting in getStyleSettings(style.javaClass))
        if (setting !in excludedSettings)
            when (setting) {
                is DirectStyleSetting ->
                    toml[setting.name] = convert(ctx, styling, setting.get(style))
                is OptStyleSetting -> {
                    val opt = setting.get(style)
                    if (opt.isActive)
                        toml[setting.name] = convert(ctx, styling, opt.value)
                }
                is ListStyleSetting -> {
                    val list = setting.get(style)
                    // If the list is empty, we can skip writing an empty list only if the preset list is empty too.
                    // Otherwise, the previously empty list would suddenly be filled with the preset list when reading
                    // the style later on.
                    if (list.isNotEmpty() || setting.get(preset).isNotEmpty())
                        toml[setting.name] = list.map { convert(ctx, styling, it) }
                }
            }
    return toml
}


private fun convert(ctx: StylingContext, styling: Styling, value: Any): Any = when (value) {
    is Int, is Double, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color -> value.toHex32()
    is Resolution -> value.toTimes()
    is FPS -> value.toFraction()
    is FontFeature -> "${value.tag}=${value.value}"
    is Style -> writeStyle(ctx, styling, value)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
