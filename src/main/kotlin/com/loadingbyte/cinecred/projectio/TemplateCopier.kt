package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourceStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.writeText


fun tryCopyTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat) {
    if (destDir.notExists())
        destDir.createDirectories()

    tryCopyStylingTemplate(destDir, locale)
    tryCopyCreditsTemplate(destDir, locale, format)
}


private fun tryCopyStylingTemplate(destDir: Path, locale: Locale) {
    val text = useResourceStream("/template/styling.toml") { it.bufferedReader().readText() }.fillPlaceholders(locale)
    val file = destDir.resolve(STYLING_FILE_NAME)
    if (file.notExists())
        file.writeText(text)
}


private fun tryCopyCreditsTemplate(destDir: Path, locale: Locale, format: SpreadsheetFormat) {
    if (locateCreditsFile(destDir).first != null)
        return

    val csv = useResourceStream("/template/credits.csv") { it.bufferedReader().readText() }
    val spreadsheet = CsvFormat.read(csv).map { it.fillPlaceholders(locale) }
    format.write(
        destDir.resolve("Credits.${format.fileExt}"), spreadsheet,
        rowLooks = mapOf(
            0 to SpreadsheetFormat.RowLook(height = 60, fontSize = 8, italic = true, wrap = true),
            1 to SpreadsheetFormat.RowLook(bold = true, borderBottom = true)
        ),
        colWidths = listOf(48, 48, 32, 16, 28, 20, 24, 24, 36)
    )

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (logoFile.notExists()) {
        logoFile.parent.createDirectories()
        useResourceStream("/template/cinecred.svg") { Files.copy(it, logoFile) }
    }
}


private fun String.fillPlaceholders(locale: Locale): String =
    replace(PLACEHOLDER_REGEX) { match ->
        val key = match.groups[1]!!.value
        if (key == "locale") locale.toLanguageTag() else l10n(key, locale)
    }

private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")
