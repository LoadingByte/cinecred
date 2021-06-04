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
    val text = readFillingPlaceholders("/template/styling.toml", locale)
    destDir.resolve("Styling.toml").writeText(text)
}


private fun copyCreditsTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat) {
    val csv = readFillingPlaceholders("/template/credits.csv", locale)
    val spreadsheet = CsvFormat.read(csv)
    val colWidths = mapOf(0 to 24, 1 to 24, 2 to 16, 3 to 4, 4 to 14, 5 to 4)
    format.write(destDir.resolve("Credits.${format.fileExt}"), spreadsheet, colWidths)

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (logoFile.notExists()) {
        logoFile.parent.createDirectories()
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
