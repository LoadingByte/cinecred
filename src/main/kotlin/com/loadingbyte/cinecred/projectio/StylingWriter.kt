package com.loadingbyte.cinecred.projectio

import com.electronwill.toml.TomlWriter
import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.project.findIrrelevantSettings
import com.loadingbyte.cinecred.project.getStyleSettings
import java.awt.Color
import java.nio.file.Path
import java.util.*
import kotlin.io.path.bufferedWriter


fun writeStyling(stylingFile: Path, styling: Styling) {
    val toml = mapOf(
        "global" to writeStyle(styling.global),
        "pageStyle" to styling.pageStyles.map(::writeStyle),
        "contentStyle" to styling.contentStyles.map(::writeStyle),
        "letterStyle" to styling.letterStyles.map(::writeStyle)
    )

    stylingFile.bufferedWriter().use { writer ->
        // Use the Unix line separator (\n) and not the OS-specific one, which would be the default.
        TomlWriter(writer, 1, false, "\n").write(toml)
    }
}


private fun <S : Any> writeStyle(style: S): Map<String, Any> {
    // The order of the settings should be preserved, hence we use a linked map.
    val toml = LinkedHashMap<String, Any>()
    val excludedSettings = findIrrelevantSettings(style)
    for (setting in getStyleSettings(style.javaClass))
        if (setting !in excludedSettings)
            setting.get(style)?.let { toml[setting.name] = convert(it) }
    return toml
}


private fun convert(value: Any): Any = when (value) {
    is Int, is Float, is Boolean, is String -> value
    is Enum<*> -> value.name
    is Locale -> value.toLanguageTag()
    is Color -> value.toHex32()
    is FPS -> value.toString2()
    is List<*> -> value.filterNotNull().map(::convert)
    else -> throw UnsupportedOperationException("Writing objects of type ${value.javaClass.name} is not supported.")
}
