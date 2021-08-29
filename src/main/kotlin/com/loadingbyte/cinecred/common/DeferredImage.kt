package com.loadingbyte.cinecred.common

import com.formdev.flatlaf.util.Graphics2DProxy
import com.loadingbyte.cinecred.common.Y.Companion.toY
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.fontbox.ttf.OTFParser
import org.apache.fontbox.ttf.OpenTypeFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeCollection
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.io.DataInputStream
import java.util.*
import kotlin.math.max


class DeferredImage(var width: Float = 0f, var height: Y = 0f.toY()) {

    private val instructions = HashMap<Layer, MutableList<Instruction>>()

    private fun addInstruction(layer: Layer, insn: Instruction) {
        instructions.getOrPut(layer) { ArrayList() }.add(insn)
    }

    fun copy(universeScaling: Float = 1f, elasticScaling: Float = 1f): DeferredImage {
        val copy = DeferredImage(
            width = width * universeScaling,
            height = (height * universeScaling).scaleElastic(elasticScaling)
        )
        copy.drawDeferredImage(this, universeScaling = universeScaling, elasticScaling = elasticScaling)
        return copy
    }

    fun drawDeferredImage(
        image: DeferredImage, x: Float = 0f, y: Y = 0f.toY(), universeScaling: Float = 1f, elasticScaling: Float = 1f
    ) {
        for (layer in image.instructions.keys) {
            val insn = Instruction.DrawDeferredImageLayer(x, y, universeScaling, elasticScaling, image, layer)
            addInstruction(layer, insn)
        }
    }

    fun drawShape(
        color: Color, shape: Shape, x: Float, y: Y, fill: Boolean = false, layer: Layer = FOREGROUND
    ) {
        addInstruction(layer, Instruction.DrawShape(x, y, shape, color, fill))
    }

    fun drawLine(
        color: Color, x1: Float, y1: Y, x2: Float, y2: Y, fill: Boolean = false, layer: Layer = FOREGROUND
    ) {
        addInstruction(layer, Instruction.DrawLine(x1, y1, x2, y2, color, fill))
    }

    fun drawRect(
        color: Color, x: Float, y: Y, width: Float, height: Y, fill: Boolean = false,
        layer: Layer = FOREGROUND
    ) {
        addInstruction(layer, Instruction.DrawRect(x, y, width, height, color, fill))
    }

    /**
     * The [y] coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the largest font.
     * If [justificationWidth] is provided, the string is fully justified to fit that exact width.
     *
     * @throws IllegalArgumentException If [fmtStr] is not equipped with both a font and a foreground color at
     *     every character.
     */
    fun drawString(
        fmtStr: FormattedString, x: Float, y: Y, justificationWidth: Float = Float.NaN,
        foregroundLayer: Layer = FOREGROUND, backgroundLayer: Layer = BACKGROUND
    ) {
        // Find the distance between y and the baseline of the tallest font in the attributed string.
        // In case there are multiple tallest fonts, take the largest distance.
        var maxFontHeight = 0f
        var aboveBaseline = 0f
        fmtStr.forEachFontRun { font, runStartIdx, runEndIdx ->
            font ?: throw IllegalArgumentException()
            val normFontLM = font.deriveFont(FONT_NORMALIZATION_ATTRS)
                .getLineMetrics(fmtStr.string.substring(runStartIdx, runEndIdx), REF_FRC)
            if (normFontLM.height >= maxFontHeight) {
                maxFontHeight = normFontLM.height
                aboveBaseline = max(aboveBaseline, normFontLM.ascent + normFontLM.leading / 2f)
            }
        }
        // The drawing instruction requires a y coordinate that points to the baseline of the string, so
        // compute that now.
        val baselineY = y + aboveBaseline

        // Layout the text. Utilize a character iterator with both foreground and background information for this.
        // Changes in foreground split the TextLayout's TextLineComponents, and these splits are important later on
        // for extracting the outline shape of each section with different foreground. By also supplying the background
        // to the TextLayout, we can later use getLogicalHighlightShape() to find background rectangles.
        var textLayout = fmtStr.fontFgBgTextLayout
        // Fully justify the text layout if requested.
        if (!justificationWidth.isNaN())
            textLayout = textLayout.getJustifiedLayout(justificationWidth)

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
        // Start with the background fillings for each run with non-null background.
        fmtStr.forEachBackgroundRun { bg, runStartIdx, runEndIdx ->
            if (bg != null) {
                val tlBounds = textLayout.bounds
                val bgBounds = Rectangle2D.Double(
                    tlBounds.x - bg.widenLeft,
                    tlBounds.y - bg.widenTop,
                    tlBounds.width + bg.widenLeft + bg.widenRight,
                    tlBounds.height + bg.widenTop + bg.widenBottom
                )
                val highlightShape = textLayout.getLogicalHighlightShape(runStartIdx, runEndIdx, bgBounds)
                addInstruction(backgroundLayer, Instruction.DrawShape(x, baselineY, highlightShape, bg.color, true))
            }
        }
        // Then collect the foreground outlines for each run of different foreground color and add a
        // foreground drawing instruction enriched with these outlines.
        // Since the TextLayout's TextLineComponents are split where the foreground color changes, we can easily extract
        // partial outlines for each section of different foreground color by using our custom getOutline(...) function.
        val outlines = mutableListOf<Pair<Shape, Color>>()
        fmtStr.forEachForegroundRun { fg, runStartIdx, runEndIdx ->
            fg ?: throw IllegalArgumentException()
            val outline = textLayout.getOutline(runStartIdx, runEndIdx)
            outlines.add(Pair(outline, fg))
        }
        addInstruction(foregroundLayer, Instruction.DrawStringForeground(x, baselineY, fmtStr, textLayout, outlines))
    }

