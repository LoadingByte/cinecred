package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.apache.fontbox.ttf.OTFParser
import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.common.PDRange
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask
import org.apache.pdfbox.util.Matrix
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY8
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.traversal.NodeFilter.SHOW_ELEMENT
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.*
import java.io.OutputStream
import java.lang.Byte.toUnsignedInt
import java.lang.Short.toUnsignedInt
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.invoke.MethodHandles
import java.lang.ref.SoftReference
import java.nio.ByteOrder
import java.nio.file.Path
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import java.util.concurrent.Future
import javax.xml.XMLConstants.XML_NS_URI
import kotlin.math.*


class DeferredImage(var width: Double = 0.0, var height: Y = 0.0.toY()) {

    private val instructions = HashMap<Layer, MutableList<Instruction>>()

    private fun addInstruction(layer: Layer, insn: Instruction) {
        instructions.computeIfAbsent(layer) { mutableListOf() }.add(insn)
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
        color: Color4f, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        drawShape(Coat.Plain(color), shape, x, y, fill, blurRadius, layer)
    }

    fun drawShape(
        coat: Coat, shape: Shape, x: Double, y: Y, fill: Boolean, blurRadius: Double = 0.0, layer: Layer = STATIC
    ) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawShape(x, y, shape, coat, fill, blurRadius))
    }

    fun drawLine(color: Color4f, x1: Double, y1: Y, x2: Double, y2: Y, dash: Boolean = false, layer: Layer = STATIC) {
        if (color.a == 0f) return
        addInstruction(layer, Instruction.DrawLine(x1, y1, x2, y2, color, dash))
    }

    fun drawRect(
        color: Color4f, x: Double, y: Y, width: Double, height: Y, fill: Boolean = false, layer: Layer = STATIC
    ) {
        if (color.a == 0f) return
        addInstruction(layer, Instruction.DrawRect(x, y, width, height, color, fill))
    }

    fun drawText(coat: Coat, text: Text, x: Double, yBaseline: Y, layer: Layer = STATIC) {
        if (!coat.isVisible()) return
        addInstruction(layer, Instruction.DrawText(x, yBaseline, text, coat))
    }

    fun drawEmbeddedPicture(embeddedPic: EmbeddedPicture, x: Double, y: Y, layer: Layer = STATIC) {
        if (embeddedPic.run { width == 0.0 || height == 0.0 }) return
        addInstruction(layer, Instruction.DrawEmbeddedPicture(x, y, embeddedPic))
    }

    fun drawEmbeddedTape(embeddedTape: EmbeddedTape, x: Double, y: Y, layer: Layer = TAPES) {
        if (embeddedTape.resolution.run { widthPx == 0 || heightPx == 0 }) return
        // When the deferred image is materialized later, we'll need the tape's thumbnail, so already start loading it.
        val asyncThumbnail = embeddedTape.tape.getPreviewFrame(embeddedTape.range.start)
        addInstruction(layer, Instruction.DrawEmbeddedTape(x, y, embeddedTape, asyncThumbnail))
    }

    /**
     * Draws the content of this deferred image onto the given [Canvas]. The canvas must be backed by a bitmap. Raster
     * content is aligned with the canvas' pixel grid to prevent interpolation and retain as much quality as possible.
     */
    fun materialize(canvas: Canvas, cache: CanvasMaterializationCache?, layers: List<Layer>) {
        require(canvas.bitmap != null) { "To materialize to an SVG or PDF, use the specialized methods." }
        val backend = CanvasBackend(canvas, cache as CanvasMaterializationCacheImpl?)
        // If only a portion of the deferred image is materialized, cull the rest to improve performance.
        // Notice that because the culling rect is aligned with the pixel grid, we correctly include all content
        // that at least partially lies inside one of the surface's pixels.
        val culling = Rectangle2D.Double(0.0, 0.0, canvas.width, canvas.height)
        materializeDeferredImage(backend, 0.0, 0.0, 1.0, 1.0, culling, this, layers)
    }

    /** Draws the content of this deferred image onto an SVG element. */
    fun materialize(svg: Element, layers: List<Layer>) {
        materializeDeferredImage(SVGBackend(svg), 0.0, 0.0, 1.0, 1.0, null, this, layers)
    }

    /** Draws the content of this deferred onto a PDF page. */
    fun materialize(
        doc: PDDocument,
        page: PDPage,
        cs: PDPageContentStream,
        masterColorSpace: ColorSpace,
        shrinkRasters: Boolean,
        jpegRasters: Boolean,
        rasterizeSVGs: Boolean,
        layers: List<Layer>
    ) {
        val backend = PDFBackend(doc, page, cs, masterColorSpace, shrinkRasters, jpegRasters, rasterizeSVGs)
        materializeDeferredImage(backend, 0.0, 0.0, 1.0, 1.0, null, this, layers)
        backend.end()
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
                universeScaling * insn.universeScaling, elasticScaling * insn.elasticScaling, culling,
                insn.image, listOf(insn.layer)
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
                universeScaling, culling, insn.embeddedTape, insn.asyncThumbnail
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
        if (culling != null) {
            val cx = (culling.x - x - safeBlurRadius) / scaling
            val cy = (culling.y - y - safeBlurRadius) / scaling
            val cw = (culling.width + 2 * safeBlurRadius) / scaling
            val ch = (culling.height + 2 * safeBlurRadius) / scaling
            // Empty rectangles can occur as guides when cells have 0 width, and we want to draw them anyway!
            if (shape is Rectangle2D && shape.isEmpty) {
                if (!(cx + cw > shape.x && cy + ch > shape.y && cx < shape.maxX && cy < shape.maxY)) return
            } else
                if (!shape.intersects(cx, cy, cw, ch)) return
        }
        // We first transform the shape and then draw it without scaling the canvas.
        // This simplifies code in the SVG and PDF backends, and is also required for snapping hairlines to pixels.
        val tx = AffineTransform().apply { translate(x, y); scale(scaling) }
        backend.materializeShape(shape.transformedBy(tx), coat.transform(tx), fill, dash, blurRadius)
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
        embeddedPic: EmbeddedPicture
    ) {
        if (culling != null && !culling.intersects(x, y, embeddedPic.width * scaling, embeddedPic.height * scaling))
            return
        backend.materializeEmbeddedPicture(x, y, scaling, embeddedPic, draft = false)
    }

    private fun materializeEmbeddedTape(
        backend: MaterializationBackend,
        x: Double, y: Double, scaling: Double, culling: Rectangle2D?,
        embeddedTape: EmbeddedTape, asyncThumbnail: Future<Picture.Raster?>
    ) {
        val resolution = embeddedTape.resolution
        if (culling != null && !culling.intersects(x, y, resolution.widthPx * scaling, resolution.heightPx * scaling))
            return
        backend.materializeEmbeddedTape(x, y, scaling, embeddedTape, asyncThumbnail)
    }


    companion object {

        // These common layers are typically used. Additional layers may be defined by users of this class.
        val STATIC = object : Layer {}
        val TAPES = object : Layer {}
        val GUIDES = object : Layer {}

        private fun FloatArray.isFinite(end: Int): Boolean =
            allBetween(0, end, Float::isFinite)

        private fun Coat.isVisible(): Boolean = when (this) {
            is Coat.Plain -> color.a != 0f
            is Coat.Gradient -> color1.a != 0f || color2.a != 0f
        }

        private fun Coat.transform(tx: AffineTransform): Coat = when (this) {
            is Coat.Plain -> this
            is Coat.Gradient -> Coat.Gradient(color1, color2, tx.transform(point1, null), tx.transform(point2, null))
        }

        private fun Coat.toShader() = when (this) {
            is Coat.Plain -> Canvas.Shader.Solid(color)
            // We interpolate Gradients in the sRGB color space instead of a better alternative (like linear RGB or
            // Oklab) for a couple of reasons:
            //   - Gradient interpolation in sRGB is pretty much the default at this point, for better or for worse.
            //     Artists expect it when they specify a gradient.
            //   - Implementing a gradient in linear RGB in a PDF or SVG file is surprisingly difficult. While it may be
            //     possible for PDFs, it seems outright impossible at this point in SVGs; even though the standards are
            //     in place, it's not supported by any browser or viewer.
            //   - Apart from the famous red-to-green case, gradients in sRGB actually often look superior to those
            //     interpolated in linear RGB. This is because sRGB's transfer characteristics kind of model the human
            //     perception of light intensity, so gradients look more perceptually uniform. For examples, see:
            //     https://aras-p.info/blog/2021/11/29/Gradients-in-linear-space-arent-better/
            //   - Still, Oklab seems to be the future of gradient interpolation (i.e., Photoshop already defaults
            //     to it), so maybe we'll switch to it too.
            is Coat.Gradient -> Canvas.Shader.LinearGradient(point1, point2, listOf(color1, color2))
        }

        private fun gaussianStdDev(radius: Double) = radius / 2.0

        private fun embeddedPictureTransform(
            x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture
        ): AffineTransform {
            val transform = AffineTransform.getTranslateInstance(x, y)
            transform.scale(scaling)
            when (embeddedPic.picture) {
                is Picture.Raster -> transform.scale(embeddedPic.scalingX, embeddedPic.scalingY)
                is Picture.Vector -> {
                    transform.scale(embeddedPic.scalingX, embeddedPic.scalingY)
                    if (embeddedPic.isCropped)
                        transform.translate(-embeddedPic.picture.cropX, -embeddedPic.picture.cropY)
                }
            }
            return transform
        }

    }


    interface Layer


    sealed interface Coat {
        class Plain(val color: Color4f) : Coat
        class Gradient(val color1: Color4f, val color2: Color4f, val point1: Point2D, val point2: Point2D) : Coat
    }


    interface Text {

        val width: Double
        val heightAboveBaseline: Double
        val heightBelowBaseline: Double
        /** The outline of the entire text, with the [transform] already applied. */
        val transformedOutline: Shape
        val fontSize: Double
        val glyphCodes: IntArray
        fun getGlyphOffsetX(glyphIdx: Int): Double
        /** Applying this to the drawn [glyphCodes] positioned by [getGlyphOffsetX] yields [transformedOutline]. */
        val transform: AffineTransform
        val fundamentalFontInfo: FundamentalFontInfo

        /** Provides access to fundamental properties of a font face, irrespective of any user configuration. */
        interface FundamentalFontInfo {
            val fontName: String
            val fontFile: Path
            val indexInCollection: Int
            /** Retrieves the outline of a particular glyph, assuming a non-transformed font of the given size. */
            fun getGlyphOutline(glyphCode: Int, fontSize: Double): Shape
        }

    }


    class EmbeddedPicture(
        val picture: Picture,
        width: Double? = null,
        height: Double? = null,
        val isCropped: Boolean = false
    ) {

        val width: Double
        val height: Double

        init {
            require((width == null || width >= 0.0) && (height == null || height >= 0.0))
            require(!isCropped || picture is Picture.Vector) { "Raster pictures cannot be cropped." }
            // Note: Even though the Canvas would align raster pictures with the pixel grid anyway, it is a good idea to
            // already round the embedded size now so that the layout code sees the same size as is later drawn.
            // In addition, this aligns the vector backends with the canvas backend when it comes to embedded size.
            val w = width?.let(::roundIfRaster)
            val h = height?.let(::roundIfRaster)
            this.width = w ?: roundIfRaster(oriWidth * if (h != null) h / oriHeight else 1.0)
            this.height = h ?: roundIfRaster(oriHeight * if (w != null) w / oriWidth else 1.0)
        }

        val scalingX: Double = this.width / oriWidth
        val scalingY: Double = this.height / oriHeight

        private val oriWidth get() = if (picture is Picture.Vector && isCropped) picture.cropWidth else picture.width
        private val oriHeight get() = if (picture is Picture.Vector && isCropped) picture.cropHeight else picture.height
        private fun roundIfRaster(size: Double) = if (picture is Picture.Raster) round(size) else size

    }


    /** @throws IllegalStateException */
    data class EmbeddedTape(
        /** Note: Accessing the metadata of this tape is guaranteed to not throw exceptions. */
        val tape: Tape,
        val resolution: Resolution = tape.spec.resolution,
        val leftMarginFrames: Int = 0,
        val rightMarginFrames: Int = 0,
        val fadeInFrames: Int = 0,
        val fadeInTransition: Transition = Transition.LINEAR,
        val fadeOutFrames: Int = 0,
        val fadeOutTransition: Transition = Transition.LINEAR,
        val range: OpenEndRange<Timecode> = tape.availableRange,
        val align: Align = Align.START
    ) {

        enum class Align { START, MIDDLE, END }

        init {
            require(resolution.run { widthPx >= 0 && heightPx >= 0 })
            require(leftMarginFrames >= 0 && rightMarginFrames >= 0)
            require(fadeInFrames >= 0 && fadeOutFrames >= 0)
            if (tape.fileSeq) require(range.start is Timecode.Frames && range.endExclusive is Timecode.Frames) else
                require(range.start is Timecode.Clock && range.endExclusive is Timecode.Clock)
            val avail = tape.availableRange  // This call is what guarantees that the metadata of "tape" is loaded.
            require(range.start.let { it >= avail.start && it < range.endExclusive })
            require(range.endExclusive.let { it > range.start && it <= avail.endExclusive })
        }

    }


    sealed interface CanvasMaterializationCache {
        companion object {
            operator fun invoke(): CanvasMaterializationCache = CanvasMaterializationCacheImpl()
        }
    }

    private class CanvasMaterializationCacheImpl : CanvasMaterializationCache {
        private val prepPics = Collections.synchronizedMap(WeakHashMap<Picture, SoftReference<Canvas.PreparedBitmap>>())
        // It is vital that this method removes the prepared bitmap and doesn't just retrieve it, because if thread A
        // has it while thread B replaces it with put...(), the bitmap could be closed while thread A is still using it.
        fun popPreparedPicture(picture: Picture): Canvas.PreparedBitmap? = prepPics.remove(picture)?.get()
        fun putPreparedPicture(picture: Picture, prepared: Canvas.PreparedBitmap) {
            prepPics.put(picture, SoftReference(prepared))?.get()?.bitmap?.close()
        }
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
            val x1: Double, val y1: Y, val x2: Double, val y2: Y, val color: Color4f, val dash: Boolean
        ) : Instruction

        class DrawRect(
            val x: Double, val y: Y, val width: Double, val height: Y, val color: Color4f, val fill: Boolean
        ) : Instruction

        class DrawText(
            val x: Double, val yBaseline: Y, val text: Text, val coat: Coat
        ) : Instruction

        class DrawEmbeddedPicture(
            val x: Double, val y: Y, val embeddedPic: EmbeddedPicture
        ) : Instruction

        class DrawEmbeddedTape(
            val x: Double, val y: Y, val embeddedTape: EmbeddedTape, val asyncThumbnail: Future<Picture.Raster?>
        ) : Instruction

    }


    private interface MaterializationBackend {

        // The default implementations skip materialization.
        fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {}
        fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {}
        fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture, draft: Boolean
        ) {
        }

        fun materializeEmbeddedTape(
            x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape, asyncThumbnail: Future<Picture.Raster?>
        )

    }


    /** Materializes tapes by rendering the tape's thumbnail or a "missing media" placeholder. */
    private interface TapeThumbnailBackend : MaterializationBackend {

        override fun materializeEmbeddedTape(
            x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape, asyncThumbnail: Future<Picture.Raster?>
        ) {
            val thumbnail = try {
                asyncThumbnail.get()
            } catch (_: Exception) {
                null
            }
            val (w, h) = embeddedTape.resolution
            if (thumbnail != null) {
                val embeddedThumbnail = EmbeddedPicture(thumbnail, w.toDouble(), h.toDouble())
                // Set the draft=true, which sets the interpolation mode to nearest neighbor to achieve a "pixelated
                // preview" effect and thereby clearly communicate that the thumbnail is just a preview.
                materializeEmbeddedPicture(x, y, scaling, embeddedThumbnail, draft = true)
            } else {
                val rect = Rectangle2D.Double(x, y, w * scaling, h * scaling)
                val coat = Coat.Gradient(
                    Color4f.MISSING_MEDIA_TOP, Color4f.MISSING_MEDIA_BOT,
                    Point2D.Double(0.0, rect.minY), Point2D.Double(0.0, rect.maxY)
                )
                materializeShape(rect, coat, fill = true, dash = false, blurRadius = 0.0)
            }
        }

    }


    private class CanvasBackend(
        private val canvas: Canvas,
        private val cache: CanvasMaterializationCacheImpl?
    ) : TapeThumbnailBackend {

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            if (fill)
                canvas.fillShape(shape, coat.toShader(), blurSigma = gaussianStdDev(blurRadius))
            else {
                val dashPattern = if (dash) floatArrayOf(4f, 8f) else null
                // Note: A stroke width of 0f makes Skia draw hairlines, which we desire for our layout guides.
                val stroke = BasicStroke(0f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f, dashPattern, 0f)
                // Snap the shape's coordinates to the nearest pixel center. This makes our hairlines very crisp.
                val pi = shape.getPathIterator(null)
                val s = Path2D.Double(pi.windingRule)
                val c = DoubleArray(6)
                while (!pi.isDone) {
                    when (pi.currentSegment(c)) {
                        PathIterator.SEG_MOVETO -> s.moveTo(snap(c[0]), snap(c[1]))
                        PathIterator.SEG_LINETO -> s.lineTo(snap(c[0]), snap(c[1]))
                        PathIterator.SEG_QUADTO -> s.quadTo(snap(c[0]), snap(c[1]), snap(c[2]), snap(c[3]))
                        PathIterator.SEG_CUBICTO ->
                            s.curveTo(snap(c[0]), snap(c[1]), snap(c[2]), snap(c[3]), snap(c[4]), snap(c[5]))
                        PathIterator.SEG_CLOSE -> s.closePath()
                    }
                    pi.next()
                }
                canvas.strokeShape(s, stroke, coat.toShader(), blurSigma = gaussianStdDev(blurRadius))
            }
        }

        private fun snap(coordinate: Double) = ceil(coordinate) - 0.5

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            // We render the text by first converting the string to a path via FormattedString and then
            // filling that path. This has the following vital advantages:
            //   - We can render using Skia while still using AWT's excellent text layout capabilities.
            //   - Native text rendering usually applies hinting, which aligns each glyph at pixel
            //     boundaries. To achieve this, glyphs are slightly shifted to the left or right. This
            //     leads to inconsistent glyph spacing, which is acceptable for desktop purposes in
            //     exchange for higher readability, but not acceptable in a movie context. By converting
            //     the text layout to a path and then filling that path, we avoid calling the native text
            //     renderer and instead call the regular vector graphics renderer, which renders the glyphs
            //     at the exact positions where the text layouter has put them, without applying the
            //     counterproductive glyph shifting.
            //   - Vector-based means of imaging like SVG exactly match the raster-based means.
            // For these advantages, we put up with the following disadvantages:
            //   - Since the glyphs are no longer aligned at pixel boundaries, heavier antialiasing kicks
            //     in, leading to the rendered text sometimes appearing more blurry. However, this is an
            //     inherent disadvantage of rendering text with perfect glyph spacing and is typically
            //     acceptable in a movie context.
            val transform = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
            }
            canvas.fillShape(text.transformedOutline, coat.toShader(), transform = transform)
        }

        override fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture, draft: Boolean
        ) {
            val pic = embeddedPic.picture
            val transform = embeddedPictureTransform(x, y, scaling, embeddedPic)
            // If we cache rendered vector graphics, we want to reuse them as often as possible. By aligning
            // them with the pixel grid, they will always be reusable unless the scaling changes.
            if (cache != null && pic is Picture.Vector) {
                val tx = transform.translateX.let { round(it) - it }
                val ty = transform.translateY.let { round(it) - it }
                transform.preConcatenate(AffineTransform.getTranslateInstance(tx, ty))
            }
            val prep = pic.prepareAsBitmap(canvas, if (draft) null else transform, cache?.popPreparedPicture(pic))
                ?: return
            canvas.drawImage(
                prep.bitmap ?: return, prep.promiseOpaque, promiseClamped = true, nearestNeighbor = draft,
                transform = if (draft) transform else prep.transform
            )
            // Put the bitmap into the cache only after we're done drawing, because as soon as it's in there, it could
            // be closed by another thread.
            cache?.putPreparedPicture(pic, prep)
        }

    }


    // Note: SVG blending is always in sRGB and there's no way to change that, so this backend doesn't accept a color
    // space parameter. Technically, one could use SVG filters to at least blend in linear light, but that's very
    // convoluted and still doesn't give us general color space support.
    private class SVGBackend(private val svg: Element) : TapeThumbnailBackend {

        private val doc get() = svg.ownerDocument

        private val defs by lazy {
            doc.createElementNS(SVG_NS_URI, "defs").also { svg.insertBefore(it, svg.firstChild) }
        }
        private val glyphPathIds = HashMap<Pair<String /* font name */, Int /* glyph code */>, String?>()
        private val picElementIds = HashMap<Picture, String>()
        private var gradientCtr = 0
        private val gradientIds = HashMap<Pair<Color4f, Color4f>, String>()
        private val blurFilterIds = HashMap<Double, String>()

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            check(!dash) { "The SVG backend does not support dashing." }
            val path = makePath(shape) ?: return
            applyCoat(path, coat, fill)

            if (blurRadius > 0.0) {
                val blurFilterId = blurFilterIds.computeIfAbsent(blurRadius) {
                    val filter = doc.createElementNS(SVG_NS_URI, "filter")
                    val id = "blur${blurFilterIds.size + 1}"
                    filter.setAttribute("id", id)
                    filter.appendChild(doc.createElementNS(SVG_NS_URI, "feGaussianBlur").apply {
                        setAttribute("stdDeviation", F.format(gaussianStdDev(blurRadius)))
                    })
                    defs.appendChild(filter)
                    id
                }
                path.setAttribute("filter", "url(#$blurFilterId)")
            }

            svg.appendChild(path)
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            val defFontSize = 12.0
            val defToUseScaling = text.fontSize / defFontSize

            val textOnlyTx = AffineTransform().apply {
                scale(defToUseScaling)
                concatenate(text.transform)
            }
            val textTx = AffineTransform().apply {
                translate(x, yBaseline)
                scale(scaling)
                concatenate(textOnlyTx)
            }
            // Compute the coat transform before processing any glyphs. This way, if this calculation fails, we don't
            // add orphaned glyph defs that might never be used in the SVG.
            val coatTx = try {
                textOnlyTx.createInverse()
            } catch (_: NoninvertibleTransformException) {
                // If the transform's determinant is 0, we try to fill a collapsed shape, so just abort.
                return
            }

            val g = doc.createElementNS(SVG_NS_URI, "g")
            g.setAttribute("transform", transformAttr(textTx))

            val fontName = text.fundamentalFontInfo.fontName
            for ((glyphIdx, glyphCode) in text.glyphCodes.withIndex()) {
                val use = doc.createElementNS(SVG_NS_URI, "use")
                val glyphPathId = glyphPathIds.computeIfAbsent(Pair(fontName, glyphCode)) {
                    val id = "glyph${glyphPathIds.size + 1}"
                    val glyphOutline = text.fundamentalFontInfo.getGlyphOutline(glyphCode, defFontSize)
                    defs.appendChild((makePath(glyphOutline) ?: return@computeIfAbsent null).apply {
                        setAttribute("id", id)
                    })
                    id
                } ?: continue
                use.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$glyphPathId")
                use.setAttribute("x", F.format(text.getGlyphOffsetX(glyphIdx) / defToUseScaling))
                g.appendChild(use)
            }

            if (g.hasChildNodes()) {
                // We wait with applying the coat until we are sure that g will actually be added to the tree.
                // Otherwise, we could end up creating orphaned gradient defs.
                applyCoat(g, coat.transform(coatTx), fill = true)
                svg.appendChild(g)
            }
        }

        private fun applyCoat(coatedElement: Element, coat: Coat, fill: Boolean) {
            when (coat) {
                is Coat.Plain -> {
                    val prefix = if (fill) "fill" else {
                        coatedElement.setAttribute("fill", "none")
                        "stroke"
                    }
                    coatedElement.setAttribute(prefix, coat.color.toSRGBHexString())
                    if (coat.color.a != 1f)
                        coatedElement.setAttribute("$prefix-opacity", F.format(coat.color.a.toDouble()))
                }
                is Coat.Gradient -> {
                    val gradientId = "gradient${++gradientCtr}"
                    coatedElement.setAttribute(if (fill) "fill" else "stroke", "url(#$gradientId)")
                    val key = Pair(coat.color1, coat.color2)
                    defs.appendChild(makeLinearGradient(coat, gradientIds[key]).apply {
                        setAttribute("id", gradientId)
                    })
                    gradientIds.putIfAbsent(key, gradientId)
                }
            }
        }

        override fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture, draft: Boolean
        ) {
            val use = doc.createElementNS(SVG_NS_URI, "use")

            val picElementId = picElementIds.computeIfAbsent(embeddedPic.picture) {
                val id = "picture${picElementIds.size + 1}"
                defs.appendChild(makePictureElement(embeddedPic.picture, id))
                id
            }
            use.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$picElementId")
            use.setAttribute("transform", transformAttr(embeddedPictureTransform(x, y, scaling, embeddedPic)))

            svg.appendChild(use)
        }

        private fun makePath(shape: Shape): Element? = when (shape) {
            is Rectangle2D -> if (shape.isEmpty) null else doc.createElementNS(SVG_NS_URI, "rect").apply {
                setAttribute("x", F.format(shape.x))
                setAttribute("y", F.format(shape.y))
                setAttribute("width", F.format(shape.width))
                setAttribute("height", F.format(shape.height))
            }
            else -> {
                val d = StringBuilder()
                val pi = shape.getPathIterator(null)
                val coords = DoubleArray(6)
                while (!pi.isDone) {
                    when (pi.currentSegment(coords)) {
                        PathIterator.SEG_MOVETO ->
                            d.append(" M ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                        PathIterator.SEG_LINETO ->
                            d.append(" L ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                        PathIterator.SEG_QUADTO ->
                            d.append(" Q ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                                .append(" ").append(F.format(coords[2])).append(" ").append(F.format(coords[3]))
                        PathIterator.SEG_CUBICTO ->
                            d.append(" C ").append(F.format(coords[0])).append(" ").append(F.format(coords[1]))
                                .append(" ").append(F.format(coords[2])).append(" ").append(F.format(coords[3]))
                                .append(" ").append(F.format(coords[4])).append(" ").append(F.format(coords[5]))
                        PathIterator.SEG_CLOSE ->
                            d.append(" Z")
                    }
                    pi.next()
                }
                if (d.isEmpty()) null else
                    doc.createElementNS(SVG_NS_URI, "path").apply { setAttribute("d", d.substring(1)) }
            }
        }

        private fun makeLinearGradient(coat: Coat.Gradient, refStopsFromId: String?): Element {
            val linearGradient = doc.createElementNS(SVG_NS_URI, "linearGradient")
            linearGradient.setAttribute("gradientUnits", "userSpaceOnUse")
            linearGradient.setAttribute("x1", F.format(coat.point1.x))
            linearGradient.setAttribute("y1", F.format(coat.point1.y))
            linearGradient.setAttribute("x2", F.format(coat.point2.x))
            linearGradient.setAttribute("y2", F.format(coat.point2.y))
            if (refStopsFromId != null)
                linearGradient.setAttributeNS(XLINK_NS_URI, "xlink:href", "#$refStopsFromId")
            else {
                linearGradient.appendChild(makeGradientStop("0", coat.color1))
                linearGradient.appendChild(makeGradientStop("1", coat.color2))
            }
            return linearGradient
        }

        private fun makeGradientStop(offset: String, color: Color4f): Element {
            return doc.createElementNS(SVG_NS_URI, "stop").apply {
                setAttribute("offset", offset)
                setAttribute("stop-color", color.toSRGBHexString())
                if (color.a != 1f)
                    setAttribute("stop-opacity", F.format(color.a.toDouble()))
            }
        }

        private fun makePictureElement(pic: Picture, picElementId: String): Element {
            val picElement = when (pic) {
                is Picture.Raster -> {
                    // Use sRGB for raster images embedded into the SVG.
                    val transparent = pic.bitmap.spec.representation.alpha != Bitmap.Alpha.OPAQUE
                    val png = BitmapWriter.PNG(Bitmap.PixelFormat.Family.RGB, transparent, ColorSpace.SRGB)
                        .convertAndWrite(pic.bitmap)
                    val data = Base64.getEncoder().encodeToString(png)
                    val image = doc.createElementNS(SVG_NS_URI, "image")
                    image.setAttributeNS(XLINK_NS_URI, "xlink:href", "data:image/png;base64,$data")
                    image
                }
                is Picture.SVG -> {
                    val picSVG = pic.import(doc)
                    // If the nested SVG has a viewBox, it must also specify its width and height, or else it vanishes.
                    picSVG.setAttribute("width", F.format(pic.width))
                    picSVG.setAttribute("height", F.format(pic.height))
                    // This attribute messes up our formatting and is deprecated anyway.
                    picSVG.removeAttributeNS(XML_NS_URI, "space")
                    // Mangle IDs to ensure they are unique to the picture.
                    val mangling = HashMap<String, String>()
                    picSVG.forEachNodeInSubtree(SHOW_ELEMENT) { elem ->
                        elem as Element
                        val id = elem.getAttribute("id")
                        if (id.isNotEmpty()) {
                            val mangledId = "$picElementId-$id"
                            mangling["#$id"] = "#$mangledId"
                            elem.setAttribute("id", mangledId)
                        }
                    }
                    picSVG.forEachNodeInSubtree(SHOW_ELEMENT) { node ->
                        val attrs = node.attributes
                        for (idx in 0..<attrs.length) {
                            val attr = attrs.item(idx) as Attr
                            val value = attr.value
                            var mangledValue = value
                            for ((old, new) in mangling)
                                mangledValue = mangledValue.replace(old, new)
                            if (value != mangledValue)
                                attr.value = mangledValue
                        }
                    }
                    picSVG
                }
                is Picture.PDF -> {
                    setNativeNumericLocaleToC()
                    val canvas = Canvas.forSVG(pic.width, pic.height)
                    pic.drawTo(canvas)
                    return makePictureElement(Picture.SVG.load(canvas.closeAndGetOutput()), picElementId)
                }
            }
            picElement.setAttribute("id", picElementId)
            return picElement
        }

        private fun transformAttr(tx: AffineTransform): String {
            val m00 = F.format(tx.scaleX)
            val m10 = F.format(tx.shearY)
            val m01 = F.format(tx.shearX)
            val m11 = F.format(tx.scaleY)
            val m02 = F.format(tx.translateX)
            val m12 = F.format(tx.translateY)
            return "matrix($m00 $m10 $m01 $m11 $m02 $m12)"
        }

        companion object {
            private val F = DecimalFormat("#.####", DecimalFormatSymbols(Locale.ROOT))
        }

    }


    private class PDFBackend(
        private val doc: PDDocument,
        private val page: PDPage,
        private val cs: PDPageContentStream,
        private val masterColorSpace: ColorSpace,
        private val shrinkRasters: Boolean,
        private val jpegRasters: Boolean,
        private val rasterizeSVGs: Boolean
    ) : TapeThumbnailBackend {

        private val csHeight = page.mediaBox.height
        private val docRes = docResMap.computeIfAbsent(doc) { DocRes(doc) }

        init {
            page.cosObject.setItem(COSName.GROUP, PDTransparencyGroupAttributes().apply {
                cosObject.setItem(COSName.TYPE, COSName.GROUP)
                cosObject.setItem(COSName.CS, obtainICCBasedCS(masterColorSpace))
            })
        }

        private fun materializeShapeWithoutTransforming(shape: Shape, fill: Boolean) {
            if (shape is Rectangle2D) {
                cs.addRect(shape.x.toFloat(), shape.y.toFloat(), shape.width.toFloat(), shape.height.toFloat())
                if (fill) cs.fill() else cs.stroke()
                return
            }

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

        override fun materializeShape(shape: Shape, coat: Coat, fill: Boolean, dash: Boolean, blurRadius: Double) {
            check(!dash) { "The PDF backend does not support dashing." }
            if (blurRadius > 0.0) {
                check(fill) { "The PDF backend does not support blurring stroke shapes." }
                // We add this padding on every side to have enough room for the blur to spill into.
                val pad = floor(blurRadius).toInt()
                // Determine the bounds of the shape, and the whole and fractional parts of the shape's coordinates.
                val bounds = shape.bounds2D
                val xWhole = floor(bounds.x)
                val yWhole = floor(bounds.y)
                val xFrac = bounds.x - xWhole
                val yFrac = bounds.y - yWhole
                // Draw the shape onto a bitmap in a blurred fashion.
                val w = ceil(xFrac + bounds.width).toInt() + 2 * pad
                val h = ceil(yFrac + bounds.height).toInt() + 2 * pad
                val rep = Canvas.compatibleRepresentation(masterColorSpace)
                Bitmap.allocate(Bitmap.Spec(Resolution(w, h), rep)).use { bitmap ->
                    Canvas.forBitmap(bitmap.zero()).use { canvas ->
                        canvas.fillShape(
                            shape, coat.toShader(), blurSigma = gaussianStdDev(blurRadius),
                            transform = AffineTransform.getTranslateInstance(pad - xWhole, pad - yWhole)
                        )
                    }
                    // Place the bitmap in the PDF.
                    val embeddedPic = EmbeddedPicture(Picture.Raster(bitmap))
                    materializeEmbeddedPicture(xWhole - pad, yWhole - pad, 1.0, embeddedPic, draft = false)
                }
                return
            }
            cs.saveGraphicsState()
            setCoat(coat, fill, shape.bounds2D)
            cs.transform(Matrix().apply { translate(0f, csHeight); scale(1f, -1f) })
            materializeShapeWithoutTransforming(shape, fill)
            cs.restoreGraphicsState()
        }

        override fun materializeText(x: Double, yBaseline: Double, scaling: Double, text: Text, coat: Coat) {
            val pdFont = getPDFont(text.fundamentalFontInfo) ?: return

            cs.saveGraphicsState()
            cs.beginText()

            val glyphs = text.glyphCodes
            val xShifts = FloatArray(glyphs.size - 1) { glyphIdx ->
                val actualWidth = pdFont.getWidth(glyphs[glyphIdx])
                val wantedWidth = ((text.getGlyphOffsetX(glyphIdx + 1) - text.getGlyphOffsetX(glyphIdx)).toFloat()
                        // Convert to the special PDF text coordinates.
                        * 1000f / text.fontSize.toFloat())
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

            cs.setFont(pdFont, text.fontSize.toFloat())
            setCoat(coat.transform(coatTx), fill = true, textBBox)
            cs.setTextMatrix(Matrix(textTx))
            cs.showGlyphsWithPositioning(glyphs, xShifts, bytesPerGlyph = 2 /* always true for TTF/OTF fonts */)

            cs.endText()
            cs.restoreGraphicsState()
        }

        override fun materializeEmbeddedPicture(
            x: Double, y: Double, scaling: Double, embeddedPic: EmbeddedPicture, draft: Boolean
        ) {
            val pic = embeddedPic.picture
            val mat = Matrix()
            mat.translate(x, csHeight - y - embeddedPic.height)
            mat.scale(scaling)
            if (rasterizeSVGs && pic is Picture.SVG) {
                if (embeddedPic.isCropped) {
                    val e = embeddedPic
                    mat.translate(e.scalingX * -pic.cropX, e.scalingY * (pic.cropY + pic.cropHeight - pic.height))
                    mat.scale(pic.width / pic.cropWidth, pic.height / pic.cropHeight)
                }
                mat.scale(embeddedPic.width, embeddedPic.height)
                cs.drawImage(docRes.pdImages.computeIfAbsent(pic) { PDImageXObject(doc) }, mat)
                val w = ceil(embeddedPic.scalingX * pic.width * scaling).toInt()
                val h = ceil(embeddedPic.scalingY * pic.height * scaling).toInt()
                docRes.pdImageResolutions.computeIfAbsent(pic) { mutableListOf() }.add(Resolution(w, h))
                return
            }
            when (pic) {
                is Picture.Raster -> {
                    mat.scale(embeddedPic.width, embeddedPic.height)
                    cs.drawImage(docRes.pdImages.computeIfAbsent(pic) {
                        // Note: The first occurrence decides whether a picture is in draft-mode or not, but that's fine
                        // since draft true only for tape thumbnails; hence a single picture never mixes both modes.
                        PDImageXObject(doc).apply { interpolate = !draft }
                    }, mat)
                    val w = ceil(embeddedPic.width * scaling).toInt()
                    val h = ceil(embeddedPic.height * scaling).toInt()
                    docRes.pdImageResolutions.computeIfAbsent(pic) { mutableListOf() }.add(Resolution(w, h))
                }
                is Picture.Vector -> {
                    mat.scale(embeddedPic.scalingX, embeddedPic.scalingY)
                    if (embeddedPic.isCropped)
                        mat.translate(-pic.cropX, pic.cropY + pic.cropHeight - pic.height)
                    cs.saveGraphicsState()
                    cs.transform(mat)
                    cs.drawForm(docRes.pdForms.computeIfAbsent(pic) {
                        when (pic) {
                            is Picture.SVG -> {
                                val canvas = Canvas.forPDF(pic.width, pic.height, ColorSpace.SRGB)
                                pic.drawTo(canvas)
                                Picture.PDF.load(canvas.closeAndGetOutput()).import(docRes.layerUtil).apply {
                                    // Set the transparency group's blending color space to sRGB.
                                    group.cosObject.setItem(COSName.CS, obtainICCBasedCS(ColorSpace.SRGB))
                                }
                            }
                            is Picture.PDF ->
                                pic.import(docRes.layerUtil)
                        }
                    })
                    cs.restoreGraphicsState()
                }
            }
        }

        /** This function expects both the Coat and the bound box to be in global coordinates (but Y is not flipped). */
        private fun setCoat(coat: Coat, fill: Boolean, bbox: Rectangle2D) {
            when (coat) {
                is Coat.Plain -> {
                    val color = coat.color.convert(masterColorSpace, clamp = true)
                    val pdColor = PDColor(color.rgb(), obtainICCBasedCS(masterColorSpace))
                    if (fill) cs.setNonStrokingColor(pdColor) else cs.setStrokingColor(pdColor)
                    if (color.a != 1f) cs.setGraphicsStateParameters(makeExtGState(fill, color.a))
                }
                is Coat.Gradient -> {
                    // Notice that we do not cache the COS objects we create for gradients. That is because pattern
                    // coordinates are always global to the page irrespective of any user matrix, so we'd need a new
                    // pattern for every place where we want to use it anyway.
                    if (coat.color1.a != 1f && coat.color1.a == coat.color2.a)
                        cs.setGraphicsStateParameters(makeExtGState(fill, coat.color1.a))
                    else if (coat.color1.a != 1f || coat.color2.a != 1f) {
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
                        PDFormContentStream(pdTrGroup).use { csTr ->
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

        private fun makeExtGState(fill: Boolean, alpha: Float) =
            docRes.extGStates.computeIfAbsent(ExtGStateKey(fill, alpha)) {
                PDExtendedGraphicsState().apply {
                    if (fill) nonStrokingAlphaConstant = alpha else strokingAlphaConstant = alpha
                }
            }

        private fun makeShadingPattern(coat: Coat.Gradient, forAlpha: Boolean): PDShadingPattern {
            fun makeColorArray(color: Color4f) = COSArray().apply {
                if (forAlpha)
                    add(COSFloat(color.a))
                else {
                    val c = color.convert(masterColorSpace, clamp = true)
                    add(COSFloat(c.r))
                    add(COSFloat(c.g))
                    add(COSFloat(c.b))
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
                colorSpace = if (forAlpha) PDDeviceGray.INSTANCE else obtainICCBasedCS(masterColorSpace)
                coords = cosCoords
                function = pdFunc
            }
            return PDShadingPattern().apply {
                patternType = 2
                shading = pdShading
            }
        }

        private fun Any /* PD(Page|Form)ContentStream */.setPattern(patternName: COSName, stroking: Boolean) {
            val opCS = if (stroking) OperatorName.STROKING_COLORSPACE else OperatorName.NON_STROKING_COLORSPACE
            val opSCN = if (stroking) OperatorName.STROKING_COLOR_N else OperatorName.NON_STROKING_COLOR_N
            appendRawCommands(this, "/Pattern $opCS\n")
            appendCOSName(this, patternName)
            appendRawCommands(this, " $opSCN\n")
        }

        private fun getPDFont(fundamentalFontInfo: Text.FundamentalFontInfo): PDFont? {
            val psName = fundamentalFontInfo.fontName

            if (psName !in docRes.pdFonts)
                try {
                    val fontFile = fundamentalFontInfo.fontFile.toFile()
                    val ttf = when (String(fontFile.inputStream().use { it.readNBytes(4) })) {
                        // TrueType Collection
                        "ttcf" -> {
                            // Read the entire file into a byte array.
                            val bI = fontFile.readBytes()
                            val numFonts = INT.get(bI, 8) as Int
                            require(numFonts in 1..1024) { "Illegal number of fonts: $numFonts" }
                            val fontIdx = fundamentalFontInfo.indexInCollection
                            require(fontIdx in 0..<numFonts) { "Font index $fontIdx exceeds font count $numFonts." }
                            // Create a byte array bO that is an extracted version of the font. For that, we copy the
                            // font's directory and all of its tables, and then adjust the table offsets in the
                            // directory. We don't need to worry about offsets inside tables because those are relative
                            // to the table's address.
                            val fontOff = INT.get(bI, 12 + fontIdx * 4) as Int
                            val numTabs = toUnsignedInt(SHORT.get(bI, fontOff + 4) as Short)
                            val dirLen = 12 + numTabs * 16
                            val tabLens = IntArray(numTabs) { t ->
                                ceilDiv(INT.get(bI, fontOff + 12 + t * 16 + 12) as Int, 4) * 4
                            }
                            val bO = ByteArray(dirLen + tabLens.sum())
                            System.arraycopy(bI, fontOff, bO, 0, dirLen)
                            var tabPtr = dirLen
                            for (t in 0..<numTabs) {
                                val p = 12 + t * 16 + 8
                                INT.set(bO, p, tabPtr)
                                System.arraycopy(bI, INT.get(bI, fontOff + p) as Int, bO, tabPtr, tabLens[t])
                                tabPtr += tabLens[t]
                            }
                            // Parse the newly created font.
                            val parser = if (String(bO, 0, 4) == "OTTO") OTFParser() else TTFParser()
                            parser.parse(RandomAccessReadBuffer(bO))
                        }
                        // OpenType Font
                        "OTTO" ->
                            OTFParser().parse(RandomAccessReadBufferedFile(fontFile))
                        // TrueType Font
                        else ->
                            // Here, one could theoretically enable embedSubset. However, our string writing logic
                            // directly writes font-specific glyphs (in contrast to unicode codepoints) since this is
                            // the only way we can leverage the power of TextLayout and also the only way we can write
                            // some special ligatures that have no unicode codepoint. Now, since PDFBox's font
                            // subsetting mechanism only works on codepoints and not on glyphs, we cannot use it.
                            TTFParser().parse(RandomAccessReadBufferedFile(fontFile))
                    }
                    docRes.pdFonts[psName] = PDType0Font.load(doc, ttf, false)
                } catch (e: Exception) {
                    LOGGER.error("Cannot load the font file of the font '{}' for PDF embedding.", psName, e)
                    // Memoize null to avoid trying to load the font file again.
                    docRes.pdFonts[psName] = null
                }

            return docRes.pdFonts.getValue(psName)
        }

        fun end() {
            for ((pic, pdImage) in docRes.pdImages)
                endImage(pic, pdImage, docRes.pdImageResolutions.getValue(pic))
        }

        private fun endImage(pic: Picture, pdImage: PDImageXObject, resolutions: List<Resolution>) {
            // If the picture shall shrink or is an SVG, find the max res, which is 2x the largest embedded res (so that
            // there's still enough detail when zooming in). However, if the original picture is actually smaller than
            // that, don't blow it up. Notice that reducing the resolution asymmetrically is fine because PDF squeezes
            // all images into a 1x1 square anyway.
            val maxEmbRes = resolutions.reduce { (w1, h1), (w2, h2) -> Resolution(max(w1, w2), max(h1, h2)) }
            val maxRes = Resolution(maxEmbRes.widthPx * 2, maxEmbRes.heightPx * 2)
            // Obtain the storage color space and planar float bitmap.
            val colorSpace: ColorSpace
            var bitmap: Bitmap
            when (pic) {
                // If the picture is a raster, directly use the raster or shrink it if necessary.
                is Picture.Raster -> {
                    colorSpace = masterColorSpace
                    bitmap = pic.bitmap
                    if (shrinkRasters) {
                        val (picRes, rep) = pic.bitmap.spec
                        val res = Resolution(min(maxRes.widthPx, picRes.widthPx), min(maxRes.heightPx, picRes.heightPx))
                        // If the maximum resolution is actually lower than the original one, shrink the bitmap.
                        if (res != picRes) {
                            bitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
                            BitmapConverter.convert(pic.bitmap, bitmap)
                        }
                    }
                }
                // If the picture is an SVG, rasterize it.
                is Picture.SVG -> {
                    colorSpace = ColorSpace.SRGB
                    val tr = AffineTransform.getScaleInstance(maxRes.widthPx / pic.width, maxRes.heightPx / pic.height)
                    Bitmap.allocate(Bitmap.Spec(maxRes, Canvas.compatibleRepresentation(colorSpace))).use { canvasBmp ->
                        Canvas.forBitmap(canvasBmp.zero()).use { canvas -> pic.drawTo(canvas, tr) }
                        bitmap = Picture.Raster(canvasBmp).bitmap
                    }
                }
                is Picture.PDF -> throw IllegalStateException()
            }
            // Now split the color and alpha components into a color image...
            populateImageXObject(pdImage, bitmap, colorSpace, obtainICCBasedCS(colorSpace))
            // ... and a grayscale alpha image. We can use alphaPlaneView() because the bitmap is planar.
            if (bitmap.spec.representation.alpha != Bitmap.Alpha.OPAQUE) {
                val pdAlphaImage = PDImageXObject(doc)
                bitmap.alphaPlaneView().use { populateImageXObject(pdAlphaImage, it, null, PDDeviceGray.INSTANCE) }
                pdImage.cosObject.setItem(COSName.SMASK, pdAlphaImage)
            }
            // If we have allocated an intermediate bitmap, free it again.
            if (pic !is Picture.Raster || bitmap != pic.bitmap)
                bitmap.close()
        }

        private fun populateImageXObject(pdImage: PDImageXObject, bitmap: Bitmap, cs: ColorSpace?, pdCS: PDColorSpace) {
            val (res, rep) = bitmap.spec
            pdImage.apply {
                bitsPerComponent = 8
                width = res.widthPx
                height = res.heightPx
                colorSpace = pdCS
            }
            val stream = pdImage.cosObject
            if (jpegRasters) {
                val jpeg = BitmapWriter.JPEG(rep.pixelFormat.family, cs).convertAndWrite(bitmap)
                stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
                stream.createRawOutputStream().use { it.write(jpeg) }
            } else {
                val pxFmt = Bitmap.PixelFormat.of(if (cs == null) AV_PIX_FMT_GRAY8 else AV_PIX_FMT_RGB24)
                val byteBmp = Bitmap.allocate(Bitmap.Spec(res, Bitmap.Representation(pxFmt, cs, Bitmap.Alpha.OPAQUE)))
                BitmapConverter.convert(bitmap, byteBmp)
                stream.setItem(COSName.FILTER, COSName.FLATE_DECODE)
                if (cs != null) {
                    stream.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                        setItem(COSName.PREDICTOR, COSInteger.get(15L))
                        setItem(COSName.COLORS, COSInteger.get(3L))
                        setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8L))
                        setItem(COSName.COLUMNS, COSInteger.get(res.widthPx.toLong()))
                    })
                    stream.createOutputStream().use { encodeRGB24Losslessly(byteBmp, it) }
                } else
                    stream.createOutputStream().use { it.write(byteBmp.getB(res.widthPx)) }
            }
        }

        // Adapted from PDFBox's LosslessFactory.PredictorEncoder.
        // Note that we've measure that encoder to be superior to the PDFBox's package-private PNGConverter.
        private fun encodeRGB24Losslessly(bitmap: Bitmap, os: OutputStream) {
            val (w, h) = bitmap.spec.resolution
            val seg = bitmap.memorySegment(0)
            val ls = bitmap.linesize(0).toLong()

            // c b
            // a x
            // x is the current pixel.
            val xRGB = ByteArray(3)
            val aRGB = ByteArray(3)
            val bRGB = ByteArray(3)
            val cRGB = ByteArray(3)

            val rawLen = 1 + w * 3
            val rawNone = ByteArray(rawLen).also { it[0] = 0 }
            val rawSub = ByteArray(rawLen).also { it[0] = 1 }
            val rawUp = ByteArray(rawLen).also { it[0] = 2 }
            val rawAvg = ByteArray(rawLen).also { it[0] = 3 }
            val rawPaeth = ByteArray(rawLen).also { it[0] = 4 }

            for (y in 0..<h) {
                aRGB.fill(0)
                cRGB.fill(0)
                var ib = (y - 1) * ls
                var ix = y * ls
                var r = 1
                repeat(w) {
                    if (y != 0)
                        MemorySegment.copy(seg, JAVA_BYTE, ib, bRGB, 0, 3); ib += 3L
                    MemorySegment.copy(seg, JAVA_BYTE, ix, xRGB, 0, 3); ix += 3L
                    for (channel in 0..2) {
                        val x = toUnsignedInt(xRGB[channel])
                        val a = toUnsignedInt(aRGB[channel])
                        val b = toUnsignedInt(bRGB[channel])
                        val c = toUnsignedInt(cRGB[channel])
                        rawNone[r] = x.toByte()
                        rawSub[r] = ((x and 0xFF) - (a and 0xFF)).toByte()
                        rawUp[r] = ((x and 0xFF) - (b and 0xFF)).toByte()
                        rawAvg[r] = (x - ((b + a) / 2)).toByte()
                        val p = a + b - c
                        rawPaeth[r] = (x - minBy(abs(p - a), a, abs(p - b), b, abs(p - c), c)).toByte()
                        r++
                    }
                    System.arraycopy(xRGB, 0, aRGB, 0, 3)
                    System.arraycopy(bRGB, 0, cRGB, 0, 3)
                }
                val raw = minBy(
                    est(rawNone), rawNone, est(rawSub), rawSub, est(rawUp), rawUp, est(rawAvg), rawAvg,
                    est(rawPaeth), rawPaeth
                )
                os.write(raw)
            }
        }

        private fun est(raw: ByteArray): Long = raw.sumOf { abs(it.toLong()) }

        private fun minBy(k1: Int, v1: Int, k2: Int, v2: Int, k3: Int, v3: Int): Int =
            if (k1 <= k2 && k1 <= k3) v1 else if (k2 <= k3) v2 else v3

        private fun <T> minBy(k1: Long, v1: T, k2: Long, v2: T, k3: Long, v3: T, k4: Long, v4: T, k5: Long, v5: T): T {
            var k = k1
            var v = v1
            // @formatter:off
            if (k2 < k) { k = k2; v = v2 }
            if (k3 < k) { k = k3; v = v3 }
            if (k4 < k) { k = k4; v = v4 }
            // @formatter:on
            if (k5 < k) v = v5
            return v
        }

        private fun obtainICCBasedCS(colorSpace: ColorSpace) = docRes.pdColorSpaces.computeIfAbsent(colorSpace) {
            makePDICCBased(doc, 3, ICCProfile.of(colorSpace).bytes)
        }

        companion object {
            private val SHORT = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.BIG_ENDIAN)
                .withInvokeExactBehavior()
            private val INT = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.BIG_ENDIAN)
                .withInvokeExactBehavior()
            private val docResMap = WeakHashMap<PDDocument, DocRes>()
        }

        private class DocRes(doc: PDDocument) {
            val extGStates = HashMap<ExtGStateKey, PDExtendedGraphicsState>()
            val pdColorSpaces = HashMap<ColorSpace, PDICCBased>()
            val pdFonts = HashMap<String /* font name */, PDFont?>()
            val pdImages = HashMap<Picture, PDImageXObject>()
            val pdImageResolutions = HashMap<Picture, MutableList<Resolution>>()
            val pdForms = HashMap<Picture.Vector, PDFormXObject>()
            val layerUtil by lazy { LayerUtility(doc) }
        }

        private data class ExtGStateKey(private val fill: Boolean, private val alpha: Float)

    }


    class PlacedTape(val embeddedTape: EmbeddedTape, val x: Double, val y: Double)

    private class PlacedTapeCollectorBackend : MaterializationBackend {

        val collected = mutableListOf<PlacedTape>()

        override fun materializeEmbeddedTape(
            x: Double, y: Double, scaling: Double, embeddedTape: EmbeddedTape, asyncThumbnail: Future<Picture.Raster?>
        ) {
            var res = embeddedTape.resolution
            res = Resolution((res.widthPx * scaling).roundToInt(), (res.heightPx * scaling).roundToInt())
            if (res.widthPx != 0 && res.heightPx != 0)
                collected.add(PlacedTape(embeddedTape.copy(resolution = res), x, y))
        }

    }

}
