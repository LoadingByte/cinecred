package com.loadingbyte.cinecred.imaging.pdf

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.common.function.PDFunctionTypeIdentity
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDSimpleFont
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode
import org.apache.pdfbox.pdmodel.graphics.color.*
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup.RenderState
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentMembershipDictionary
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.RenderDestination
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GRAY16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBAF32
import java.awt.BasicStroke
import java.awt.Rectangle
import java.awt.Shape
import java.awt.color.ICC_ColorSpace
import java.awt.geom.*
import java.io.IOException
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*


/** Based on the source code of PageDrawer and related classes in PDFBox 3.0.5. */
class PDFDrawer(
    private val document: PDDocument,
    page: PDPage,
    private val destination: RenderDestination
) : PDFGraphicsStreamEngine(page) {

    private val groupStack = ArrayDeque<Group>()
    private val textClippings = mutableListOf<Shape>()
    private var nestedHiddenOCGCount = 0

    fun drawTo(canvas: Canvas, transform: AffineTransform?) {
        // Find the transform from the page's coordinate system to the coordinate system of the canvas.
        val pageToCanvasTransform = compensateForCropBoxAndRotation(andFlip = true, page)
        transform?.let(pageToCanvasTransform::preConcatenate)

        // Instantiate the Device* color spaces by looking at the document's and page's output intents.
        val devCS = getDeviceColorSpaces(document, page)

        // Find the primaries and transfer characteristics that must be used for compositing the PDF.
        val pdCS = page.cosObject.getCOSDictionary(COSName.GROUP)
            ?.let { PDTransparencyGroupAttributes(it).getColorSpace(page.resources) }
        val requiredCS = convertRootPDFColorSpaceToColorSpace(pdCS, devCS)
        val numCompsOfSpecifiedCIEBasedBlendingCS =
            if (pdCS is PDCalGray || pdCS is PDCalRGB || pdCS is PDICCBased) pdCS.numberOfComponents else -1

        // And now composite the PDF in that color space.
        val cropBox = page.cropBox
        canvas.compositeLayer(
            bounds = Rectangle2D.Float(cropBox.lowerLeftX, cropBox.lowerLeftY, cropBox.width, cropBox.height),
            transform = pageToCanvasTransform, colorSpace = requiredCS
        ) { effCanvas, effPageToCanvasTr ->
            withGroup(Group(arrayOf(effCanvas), effPageToCanvasTr, devCS, numCompsOfSpecifiedCIEBasedBlendingCS)) {
                processPage(page)
            }
        }
    }

    override fun beginText() {
        textClippings.clear()
    }

    override fun endText() {
        // Apply the buffered clip as one area.
        if (graphicsState.textState.renderingMode.isClip && textClippings.isNotEmpty()) {
            val clip = GeneralPath(Path2D.WIND_NON_ZERO)
            for (shape in textClippings)
                clip.append(shape, false)
            graphicsState.intersectClippingPath(clip)
            textClippings.clear()
        }
    }

    override fun showFontGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val at = textRenderingMatrix.createAffineTransform()
        at.concatenate(font.fontMatrix.createAffineTransform())
        if (!font.isEmbedded && !font.isVertical && !font.isStandard14 && font.hasExplicitWidth(code)) {
            val fontWidth = font.getWidthFromFont(code)
            if (displacement.x > 0f && fontWidth > 0f && abs(fontWidth - displacement.x * 1000f) > 0.0001f)
                at.scale((displacement.x * 1000f / fontWidth).toDouble(), 1.0)
        }

        val glyphCache = glyphCaches.computeIfAbsent(font as PDVectorFont, PDFDrawer::GlyphCache)
        val glyph = Path2D.Float(glyphCache.getPathForCharacterCode(code), at)

        val renderingMode = graphicsState.textState.renderingMode
        if (isContentRendered()) {
            if (renderingMode.isFill)
                doDrawShape(glyph, stroking = false)
            if (renderingMode.isStroke)
                doDrawShape(glyph, stroking = true)
        }
        if (renderingMode.isClip)
            textClippings.add(glyph)
    }

    override fun showType3Glyph(textRenderingMatrix: Matrix?, font: PDType3Font?, code: Int, displacement: Vector?) {
        if (graphicsState.textState.renderingMode != RenderingMode.NEITHER)
            super.showType3Glyph(textRenderingMatrix, font, code, displacement)
    }

    override fun clip(windingRule: Int) {
        // the clipping path will not be updated until the succeeding painting operator is called
        group.clipWindingRule = windingRule
    }

    override fun getCurrentPoint(): Point2D =
        group.linePath.getCurrentPoint()

    override fun moveTo(x: Float, y: Float) {
        group.linePath.moveTo(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        group.linePath.lineTo(x, y)
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        group.linePath.curveTo(x1, y1, x2, y2, x3, y3)
    }

    override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
        group.linePath.apply {
            moveTo(p0.x.toFloat(), p0.y.toFloat())
            lineTo(p1.x.toFloat(), p1.y.toFloat())
            lineTo(p2.x.toFloat(), p2.y.toFloat())
            lineTo(p3.x.toFloat(), p3.y.toFloat())
            closePath()
        }
    }

    override fun closePath() {
        group.linePath.closePath()
    }

    override fun endPath() {
        val linePath = group.linePath
        if (group.clipWindingRule != -1) {
            linePath.setWindingRule(group.clipWindingRule)
            if (!linePath.getPathIterator(null).isDone)
                graphicsState.intersectClippingPath(adjustClipForPath(linePath))
            group.clipWindingRule = -1
        }
        linePath.reset()
    }

    override fun strokePath() {
        val linePath = group.linePath
        if (isContentRendered() && !linePath.getPathIterator(null).isDone)
            doDrawShape(linePath, stroking = true)
        linePath.reset()
    }

    override fun fillPath(windingRule: Int) {
        val linePath = group.linePath
        linePath.setWindingRule(windingRule)
        if (isContentRendered() && !linePath.getPathIterator(null).isDone)
            doDrawShape(linePath, stroking = false)
        linePath.reset()
    }

    override fun fillAndStrokePath(windingRule: Int) {
        val linePath = group.linePath
        linePath.setWindingRule(windingRule)
        if (isContentRendered() && !linePath.getPathIterator(null).isDone) {
            doDrawShape(linePath, stroking = false)
            doDrawShape(linePath, stroking = true)
        }
        linePath.reset()
    }

    override fun drawImage(pdImage: PDImage) {
        if (!isContentRendered() || pdImage is PDImageXObject && isHiddenOCG(pdImage.optionalContent))
            return
        val transform = graphicsState.currentTransformationMatrix.createAffineTransform()
        // Notice: PDF assumes that all images have size (1, 1), and that appropriate scaling is done by the CTM.
        transform.scale(1.0 / pdImage.width, 1.0 / pdImage.height)
        // Ignoring the context soft mask if the image has a MASK or SMASK is pretty weird, but PDFBox does it as well,
        // and we've observed that it indeed is necessary for some PDFs to be rendered correctly.
        val noSoftMask = pdImage.cosObject.containsKey(COSName.MASK) || pdImage.cosObject.containsKey(COSName.SMASK)
        if (pdImage.isStencil)
            convert1BitPDFImageToShortMask(pdImage)?.let { bitmap ->
                doDrawBitmap(bitmap, false, false, transform, false, true, pdImage.interpolate, noSoftMask)
                bitmap.close()
            }
        else
            convertPDFImageToXYZAD50(pdImage, group.devCS)?.let { (bitmap, promiseOpaque) ->
                val modBitmap = bitmap.use(::applyCurrentTransferFunction)
                doDrawBitmap(modBitmap, promiseOpaque, false, transform, false, false, pdImage.interpolate, noSoftMask)
                modBitmap.close()
            }
    }

    override fun shadingFill(shadingName: COSName) {
        if (!isContentRendered())
            return
        val ctm = graphicsState.currentTransformationMatrix
        val shading = resources.getShading(shadingName) ?: return
        val bbox = shading.bBox
        val area = Area(graphicsState.currentClippingPath)
        if (bbox != null)
            area.intersect(Area(bbox.transform(ctm)))
        else
            shading.getBounds(AffineTransform(), ctm)?.let { bounds -> area.intersect(Area(growAndRound(bounds, 1.0))) }
        if (!area.isEmpty)
            doDrawShape(area, stroking = false, forcedShading = shading, forcedShadingMatrix = ctm, applyClip = false)
    }

    override fun showForm(form: PDFormXObject) {
        if (!isContentRendered() || isHiddenOCG(form.optionalContent))
            return
        // You might wonder why we don't have any color space handling here. The only way for a FormXObject to change
        // its blending color space is via its "Group" sub-dictionary, which may contain a "CS" entry. By section 8.10.3
        // of the PDF specification, a "Group" dict must also contain a "S" entry with the only allowed value being
        // "Transparency". If this condition is met, PDFBox detects the FormXObject as a transparency group and invokes
        // showTransparencyGroup() instead of this method. So to sum up, when this method is invoked, the FormXObject
        // shall be drawn directly onto the current canvas without a color space change.
        // Notice that the PDF specification also talks about page groups. One might think that this could be relevant
        // for FormXObjects, since FormXObjects are sometimes used to embed foreign a page from another document into a
        // new document. However, the page group has nothing to do with that; instead, it is the root group of a page,
        // and as such, when drawing a page, we only encounter the page group once, and that's right at the start. In
        // this class, the page group's "CS" entry is handled in the drawTo() method.
        withGroup(group.copyCanvasesAndTransform()) { super.showForm(form) }
    }

    override fun showTransparencyGroup(form: PDTransparencyGroup) {
        if (!isContentRendered() || isHiddenOCG(form.optionalContent))
            return
        val ctm = graphicsState.currentTransformationMatrix
        val (tgBitmap, tgBounds) = renderTransparencyGroup(form, false, group.devCS, null, ctm) ?: return
        val offsetTransform = AffineTransform.getTranslateInstance(tgBounds.getX(), tgBounds.getY())
        doDrawBitmap(tgBitmap, false, true, offsetTransform, true, false, false, false)
        tgBitmap.close()
    }

    override fun beginMarkedContentSequence(tag: COSName?, properties: COSDictionary?) {
        if (nestedHiddenOCGCount > 0)
            nestedHiddenOCGCount++
        else if (properties != null && isHiddenOCG(PDPropertyList.create(properties)))
            nestedHiddenOCGCount = 1
    }

    override fun endMarkedContentSequence() {
        if (nestedHiddenOCGCount > 0)
            nestedHiddenOCGCount--
    }

    private fun isContentRendered() =
        nestedHiddenOCGCount <= 0

    private fun isHiddenOCG(propertyList: PDPropertyList?): Boolean {
        if (propertyList is PDOptionalContentGroup) {
            val printState = propertyList.getRenderState(destination)
            if (printState == null) {
                val ocProperties = document.documentCatalog.ocProperties
                if (ocProperties != null && !ocProperties.isGroupEnabled(propertyList))
                    return true
            } else if (printState == RenderState.OFF)
                return true
        } else if (propertyList is PDOptionalContentMembershipDictionary) {
            val veArray = propertyList.cosObject.getCOSArray(COSName.VE)
            if (veArray != null && veArray.size() != 0)
                return isHiddenVisibilityExpression(veArray)
            val ocgs = propertyList.ocGs.ifEmpty { return false }
            return when (propertyList.visibilityPolicy) {
                COSName.ALL_ON -> ocgs.any { isHiddenOCG(it) }
                COSName.ALL_OFF -> ocgs.any { !isHiddenOCG(it) }
                COSName.ANY_OFF -> ocgs.none { isHiddenOCG(it) }
                else -> ocgs.none { !isHiddenOCG(it) }
            }
        }
        return false
    }

    private fun isHiddenVisibilityExpression(veArray: COSArray): Boolean {
        if (veArray.size() == 0)
            return false
        when (veArray.getName(0)) {
            "And" -> {
                for (idx in 1..<veArray.size()) {
                    val base = veArray.getObject(idx)
                    if (base is COSArray && isHiddenVisibilityExpression(base) ||
                        base is COSDictionary && isHiddenOCG(PDPropertyList.create(base))
                    ) return true
                }
                return false
            }
            "Or" -> {
                for (idx in 1..<veArray.size()) {
                    val base = veArray.getObject(idx)
                    if (base is COSArray && !isHiddenVisibilityExpression(base) ||
                        base is COSDictionary && !isHiddenOCG(PDPropertyList.create(base))
                    ) return false
                }
                return true
            }
            "Not" ->
                return veArray.size() == 2 && when (val base = veArray.getObject(1)) {
                    is COSArray -> !isHiddenVisibilityExpression(base)
                    is COSDictionary -> !isHiddenOCG(PDPropertyList.create(base))
                    else -> false
                }
            else -> return false
        }
    }

    private fun adjustClipForPath(linePath: GeneralPath): GeneralPath {
        val transform = group.pageToCanvasTransform
        if (transform.type and AffineTransform.TYPE_MASK_SCALE != 0 &&
            transform.type and (AffineTransform.TYPE_TRANSLATION or AffineTransform.TYPE_FLIP or
                    AffineTransform.TYPE_MASK_SCALE).inv() == 0
        ) {
            val sx = abs(transform.scaleX)
            val sy = abs(transform.scaleY)
            if (sx > 1.0 && sy > 1.0)
                return linePath
            val bounds = linePath.bounds
            var w = bounds.getWidth()
            var h = bounds.getHeight()
            val sw = sx * w
            val sh = sy * h
            val minSize = 2.0
            if (sw < minSize || sh < minSize) {
                var x = bounds.getX()
                var y = bounds.getY()
                if (sw < minSize) {
                    w = minSize / sx
                    x = bounds.centerX - w / 2
                }
                if (sh < minSize) {
                    h = minSize / sy
                    y = bounds.centerY - h / 2
                }
                return GeneralPath(Rectangle2D.Double(x, y, w, h))
            }
        }
        return linePath
    }

    private val group: Group get() = groupStack.peek()

    private inline fun withGroup(group: Group, block: () -> Unit) {
        groupStack.push(group)
        try {
            block()
        } finally {
            groupStack.pop()
        }
    }


    /* ***************************************
       ********** DRAWING FUNCTIONS **********
       *************************************** */

    private fun doDrawShape(
        shape: Shape,
        stroking: Boolean,
        forcedShading: PDShading? = null,
        forcedShadingMatrix: Matrix? = null,
        applyClip: Boolean = true
    ) {
        val transform = group.pageToCanvasTransform

        // Configure alpha.
        val alpha = if (stroking) graphicsState.alphaConstant else graphicsState.nonStrokeAlphaConstant
        if (alpha <= 0.0) return
        // Configure stroke and compute the amount of pixels by which a stroked path is inflated.
        var stroke: BasicStroke? = null
        var strokeInflation = 0.0
        if (stroking) {
            stroke = currentStroke()
            strokeInflation = if (stroke.lineWidth == 0f) 1.0 else {
                var mult = 1.0
                if (stroke.endCap == BasicStroke.CAP_SQUARE) mult = 1.414 /* sqrt(2) */
                if (stroke.lineJoin == BasicStroke.JOIN_MITER) mult = max(mult, stroke.miterLimit.toDouble())
                stroke.lineWidth * 0.5 * mult
            }
        }
        // Configure color/pattern/shading.
        val boundsOnCanvas = lazy(LazyThreadSafetyMode.NONE) {
            growAndRound(shape.transformedBy(transform).bounds2D, strokeInflation).intersection(canvasBounds)
        }
        val shader = if (forcedShading != null)
            makeShadingShader(forcedShading, forcedShadingMatrix!!, boundsOnCanvas.value) ?: return
        else
            currentShader(stroking, boundsOnCanvas) ?: return
        // Configure blending and soft mask.
        val blendMode = currentBlendMode()
        val matte = currentSoftMaskMatte()

        val clip = if (!applyClip) emptyList() else group.clipCache.transform(graphicsState.currentClippingPaths)
        for (canvas in group.canvases)
            if (stroke != null)
                canvas.strokeShape(shape, stroke, shader, alpha, matte, 0.0, blendMode, transform, clip)
            else
                canvas.fillShape(shape, shader, alpha, matte, 0.0, blendMode, transform, clip)

        if (shader is Canvas.Shader.Image) shader.bitmap.close()
        matte?.bitmap?.close()
    }

    private fun doDrawBitmap(
        bitmap: Bitmap,
        promiseOpaque: Boolean,
        promiseClamped: Boolean,
        transform: AffineTransform,
        isTotalTransform: Boolean,
        isMask: Boolean,
        interpolate: Boolean,
        noSoftMask: Boolean
    ) {
        val alpha = graphicsState.nonStrokeAlphaConstant
        if (alpha <= 0.0) return

        val (w, h) = bitmap.spec.resolution
        val totalTransform = if (isTotalTransform) transform else AffineTransform(group.pageToCanvasTransform).apply {
            concatenate(transform)
            flipY(h.toDouble())
        }
        val boundsOnCanvas = lazy(LazyThreadSafetyMode.NONE) {
            Rectangle(w, h).transformedBy(totalTransform).bounds.intersection(canvasBounds)
        }
        val nearestNeigh = !interpolate && (totalTransform.scalingFactorX > 1.0 || totalTransform.scalingFactorY > 1.0)
        val shader = if (isMask) currentShader(stroking = false, boundsOnCanvas) ?: return else null
        val blendMode = currentBlendMode()
        val matte = if (noSoftMask) null else currentSoftMaskMatte()

        val clip = group.clipCache.transform(graphicsState.currentClippingPaths)
        for (canvas in group.canvases)
            if (shader != null)
                canvas.fillStencil(bitmap, shader, nearestNeigh, alpha, matte, 0.0, blendMode, totalTransform, clip)
            else
                canvas.drawImage(
                    bitmap, promiseOpaque, promiseClamped, nearestNeigh, alpha, matte, 0.0, blendMode, totalTransform,
                    clip
                )

        if (shader is Canvas.Shader.Image) shader.bitmap.close()
        matte?.bitmap?.close()
    }

    private val canvasBounds: Rectangle
        get() = group.let { Rectangle(ceil(it.width).toInt(), ceil(it.height).toInt()) }


    /* *****************************************
       ********** PAINT CONFIGURATORS **********
       ***************************************** */

    private fun currentStroke(): BasicStroke {
        val state = graphicsState

        // Minimum line width as used by Adobe Reader.
        val lineWidth = transformWidth(state.lineWidth).coerceAtLeast(0.25f)
        val lineCap = state.lineCap.coerceIn(0, 2)
        val lineJoin = state.lineJoin.coerceIn(0, 2)
        val miterLimit = state.miterLimit.let { if (it < 1f) 10f else it }

        val dashPattern = state.lineDashPattern
        var dashArray = dashPattern.dashArray
        if (dashArray.isEmpty() || dashArray.any { !it.isFinite() })
            dashArray = null
        // Show an all-zero dash array line invisible like Adobe does.
        else if (dashArray.all { it == 0f })
            dashArray = floatArrayOf(0f, 1f)
        else
            for (i in dashArray.indices)
                dashArray[i] = transformWidth(dashArray[i])
        val phaseStart = transformWidth(dashPattern.phase.toFloat()).coerceAtMost(Short.MAX_VALUE.toFloat())

        return BasicStroke(lineWidth, lineCap, lineJoin, miterLimit, dashArray, phaseStart)
    }

    private fun currentShader(stroking: Boolean, boundsOnCanvas: Lazy<Rectangle>): Canvas.Shader? {
        val pdColor = if (stroking) graphicsState.strokingColor else graphicsState.nonStrokingColor
        val pdCS = pdColor.colorSpace
        if (pdCS !is PDPattern) {
            val color = convertPDFColorToXYZAD50(pdColor, group.devCS) ?: return null
            return Canvas.Shader.Solid(Color4f(color, ColorSpace.XYZD50))
        } else {
            val pattern = pdCS.getPattern(pdColor)
            val fullMatrix = Matrix.concatenate(initialMatrix, pattern.matrix)
            return when (pattern) {
                is PDShadingPattern -> makeShadingShader(pattern.shading, fullMatrix, boundsOnCanvas.value)
                is PDTilingPattern -> when (pattern.paintType) {
                    PDTilingPattern.PAINT_COLORED ->
                        makeTilingShader(pattern, null, null, fullMatrix, boundsOnCanvas.value)
                    PDTilingPattern.PAINT_UNCOLORED ->
                        makeTilingShader(pattern, pdColor, pdCS.underlyingColorSpace, fullMatrix, boundsOnCanvas.value)
                    else -> null
                }
                else -> null
            }
        }
    }

    private fun makeShadingShader(shading: PDShading, matrix: Matrix, boundsOnCanvas: Rectangle): Canvas.Shader? {
        if (boundsOnCanvas.isEmpty) return null
        val totalTransform = matrix.createAffineTransform().apply { preConcatenate(group.pageToCanvasTransform) }
        val (shaderBitmap, promiseOpaque) =
            materializePDFShadingAsXYZAD50(shading, boundsOnCanvas, totalTransform, group.devCS) ?: return null
        val offsetTransform = AffineTransform.getTranslateInstance(boundsOnCanvas.minX, boundsOnCanvas.minY)
        return Canvas.Shader.Image(
            shaderBitmap, promiseOpaque, transform = offsetTransform, disregardUserTransform = true
        )
    }

    private fun makeTilingShader(
        pattern: PDTilingPattern,
        pouredColor: PDColor?,
        pouredCS: PDColorSpace?,
        matrix: Matrix,
        boundsOnCanvas: Rectangle
    ): Canvas.Shader? {
        if (boundsOnCanvas.isEmpty) return null

        val totalTransform = matrix.createAffineTransform().apply { preConcatenate(group.pageToCanvasTransform) }
        val (cellBitmap, cellBounds) = renderTilingCell(pattern, pouredColor, pouredCS, totalTransform) ?: return null

        // Transform the xStep and yStep vectors into canvas space, and then round them to have integer components only.
        val pts = doubleArrayOf(pattern.xStep.toDouble(), 0.0, 0.0, pattern.yStep.toDouble())
        totalTransform.deltaTransform(pts, 0, pts, 0, 2)
        var step1X = pts[0].roundToInt()
        var step1Y = pts[1].roundToInt()
        var step2X = pts[2].roundToInt()
        var step2Y = pts[3].roundToInt()

        // Ensure that step1 and step2 are non-zero and linearly independent vectors.
        if (step1X == 0 && step1Y == 0 && step2X == 0 && step2Y == 0) {
            step1X = 1
            step2Y = 1
        } else if (step1X == 0 && step1Y == 0)
            if (abs(step2X) < abs(step2Y)) step1X = 1 else step1Y = 1
        else if (step2X == 0 && step2Y == 0)
            if (abs(step1X) < abs(step1Y)) step2X = 1 else step2Y = 1
        else if (step1X == 0 && step2X == 0)
            if (abs(pts[0]) > abs(pts[2]))
                step1X = if (pts[0] > 0f) 1 else -1
            else
                step2X = if (pts[2] > 0f) 1 else -1
        else if (step1Y == 0 && step2Y == 0)
            if (abs(pts[1]) > abs(pts[3]))
                step1Y = if (pts[1] > 0f) 1 else -1
            else
                step2Y = if (pts[3] > 0f) 1 else -1
        else if (step2X != 0 && step2Y != 0 && abs(step1X / step2X.toDouble() - step1Y / step2Y.toDouble()) < 0.001)
            if (abs(step1X) > abs(step1Y) && abs(step1X) > abs(step2X) && abs(step1X) > abs(step2Y))
                step1X += step1X.sign
            else if (abs(step1Y) > abs(step2X) && abs(step1Y) > abs(step2Y))
                step1Y += step1Y.sign
            else if (abs(step2X) > abs(step2Y))
                step2X += step2X.sign
            else
                step2Y += step2Y.sign

        val shaderRes = Resolution(boundsOnCanvas.width, boundsOnCanvas.height)
        val shaderBitmap = Bitmap.allocate(Bitmap.Spec(shaderRes, cellBitmap.spec.representation))
        Canvas.forBitmap(shaderBitmap.zero()).use { canvas ->
            // Replicate the pattern cell on the shader bitmap. For each potential drawing location, test whether it
            // can be reached from the origin of cellBounds by only walking with step1 and step2. If yes, draw the cell.
            val offsetX = boundsOnCanvas.x - cellBounds.x
            val offsetY = boundsOnCanvas.y - cellBounds.y
            val minX = offsetX - (cellBounds.width - 1)
            val minY = offsetY - (cellBounds.height - 1)
            val maxX = offsetX + boundsOnCanvas.width
            val maxY = offsetY + boundsOnCanvas.height
            val d = step1X * step2Y - step1Y * step2X
            for (y in minY..<maxY)
                for (x in minX..<maxX)
                    if ((x * step2Y - y * step2X) % d == 0 && (y * step1X - x * step1Y) % d == 0)
                        canvas.drawImageFast(cellBitmap, x = x - offsetX, y = y - offsetY)
        }

        val offsetTransform = AffineTransform.getTranslateInstance(boundsOnCanvas.minX, boundsOnCanvas.minY)
        return Canvas.Shader.Image(
            shaderBitmap, promiseClamped = true, transform = offsetTransform, disregardUserTransform = true
        )
    }

    private fun currentBlendMode() =
        // Note the default cause should never happen, though if it does, just fall back to the normal blend mode.
        BLEND_MODE_LOOKUP.getOrDefault(graphicsState.blendMode, Canvas.BlendMode.SRC_OVER)

    // Note: In other PDF viewers, output intents don't modify Device* color spaces inside soft masks.
    // As we want to replicate that behavior, we pass devCS=null to every method that accepts it here.
    private fun currentSoftMaskMatte(): Canvas.Matte? {
        val softMask = graphicsState.softMask ?: return null
        val form = softMask.group ?: return null
        val matrix = softMask.initialTransformationMatrix
        val pdCS = form.group.getColorSpace(form.resources)
        val backdropColor = if (softMask.subType != COSName.LUMINOSITY) null else
            softMask.backdropColor?.let { bc ->
                if (pdCS == null || pdCS.numberOfComponents != bc.size()) null else
                    convertPDFColorToXYZAD50(bc.toFloatArray(), pdCS, null)
            } ?: floatArrayOf(0f, 0f, 0f, 1f)
        val transfer = try {
            softMask.transferFunction.let { if (it is PDFunctionTypeIdentity) null else it }
        } catch (_: IOException) {
            null
        }

        val (tgBitmap, tgBounds) = renderTransparencyGroup(form, true, null, backdropColor, matrix) ?: return null
        val res = tgBitmap.spec.resolution
        val (iw, ih) = res

        // Add a 1px-wide border around the mask and later fill it with the out-of-bounds value. Then, we later tell
        // Skia to clamp the image shader to its border, essentially filling the entire remaining plane with that value.
        val ow = iw + 2
        val oh = ih + 2
        val outPx = ShortArray(ow * oh)
        val borderAlpha: Short
        when (softMask.subType) {
            COSName.ALPHA -> {
                val inPx = tgBitmap.getF(iw * 4)
                borderAlpha = softMaskAlpha(0f, transfer)
                moveSoftMask(iw, ih) { i, o -> outPx[o] = softMaskAlpha(inPx[i + 3], transfer) }
            }
            COSName.LUMINOSITY -> {
                // Note: Because backdropColor is not null and has alpha of 1, the group bitmap is opaque, so we can
                // promiseOpaque and also don't need to worry about premultiplied alpha.
                if (pdCS is PDDeviceRGB) {
                    val inPx = tgBitmap.getF(iw * 4)
                    val b = backdropColor!!.clone()
                    ColorSpace.XYZD50.convert(tgBitmap.spec.representation.colorSpace!!, b, alpha = true, clamp = true)
                    borderAlpha = softMaskAlpha(deviceRGBLuminosity(b, 0), transfer)
                    moveSoftMask(iw, ih) { i, o -> outPx[o] = softMaskAlpha(deviceRGBLuminosity(inPx, i), transfer) }
                } else {
                    val inPx = if (pdCS is PDDeviceGray) tgBitmap.getF(iw * 4) else
                        Bitmap.allocate(Bitmap.Spec(res, Canvas.compatibleRepresentation(ColorSpace.XYZD50))).use { b ->
                            BitmapConverter.convert(tgBitmap, b, promiseOpaque = true)
                            b.getF(iw * 4)
                        }
                    borderAlpha = softMaskAlpha(backdropColor!![1], transfer)
                    moveSoftMask(iw, ih) { i, o -> outPx[o] = softMaskAlpha(inPx[i + 1], transfer) }
                }
            }
            else -> return null
        }
        tgBitmap.close()
        if (borderAlpha != 0.toShort())
            fillBorder(outPx, ow, oh, borderAlpha)

        val maskRepresentation = Bitmap.Representation(Bitmap.PixelFormat.of(AV_PIX_FMT_GRAY16LE))
        val maskBitmap = Bitmap.allocate(Bitmap.Spec(Resolution(ow, oh), maskRepresentation))
        maskBitmap.put(outPx, ow, byteOrder = ByteOrder.LITTLE_ENDIAN)
        val offset = AffineTransform.getTranslateInstance((tgBounds.x - 1).toDouble(), (tgBounds.y - 1).toDouble())
        return Canvas.Matte(maskBitmap, offset, Canvas.TileMode.CLAMP, disregardUserTransform = true)
    }

    private fun softMaskAlpha(arg: Float, fn: PDFunction?): Short {
        val alpha = if (fn == null) arg else try {
            fn.eval(floatArrayOf(arg))[0]
        } catch (_: IOException) {
            arg
        }
        return Math.round(alpha * 65535f).coerceIn(0, 65535).toShort()
    }

    // Defined in section 11.5.3 of the PDF specification.
    private fun deviceRGBLuminosity(colors: FloatArray, at: Int) =
        0.3f * colors[at] + 0.59f * colors[at + 1] + 0.11f * colors[at + 2]

    private inline fun moveSoftMask(iw: Int, ih: Int, perform: (Int, Int) -> Unit) {
        var i = 0
        var o = iw + 3
        repeat(ih) {
            repeat(iw) {
                perform(i, o)
                i += 4
                o++
            }
            o += 2
        }
    }

    private fun applyCurrentTransferFunction(bitmap: Bitmap): Bitmap {
        val transfers = try {
            val value = graphicsState.transfer
            when {
                value is COSArray && value.size() >= 3 -> (0..2).map { PDFunction.create(value.getObject(it)) }
                value is COSDictionary -> PDFunction.create(value).let { listOf(it, it, it) }
                else -> null
            }
        } catch (_: IOException) {
            null
        }
        if (transfers == null || transfers.all { it is PDFunctionTypeIdentity })
            return bitmap.view()

        val res = bitmap.spec.resolution
        val rep = Bitmap.Representation(
            // The transfer function seems to be defined on the output, so we need to convert to the output color space.
            Bitmap.PixelFormat.of(AV_PIX_FMT_RGBAF32), group.colorSpace, Bitmap.Alpha.STRAIGHT
        )
        val newBitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
        BitmapConverter.convert(bitmap, newBitmap)
        val (w, h) = res
        val seg = newBitmap.memorySegment(0)
        val ls = newBitmap.linesize(0)
        try {
            for (y in 0..<h) {
                var i = y * ls
                for (x in 0..<w) {
                    seg.putFloat(i + 0L, transfers[0].eval(floatArrayOf(seg.getFloat(i + 0L)))[0])
                    seg.putFloat(i + 4L, transfers[1].eval(floatArrayOf(seg.getFloat(i + 4L)))[1])
                    seg.putFloat(i + 8L, transfers[2].eval(floatArrayOf(seg.getFloat(i + 8L)))[2])
                    i += 16
                }
            }
            return newBitmap
        } catch (_: IOException) {
            // We get here when a PDFunction throws an IOException.
            newBitmap.close()
            return bitmap.view()
        }
    }


    /* ******************************************
       ********** SUB-BITMAP RENDERERS **********
       ****************************************** */

    private fun renderTilingCell(
        pattern: PDTilingPattern,
        pouredColor: PDColor?,
        pouredCS: PDColorSpace?,
        totalTransform: AffineTransform
    ): Pair<Bitmap, Rectangle>? {
        val boundsOnCurCanvas = (pattern.bBox ?: return null).toGeneralPath().apply { transform(totalTransform) }.bounds
        if (boundsOnCurCanvas.isEmpty)
            return null
        val res = Resolution(boundsOnCurCanvas.width, boundsOnCurCanvas.height)
        val rep = Canvas.compatibleRepresentation(group.colorSpace)
        val newBitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
        val newPageToCanvasTransform = AffineTransform().apply {
            translate(-boundsOnCurCanvas.minX, -boundsOnCurCanvas.minY)
            concatenate(group.pageToCanvasTransform)
        }
        Canvas.forBitmap(newBitmap.zero()).use { newCanvas ->
            withGroup(Group(arrayOf(newCanvas), newPageToCanvasTransform, group.devCS, -1)) {
                processTilingPattern(pattern, pouredColor, pouredCS)
            }
        }
        return Pair(newBitmap, boundsOnCurCanvas)
    }

    private fun renderTransparencyGroup(
        form: PDTransparencyGroup,
        forSoftMask: Boolean,
        devCS: DeviceCS?,
        backdropColor: FloatArray?,
        ctm: Matrix,
    ): Pair<Bitmap, Rectangle>? {
        val boundsOnCurCanvas = Area((form.bBox ?: return null).transform(Matrix.concatenate(ctm, form.matrix))).apply {
            // PDFBox's PageDrawer also does this intersection, apparently to prevent ginormous bounding boxes.
            intersect(graphicsState.currentClippingPath)
            transform(group.pageToCanvasTransform)
            if (isEmpty)
                return null
        }.bounds

        // Note: This getColorSpace() call always resolves the DefaultRGB etc. color spaces if present.
        val pdCS = form.group.getColorSpace(form.resources)
        val cs = when {
            // Soft masks never inherit their context's color space.
            forSoftMask ->
                convertRootPDFColorSpaceToColorSpace(pdCS, devCS)
            // Non-isolated groups and groups with unspecified color space inherit the parent group's color space.
            !form.group.isIsolated || pdCS == null ->
                group.colorSpace
            // If a device color space is specified, first look for the nearest compatible CIE-based color space down
            // the group stack, and if none is found, resort to using the device color space.
            pdCS is PDDeviceGray || pdCS is PDDeviceRGB || pdCS is PDDeviceCMYK ->
                groupStack.find { it.numCompsOfSpecifiedCIEBasedBlendingCS == pdCS.numberOfComponents }?.colorSpace
                    ?: convertPDFColorSpaceToColorSpace(pdCS, devCS)!!
            // CIE-based color spaces are directly used, and if erroneous, we fall back to the parent group's space.
            else ->
                convertPDFColorSpaceToColorSpace(pdCS, devCS) ?: group.colorSpace
        }
        val numCompsOfSpecifiedCIEBasedBlendingCS =
            if (pdCS is PDCalGray || pdCS is PDCalRGB || pdCS is PDICCBased) pdCS.numberOfComponents else -1

        val res = Resolution(boundsOnCurCanvas.width, boundsOnCurCanvas.height)
        val newBitmap = Bitmap.allocate(Bitmap.Spec(res, Canvas.compatibleRepresentation(cs)))
        val newCanvas = Canvas.forBitmap(newBitmap.zero())

        var backdropBitmap: Bitmap? = null
        var newAlphaCanvas: Canvas? = null
        var newAlphaBitmap: Bitmap? = null
        if (!forSoftMask && !form.group.isIsolated && hasBlendMode(form, HashSet())) {
            // Note: For vector canvases and first-level transparency groups, backdropBitmap will be null, so the render
            // would come out wrong. However, this "if"-condition only activates in very few PDFs, and the only time we
            // draw a PDF to a vector canvas is for PDF->SVG conversion, which is not faithful anyway due to Skia's
            // crappy SVG backend, so we don't care.
            backdropBitmap = group.canvases[0].bitmap
            if (backdropBitmap != null) {
                // backdropBitmap must be included in the group image but not in the group alpha.
                val offset = AffineTransform.getTranslateInstance(-boundsOnCurCanvas.minX, -boundsOnCurCanvas.minY)
                newCanvas.drawImage(backdropBitmap, promiseClamped = true, transform = offset)
                newAlphaBitmap = Bitmap.allocate(newBitmap.spec)
                newAlphaCanvas = Canvas.forBitmap(newAlphaBitmap.zero())
            }
        }

        if (backdropColor != null)
            newCanvas.fill(Canvas.Shader.Solid(Color4f(backdropColor, ColorSpace.XYZD50)))

        // Note: It is important for the backdrop stuff that "canvas" is first in the array, not "alphaCanvas".
        val newCanvases = if (newAlphaCanvas == null) arrayOf(newCanvas) else arrayOf(newCanvas, newAlphaCanvas)
        val newPageToCanvasTransform = AffineTransform().apply {
            translate(-boundsOnCurCanvas.minX, -boundsOnCurCanvas.minY)
            concatenate(group.pageToCanvasTransform)
        }
        withGroup(Group(newCanvases, newPageToCanvasTransform, devCS, numCompsOfSpecifiedCIEBasedBlendingCS)) {
            if (forSoftMask)
                processSoftMask(form)
            else
                processTransparencyGroup(form)
        }

        if (backdropBitmap != null)
            remBackdrop(newBitmap, newAlphaBitmap!!, backdropBitmap, boundsOnCurCanvas.x, boundsOnCurCanvas.y)

        newCanvas.close()
        newAlphaCanvas?.close()
        newAlphaBitmap?.close()
        return Pair(newBitmap, boundsOnCurCanvas)
    }

    /** See PDFBox's GroupGraphics.removeBackdrop() for an explanation of this function. */
    private fun remBackdrop(bitmap: Bitmap, alphaBitmap: Bitmap, backdropBitmap: Bitmap, offsetX: Int, offsetY: Int) {
        val (width, height) = bitmap.spec.resolution
        val (backdropWidth, backdropHeight) = backdropBitmap.spec.resolution

        val backdropView: Bitmap
        // Note that this is .. and not ..< on purpose! You can derive that from GroupGraphics.removeBackdrop().
        if (offsetX in 0..backdropWidth - width && offsetY in 0..backdropHeight - height)
            backdropView = backdropBitmap.view(offsetX, offsetY, width, height, 1)
        else {
            backdropView = Bitmap.allocate(backdropBitmap.spec.copy(resolution = Resolution(width, height)))
            backdropView.zero().blitLeniently(backdropBitmap, offsetX, offsetY, width, height, 0, 0)
        }

        val oSeg = bitmap.memorySegment(0)
        val aSeg = alphaBitmap.memorySegment(0)
        val bSeg = backdropView.memorySegment(0)
        val oLs = bitmap.linesize(0)
        val aLs = alphaBitmap.linesize(0)
        val bLs = backdropView.linesize(0)

        for (y in 0L..<height) {
            var o = y * oLs
            var a = y * aLs
            var b = y * bLs
            for (x in 0..<width) {
                // Note: These formulas differ from PDFBox and the PDF spec because (a) all the bitmaps have
                // premultiplied alpha, and (b) we optimized them a bit.
                val oA = oSeg.getFloat(o + 12L)
                val aA = aSeg.getFloat(a + 12L)
                val bA = bSeg.getFloat(b + 12L)
                if (oA <= 0f || aA <= 0f)
                    oSeg.asSlice(o, 16L).fill(0)
                else if (bA <= 0f) {
                    val oF = aA / oA
                    oSeg.putFloat(o + 0L, oF * oSeg.getFloat(o + 0L))
                    oSeg.putFloat(o + 4L, oF * oSeg.getFloat(o + 4L))
                    oSeg.putFloat(o + 8L, oF * oSeg.getFloat(o + 8L))
                    oSeg.putFloat(o + 12L, aA)
                } else {
                    val oF = (bA - bA * aA + aA) / oA
                    val bF = aA - 1f
                    oSeg.putFloat(o + 0L, (oF * oSeg.getFloat(o + 0L) + bF * bSeg.getFloat(b + 0L)).coerceIn(0f, 1f))
                    oSeg.putFloat(o + 4L, (oF * oSeg.getFloat(o + 4L) + bF * bSeg.getFloat(b + 4L)).coerceIn(0f, 1f))
                    oSeg.putFloat(o + 8L, (oF * oSeg.getFloat(o + 8L) + bF * bSeg.getFloat(b + 8L)).coerceIn(0f, 1f))
                    oSeg.putFloat(o + 12L, aA)
                }
                o += 16L
                a += 16L
                b += 16L
            }
        }

        backdropView.close()
    }


    companion object {

        private val BLEND_MODE_LOOKUP = mapOf(
            BlendMode.NORMAL to Canvas.BlendMode.SRC_OVER,
            BlendMode.MULTIPLY to Canvas.BlendMode.MULTIPLY,
            BlendMode.SCREEN to Canvas.BlendMode.SCREEN,
            BlendMode.OVERLAY to Canvas.BlendMode.OVERLAY,
            BlendMode.DARKEN to Canvas.BlendMode.DARKEN,
            BlendMode.LIGHTEN to Canvas.BlendMode.LIGHTEN,
            BlendMode.COLOR_DODGE to Canvas.BlendMode.COLOR_DODGE,
            BlendMode.COLOR_BURN to Canvas.BlendMode.COLOR_BURN,
            BlendMode.HARD_LIGHT to Canvas.BlendMode.HARD_LIGHT,
            BlendMode.SOFT_LIGHT to Canvas.BlendMode.SOFT_LIGHT,
            BlendMode.DIFFERENCE to Canvas.BlendMode.DIFFERENCE,
            BlendMode.EXCLUSION to Canvas.BlendMode.EXCLUSION,
            BlendMode.HUE to Canvas.BlendMode.HUE,
            BlendMode.SATURATION to Canvas.BlendMode.SATURATION,
            BlendMode.COLOR to Canvas.BlendMode.COLOR,
            BlendMode.LUMINOSITY to Canvas.BlendMode.LUMINOSITY
        )

        private val deviceCSCache = Collections.synchronizedMap(WeakHashMap<PDPage, DeviceCS>())
        private val glyphCaches = Collections.synchronizedMap(WeakHashMap<PDVectorFont, GlyphCache>())

        fun getDeviceColorSpaces(document: PDDocument, page: PDPage): DeviceCS = deviceCSCache.computeIfAbsent(page) {
            val outputIntents = mutableListOf<PDOutputIntent>()
            page.cosObject.getCOSArray(COSName.OUTPUT_INTENTS)?.mapTo(outputIntents) {
                PDOutputIntent((if (it is COSObject) it.getObject() else it) as COSDictionary)
            }
            outputIntents += document.documentCatalog.outputIntents
            // Put PDF/A output intents at the beginning; works nicely because the sorting is stable.
            outputIntents.sortBy { it.cosObject.getItem(COSName.S) != COSName.GTS_PDFA1 }
            var devGray: ICCProfile? = null
            var devRGB: ICCProfile? = null
            var devCMYK: ICCProfile? = null
            for (outputIntent in outputIntents) {
                if (outputIntent.outputConditionIdentifier.let { it != null && "sRGB" in it }) {
                    if (devRGB == null) devRGB = ICCProfile.of(ColorSpace.SRGB)
                } else {
                    val iccArray = COSArray(listOf(COSName.ICCBASED, outputIntent.destOutputIntent))
                    val awtCS = PDICCBased.create(iccArray, null).getAWTColorSpace() ?: continue
                    when (awtCS.type) {
                        ICC_ColorSpace.TYPE_GRAY -> if (devGray == null) devGray = ICCProfile.of(awtCS.profile)
                        ICC_ColorSpace.TYPE_RGB -> if (devRGB == null) devRGB = ICCProfile.of(awtCS.profile)
                        ICC_ColorSpace.TYPE_CMYK -> if (devCMYK == null) devCMYK = ICCProfile.of(awtCS.profile)
                    }
                }
                if (devGray != null && devRGB != null && devCMYK != null)
                    break
            }
            DeviceCS(devGray, devRGB, devCMYK)
        }

        fun sizeOfRotatedCropBox(page: PDPage): Dimension2D {
            val cropBox = page.cropBox
            var width = cropBox.width.toDouble()
            var height = cropBox.height.toDouble()
            if (page.rotation % 180 != 0)
                width = height.also { height = width }
            return Dimension2DDouble(width, height)
        }

        fun compensateForCropBoxAndRotation(andFlip: Boolean, page: PDPage): AffineTransform {
            val tr = AffineTransform()
            val cropBox = page.cropBox
            var rotation = page.rotation
            if (rotation != 0) {
                if (!andFlip) rotation = 360 - rotation
                when (rotation) {
                    90 -> tr.translate(cropBox.height.toDouble(), 0.0)
                    180 -> tr.translate(cropBox.width.toDouble(), cropBox.height.toDouble())
                    270 -> tr.translate(0.0, cropBox.width.toDouble())
                }
                tr.rotate(Math.toRadians(rotation.toDouble()))
            }
            if (andFlip)
                tr.flipY(cropBox.height.toDouble())
            tr.translate(-cropBox.lowerLeftX.toDouble(), -cropBox.lowerLeftY.toDouble())
            return tr
        }

        private fun AffineTransform.flipY(h: Double) {
            translate(0.0, h)
            scale(1.0, -1.0)
        }

        private fun growAndRound(r: Rectangle2D, g: Double): Rectangle {
            val x = floor(r.minX - g).toInt()
            val y = floor(r.minY - g).toInt()
            val w = ceil(r.maxX + g).toInt() - x
            val h = ceil(r.maxY + g).toInt() - y
            return Rectangle(x, y, w, h)
        }

        private fun fillBorder(image: ShortArray, w: Int, h: Int, value: Short) {
            // Fill the top and bottom rows, minus the right cell of the top row and left cell of the bottom row.
            val jump = w * (h - 1) + 1
            for (i in 0..<w - 1) {
                image[i] = value
                image[i + jump] = value
            }
            // Fill the remaining cells in the left and right columns.
            var i = w - 1
            while (i < w * h - 1) {
                image[i] = value
                image[i + 1] = value
                i += w
            }
        }

        private fun hasBlendMode(group: PDTransparencyGroup, groupsDone: MutableSet<COSBase>): Boolean {
            if (groupsDone.contains(group.cosObject))
                return false
            groupsDone.add(group.cosObject)
            val resources = group.resources ?: return false
            for (name in resources.extGStateNames) {
                val extGState = resources.getExtGState(name) ?: continue
                val blendMode = extGState.blendMode
                if (blendMode != BlendMode.NORMAL)
                    return true
            }
            // Recursively process nested transparency groups
            for (name in resources.xObjectNames) {
                val xObject = try {
                    resources.getXObject(name)
                } catch (_: IOException) {
                    continue
                }
                if (xObject is PDTransparencyGroup && hasBlendMode(xObject, groupsDone))
                    return true
            }
            return false
        }

        // If a root context (the page or a soft mask) doesn't specify a color space, or it's erroneous, fall back to
        // DeviceRGB, which resolves to either the RGB output intent or sRGB.
        private fun convertRootPDFColorSpaceToColorSpace(rootCS: PDColorSpace?, devCS: DeviceCS?) =
            rootCS?.let { convertPDFColorSpaceToColorSpace(it, devCS) }
                ?: convertPDFColorSpaceToColorSpace(PDDeviceRGB.INSTANCE, devCS)!!

    }


    private class Group(
        val canvases: Array<Canvas>,
        /** Transform from the page's coordinate system to the group's canvas' coordinate system. */
        val pageToCanvasTransform: AffineTransform,
        /** CIE-based Device* color spaces derived from the document's or page's output intents. */
        val devCS: DeviceCS?,
        /**
         * If this group mirrors a PDF transparency group, this field stores the number of components of whatever
         * CIE-based color space was specified as the group's blending color space, irrespective of whether it was
         * actually parsable for us. Otherwise, it should be -1.
         */
        val numCompsOfSpecifiedCIEBasedBlendingCS: Int
    ) {

        var clipWindingRule = -1
        val linePath = GeneralPath()
        val clipCache = ClipCache(pageToCanvasTransform)

        fun copyCanvasesAndTransform() =
            Group(canvases, pageToCanvasTransform, devCS, numCompsOfSpecifiedCIEBasedBlendingCS)

        val width get() = canvases[0].width
        val height get() = canvases[0].height
        val colorSpace get() = canvases[0].colorSpace

    }


    private class ClipCache(private val pageToCanvasTransform: AffineTransform) {

        private var source: List<Shape>? = null
        private var result: List<Shape>? = null

        fun transform(clip: List<Shape>): List<Shape> {
            if (!matchesSource(clip)) {
                source = clip.toMutableList()
                result = clip.map { it.transformedBy(pageToCanvasTransform) }
            }
            return result!!
        }

        private fun matchesSource(clip: List<Shape>): Boolean {
            val source = this.source
            if (source == null || source.size != clip.size)
                return false
            // Check for identity equality because we know that clip shapes are never modified after the fact.
            for (idx in source.indices)
                if (source[idx] !== clip[idx])
                    return false
            return true
        }

    }


    private class GlyphCache(private val font: PDVectorFont) {

        private val cache = HashMap<Int, Path2D.Float>()

        fun getPathForCharacterCode(code: Int): Path2D.Float = cache.computeIfAbsent(code) {
            try {
                // Return empty path for line feed on std14
                if (!font.hasGlyph(code) && font is PDSimpleFont && font.isStandard14 && code == 10)
                    Path2D.Float()
                else
                    font.getNormalizedPath(code)
            } catch (_: IOException) {
                Path2D.Float()
            }
        }

    }


    private class Dimension2DDouble(private var width: Double, private var height: Double) : Dimension2D() {
        override fun getWidth() = width
        override fun getHeight() = height
        override fun setSize(width: Double, height: Double) {
            this.width = width
            this.height = height
        }
    }

}
