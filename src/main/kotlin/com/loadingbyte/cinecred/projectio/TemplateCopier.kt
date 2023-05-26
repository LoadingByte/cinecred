package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourceStream
import com.loadingbyte.cinecred.projectio.service.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.notExists
import kotlin.io.path.writeText


/** @throws IOException */
fun tryCopyTemplate(destDir: Path, locale: Locale, scale: Int, creditsFormat: SpreadsheetFormat) {
    tryCopyTemplate(destDir, locale, scale, creditsFormat, null, null)
}

/**
 * @throws ForbiddenException
 * @throws DownException
 * @throws IOException
 */
fun tryCopyTemplate(destDir: Path, locale: Locale, scale: Int, creditsService: Service, creditsFilename: String) {
    tryCopyTemplate(destDir, locale, scale, null, creditsService, creditsFilename)
}


private fun tryCopyTemplate(
    destDir: Path,
    locale: Locale,
    scale: Int,
    creditsFormat: SpreadsheetFormat?,
    creditsService: Service?,
    creditsFilename: String?
) {
    // First try to write the credits file, so that if something goes wrong (which is likely with online services),
    // the project folder just isn't created at all, instead of being half-created.
    tryCopyCreditsTemplate(destDir, locale, scale, creditsFormat, creditsService, creditsFilename)
    tryCopyStylingTemplate(destDir, locale, scale)
}


private fun tryCopyStylingTemplate(destDir: Path, locale: Locale, scale: Int) {
    val text = filt(useResourceStream("/template/styling.toml") { it.bufferedReader().readText() }, locale, scale)
    val file = destDir.resolve(STYLING_FILE_NAME)
    if (file.notExists())
        file.writeText(text)
}


private fun tryCopyCreditsTemplate(
    destDir: Path,
    locale: Locale,
    scale: Int,
    creditsFormat: SpreadsheetFormat?,
    creditsService: Service?,
    creditsFilename: String?
) {
    if (ProjectIntake.locateCreditsFile(destDir).first != null)
        return

    val csv = useResourceStream("/template/credits.csv") { it.bufferedReader().readText() }
    val spreadsheet = CsvFormat.read(csv).map { filt(it, locale, scale) }
    val look = SpreadsheetLook(
        rowLooks = mapOf(
            0 to SpreadsheetLook.RowLook(height = 80, fontSize = 8, italic = true, wrap = true),
            1 to SpreadsheetLook.RowLook(bold = true, borderBottom = true)
        ),
        colWidths = listOf(48, 48, 32, 16, 28, 20, 48, 24, 36)
    )
    when {
        creditsFormat != null -> {
            destDir.createDirectoriesSafely()
            creditsFormat.write(destDir.resolve("Credits.${creditsFormat.fileExt}"), spreadsheet, "Credits", look)
        }
        creditsService != null && creditsFilename != null -> {
            val link = creditsService.upload(creditsFilename, "Credits", spreadsheet, look)
            // Uploading the credits file can take some time. If the user cancels in the meantime, the uploader is
            // actually not interrupted. So instead, we detect interruption here and stop project initialization.
            if (Thread.interrupted())
                throw InterruptedException()
            destDir.createDirectoriesSafely()
            writeServiceLink(destDir.resolve("Credits.$WRITTEN_SERVICE_LINK_EXT"), link)
        }
        else -> throw IllegalArgumentException()
    }

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
