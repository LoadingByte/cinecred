package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.Picture
import java.awt.Font
import java.awt.FontFormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO


private val Path.extension: String
    get() = fileName.toString().substringAfterLast('.').toLowerCase()


fun tryReadFont(fontFile: Path): Font? {
    val ext = fontFile.extension
    if (Files.isRegularFile(fontFile) && (ext == "ttf" || ext == "otf"))
        try {
            return Font.createFonts(fontFile.toFile())[0]
        } catch (_: FontFormatException) {
        }
    return null
}


private val RASTER_PICTURE_EXTS = ImageIO.getReaderFileSuffixes().toSet()

fun tryReadPictureLoader(pictureFile: Path): Lazy<Picture?>? {
    val ext = pictureFile.extension
    if (Files.isRegularFile(pictureFile) && ext in RASTER_PICTURE_EXTS)
        return lazy {
            try {
                Picture.Raster(ImageIO.read(pictureFile.toFile()))
            } catch (_: IOException) {
                null
            }
        }
    return null
}
