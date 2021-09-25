package com.loadingbyte.cinecred.common

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Shape
import java.awt.Stroke
import java.awt.font.GlyphVector
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.*
import java.text.AttributedString
import java.text.BreakIterator
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class FormattedString private constructor(
    val string: String,
    private val attrs: Attributes,
    private val locale: Locale,
    private val justificationWidth: Float
) {

    /* ***************************************
       ************ MODIFIED COPIES **********
       *************************************** */

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
    fun justify(width: Float): FormattedString =
        FormattedString(string, attrs, locale, justificationWidth = width)


    /* *********************************************************
       ************ INFORMATION EXTRACTION - VERTICAL **********
       ********************************************************* */

    private var _height: Float = Float.NaN
    private var _heightAboveBaseline: Float = Float.NaN

    val height: Float
        get() = run { ensureInitializedVertical(); _height }
    val heightAboveBaseline: Float
        get() = run { ensureInitializedVertical(); _heightAboveBaseline }

    private fun ensureInitializedVertical() {
        if (!_height.isNaN())
            return

        // Find the tallest font, as well as the distance between the top of the string and the baseline of the
        // tallest font. In case there are multiple tallest fonts, take the largest distance.
        var maxFontHeight = 0f
        var aboveBaseline = 0f

        for (font in attrs.distinctFonts) {
            val lm = font.awtFont.lineMetrics
            // The current Java implementation always uses the roman baseline everywhere. We take advantage of this
            // implementation detail and do not implement code for different baselines. In case this behavior
            // changes with a future Java version, we do not want out code to silently fail, hence we check here.
            check(lm.baselineIndex == java.awt.Font.ROMAN_BASELINE)
            if (font.heightPx >= maxFontHeight) {
                maxFontHeight = font.heightPx
                aboveBaseline = max(aboveBaseline, (lm.ascent + lm.leading / 2f) / font.scaling /* ignore scaling */)
            }
        }
        _height = maxFontHeight
        _heightAboveBaseline = aboveBaseline
    }


    /* ***********************************************************
       ************ INFORMATION EXTRACTION - HORIZONTAL **********
       *********************************************************** */

    private var _width: Float = Float.NaN
    private lateinit var _segments: List<Segment>
    private lateinit var _decorationsUnderlay: List<Pair<Shape, Color>>
    private lateinit var _decorationsOverlay: List<Pair<Shape, Color>>
    private lateinit var _backgrounds: List<Pair<Rectangle2D, Color>>

    val width: Float
        get() = run { ensureInitializedHorizontal(); _width }
    val segments: List<Segment>
        get() = run { ensureInitializedHorizontal(); _segments }
    val decorationsUnderlay: List<Pair<Shape, Color>>
        get() = run { ensureInitializedHorizontal(); _decorationsUnderlay }
    val decorationsOverlay: List<Pair<Shape, Color>>
        get() = run { ensureInitializedHorizontal(); _decorationsOverlay }
    val backgrounds: List<Pair<Rectangle2D, Color>>
        get() = run { ensureInitializedHorizontal(); _backgrounds }

    private fun ensureInitializedHorizontal() {
        if (!_width.isNaN())
            return

        CustomGlyphLayoutEngine.begin(customGlyphLayoutEngineConfigSource)

        val textLayout = TextLayout(textLayoutAttrStr.iterator, REF_FRC).let {
            if (justificationWidth.isNaN()) it else it.getJustifiedLayout(justificationWidth)
        }
        // The current Java implementation always uses the roman baseline everywhere. We take advantage of this
        // implementation detail and do not implement code for different baselines. In case this behavior
        // changes with a future Java version, we do not want out code to silently fail, hence we check here.
        check(textLayout.baseline.toInt() == java.awt.Font.ROMAN_BASELINE)

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

        // For each glyph vector apart from the visually leftmost one, find the gap between that glyph vector and
        // the glyph vector left to it. This gap is defined as the maximum tracking of those two glyph vectors.
        val gvLeftHandGaps = FloatArray(gvs.size)
        for (visualGvIdx in 1 until gvs.size) {
            val leftGvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx - 1)
            val rightGvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
            val leftFont = attrs.getAttr(gvStartCharIndices[leftGvIdx]).font
            val rightFont = attrs.getAttr(gvStartCharIndices[rightGvIdx]).font
            gvLeftHandGaps[rightGvIdx] = max(leftFont.trackingPx, rightFont.trackingPx)
        }

        // Create a "Segment" from each glyph vector. Also collect the added extra gaps and find the overall width
        // of the FormattedString.
        val segments = arrayOfNulls<Segment>(gvs.size)
        var gapAccumulator = 0f
        for (visualGvIdx in gvs.indices) {
            val gvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
            val gv = gvs[gvIdx]
            val gvStartCharIdx = gvStartCharIndices[gvIdx]
            val font = attrs.getAttr(gvStartCharIdx).font
            gapAccumulator += gvLeftHandGaps[gvIdx]
            val baseX = textLayout.getGlyphVectorX(gvIdx) + gapAccumulator
            segments[gvIdx] = Segment(font, baseX, gv)
            gapAccumulator -= font.trackingPx
        }
        _width = textLayout.advance + gapAccumulator
        _segments = segments.requireNoNulls().asList()

        // Now that we have obtained information about rendering the glyphs themselves, the following code will
        // concern itself with retrieving information about the text decorations and background.
        // Note: We consider whole GlyphVector in one piece and hence draw decorations and background between GV
        // boundaries. However, this is not an issue since we have provokes splits in the TextLayout's
        // TextLineComponents (and as such also in the GVs) at each point where the deco or background changes.

        /**
         * Returns the x-coordinate of the left edge of the GlyphVector at the given visual and logical index.
         * If there is a gap at the left-hand side of the GV, returns an x-coordinate in the middle of that gap.
         */
        fun getLeftEdge(visualGvIdx: Int): Float {
            // First, we check for two conditions upon which we can return early.
            // If requesting the left edge of the leftmost GlyphVector, that would of course be 0.
            if (visualGvIdx == 0)
                return 0f
            // If requesting the left edge of the "virtual GlyphVector" right of the rightmost GlyphVector,
            // that would be the width of the entire FormattedString.
            if (visualGvIdx == gvs.size)
                return _width
            // Find the logical index of the GlyphVector, which is compatible with our other arrays.
            val gvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
            // Now we have found the GlyphVector whose leftmost coordinate is the left edge of the char in question.
            // Get that leftmost coordinate ("baseX"). Also subtract half of the gap left of the GlyphVector
            // such that we return the middle of that gap.
            return segments[gvIdx]!!.baseX - gvLeftHandGaps[gvIdx] / 2f
        }

        // Find shapes with respective filling colors that realize the text decorations.
        // Do this by iterating through the visual glyph vector indices, i.e., from left to right. The lambda "action"
        // is called whenever a stretch of the same decoration has come to an end.
        val decorationsUnderlay = mutableListOf<Pair<Shape, Color>>()
        val decorationsOverlay = mutableListOf<Pair<Shape, Color>>()
        forEachElementStretch(
            numItems = gvs.size,
            getItem = { visualGvIdx ->
                val gvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
                attrs.getAttr(gvStartCharIndices[gvIdx]).decorations
            },
            action = { deco, startVisualGvIdx, endVisualGvIdx ->
                var leftX = getLeftEdge(startVisualGvIdx)
                var rightX = getLeftEdge(endVisualGvIdx)
                if (startVisualGvIdx == 0)
                    leftX -= deco.widenLeftPx
                if (endVisualGvIdx == gvs.size)
                    rightX += deco.widenRightPx
                if (leftX > rightX)
                    leftX = rightX.also { rightX = leftX }

                val line = if (deco.dashPatternPx == null || deco.dashPatternPx.isEmpty())
                    Rectangle2D.Float(leftX, deco.offsetPx - deco.thicknessPx / 2f, rightX - leftX, deco.thicknessPx)
                else
                    BasicStroke(
                        deco.thicknessPx, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, deco.dashPatternPx, leftX
                    ).createStrokedShape(Line2D.Float(leftX, deco.offsetPx, rightX, deco.offsetPx))

                if (!deco.clear)
                    decorationsOverlay.add(Pair(line, deco.color))
                else if (deco.clearRadiusPx == 0f)
                    decorationsUnderlay.add(Pair(line, deco.color))
                else {
                    val lineArea = Area(line)
                    val dilStrokeCap =
                        if (deco.clearJoin == BasicStroke.JOIN_ROUND) BasicStroke.CAP_ROUND else BasicStroke.CAP_SQUARE
                    val dilStroke = BasicStroke(deco.clearRadiusPx * 2f, dilStrokeCap, deco.clearJoin)
                    for (visualGvIdx in startVisualGvIdx until endVisualGvIdx) {
                        val gvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
                        val segment = segments[gvIdx]!!
                        val outlineTx = AffineTransform().apply { translate(segment.baseX, 0f) }
                        for (glyphIdx in 0 until segment.numGlyphs) {
                            // Note: Glyph outlines turn out to be float paths, and hence, the following
                            // transformation by outlineTx is really efficient.
                            val glyphOutline = Path2D.Float(segment.getGlyphOutline(glyphIdx), outlineTx)
                            lineArea.subtract(Area(dilate(glyphOutline, dilStroke)))
                        }
                    }
                    decorationsUnderlay.add(Pair(lineArea, deco.color))
                }
            }
        )
        _decorationsUnderlay = decorationsUnderlay
        _decorationsOverlay = decorationsOverlay

        // Find rectangles with respective filling colors that realize the background.
        // Do this similarly as for the text decorations.
        val backgrounds = mutableListOf<Pair<Rectangle2D, Color>>()
        forEachStretch(
            numItems = gvs.size,
            getItem = { visualGvIdx ->
                val gvIdx = textLayout.visualToLogicalGvIdx(visualGvIdx)
                attrs.getAttr(gvStartCharIndices[gvIdx]).background
            },
            action = { bg, startVisualGvIdx, endVisualGvIdx ->
                var leftX = getLeftEdge(startVisualGvIdx)
                var rightX = getLeftEdge(endVisualGvIdx)
                if (startVisualGvIdx == 0)
                    leftX -= bg.widenLeftPx
                if (endVisualGvIdx == gvs.size)
                    rightX += bg.widenRightPx
                val topY = -heightAboveBaseline - bg.widenTopPx
                val botY = height - heightAboveBaseline + bg.widenBottomPx
                val rect = Rectangle2D.Float(min(leftX, rightX), min(topY, botY), abs(rightX - leftX), abs(botY - topY))
                backgrounds.add(Pair(rect, bg.color))
            })
        _backgrounds = backgrounds

        CustomGlyphLayoutEngine.end()
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

    private inline fun <T> forEachElementStretch(
        numItems: Int, getItem: (Int) -> Set<T>, action: (T, Int, Int) -> Unit
    ) {
        // Maps set items which are currently to their respective run start.
        val stretches = HashMap<T, Int>()
        for (idx in 0 until numItems) {
            val set = getItem(idx)
            // For each element which currently has a stretch but doesn't appear at the current index,
            // remove that element and call the action() function to signal a completed stretch.
            val iter = stretches.iterator()
            while (iter.hasNext()) {
                val (stretchValue, stretchStartIdx) = iter.next()
                if (stretchValue !in set) {
                    action(stretchValue, stretchStartIdx, idx)
                    iter.remove()
                }
            }
            // For each element which doesn't have a stretch yet but appears at the current index,
            // put that element into the map alongside the current index as stretchStartIdx.
            set.forEach { value -> stretches.putIfAbsent(value, idx) }
        }
        for ((stretchValue, stretchStartIdx) in stretches)
            action(stretchValue, stretchStartIdx, numItems)
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


    /* ******************************************************
       ************ INFORMATION EXTRACTION - OTHER **********
       ****************************************************** */

    fun breakLines(wrappingWidth: Float): List<Int> {
        // Employ a LineBreakMeasurer to find the best spots to insert a newline.
        val breaks = mutableListOf(0)
        CustomGlyphLayoutEngine.begin(customGlyphLayoutEngineConfigSource)
        val tlAttrCharIter = textLayoutAttrStr.iterator
        val lineMeasurer = LineBreakMeasurer(tlAttrCharIter, BreakIterator.getLineInstance(locale), REF_FRC)
        while (lineMeasurer.position != tlAttrCharIter.endIndex) {
            val lineEndPos = lineMeasurer.nextOffset(wrappingWidth)
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
            // Whenever something about the font changes (also non-AWT properties like our "hShearing") or the
            // decoration or background changes, provoke a split in the TextLayout's TextLineComponents by changing
            // the foreground color to a new one. This way, we later have an individual GlyphVector for each such
            // segment and can hence render each segment individually. We absolutely have to provoke the split during
            // the layouting phase and cannot just manually find the split points later because ligatures must not be
            // substituted when there is a split point between the ligatured characters, i.e., between two "f"-s.
            // That last point is also important for changes of the background and text decorations, since we can't
            // find the exact split point of ligatured characters.
            tlAttrStr.addAttribute(TextAttribute.FOREGROUND, Color(counter++), runStartIdx, runEndIdx)
        }
        tlAttrStr
    }

    private val customGlyphLayoutEngineConfigSource = { charIdx: Int ->
        val attr = attrs.getAttr(charIdx)
        CustomGlyphLayoutEngine.ExtConfig(locale, attr.font.features)
    }


    class Attribute(
        val font: Font,
        val decorations: Set<Decoration>,
        val background: Background?
    )


    class Font(
        val color: Color,
        private val baseAWTFont: java.awt.Font,
        val heightPx: Float,
        val scaling: Float = 1f,
        val hScaling: Float = 1f,
        private val hShearing: Float = 0f,
        private val hOffsetRem: Float = 0f,
        private val vOffsetRem: Float = 0f,
        private val kerning: Boolean = false,
        private val ligatures: Boolean = false,
        val features: List<String> = emptyList(),
        private val trackingEm: Float = 0f
    ) {

        init {
            for ((key, value) in baseAWTFont.attributes)
                require(value == null || key in ALLOWED_FONT_ATTRS) { "Disallowed font attribute: $key" }
        }

        val unscaledPointSize = findSize(baseAWTFont, heightPx)
        val pointSize = unscaledPointSize * scaling

        private val hOffsetPx get() = hOffsetRem * unscaledPointSize
        private val vOffsetPx get() = vOffsetRem * unscaledPointSize
        val trackingPx: Float get() = trackingEm * pointSize

        val awtFont: java.awt.Font = run {
            val fontAttrs = HashMap<TextAttribute, Any>()
            fontAttrs[TextAttribute.SIZE] = pointSize
            if (hScaling != 1f)
                fontAttrs[TextAttribute.WIDTH] = hScaling
            if (kerning)
                fontAttrs[TextAttribute.KERNING] = TextAttribute.KERNING_ON
            if (ligatures)
                fontAttrs[TextAttribute.LIGATURES] = TextAttribute.LIGATURES_ON
            if (trackingEm != 0f)
                fontAttrs[TextAttribute.TRACKING] = trackingEm
            baseAWTFont.deriveFont(fontAttrs)
        }

        fun getPostprocessingTransform(withHScaling: Boolean, invertY: Boolean = false): AffineTransform {
            val lm = awtFont.lineMetrics
            // The y-coordinate of the line that should remain fixed when hShearing takes place,
            // relative to the baseline.
            val hShearingFixLine = (lm.descent - lm.ascent) / 2f
            val inv = if (invertY) -1f else 1f
            return AffineTransform().apply {
                translate(hOffsetPx, inv * (vOffsetPx + hShearingFixLine))
                shear(inv * hShearing, 0f)
                translate(0f, inv * -hShearingFixLine)
                if (withHScaling)
                    scale(hScaling, 1f)
            }
        }

        fun scaled(extraScaling: Float) = Font(
            color, baseAWTFont, heightPx, scaling * extraScaling, hScaling, hShearing, hOffsetRem, vOffsetRem,
            kerning, ligatures, features, trackingEm
        )

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
            private fun findSize(awtFont: java.awt.Font, targetHeight: Float): Float {
                // Step 1: Exponential search to determine the rough range of the font size we're looking for.
                var size = 2f
                // Upper-bound the number of repetitions to avoid:
                //   - Accidental infinite looping.
                //   - Too large fonts, as they cause the Java font rendering engine to destroy its own fonts.
                for (i in 0 until 10) {
                    val height = awtFont.deriveFont(size * 2f).lineMetrics.height
                    if (height >= targetHeight)
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
                        abs(height - targetHeight) < 0.001f -> break
                        height > targetHeight -> size -= intervalLength
                        height < targetHeight -> size += intervalLength
                    }
                }

                return size
            }

        }

    }


    data class Decoration(
        val color: Color,
        val offsetPx: Float,
        val thicknessPx: Float,
        val widenLeftPx: Float,
        val widenRightPx: Float,
        val clear: Boolean,
        val clearRadiusPx: Float,
        val clearJoin: Int, // one of the constants in BasicStroke
        val dashPatternPx: FloatArray?
    ) {

        override fun equals(other: Any?) =
            this === other ||
                    other is Decoration && color == other.color && offsetPx == other.offsetPx &&
                    thicknessPx == other.thicknessPx && widenLeftPx == other.widenLeftPx &&
                    widenRightPx == other.widenRightPx && clear == other.clear &&
                    (!clear || clearRadiusPx == other.clearRadiusPx && clearJoin == other.clearJoin) &&
                    dashPatternPx.contentEquals(other.dashPatternPx)

        override fun hashCode(): Int {
            var result = color.hashCode()
            result = 31 * result + offsetPx.hashCode()
            result = 31 * result + thicknessPx.hashCode()
            result = 31 * result + widenLeftPx.hashCode()
            result = 31 * result + widenRightPx.hashCode()
            result = 31 * result + clear.hashCode()
            if (clear) {
                result = 31 * result + clearRadiusPx.hashCode()
                result = 31 * result + clearJoin
            }
            result = 31 * result + (dashPatternPx?.contentHashCode() ?: 0)
            return result
        }

    }


    data class Background(
        val color: Color,
        val widenLeftPx: Float,
        val widenRightPx: Float,
        val widenTopPx: Float,
        val widenBottomPx: Float
    )


    class Segment(
        val font: Font,
        val baseX: Float,
        private val gv: GlyphVector
    ) {

        val numGlyphs: Int
            get() = gv.numGlyphs

        /**
         * A shape that outlines the text of this segment. All font properties have already been applied to it.
         * The first character lies at (0,0). Ergo, to draw the outline, a further translation by [baseX] is needed.
         */
        val outline: Shape by lazy {
            // Find the custom font transformations. Since we leave hScaling to TextLayout, do not apply that again.
            val tx = font.getPostprocessingTransform(withHScaling = false)
            Path2D.Float(gv.outline, tx)
        }

        fun getGlyphCodes(): IntArray =
            gv.getGlyphCodes(0, numGlyphs, null)

        fun getGlyphOffsetX(glyphIdx: Int): Float =
            glyphPos[2 * glyphIdx]

        val width: Float
            get() = glyphPos[2 * numGlyphs] - font.trackingPx

        fun getGlyphOutline(glyphIdx: Int): Shape =
            glyphOutlines[glyphIdx]

        private val glyphPos = gv.getGlyphPositions(0, numGlyphs + 1, null)

        private val glyphOutlines by lazy {
            // Find the custom font transformations. Since we leave hScaling to TextLayout, do not apply that again.
            val tx = font.getPostprocessingTransform(withHScaling = false)
            Array(numGlyphs) { glyphIdx -> Path2D.Float(gv.getGlyphOutline(glyphIdx), tx) }
        }

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
            val runEnd = (if (numRuns == 0) 0 else runEnds[numRuns - 1]) + string.length
            if (runAttrs.size == numRuns) {
                runAttrs = runAttrs.copyOf(numRuns * 4)
                runEnds = runEnds.copyOf(numRuns * 4)
            }
            runAttrs[numRuns] = attribute
            runEnds[numRuns] = runEnd
            numRuns++
        }

        fun build(): FormattedString =
            FormattedString(
                stringBuilder.toString(), Attributes(numRuns, runAttrs, runEnds, 0, stringBuilder.length),
                locale, justificationWidth = Float.NaN
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

        val distinctFonts: Set<Font> = HashSet<Font>().apply {
            for (runIdx in runLimStart until runLimEnd)
                add(runAttrs[runIdx]!!.font)
        }

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
