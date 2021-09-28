package com.loadingbyte.cinecred.drawer

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries


// Load the fonts that are bundled with this program.
val BUNDLED_FONTS: List<Font> = run {
    val uri = (object {}.javaClass).getResource("/fonts")!!.toURI()
    if (uri.scheme == "jar")
        FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs ->
            fs.getPath("/fonts").listDirectoryEntries().flatMap { file ->
                file.inputStream().use { Font.createFonts(it).asList() }
            }
        }
    else
        Path.of(uri).listDirectoryEntries().flatMap { file ->
            Font.createFonts(file.toFile()).asList()
        }
}

// Load the fonts that are present on the system.
val SYSTEM_FONTS: List<Font> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.filter { font ->
        when (font.getFamily(Locale.ROOT)) {
            // Exclude the logical fonts since they differ unpredictably between systems.
            Font.DIALOG, Font.DIALOG_INPUT, Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED -> false
            else -> true
        }
    }


private val nameToBundledFont = BUNDLED_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }
private val nameToSystemFont = SYSTEM_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }

fun getBundledFont(name: String): Font? =
    nameToBundledFont[name]

fun getSystemFont(name: String): Font? =
    nameToSystemFont[name]
