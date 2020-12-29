package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.FontSpec
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.font.TextLayout
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max


// This is a reference graphics context used to measure the size of fonts.
val REF_G2: Graphics2D = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB).createGraphics()


class RichFont(val spec: FontSpec, val awt: Font) {
    val metrics: FontMetrics = REF_G2.getFontMetrics(awt)
}


class DeferredImage {

    var width = 0f; private set
    var height = 0f; private set

    private val instructions = mutableListOf<Instruction>()

    val intWidth get() = ceil(width).toInt()
    val intHeight get() = ceil(height).toInt()

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
        width = max(width, x + scaling * font.metrics.stringWidth(str))
        height = max(height, y + scaling * font.spec.run { heightPx + extraLineSpacingPx })
        instructions.add(Instruction.DrawString(font, str, x, y, scaling, isGuide))
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

    }


    enum class TextMode { TEXT, PATH, PATH_WITH_INVISIBLE_TEXT }

    fun materialize(g2: Graphics2D, drawGuides: Boolean, textMode: TextMode) {
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
                    val y = insn.y + insn.scaling * (insn.font.spec.extraLineSpacingPx / 2f + insn.font.metrics.ascent)

                    if (textMode == TextMode.TEXT) {
                        g2.font = scaledFont
                        g2.drawString(insn.str, insn.x, y)
                    } else {
                        // If requested, draw the font as a path so that vector-based means of imaging like SVG can
                        // exactly match the pixel-based means like PNG export. Note that we do not use GlyphVector
                        // here because that has issues with kerning.
                        val textLayout = TextLayout(insn.str, scaledFont, g2.fontRenderContext)
                        textLayout.draw(g2, insn.x, y)

                        // If requested, additionally draw some invisible strings where the visible, "pathified"
                        // strings already lie. Even though this is nowhere near accurate, it's especially useful to
                        // enable text copying in PDFs.
                        if (textMode == TextMode.PATH_WITH_INVISIBLE_TEXT) {
                            g2.font = scaledFont
                            g2.color = Color(0, 0, 0, 0)
                            var charIdx = 0
                            for (word in insn.str.split(' ')) {
                                // The PDF library is buggy and may directly interpret the inputted string as a control
                                // sequence. To circumvent this, we need to escape parentheses. We might actually need
                                // to escape even more characters, but we aren't aware of this yet.
                                val escapedWord = word.replace("(", "\\(").replace(")", "\\)")
                                // Estimate the x coordinate where the word starts from the word's first glyph's bounds.
                                val xOffset = textLayout.getBlackBoxBounds(charIdx, charIdx + 1).bounds2D.x.toFloat()
                                g2.drawString(escapedWord, insn.x + xOffset, y)
                                charIdx += word.length + 1
                            }
                        }
                    }
                }
            }
        }
    }
}
