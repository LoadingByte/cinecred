package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourceStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.notExists
import kotlin.io.path.writeText


/** @throws IOException */
fun tryCopyTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat, scale: Int) {
    destDir.createDirectoriesSafely()
    tryCopyStylingTemplate(destDir, locale, scale)
    tryCopyCreditsTemplate(destDir, locale, format, scale)
}


private fun tryCopyStylingTemplate(destDir: Path, locale: Locale, scale: Int) {
    val text = filt(useResourceStream("/template/styling.toml") { it.bufferedReader().readText() }, locale, scale)
    val file = destDir.resolve(STYLING_FILE_NAME)
    if (file.notExists())
        file.writeText(text)
}


private fun tryCopyCreditsTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat, scale: Int) {
    if (locateCreditsFile(destDir).first != null)
        return

    val csv = useResourceStream("/template/credits.csv") { it.bufferedReader().readText() }
    val spreadsheet = CsvFormat.read(csv).map { filt(it, locale, scale) }
    format.write(
        destDir.resolve("Credits.${format.fileExt}"), spreadsheet,
        rowLooks = mapOf(
            0 to SpreadsheetFormat.RowLook(height = 60, fontSize = 8, italic = true, wrap = true),
            1 to SpreadsheetFormat.RowLook(bold = true, borderBottom = true)
        ),
        colWidths = listOf(48, 48, 32, 16, 28, 20, 24, 16, 24, 36)
    )

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (logoFile.notExists()) {
        logoFile.parent.createDirectoriesSafely()
        useResourceStream("/template/cinecred.svg") { Files.copy(it, logoFile) }
    }
}


private fun filt(string: String, locale: Locale, scale: Int): String = string
    .replace(PLACEHOLDER_REGEX) { match ->
        val key = match.groups[1]!!.value
        if (key == "locale") locale.toLanguageTag() else l10n(key, locale)
    }
    .replace(SCALING_REGEX) { match ->
        val num = match.groups[1]!!.value
        (num.toInt() * scale).toString()
    }

private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")
private val SCALING_REGEX = Regex("<([0-9]+)>")
