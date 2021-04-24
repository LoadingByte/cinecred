package com.loadingbyte.cinecred.common

import org.apache.batik.ext.awt.image.GraphicsUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints.*
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.text.MessageFormat
import java.util.*


enum class Severity { INFO, WARN, ERROR }


fun l10nBundle(locale: Locale): ResourceBundle = ResourceBundle.getBundle("l10n.strings", locale)

private val DEFAULT_BUNDLE = l10nBundle(Locale.getDefault())
fun l10n(key: String): String = DEFAULT_BUNDLE.getString(key)
fun l10n(key: String, vararg args: Any?): String = MessageFormat.format(l10n(key), *args)

val SUPPORTED_LOCALES = mapOf(
    Locale.ROOT to Locale.ENGLISH.displayLanguage,
    Locale.GERMAN to Locale.GERMAN.displayLanguage
)
private val BUNDLES = SUPPORTED_LOCALES.keys.map { locale -> l10nBundle(locale) }
private val l10nAllCache = mutableMapOf<String, List<String>>()
fun l10nAll(key: String): List<String> = l10nAllCache.getOrPut(key) {
    BUNDLES.map { bundle -> bundle.getString(key) }
}


val gCfg: GraphicsConfiguration
    get() = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

// This is a reference graphics context used to measure the size of fonts.
val REF_G2: Graphics2D = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    .createGraphics()
    .apply { setHighQuality() }
val REF_FRC: FontRenderContext = REF_G2.fontRenderContext


inline fun Graphics.withNewG2(block: (Graphics2D) -> Unit) {
    val g2 = create() as Graphics2D
    try {
        block(g2)
    } finally {
        g2.dispose()
    }
}


inline fun Graphics2D.preserveTransform(block: () -> Unit) {
    val prevTransform = transform  // creates a defensive copy
    block()
    transform = prevTransform
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
    setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY)
    setRenderingHint(KEY_DITHERING, VALUE_DITHER_DISABLE)
    setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(KEY_ALPHA_INTERPOLATION, VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(KEY_COLOR_RENDERING, VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON)
    // Note that we not only activate fractional font metrics because the result obviously looks better, but also
    // because if we don't, the getStringWidth() method sometimes yields incorrect results.
    setRenderingHint(KEY_FRACTIONALMETRICS, VALUE_FRACTIONALMETRICS_ON)
}
