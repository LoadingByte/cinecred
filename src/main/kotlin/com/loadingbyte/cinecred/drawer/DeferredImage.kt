package com.loadingbyte.cinecred.drawer

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextDrawer
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextForcedDrawer
import org.apache.batik.gvt.GraphicsNode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
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

    fun drawDeferredImage(image: DeferredImage, x: Float, y: Float, scaling: Float) {
        width = max(width, scaling * image.width)
        height = max(height, scaling * image.height)

        for (insn in image.instructions)
            when (insn) {
                is Instruction.DrawLine ->
                    drawLine(
                        insn.color, x + scaling * insn.x1, y + scaling * insn.y1, x + scaling * insn.x2,
                        y + scaling * insn.y2, insn.isGuide
                    )
                is Instruction.DrawRect ->
                    drawRect(
                        insn.color, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.width,
                        scaling * insn.height, insn.isGuide
                    )
                is Instruction.DrawString ->
                    drawString(
                        insn.font, insn.str, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling,
                        insn.isGuide
                    )
                is Instruction.DrawBufferedImage ->
                    drawBufferedImage(
                        insn.img, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling, insn.isGuide
                    )
                is Instruction.DrawSVGNode ->
                    drawSVGNode(
                        insn.node, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling, insn.isGuide
                    )
                is Instruction.DrawPDFPage ->
                    drawPDFPage(
                        insn.doc, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling, insn.isGuide
                    )
            }
    }

    fun drawLine(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, isGuide: Boolean = false) {
        width = max(width, max(x1, x2))
        height = max(height, max(y1, y2))
        instructions.add(Instruction.DrawLine(color, x1, y1, x2, y2, isGuide))
    }

    fun drawRect(color: Color, x: Float, y: Float, width: Float, height: Float, isGuide: Boolean = false) {
        this.width = max(this.width, x + width)
        this.height = max(this.height, y + height)
        instructions.add(Instruction.DrawRect(color, x, y, width, height, isGuide))
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

    fun drawBufferedImage(img: BufferedImage, x: Float, y: Float, scaling: Float = 1f, isGuide: Boolean = false) {
        width = max(width, x + scaling * img.width)
        height = max(height, y + scaling * img.height)
        instructions.add(Instruction.DrawBufferedImage(img, x, y, scaling, isGuide))
    }

    fun drawSVGNode(node: GraphicsNode, x: Float, y: Float, scaling: Float = 1f, isGuide: Boolean = false) {
        width = max(width, x + scaling * node.bounds.width.toFloat())
        height = max(height, y + scaling * node.bounds.height.toFloat())
        instructions.add(Instruction.DrawSVGNode(node, x, y, scaling, isGuide))
    }

    fun drawPDFPage(doc: PDDocument, x: Float, y: Float, scaling: Float = 1f, isGuide: Boolean = false) {
        val cropBox = doc.pages[0].cropBox
        width = max(width, x + scaling * cropBox.width)
        height = max(height, y + scaling * cropBox.width)
        instructions.add(Instruction.DrawPDFPage(doc, x, y, scaling, isGuide))
    }


    private sealed class Instruction(val isGuide: Boolean) {

        class DrawLine(
            val color: Color, val x1: Float, val y1: Float, val x2: Float, val y2: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawRect(
            val color: Color, val x: Float, val y: Float, val width: Float, val height: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawString(
            val font: RichFont, val str: String, val x: Float, val y: Float, val scaling: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawBufferedImage(
            val img: BufferedImage, val x: Float, val y: Float, val scaling: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawSVGNode(
            val node: GraphicsNode, val x: Float, val y: Float, val scaling: Float, isGuide: Boolean
        ) : Instruction(isGuide)

        class DrawPDFPage(
            val doc: PDDocument, val x: Float, val y: Float, val scaling: Float, isGuide: Boolean
        ) : Instruction(isGuide)

    }


    fun materialize(g2: Graphics2D, drawGuides: Boolean = false) {
        for (insn in instructions) {
            if (!drawGuides && insn.isGuide)
                continue

            when (insn) {
                is Instruction.DrawLine -> {
                    g2.color = insn.color
                    g2.draw(Line2D.Float(insn.x1, insn.y1, insn.x2, insn.y2))
                }
                is Instruction.DrawRect -> {
                    g2.color = insn.color
                    g2.draw(Rectangle2D.Float(insn.x, insn.y, insn.width, insn.height))
                }
                is Instruction.DrawString -> {
                    g2.color = insn.font.spec.color

                    val scaledFont = insn.font.awt.deriveFont(insn.font.awt.size2D * insn.scaling)
                    val y = insn.y + insn.scaling * insn.font.metrics.ascent

                    // Draw the font as a path so that vector-based means of imaging like SVG can exactly match the
                    // pixel-based means like PNG export. Note that we do not use GlyphVector here because that has
                    // issues with kerning.
                    val textLayout = TextLayout(insn.str, scaledFont, g2.fontRenderContext)
                    textLayout.draw(g2, insn.x, y)

                    // When drawing to a PDF, additionally draw some invisible strings where the visible, vectorized
                    // strings already lie. Even though this is nowhere near accurate, it enables text copying in PDFs.
                    if (g2 is PdfBoxGraphics2D) {
                        // Force the following text to be drawn using any font it can find.
                        g2.setFontTextDrawer(PdfBoxGraphics2DFontTextForcedDrawer())
                        // We use a placeholder default PDF font. We make it slightly smaller than the visible font
                        // to make sure that the invisible text completely fits into the bounding box of the visible
                        // text, even when the visible font is a narrow one. This way, spaces between words are
                        // correctly recognized by the PDF reader.
                        g2.font = Font("SansSerif", 0, (scaledFont.size2D * 0.75f).toInt())
                        // The invisible text should of course be invisible.
                        g2.color = Color(0, 0, 0, 0)
                        // Draw each word separately at the position of the vectorized version of the word.
                        // This way, inaccuracies concerning font family, weight, kerning, etc. don't hurt too much.
                        var charIdx = 0
                        for (word in insn.str.split(' ')) {
                            // Estimate the x coordinate where the word starts from the word's first glyph's bounds.
                            val xOffset = textLayout.getBlackBoxBounds(charIdx, charIdx + 1).bounds2D.x.toFloat()
                            g2.drawString(word, insn.x + xOffset, y)
                            charIdx += word.length + 1
                        }
                        // We are done. Future text should again be vectorized, as indicated by
                        // the presence of the default, unconfigured FontTextDrawer.
                        g2.setFontTextDrawer(PdfBoxGraphics2DFontTextDrawer())
                    }
                }
                is Instruction.DrawBufferedImage -> {
                    val tx = AffineTransform().apply {
                        translate(insn.x.toDouble(), insn.y.toDouble())
                        scale(insn.scaling.toDouble(), insn.scaling.toDouble())
                    }
                    g2.drawImage(insn.img, tx, null)
                }
                is Instruction.DrawSVGNode -> {
                    val tx = AffineTransform().apply {
                        translate(insn.x.toDouble(), insn.y.toDouble())
                        scale(insn.scaling.toDouble(), insn.scaling.toDouble())
                    }
                    val prevTransform = g2.transform
                    g2.transform(tx)
                    insn.node.paint(g2)
                    g2.transform = prevTransform
                }
                is Instruction.DrawPDFPage -> {
                    // We first render to an image and then render that image to the target graphics because direct
                    // rendering with PDFBox is known to sometimes have issues.
                    val img = PDFRenderer(insn.doc).renderImage(0, insn.scaling, ImageType.ARGB)
                    val tx = AffineTransform.getTranslateInstance(insn.x.toDouble(), insn.y.toDouble())
                    g2.drawImage(img, tx, null)
                }
            }
        }
    }

}
