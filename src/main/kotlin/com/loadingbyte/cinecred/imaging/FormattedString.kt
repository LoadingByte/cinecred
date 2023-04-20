package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import java.awt.*
import java.awt.font.GlyphVector
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.*
import java.text.AttributedString
import java.text.Bidi
import java.text.BreakIterator
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

    fun sub(startIdx: Int, endIdx: Int): FormattedString =
        FormattedString(string.substring(startIdx, endIdx), attrs.sub(startIdx, endIdx), locale, justificationWidth)

    fun trim(): FormattedString? {
        val startIdx = string.indexOfFirst { !it.isWhitespace() }
        val endIdx = string.indexOfLast { !it.isWhitespace() } + 1
        return if (startIdx == -1 || startIdx >= endIdx) null else sub(startIdx, endIdx)
    }

    /**
     * Fully justifies the formatted string to exactly fit the provided [width].
     */
    fun justify(width: Double): FormattedString =
        FormattedString(string, attrs, locale, justificationWidth = width)


    /* *******************************************************
       ********** INFORMATION EXTRACTION - VERTICAL **********
       ******************************************************* */

    private var _height: Double = Double.NaN
    private var _heightAboveBaseline: Double = Double.NaN

    val height: Double
        get() = run { ensureInitializedVertical(); _height }
    private val heightAboveBaseline: Double
        get() = run { ensureInitializedVertical(); _heightAboveBaseline }

    private fun ensureInitializedVertical() {
        if (!_height.isNaN())
            return

        // Find the tallest font, as well as the distance between the top of the string and the baseline of the
        // tallest font. In case there are multiple tallest fonts, take the largest distance.
        var maxFontHeight = 0.0
        var aboveBaseline = 0.0

        var idx = 0
        attrs.forEachRun { attr, _, _ ->
            val font = attr.font
            // These comparisons must be resilient against floating point inaccuracy.
            if (font.totalHeightPx >= maxFontHeight - 0.001) {
                aboveBaseline = when {
                    font.totalHeightPx > maxFontHeight + 0.001 -> font.totalHeightAboveBaselinePx
                    else -> max(aboveBaseline, font.totalHeightAboveBaselinePx)
                }
                maxFontHeight = font.totalHeightPx
            }
            idx++
        }
        _height = maxFontHeight
        _heightAboveBaseline = aboveBaseline
    }


    /* *********************************************************
       ********** INFORMATION EXTRACTION - HORIZONTAL **********
       ********************************************************* */

    private var _width: Double = Double.NaN
    private lateinit var _textLayout: TextLayout

    val width: Double
        get() = run { ensureInitializedHorizontal(); _width }
    private val textLayout: TextLayout
        get() = run { ensureInitializedHorizontal(); _textLayout }

    private fun ensureInitializedHorizontal() {
        if (!_width.isNaN())
            return

        CustomGlyphLayoutEngine.begin(makeCustomGlyphLayoutEngineConfigSource())
        val textLayout = TextLayout(textLayoutAttrStr.iterator, REF_FRC).let {
            if (justificationWidth.isNaN()) it else it.getJustifiedLayout(justificationWidth.toFloat())
        }
        CustomGlyphLayoutEngine.end()

        // The current Java implementation always uses the roman baseline everywhere. We take advantage of this
        // implementation detail and do not implement code for different baselines. In case this behavior
        // changes with a future Java version, we do not want out code to silently fail, hence we check here.
        check(textLayout.baseline.toInt() == java.awt.Font.ROMAN_BASELINE)

        _width = textLayout.advance.toDouble()
        _textLayout = textLayout
    }


    /* *****************************
       ********** DRAWING **********
       ***************************** */

    /**
     * The [y] coordinate used by this method differs from the one used by Graphics2D.
     * Here, the coordinate doesn't point to the baseline, but to the topmost part of the largest font.
     */
    fun drawTo(image: DeferredImage, x: Double, y: Y, layer: DeferredImage.Layer) {
        // Get all GlyphVectors which layout the string. They are ordered such that the first GlyphVector realizes
        // the first chars of the string, the next one realizes the chars immediately after that and so on. In case
        // there is BIDI (bidirectional) text, they are not necessarily ordered visually from left to right.
        // Note: We assume that every TextLineComponent of the TextLayout is indeed based on a GlyphVector.
        // Since we do not use TextAttribute.CHAR_REPLACEMENT in this class, that assumption will always hold.
        val gvs = textLayout.getGlyphVectors()

        // For each glyph vector, find the index into the string where that glyph vector starts and ends.
        val gvStartCharIndices = IntArray(gvs.size)
        var charCtr = 0
        for (gvIdx in gvs.indices) {
            gvStartCharIndices[gvIdx] = charCtr
            charCtr += textLayout.getGlyphVectorNumChars(gvIdx)
        }

        // Reorder the glyph vectors from left to right and enrich them with positioning and font information.
        // This yields a list of GlyphSegments.
        val gssArray = arrayOfNulls<GlyphSegment>(gvs.size)
        var rightBaseX = width
        for (gsIdx in gvs.lastIndex downTo 0) {
            val gvIdx = textLayout.visualToLogicalGvIdx(gsIdx)
            val gvStartCharIdx = gvStartCharIndices[gvIdx]
            val font = attrs.getAttr(gvStartCharIdx).font
            val baseX = textLayout.getGlyphVectorX(gvIdx).toDouble()
            val width = rightBaseX - baseX
            gssArray[gsIdx] = GlyphSegment(gvs[gvIdx], baseX, width, font)
            rightBaseX = baseX
        }
        val gss = gssArray.requireNoNulls().asList()

        // Draw each continuous stretch of glyph vectors that share the same design.
        // Do this by iterating through the visual glyph vector indices, i.e., from left to right. The lambda "action"
        // is called whenever a stretch of the same design has come to an end.
        // Note: We consider each GlyphVector in one whole piece. We can do this because we have provoked splits in the
        // TextLayout's TextLineComponents (and as such also in the GVs) at each point where the design changes.
        val yBaseline = y + heightAboveBaseline
        forEachStretch(
            numItems = gss.size,
            getItem = { gsIdx ->
                val gvIdx = textLayout.visualToLogicalGvIdx(gsIdx)
                attrs.getAttr(gvStartCharIndices[gvIdx]).design
            },
            action = { design, startGsIdx, endGsIdx ->
                drawSameDesignStretchTo(image, x, yBaseline, layer, gss.subList(startGsIdx, endGsIdx), design)
            })
    }

    private inline fun <T> forEachStretch(
        numItems: Int, getItem: (Int) -> T?, action: (T, Int, Int) -> Unit
    ) {
        var stretchValue: T? = null
        var stretchStartIdx = 0
        for (idx in 0 until numItems) {
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
        gss: List<GlyphSegment>, design: Design
    ) {
        // Find the x-coordinates delimiting the stretch.
        val xLeft = gss.first().baseX
        val xRight = gss.last().run { baseX + width }
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
                is Layer.Shape.Text -> sequenceOf(Form.GlyphSegments(anchor = center, gss, null))
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
                    for (i in 1 until forms.size)
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

    private class GlyphSegment(
        private val gv: GlyphVector,
        val baseX: Double,
        val width: Double,
        val font: Font
    ) {
        val outline: Shape = gv.getOutline(font.hOffsetPx.toFloat(), font.vOffsetPx.toFloat())
        val baseXOutline: Shape by lazy { gv.getOutline((font.hOffsetPx + baseX).toFloat(), font.vOffsetPx.toFloat()) }
        val glyphCodes: IntArray get() = gv.getGlyphCodes(0, gv.numGlyphs, null)
        fun getGlyphOffsetX(glyphIdx: Int) = glyphPos[2 * glyphIdx].toDouble()
        private val glyphPos by lazy { gv.getGlyphPositions(0, gv.numGlyphs + 1, null) }
        val glyphBounds: Rectangle2D get() = gv.visualBounds
    }

    private sealed interface Form {

        val anchor: Point2D
        val awtShape: Shape
        fun transform(tx: AffineTransform): Form
        fun drawTo(img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer)

        class AWTShape(override val anchor: Point2D, override val awtShape: Shape) : Form {

            override fun transform(tx: AffineTransform) = AWTShape(
                anchor = tx.transform(anchor, null),
                awtShape = if (awtShape is Path2D.Float) Path2D.Float(awtShape, tx) else Path2D.Double(awtShape, tx)
            )

            override fun drawTo(
                img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer
            ) {
                img.drawShape(coat, awtShape, x, yBaseline, fill = true, layer = layer)
            }

        }

        class GlyphSegments(
            override val anchor: Point2D,
            val gss: List<GlyphSegment>,
            val transform: AffineTransform?
        ) : Form {

            // Note: Glyph outlines turn out to be float paths, and hence, the conversions here are really efficient.

            override val awtShape: Shape
                get() {
                    if (gss.isEmpty())
                        return Path2D.Float()
                    val awtShape = Path2D.Float(gss[0].baseXOutline, transform)
                    for (i in 1 until gss.size)
                        awtShape.append(gss[i].baseXOutline.getPathIterator(transform), false)
                    return awtShape
                }

            override fun transform(tx: AffineTransform) = GlyphSegments(
                anchor = tx.transform(anchor, null),
                gss = gss,
                transform = if (transform == null) tx else AffineTransform(tx).apply { concatenate(transform) }
            )

            override fun drawTo(
                img: DeferredImage, x: Double, yBaseline: Y, coat: DeferredImage.Coat, layer: DeferredImage.Layer
            ) {
                for (gs in gss) {
                    val postTx = if (transform == null) null else AffineTransform().apply {
                        translate(-gs.baseX, 0.0)
                        concatenate(transform)
                        translate(gs.baseX, 0.0)
                    }
                    img.drawText(coat, TextImpl(gs, postTx), x + gs.baseX, yBaseline, layer)
                }
            }

        }

    }

    private class TextImpl(
        private val gs: GlyphSegment,
        private val postTx: AffineTransform?
    ) : DeferredImage.Text {

        override val transform = AffineTransform().apply {
            postTx?.let(::concatenate)
            scale(gs.font.hScaling, 1.0)
            translate(gs.font.hOffsetPx, gs.font.vOffsetPx)
        }

        override val heightAboveBaseline: Double
        override val heightBelowBaseline: Double

        init {
            val b = gs.glyphBounds
            val points = doubleArrayOf(b.minX, b.minY, b.maxX, b.minY, b.minX, b.maxY, b.maxX, b.maxY)
            transform.transform(points, 0, points, 0, 4)
            var yMax = Double.NEGATIVE_INFINITY
            var yMin = Double.POSITIVE_INFINITY
            for (i in 0 until 4) {
                val y = points[i * 2]
                yMax = max(yMax, y)
                yMin = min(yMin, y)
            }
            heightAboveBaseline = yMax
            heightBelowBaseline = -yMin
        }

        override val width get() = gs.width
        override val font get() = gs.font.awtFont
        override val transformedOutline by lazy {
            // Note: Glyph outlines turn out to be float paths, and hence, the transformation by tx is really efficient.
            if (postTx == null) gs.outline else Path2D.Float(gs.outline, postTx)
        }
        override val glyphCodes get() = gs.glyphCodes
        // Since we apply hScaling in the layout engine, we have to divide it out again here.
        override fun getGlyphOffsetX(glyphIdx: Int) = gs.getGlyphOffsetX(glyphIdx) / gs.font.hScaling

    }


    /* ****************************************************
       ********** INFORMATION EXTRACTION - OTHER **********
       **************************************************** */

    fun breakLines(wrappingWidth: Double): List<Int> {
        // Employ a LineBreakMeasurer to find the best spots to insert a newline.
        val breaks = mutableListOf(0)
        CustomGlyphLayoutEngine.begin(makeCustomGlyphLayoutEngineConfigSource())
        val tlAttrCharIter = textLayoutAttrStr.iterator
        val lineMeasurer = LineBreakMeasurer(tlAttrCharIter, BreakIterator.getLineInstance(locale), REF_FRC)
        while (lineMeasurer.position != tlAttrCharIter.endIndex) {
            val lineEndPos = lineMeasurer.nextOffset(wrappingWidth.toFloat())
            lineMeasurer.position = lineEndPos
            breaks.add(lineEndPos)
        }
        CustomGlyphLayoutEngine.end()
        return breaks
    }

    /**
     * An [AttributedString] that can be used to actually create a [TextLayout]. It employs AWT font attributes
     * and provokes splits in the TextLayout's TextLineComponents at strategic positions (see comments for details).
     */
    private val textLayoutAttrStr: AttributedString by lazy {
        val tlAttrStr = AttributedString(string)
        var counter = 0
        attrs.forEachRun { attr, runStartIdx, runEndIdx ->
            // Add AWT font attribute.
            tlAttrStr.addAttribute(TextAttribute.FONT, attr.font.awtFont, runStartIdx, runEndIdx)
            // Whenever something about the font changes (also non-AWT properties like our "vOffset") or the
            // design changes, provoke a split in the TextLayout's TextLineComponents by changing
            // the foreground color to a new one. This way, we later have an individual GlyphVector for each such
            // segment and can hence render each segment individually. We absolutely have to provoke the split during
            // the layouting phase and cannot just manually find the split points later because ligatures must not be
            // substituted when there is a split point between the ligatured characters, i.e., between two "f"-s.
            // That last point is also important for changes of the design, since we can't
            // find the exact split point of ligatured characters.
            tlAttrStr.addAttribute(TextAttribute.FOREGROUND, Color(counter++), runStartIdx, runEndIdx)
        }
        tlAttrStr
    }

    private fun makeCustomGlyphLayoutEngineConfigSource(): CustomGlyphLayoutEngine.ExtConfigSource {
        // If the string doesn't uniformly flow from left to right, prepare a mapping from visual to logical chars.
        var logToVis: IntArray? = null
        var visToLog: IntArray? = null
        val chars = string.toCharArray()
        if (Bidi.requiresBidi(chars, 0, chars.size)) {
            val bidi = Bidi(chars, 0, null, 0, chars.size, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            if (!bidi.isLeftToRight) {
                visToLog = bidi.visualToLogicalMap()
                logToVis = visToLog.inverseMap()
            }
        }

        return CustomGlyphLayoutEngine.ExtConfigSource { startCharIdx: Int, endCharIdx: Int ->
            val font = attrs.getAttr(startCharIdx).font
            // Get the fonts of the segment to the left and right of the current segment.
            val leftFont: Font?
            val rightFont: Font?
            if (visToLog == null || logToVis == null) {
                leftFont = if (startCharIdx == 0) null else attrs.getAttr(startCharIdx - 1).font
                rightFont = if (endCharIdx == string.length) null else attrs.getAttr(endCharIdx).font
            } else {
                var leftVisIdx = logToVis[startCharIdx]
                var rightVisIdx = logToVis[endCharIdx - 1]
                if (leftVisIdx > rightVisIdx)
                    leftVisIdx = rightVisIdx.also { rightVisIdx = leftVisIdx }
                leftFont = if (leftVisIdx == 0) null else attrs.getAttr(visToLog[leftVisIdx - 1]).font
                rightFont = if (rightVisIdx == string.lastIndex) null else attrs.getAttr(visToLog[rightVisIdx + 1]).font
            }
            // Find the inter-segment tracking to the left and the right. Each one is defined as the maximal tracking
            // of the current or the respective neighboring segment. Then add half of those inter-segment trackings
            // to this segment; the neighboring segments will also receive the same half trackings, so in the end,
            // they sum up to the whole inter-segment trackings.
            val bearingLeftPx = if (leftFont == null) 0.0 else max(font.trackingPx, leftFont.trackingPx) / 2.0
            val bearingRightPx = if (rightFont == null) 0.0 else max(font.trackingPx, rightFont.trackingPx) / 2.0
            // Assemble the extended configuration data object.
            CustomGlyphLayoutEngine.ExtConfig(
                locale, font.hScaling.toFloat(), font.trackingPx.toFloat(), bearingLeftPx.toFloat(),
                bearingRightPx.toFloat(), font.features
            )
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
        baseAWTFont: java.awt.Font,
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
        kerning: Boolean = false,
        ligatures: Boolean = false,
        val features: List<Feature> = emptyList()
    ) {

        val awtFont: java.awt.Font
        val unscaledAWTFont: java.awt.Font
        val fontHeightAboveBaselinePx: Double
        val totalHeightPx: Double
        val totalHeightAboveBaselinePx: Double
        val hOffsetPx: Double
        val vOffsetPx: Double
        val trackingPx: Double

        init {
            for ((key, value) in baseAWTFont.attributes)
                require(value == null || key in ALLOWED_FONT_ATTRS) { "Disallowed font attribute: $key" }

            val unscaledPointSize = findSize(baseAWTFont, fontHeightPx)
            val pointSize = unscaledPointSize * scaling

            hOffsetPx = if (hOffsetUnit == Unit.UNSCALED_EM) hOffset * unscaledPointSize else hOffset
            vOffsetPx = if (vOffsetUnit == Unit.UNSCALED_EM) vOffset * unscaledPointSize else vOffset

            val fontAttrs = HashMap<TextAttribute, Any>()
            fontAttrs[TextAttribute.SIZE] = pointSize
            if (hScaling != 1.0)
                fontAttrs[TextAttribute.WIDTH] = hScaling
            if (kerning)
                fontAttrs[TextAttribute.KERNING] = TextAttribute.KERNING_ON
            if (ligatures)
                fontAttrs[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
            awtFont = baseAWTFont.deriveFont(fontAttrs)

            unscaledAWTFont = awtFont.deriveFont(mapOf(TextAttribute.SIZE to unscaledPointSize))

            fontHeightAboveBaselinePx = unscaledAWTFont.lineMetrics.run { ascent + leading / 2.0 }
            totalHeightPx = fontHeightPx + leadingTopPx + leadingBottomPx
            totalHeightAboveBaselinePx = fontHeightAboveBaselinePx + leadingTopPx
            trackingPx = trackingEm * pointSize * hScaling
        }

        /*
         * The above init code sets [TextAttribute.WIDTH] on the AWT [Font] object to realize hScaling. This is odd
         * because we usually realize all text transformations ourselves at the end.
         *
         * We now illustrate why we need this special behavior. First note that [TextLayout] must know the final
         * positions and advances of each glyph to gain access to the final text width. Hence, we apply hScaling to
         * these glyph positions already in our [CustomGlyphLayoutEngine]. As such, the second stage, i.e., scaling the
         * glyph shapes, must be glyph-wise, meaning that we cannot just apply a transform that realizes hScaling to
         * whole rendered segments outline after the fact.
         *
         * It turns out that we cannot just utilize the [TextAttribute.TRANSFORM] text attribute for this, as doing that
         * messes with the metrics exploited by [TextLayout], thereby changing the layout procedure in undesired ways.
         * On the other hand, it also proves difficult to manually apply a glyph-wise transform to glyph outlines
         * generated by the JDK after the fact without sacrificing performance or deeply invading private JDK APIs.
         *
         * To solve this problem, we apply hScaling, which if you recall is the only transformation that is necessarily
         * glyph-wise, directly to the [Font] via [TextAttribute.WIDTH]. Doing only this thankfully does not mess with
         * the metrics. It however imposes loose limits on the admissible hScaling values, which we encode in the
         * styling constraints. The remaining transformations are applied later on to whole segment outlines.
         */


        enum class Unit { PIXEL, UNSCALED_EM }

        class Feature(val tag: String, val value: Int)

        companion object {

            private val ALLOWED_FONT_ATTRS = listOf(TextAttribute.FAMILY, TextAttribute.SIZE)

            /**
             * This method finds the font size that produces the requested target font height in pixels.
             * Theoretically, this can be done in closed form, see:
             * https://stackoverflow.com/questions/5829703/java-getting-a-font-with-a-specific-height-in-pixels
             *
             * However, tests have shown that the above method is not reliable with all fonts (e.g., not with
             * Open Sans). Therefore, this method uses a numerical search to find the font size.
             */
            private fun findSize(awtFont: java.awt.Font, targetHeightPx: Double): Float {
                // Step 1: Exponential search to determine the rough range of the font size we're looking for.
                var size = 2f
                // Upper-bound the number of repetitions to avoid:
                //   - Accidental infinite looping.
                //   - Too large fonts, as they cause the Java font rendering engine to destroy its own fonts.
                for (i in 0 until 10) {
                    val height = awtFont.deriveFont(size * 2f).lineMetrics.height
                    if (height >= targetHeightPx)
                        break
                    size *= 2f
                }

                // Step 2: Binary search to find the exact font size.
                // If $size is still 2, we look for a size between 0 and 4.
                // Otherwise, we look for a size between $size and $size*2.
                val minSize = if (size == 2f) 0f else size
                val maxSize = size * 2f
                var intervalLength = (maxSize - minSize) / 2f
                size = minSize + intervalLength
                // Upper-bound the number of repetitions to avoid accidental infinite looping.
                for (i in 0 until 20) {
                    intervalLength /= 2f
                    val height = awtFont.deriveFont(size).lineMetrics.height
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
                val color: Color
            ) : Coloring

            class Gradient(
                val color1: Color,
                val color2: Color,
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

        fun build(): FormattedString =
            FormattedString(
                stringBuilder.toString(), Attributes(numRuns, runAttrs, runEnds, 0, stringBuilder.length),
                locale, justificationWidth = Double.NaN
            )

    }


    private class Attributes(
        private val numRuns: Int,
        private val runAttrs: Array<Attribute?>,
        private val runEnds: IntArray,
        private val charLimStart: Int,
        private val charLimLen: Int
    ) {

        private fun getIdxOfRunContaining(charIdx: Int): Int {
            val runIdx = runEnds.binarySearch(charIdx + charLimStart, toIndex = numRuns)
            return if (runIdx >= 0) runIdx + 1 else -runIdx - 1
        }

        private val runLimStart = getIdxOfRunContaining(0)
        private val runLimEnd = getIdxOfRunContaining(charLimLen - 1) + 1

        fun sub(startIdx: Int, endIdx: Int) =
            Attributes(numRuns, runAttrs, runEnds, startIdx + charLimStart, endIdx - startIdx)

        fun getAttr(charIdx: Int) = runAttrs[getIdxOfRunContaining(charIdx)]!!

        inline fun forEachRun(
            action: (attr: Attribute, runStartCharIdx: Int, runEndCharIdx: Int) -> Unit
        ) {
            for (runIdx in runLimStart until runLimEnd) {
                val runStartCharIdx = if (runIdx == 0) 0 else (runEnds[runIdx - 1] - charLimStart).coerceAtLeast(0)
                val runEndCharIdx = (runEnds[runIdx] - charLimStart).coerceAtMost(charLimLen)
                action(runAttrs[runIdx]!!, runStartCharIdx, runEndCharIdx)
            }
        }

    }

}
