package com.loadingbyte.cinecred.imaging

import com.github.ooxi.jdatauri.DataUri
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.pdf.PDFDrawer
import com.loadingbyte.cinecred.natives.skiacapi.Path
import com.loadingbyte.cinecred.natives.skiacapi.loadImage_t
import com.loadingbyte.cinecred.natives.skiacapi.skiacapi_h.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.RenderDestination
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBAF32
import java.awt.BasicStroke
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.*
import java.lang.ref.SoftReference
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt
import java.awt.Color as AWTColor
import java.awt.color.ColorSpace as AWTColorSpace


/**
 * A Java wrapper around a Skia canvas that permits drawing to a float32 RGBA [Bitmap], SVG, or PDF.
 *
 * Canvases are memory-managed by the garbage collector. However, it is highly advised to manually free them when they
 * are no longer needed by calling [close].
 *
 * Due to some shortcomings of Skia, this class injects certain preprocessing steps before draw calls are actually
 * passed to Skia:
 *
 *
 * ## Gamut Reduction
 *
 * Drawing a gradient whose endpoints are out-of-gamut (partially) produces out-of-gamut pixels. When delivering the
 * result as an integer image, those out-of-gamut pixels are simply clamped to be in-gamut, which often leads to very
 * undesired hue shifts along the gradient because clamping is a very bad gamut reduction method. Similarly, drawing a
 * solid color or an image with out-of-gamut colors at varying transparencies interpolates between the background and
 * the out-of-gamut foreground color, so we experience the same problem. This can even occur if the original image is
 * in-gamut because the Lanczos resizing filter returns values that exceed the input data range.
 *
 * One potential solution to this could be to replace the clamping with a proper gamut reduction method at the final
 * delivery stage (like converting to Oklch, reducing the chroma until we're in-gamut, and then converting back).
 * However, that's very slow, and it's a can of worms we don't really want to open: good gamut reduction is an art in
 * itself and most users will input only in-gamut or nearly in-gamut images anyway.
 *
 * Instead, we clamp every input color, gradient endpoint, and image to the gamut of the canvas right before we draw it.
 * Assuming that all input colors and images are nearly in-gamut, this clamping is a good enough gamut reduction method,
 * and by doing it early, we avoid all the gradient and blending interpolation issues mentioned above.
 *
 *
 * ## Image Snapping
 *
 * Drawing an unscaled image at a fractional position resamples it, reducing its quality. Also, the edge pixels of the
 * image will be drawn slightly transparent, which looks unclean.
 *
 * To solve this, we slightly shift images such that they are aligned exactly with the canvas's pixel boundaries. This
 * shift is practically unnoticeable.
 *
 *
 * ## Image Scaling
 *
 * Drawing a scales image with bilinear or bicubic interpolation exhibits poor quality when downscaling by more than a
 * factor of two. That is because those interpolation methods only consider a small neighborhood of original pixels when
 * obtaining a downscaled pixel, so most original pixels are effectively dropped.
 *
 * To solve this, we resample images to their actual rendered size using the high-quality Lanczos filter. Notice that we
 * apply this filter not only for scaling, but simulate it for all other linear transformations as well.
 */
