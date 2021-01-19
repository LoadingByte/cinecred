package com.loadingbyte.cinecred.common

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextDrawer
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextForcedDrawer
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.*
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import kotlin.math.ceil
import kotlin.math.max


class DeferredImage {

    var width = 0f; private set
    var height = 0f; private set

    private val instructions = mutableListOf<Instruction>()

    fun setMinWidth(minWidth: Float) {
        width = max(width, minWidth)
    }

    fun setMinHeight(minHeight: Float) {
        height = max(height, minHeight)
    }

    fun drawDeferredImage(image: DeferredImage, x: Float, y: Float, scaling: Float = 1f) {
        width = max(width, scaling * image.width)
        height = max(height, scaling * image.height)

        for (insn in image.instructions)
            when (insn) {
                is Instruction.DrawShape ->
                    drawShape(
                        insn.color, insn.shape, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling,
                        insn.fill, insn.isGuide
                    )
                is Instruction.DrawString ->
                    drawString(
                        insn.font, insn.str, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling,
                        insn.isGuide
                    )
                is Instruction.DrawPicture ->
                    drawPicture(
                        insn.pic.scaled(scaling), x + scaling * insn.x, y + scaling * insn.y, insn.isGuide
                    )
            }
    }

    fun drawShape(
        color: Color, shape: Shape, x: Float = 0f, y: Float = 0f, scaling: Float = 1f, fill: Boolean = false,
        isGuide: Boolean = false
    ) {
        val bounds = shape.bounds2D
        width = max(width, x + scaling * bounds.maxX.toFloat())
        height = max(height, y + scaling * bounds.maxY.toFloat())
        instructions.add(Instruction.DrawShape(color, shape, x, y, scaling, fill, isGuide))
    }

    fun drawLine(
        color: Color, x1: Float, y1: Float, x2: Float, y2: Float, fill: Boolean = false, isGuide: Boolean = false
    ) {
        drawShape(color, Line2D.Float(x1, y1, x2, y2), fill = fill, isGuide = isGuide)
    }

    fun drawRect(
        color: Color, x: Float, y: Float, width: Float, height: Float, fill: Boolean = false, isGuide: Boolean = false
    ) {
        drawShape(color, Rectangle2D.Float(x, y, width, height), fill = fill, isGuide = isGuide)
    }

    /**
     * The y coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the font.
     */
    fun drawString(font: RichFont, str: String, x: Float, y: Float, scaling: Float = 1f, isGuide: Boolean = false) {
        width = max(width, x + scaling * font.awt.getStringWidth(str))
        height = max(height, y + scaling * font.spec.heightPx)
        instructions.add(Instruction.DrawString(font, str, x, y, scaling, isGuide))
    }

    fun drawPicture(pic: Picture, x: Float, y: Float, isGuide: Boolean = false) {
        width = max(width, x + pic.width)
        height = max(height, y + pic.height)
        instructions.add(Instruction.DrawPicture(pic, x, y, isGuide))
    }


    private sealed class Instruction(val isGuide: Boolean) {

