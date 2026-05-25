package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.flatMapToSequence
import com.loadingbyte.cinecred.common.transformedBy
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.Stroke
import java.awt.geom.*
import java.util.*
import kotlin.math.*


class FormattedString private constructor(
    val string: String,
    private val attrs: Attributes,
    private val locale: Locale,
    private val justificationWidth: Double
) {

    /* *************************************
       ********** MODIFIED COPIES **********
       ************************************* */

    /** Fully justifies the text to exactly fit the supplied width. */
    fun justified(justificationWidth: Double): FormattedString {
        val just = FormattedString(string, attrs, locale, justificationWidth = justificationWidth)
        if (::_glyphString.isInitialized)
            just.initializeHorizontalFrom(glyphString.justified(justificationWidth))
        return just
    }

    fun breakIntoLines(lineWidth: Double): List<FormattedString> {
        val lines = mutableListOf<FormattedString>()
        for ((lineGlyphString, lineStartCharIdx, lineEndCharIdx) in glyphString.breakIntoLines(lineWidth)) {
            val line = FormattedString(
                string.substring(lineStartCharIdx, lineEndCharIdx), attrs.sub(lineStartCharIdx, lineEndCharIdx),
                locale, justificationWidth = Double.NaN
            )
            line.initializeHorizontalFrom(lineGlyphString)
            lines += line
        }
        return lines
    }


    /* *******************************************************
       ********** INFORMATION EXTRACTION - VERTICAL **********
       ******************************************************* */

    private var _height: Double = Double.NaN
    private var _heightAboveBaseline: Double = Double.NaN
    private var _heightBelowBaseline: Double = Double.NaN

    val height: Double
        get() = run { ensureInitializedVertical(); _height }
    val heightAboveBaseline: Double
        get() = run { ensureInitializedVertical(); _heightAboveBaseline }
    val heightBelowBaseline: Double
        get() = run { ensureInitializedVertical(); _heightBelowBaseline }

    private fun ensureInitializedVertical() {
        if (!_height.isNaN())
            return

        // Find the greatest distance between the top of any font and the baseline, as well as for the bottom.
        // The string's height is then the sum of those two greatest distances.
        var aboveBaseline = 0.0
        var belowBaseline = 0.0
        attrs.forEachRun { attr, _, _ ->
            val font = attr.font
            aboveBaseline = max(aboveBaseline, font.totalHeightAboveBaselinePx)
            belowBaseline = max(belowBaseline, font.totalHeightBelowBaselinePx)
        }
        _height = aboveBaseline + belowBaseline
        _heightAboveBaseline = aboveBaseline
        _heightBelowBaseline = belowBaseline
    }


    /* *********************************************************
       ********** INFORMATION EXTRACTION - HORIZONTAL **********
       ********************************************************* */

    private var _width: Double = Double.NaN
    private var _missesGlyphs: Boolean = false
    private lateinit var _glyphString: GlyphString<Attribute>

    val width: Double
        get() = run { ensureInitializedHorizontal(); _width }
    val missesGlyphs: Boolean
        get() = run { ensureInitializedHorizontal(); _missesGlyphs }
    private val glyphString: GlyphString<Attribute>
        get() = run { ensureInitializedHorizontal(); _glyphString }

    private fun ensureInitializedHorizontal() {
        if (!_width.isNaN())
            return

        val runs = mutableListOf<GlyphString.Run<Attribute>>()
        attrs.forEachRun { attr, runStartCharIdx, _ ->
            val font = attr.font
            runs += GlyphString.Run(
                runStartCharIdx, font.fontCase, font.kerning, font.ligatures, font.features, font.hScaling,
                font.trackingPx, attr
            )
        }
        var glyphString = GlyphString.of(string, runs, locale)

        if (!justificationWidth.isNaN())
            glyphString = glyphString.justified(justificationWidth)

        initializeHorizontalFrom(glyphString)
    }

    private fun initializeHorizontalFrom(glyphString: GlyphString<Attribute>) {
        _width = glyphString.width
        _missesGlyphs = glyphString.segments.any { seg -> seg.hasMissingGlyph }
        _glyphString = glyphString
    }


    /* *****************************
       ********** DRAWING **********
       ***************************** */

    fun drawTo(image: DeferredImage, x: Double, yBaseline: Y, layer: DeferredImage.Layer) {
        // Draw each continuous stretch of glyph segments that share the same design.
        // Do this by iterating through the glyph segment indices, i.e., from left to right. The lambda "action"
        // is called whenever a stretch of the same design has come to an end.
        // Note: We consider each segment in one whole piece. We can do this because we have provoked splits in the
        // GlyphString's runs (and as such also in the segments) at each point where the design changes.
        val segments = glyphString.segments
        forEachStretch(
            numItems = segments.size,
            getItem = { segIdx -> segments[segIdx].userData.design },
            action = { design, startSegIdx, endSegIdx ->
                drawSameDesignStretchTo(image, x, yBaseline, layer, segments.subList(startSegIdx, endSegIdx), design)
            })
    }

    private inline fun <T> forEachStretch(
        numItems: Int, getItem: (Int) -> T?, action: (T, Int, Int) -> Unit
    ) {
        var stretchValue: T? = null
        var stretchStartIdx = 0
        for (idx in 0..<numItems) {
            val value = getItem(idx)
            if (stretchValue != null && stretchValue != value)
                action(stretchValue, stretchStartIdx, idx)
            if (value != stretchValue) {
                stretchValue = value
                stretchStartIdx = idx
            }
        }
        if (stretchValue != null)
            action(stretchValue, stretchStartIdx, numItems)
    }

    private fun drawSameDesignStretchTo(
        image: DeferredImage, x: Double, yBaseline: Y, imageLayer: DeferredImage.Layer,
        segments: List<GlyphString.Segment<Attribute>>, design: Design
    ) {
        // Find the x-coordinates delimiting the stretch.
        val xLeft = segments.first().baseX
        val xRight = segments.last().run { this.baseX + this.width }
        // Find the center point of the stretch.
        val center = Point2D.Double(
            (xLeft + xRight) / 2.0,
            design.masterFont.fontHeightPx / 2.0 - design.masterFont.fontHeightAboveBaselinePx
        )

        val formCache = arrayOfNulls<List<Form>>(design.layers.size)
        val visitingLayers = BooleanArray(design.layers.size)

        fun formLayer(layerIdx: Int): List<Form> {
            // Return early if this layer does not exist.
            if (layerIdx !in design.layers.indices) return emptyList()
            // Return early if we have already formed this layer.
            formCache[layerIdx]?.let { return it }
            // Return early if we have detected a reference cycle between layers.
            if (visitingLayers[layerIdx]) return emptyList()

            visitingLayers[layerIdx] = true
            val layer = design.layers[layerIdx]

            // Compute the basic forms making up the layer.
            var forms = when (val shape = layer.shape) {
                is Layer.Shape.Text -> sequenceOf(Form.GlyphSegments(anchor = center, segments, null))
                is Layer.Shape.Stripe -> sequenceOf(makeStripeForm(shape, xLeft, xRight))
                is Layer.Shape.Clone -> shape.layers.flatMapToSequence(::formLayer)
            }

            layer.dilation?.let { dilation ->
                val dilStroke =
                    BasicStroke((dilation.radiusPx * 2.0).toFloat(), capForJoin(dilation.join), dilation.join)
                forms = forms.map { form -> Form.AWTShape(form.anchor, dilate(form.awtShape, dilStroke)) }
            }

            // Convert the forms to contours if requested.
            layer.contour?.let { contour ->
                val stroke = BasicStroke(contour.thicknessPx.toFloat(), capForJoin(contour.join), contour.join)
                forms = forms.map { form -> Form.AWTShape(form.anchor, stroke.createStrokedShape(form.awtShape)) }
            }

            // Transform the forms if requested.
            val hasOffset = layer.hOffsetPx != 0.0 || layer.vOffsetPx != 0.0
            val hasScaling = layer.hScaling != 1.0 || layer.vScaling != 1.0
            val hasShearing = layer.hShearing != 0.0 || layer.vShearing != 0.0
            if (hasScaling || hasShearing) {
                val tx = AffineTransform()
                if (hasScaling) tx.scale(layer.hScaling, layer.vScaling)
                if (hasShearing) tx.shear(layer.hShearing, layer.vShearing)
                if (layer.anchorGlobal) {
                    val anchoredTx = anchor(tx, center, layer.hOffsetPx, layer.vOffsetPx, 0.0, 0.0)
                    forms = forms.map { form -> form.transform(anchoredTx) }
                } else {
                    // For "clone" layers with the "sibling" anchor setting, use the anchor of that sibling layer.
                    // If the sibling has multiple forms (a case which gives a warning in the UI), use the first form.
                    val siblingAnchor = if (layer.shape !is Layer.Shape.Clone) null else
                        formLayer(layer.shape.anchorSiblingLayer).firstOrNull()?.anchor
                    if (siblingAnchor != null) {
                        val anchoredTx = anchor(tx, siblingAnchor, 0.0, 0.0, layer.hOffsetPx, layer.vOffsetPx)
                        forms = forms.map { form -> form.transform(anchoredTx) }
                    } else
                        forms = forms.map { form ->
                            form.transform(anchor(tx, form.anchor, 0.0, 0.0, layer.hOffsetPx, layer.vOffsetPx))
                        }
                }
            } else if (hasOffset) {
                val tx = AffineTransform.getTranslateInstance(layer.hOffsetPx, layer.vOffsetPx)
                forms = forms.map { form -> form.transform(tx) }
            }

            // Poke holes into the forms to clear around other layers if requested.
            layer.clearing?.let { clearing ->
                val clearArea = Area()
                if (clearing.radiusPx == 0.0)
                    for (clearForm in clearing.layers.flatMapToSequence(::formLayer))
                        clearArea.add(Area(clearForm.awtShape))
                else {
                    val dilStroke =
                        BasicStroke((clearing.radiusPx * 2.0).toFloat(), capForJoin(clearing.join), clearing.join)
                    for (clearForm in clearing.layers.flatMapToSequence(::formLayer))
                        clearArea.add(Area(dilate(clearForm.awtShape, dilStroke)))
                }
                forms = forms.map { form ->
                    Form.AWTShape(form.anchor, Area(form.awtShape).apply { subtract(clearArea) })
                }
            }

            val formList = forms.toList()
            formCache[layerIdx] = formList
            visitingLayers[layerIdx] = false
            return formList
        }

        for ((layerIdx, layer) in design.layers.withIndex()) {
            // Skip invisible (likely helper) layers early for improved performance.
            val invisible = when (val c = layer.coloring) {
                is Layer.Coloring.Plain -> c.color.a == 0f
                is Layer.Coloring.Gradient -> c.color1.a == 0f && c.color2.a == 0f
            }
            if (invisible)
                continue
            val forms = formLayer(layerIdx)
            val coat = makeCoat(layer.coloring, center)
            if (layer.blurRadiusPx == 0.0)
                for (form in forms)
                    form.drawTo(image, x, yBaseline, coat, imageLayer)
            else if (forms.isNotEmpty()) {
                // Merge all forms into one shape and then blur all of them in one go. This makes a difference at points
                // where two forms come very close to each other, and in those cases, we pursue the behavior of other
                // editing programs.
                val mergedShape = if (forms.size == 1) forms[0].awtShape else {
                    val path = Path2D.Double(forms[0].awtShape)
                    for (i in 1..<forms.size)
                        path.append(forms[i].awtShape, false)
                    path
                }
                image.drawShape(coat, mergedShape, x, yBaseline, fill = true, layer.blurRadiusPx, imageLayer)
            }
        }
    }

    private fun capForJoin(join: Int): Int =
        if (join == BasicStroke.JOIN_ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_SQUARE

    private fun makeStripeForm(shape: Layer.Shape.Stripe, xLeft: Double, xRight: Double): Form {
        val x0 = xLeft - shape.widenLeftPx
        val x1 = xRight + shape.widenRightPx
        val x = min(x0, x1)
        val w = abs(x1 - x0)
        val y = shape.offsetPx - shape.heightPx / 2.0
        val h = shape.heightPx
        val r = min(shape.cornerRadiusPx, min(w, h) / 2.0)

        val anchor = Point2D.Double(x + w / 2.0, y + h / 2.0)

        val dashed = if (shape.dashPatternPx == null || shape.dashPatternPx.isEmpty()) null else
            BasicStroke(
                shape.heightPx.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                shape.dashPatternPx.toFloatArray(), 0f
            ).createStrokedShape(Line2D.Double(x, shape.offsetPx, x + w, shape.offsetPx))

        if (dashed != null && (shape.cornerJoin == BasicStroke.JOIN_MITER || r == 0.0))
            return Form.AWTShape(anchor, dashed)

        val rect = when (shape.cornerJoin) {
            BasicStroke.JOIN_MITER -> Rectangle2D.Double(x, y, w, h)
            BasicStroke.JOIN_ROUND -> RoundRectangle2D.Double(x, y, w, h, r * 2.0, r * 2.0)
            BasicStroke.JOIN_BEVEL -> Path2D.Double().apply {
                moveTo(x + r, y)
                lineTo(x + w - r, y)
                lineTo(x + w, y + r)
                lineTo(x + w, y + h - r)
                lineTo(x + w - r, y + h)
                lineTo(x + r, y + h)
                lineTo(x, y + h - r)
                lineTo(x, y + r)
                closePath()
            }
            else -> throw IllegalStateException()
        }

        val awtShape = if (dashed == null) rect else Area(rect).apply { intersect(Area(dashed)) }
        return Form.AWTShape(anchor, awtShape)
    }

    private fun anchor(
        transform: AffineTransform, anchor: Point2D, preTx: Double, preTy: Double, postTx: Double, postTy: Double
    ) = AffineTransform().apply {
        translate(anchor.x + postTx, anchor.y + postTy)
        concatenate(transform)
        translate(-anchor.x + preTx, -anchor.y + preTy)
    }

    /**
     * Dilates a shape, that is, extends its edges outwards. The amount of dilation in pixels is half
     * the thickness of the given [dilStroke].
     */
    private fun dilate(shape: Shape, dilStroke: Stroke): Shape {
        // To dilate a shape, we apply the following procedure:
        //  1. Convert the shape to an area. This "normalizes" the path by removing any overlaps of path segments.
        //     Also, the outermost sub-paths of an Area are oriented counter-clockwise, and any inner sub-paths
        //     which cut out holes or fill in parts of holes are oriented in the inverse direction of their
        //     surrounding sub-path. All this means that the left side of any sub-path always points to a filled
        //     area, while the right side always points to non-filled area.
        val area = Area(shape)
        //  2. Compute a shape whose interior realizes a stroke along the edges of the area shape. Because of the
        //     way Java2D's algorithmic stroking works, that stroke shape will have two sub-paths for each sub-path
        //     of the area shape. Looking at such a pair of sub-paths, the first one traces along the left side of
        //     the original sub-path in the original direction while the second one traces along the right side in
        //     the reverse direction.
        val stroke = dilStroke.createStrokedShape(area)
        //  3. Extract the second, fourth, sixth, and so on sub-path from the stroke shape and collect them in the
        //     final output shape. As we have noted, these sub-paths trace along the right side of the sub-paths of
        //     the area shape, and to the right side of those lies the exterior. Hence, the collected sub-paths form
        //     a dilated version of the area shape.
        //     You may wonder why we don't just use the dilated stroke directly and combine it with the original shape
        //     by just adding it to the area object. First, measurements have shown this to be at least 2x slower than
        //     our current approach, even when trying to minimize the area operations by also considering the sole
        //     caller of this method. Second, with shapes like the symbol ^ and the miter join rule, when looking
        //     at the peak, the stroke behaves correctly for the upper part, but extends out even above the upper part
        //     for the lower part because the angle there is quite acute, thereby producing an incorrect result with
        //     an artificial peak extending out above the dilated shape.
        //     Note: Stroke shapes are double paths, and hence, we also use doubles to avoid conversions to floats.
        val pi = stroke.getPathIterator(null)
        val coords = DoubleArray(6)
        val out = Path2D.Double(pi.windingRule)
        var flag = true
        while (!pi.isDone) {
            val type = pi.currentSegment(coords)
            if (type == PathIterator.SEG_MOVETO)
                flag = !flag
            if (flag)
                when (type) {
                    PathIterator.SEG_MOVETO ->
                        out.moveTo(coords[0], coords[1])
                    PathIterator.SEG_LINETO ->
                        out.lineTo(coords[0], coords[1])
                    PathIterator.SEG_QUADTO ->
                        out.quadTo(coords[0], coords[1], coords[2], coords[3])
                    PathIterator.SEG_CUBICTO ->
                        out.curveTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5])
                    PathIterator.SEG_CLOSE ->
                        out.closePath()
                }
            pi.next()
        }
        return out
    }

    private fun makeCoat(coloring: Layer.Coloring, center: Point2D.Double): DeferredImage.Coat =
        when (coloring) {
            is Layer.Coloring.Plain -> DeferredImage.Coat.Plain(coloring.color)
            is Layer.Coloring.Gradient -> {
                val angleRad = Math.toRadians(coloring.angleDeg)
                val dx = sin(angleRad)
                val dy = cos(angleRad)
                val ext = coloring.extentPx.coerceAtLeast(0.01)  // Otherwise, the gradient disappears.
                val offset1 = coloring.shiftPx - ext / 2.0
                val offset2 = coloring.shiftPx + ext / 2.0
                DeferredImage.Coat.Gradient(
                    coloring.color1, coloring.color2,
                    point1 = Point2D.Double(center.x + offset1 * dx, center.y + offset1 * dy),
                    point2 = Point2D.Double(center.x + offset2 * dx, center.y + offset2 * dy)
                )
            }
        }

    private sealed interface Form {

        val anchor: Point2D
        val awtShape: Shape
        fun transform(tx: AffineTransform): Form
        fun drawTo(img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer)

        class AWTShape(override val anchor: Point2D, override val awtShape: Shape) : Form {

            override fun transform(tx: AffineTransform) = AWTShape(
                anchor = tx.transform(anchor, null),
                awtShape = awtShape.transformedBy(tx)
            )

            override fun drawTo(
                img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer
            ) {
                img.drawShape(coat, awtShape, x, yBaseline, fill = true, layer = layer)
            }

        }

        class GlyphSegments(
            override val anchor: Point2D,
            val segments: List<GlyphString.Segment<Attribute>>,
            val transform: AffineTransform?
        ) : Form {

            override val awtShape by lazy {
                val path = Path2D.Float()
                for (seg in segments) {
                    val font = seg.userData.font
                    seg.appendOutlineTo(path, seg.baseX + font.hOffsetPx, font.vOffsetPx)
                }
                transform?.let(path::transform)
                path
            }

            override fun transform(tx: AffineTransform) = GlyphSegments(
                anchor = tx.transform(anchor, null),
                segments = segments,
                transform = if (transform == null) tx else AffineTransform(tx).apply { concatenate(transform) }
            )

            override fun drawTo(
                img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer
            ) {
                for (seg in segments) {
                    val postTx = if (transform == null) null else AffineTransform().apply {
                        translate(-seg.baseX, 0.0)
                        concatenate(transform)
                        translate(seg.baseX, 0.0)
                    }
                    img.drawText(coat, TextImpl(seg, postTx), x + seg.baseX, yBaseline, layer)
                }
            }

        }

    }

    private class TextImpl(
        private val seg: GlyphString.Segment<Attribute>,
        private val postTx: AffineTransform?
    ) : DeferredImage.Text {

        override val bounds: Rectangle2D

        init {
            val font = seg.userData.font
            val b = seg.getOutlineBounds(font.hOffsetPx, font.vOffsetPx)
            if (postTx == null)
                bounds = b
            else {
                val points = doubleArrayOf(b.minX, b.minY, b.maxX, b.minY, b.minX, b.maxY, b.maxX, b.maxY)
                postTx.transform(points, 0, points, 0, 4)
                var minX = Double.POSITIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                for (i in 0..<4) {
                    var x = points[i * 2]
                    val y = points[i * 2 + 1]
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
                bounds = Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
            }
        }

        override val outline by lazy {
            val font = seg.userData.font
            val path = seg.appendOutlineTo(Path2D.Float(), font.hOffsetPx, font.vOffsetPx)
            postTx?.let(path::transform)
            path
        }

        override val glyphCount: Int get() = seg.glyphCount
        override fun getGlyph(glyphIdx: Int) = seg.getGlyph(glyphIdx)
        override val string get() = seg.string
        override val fontCase get() = seg.fontCase

        override fun getManualGlyphPositionX(glyphIdx: Int) = seg.getGlyphPositionXPreHScaling(glyphIdx)
        override fun getManualGlyphPositionY(glyphIdx: Int) = seg.getGlyphPositionY(glyphIdx)

        override val manualTransform = AffineTransform().apply {
            postTx?.let(::concatenate)
            val font = seg.userData.font
            scale(font.hScaling, 1.0)
            translate(font.hOffsetPx, font.vOffsetPx)
        }

    }


    companion object {
        private fun DoubleArray.toFloatArray() = FloatArray(size) { idx -> this[idx].toFloat() }
    }


    class Attribute(
        val font: Font,
        val design: Design
    )


    class Font(
        baseFontCase: Font.Case,
        val fontHeightPx: Double,
        leadingTopPx: Double = 0.0,
        leadingBottomPx: Double = 0.0,
        scaling: Double = 1.0,
        val hScaling: Double = 1.0,
        hOffset: Double = 0.0,
        hOffsetUnit: Unit = Unit.PIXEL,
        vOffset: Double = 0.0,
        vOffsetUnit: Unit = Unit.PIXEL,
        trackingEm: Double = 0.0,
        val kerning: Boolean = false,
        val ligatures: Boolean = false,
        val features: List<Font.Feature> = emptyList()
    ) {

        val fontCase: Font.Case
        val unscaledFontCase: Font.Case
        val fontHeightAboveBaselinePx: Double
        val fontHeightBelowBaselinePx: Double
        val totalHeightAboveBaselinePx: Double
        val totalHeightBelowBaselinePx: Double
        val hOffsetPx: Double
        val vOffsetPx: Double
        val trackingPx: Double

        init {
            val unscaledPointSize = findSize(baseFontCase, fontHeightPx)
            val pointSize = unscaledPointSize * scaling

            hOffsetPx = if (hOffsetUnit == Unit.UNSCALED_EM) hOffset * unscaledPointSize else hOffset
            vOffsetPx = if (vOffsetUnit == Unit.UNSCALED_EM) vOffset * unscaledPointSize else vOffset

            fontCase = baseFontCase.withSize(pointSize)
            unscaledFontCase = fontCase.withSize(unscaledPointSize)

            fontHeightAboveBaselinePx = unscaledFontCase.run { ascent + lineGap / 2.0 }
            fontHeightBelowBaselinePx = unscaledFontCase.run { descent + lineGap / 2.0 }
            totalHeightAboveBaselinePx = fontHeightAboveBaselinePx + leadingTopPx
            totalHeightBelowBaselinePx = fontHeightBelowBaselinePx + leadingBottomPx
            trackingPx = trackingEm * pointSize
        }


        enum class Unit { PIXEL, UNSCALED_EM }

        companion object {

            /**
             * This method finds the font size that produces the requested target font height in pixels.
             * Theoretically, this can be done in closed form, see:
             * https://stackoverflow.com/questions/5829703/java-getting-a-font-with-a-specific-height-in-pixels
             *
             * However, tests have shown that the above method is not reliable with all fonts (e.g., not with
             * Open Sans). Therefore, this method uses a numerical search to find the font size.
             */
            private fun findSize(fontCase: Font.Case, targetHeightPx: Double): Double {
                // Step 1: Exponential search to determine the rough range of the font size we're looking for.
                var size = 2.0
                // Upper-bound the number of repetitions to avoid:
                //   - Accidental infinite looping.
                //   - Too large fonts, as they cause the Java font rendering engine to destroy its own fonts.
                for (i in 0..<10) {
                    val height = fontCase.withSize(size * 2.0).height
                    if (height >= targetHeightPx)
                        break
                    size *= 2.0
                }

                // Step 2: Binary search to find the exact font size.
                // If $size is still 2, we look for a size between 0 and 4.
                // Otherwise, we look for a size between $size and $size*2.
                val minSize = if (size == 2.0) 0.0 else size
                val maxSize = size * 2.0
                var intervalLength = (maxSize - minSize) / 2.0
                size = minSize + intervalLength
                // Upper-bound the number of repetitions to avoid accidental infinite looping.
                for (i in 0..<20) {
                    intervalLength /= 2.0
                    val height = fontCase.withSize(size).height
                    when {
                        abs(height - targetHeightPx) < 0.001 -> break
                        height > targetHeightPx -> size -= intervalLength
                        height < targetHeightPx -> size += intervalLength
                    }
                }

                return size
            }

        }

    }


    // Note: An important property of this class is to have identity-based equality.
    class Design(
        val masterFont: Font,
        val layers: List<Layer>
    )


    class Layer(
        val coloring: Coloring,
        val shape: Shape,
        val dilation: Dilation? = null,
        val contour: Contour? = null,
        val hOffsetPx: Double = 0.0,
        val vOffsetPx: Double = 0.0,
        val hScaling: Double = 1.0,
        val vScaling: Double = 1.0,
        val hShearing: Double = 0.0,
        val vShearing: Double = 0.0,
        val anchorGlobal: Boolean = false,
        val clearing: Clearing? = null,
        val blurRadiusPx: Double = 0.0
    ) {

        sealed interface Coloring {

            class Plain(
                val color: Color4f
            ) : Coloring

            class Gradient(
                val color1: Color4f,
                val color2: Color4f,
                val angleDeg: Double,
                val extentPx: Double,
                val shiftPx: Double = 0.0
            ) : Coloring

        }

        sealed interface Shape {

            object Text : Shape

            class Stripe(
                val heightPx: Double,
                val offsetPx: Double,
                val widenLeftPx: Double = 0.0,
                val widenRightPx: Double = 0.0,
                val cornerJoin: Int = BasicStroke.JOIN_MITER,
                val cornerRadiusPx: Double = 0.0,
                val dashPatternPx: DoubleArray? = null
            ) : Shape

            class Clone(
                val layers: IntArray,
                val anchorSiblingLayer: Int = -1
            ) : Shape

        }

        class Dilation(
            val radiusPx: Double,
            val join: Int = BasicStroke.JOIN_MITER
        )

        class Contour(
            val thicknessPx: Double,
            val join: Int = BasicStroke.JOIN_MITER
        )

        class Clearing(
            val layers: IntArray,
            val radiusPx: Double,
            val join: Int = BasicStroke.JOIN_MITER
        )

    }


    class Builder(private val locale: Locale) {

        private val stringBuilder = StringBuilder()
        private var numRuns = 0
        private var runAttrs = arrayOfNulls<Attribute?>(1)
        private var runEnds = IntArray(1)

        fun append(string: String, attribute: Attribute) {
            if (string.isEmpty())
                return
            stringBuilder.append(string)
            if (runAttrs.size == numRuns) {
                runAttrs = runAttrs.copyOf(numRuns * 4)
                runEnds = runEnds.copyOf(numRuns * 4)
            }
            runAttrs[numRuns] = attribute
            runEnds[numRuns] = stringBuilder.length
            numRuns++
        }

        fun build(): FormattedString {
            check(stringBuilder.isNotEmpty()) { "A FormattedString must not be empty." }
            return FormattedString(
                stringBuilder.toString(), Attributes(numRuns, runAttrs, runEnds, 0, stringBuilder.length),
                locale, justificationWidth = Double.NaN
            )
        }

    }


    private class Attributes(
        private val numRuns: Int,
        private val runAttrs: Array<Attribute?>,
        private val runEndCharInd: IntArray,
        private val charsLim0: Int,
        private val charsLim1: Int
    ) {

        private fun getIdxOfRunContaining(charIdx: Int): Int {
            val runIdx = runEndCharInd.binarySearch(charIdx, toIndex = numRuns)
            return if (runIdx >= 0) runIdx + 1 else -runIdx - 1
        }

        private val runsLim0 = getIdxOfRunContaining(charsLim0)
        private val runsLim1 = getIdxOfRunContaining(charsLim1 - 1) + 1

        fun sub(startCharIdx: Int, endCharIdx: Int) =
            Attributes(numRuns, runAttrs, runEndCharInd, charsLim0 + startCharIdx, charsLim0 + endCharIdx)

        inline fun forEachRun(
            action: (attr: Attribute, runStartCharIdx: Int, runEndCharIdx: Int) -> Unit
        ) {
            for (runIdx in runsLim0..<runsLim1) {
                val runStartCharIdx = if (runIdx == 0) 0 else (runEndCharInd[runIdx - 1] - charsLim0).coerceAtLeast(0)
                val runEndCharIdx = (runEndCharInd[runIdx] - charsLim0).coerceAtMost(charsLim1 - charsLim0)
                action(runAttrs[runIdx]!!, runStartCharIdx, runEndCharIdx)
            }
        }

    }

}
