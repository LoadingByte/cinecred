package com.loadingbyte.cinecred.common

import com.loadingbyte.cinecred.project.FontSpec
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
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


// This is a reference graphics context used to measure the size of fonts.
val REF_G2: Graphics2D = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    .createGraphics()
    .apply { setHighQuality() }
val REF_FRC: FontRenderContext = REF_G2.fontRenderContext


class RichFont(val spec: FontSpec, val awt: Font) {
    val metrics: FontMetrics = REF_G2.getFontMetrics(awt)
}


fun Graphics2D.setHighQuality() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}


fun Font.getStringWidth(str: String): Float =
    TextLayout(str, this, REF_FRC).advance
