package com.loadingbyte.cinecred.common

import com.loadingbyte.cinecred.project.FontSpec
import org.apache.batik.ext.awt.image.GraphicsUtil
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints.*
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


inline fun Graphics.withNewG2(block: (Graphics2D) -> Unit) {
    val g2 = create() as Graphics2D
    try {
        block(g2)
    } finally {
        g2.dispose()
    }
}


inline fun BufferedImage.withG2(block: (Graphics2D) -> Unit): BufferedImage {
    // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
    val g2 = GraphicsUtil.createGraphics(this)
    try {
        block(g2)
    } finally {
        g2.dispose()
    }
    return this
}


fun Graphics2D.setHighQuality() {
    setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)
    setRenderingHint(KEY_ALPHA_INTERPOLATION, VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(KEY_COLOR_RENDERING, VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(KEY_DITHERING, VALUE_DITHER_DISABLE)
    setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY)
    setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON)
    // For some reason, using fractional font metrics sometimes leads to letter spacing issues. However, we have to
    // activate it nevertheless for the getStringWidth() method to yield correct results. In DeferredImage's text
    // drawing code, we implement a workaround that avoids the letter spacing issues.
    setRenderingHint(KEY_FRACTIONALMETRICS, VALUE_FRACTIONALMETRICS_ON)
}


fun Font.getStringWidth(str: String): Float =
    TextLayout(str, this, REF_FRC).advance
