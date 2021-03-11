package com.loadingbyte.cinecred.common

import com.formdev.flatlaf.util.Graphics2DProxy
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextDrawer
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2DFontTextForcedDrawer
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.text.AttributedCharacterIterator
import java.text.AttributedCharacterIterator.Attribute
import java.text.CharacterIterator
import java.util.*
import kotlin.math.max


class DeferredImage(var width: Float = 0f, var height: Float = 0f) {

    private val instructions = HashMap<Layer, MutableList<Instruction>>()

    private fun addInstruction(layer: Layer, insn: Instruction) {
        instructions.getOrPut(layer) { ArrayList() }.add(insn)
    }

    fun drawDeferredImage(image: DeferredImage, x: Float, y: Float, scaling: Float = 1f) {
        width = max(width, x + scaling * image.width)
        height = max(height, y + scaling * image.height)

        for ((layer, insns) in image.instructions.entries)
            for (insn in insns) {
                val newInsn = when (insn) {
                    is Instruction.DrawShapes -> Instruction.DrawShapes(
                        insn.shapes, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling, insn.fill
                    )
                    is Instruction.DrawPicture -> Instruction.DrawPicture(
                        insn.pic.scaled(scaling), x + scaling * insn.x, y + scaling * insn.y
                    )
                    is Instruction.DrawInvisiblePDFStrings -> Instruction.DrawInvisiblePDFStrings(
                        insn.parts, x + scaling * insn.x, y + scaling * insn.baselineY, scaling * insn.scaling
                    )
                }
                addInstruction(layer, newInsn)
            }
    }

    fun drawShape(
        color: Color, shape: Shape, x: Float = 0f, y: Float = 0f, scaling: Float = 1f, fill: Boolean = false,
        layer: Layer = FOREGROUND
    ) {
        if (!layer.isHelperLayer) {
            val bounds = shape.bounds2D
            width = max(width, x + scaling * bounds.maxX.toFloat())
            height = max(height, y + scaling * bounds.maxY.toFloat())
        }
        addInstruction(layer, Instruction.DrawShapes(listOf(Pair(shape, color)), x, y, scaling, fill))
    }

    fun drawLine(
        color: Color, x1: Float, y1: Float, x2: Float, y2: Float, fill: Boolean = false, layer: Layer = FOREGROUND
    ) {
        drawShape(color, Line2D.Float(x1, y1, x2, y2), fill = fill, layer = layer)
    }

    fun drawRect(
        color: Color, x: Float, y: Float, width: Float, height: Float, fill: Boolean = false, layer: Layer = FOREGROUND
    ) {
        drawShape(color, Rectangle2D.Float(x, y, width, height), fill = fill, layer = layer)
    }

