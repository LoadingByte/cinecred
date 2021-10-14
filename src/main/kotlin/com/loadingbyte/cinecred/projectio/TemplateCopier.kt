package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.l10n
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.writeText


fun copyTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat, copyStyling: Boolean, copyCredits: Boolean) {
    if (destDir.notExists())
        destDir.createDirectories()

    if (copyStyling)
        copyStylingTemplate(destDir, locale)
    if (copyCredits)
        copyCreditsTemplate(destDir, locale, format)
}


private fun copyStylingTemplate(destDir: Path, locale: Locale) {
    val text = readResource("/template/styling.toml").fillPlaceholders(locale)
    destDir.resolve("Styling.toml").writeText(text)
}


private fun copyCreditsTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat) {
    val csv = readResource("/template/credits.csv")
    val spreadsheet = CsvFormat.read(csv)
        .map { record -> SpreadsheetRecord(record.recordNo, record.cells.map { it.fillPlaceholders(locale) }) }
    format.write(
        destDir.resolve("Credits.${format.fileExt}"), spreadsheet,
        rowLooks = mapOf(
            0 to SpreadsheetFormat.RowLook(height = 60, fontSize = 8, italic = true, wrap = true),
            1 to SpreadsheetFormat.RowLook(bold = true, borderBottom = true)
        ),
        colWidths = listOf(48, 48, 32, 16, 28, 16, 24, 24, 32)
    )

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (logoFile.notExists()) {
        logoFile.parent.createDirectories()
        Dummy.javaClass.getResourceAsStream("/logo.svg")!!.use { stream -> Files.copy(stream, logoFile) }
    }
}


private fun readResource(resourceName: String): String =
    Dummy.javaClass.getResourceAsStream(resourceName)!!.use { stream ->
        stream.bufferedReader().readText()
    }

private object Dummy


private fun String.fillPlaceholders(locale: Locale): String =
    replace(PLACEHOLDER_REGEX) { match ->
        val key = match.groups[1]!!.value
        if (key == "locale") locale.toLanguageTag() else l10n(key, locale)
    }

private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")
