package com.loadingbyte.cinecred.drawer

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.isTTFOrOTF
import com.loadingbyte.cinecred.common.useResourcePath
import com.loadingbyte.cinecred.common.walkSafely
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*


// Load the fonts that are bundled with this program.
val BUNDLED_FONTS: List<Font> = useResourcePath("/fonts") { fontsDir ->
    fontsDir.listDirectoryEntries().flatMap { file ->
        if (file.fileSystem.provider().scheme == "file")
            Font.createFonts(file.toFile()).asList()
        else
            file.inputStream().use { Font.createFonts(it).asList() }
    }
}

// Load the fonts that are present on the system. We only want to include TrueType/OpenType fonts, because:
//   - Logical fonts (e.g., "Dialog" and "Serif") and system-native fonts differ unpredictably between systems.
//   - At various points in the code, notably ReflectionExt and PDF font embedding, we only support TTF/OTF.
val SYSTEM_FONTS: List<Font> =
    if (SystemInfo.isMacOS) {
        // On macOS, the JDK only supplies us with "CFont" implementations, which do not let us access the underlying
        // TTF/OTF structure, and not even contain a path to the originating font file. Hence, we need to manually read
        // in all font files from the well-known font directories.
        sequenceOf(
            Path("/System/Library/Fonts"),
            Path("/Network/Library/Fonts"),
            Path("/Library/Fonts"),
            Path(System.getProperty("user.home")).resolve("Library/Fonts")
        )
            .filter(Path::exists)
            .flatMap(Path::walkSafely)
            .filter(Path::isRegularFile)
            // The createFonts() method can only successfully read TrueType/OpenType fonts, which is desired.
            // If a FontFormatException or IOException occurs, just skip over the problematic font file.
            .flatMap {
                try {
                    Font.createFonts(it.toFile()).asSequence()
                } catch (_: Exception) {
                    // This happens quite regularly on macOS, so do not log the stacktrace to not spam the log.
                    LOGGER.error("Skipping system font '{}' because it is corrupt or cannot be read.", it)
                    emptySequence()
                }
            }
            // Internal macOS fonts start with a dot; we do not want to include those.
            .filter { !it.getFamily(Locale.ROOT).startsWith('.') && !it.getFontName(Locale.ROOT).startsWith('.') }
            .toList()
    } else
        GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.filter(Font::isTTFOrOTF)


private val nameToBundledFont = BUNDLED_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }
private val nameToSystemFont = SYSTEM_FONTS
    .associateBy { font -> font.getFontName(Locale.ROOT) }

fun getBundledFont(name: String): Font? =
    nameToBundledFont[name]

fun getSystemFont(name: String): Font? =
    nameToSystemFont[name]
