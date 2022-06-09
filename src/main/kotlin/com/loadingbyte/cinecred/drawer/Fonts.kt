package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.useResourcePath
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.*
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries


// Load the fonts that are bundled with this program.
val BUNDLED_FONTS: List<Font> = useResourcePath("/fonts") { fontsDir ->
    fontsDir.listDirectoryEntries().flatMap { file ->
        if (file.fileSystem.provider().scheme == "file")
            Font.createFonts(file.toFile()).asList()
        else
            file.inputStream().use { Font.createFonts(it).asList() }
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
