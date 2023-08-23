package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.batik.svggen.SVGGraphics2D
import org.apache.fontbox.ttf.OTFParser
import org.apache.fontbox.ttf.OpenTypeFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeCollection
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRange
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask
import org.apache.pdfbox.util.Matrix
import java.awt.*
import java.awt.RenderingHints.KEY_INTERPOLATION
import java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.DataInputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
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
        color: Color, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        drawShape(Coat.Plain(color), shape, x, y, fill, blurRadius, layer)
    }

    fun drawShape(
        coat: Coat, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawShape(x, y, shape, coat, fill, blurRadius))
    }

    fun drawLine(color: Color, x1: Double, y1: Y, x2: Double, y2: Y, dash: Boolean = false, layer: Layer = STATIC) {
        if (color.alpha == 0) return
        addInstruction(layer, Instruction.DrawLine(x1, y1, x2, y2, color, dash))
    }

    fun drawRect(
        color: Color, x: Double, y: Y, width: Double, height: Y, fill: Boolean = false, layer: Layer = STATIC
    ) {
        if (color.alpha == 0) return
        addInstruction(layer, Instruction.DrawRect(x, y, width, height, color, fill))
    }

    fun drawText(coat: Coat, text: Text, x: Double, yBaseline: Y, layer: Layer = STATIC) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawText(x, yBaseline, text, coat))
    }

    fun drawEmbeddedPicture(embeddedPic: Picture.Embedded, x: Double, y: Y, layer: Layer = STATIC) {
        addInstruction(layer, Instruction.DrawEmbeddedPicture(x, y, embeddedPic))
    }

    fun drawEmbeddedTape(embeddedTape: Tape.Embedded, x: Double, y: Y, layer: Layer = TAPES) {
        val thumbnail = try {
            Picture.Raster(embeddedTape.tape.getPreviewFrame(embeddedTape.range.start)!!)
        } catch (_: Exception) {
            null
        }
        addInstruction(layer, Instruction.DrawEmbeddedTape(x, y, embeddedTape, thumbnail))
    }

    /**
     * Draws the content of this deferred image onto the given [BufferedImage]. Any raster content is aligned with the
     * buffered image's pixel grid to prevent interpolation and retain as much quality as possible.
     */
    fun materialize(bufImage: BufferedImage, layers: List<Layer>, composite: Composite? = null) {
        bufImage.withG2 { g2 ->
            g2.setHighQuality()
            composite?.let(g2::setComposite)
            val backend = Graphics2DBackend(g2, rasterOutput = true)
            // If only a portion of the deferred image is materialized, cull the rest to improve performance.
            // Notice that because the culling rect is aligned with the pixel grid, we correctly include all content
            // that at least partially lies inside one of the buffered image's pixels.
            val culling = Rectangle2D.Double(0.0, 0.0, bufImage.width.toDouble(), bufImage.height.toDouble())
            materializeDeferredImage(backend, 0.0, 0.0, 1.0, 1.0, culling, this, layers)
        }
    }

    /** Draws the content of this deferred image onto an SVG via the given [SVGGraphics2D]. */
    fun materialize(g2: SVGGraphics2D, layers: List<Layer>) {
        materializeDeferredImage(Graphics2DBackend(g2, rasterOutput = false), 0.0, 0.0, 1.0, 1.0, null, this, layers)
    }

    /** Draws the content of this deferred onto a PDF page. */
    fun materialize(doc: PDDocument, page: PDPage, cs: PDPageContentStream, layers: List<Layer>) {
        materializeDeferredImage(PDFBackend(doc, page, cs), 0.0, 0.0, 1.0, 1.0, null, this, layers)
    }

    fun collectPlacedTapes(layers: List<Layer>): List<PlacedTape> {
        val backend = PlacedTapeCollectorBackend()
        materializeDeferredImage(backend, 0.0, 0.0, 1.0, 1.0, null, this, layers)
        return backend.collected
    }

    private fun materializeDeferredImage(
        backend: MaterializationBackend,
        x: Double, y: Double, universeScaling: Double, elasticScaling: Double, culling: Rectangle2D?,
        image: DeferredImage, layers: List<Layer>
    ) {
        if (culling != null) {
            // We want seemingly degenerate images (which can occur when a width or height is forced down to 0)
            // to be drawn as well, but Rectangle2D.intersects() would kick them out, so we implement our own logic.
            val w = universeScaling * image.width
            val h = universeScaling * image.height.resolve(elasticScaling)
            val cx = culling.x
            val cy = culling.y
            if (x + w < cx || y + h < cy || x > cx + culling.width || y > cy + culling.height)
                return
        }
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
                universeScaling, culling, insn.shape, insn.coat, insn.fill, false, universeScaling * insn.blurRadius
            )
            is Instruction.DrawLine -> materializeShape(
                backend, x, y, universeScaling, culling,
                Line2D.Double(insn.x1, insn.y1.resolve(elasticScaling), insn.x2, insn.y2.resolve(elasticScaling)),
                Coat.Plain(insn.color), fill = false, insn.dash, blurRadius = 0.0
            )
            is Instruction.DrawRect -> materializeShape(
                backend, x, y, universeScaling, culling,
                Rectangle2D.Double(
                    insn.x, insn.y.resolve(elasticScaling), insn.width, insn.height.resolve(elasticScaling)
                ), Coat.Plain(insn.color), insn.fill, dash = false, blurRadius = 0.0
            )
            is Instruction.DrawText -> materializeText(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.yBaseline.resolve(elasticScaling),
                universeScaling, culling, insn.text, insn.coat
            )
            is Instruction.DrawEmbeddedPicture -> materializeEmbeddedPicture(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling, culling, insn.embeddedPic
            )
            is Instruction.DrawEmbeddedTape -> materializeEmbeddedTape(
                backend, x + universeScaling * insn.x, y + universeScaling * insn.y.resolve(elasticScaling),
                universeScaling, culling, insn.embeddedTape, insn.thumbnail
            )
        }
    }

    private fun materializeShape(
        backend: MaterializationBackend,
        x: Double, y: Double, scaling: Double, culling: Rectangle2D?,
        shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double
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
        val transformedCoat = coat.transform(tx)
        backend.materializeShape(transformedShape, transformedCoat, fill, dash, blurRadius)
    }

    private fun materializeText(
        backend: MaterializationBackend,
        x: Double, yBaseline: Double, scaling: Double, culling: Rectangle2D?,
        text: Text, coat: Coat
    ) {
        if (culling != null &&
            !culling.intersects(
                x,
                yBaseline - text.heightAboveBaseline * scaling,
                text.width * scaling,
                (text.heightAboveBaseline + text.heightBelowBaseline) * scaling
            )
        ) return
        backend.materializeText(x, yBaseline, scaling, text, coat)
    }

    private fun materializeEmbeddedPicture(
        backend: MaterializationBackend,
        x: Double, y: Double, scaling: Double, culling: Rectangle2D?,
        embeddedPic: Picture.Embedded
    ) {
        if (culling != null && !culling.intersects(x, y, embeddedPic.width * scaling, embeddedPic.height * scaling))
            return
        backend.materializeEmbeddedPicture(x, y, scaling, embeddedPic, draft = false)
    }

    private fun materializeEmbeddedTape(
        backend: MaterializationBackend,
        x: Double, y: Double, scaling: Double, culling: Rectangle2D?,
        embeddedTape: Tape.Embedded, thumbnail: Picture.Raster?
    ) {
        val resolution = embeddedTape.resolution
        if (culling != null && !culling.intersects(x, y, resolution.widthPx * scaling, resolution.heightPx * scaling))
            return
        backend.materializeEmbeddedTape(x, y, scaling, embeddedTape, thumbnail)
    }


    companion object {

        // These common layers are typically used. Additional layers may be defined by users of this class.
        val STATIC = object : Layer {}
        val TAPES = object : Layer {}
        val GUIDES = object : Layer {}

        private fun FloatArray.isFinite(end: Int): Boolean =
            allBetween(0, end, Float::isFinite)

        private fun Coat.isVisible(): Boolean = when (this) {
            is Coat.Plain -> color.alpha != 0
            is Coat.Gradient -> color1.alpha != 0 || color2.alpha != 0
        }

        private fun Coat.transform(tx: AffineTransform): Coat = when (this) {
            is Coat.Plain -> this
            is Coat.Gradient -> Coat.Gradient(color1, color2, tx.transform(point1, null), tx.transform(point2, null))
        }

        private fun Coat.toPaint(): Paint = when (this) {
            is Coat.Plain -> color
            // Here, we use GradientPaint, which interpolates in the sRGB color space, instead of LinearGradientPaint,
            // which would offer us to interpolate in the linear RGB color space, for a couple of reasons:
            //   - Gradient interpolation in sRGB is pretty much the default at this point, for better or for worse.
            //     Artists expect it when they specify a gradient.
            //   - Implementing a gradient in linear RGB in a PDF or SVG file is surprisingly difficult. While it may be
            //     possible for PDFs, it seems outright impossible at this point in SVGs; even though the standards are
            //     in place, it's not supported by any browser or viewer.
            //   - Apart from the famous red-to-green case, gradients in sRGB actually often look superior to those
            //     interpolated in linear RGB. This is because sRGB's transfer characteristic kind of models the human
            //     perception of light intensity, so gradients look more perceptually uniform. For examples, see:
            //     https://aras-p.info/blog/2021/11/29/Gradients-in-linear-space-arent-better/
            is Coat.Gradient -> GradientPaint(point1, color1, point2, color2)
        }

        private fun gaussianStdDev(radius: Double) = radius / 2.0

        private fun gaussianKernelVector(radius: Double): FloatArray {
            val sigma = gaussianStdDev(radius)
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

    }


    interface Layer


    sealed interface Coat {
        class Plain(val color: Color) : Coat
        class Gradient(val color1: Color, val color2: Color, val point1: Point2D, val point2: Point2D) : Coat
    }


    interface Text {
        val width: Double
        val heightAboveBaseline: Double
        val heightBelowBaseline: Double
        /** The outline of the entire text, with the [transform] already applied. */
        val transformedOutline: Shape
        val font: Font
        val glyphCodes: IntArray
        fun getGlyphOffsetX(glyphIdx: Int): Double
        /** Applying this to the drawn [glyphCodes] positioned by [getGlyphOffsetX] yields [transformedOutline]. */
        val transform: AffineTransform
    }


    private sealed interface Instruction {

        class DrawDeferredImageLayer(
            val x: Double, val y: Y, val universeScaling: Double, val elasticScaling: Double,
            val image: DeferredImage, val layer: Layer
        ) : Instruction

        class DrawShape(
            val x: Double, val y: Y, val shape: Shape, val coat: Coat, val fill: Boolean, val blurRadius: Double
        ) : Instruction

        class DrawLine(
            val x1: Double, val y1: Y, val x2: Double, val y2: Y, val color: Color, val dash: Boolean
        ) : Instruction

        class DrawRect(
            val x: Double, val y: Y, val width: Double, val height: Y, val color: Color, val fill: Boolean
        ) : Instruction

        class DrawText(
            val x: Double, val yBaseline: Y, val text: Text, val coat: Coat
        ) : Instruction

        class DrawEmbeddedPicture(
            val x: Double, val y: Y, val embeddedPic: Picture.Embedded
        ) : Instruction

        class DrawEmbeddedTape(
            val x: Double, val y: Y, val embeddedTape: Tape.Embedded, val thumbnail: Picture.Raster?
        ) : Instruction

    }


    private interface MaterializationBackend {

        // The default implementations skip materialization.
        fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {}
        fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {}
        fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: Picture.Embedded, draft: Boolean
        ) {
        }

        fun materializeEmbeddedTape(
            x: Double,
            y: Double,
            scaling: Double,
            embeddedTape: Tape.Embedded,
            thumbnail: Picture.Raster?
        )

    }


    /** Implements blurring by first rasterizing the shape to an image and blurring that. */
    private interface RasterBlurBackend : MaterializationBackend {

        fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean)

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            if (blurRadius <= 0.0)
                materializeShape(shape, coat, fill, dash)
            else {
                check(fill) { "Blurring is only supported for filled shapes." }
                // Build the horizontal and vertical Gaussian kernels and determine the necessary image padding.
                val kernelVec = gaussianKernelVector(blurRadius)
                val hKernel = Kernel(kernelVec.size, 1, kernelVec)
                val vKernel = Kernel(1, kernelVec.size, kernelVec)
                val halfPad = (kernelVec.size - 1) / 2
                val fullPad = 2 * halfPad
                // Determine the bounds of the shape, and the whole and fractional parts of the shape's coordinates.
                val bounds = shape.bounds2D
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
                    BufferedImage.TYPE_4BYTE_ABGR_PRE
                ).withG2 { g2 ->
                    g2.setHighQuality()
                    g2.paint = coat.toPaint()
                    g2.translate(fullPad - xWhole, fullPad - yWhole)
                    g2.fill(shape)
                }
                // Apply the convolution kernels.
                val tmp = ConvolveOp(hKernel).filter(img, null)
                img = ConvolveOp(vKernel).filter(tmp, img)
                // Cut off the padding that is guaranteed to be empty due to the convolutions' EDGE_ZERO_FILL behavior.
                img = img.getSubimage(halfPad, halfPad, img.width - fullPad, img.height - fullPad)
                // Send the resulting image to the backend.
                val embeddedPic = Picture.Embedded.Raster(Picture.Raster(img))
                materializeEmbeddedPicture(xWhole - halfPad, yWhole - halfPad, 1.0, embeddedPic, draft = false)
            }
        }

    }


    /** Materializes tapes by rendering the tape's thumbnail or a "missing media" placeholder. */
    private interface TapeThumbnailBackend : MaterializationBackend {

        override fun materializeEmbeddedTape(
            x: Double,
            y: Double,
            scaling: Double,
            embeddedTape: Tape.Embedded,
            thumbnail: Picture.Raster?
        ) {
            if (thumbnail != null) {
                val embeddedThumbnail = Picture.Embedded.Raster(thumbnail).withForcedResolution(embeddedTape.resolution)
                materializeEmbeddedPicture(x, y, scaling, embeddedThumbnail, draft = true)
            } else {
                val (w, h) = embeddedTape.resolution
                val rect = Rectangle2D.Double(x, y, w * scaling, h * scaling)
                val coat = Coat.Gradient(
                    Tape.MISSING_MEDIA_TOP_COLOR, Tape.MISSING_MEDIA_BOT_COLOR,
                    Point2D.Double(0.0, rect.minY), Point2D.Double(0.0, rect.maxY)
                )
                materializeShape(rect, coat, fill = true, dash = false, blurRadius = 0.0)
            }
        }

    }


    private class Graphics2DBackend(
        private val g2: Graphics2D,
        private val rasterOutput: Boolean
    ) : RasterBlurBackend, TapeThumbnailBackend {

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean) {
            g2.paint = coat.toPaint()
            if (fill)
                g2.fill(shape)
            else if (dash) {
                val oldStroke = g2.stroke
                g2.stroke =
                    BasicStroke(1f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 8f), 0f)
                g2.draw(shape)
                g2.stroke = oldStroke
            } else
                g2.draw(shape)
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
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
            g2.preserveTransform {
                g2.translate(x, yBaseline)
                g2.scale(scaling)
                materializeShape(text.transformedOutline, coat, fill = true, dash = false)
            }
        }

        override fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: Picture.Embedded, draft: Boolean
        ) {
            when (embeddedPic) {
                is Picture.Embedded.Raster -> g2.preserveTransform {
                    val pic = embeddedPic.picture
                    // When drawing vector graphics, neither care about aligning with the pixel grid nor creating a
                    // scaled copy with the target resolution.
                    if (!rasterOutput) {
                        g2.translate(x, y)
                        g2.scale(embeddedPic.scalingX * scaling)
                        g2.drawImage(pic.img, 0, 0, null)
                        return@preserveTransform
                    }
                    // When drawing to a raster output, align the raster picture with the pixel grid and force the
                    // picture to have integer size to prevent interpolation.
                    g2.translate(x.roundToInt(), y.roundToInt())
                    val w = (embeddedPic.width * scaling).roundToInt()
                    val h = (embeddedPic.height * scaling).roundToInt()
                    // Note: Directly drawing with bilinear or bicubic interpolation exhibits poor quality when
                    // downscaling by more than a factor of two, and Image.getScaledInstance() with SCALE_AREA_AVERAGING
                    // is way too slow, so we use zimg with the Lanczos algorithm instead.
                    // Scaling happens in the sRGB color space, as opposed to the often recommended linear RGB space.
                    // This has a couple of reasons:
                    //   - Converting from the sRGB to a linear transfer characteristic is slow.
                    //   - Scaling in linear RGB is not always advantageous, as is shown here:
                    //     https://entropymine.com/imageworsener/gamma/
                    // Nevertheless, if "draft" is true, directly draw using Java2D's interpolation as it's very fast.
                    if (draft) {
                        // Set the interpolation mode to nearest neighbor to achieve a "pixelated preview" effect and
                        // thereby clearly communicate that this picture is drawn in draft mode.
                        val hint = g2.getRenderingHint(KEY_INTERPOLATION)
                        g2.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                        g2.drawImage(pic.img, 0, 0, w, h, null)
                        g2.setRenderingHint(KEY_INTERPOLATION, hint)
                        return@preserveTransform
                    }
                    var scaledImg = scaledImgCache[pic.img]
                    if (scaledImg == null || scaledImg.width != w || scaledImg.height != h) {
                        scaledImg = Image2ImageConverter(
                            srcResolution = Resolution(pic.img.width, pic.img.height),
                            dstResolution = Resolution(w, h),
                            hasAlpha = pic.img.colorModel.hasAlpha(),
                            isAlphaPremultiplied = pic.img.isAlphaPremultiplied
                        ).convert(pic.img)
                        scaledImgCache[pic.img] = scaledImg
                    }
                    g2.drawImage(scaledImg, 0, 0, null)
                }
                is Picture.Embedded.Vector -> g2.preserveTransform {
                    val pic = embeddedPic.picture
                    g2.translate(x, y)
                    g2.scale(embeddedPic.scaling * scaling)
                    if (embeddedPic.isCropped)
                        g2.translate(-pic.cropX, -pic.cropY)
                    pic.drawTo(g2)
                }
            }
        }

        companion object {
            private val scaledImgCache = ConcurrentHashMap<BufferedImage, BufferedImage>()
        }

    }


    private class PDFBackend(
        private val doc: PDDocument,
        private val page: PDPage,
        private val cs: PDPageContentStream,
    ) : RasterBlurBackend, TapeThumbnailBackend {

        private val csHeight = page.mediaBox.height
        private val docRes = docResMap.computeIfAbsent(doc) { DocRes(doc) }

        private fun materializeShapeWithoutTransforming(shape: Shape, fill: Boolean) {
            val pi = shape.getPathIterator(null)
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

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean) {
            check(!dash) { "The PDF backend does not support dashing." }
            cs.saveGraphicsState()
            setCoat(coat, fill, shape.bounds2D)
            cs.transform(Matrix().apply { translate(0f, csHeight); scale(1f, -1f) })
            materializeShapeWithoutTransforming(shape, fill)
            cs.restoreGraphicsState()
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            cs.saveGraphicsState()
            cs.beginText()

            val pdFont = getPDFont(text.font)
            val glyphs = text.glyphCodes
            val xShifts = FloatArray(glyphs.size - 1) { glyphIdx ->
                val actualWidth = pdFont.getWidth(glyphs[glyphIdx])
                val wantedWidth = ((text.getGlyphOffsetX(glyphIdx + 1) - text.getGlyphOffsetX(glyphIdx)).toFloat()
                        // Convert to the special PDF text coordinates.
                        * 1000f / text.font.size2D)
                actualWidth - wantedWidth
            }

            val textBBox = Rectangle2D.Double(
                x,
                yBaseline - text.heightAboveBaseline * scaling,
                text.width * scaling,
                (text.heightAboveBaseline + text.heightBelowBaseline) * scaling
            )
            val textTx = AffineTransform().apply {
                translate(x, csHeight - yBaseline)
                scale(scaling)
                val t = text.transform
                concatenate(AffineTransform(t.scaleX, t.shearY, -t.shearX, t.scaleY, t.translateX, -t.translateY))
            }
            val coatTx = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
            }

            cs.setFont(pdFont, text.font.size2D)
            setCoat(coat.transform(coatTx), fill = true, textBBox)
            cs.setTextMatrix(Matrix(textTx))
            cs.showGlyphsWithPositioning(glyphs, xShifts, bytesPerGlyph = 2 /* always true for TTF/OTF fonts */)

            cs.endText()
            cs.restoreGraphicsState()
        }

        override fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: Picture.Embedded, draft: Boolean
        ) {
            val mat = Matrix()
            mat.translate(x, csHeight - y - embeddedPic.height)
            mat.scale(scaling)
            when (embeddedPic) {
                is Picture.Embedded.Raster -> {
                    mat.scale(embeddedPic.width, embeddedPic.height)
                    cs.drawImage(docRes.pdImages.computeIfAbsent(embeddedPic.picture, ::makeImageXObject), mat)
                }
                is Picture.Embedded.Vector -> {
                    val pic = embeddedPic.picture
                    mat.scale(embeddedPic.scaling)
                    if (embeddedPic.isCropped)
                        mat.translate(-pic.cropX, pic.cropY + pic.cropHeight - pic.height)
                    cs.saveGraphicsState()
                    cs.transform(mat)
                    cs.drawForm(docRes.pdForms.getOrPut(pic) {
                        when (pic) {
                            is Picture.SVG -> {
                                val g2 = PdfBoxGraphics2D(doc, pic.width.toFloat(), pic.height.toFloat())
                                pic.drawTo(g2)
                                g2.dispose()
                                g2.xFormObject
                            }
                            is Picture.PDF ->
                                pic.import(docRes.layerUtil) ?: return
                        }
                    })
                    cs.restoreGraphicsState()
                }
            }
        }

        private fun makeImageXObject(pic: Picture.Raster): PDImageXObject {
            val img = pic.img
            // Since PDFs are not used for mastering but instead for previews, we lossily compress raster
            // images to produce smaller files.
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
                    return JPEGFactory.createFromImage(doc, colorImg).apply {
                        cosObject.setItem(COSName.SMASK, JPEGFactory.createFromImage(doc, alphaImg))
                    }
                }
            }
            return JPEGFactory.createFromImage(doc, img)
        }

        /** This function expects both the Coat and the bound box to be in global coordinates (but Y is not flipped). */
        private fun setCoat(coat: Coat, fill: Boolean, bbox: Rectangle2D) {
            when (coat) {
                is Coat.Plain -> {
                    val color = coat.color
                    if (fill) cs.setNonStrokingColor(color) else cs.setStrokingColor(color)
                    val extGState = docRes.extGStates.computeIfAbsent(ExtGStateKey(fill, color.alpha)) {
                        PDExtendedGraphicsState().apply {
                            val alpha = color.alpha / 255f
                            if (fill) nonStrokingAlphaConstant = alpha else strokingAlphaConstant = alpha
                        }
                    }
                    cs.setGraphicsStateParameters(extGState)
                }
                is Coat.Gradient -> {
                    // Notice that we do not cache the COS objects we create for gradients. That is because pattern
                    // coordinates are always global to the page irrespective of any user matrix, so we'd need a new
                    // pattern for every place where we want to use it anyway.
                    if (coat.color1.alpha != 255 || coat.color2.alpha != 255) {
                        // First construct a form XObject.
                        val bboxW = bbox.width.toFloat()
                        val bboxH = bbox.height.toFloat()
                        val bboxX = bbox.x.toFloat()
                        val bboxY = csHeight - bbox.y.toFloat() - bboxH
                        val pdTrGroupResources = PDResources()
                        val pdTrGroupAttrs = PDTransparencyGroupAttributes().apply {
                            cosObject.setItem(COSName.TYPE, COSName.GROUP)
                            cosObject.setItem(COSName.CS, COSName.DEVICEGRAY)
                        }
                        val pdTrGroup = PDTransparencyGroup(doc).apply {
                            formType = 1
                            bBox = PDRectangle(bboxX, bboxY, bboxW, bboxH)
                            resources = pdTrGroupResources
                            cosObject.setItem(COSName.GROUP, pdTrGroupAttrs)
                        }
                        // Paint the alpha gradient into the XObject.
                        val trGroupPatternName =
                            pdTrGroupResources.add(makeShadingPattern(coat, forAlpha = true))
                        PDPageContentStream(doc, pdTrGroup, pdTrGroup.stream.createOutputStream()).use { csTr ->
                            csTr.saveGraphicsState()
                            csTr.setPattern(trGroupPatternName, stroking = false)
                            csTr.addRect(bboxX, bboxY, bboxW, bboxH)
                            csTr.fill()
                            csTr.restoreGraphicsState()
                        }
                        // Finally, construct an alpha mask ("soft mask") that uses the XObject we just created.
                        val pdSoftMask = PDSoftMask(COSDictionary()).apply {
                            cosObject.setItem(COSName.TYPE, COSName.MASK)
                            cosObject.setItem(COSName.S, COSName.LUMINOSITY)
                            cosObject.setItem(COSName.G, pdTrGroup)
                        }
                        // Now apply that alpha mask.
                        val extGState = PDExtendedGraphicsState().apply {
                            alphaSourceFlag = false
                            cosObject.setItem(COSName.SMASK, pdSoftMask)
                        }
                        cs.setGraphicsStateParameters(extGState)
                    }

                    val patternName = page.resources.add(makeShadingPattern(coat, forAlpha = false))
                    cs.setPattern(patternName, stroking = !fill)
                }
            }
        }

        private fun makeShadingPattern(coat: Coat.Gradient, forAlpha: Boolean): PDShadingPattern {
            fun makeColorArray(color: Color) = COSArray().apply {
                if (forAlpha)
                    add(COSFloat(color.alpha / 255f))
                else {
                    add(COSFloat(color.red / 255f))
                    add(COSFloat(color.green / 255f))
                    add(COSFloat(color.blue / 255f))
                }
            }

            val cosCoords = COSArray().apply {
                add(COSFloat(coat.point1.x.toFloat()))
                add(COSFloat(csHeight - coat.point1.y.toFloat()))
                add(COSFloat(coat.point2.x.toFloat()))
                add(COSFloat(csHeight - coat.point2.y.toFloat()))
            }
            val pdFunc = PDFunctionType2(COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 2)
                setInt(COSName.N, 1)
                setItem(COSName.DOMAIN, PDRange())
                setItem(COSName.C0, makeColorArray(coat.color1))
                setItem(COSName.C1, makeColorArray(coat.color2))
            })
            val pdShading = PDShadingType2(COSDictionary()).apply {
                shadingType = PDShading.SHADING_TYPE2
                extend = COSArray().apply { add(COSBoolean.TRUE); add(COSBoolean.TRUE) }
                colorSpace = if (forAlpha) PDDeviceGray.INSTANCE else PDDeviceRGB.INSTANCE
                coords = cosCoords
                function = pdFunc
            }
            return PDShadingPattern().apply {
                patternType = 2
                shading = pdShading
            }
        }

        @Suppress("DEPRECATION")
        private fun PDPageContentStream.setPattern(patternName: COSName, stroking: Boolean) {
            val opCS = if (stroking) OperatorName.STROKING_COLORSPACE else OperatorName.NON_STROKING_COLORSPACE
            val opSCN = if (stroking) OperatorName.STROKING_COLOR_N else OperatorName.NON_STROKING_COLOR_N
            appendRawCommands("/Pattern $opCS\n")
            appendCOSName(patternName)
            appendRawCommands(" $opSCN\n")
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
                                LOGGER.error(msg, fontFile, psName)
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
                    LOGGER.error("Cannot load the font file of the font '{}' for PDF embedding.", psName, e)
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
            val pdForms = HashMap<Picture.Vector, PDFormXObject>()
            val layerUtil by lazy { LayerUtility(doc) }
        }

        private data class ExtGStateKey(private val fill: Boolean, private val alpha: Int)

    }


    class PlacedTape(val embeddedTape: Tape.Embedded, val x: Double, val y: Double)

    private class PlacedTapeCollectorBackend : MaterializationBackend {

        val collected = mutableListOf<PlacedTape>()

        override fun materializeEmbeddedTape(
            x: Double, y: Double, scaling: Double, embeddedTape: Tape.Embedded, thumbnail: Picture.Raster?
        ) {
            var res = embeddedTape.resolution
            res = Resolution((res.widthPx * scaling).roundToInt(), (res.heightPx * scaling).roundToInt())
            collected.add(PlacedTape(embeddedTape.copy(resolution = res), x, y))
        }

    }

}