        class DrawShape(
            val color: Color, val shape: Shape, val x: Float, val y: Float, val scaling: Float, val fill: Boolean,
            isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawString(
            val font: RichFont, val str: String, val x: Float, val y: Float, val scaling: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawPicture(
            val pic: Picture, val x: Float, val y: Float, isGuide: Boolean
        ) : Instruction(isGuide)

    }


    /**
     * The [guideStrokeWidth] is only relevant for guides drawn with [drawShape] or its derivative methods.
     */
    fun materialize(g2: Graphics2D, drawGuides: Boolean = false, guideStrokeWidth: Float = 1f) {
        for (insn in instructions) {
            if (!drawGuides && insn.isGuide)
                continue

            when (insn) {
                is Instruction.DrawShape -> {
                    val prevTransform = g2.transform
                    val prevStroke = g2.stroke
                    g2.color = insn.color
                    if (insn.isGuide)
                        g2.stroke = BasicStroke(guideStrokeWidth)
                    g2.translate(insn.x.toDouble(), insn.y.toDouble())
                    g2.scale(insn.scaling.toDouble(), insn.scaling.toDouble())
                    if (insn.fill)
                        g2.fill(insn.shape)
                    else
                        g2.draw(insn.shape)
                    g2.transform = prevTransform
                    g2.stroke = prevStroke
                }
                is Instruction.DrawString -> {
                    g2.color = insn.font.spec.color

                    // Text rendering is done by first scaling up the font by a factor of "hackScaling" (which ensures
                    // its size is at least 300pt), then converting the string to a path via TextLayout, and finally
                    // drawing the path scaled down by a factor of "hackScaling". This has two main advantages:
                    //   - Using a much larger font for the text layout process avoids irregular letter spacing issues
                    //     when working with very small fonts. These issues would otherwise appear depending on the JVM
                    //     and OS version.
                    //   - Vector-based means of imaging like SVG exactly match the pixel-based means like PNG export.
                    // Note that we do not use GlyphVector here because that has serious issues with kerning.
                    val scaledFontSize = insn.font.awt.size2D * insn.scaling
                    val hackScaling = ceil(300f / scaledFontSize)
                    val hackScaledFont = insn.font.awt.deriveFont(scaledFontSize * hackScaling)
                    val hackScaledTextLayout = TextLayout(insn.str, hackScaledFont, g2.fontRenderContext)
                    val baselineY = insn.y + hackScaledTextLayout.ascent / hackScaling

                    val prevTransform = g2.transform
                    g2.translate(insn.x.toDouble(), baselineY.toDouble())
                    g2.scale(1.0 / hackScaling, 1.0 / hackScaling)
                    hackScaledTextLayout.draw(g2, 0f, 0f)
                    g2.transform = prevTransform

                    // When drawing to a PDF, additionally draw some invisible strings where the visible, vectorized
                    // strings already lie. Even though this is nowhere near accurate, it enables text copying in PDFs.
                    if (g2 is PdfBoxGraphics2D) {
                        val scaledFont = insn.font.awt.deriveFont(scaledFontSize)
                        val scaledTextLayout = TextLayout(insn.str, scaledFont, g2.fontRenderContext)
                        // Force the following text to be drawn using any font it can find.
                        g2.setFontTextDrawer(PdfBoxGraphics2DFontTextForcedDrawer())
                        // We use a placeholder default PDF font. We make it slightly smaller than the visible font
                        // to make sure that the invisible text completely fits into the bounding box of the visible
                        // text, even when the visible font is a narrow one. This way, spaces between words are
                        // correctly recognized by the PDF reader.
                        g2.font = Font("SansSerif", 0, (scaledFontSize * 0.75f).toInt())
                        // The invisible text should of course be invisible.
                        g2.color = Color(0, 0, 0, 0)
                        // Draw each word separately at the position of the vectorized version of the word.
                        // This way, inaccuracies concerning font family, weight, kerning, etc. don't hurt too much.
                        var charIdx = 0
                        for (word in insn.str.split(' ')) {
                            // Estimate the x coordinate where the word starts from the word's first glyph's bounds.
                            val xOffset = scaledTextLayout.getBlackBoxBounds(charIdx, charIdx + 1).bounds2D.x.toFloat()
                            // Note: Append a space to all words because without it, some PDF viewers give text
                            // without any spaces when the user tries to copy it. Note that we also append a space
                            // to the last word of the sentence to make sure that when the user tries to copy multiple
                            // sentences, e.g., multiple lines of text, there are at least spaces between the lines.
                            g2.drawString("$word ", insn.x + xOffset, baselineY)
                            charIdx += word.length + 1
                        }
                        // We are done. Future text should again be vectorized, as indicated by
                        // the presence of the default, unconfigured FontTextDrawer.
                        g2.setFontTextDrawer(PdfBoxGraphics2DFontTextDrawer())
                    }
                }
                is Instruction.DrawPicture -> when (val pic = insn.pic) {
                    is Picture.Raster -> {
                        val tx = AffineTransform()
                        tx.translate(insn.x.toDouble(), insn.y.toDouble())
                        tx.scale(pic.scaling.toDouble(), pic.scaling.toDouble())
                        g2.drawImage(pic.img, tx, null)
                    }
                    is Picture.SVG -> {
                        val tx = AffineTransform()
                        tx.translate(insn.x.toDouble(), insn.y.toDouble())
                        tx.scale(pic.scaling.toDouble(), pic.scaling.toDouble())
                        if (pic.isCropped)
                            tx.translate(-pic.gvtRoot.bounds.x, -pic.gvtRoot.bounds.y)
                        val prevTransform = g2.transform
                        g2.transform(tx)
                        pic.gvtRoot.paint(g2)
                        g2.transform = prevTransform
                    }
                    is Picture.PDF -> {
                        // We first render to an image and then render that image to the target graphics because direct
                        // rendering with PDFBox is known to sometimes have issues.
                        val img = PDFRenderer(pic.doc).renderImage(0, pic.scaling, ImageType.ARGB)
                        val tx = AffineTransform.getTranslateInstance(insn.x.toDouble(), insn.y.toDouble())
                        if (pic.isCropped)
                            tx.translate(-pic.minBox.x * pic.scaling, -pic.minBox.y * pic.scaling)
                        g2.drawImage(img, tx, null)
                    }
                }
            }
        }
    }

}