    /**
     * The [y] coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the largest font.
     * If [justificationWidth] is provided, the string is fully justified to fit that exact width.
     *
     * @throws IllegalArgumentException If [attrCharIter] is not equipped with both an [TextAttribute.FONT] and a
     *     [TextAttribute.FOREGROUND] at every character.
     */
    fun drawString(
        attrCharIter: AttributedCharacterIterator, x: Float, y: Float, justificationWidth: Float = Float.NaN,
        scaling: Float = 1f, layer: Layer = FOREGROUND
    ) {
        // Find the height of the tallest font in the attributed string, and find the distance between y and that
        // font's baseline. In case there are multiple tallest fonts, take the largest distance.
        var maxFontHeight = 0
        var aboveBaseline = 0f
        attrCharIter.forEachRunOf(TextAttribute.FONT) {
            val font = attrCharIter.getAttribute(TextAttribute.FONT) as Font? ?: throw IllegalArgumentException()
            val normFontMetrics = REF_G2.getFontMetrics(font.deriveFont(FONT_NORMALIZATION_ATTRS))
            if (normFontMetrics.height > maxFontHeight) {
                maxFontHeight = normFontMetrics.height
                aboveBaseline = max(aboveBaseline, normFontMetrics.ascent + normFontMetrics.leading / 2f)
            }
        }
        // The drawing instruction requires a y coordinate that points to the baseline of the string, so
        // compute that now.
        val baselineY = y + aboveBaseline

        // Layout the text.
        var textLayout = TextLayout(attrCharIter, REF_FRC)
        // Fully justify the text layout if requested.
        if (!justificationWidth.isNaN())
            textLayout = textLayout.getJustifiedLayout(justificationWidth)

        // If the layer isn't a helper layer, adjust the DeferredImage's width and height to accommodate for the string.
        if (!layer.isHelperLayer) {
            width = max(width, x + scaling * textLayout.advance)
            height = max(height, y + scaling * maxFontHeight)
        }

        // We render the text by first converting the string to a path via the TextLayout and then
        // later filling that path. This has the following vital advantages:
        //   - Native text rendering via TextLayout.draw(), which internally eventually calls
        //     Graphics2D.drawGlyphVector(), typically ensures that each glyph is aligned at pixel
        //     boundaries. To achieve this, glyphs are slightly shifted to the left or right. This
        //     leads to inconsistent glyph spacing, which is acceptable for desktop purposes in
        //     exchange for higher readability, but not acceptable in a movie context. By converting
        //     the text layout to a path and then filling that path, we avoid calling the native text
        //     renderer and instead call the regular vector graphics renderer, which renders the glyphs
        //     at the exact positions where the text layouter has put them, without applying the
        //     counterproductive glyph shifting.
        //   - Vector-based means of imaging like SVG exactly match the raster-based means.
        // For these advantages, we put up with the following disadvantages:
        //   - Rendering this way is slower than natively rendering text via TextLayout.draw().
        //   - Since the glyphs are no longer aligned at pixel boundaries, heavier antialiasing kicks
        //     in, leading to the rendered text sometimes appearing more blurry. However, this is an
        //     inherent disadvantage of rendering text with perfect glyph spacing and is typically
        //     acceptable in a movie context.
        val fill = mutableListOf<Pair<Shape, Color>>()
        // Start with the background fillings for each run with non-null background color.
        attrCharIter.forEachRunOf(TextAttribute.BACKGROUND) { runEndIdx ->
            val bg = attrCharIter.getAttribute(TextAttribute.BACKGROUND) as Color?
            if (bg != null) {
                val highlightShape = textLayout.getLogicalHighlightShape(attrCharIter.index, runEndIdx)
                fill.add(Pair(highlightShape, bg))
            }
        }
        // Then lay the foreground outline fillings for each run of different foreground color on top of that.
        attrCharIter.forEachRunOf(TextAttribute.FOREGROUND) { runEndIdx ->
            val fg = attrCharIter.getAttribute(TextAttribute.FOREGROUND) as Color? ?: throw IllegalArgumentException()
            val outline = textLayout.getOutline(attrCharIter.index, runEndIdx)
            fill.add(Pair(outline, fg))
        }

        // Finally, add a drawing instruction using all prepared information.
        addInstruction(layer, Instruction.DrawShapes(fill, x, baselineY, scaling, fill = true))

        // When drawing to a PDF, we additionally want to draw some invisible strings at the places where the visible,
        // vectorized strings already lie. Even though this is nowhere near accurate, it enables text copying in PDFs.
        // We now collect information in order to create an instruction for this purpose.
        val invisParts = mutableListOf<Instruction.DrawInvisiblePDFStrings.Part>()
        // Extract the string from the character iterator.
        val str = attrCharIter.getString()
        // Handle each run of a different font separately.
        attrCharIter.forEachRunOf(TextAttribute.FONT) { runEndIdx ->
            val font = attrCharIter.getAttribute(TextAttribute.FONT) as Font
            // We make the invisible placeholder default PDF font slightly smaller than the visible font
            // to make sure that the invisible text completely fits into the bounding box of the visible
            // text, even when the visible font is a narrow one. This way, spaces between words are
            // correctly recognized by the PDF reader.
            val invisFontSize = font.size2D * 0.75f
            // We want to draw each word separately at the position of the vectorized version of the word.
            // This way, inaccuracies concerning font family, weight, kerning, etc. don't hurt too much.
            var charIdx = 0
            for (word in str.substring(attrCharIter.index, runEndIdx).split(' '))
                if (word.isNotBlank()) {
                    // Estimate the relative x coordinate where the word starts from the word's first glyph's bounds.
                    val xOffset = textLayout.getBlackBoxBounds(charIdx, charIdx + 1).bounds2D.x.toFloat()
                    // Note: Append a space to all words because without it, some PDF viewers yield text
                    // without any spaces when the user tries to copy it. Note that we also append a space
                    // to the last word of the sentence to make sure that when the user tries to copy multiple
                    // sentences, e.g., multiple lines of text, there are at least spaces between the lines.
                    invisParts.add(Instruction.DrawInvisiblePDFStrings.Part("$word ", xOffset, invisFontSize))
                    charIdx += word.length + 1
                }
        }
        // Finally, add a drawing instruction for the invisible PDF strings.
        addInstruction(layer, Instruction.DrawInvisiblePDFStrings(invisParts, x, baselineY, scaling))
    }

    fun drawPicture(pic: Picture, x: Float, y: Float, layer: Layer = FOREGROUND) {
        if (!layer.isHelperLayer) {
            width = max(width, x + pic.width)
            height = max(height, y + pic.height)
        }
        addInstruction(layer, Instruction.DrawPicture(pic, x, y))
    }

    fun materialize(g2: Graphics2D, layers: List<Layer>) {
        for (layer in layers)
            for (insn in instructions[layer] ?: emptyList())
                materializeInstruction(g2, insn)
    }

