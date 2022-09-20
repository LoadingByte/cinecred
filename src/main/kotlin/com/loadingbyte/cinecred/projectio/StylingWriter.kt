package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.TomlWriter
import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.toFraction
import com.loadingbyte.cinecred.common.toHex32
import com.loadingbyte.cinecred.project.*
import java.awt.Color
import java.nio.file.Path
import java.util.*
import kotlin.io.path.bufferedWriter


fun writeStyling(stylingFile: Path, ctx: StylingContext, styling: Styling) {
    val toml = mapOf(
        "global" to writeStyle(ctx, styling.global),
        "pageStyle" to styling.pageStyles.map { writeStyle(ctx, it) },
        "contentStyle" to styling.contentStyles.map { writeStyle(ctx, it) },
        "letterStyle" to styling.letterStyles.map { writeStyle(ctx, it) }
    )

    stylingFile.bufferedWriter().use { writer ->
        // Use the Unix line separator (\n) and not the OS-specific one, which would be the default.
        TomlWriter(writer, 1, false, "\n").write(toml)
    }
}


private fun <S : Style> writeStyle(ctx: StylingContext, style: S): Map<String, Any> {
    // The order of the settings should be preserved, hence we use a linked map.
    val toml = LinkedHashMap<String, Any>()
    val excludedSettings = findIneffectiveSettings(ctx, style)
    for (setting in getStyleSettings(style.javaClass))
        if (setting !in excludedSettings)
            when (setting) {
                is DirectStyleSetting ->
                    toml[setting.name] = convert(ctx, setting.get(style))
                is OptStyleSetting -> {
                    val opt = setting.get(style)
                    if (opt.isActive)
                        toml[setting.name] = convert(ctx, opt.value)
                }
                is ListStyleSetting -> {
                    val list = setting.get(style)
                    if (list.isNotEmpty())
                        toml[setting.name] = list.map { convert(ctx, it) }
                }
            }
    return toml
}


private fun convert(ctx: StylingContext, value: Any): Any = when (value) {
    is Int, is Float, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color -> value.toHex32()
    is FPS -> value.toFraction()
    is FontFeature -> "${value.tag}=${value.value}"
    is Style -> writeStyle(ctx, value)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
