package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.Graphics2DProxy
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.fontbox.ttf.OTFParser
import org.apache.fontbox.ttf.OpenTypeFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeCollection
import org.apache.pdfbox.cos.COSName
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
import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.DataInputStream
import java.util.*
import kotlin.math.*


class DeferredImage(var width: Double = 0.0, var height: Y = 0.0.toY()) {

    private val instructions = HashMap<Layer, MutableList<Instruction>>()

    private fun addInstruction(layer: Layer, insn: Instruction) {
        instructions.computeIfAbsent(layer) { ArrayList() }.add(insn)
    }

    fun copy(universeScaling: Double = 1.0, elasticScaling: Double = 1.0): DeferredImage {
        val copy = DeferredImage(
            width = width * universeScaling,
            height = (height * universeScaling).scaleElastic(elasticScaling)
        )
        copy.drawDeferredImage(this, universeScaling = universeScaling, elasticScaling = elasticScaling)
        return copy
    }

    fun drawDeferredImage(
        image: DeferredImage,
        x: Double = 0.0, y: Y = 0.0.toY(), universeScaling: Double = 1.0, elasticScaling: Double = 1.0
    ) {
        for (layer in image.instructions.keys) {
            val insn = Instruction.DrawDeferredImageLayer(x, y, universeScaling, elasticScaling, image, layer)
            addInstruction(layer, insn)
        }
    }

    fun drawShape(
        color: Color, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = FOREGROUND
    ) {
        addInstruction(layer, Instruction.DrawShape(x, y, shape, color, fill, blurRadius))
    }

    fun drawLine(color: Color, x1: Double, y1: Y, x2: Double, y2: Y, layer: Layer = FOREGROUND) {
        addInstruction(layer, Instruction.DrawLine(x1, y1, x2, y2, color))
    }

    fun drawRect(
        color: Color, x: Double, y: Y, width: Double, height: Y, fill: Boolean = false, layer: Layer = FOREGROUND
    ) {
        addInstruction(layer, Instruction.DrawRect(x, y, width, height, color, fill))
    }

    /**
     * The [y] coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the largest font.
     *
     * @throws IllegalArgumentException If [fmtStr] is not equipped with both a font and a foreground color at
     *     every character.
     */
    fun drawString(
        fmtStr: FormattedString, x: Double, y: Y,
        foregroundLayer: Layer = FOREGROUND, backgroundLayer: Layer = BACKGROUND
    ) {
        // FormattedString yields results relative to the baseline, so we need the baseline's y-coordinate.
        val baselineY = y + fmtStr.heightAboveBaseline

        for ((shape, color) in fmtStr.decorationsUnderlay)
            addInstruction(foregroundLayer, Instruction.DrawShape(x, baselineY, shape, color, true))
        addInstruction(foregroundLayer, Instruction.DrawStringForeground(x, baselineY, fmtStr))
        for ((shape, color) in fmtStr.decorationsOverlay)
            addInstruction(foregroundLayer, Instruction.DrawShape(x, baselineY, shape, color, true))
        for ((shape, color) in fmtStr.backgrounds)
            addInstruction(backgroundLayer, Instruction.DrawShape(x, baselineY, shape, color, true))
    }

    fun drawPicture(pic: Picture, x: Double, y: Y, layer: Layer = FOREGROUND) {
        addInstruction(layer, Instruction.DrawPicture(x, y, pic))
    }

    fun materialize(g2: Graphics2D, layers: List<Layer>, culling: Rectangle2D? = null) {
        materializeDeferredImage(Graphics2DBackend(g2), 0.0, 0.0, 1.0, 1.0, culling, this, layers)
    }

    fun materialize(
        doc: PDDocument, cs: PDPageContentStream, csHeight: Float, layers: List<Layer>, culling: Rectangle2D? = null
    ) {
        materializeDeferredImage(PDFBackend(doc, cs, csHeight), 0.0, 0.0, 1.0, 1.0, culling, this, layers)
    }

    private fun materializeDeferredImage(
        backend: MaterializationBackend,
        x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?,
        image: DeferredImage, layers: List<Layer>
    ) {
        for (layer in layers)
            for (insn in image.instructions.getOrDefault(layer, emptyList()))
                materializeInstruction(backend, x, y, universeScaling, elasticScaling, culling, insn)
    }