    private fun materializeInstruction(g2: Graphics2D, insn: Instruction) {
        when (insn) {
            is Instruction.DrawShapes -> {
                // We first transform the shapes and then draw them without scaling the graphics object.
                // This ensures that the shapes will exhibit the graphics object's stroke width,
                // which is 1 pixel by default.
                val tx = AffineTransform()
                tx.translate(insn.x.toDouble(), insn.y.toDouble())
                tx.scale(insn.scaling.toDouble(), insn.scaling.toDouble())
                for ((shape, color) in insn.shapes) {
                    g2.color = color
                    val transformedShape = tx.createTransformedShape(shape)
                    if (insn.fill)
                        g2.fill(transformedShape)
                    else
                        g2.draw(transformedShape)
                }
            }
            is Instruction.DrawPicture -> when (val pic = insn.pic) {
                is Picture.Raster -> {
                    val tx = AffineTransform()
                    tx.translate(insn.x.toDouble(), insn.y.toDouble())
                    tx.scale(pic.scaling.toDouble(), pic.scaling.toDouble())
                    g2.drawImage(pic.img, tx, null)
                }
                is Picture.SVG -> g2.preserveTransform {
                    g2.translate(insn.x.toDouble(), insn.y.toDouble())
                    g2.scale(pic.scaling.toDouble(), pic.scaling.toDouble())
                    // Batik might not be thread-safe, even though we haven't tested that.
                    synchronized(pic.gvtRoot) {
                        if (pic.isCropped)
                            g2.translate(-pic.gvtRoot.bounds.x, -pic.gvtRoot.bounds.y)
                        pic.gvtRoot.paint(g2)
                    }
                }
                is Picture.PDF -> g2.preserveTransform {
                    g2.translate(insn.x.toDouble(), insn.y.toDouble())
                    if (pic.isCropped)
                        g2.translate(-pic.minBox.x * pic.scaling, -pic.minBox.y * pic.scaling)
                    // PDFBox calls clearRect() before starting to draw. This sometimes results in a black
                    // box even if g2.background is set to a transparent color. The most thorough fix is
                    // to just block all calls to clearRect().
                    val g2Proxy = object : Graphics2DProxy(g2) {
                        override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
                            // Block call.
                        }
                    }
                    // PDFBox is definitely not thread-safe.
                    synchronized(pic.doc) {
                        PDFRenderer(pic.doc).renderPageToGraphics(0, g2Proxy, pic.scaling)
                    }
                }
            }
            is Instruction.DrawInvisiblePDFStrings -> {
                if (g2 is PdfBoxGraphics2D) {
                    // Force the following text to be drawn using any font it can find.
                    g2.setFontTextDrawer(PdfBoxGraphics2DFontTextForcedDrawer())
                    // The invisible text should of course be invisible.
                    g2.color = Color(0, 0, 0, 0)
                    for (part in insn.parts) {
                        // We use a placeholder default PDF font.
                        g2.font = Font("SansSerif", 0, (insn.scaling * part.fontSize).toInt())
                        g2.drawString(part.str, insn.x + insn.scaling * part.unscaledXOffset, insn.baselineY)
                    }
                    // We are done. Future text should again be vectorized, as indicated by
                    // the presence of the default, unconfigured FontTextDrawer.
                    g2.setFontTextDrawer(PdfBoxGraphics2DFontTextDrawer())
                }
            }
        }
    }


    companion object {

        // These three common layers are typically used. Additional layers may be defined by users of this class.
        val FOREGROUND = Layer(isHelperLayer = false)
        val BACKGROUND = Layer(isHelperLayer = false)
        val GUIDES = Layer(isHelperLayer = true)

        private val FONT_NORMALIZATION_ATTRS = mapOf(TextAttribute.SUPERSCRIPT to null)

        private fun CharacterIterator.getString(): String {
            val result = StringBuilder(endIndex - beginIndex)
            index = beginIndex
            var c = first()
            while (c != AttributedCharacterIterator.DONE) {
                result.append(c)
                c = next()
            }
            return result.toString()
        }

        private inline fun AttributedCharacterIterator.forEachRunOf(attr: Attribute, block: (Int) -> Unit) {
            index = beginIndex
            while (index != endIndex) {
                val runEndIdx = getRunLimit(attr)
                block(runEndIdx)
                index = runEndIdx
            }
        }

    }


    class Layer(val isHelperLayer: Boolean)


    private sealed class Instruction {

        class DrawShapes(
            val shapes: List<Pair<Shape, Color>>, val x: Float, val y: Float, val scaling: Float, val fill: Boolean
        ) : Instruction()

        class DrawPicture(
            val pic: Picture, val x: Float, val y: Float
        ) : Instruction()

        class DrawInvisiblePDFStrings(
            val parts: List<Part>, val x: Float, val baselineY: Float, val scaling: Float
        ) : Instruction() {
            class Part(val str: String, val unscaledXOffset: Float, val fontSize: Float)
        }

    }

}
