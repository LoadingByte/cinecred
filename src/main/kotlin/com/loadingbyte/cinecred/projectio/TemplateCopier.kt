package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.l10n
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


fun copyTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat, copyStyling: Boolean, copyCredits: Boolean) {
    if (Files.notExists(destDir))
        Files.createDirectories(destDir)

    if (copyStyling)
        copyStylingTemplate(destDir, locale)
    if (copyCredits)
        copyCreditsTemplate(destDir, locale, format)
}


private fun copyStylingTemplate(destDir: Path, locale: Locale) {
    val text = readFillingPlaceholders("/template/styling.toml", locale)
    Files.writeString(destDir.resolve("Styling.toml"), text)
}


private fun copyCreditsTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat) {
    val csv = readFillingPlaceholders("/template/credits.csv", locale)
    val spreadsheet = CsvFormat.read(csv)
    val colWidths = mapOf(3 to 14, 4 to 16, 5 to 24, 6 to 24, 7 to 24)
    format.write(destDir.resolve("Credits.${format.fileExt}"), spreadsheet, colWidths)

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (!Files.exists(logoFile)) {
        Files.createDirectories(logoFile.parent)
        Dummy.javaClass.getResourceAsStream("/logo.svg")!!.use { stream -> Files.copy(stream, logoFile) }
    }
}


private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")

private fun readFillingPlaceholders(resourceName: String, locale: Locale): String =
    Dummy.javaClass.getResourceAsStream(resourceName)!!.use { stream ->
        stream.bufferedReader().readText()
    }.replace(PLACEHOLDER_REGEX) { match ->
        val key = match.groups[1]!!.value
        if (key == "locale") locale.toLanguageTag() else l10n(key, locale)
    }


private object Dummy
