package com.loadingbyte.cinecred.drawer

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.*
import kotlin.streams.toList


// Load the fonts that are bundled with this program.
val BUNDLED_FONTS: List<Font> = run {
    val dummyClass = object {}.javaClass
    dummyClass.getResourceAsStream("/fonts").use { it.bufferedReader().lines() }
        .map { filename -> dummyClass.getResourceAsStream("/fonts/$filename").use { Font.createFonts(it)[0] } }
        .toList()
}

// Load the fonts that are present on the system.
val SYSTEM_FONTS: List<Font> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
        .toList()


private val nameToBundledFont = BUNDLED_FONTS
    .associateBy { font -> font.getFontName(Locale.US) }
private val nameToSystemFont = SYSTEM_FONTS
    .associateBy { font -> font.getFontName(Locale.US) }

fun getBundledFont(name: String): Font? =
    nameToBundledFont[name]

// Note: If the font map doesn't contain a font with the specified name, we create a font object to find a font
// that (hopefully) best matches the specified font.
fun getSystemFont(name: String): Font =
    nameToSystemFont[name] ?: Font(name, 0, 1)
