package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.FontSpec
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.font.TextLayout
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
                is Instruction.DrawString ->
                    drawString(insn.font, insn.str, x + scaling * insn.x, y + scaling * insn.y, scaling * insn.scaling)
            }
    }

    /**
     * The y coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the font.
     */
    fun drawString(font: RichFont, str: String, x: Float, y: Float, scaling: Float) {
        width = max(width, x + scaling * font.metrics.stringWidth(str))
        height = max(height, y + scaling * font.spec.run { heightPx + extraLineSpacingPx })
        instructions.add(Instruction.DrawString(font, str, x, y, scaling))
    }

    private sealed class Instruction {
        class DrawString(val font: RichFont, val str: String, val x: Float, val y: Float, val scaling: Float) :
            Instruction()
    }


    enum class TextMode { TEXT, PATH, PATH_WITH_INVISIBLE_TEXT }

    fun materialize(g2: Graphics2D, textMode: TextMode) {
        for (insn in instructions)
            when (insn) {
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