    private fun materializeInstruction(
        backend: MaterializationBackend,
        x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?,
        insn: Instruction
    ) {
        when (insn) {
            is Instruction.DrawDeferredImageLayer -> materializeDeferredImage(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling * insn.universeScaling, elasticScaling * insn.elasticScaling, culling, insn.image,
                listOf(insn.layer)
            )
            is Instruction.DrawShape -> materializeShape(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling, culling, insn.shape, insn.color, insn.fill, universeScaling * insn.blurRadius
            )
            is Instruction.DrawLine -> materializeShape(
                backend, x, y, universeScaling, culling,
                Line2D.Double(insn.x1, insn.y1.resolve(elasticScaling), insn.x2, insn.y2.resolve(elasticScaling)),
                insn.color, fill = false, blurRadius = 0.0
            )
            is Instruction.DrawRect -> materializeShape(
                backend, x, y, universeScaling, culling,
                Rectangle2D.Double(
                    insn.x, insn.y.resolve(elasticScaling), insn.width, insn.height.resolve(elasticScaling)
                ), insn.color, insn.fill, blurRadius = 0.0
            )
            is Instruction.DrawStringForeground -> materializeStringForeground(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.baselineY.resolve(elasticScaling),
                universeScaling, culling, insn.fmtStr
            )
            is Instruction.DrawPicture -> materializePicture(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling), culling,
                insn.pic.scaled(universeScaling)
            )
        }
    }

    private fun materializeShape(
        backend: MaterializationBackend,
        x: Double, y: Double, scaling: Double, culling: Rectangle2D?,
        shape: Shape, color: Color, fill: Boolean, blurRadius: Double
    ) {
        // It would be a bit complicated to exactly determine which pixels are affected after the blur, so instead, we
        // just add a safeguard buffer to better be sure that not a single blurred pixel is accidentally culled.
        val safeBlurRadius = if (blurRadius == 0.0) 0.0 else blurRadius + 4.0
        if (culling != null &&
            !shape.intersects(
                (culling.x - x - safeBlurRadius) / scaling,
                (culling.y - y - safeBlurRadius) / scaling,
                (culling.width + 2 * safeBlurRadius) / scaling,
                (culling.height + 2 * safeBlurRadius) / scaling
            )
        ) return
        // We first transform the shape and then draw it without scaling the canvas.
        // This ensures that the shape will exhibit the default stroke width, which is usually 1 pixel.
        val tx = AffineTransform().apply { translate(x, y); scale(scaling) }
        val transformedShape = if (shape is Path2D.Float) Path2D.Float(shape, tx) else Path2D.Double(shape, tx)
        if (blurRadius <= 0.0)
            backend.materializeShape(transformedShape, color, fill)
        else {
            // For both backends, the only conceivable way to implement blurring appears to be to first draw the shape
            // to an image, blur that, and then to send it to the backend.
            check(fill) { "Blurring is only supported for filled shapes." }
            // Build the horizontal and vertical Gaussian kernels and determine the necessary image padding.
            val kernelVec = gaussianKernelVector(blurRadius)
            val hKernel = Kernel(kernelVec.size, 1, kernelVec)
            val vKernel = Kernel(1, kernelVec.size, kernelVec)
            val halfPad = (kernelVec.size - 1) / 2
            val fullPad = 2 * halfPad
            // Determine the bounds of the shape, and the whole and fractional parts of the shape's coordinates.
            val bounds = transformedShape.bounds2D
            val xWhole = floor(bounds.x)
            val yWhole = floor(bounds.y)
            val xFrac = bounds.x - xWhole
            val yFrac = bounds.y - yWhole
            // Paint the shape to an intermediate image with sufficient padding.
            var img = BufferedImage(
                ceil(xFrac + bounds.width).toInt() + 2 * fullPad,
                ceil(yFrac + bounds.height).toInt() + 2 * fullPad,
                // If the image did not have premultiplied alpha, transparent pixels would have black color channels
                // and would hence spill blackness when blurred into other pixels.
                BufferedImage.TYPE_INT_ARGB_PRE
            ).withG2 { g2 ->
                g2.setHighQuality()
                g2.color = color
                g2.translate(fullPad - xWhole, fullPad - yWhole)
                g2.fill(transformedShape)
            }
            // Apply the convolution kernels.
            val tmp = ConvolveOp(hKernel).filter(img, null)
            img = ConvolveOp(vKernel).filter(tmp, img)
            // Cut off the padding that is guaranteed to be empty due to the convolutions' EDGE_ZERO_FILL behavior.
            img = img.getSubimage(halfPad, halfPad, img.width - fullPad, img.height - fullPad)
            // Send the resulting image to the backend.
            backend.materializePicture(xWhole - halfPad, yWhole - halfPad, Picture.Raster(img))
        }
    }

    private fun gaussianKernelVector(radius: Double): FloatArray {
        val sigma = radius / 2.0
        val twoSigmaSq = 2.0 * sigma * sigma
        val sqrtOfTwoPiSigma = sqrt(2.0 * PI * sigma)
        // Compute the Gaussian curve.
        val intRadius = floor(radius).toInt()
        val vecSize = intRadius * 2 + 1
        val vec = FloatArray(vecSize) { idx ->
            val distToCenter = idx - intRadius
            (exp(-distToCenter * distToCenter / twoSigmaSq) / sqrtOfTwoPiSigma).toFloat()
        }
        // Normalize the curve to fix numerical inaccuracies.
        val sum = vec.sum()
        return FloatArray(vecSize) { idx -> vec[idx] / sum }
    }

    private fun materializeStringForeground(
        backend: MaterializationBackend,
        x: Double, baselineY: Double, scaling: Double, culling: Rectangle2D?,
        fmtStr: FormattedString
    ) {
        if (culling != null &&
            !culling.intersects(
                x, baselineY - fmtStr.heightAboveBaseline * scaling, fmtStr.width * scaling, fmtStr.height * scaling
            )
        ) return
        backend.materializeStringForeground(x, baselineY, scaling, fmtStr)
    }

    private fun materializePicture(
        backend: MaterializationBackend,
        x: Double, y: Double, culling: Rectangle2D?,
        pic: Picture
    ) {
        if (culling != null && !culling.intersects(x, y, pic.width, pic.height))
            return
        backend.materializePicture(x, y, pic)
    }


    companion object {

        // These common layers are typically used. Additional layers may be defined by users of this class.
        val FOREGROUND = Layer()
        val BACKGROUND = Layer()
        val GUIDES = Layer()

        val DELIVERED_LAYERS = listOf(BACKGROUND, FOREGROUND)

        private fun FloatArray.isFinite(end: Int): Boolean =
            allBetween(0, end, Float::isFinite)

    }


    class Layer


    private sealed interface Instruction {

        class DrawDeferredImageLayer(
            val x: Double, val y: Y, val universeScaling: Double, val elasticScaling: Double,
            val image: DeferredImage, val layer: Layer
        ) : Instruction

        class DrawShape(
            val x: Double, val y: Y, val shape: Shape, val color: Color, val fill: Boolean, val blurRadius: Double
        ) : Instruction

        class DrawLine(
            val x1: Double, val y1: Y, val x2: Double, val y2: Y, val color: Color
        ) : Instruction

        class DrawRect(
            val x: Double, val y: Y, val width: Double, val height: Y, val color: Color, val fill: Boolean
        ) : Instruction

        class DrawStringForeground(
            val x: Double, val baselineY: Y, val fmtStr: FormattedString
        ) : Instruction

        class DrawPicture(
            val x: Double, val y: Y, val pic: Picture
        ) : Instruction

    }


    private interface MaterializationBackend {
        fun materializeShape(shape: Shape, color: Color, fill: Boolean)
        fun materializeStringForeground(x: Double, baselineY: Double, scaling: Double, fmtStr: FormattedString)
        fun materializePicture(x: Double, y: Double, pic: Picture)
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
            x: Double, baselineY: Double, scaling: Double, fmtStr: FormattedString
        ) {
            // We render the text by first converting the string to a path via FormattedString and then
            // filling that path. This has the following vital advantages:
            //   - Native text rendering via TextLayout.draw() etc., which internally eventually calls
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
            for (segment in fmtStr.segments)
                g2.preserveTransform {
                    g2.translate(x, baselineY)
                    g2.scale(scaling)
                    g2.translate(segment.baseX, 0.0)
                    materializeShape(segment.outline, segment.font.color, fill = true)
                }
        }

        override fun materializePicture(x: Double, y: Double, pic: Picture) {
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
                is Picture.PDF -> g2.withNewG2 { g2 ->
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
                            PDFRenderer(pic.doc).renderPageToGraphics(0, g2Proxy, pic.scaling.toFloat())
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

        private val docRes = docResMap.computeIfAbsent(doc) { DocRes(doc) }

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
            x: Double, baselineY: Double, scaling: Double, fmtStr: FormattedString
        ) {
            cs.saveGraphicsState()
            cs.beginText()
            for (seg in fmtStr.segments) {
                val pdFont = getPDFont(seg.font.awtFont)
                val glyphs = seg.getGlyphCodes()
                val xShifts = FloatArray(seg.numGlyphs - 1) { glyphIdx ->
                    val actualWidth = pdFont.getWidth(glyphs[glyphIdx])
                    val wantedWidth = ((seg.getGlyphOffsetX(glyphIdx + 1) - seg.getGlyphOffsetX(glyphIdx))
                            // Because we apply hScaling via the text matrix, we have to divide it out here
                            // so that it is not applied two times.
                            / seg.font.hScaling
                            // Convert to the special PDF text coordinates.
                            * 1000.0 / seg.font.pointSize).toFloat()
                    actualWidth - wantedWidth
                }

                val textTx = AffineTransform().apply {
                    translate(x, csHeight - baselineY)
                    scale(scaling)
                    translate(seg.baseX, 0.0)
                    concatenate(seg.font.getPostprocessingTransform(withHScaling = true, invertY = true))
                }

                cs.setFont(pdFont, seg.font.pointSize.toFloat())
                setColor(seg.font.color, fill = true)
                cs.setTextMatrix(Matrix(textTx))
                cs.showGlyphsWithPositioning(glyphs, xShifts, bytesPerGlyph = 2 /* always true for TTF/OTF fonts */)
            }
            cs.endText()
            cs.restoreGraphicsState()
        }

        override fun materializePicture(x: Double, y: Double, pic: Picture) {
            val mat = Matrix().apply { translate(x, csHeight - y - pic.height) }

            when (pic) {
                is Picture.Raster -> {
                    mat.scale(pic.width, pic.height)
                    // Since PDFs are not used for mastering but instead for previews, we lossily compress raster
                    // images to produce smaller files.
                    val pdImg = docRes.pdImages.computeIfAbsent(pic) {
                        val img = pic.img
                        // Adapted from JPEGFactory, but uses AlphaComposite to discard the alpha channel instead of
                        // ColorConvertOp, because the latter just draws the image onto a black background, thereby
                        // tinting all non-opaque pixels black depending on their transparency. Notice that even though
                        // JPEGFactory.getColorImage() warns about using AlphaComposite, we cannot reproduce the
                        // problems that they describe.
                        if (img.colorModel.hasAlpha()) {
                            val alphaRaster = img.alphaRaster
                            if (alphaRaster != null) {
                                val alphaImg = BufferedImage(img.width, img.height, BufferedImage.TYPE_BYTE_GRAY)
                                    .apply { data = alphaRaster }
                                val colorImg = BufferedImage(img.width, img.height, BufferedImage.TYPE_3BYTE_BGR)
                                    .withG2 { g2 ->
                                        g2.composite = AlphaComposite.Src
                                        g2.drawImage(img, 0, 0, null)
                                    }
                                return@computeIfAbsent JPEGFactory.createFromImage(doc, colorImg).apply {
                                    cosObject.setItem(COSName.SMASK, JPEGFactory.createFromImage(doc, alphaImg))
                                }
                            }
                        }
                        JPEGFactory.createFromImage(doc, img)
                    }
                    cs.drawImage(pdImg, mat)
                }
                is Picture.SVG -> {
                    val pdForm = docRes.pdForms.computeIfAbsent(pic) {
                        val g2 = PdfBoxGraphics2D(doc, pic.width.toFloat(), pic.height.toFloat())
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

            cs.setGraphicsStateParameters(docRes.extGStates.computeIfAbsent(ExtGStateKey(fill, color.alpha)) {
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
                        // TrueType Collection
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
                        // OpenType Font
                        0x4F54544F ->
                            docRes.pdFonts[psName] = PDType0Font.load(doc, OTFParser().parse(fontFile), false)
                        // TrueType Font
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
            val extGStates = HashMap<ExtGStateKey, PDExtendedGraphicsState>()
            val pdFonts = HashMap<String /* font name */, PDFont>()
            val pdImages = HashMap<Picture.Raster, PDImageXObject>()
            val pdForms = HashMap<Picture, PDFormXObject>()
            val layerUtil by lazy { LayerUtility(doc) }
        }

        private data class ExtGStateKey(private val fill: Boolean, private val alpha: Int)

    }

}