class Canvas private constructor(
    val width: Double,
    val height: Double,
    val colorSpace: ColorSpace,
    val bitmap: Bitmap?,
    canvasHandle: MemorySegment,
    docHandle: MemorySegment?,
    streamHandle: MemorySegment?
) : AutoCloseable {

    init {
        if (canvasHandle == NULL)
            throw NullPointerException("Failed to allocate Skia canvas.")
    }

    private val handleArena = Arena.ofShared()
    private val streamArena = Arena.ofShared()
    private val canvasHandle = canvasHandle.reinterpret(handleArena, if (docHandle == null) ::SkCanvas_delete else null)
    private val docHandle = docHandle?.reinterpret(handleArena, ::SkRefCnt_unref)
    private val streamHandle = streamHandle?.reinterpret(streamArena, ::SkDynamicMemoryWStream_delete)
    private val cleanable = CLEANER.register(this, CleanerAction(handleArena, streamArena))

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    private class CleanerAction(private vararg val arenas: Arena) : Runnable {
        override fun run() {
            for (arena in arenas) if (arena.scope().isAlive) arena.close()
        }
    }

    init {
        bitmap?.addClosureListener(::close)
        // Save initially so that we can always assume the canvas has one saved state.
        SkCanvas_save(this.canvasHandle)
    }

    private val effectualTransform = AffineTransform()
    private var effectualClip: List<Shape> = emptyList()

    override fun close() {
        cleanable.clean()
    }

    fun closeAndGetOutput(): ByteArray {
        checkNotNull(streamHandle) { "Cannot get output of canvas because it has no stream." }
        docHandle?.let(::SkDocument_endPage)
        handleArena.close()
        val dataHandle = SkDynamicMemoryWStream_detachAsData(streamHandle)
        val arr = SkData_data(dataHandle).reinterpret(SkData_size(dataHandle)).toArray(JAVA_BYTE)
        SkData_unref(dataHandle)
        streamArena.close()
        return arr
    }

    fun compositeLayer(bounds: Rectangle2D? = null, alpha: Double = 1.0, block: () -> Unit) {
        if (alpha <= 0.0) return
        // Due to an apparent a bug in the Skia SVG backend, calling saveLayer() causes nothing to be rendered. So when
        // this is an SVG canvas, just forget about the alpha + the layer semantics and instead directly run the block.
        if (bitmap == null && docHandle == null) {
            block()
            return
        }
        effectualTransform.setToIdentity()
        effectualClip = emptyList()
        SkCanvas_restore(canvasHandle)
        val paint = if (alpha >= 1.0) NULL else SkPaint_New().also { SkPaint_setAlpha(it, alpha.toFloat()) }
        if (bounds == null)
            SkCanvas_saveLayer(canvasHandle, false, 0f, 0f, 0f, 0f, paint)
        else
            SkCanvas_saveLayer(
                canvasHandle, true, bounds.x.toFloat(), bounds.y.toFloat(), bounds.width.toFloat(),
                bounds.height.toFloat(), paint
            )
        if (paint != NULL) SkPaint_delete(paint)
        // This save state is for applyTransformAndClip() to immediately restore to.
        SkCanvas_save(canvasHandle)
        try {
            block()
        } finally {
            SkCanvas_restore(canvasHandle)
            // This restore() mirrors the saveLayer() call.
            SkCanvas_restore(canvasHandle)
            SkCanvas_save(canvasHandle)
        }
    }

    fun compositeLayer(
        bounds: Rectangle2D,
        alpha: Double = 1.0,
        transform: AffineTransform? = null,
        colorSpace: ColorSpace = this.colorSpace,
        block: (Canvas, AffineTransform) -> Unit
    ) {
        if (alpha <= 0.0) return
        // First find the pixel box of the transformed bounds on the canvas.
        val pxBox = (if (transform == null) bounds else bounds.transformedBy(transform))
            .bounds.intersection(Rectangle(ceil(width).toInt(), ceil(height).toInt()))
        if (!pxBox.isEmpty) {
            // If the primaries and transfer characteristics match, draw directly onto this canvas.
            if (colorSpace == this.colorSpace)
                compositeLayer(pxBox as Rectangle2D?, alpha) { block(this, transform ?: AffineTransform()) }
            else {
                // Otherwise, we need to allocate a completely new canvas, let the client draw onto that,
                // and finally composite the result back onto this canvas.
                // Then allocate the sub-bitmap and sub-canvas and let the client draw to it.
                val subRep = compatibleRepresentation(colorSpace)
                Bitmap.allocate(Bitmap.Spec(Resolution(pxBox.width, pxBox.height), subRep)).use { subBitmap ->
                    forBitmap(subBitmap.zero()).use { subCanvas ->
                        val adjustedTransform = AffineTransform.getTranslateInstance(-pxBox.minX, -pxBox.minY)
                        transform?.let(adjustedTransform::concatenate)
                        block(subCanvas, adjustedTransform)
                    }
                    // And finally composite the drawn sub-bitmap onto this canvas.
                    val offsetTransform = AffineTransform.getTranslateInstance(pxBox.minX, pxBox.minY)
                    drawImage(subBitmap, alpha = alpha, transform = offsetTransform)
                }
            }
        }
    }

    fun fill(shader: Shader.Solid) {
        if (bitmap == null)
            fillShape(Rectangle2D.Double(0.0, 0.0, width, height), shader)
        else {
            val color = shader.color.clone()
            shader.colorSpace.convert(colorSpace, color, alpha = true, clamp = true)
            val (w, h) = bitmap.spec.resolution
            val bmpSeg = bitmap.memorySegment(0)
            val ls = bitmap.linesize(0)
            Arena.ofConfined().use { arena ->
                val rowSeg = arena.allocateArray(JAVA_FLOAT, w * 4L)
                for (x in 0..<w)
                    MemorySegment.copy(color, 0, rowSeg, JAVA_FLOAT, x * 16L, 4)
                for (y in 0..<h)
                    MemorySegment.copy(rowSeg, 0L, bmpSeg, y * ls.toLong(), w * 16L)
            }
        }
    }

    fun fillShape(
        shape: Shape,
        shader: Shader,
        alpha: Double = 1.0,
        matte: Matte? = null,
        blurSigma: Double = 0.0,
        blendMode: BlendMode = BlendMode.SRC_OVER,
        transform: AffineTransform? = null,
        clip: List<Shape> = emptyList()
    ) {
        fillOrStrokeShape(shape, null, shader, alpha, matte, blurSigma, blendMode, transform, clip)
    }

    fun strokeShape(
        shape: Shape,
        stroke: BasicStroke,
        shader: Shader,
        alpha: Double = 1.0,
        matte: Matte? = null,
        blurSigma: Double = 0.0,
        blendMode: BlendMode = BlendMode.SRC_OVER,
        transform: AffineTransform? = null,
        clip: List<Shape> = emptyList()
    ) {
        fillOrStrokeShape(shape, stroke, shader, alpha, matte, blurSigma, blendMode, transform, clip)
    }

    fun fillStencil(
        stencil: Bitmap,
        shader: Shader,
        nearestNeighbor: Boolean = false,
        alpha: Double = 1.0,
        matte: Matte? = null,
        blurSigma: Double = 0.0,
        blendMode: BlendMode = BlendMode.SRC_OVER,
        transform: AffineTransform? = null,
        clip: List<Shape> = emptyList()
    ) {
        require(isAlphaRep(stencil.spec.representation))
        fillStencilOrDrawImage(
            stencil, false, false,
            nearestNeighbor, shader, alpha, matte, blurSigma, blendMode, transform, clip
        )
    }

    fun drawImage(
        image: Bitmap,
        promiseOpaque: Boolean = false,
        promiseClamped: Boolean = false,
        nearestNeighbor: Boolean = false,
        alpha: Double = 1.0,
        matte: Matte? = null,
        blurSigma: Double = 0.0,
        blendMode: BlendMode = BlendMode.SRC_OVER,
        transform: AffineTransform? = null,
        clip: List<Shape> = emptyList()
    ) {
        require(isColorRep(image.spec.representation))
        fillStencilOrDrawImage(
            image, promiseOpaque, promiseClamped,
            nearestNeighbor, null, alpha, matte, blurSigma, blendMode, transform, clip
        )
    }

    /**
     * Draws an RGBAF32 bitmap with full range, the same color space as the canvas, and no values outside the [0, 1]
     * range (this is a condition known as `promiseClamped`) at some integer offset without scaling. This method skips
     * a lot of the machinery of the other bitmap-drawing methods and is thus mainly useful when making a large amount
     * of draw calls.
     */
    fun drawImageFast(
        image: Bitmap,
        promiseOpaque: Boolean = false,
        alpha: Double = 1.0,
        x: Int = 0,
        y: Int = 0,
        clip: List<Shape> = emptyList()
    ) {
        if (alpha <= 0.0) return
        val rep = image.spec.representation
        require(
            rep.pixelFormat.code == AV_PIX_FMT_RGBAF32 && rep.range == Bitmap.Range.FULL && rep.colorSpace == colorSpace
        ) { "Fast drawing only supports canvas-compatible images." }
        applyTransformAndClip(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()), clip)
        if (alpha == 1.0)
            callSkDrawImage(image, promiseOpaque, SkFilterMode_Nearest(), NULL)
        else {
            val paint = SkPaint_New()
            SkPaint_setAlpha(paint, alpha.toFloat())
            callSkDrawImage(image, promiseOpaque, SkFilterMode_Nearest(), paint)
            SkPaint_delete(paint)
        }
    }

    fun drawSVG(svg: SourceSVG, transform: AffineTransform? = null) {
        // Make sure to composite the SVG in the sRGB color space as mandated by the specification.
        val bounds = Rectangle2D.Double(0.0, 0.0, svg.width, svg.height)
        compositeLayer(
            bounds, transform = transform, colorSpace = ColorSpace.SRGB
        ) { effCanvas, effTransform ->
            effCanvas.applyTransformAndClip(effTransform, listOf(bounds.transformedBy(effTransform)))
            SkSVGDOM_render(svg.handle, effCanvas.canvasHandle)
        }
    }

    fun drawPDF(pdf: PDDocument, transform: AffineTransform? = null) {
        PDFDrawer(pdf, pdf.getPage(0), RenderDestination.EXPORT).drawTo(this, transform)
    }

    /**
     * Runs all expensive preprocessing steps that are required before a bitmap can be drawn, and then returns a new
     * bitmap object (which might share storage with the old one) that can be drawn very quickly by an *endpoint* like
     * [drawImage]. Preparation is compatible with all canvas/shader/matte endpoints that accept a bitmap.
     *
     * When calling an endpoint with a prepared bitmap, the following arguments shall be passed:
     *
     *     bitmap         = prepared.bitmap
     *     promiseOpaque  = prepared.promiseOpaque
     *     promiseClamped = true
     *     transform      = prepared.transform
     *
     * To use this method with nearest-neighbor interpolation, just don't pass a transform to it, and instead pass the
     * original transform to the endpoint.
     *
     * @param cached A previous preparation of the same bitmap on any canvas; if compatible, avoids the expensive steps.
     */
    fun prepareBitmap(
        bitmap: Bitmap,
        promiseOpaque: Boolean = false,
        promiseClamped: Boolean = false,
        transform: AffineTransform? = null,
        cached: PreparedBitmap? = null
    ): PreparedBitmap =
        prepareBitmap(bitmap, promiseOpaque, promiseClamped, transform ?: IDENTITY, colorSpace, cached)

    fun prepareSVGAsBitmap(
        svg: SourceSVG,
        transform: AffineTransform? = null,
        cached: PreparedBitmap? = null
    ): PreparedBitmap =
        prepareVectorGraphicAsBitmap(
            svg.width, svg.height, { c, t -> c.drawSVG(svg, t) }, transform ?: IDENTITY, colorSpace, cached
        )

    fun preparePDFAsBitmap(
        pdf: PDDocument,
        transform: AffineTransform? = null,
        cached: PreparedBitmap? = null
    ): PreparedBitmap {
        val size = PDFDrawer.sizeOfRotatedCropBox(pdf.getPage(0))
        return prepareVectorGraphicAsBitmap(
            size.width, size.height, { c, t -> c.drawPDF(pdf, t) }, transform ?: IDENTITY, colorSpace, cached
        )
    }

    private fun fillOrStrokeShape(
        shape: Shape,
        stroke: BasicStroke?,
        shader: Shader,
        alpha: Double,
        matte: Matte?,
        blurSigma: Double,
        blendMode: BlendMode,
        transform: AffineTransform?,
        clip: List<Shape>
    ) {
        if (alpha <= 0.0) return
        applyTransformAndClip(transform, clip)
        Arena.ofConfined().use { arena ->
            val paint = SkPaint_New()
            val needsClosing = mutableListOf<Bitmap>()
            SkPaint_setAntiAlias(paint, true)
            if (stroke == null)
                SkPaint_setStroke(paint, false)
            else {
                SkPaint_setStroke(paint, true)
                SkPaint_setStrokeProperties(
                    paint, stroke.lineWidth, CAP_LOOKUP[stroke.endCap], JOIN_LOOKUP[stroke.lineJoin], stroke.miterLimit
                )
                val dashPattern = stroke.dashArray
                if (dashPattern != null && dashPattern.isNotEmpty()) {
                    // Repeat the array twice if it has odd length, because Skia only supports even-length patterns.
                    val size = dashPattern.size
                    val dup = size % 2 == 1
                    val intervals = arena.allocateArray(JAVA_FLOAT, size * if (dup) 2L else 1L)
                    MemorySegment.copy(dashPattern, 0, intervals, JAVA_FLOAT, 0L, size)
                    if (dup)
                        MemorySegment.copy(dashPattern, 0, intervals, JAVA_FLOAT, size * 4L, size)
                    SkPaint_setDashPathEffect(paint, intervals, dashPattern.size, stroke.dashPhase)
                }
            }
            applyToPaint(paint, shader, alpha, transform, transform, colorSpace, needsClosing)
            applyToPaint(paint, matte, blurSigma, blendMode, transform, transform, needsClosing)
            SkCanvas_drawPath(canvasHandle, shapeToPath(shape, arena), paint)
            SkPaint_delete(paint)
            needsClosing.forEach(Bitmap::close)
        }
    }

    private fun fillStencilOrDrawImage(
        bitmap: Bitmap,
        promiseOpaque: Boolean,
        promiseClamped: Boolean,
        nearestNeighbor: Boolean,
        shader: Shader?,
        alpha: Double,
        matte: Matte?,
        blurSigma: Double,
        blendMode: BlendMode,
        transform: AffineTransform?,
        clip: List<Shape>
    ) {
        if (alpha <= 0.0) return
        val prepared: PreparedBitmap
        var canvasTransform = transform
        var filterMode = SkFilterMode_Nearest()
        if (nearestNeighbor)
            prepared = prepareBitmap(bitmap, promiseOpaque, promiseClamped, IDENTITY, colorSpace, null)
        else {
            prepared = prepareBitmap(bitmap, promiseOpaque, promiseClamped, transform ?: IDENTITY, colorSpace, null)
            canvasTransform = prepared.transform
            // If the preparation failed to apply the transform, fall back to Skia's linear interpolation.
            if (canvasTransform == transform)
                filterMode = SkFilterMode_Linear()
        }
        val prepBitmap = prepared.bitmap ?: return
        // Note: We have observed incongruencies with shader coordinate systems and especially mask filter shader
        // placement when passing x/y coordinates to Skia's drawImage() function. To circumvent them, we apply the
        // x/y translation via a canvas transform.
        applyTransformAndClip(canvasTransform, clip)
        val paint = SkPaint_New()
        val needsClosing = mutableListOf<Bitmap>()
        if (shader != null)
            applyToPaint(paint, shader, alpha, transform, canvasTransform, colorSpace, needsClosing)
        else if (alpha < 1.0)
            SkPaint_setAlpha(paint, alpha.toFloat())
        applyToPaint(paint, matte, blurSigma, blendMode, transform, canvasTransform, needsClosing)
        callSkDrawImage(prepBitmap, prepared.promiseOpaque, filterMode, paint)
        SkPaint_delete(paint)
        needsClosing.forEach(Bitmap::close)
        prepBitmap.close()
    }

    private fun callSkDrawImage(bitmap: Bitmap, promiseOpaque: Boolean, filterMode: Byte, paint: MemorySegment) {
        val (w, h) = bitmap.spec.resolution
        val rep = bitmap.spec.representation
        val (colorType, alphaType) = colorAndAlphaTypeFor(rep.pixelFormat, rep.alpha, promiseOpaque)
        SkCanvas_drawImage(
            canvasHandle, w, h, colorType, alphaType,
            if (isAlphaRep(rep)) NULL else rep.colorSpace!!.skiaHandle,
            bitmap.memorySegment(0),
            bitmap.linesize(0).toLong(),
            0f, 0f, filterMode, paint
        )
    }

    private fun applyTransformAndClip(transform: AffineTransform?, clip: List<Shape>) {
        val tr = transform ?: IDENTITY
        if (tr != effectualTransform || clip != effectualClip) {
            effectualTransform.setTransform(tr)
            effectualClip = clip
            SkCanvas_restore(canvasHandle)
            SkCanvas_save(canvasHandle)
            // It's important to first apply the clip and then the transform because the clip is in global coordinates.
            if (clip.isNotEmpty()) {
                var arena: Arena? = null
                for (sh in clip)
                    if (sh is Rectangle2D)
                        SkCanvas_clipRect(
                            canvasHandle, sh.x.toFloat(), sh.y.toFloat(), sh.width.toFloat(), sh.height.toFloat(), true
                        )
                    else {
                        if (arena == null)
                            arena = Arena.ofConfined()!!
                        SkCanvas_clipPath(canvasHandle, shapeToPath(sh, arena), true)
                    }
                arena?.close()
            }
            if (!tr.isIdentity)
                SkCanvas_setMatrix(canvasHandle, tr.m00, tr.m10, tr.m01, tr.m11, tr.m02, tr.m12)
        }
    }


    companion object {

        fun compatibleRepresentation(colorSpace: ColorSpace): Bitmap.Representation =
            Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_RGBAF32), colorSpace, Bitmap.Alpha.PREMULTIPLIED)

        fun forBitmap(bitmap: Bitmap): Canvas {
            val rep = bitmap.spec.representation
            require(rep.pixelFormat.code == AV_PIX_FMT_RGBAF32)
            require(rep.range == Bitmap.Range.FULL)
            requireNotNull(rep.colorSpace)
            require(rep.alpha == Bitmap.Alpha.PREMULTIPLIED)
            val (w, h) = bitmap.spec.resolution
            val canvasHandle = SkCanvas_MakeRasterDirect(
                w, h, SkColorType_RGBA_F32(), SkAlphaType_Premul(),
                rep.colorSpace.skiaHandle,
                bitmap.memorySegment(0),
                bitmap.linesize(0).toLong()
            )
            return Canvas(w.toDouble(), h.toDouble(), rep.colorSpace, bitmap, canvasHandle, null, null)
        }

        fun forSVG(width: Double, height: Double): Canvas {
            require(width > 0.0 && height > 0.0)
            val streamHandle = SkDynamicMemoryWStream_New()
            val canvasHandle = SkSVGCanvas_Make(streamHandle, 0f, 0f, width.toFloat(), height.toFloat())
            // The SVG specification mandates that when an SVG is later rendered, it must be composited in sRGB.
            // So to anyone who now asks about the canvas's compositing color space, we answer sRGB.
            return Canvas(width, height, ColorSpace.SRGB, null, canvasHandle, null, streamHandle)
        }

        fun forPDF(width: Double, height: Double, colorSpace: ColorSpace): Canvas {
            require(width > 0.0 && height > 0.0)
            val streamHandle = SkDynamicMemoryWStream_New()
            val docHandle = SkPDF_MakeDocument(streamHandle)
            val canvasHandle = SkDocument_beginPage(docHandle, width.toFloat(), height.toFloat())
            return Canvas(width, height, colorSpace, null, canvasHandle, docHandle, streamHandle)
        }

        private val IDENTITY = AffineTransform()

        private val VERB_LOOKUP = ByteArray(5).apply {
            set(PathIterator.SEG_MOVETO, SkPathVerb_Move())
            set(PathIterator.SEG_LINETO, SkPathVerb_Line())
            set(PathIterator.SEG_QUADTO, SkPathVerb_Quad())
            set(PathIterator.SEG_CUBICTO, SkPathVerb_Cubic())
            set(PathIterator.SEG_CLOSE, SkPathVerb_Close())
        }
        private val VERB_COORD_COUNT_LOOKUP = IntArray(5).apply {
            set(PathIterator.SEG_MOVETO, 2)
            set(PathIterator.SEG_LINETO, 2)
            set(PathIterator.SEG_QUADTO, 4)
            set(PathIterator.SEG_CUBICTO, 6)
            set(PathIterator.SEG_CLOSE, 0)
        }

        private val CAP_LOOKUP = ByteArray(3).apply {
            set(BasicStroke.CAP_BUTT, SkPaintCap_Butt())
            set(BasicStroke.CAP_ROUND, SkPaintCap_Round())
            set(BasicStroke.CAP_SQUARE, SkPaintCap_Square())
        }
        private val JOIN_LOOKUP = ByteArray(3).apply {
            set(BasicStroke.JOIN_MITER, SkPaintJoin_Miter())
            set(BasicStroke.JOIN_ROUND, SkPaintJoin_Round())
            set(BasicStroke.JOIN_BEVEL, SkPaintJoin_Bevel())
        }

        private val AffineTransform.m00 get() = scaleX.toFloat()
        private val AffineTransform.m10 get() = shearY.toFloat()
        private val AffineTransform.m01 get() = shearX.toFloat()
        private val AffineTransform.m11 get() = scaleY.toFloat()
        private val AffineTransform.m02 get() = translateX.toFloat()
        private val AffineTransform.m12 get() = translateY.toFloat()

        private fun shapeToPath(shape: Shape, arena: Arena): MemorySegment {
            var verbs = ByteArray(256)
            var verbCount = 0
            var points = FloatArray(512)
            var pointCountX2 = 0

            val pi = shape.getPathIterator(null)
            val coords = FloatArray(6)
            while (!pi.isDone) {
                val segType = pi.currentSegment(coords)
                val verb = VERB_LOOKUP[segType]
                val verbCoordCount = VERB_COORD_COUNT_LOOKUP[segType]
                if (verbCount + 1 > verbs.size)
                    verbs = verbs.copyOf(verbs.size * 2)
                if (pointCountX2 + verbCoordCount > points.size)
                    points = points.copyOf(points.size * 2)
                verbs[verbCount++] = verb
                for (i in 0..<verbCoordCount)
                    points[pointCountX2++] = coords[i]
                pi.next()
            }

            val path = Path.allocate(arena)
            Path.`verbs$set`(path, arena.allocateArray(verbs))
            Path.`verbCount$set`(path, verbCount)
            Path.`points$set`(path, arena.allocateArray(points))
            Path.`pointCount$set`(path, pointCountX2 / 2)
            Path.`isEvenOdd$set`(path, pi.windingRule == PathIterator.WIND_EVEN_ODD)
            return path
        }

        private fun isColorRep(rep: Bitmap.Representation) =
            rep.pixelFormat.family != Bitmap.PixelFormat.Family.GRAY

        private fun isAlphaRep(rep: Bitmap.Representation) =
            rep.pixelFormat.run { family == Bitmap.PixelFormat.Family.GRAY && !hasAlpha }

        private data class ColorAndAlphaType(val colorType: Byte, val alphaType: Byte)

        private fun colorAndAlphaTypeFor(pixelFormat: Bitmap.PixelFormat, alpha: Bitmap.Alpha, promiseOpaque: Boolean) =
            when (pixelFormat.code) {
                AV_PIX_FMT_RGBAF32 -> ColorAndAlphaType(SkColorType_RGBA_F32(), alphaTypeFor(alpha, promiseOpaque))
                AV_PIX_FMT_GRAY16LE -> ColorAndAlphaType(SkColorType_A16_unorm(), SkAlphaType_Premul())
                else -> throw IllegalArgumentException("Pixel format $pixelFormat is incompatible with Skia.")
            }

        private fun alphaTypeFor(alpha: Bitmap.Alpha, promiseOpaque: Boolean) = when {
            promiseOpaque -> SkAlphaType_Opaque()
            alpha == Bitmap.Alpha.PREMULTIPLIED -> SkAlphaType_Premul()
            // We have observed that Skia often exhibits wrong blending when drawing images with straight alpha, despite
            // the image being correctly tagged as straight. For example, Skia's SVG renderer adds a white shadow behind
            // images embedded in the SVG when they are scaled up, and Skia's standard drawImage() function adds a black
            // shadow in the same scenario. Premultiplying the images ourselves and then passing them tagged as
            // premultiplied fixes these issues for some reason.
            else -> throw IllegalArgumentException("Skia bugs out on straight alpha images.")
        }

        private fun applyToPaint(
            paint: MemorySegment, shader: Shader, alpha: Double,
            userTransform: AffineTransform?, canvasTransform: AffineTransform?, canvasCS: ColorSpace,
            needsClosing: MutableList<Bitmap>
        ) {
            val shaderHandle = when (shader) {
                is Shader.Solid -> {
                    val c = shader.color.clone()
                    shader.colorSpace.convert(canvasCS, c, alpha = true, clamp = true)
                    SkPaint_setColor(paint, c[0], c[1], c[2], c[3] * alpha.toFloat(), canvasCS.skiaHandle)
                    return
                }
                is Shader.LinearGradient ->
                    allocateLinearGradientShader(shader, userTransform, canvasTransform, canvasCS)
                is Shader.Image ->
                    allocateBitmapShader(
                        shader.bitmap, shader.promiseOpaque, shader.promiseClamped, shader.transform, shader.tileMode,
                        shader.disregardUserTransform, userTransform, canvasTransform, canvasCS, needsClosing
                    )
            }
            SkPaint_setShader(paint, shaderHandle)
            SkRefCnt_unref(shaderHandle)
            if (alpha < 1.0)
                SkPaint_setAlpha(paint, alpha.toFloat())
        }

        private fun applyToPaint(
            paint: MemorySegment, matte: Matte?, blurSigma: Double, blendMode: BlendMode,
            userTransform: AffineTransform?, canvasTransform: AffineTransform?,
            needsClosing: MutableList<Bitmap>
        ) {
            if (matte != null) {
                val shaderHandle = allocateBitmapShader(
                    matte.bitmap, false, false, matte.transform, matte.tileMode, matte.disregardUserTransform,
                    userTransform, canvasTransform, null, needsClosing
                )
                SkPaint_setShaderMaskFilter(paint, shaderHandle)
                SkRefCnt_unref(shaderHandle)
            }
            if (blurSigma > 0.0)
                blurSigma.toFloat().let { SkPaint_setBlurImageFilter(paint, it, it) }
            SkPaint_setBlendMode(paint, blendMode.code)
        }

        private fun allocateLinearGradientShader(
            shader: Shader.LinearGradient,
            userTransform: AffineTransform?, canvasTransform: AffineTransform?, canvasCS: ColorSpace
        ): MemorySegment {
            val p = doubleArrayOf(shader.point1.x, shader.point1.y, shader.point2.x, shader.point2.y)
            if ((userTransform != null || canvasTransform != null) && userTransform != canvasTransform) {
                val pTransform = canvasTransform?.createInverse() ?: AffineTransform()
                userTransform?.let(pTransform::concatenate)
                pTransform.transform(p, 0, p, 0, 2)
            }
            Arena.ofConfined().use { arena ->
                val colors = shader.colors.clone()
                shader.colorSpace.convert(canvasCS, colors, alpha = true, clamp = true)
                val colorsSeg = arena.allocateArray(colors)
                val posSeg = if (shader.pos == null) NULL else arena.allocateArray(shader.pos)
                return SkGradientShader_MakeLinear(
                    p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(),
                    colorsSeg, canvasCS.skiaHandle, posSeg, colors.size / 4,
                    TileMode.CLAMP.code, SkGradientShaderInterpolationColorSpace_SRGB()
                )
            }
        }

        private fun allocateBitmapShader(
            bitmap: Bitmap, promiseOpaque: Boolean, promiseClamped: Boolean,
            shaderTransform: AffineTransform?, tileMode: TileMode, disregardUserTransform: Boolean,
            userTransform: AffineTransform?, canvasTransform: AffineTransform?, canvasCS: ColorSpace?,
            needsClosing: MutableList<Bitmap>
        ): MemorySegment {
            val physicalTransform = AffineTransform().apply {
                if (!disregardUserTransform)
                    userTransform?.let(::concatenate)
                shaderTransform?.let(::concatenate)
            }
            val prepared = prepareBitmap(bitmap, promiseOpaque, promiseClamped, physicalTransform, canvasCS, null)
            val shaderBitmap = prepared.bitmap ?: run {
                // It's difficult to predict the effect of a vanishingly small shader image,
                // so if that happens, just pass in a transparent 1x1 bitmap.
                Bitmap.allocate(Bitmap.Spec(Resolution(1, 1), compatibleRepresentation(ColorSpace.XYZD50))).zero()
            }
            needsClosing += shaderBitmap
            val tr = prepared.transform
            // If the preparation failed to apply the transform, fall back to Skia's linear interpolation.
            val filterMode = if (tr == physicalTransform) SkFilterMode_Linear() else SkFilterMode_Nearest()
            if (canvasTransform != null)
                tr.preConcatenate(canvasTransform.createInverse())

            val (w, h) = shaderBitmap.spec.resolution
            val rep = shaderBitmap.spec.representation
            val (colorType, alphaType) = colorAndAlphaTypeFor(rep.pixelFormat, rep.alpha, prepared.promiseOpaque)
            return SkImage_makeShader(
                w, h, colorType, alphaType,
                if (isAlphaRep(rep)) NULL else rep.colorSpace!!.skiaHandle,
                shaderBitmap.memorySegment(0),
                shaderBitmap.linesize(0).toLong(),
                tileMode.code, tileMode.code, filterMode, tr.m00, tr.m10, tr.m01, tr.m11, tr.m02, tr.m12
            )
        }

        private fun prepareBitmap(
            bitmap: Bitmap, promiseOpaque: Boolean, promiseClamped: Boolean,
            transform: AffineTransform, canvasCS: ColorSpace?, cached: PreparedBitmap?
        ): PreparedBitmap {
            // Find whether the representation of the passed bitmap is directly supported by Skia.
            val (res, inRep) = bitmap.spec
            val compatiblePixelFormat = when {
                isColorRep(inRep) -> Bitmap.PixelFormat.of(AV_PIX_FMT_RGBAF32)
                isAlphaRep(inRep) -> Bitmap.PixelFormat.of(AV_PIX_FMT_GRAY16LE)
                else -> throw IllegalArgumentException("Representation is neither color nor alpha: $inRep")
            }
            val isMask = compatiblePixelFormat.code == AV_PIX_FMT_GRAY16LE
            val isInRepCompatible = inRep.pixelFormat == compatiblePixelFormat && inRep.range == Bitmap.Range.FULL &&
                    inRep.alpha != Bitmap.Alpha.STRAIGHT && (isMask || (inRep.colorSpace == canvasCS && promiseClamped))

            // If there is no transform and Skia understands the bitmap, just return it.
            if (transform.isIdentity && isInRepCompatible)
                return PreparedBitmap(bitmap.view(), promiseOpaque, AffineTransform(), AffineTransform())

            // Create a representation that is supported by Skia and that requires the least possible work by Skia to
            // convert to another color space. We'll convert the bitmap to this representation.
            val outRep = Bitmap.Representation(
                compatiblePixelFormat,
                if (isMask) null else canvasCS,
                if (compatiblePixelFormat.hasAlpha) Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE
            )

            val scaleX = transform.scaleX
            val scaleY = transform.scaleY
            val shearX = transform.shearX
            val shearY = transform.shearY
            val copiedTransform = AffineTransform(transform)

            val transformedBitmap: Bitmap?
            val transformedPromiseOpaque: Boolean
            val drawAtX: Int
            val drawAtY: Int
            if (scaleX >= 0.0 && scaleY >= 0.0 && shearX == 0.0 && shearY == 0.0) {
                transformedPromiseOpaque = promiseOpaque || inRep.alpha == Bitmap.Alpha.OPAQUE
                drawAtX = transform.translateX.roundToInt()
                drawAtY = transform.translateY.roundToInt()
                val scaledRes = Resolution((res.widthPx * scaleX).roundToInt(), (res.heightPx * scaleY).roundToInt())
                if (scaledRes.widthPx == 0 || scaledRes.heightPx == 0)
                    transformedBitmap = null
                else if (scaledRes == res && isInRepCompatible)
                    transformedBitmap = bitmap.view()
                else if (cached?.bitmap != null && cached.bitmap.spec.representation.colorSpace == canvasCS &&
                    cached.originalTransform.let {
                        it.scaleX >= 0.0 && it.scaleY >= 0.0 && it.shearX == 0.0 && it.shearY == 0.0
                    } && cached.bitmap.spec.resolution == scaledRes
                )
                    transformedBitmap = cached.bitmap.view()
                else {
                    transformedBitmap = Bitmap.allocate(Bitmap.Spec(scaledRes, outRep))
                    BitmapConverter.convert(bitmap, transformedBitmap, promiseOpaque = promiseOpaque)
                }
            } else {
                // For the case of general transformations, we follow the strategy outlined here:
                // https://github.com/avaneev/avir#affine-and-non-linear-transformations
                // In short, we scale the image 4x with Lanczos, then transform it with bilinear interpolation,
                // and finally scale it back down 4x with Lanczos.
                if (isMask) {
                    // This class doesn't support drawing grayscale bitmaps, and Skia doesn't even seem to support
                    // drawing grayscale images with more than 8 bits of depth. So instead, we only convert to the
                    // correct grayscale format and let Skia's bilinear interpolation take care of the transform.
                    if (isInRepCompatible)
                        transformedBitmap = bitmap.view()
                    else if (cached?.bitmap != null && cached.transform == cached.originalTransform)
                        transformedBitmap = cached.bitmap.view()
                    else {
                        transformedBitmap = Bitmap.allocate(Bitmap.Spec(res, outRep))
                        BitmapConverter.convert(bitmap, transformedBitmap, promiseOpaque = promiseOpaque)
                    }
                    return PreparedBitmap(transformedBitmap, false, copiedTransform, copiedTransform)
                }
                transformedPromiseOpaque = false
                val boundsOnCanvas = Rectangle(0, 0, res.widthPx, res.heightPx).transformedBy(transform).bounds
                drawAtX = boundsOnCanvas.x
                drawAtY = boundsOnCanvas.y
                if (cached?.bitmap != null && cached.bitmap.spec.representation.colorSpace == canvasCS &&
                    differOnlyByIntegerTranslation(cached.originalTransform, transform)
                )
                    transformedBitmap = cached.bitmap.view()
                else {
                    val sx = transform.scalingFactorX
                    val sy = transform.scalingFactorY
                    val res1 = Resolution((res.widthPx * 4 * sx).roundToInt(), (res.heightPx * 4 * sy).roundToInt())
                    val res3 = Resolution(boundsOnCanvas.width, boundsOnCanvas.height)
                    if (res1.widthPx <= 0 || res1.heightPx <= 0 || res3.widthPx <= 0 || res3.heightPx <= 0)
                        transformedBitmap = null
                    else {
                        val res2 = Resolution(res3.widthPx * 4, res3.heightPx * 4)
                        val bitmap1 = Bitmap.allocate(Bitmap.Spec(res1, outRep))
                        val bitmap2 = Bitmap.allocate(Bitmap.Spec(res2, outRep))
                        transformedBitmap = Bitmap.allocate(Bitmap.Spec(res3, outRep))
                        BitmapConverter.convert(bitmap, bitmap1, promiseOpaque = promiseOpaque)
                        forBitmap(bitmap2.zero()).use { canvas ->
                            canvas.applyTransformAndClip(AffineTransform().apply {
                                scale(res2.widthPx / res3.widthPx.toDouble(), res2.heightPx / res3.heightPx.toDouble())
                                translate(-drawAtX.toDouble(), -drawAtY.toDouble())
                                concatenate(transform)
                                scale(res.widthPx / res1.widthPx.toDouble(), res.heightPx / res1.heightPx.toDouble())
                            }, emptyList())
                            val paint = SkPaint_New()
                            SkPaint_setAntiAlias(paint, true)  // Edge pixels should be partially transparent.
                            canvas.callSkDrawImage(bitmap1, promiseOpaque, SkFilterMode_Linear(), paint)
                            SkPaint_delete(paint)
                        }
                        BitmapConverter.convert(bitmap2, transformedBitmap)
                        bitmap1.close()
                        bitmap2.close()
                    }
                }
            }

            // If a new float bitmap has been created, clamp it. See the class's comment for details on why.
            if (!isMask && transformedBitmap != null && !transformedBitmap.sharesStorageWith(bitmap))
                transformedBitmap.clampFloatColors(transformedPromiseOpaque)

            val intTransl = AffineTransform.getTranslateInstance(drawAtX.toDouble(), drawAtY.toDouble())
            return PreparedBitmap(transformedBitmap, transformedPromiseOpaque, intTransl, copiedTransform)
        }

        private fun prepareVectorGraphicAsBitmap(
            width: Double, height: Double, drawTo: (Canvas, AffineTransform) -> Unit,
            transform: AffineTransform, canvasCS: ColorSpace, cached: PreparedBitmap?
        ): PreparedBitmap {
            val bitmap: Bitmap
            val b = Rectangle2D.Double(0.0, 0.0, width, height).transformedBy(transform).bounds
            if (cached?.bitmap != null && cached.bitmap.spec.representation.colorSpace == canvasCS &&
                differOnlyByIntegerTranslation(cached.originalTransform, transform)
            )
                bitmap = cached.bitmap.view()
            else {
                bitmap = Bitmap.allocate(Bitmap.Spec(Resolution(b.width, b.height), compatibleRepresentation(canvasCS)))
                val shiftedTransform = AffineTransform.getTranslateInstance(-b.x.toDouble(), -b.y.toDouble())
                    .apply { concatenate(transform) }
                forBitmap(bitmap.zero()).use { canvas -> drawTo(canvas, shiftedTransform) }
            }
            val intTransl = AffineTransform.getTranslateInstance(b.x.toDouble(), b.y.toDouble())
            return PreparedBitmap(bitmap, false, intTransl, AffineTransform(transform))
        }

        private fun differOnlyByIntegerTranslation(t1: AffineTransform, t2: AffineTransform) =
            abs(t1.scaleX - t2.scaleX) < 0.001 && abs(t1.scaleY - t2.scaleY) < 0.001 &&
                    abs(t1.shearX - t2.shearX) < 0.001 && abs(t1.shearY - t2.shearY) < 0.001 &&
                    (t1.translateX - t2.translateX).let { abs(it - round(it)) < 0.001 } &&
                    (t1.translateY - t2.translateY).let { abs(it - round(it)) < 0.001 }

    }


    enum class BlendMode(val code: Byte) {
        CLEAR(SkBlendMode_Clear()),
        SRC(SkBlendMode_Src()),
        DST(SkBlendMode_Dst()),
        SRC_OVER(SkBlendMode_SrcOver()),
        DST_OVER(SkBlendMode_DstOver()),
        SRC_IN(SkBlendMode_SrcIn()),
        DST_IN(SkBlendMode_DstIn()),
        SRC_OUT(SkBlendMode_SrcOut()),
        DST_OUT(SkBlendMode_DstOut()),
        SRC_ATOP(SkBlendMode_SrcATop()),
        DST_ATOP(SkBlendMode_DstATop()),
        XOR(SkBlendMode_Xor()),
        PLUS(SkBlendMode_Plus()),
        MODULATE(SkBlendMode_Modulate()),
        SCREEN(SkBlendMode_Screen()),
        OVERLAY(SkBlendMode_Overlay()),
        DARKEN(SkBlendMode_Darken()),
        LIGHTEN(SkBlendMode_Lighten()),
        COLOR_DODGE(SkBlendMode_ColorDodge()),
        COLOR_BURN(SkBlendMode_ColorBurn()),
        HARD_LIGHT(SkBlendMode_HardLight()),
        SOFT_LIGHT(SkBlendMode_SoftLight()),
        DIFFERENCE(SkBlendMode_Difference()),
        EXCLUSION(SkBlendMode_Exclusion()),
        MULTIPLY(SkBlendMode_Multiply()),
        HUE(SkBlendMode_Hue()),
        SATURATION(SkBlendMode_Saturation()),
        COLOR(SkBlendMode_Color()),
        LUMINOSITY(SkBlendMode_Luminosity())
    }


    enum class TileMode(val code: Byte) {
        /** Repeats the edge color outside the image's bounds. */
        CLAMP(SkTileMode_Clamp()),
        /** Transparent outside the image's bounds. */
        DECAL(SkTileMode_Decal())
    }


    sealed interface Shader {

        class Solid(val color: FloatArray, val colorSpace: ColorSpace) : Shader {

            init {
                require(color.size == 4)
            }

            constructor(color: AWTColor) : this(
                color.getComponents(AWTColorSpace.getInstance(AWTColorSpace.CS_CIEXYZ), null),
                ColorSpace.XYZD50
            )

        }

        class LinearGradient(
            val point1: Point2D,
            val point2: Point2D,
            val colors: FloatArray,
            val colorSpace: ColorSpace,
            val pos: FloatArray? = null
        ) : Shader {

            init {
                require(colors.size == if (pos == null) 8 else pos.size * 4)
                require(pos == null || pos.size >= 2)
            }

            constructor(
                point1: Point2D, point2: Point2D,
                colors: List<AWTColor>, pos: FloatArray? = null
            ) : this(point1, point2, convColors(colors), ColorSpace.XYZD50, pos)

            companion object {
                private fun convColors(colors: List<AWTColor>): FloatArray {
                    val xyza = FloatArray(4)
                    val xyzas = FloatArray(colors.size * 4)
                    for (i in colors.indices) {
                        colors[i].getComponents(AWTColorSpace.getInstance(AWTColorSpace.CS_CIEXYZ), xyza)
                        System.arraycopy(xyza, 0, xyzas, i * 4, 4)
                    }
                    return xyzas
                }
            }

        }

        class Image(
            val bitmap: Bitmap,
            val promiseOpaque: Boolean = false,
            val promiseClamped: Boolean = false,
            val transform: AffineTransform? = null,
            val tileMode: TileMode = TileMode.DECAL,
            val disregardUserTransform: Boolean = false
        ) : Shader {
            init {
                require(isColorRep(bitmap.spec.representation))
            }
        }

    }


    class Matte(
        val bitmap: Bitmap,
        val transform: AffineTransform? = null,
        val tileMode: TileMode = TileMode.DECAL,
        val disregardUserTransform: Boolean = false
    ) {
        init {
            require(isAlphaRep(bitmap.spec.representation))
        }
    }


    class PreparedBitmap(
        val bitmap: Bitmap?,
        val promiseOpaque: Boolean,
        val transform: AffineTransform,
        val originalTransform: AffineTransform
    )


    /** @throws IllegalArgumentException If the SVG cannot be parsed or is invalid in some respect. */
    class SourceSVG(xml: String) : AutoCloseable {

        private val handleArena = Arena.ofShared()
        val handle: MemorySegment
        val width: Double
        val height: Double

        init {
            Arena.ofConfined().use { arena ->
                setNativeNumericLocaleToC()
                val cStr = arena.allocateUtf8String(xml)
                handle = SkSVGDOM_Make(cStr, cStr.byteSize() - 1, LoadImageFromDataURI.UPCALL_STUB)
                    .also { require(it != NULL) { "Failed to allocate Skia SVGDOM." } }
                    .reinterpret(handleArena, ::SkRefCnt_unref)

                try {
                    val contSizeSeg = arena.allocateArray(JAVA_FLOAT, 2)
                    SkSVGDOM_containerSize(handle, contSizeSeg)
                    var w = contSizeSeg.getAtIndex(JAVA_FLOAT, 0)
                    var h = contSizeSeg.getAtIndex(JAVA_FLOAT, 1)
                    // If the SVG misses the "width" or "height" attribute, or it is specified in relative units
                    // (like %), Skia assigns a size of 0 to that dimension. As such, the SVG basically vanishes.
                    // Of course, the user can still get the SVG to show by scaling it back up in the program,
                    // but we'd like a better default. So we read the viewBox attribute of the SVG (which must
                    // exist when width/height are missing) and set the SVG size to the viewBox size.
                    if (w <= 0f || h <= 0f) {
                        val viewBoxSeg = arena.allocateArray(JAVA_FLOAT, 4)
                        require(SkSVGDOM_getViewBox(handle, viewBoxSeg)) { "SVG has neither width/height nor viewBox." }
                        w = viewBoxSeg.getAtIndex(JAVA_FLOAT, 2)
                        h = viewBoxSeg.getAtIndex(JAVA_FLOAT, 3)
                        SkSVGDOM_setContainerSize(handle, w, h)
                    }
                    require(w > 0.001f && h > 0.001f) { "SVG's width and/or height is vanishingly small." }
                    width = w.toDouble()
                    height = h.toDouble()
                } catch (t: Throwable) {
                    close()
                    throw t
                }
            }
        }

        override fun close() {
            handleArena.close()
        }

        // We supply our own image-from-data-URI loader to widen the supported formats beyond whichever codecs happen to
        // have been compiled into Skia.
        private class LoadImageFromDataURI : loadImage_t {

            companion object {
                val UPCALL_STUB: MemorySegment = loadImage_t.allocate(LoadImageFromDataURI(), Arena.global())
            }

            private val timer = Timer("SVGEmbeddedImageRetainer", true)
            private val cache = ConcurrentHashMap<String, Optional<SoftReference<PreparedBitmap>>>()

            override fun apply(
                path: MemorySegment,
                name: MemorySegment,
                id: MemorySegment,
                w: MemorySegment,
                h: MemorySegment,
                colorType: MemorySegment,
                alphaType: MemorySegment,
                colorSpace: MemorySegment,
                pixels: MemorySegment,
                rowBytes: MemorySegment
            ): Boolean {
                try {
                    val prepared = read(name.getUtf8String(0L)) ?: return false
                    val bitmap = prepared.bitmap ?: return false
                    val (res, rep) = bitmap.spec
                    val (ct, at) = colorAndAlphaTypeFor(rep.pixelFormat, rep.alpha, prepared.promiseOpaque)
                    w.set(JAVA_INT, 0L, res.widthPx)
                    h.set(JAVA_INT, 0L, res.heightPx)
                    colorType.set(JAVA_BYTE, 0L, ct)
                    alphaType.set(JAVA_BYTE, 0L, at)
                    colorSpace.set(ADDRESS, 0L, rep.colorSpace!!.skiaHandle)
                    pixels.set(ADDRESS, 0L, bitmap.memorySegment(0))
                    rowBytes.set(JAVA_LONG, 0L, bitmap.linesize(0).toLong())
                    // Schedule a task that prevents rasterPic from being garbage-collected long enough for Skia's SVG
                    // renderer to draw the image.
                    timer.schedule(Retainer(bitmap), 20_000L)
                    return true
                } catch (t: Throwable) {
                    // We have to catch all exceptions because if one escapes, a segfault happens.
                    runCatching { LOGGER.error("Cannot load image on behalf of Skia.", t) }
                    return false
                }
            }

            private fun read(uri: String): PreparedBitmap? {
                cache[uri]?.let { opt -> if (opt.isEmpty) return null else opt.get().get()?.let { return it } }
                try {
                    // Note: We intentionally ignore the MIME type and instead let BitmapReader figure out the format.
                    val bytes = DataUri.parse(uri, Charsets.UTF_8).data
                    val prepared = BitmapReader.read(bytes, planar = false).use { bitmap ->
                        prepareBitmap(bitmap, false, false, IDENTITY, ColorSpace.SRGB, null)
                    }
                    cache[uri] = Optional.of(SoftReference(prepared))
                    return prepared
                } catch (e: Exception) {
                    LOGGER.error("Skipping image embedded in SVG because it is corrupt or cannot be read.", e)
                    cache[uri] = Optional.empty()
                    return null
                }
            }

            private class Retainer(private val bitmap: Bitmap) : TimerTask() {
                override fun run() {}
            }

        }

    }

}
