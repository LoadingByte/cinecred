package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.l10nBundle
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.regex.Pattern


fun copyStylingTemplate(destDir: Path, locale: Locale) {
    copyFillingPlaceholders("/template/styling.toml", destDir.resolve("Styling.toml"), locale)
}


fun copyCreditsTemplate(destDir: Path, locale: Locale) {
    copyFillingPlaceholders("/template/credits.csv", destDir.resolve("Credits.csv"), locale)

    val logoFile = destDir.resolve("Logos").resolve("Cinecred.svg")
    if (!Files.exists(logoFile)) {
        Files.createDirectories(logoFile.parent)
        Dummy.javaClass.getResourceAsStream("/logo.svg").use { stream -> Files.copy(stream, logoFile) }
    }
}


private val PLACEHOLDER_REGEX = Pattern.compile("\\{([^}]+)}")

private fun copyFillingPlaceholders(resourceName: String, dest: Path, locale: Locale) {
    val bundle = l10nBundle(locale)
    val content = Dummy.javaClass.getResourceAsStream(resourceName).use { stream ->
        stream.bufferedReader().readLines()
    }.map { line ->
        PLACEHOLDER_REGEX.matcher(line).replaceAll { match -> bundle.getString(match.group(1)) }
    }
    Files.write(dest, content)
}


private object Dummy
