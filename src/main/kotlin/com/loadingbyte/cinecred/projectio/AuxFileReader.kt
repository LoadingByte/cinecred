package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import java.awt.Font
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name


private val FONT_FILE_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "ttf", "ttc", "otf", "otc")

/** Doesn't throw, instead returns an empty list. */
fun tryReadFonts(fontFile: Path): List<Font> {
    val ext = fontFile.extension
    if (fontFile.isRegularFile() && ext in FONT_FILE_EXTS)
        try {
            return Font.createFonts(fontFile.toFile()).toList()
        } catch (e: Exception) {
            LOGGER.error("Skipping font '{}' because it is corrupt or cannot be read.", fontFile, e)
        }
    return emptyList()
}


/** Doesn't throw; instead, the picture loader returns null when loaded. */
fun tryReadPictureLoader(pictureFile: Path): PictureLoader? =
    if (pictureFile.isRegularFile() && pictureFile.extension in Picture.EXTS)
        PictureLoader(pictureFile)
    else
        null

class PictureLoader(file: Path) {
    private val lazy = lazy {
        try {
            Picture.load(file)
        } catch (e: Exception) {
            LOGGER.error("Skipping picture '{}' because it is corrupt or cannot be read.", file, e)
            null
        }
    }
    val filename: String = file.name
    val picture: Picture? get() = lazy.value
    fun dispose() {
        if (lazy.isInitialized()) lazy.value?.close()
    }
}


/** Doesn't throw, instead returns null. */
fun tryRecognizeTape(tapeFileOrDir: Path): Tape? =
    try {
        Tape.recognize(tapeFileOrDir)
    } catch (e: IOException) {
        LOGGER.error("Skipping tape '{}' because it is corrupt or cannot be read.", tapeFileOrDir, e)
        null
    }
