package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.TomlWriter
import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.toFraction
import com.loadingbyte.cinecred.common.toHex32
import com.loadingbyte.cinecred.project.*
import java.awt.Color
import java.io.StringWriter
import java.nio.file.Path
import java.util.*
import kotlin.io.path.writeText


fun writeStyling(stylingFile: Path, ctx: StylingContext, styling: Styling) {
    val toml = mapOf(
        "global" to writeStyle(ctx, styling, styling.global),
        "pageStyle" to styling.pageStyles.map { writeStyle(ctx, styling, it) },
        "contentStyle" to styling.contentStyles.map { writeStyle(ctx, styling, it) },
        "letterStyle" to styling.letterStyles.map { writeStyle(ctx, styling, it) }
    )

    // First write the text to a string and not directly to the file so that the file is not corrupted
    // if TomlWriter happens to crash the program.
    val text = StringWriter().also { writer ->
        // Use the Unix line separator (\n) and not the OS-specific one, which would be the default.
        TomlWriter(writer, 1, false, "\n").write(toml)
    }.toString()

    stylingFile.writeText(text)
}


private fun <S : Style> writeStyle(ctx: StylingContext, styling: Styling, style: S): Map<String, Any> {
    // The order of the settings should be preserved, hence we use a linked map.
    val toml = LinkedHashMap<String, Any>()
    val excludedSettings = findIneffectiveSettings(ctx, styling, style)
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
                    if (list.isNotEmpty())
                        toml[setting.name] = list.map { convert(ctx, styling, it) }
                }
            }
    return toml
}


private fun convert(ctx: StylingContext, styling: Styling, value: Any): Any = when (value) {
    is Int, is Float, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color -> value.toHex32()
    is FPS -> value.toFraction()
    is FontFeature -> "${value.tag}=${value.value}"
    is Style -> writeStyle(ctx, styling, value)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
