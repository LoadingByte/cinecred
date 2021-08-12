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
    GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.toList()


private val nameToBundledFont = BUNDLED_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }
private val nameToSystemFont = SYSTEM_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }

fun getBundledFont(name: String): Font? =
    nameToBundledFont[name]

// Note: If the font map doesn't contain a font with the specified name, we create a font object to find a font
// that (hopefully) best matches the specified font.
fun getSystemFont(name: String): Font =
    nameToSystemFont[name] ?: Font(name, 0, 1)
