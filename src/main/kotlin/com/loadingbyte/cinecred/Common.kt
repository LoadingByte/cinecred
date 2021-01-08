package com.loadingbyte.cinecred

import java.text.MessageFormat
import java.util.*


enum class Severity { INFO, WARN, ERROR }


fun l10nBundle(locale: Locale): ResourceBundle = ResourceBundle.getBundle("l10n.strings", locale)

private val DEFAULT_BUNDLE = l10nBundle(Locale.getDefault())
fun l10n(key: String): String = DEFAULT_BUNDLE.getString(key)
fun l10n(key: String, vararg args: Any?): String = MessageFormat.format(l10n(key), *args)

val SUPPORTED_LOCALES = mapOf(
    Locale.ROOT to "English",
    Locale.GERMAN to "Deutsch"
)
private val BUNDLES = SUPPORTED_LOCALES.keys.map { locale -> l10nBundle(locale) }
private val l10nAllCache = mutableMapOf<String, List<String>>()
fun l10nAll(key: String): List<String> = l10nAllCache.getOrPut(key) {
    BUNDLES.map { bundle -> bundle.getString(key) }
}
