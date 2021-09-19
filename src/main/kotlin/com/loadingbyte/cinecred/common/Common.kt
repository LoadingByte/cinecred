package com.loadingbyte.cinecred.common

import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.pdfbox.util.Matrix
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.RenderingHints.*
import java.awt.font.FontRenderContext
import java.awt.font.LineMetrics
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.text.MessageFormat
import java.util.*


val LOGGER: Logger = LoggerFactory.getLogger("Cinecred")


enum class Severity { INFO, WARN, ERROR }


val TRANSLATED_LOCALES: List<Locale> = listOf(Locale.ENGLISH, Locale.GERMAN)
val FALLBACK_TRANSLATED_LOCALE: Locale = Locale.ENGLISH

private val BUNDLE_CONTROL = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)
private fun getL10nBundle(locale: Locale) = ResourceBundle.getBundle("l10n.strings", locale, BUNDLE_CONTROL)

fun l10n(key: String, locale: Locale = Locale.getDefault()): String = getL10nBundle(locale).getString(key)
fun l10n(key: String, vararg args: Any?, locale: Locale = Locale.getDefault()): String =
    MessageFormat.format(l10n(key, locale), *args)

private val l10nAllCache = HashMap<String, List<String>>()
fun l10nAll(key: String): List<String> = l10nAllCache.getOrPut(key) {
    TRANSLATED_LOCALES.map { locale -> getL10nBundle(locale).getString(key) }
}


val gCfg: GraphicsConfiguration
    get() = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

// This is a reference font render context used to measure the size of fonts.
val REF_FRC: FontRenderContext = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    .createGraphics()
    .apply { setHighQuality() }
    .fontRenderContext


fun AffineTransform.translate(tx: Float, ty: Float) = translate(tx.toDouble(), ty.toDouble())
fun AffineTransform.scale(sx: Float, sy: Float) = scale(sx.toDouble(), sy.toDouble())
fun AffineTransform.scale(s: Float) = scale(s.toDouble(), s.toDouble())
fun AffineTransform.shear(shx: Float, shy: Float) = shear(shx.toDouble(), shy.toDouble())
fun Graphics2D.translate(tx: Float, ty: Float) = translate(tx.toDouble(), ty.toDouble())
fun Graphics2D.scale(s: Float) = scale(s.toDouble(), s.toDouble())
fun Matrix.translate(tx: Double, ty: Double) = translate(tx.toFloat(), ty.toFloat())
fun Matrix.scale(s: Float) = scale(s, s)


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


val Font.lineMetrics: LineMetrics
    get() = getLineMetrics("Beispiel!", REF_FRC)
