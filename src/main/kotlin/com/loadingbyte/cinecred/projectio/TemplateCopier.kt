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
fun tryCopyTemplate(destDir: Path, template: Template) {
    tryCopyTemplate(destDir, template, null, null, null)
}

/** @throws IOException */
fun tryCopyTemplate(destDir: Path, template: Template, creditsFormat: SpreadsheetFormat) {
    tryCopyTemplate(destDir, template, creditsFormat, null, null)
}

/**
 * @throws ForbiddenException
 * @throws DownException
 * @throws IOException
 */
fun tryCopyTemplate(destDir: Path, template: Template, creditsAccount: Account, creditsFilename: String) {
    tryCopyTemplate(destDir, template, null, creditsAccount, creditsFilename)
}

class Template(
    val locale: Locale,
    val scale: Int,
    val sample: Boolean
)


private fun tryCopyTemplate(
    destDir: Path,
    template: Template,
    creditsFormat: SpreadsheetFormat?,
    creditsAccount: Account?,
    creditsFilename: String?
) {
    // First try to write the credits file, so that if something goes wrong (which is likely with online services),
    // the project folder just isn't created at all, instead of being half-created.
    if (creditsFormat != null || creditsAccount != null)
        tryCopyCreditsTemplate(destDir, template, creditsFormat, creditsAccount, creditsFilename)
    tryCopyStylingTemplate(destDir, template)
    if (template.sample)
        tryCopyAuxiliaryFiles(destDir)
}


private fun tryCopyCreditsTemplate(
    destDir: Path,
    template: Template,
    creditsFormat: SpreadsheetFormat?,
    creditsAccount: Account?,
    creditsFilename: String?
) {
    var csv = useResourceStream("/template/credits.csv") { it.bufferedReader().readLines() }
    // If desired, cut off the sample credits and only keep the table header.
    if (!template.sample)
        csv = csv.subList(0, 2)
    val spreadsheetName = l10n("project.template.spreadsheetName")
    val spreadsheet = CsvFormat.read(spreadsheetName, csv.joinToString("\n")).map { fillIn(it, template) }
    val look = SpreadsheetLook(
        rowLooks = mapOf(
            0 to SpreadsheetLook.RowLook(height = 130, fontSize = 8, italic = true, wrap = true),
            1 to SpreadsheetLook.RowLook(bold = true, borderBottom = true)
        ),
        colWidths = listOf(45, 45, 15, 15, 25, 15, 40, 20, 25, 25)
    )
    when {
        creditsFormat != null -> {
            val destFile = destDir.resolve("Credits.${creditsFormat.fileExt}")
            if (!destFile.notExists())
                return
            destDir.createDirectoriesSafely()
            creditsFormat.write(destFile, spreadsheet, look)
        }
        creditsAccount != null && creditsFilename != null -> {
            val destFile = destDir.resolve("Credits.$WRITTEN_SERVICE_LINK_EXT")
            if (!destFile.notExists())
                return
            val link = creditsAccount.upload(creditsFilename, spreadsheet, look)
            // Uploading the credits file can take some time. If the user cancels in the meantime, the uploader is
            // actually not interrupted. So instead, we detect interruption here and stop project initialization.
            if (Thread.interrupted())
                throw InterruptedException()
            destDir.createDirectoriesSafely()
            writeServiceLink(destFile, link)
        }
        else -> throw IllegalArgumentException()
    }
}


private fun tryCopyStylingTemplate(destDir: Path, template: Template) {
    val file = destDir.resolve(STYLING_FILE_NAME)
    if (file.notExists()) {
        var lines = useResourceStream("/template/styling.toml") { it.bufferedReader().readLines() }
        // If desired, cut off the template where the first sample style declaration starts.
        if (!template.sample)
            lines = lines.subList(0, lines.indexOfFirst { "[[" in it })
        destDir.createDirectoriesSafely()
        file.writeText(fillIn(lines.joinToString("\n"), template))
    }
}


private fun tryCopyAuxiliaryFiles(destDir: Path) {
    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (logoFile.notExists()) {
        logoFile.parent.createDirectoriesSafely()
        useResourceStream("/template/cinecred.svg") { Files.copy(it, logoFile) }
    }
}


private fun fillIn(string: String, template: Template): String = string
    .replace(PLACEHOLDER_REGEX) { match ->
        val key = match.groups[1]!!.value
        if (key == "locale") template.locale.toLanguageTag() else l10n(key, template.locale)
    }
    .replace(SCALING_REGEX) { match ->
        val num = match.groups[1]!!.value
        (num.toInt() * template.scale).toString()
    }

private val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z0-9.]+)}")
private val SCALING_REGEX = Regex("<([0-9]+)>")