    fun drawPicture(pic: Picture, x: Float, y: Y, layer: Layer = FOREGROUND) {
        addInstruction(layer, Instruction.DrawPicture(x, y, pic))
    }

    fun materialize(g2: Graphics2D, layers: List<Layer>) {
        materializeDeferredImage(Graphics2DBackend(g2), 0f, 0f, 1f, 1f, this, layers)
    }

    fun materialize(doc: PDDocument, cs: PDPageContentStream, csHeight: Float, layers: List<Layer>) {
        materializeDeferredImage(PDFBackend(doc, cs, csHeight), 0f, 0f, 1f, 1f, this, layers)
    }

    private fun materializeDeferredImage(
        backend: MaterializationBackend, x: Float, y: Float, universeScaling: Float, elasticScaling: Float,
        image: DeferredImage, layers: List<Layer>
    ) {
        for (layer in layers)
            for (insn in image.instructions.getOrDefault(layer, emptyList()))
                materializeInstruction(backend, x, y, universeScaling, elasticScaling, insn)
    }

    private fun materializeInstruction(
        backend: MaterializationBackend, x: Float, y: Float, universeScaling: Float, elasticScaling: Float,
        insn: Instruction
    ) {
        when (insn) {
            is Instruction.DrawDeferredImageLayer -> materializeDeferredImage(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling * insn.universeScaling, elasticScaling * insn.elasticScaling, insn.image,
                listOf(insn.layer)
            )
            is Instruction.DrawShape -> materializeShape(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling, insn.shape, insn.color, insn.fill
            )
            is Instruction.DrawLine -> materializeShape(
                backend, x, y, universeScaling,
                Line2D.Float(insn.x1, insn.y1.resolve(elasticScaling), insn.x2, insn.y2.resolve(elasticScaling)),
                insn.color, insn.fill
            )
            is Instruction.DrawRect -> materializeShape(
                backend, x, y, universeScaling,
                Rectangle2D.Float(
                    insn.x, insn.y.resolve(elasticScaling), insn.width, insn.height.resolve(elasticScaling)
                ), insn.color, insn.fill
            )
            is Instruction.DrawStringForeground -> backend.materializeStringForeground(
                x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling), universeScaling,
                insn.fmtStr, insn.textLayout, insn.outlines
            )
            is Instruction.DrawPicture -> backend.materializePicture(
                x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                insn.pic.scaled(universeScaling)
            )
        }
    }

    private fun materializeShape(
        backend: MaterializationBackend, x: Float, y: Float, scaling: Float,
        shape: Shape, color: Color, fill: Boolean
    ) {
        // We first transform the shape and then draw it without scaling the canvas.
        // This ensures that the shape will exhibit the default stroke width, which is usually 1 pixel.
        val tx = AffineTransform().apply { translate(x, y); scale(scaling) }
        backend.materializeShape(tx.createTransformedShape(shape), color, fill)
    }


    companion object {

        // These common layers are typically used. Additional layers may be defined by users of this class.
        val FOREGROUND = Layer()
        val BACKGROUND = Layer()
        val GROUNDING = Layer()
        val GUIDES = Layer()

        private val FONT_NORMALIZATION_ATTRS = mapOf(TextAttribute.SUPERSCRIPT to null)

        private inline fun FloatArray.indexOfFirst(
            start: Int = 0, end: Int = -1, step: Int = 1, predicate: (Float) -> Boolean
        ): Int {
            for (idx in start until (if (end == -1) size else end) step step)
                if (predicate(this[idx]))
                    return idx
            return -1
        }

        private fun FloatArray.isFinite(start: Int = 0, end: Int = -1): Boolean =
            indexOfFirst(start, end) { !it.isFinite() } == -1

        private fun AffineTransform.translate(tx: Float, ty: Float) = translate(tx.toDouble(), ty.toDouble())
        private fun AffineTransform.scale(s: Float) = scale(s.toDouble(), s.toDouble())
        private fun Graphics2D.translate(tx: Float, ty: Float) = translate(tx.toDouble(), ty.toDouble())
        private fun Graphics2D.scale(s: Float) = scale(s.toDouble(), s.toDouble())
        private fun Matrix.translate(tx: Double, ty: Double) = translate(tx.toFloat(), ty.toFloat())
        private fun Matrix.scale(s: Float) = scale(s, s)

    }


    class Layer


    private sealed class Instruction {

        class DrawDeferredImageLayer(
            val x: Float, val y: Y, val universeScaling: Float, val elasticScaling: Float,
            val image: DeferredImage, val layer: Layer
        ) : Instruction()

        class DrawShape(
            val x: Float, val y: Y, val shape: Shape, val color: Color, val fill: Boolean
        ) : Instruction()

        class DrawLine(
            val x1: Float, val y1: Y, val x2: Float, val y2: Y, val color: Color, val fill: Boolean
        ) : Instruction()

        class DrawRect(
            val x: Float, val y: Y, val width: Float, val height: Y, val color: Color, val fill: Boolean
        ) : Instruction()

        class DrawStringForeground(
            val x: Float, val y: Y, val fmtStr: FormattedString, val textLayout: TextLayout,
            val outlines: List<Pair<Shape, Color>>
        ) : Instruction()

        class DrawPicture(
            val x: Float, val y: Y, val pic: Picture
        ) : Instruction()

    }


    private interface MaterializationBackend {

        fun materializeShape(shape: Shape, color: Color, fill: Boolean)

        fun materializeStringForeground(
            x: Float, y: Float, scaling: Float, fmtStr: FormattedString, textLayout: TextLayout,
            outlines: List<Pair<Shape, Color>>
        )

        fun materializePicture(x: Float, y: Float, pic: Picture)

    }


    private class Graphics2DBackend(private val g2: Graphics2D) : MaterializationBackend {

        override fun materializeShape(shape: Shape, color: Color, fill: Boolean) {
            g2.color = color
            if (fill)
                g2.fill(shape)
            else
                g2.draw(shape)
        }

        override fun materializeStringForeground(
            x: Float, y: Float, scaling: Float, fmtStr: FormattedString, textLayout: TextLayout,
            outlines: List<Pair<Shape, Color>>
        ) {
            g2.preserveTransform {
                g2.translate(x, y)
                g2.scale(scaling)
                for ((shape, color) in outlines)
                    materializeShape(shape, color, fill = true)
            }
        }

        override fun materializePicture(x: Float, y: Float, pic: Picture) {
            when (pic) {
                is Picture.Raster -> {
                    val tx = AffineTransform().apply { translate(x, y); scale(pic.scaling) }
                    g2.drawImage(pic.img, tx, null)
                }
                is Picture.SVG -> g2.preserveTransform {
                    g2.translate(x, y)
                    g2.scale(pic.scaling)
                    // Batik might not be thread-safe, even though we haven't tested that.
                    synchronized(pic.gvtRoot) {
                        if (pic.isCropped)
                            g2.translate(-pic.gvtRoot.bounds.x, -pic.gvtRoot.bounds.y)
                        pic.gvtRoot.paint(g2)
                    }
                }
                // Note: We have to create a new Graphics2D object here because PDFBox modifies it heavily
                // and sometimes even makes it totally unusable.
                is Picture.PDF -> @Suppress("NAME_SHADOWING") g2.withNewG2 { g2 ->
                    g2.translate(x, y)
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
                    // PDFBox is definitely not thread-safe. Also, we check whether the document has been closed prior
                    // to using it and do not want it to close later while we are using it. Since the closing operation
                    // in the Picture class is also synchronized, we avoid such a situation.
                    synchronized(pic.doc) {
                        // If the project that opened this document has been closed and with it the document (which is
                        // possible because materialization might happen in a background thread), do not render the
                        // PDF to avoid provoking an exception.
                        if (!pic.doc.document.isClosed)
                            PDFRenderer(pic.doc).renderPageToGraphics(0, g2Proxy, pic.scaling)
                    }
                }
            }
        }

    }


    private class PDFBackend(
        private val doc: PDDocument,
        private val cs: PDPageContentStream,
        private val csHeight: Float
    ) : MaterializationBackend {

        private val docRes = docResMap.getOrPut(doc) { DocRes(doc) }

        private fun materializeShapeWithoutTransforming(shape: Shape, fill: Boolean) {
            val pi = shape.getPathIterator(AffineTransform())
            val coords = FloatArray(6)
            while (!pi.isDone) {
                when (pi.currentSegment(coords)) {
                    PathIterator.SEG_MOVETO ->
                        if (coords.isFinite(end = 2))
                            cs.moveTo(coords[0], coords[1])
                    PathIterator.SEG_LINETO ->
                        if (coords.isFinite(end = 2))
                            cs.lineTo(coords[0], coords[1])
                    PathIterator.SEG_QUADTO ->
                        if (coords.isFinite(end = 4))
                            cs.curveTo1(coords[0], coords[1], coords[2], coords[3])
                    PathIterator.SEG_CUBICTO ->
                        if (coords.isFinite(end = 6))
                            cs.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5])
                    PathIterator.SEG_CLOSE ->
                        cs.closePath()
                }
                pi.next()
            }

            if (fill)
                if (pi.windingRule == PathIterator.WIND_EVEN_ODD)
                    cs.fillEvenOdd()
                else
                    cs.fill()
            else
                cs.stroke()
        }

        override fun materializeShape(shape: Shape, color: Color, fill: Boolean) {
            cs.saveGraphicsState()
            setColor(color, fill)
            cs.transform(Matrix().apply { translate(0f, csHeight); scale(1f, -1f) })
            materializeShapeWithoutTransforming(shape, fill)
            cs.restoreGraphicsState()
        }

        override fun materializeStringForeground(
            x: Float, y: Float, scaling: Float, fmtStr: FormattedString, textLayout: TextLayout,
            outlines: List<Pair<Shape, Color>>
        ) {
            // Note: We assume that every TextLineComponent of the TextLayout is indeed based on a GlyphVector.
            // Since we do not use TextAttribute.CHAR_REPLACEMENT in this program, that assumption will always hold.
            val tlGVs = textLayout.getGlyphVectors()

            cs.saveGraphicsState()
            cs.transform(Matrix().apply { translate(x, csHeight - y); scale(scaling) })

            // Draw the text by drawing each GlyphVector.
            cs.beginText()
            for (tlGV in tlGVs) {
                val font = tlGV.gv.font
                val pdFont = getPDFont(font)

                // If superscripting is enabled, find the appropriately downscaled font size.
                val fontSize = if (font.attributes[TextAttribute.SUPERSCRIPT] == null) font.size2D else {
                    val runStr = fmtStr.string.substring(tlGV.startCharIdx, tlGV.stopCharIdx)
                    val slm = font.getLineMetrics(runStr, REF_FRC)
                    val nlm = font.deriveFont(FONT_NORMALIZATION_ATTRS).getLineMetrics(runStr, REF_FRC)
                    font.size2D * slm.height / nlm.height
                }

                val numGlyphs = tlGV.gv.numGlyphs
                val glyphs = tlGV.gv.getGlyphCodes(0, numGlyphs, null)
                val glyphPos = tlGV.gv.getGlyphPositions(0, numGlyphs, null)
                val xShifts = FloatArray(numGlyphs - 1)
                for (glyphIdx in 0 until numGlyphs - 1) {
                    val actualWidth = pdFont.getWidth(glyphs[glyphIdx])
                    val wantedWidth = (glyphPos[(glyphIdx + 1) * 2] - glyphPos[glyphIdx * 2]) * 1000f / fontSize
                    xShifts[glyphIdx] = actualWidth - wantedWidth
                }

                cs.setFont(pdFont, fontSize)
                setColor(fmtStr.getForeground(tlGV.startCharIdx)!!, fill = true)
                cs.setTextMatrix(Matrix.getTranslateInstance(tlGV.x, tlGV.y - glyphPos[1]))
                cs.showGlyphsWithPositioning(glyphs, xShifts, bytesPerGlyph = 2 /* always true for TTF/OTF fonts */)
            }
            cs.endText()

            // Where applicable, draw underline & strikethrough.
            for (tlGV in tlGVs) {
                val ul = fmtStr.getUnderline(tlGV.startCharIdx)
                val st = fmtStr.getStrikethrough(tlGV.startCharIdx)

                if (ul || st) {
                    // In case of superscripting, we still retrieve the font metrics of the unscaled font since they
                    // already include all the changes made by superscripting, and scaling would distort them.
                    val runStr = fmtStr.string.substring(tlGV.startCharIdx, tlGV.stopCharIdx)
                    val lm = tlGV.gv.font.getLineMetrics(runStr, REF_FRC)

                    val numGlyphs = tlGV.gv.numGlyphs
                    val glyphPos = tlGV.gv.getGlyphPositions(0, numGlyphs + 1, null)

                    fun drawLine(offset: Float, thickness: Float) {
                        // Since the thickness of underlining and strikethrough usually does not depend on
                        // superscripting in Java, we accordingly use the non-downscaled font size for computing
                        // the fallback thickness.
                        cs.setLineWidth(if (thickness > 0.00001f) thickness else 0.04f * tlGV.gv.font.size2D)
                        val lineY = tlGV.y - offset
                        cs.moveTo(tlGV.x + glyphPos[0], lineY)
                        cs.lineTo(tlGV.x + glyphPos[numGlyphs * 2], lineY)
                        cs.stroke()
                    }

                    setColor(fmtStr.getForeground(tlGV.startCharIdx)!!, fill = false)
                    if (ul)
                        drawLine(lm.underlineOffset, lm.underlineThickness)
                    if (st)
                        drawLine(lm.strikethroughOffset, lm.strikethroughThickness)
                }
            }

            cs.restoreGraphicsState()
        }

        override fun materializePicture(x: Float, y: Float, pic: Picture) {
            val mat = Matrix().apply { translate(x, csHeight - y - pic.height) }

            when (pic) {
                is Picture.Raster -> {
                    mat.scale(pic.width, pic.height)
                    // Since PDFs are not used for mastering but instead for previews, we lossily compress raster
                    // images to produce smaller files.
                    val pdImg = docRes.pdImages.getOrPut(pic) { JPEGFactory.createFromImage(doc, pic.img) }
                    cs.drawImage(pdImg, mat)
                }
                is Picture.SVG -> {
                    val pdForm = docRes.pdForms.getOrPut(pic) {
                        val g2 = PdfBoxGraphics2D(doc, pic.width, pic.height)
                        g2.scale(pic.scaling)
                        // Batik might not be thread-safe, even though we haven't tested that.
                        synchronized(pic.gvtRoot) {
                            if (pic.isCropped)
                                g2.translate(-pic.gvtRoot.bounds.x, -pic.gvtRoot.bounds.y)
                            pic.gvtRoot.paint(g2)
                        }
                        g2.dispose()
                        g2.xFormObject
                    }
                    cs.saveGraphicsState()
                    cs.transform(mat)
                    cs.drawForm(pdForm)
                    cs.restoreGraphicsState()
                }
                is Picture.PDF -> {
                    mat.scale(pic.scaling)
                    if (pic.isCropped) {
                        val origHeight = pic.doc.pages[0].cropBox.height
                        mat.translate(-pic.minBox.x, pic.minBox.maxY - origHeight)
                    }
                    val pdForm = docRes.pdForms.getOrPut(pic) {
                        // PDFBox is not thread-safe. Also, we have the same closing situation as with the
                        // Graphics2DBackend further up this file. See the comments there for details.
                        synchronized(pic.doc) {
                            if (pic.doc.document.isClosed)
                                return
                            docRes.layerUtil.importPageAsForm(pic.doc, 0)
                        }
                    }
                    cs.saveGraphicsState()
                    cs.transform(mat)
                    cs.drawForm(pdForm)
                    cs.restoreGraphicsState()
                }
            }
        }

        private fun setColor(color: Color, fill: Boolean) {
            if (fill)
                cs.setNonStrokingColor(color)
            else
                cs.setStrokingColor(color)

            cs.setGraphicsStateParameters(docRes.extGStates.getOrPut(color.alpha) {
                PDExtendedGraphicsState().apply {
                    if (fill)
                        nonStrokingAlphaConstant = color.alpha / 255f
                    else
                        strokingAlphaConstant = color.alpha / 255f
                }
            })
        }

        private fun getPDFont(font: Font): PDFont {
            val psName = font.psName

            if (psName !in docRes.pdFonts)
                try {
                    val fontFile = font.getFontFile().toFile()
                    when (DataInputStream(fontFile.inputStream()).use { it.readInt() }) {
                        0x74746366 -> {
                            TrueTypeCollection(fontFile).processAllFonts { ttf ->
                                docRes.pdFonts[ttf.name] = PDType0Font.load(doc, ttf, ttf !is OpenTypeFont)
                            }
                            if (psName !in docRes.pdFonts) {
                                val msg = "Successfully loaded the font file '{}' for PDF embedding, but the font " +
                                        "'{}' which lead to that file can for some reason not be found in there."
                                LOGGER.warn(msg, fontFile, psName)
                                // Memoize the fallback font to avoid trying to load the font file again.
                                docRes.pdFonts[psName] = PDType1Font.HELVETICA
                            }
                        }
                        0x4f54544f ->
                            docRes.pdFonts[psName] = PDType0Font.load(doc, OTFParser().parse(fontFile), false)
                        else ->
                            // Here, one could theoretically enable embedSubset. However, our string writing logic
                            // directly writes font-specific glyphs (in contrast to unicode codepoints) since this is
                            // the only way we can leverage the power of TextLayout and also the only way we can write
                            // some special ligatures that have no unicode codepoint. Now, since PDFBox's font
                            // subsetting mechanism only works on codepoints and not on glyphs, we cannot use it.
                            docRes.pdFonts[psName] = PDType0Font.load(doc, TTFParser().parse(fontFile), false)
                    }
                } catch (e: Exception) {
                    LOGGER.warn("Cannot load the font file of the font '{}' for PDF embedding.", psName, e)
                    // Memoize the fallback font to avoid trying to load the font file again.
                    docRes.pdFonts[psName] = PDType1Font.HELVETICA
                }

            return docRes.pdFonts.getValue(psName)
        }

        companion object {
            private val docResMap = WeakHashMap<PDDocument, DocRes>()
        }

        private class DocRes(doc: PDDocument) {
            val extGStates = HashMap<Int /* alpha */, PDExtendedGraphicsState>()
            val pdFonts = HashMap<String /* font name */, PDFont>()
            val pdImages = HashMap<Picture.Raster, PDImageXObject>()
            val pdForms = HashMap<Picture, PDFormXObject>()
            val layerUtil by lazy { LayerUtility(doc) }
        }

    }

}
