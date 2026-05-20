package com.loadingbyte.cinecred.demo

import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.transformedBy
import com.loadingbyte.cinecred.ui.helper.preserveTransform
import com.loadingbyte.cinecred.ui.helper.scale
import com.loadingbyte.cinecred.ui.helper.withNewG2
import java.awt.*
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.awt.geom.*
import java.awt.image.BufferedImage
import java.awt.image.BufferedImageOp
import java.awt.image.ImageObserver
import java.awt.image.RenderedImage
import java.awt.image.renderable.RenderableImage
import java.text.AttributedCharacterIterator
import javax.swing.border.MatteBorder
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.round


/** Does a better job at scaling prints of our UI than just using scale() on a regular Graphics2D object. */
class ScaledGraphics2D private constructor(
    private val parent: ScaledGraphics2D?,
    private val delegate: Graphics2D,
    private val scaling: Double
) : Graphics2D() {

    private val transform: AffineTransform = parent?.transform?.let(::AffineTransform) ?: AffineTransform()
    private var clip: Shape? = parent?.clip
    private val deferredFills: MutableList<Triple<Shape, Shape?, Paint>> = parent?.deferredFills ?: mutableListOf()

    constructor(delegate: Graphics2D, scaling: Double) : this(null, delegate, scaling) {
        require(delegate.transform.isIdentity)
        require(delegate.clip == null)
    }

    companion object {

        private fun splitShapeAtClose(shape: Shape): List<Shape> {
            val splitShapes = mutableListOf<Shape>()
            val pi = shape.getPathIterator(null)
            var s = Path2D.Float(pi.windingRule)  // FlatUIUtils uses float paths.
            val c = FloatArray(6)
            while (!pi.isDone) {
                when (pi.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> s.moveTo(c[0], c[1])
                    PathIterator.SEG_LINETO -> s.lineTo(c[0], c[1])
                    PathIterator.SEG_QUADTO -> s.quadTo(c[0], c[1], c[2], c[3])
                    PathIterator.SEG_CUBICTO -> s.curveTo(c[0], c[1], c[2], c[3], c[4], c[5])
                    PathIterator.SEG_CLOSE -> {
                        s.closePath()
                        splitShapes.add(s)
                        s = Path2D.Float(pi.windingRule)
                    }
                }
                pi.next()
            }
            return splitShapes
        }

        private fun getDimension(img: Image): Dimension {
            val w = img.getWidth(null)
            val h = img.getHeight(null)
            check(w != -1 && h != -1)
            return Dimension(w, h)
        }

        private fun getBoundsTransform(oldBounds: Rectangle2D, newBounds: Rectangle2D) =
            if (oldBounds.width < 0.001 || oldBounds.height < 0.001)
                AffineTransform.getScaleInstance(0.0, 0.0)
            else
                AffineTransform().apply {
                    translate(newBounds.x, newBounds.y)
                    scale(newBounds.width / oldBounds.width, newBounds.height / oldBounds.height)
                    translate(-oldBounds.x, -oldBounds.y)
                }

        private fun makeRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) =
            if (width <= 0 || height <= 0) null else RoundRectangle2D.Double(
                x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), arcWidth.toDouble(),
                arcHeight.toDouble()
            )

        private fun makeOval(x: Int, y: Int, width: Int, height: Int) =
            if (width <= 0 || height <= 0) null else
                Ellipse2D.Double(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

        private fun makeArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int, type: Int) =
            if (width <= 0 || height <= 0) null else Arc2D.Double(
                x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble(), startAngle.toDouble(),
                arcAngle.toDouble(), type
            )

    }

    private fun scaleBounds(bounds: Rectangle2D): Rectangle2D {
        val x = bounds.x
        val y = bounds.y
        val w = bounds.width
        val h = bounds.height
        if (abs(round(x) - x) < 0.001 && abs(round(y) - y) < 0.001 &&
            abs(round(w) - w) < 0.001 && abs(round(h) - h) < 0.001 &&
            w > 0.5 && h > 0.5
        ) {
            val x0 = round(x * scaling)
            val y0 = round(y * scaling)
            val x1 = round((x + w) * scaling).coerceAtLeast(x0 + 1)
            val y1 = round((y + h) * scaling).coerceAtLeast(y0 + 1)
            return Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0)
        } else
            return Rectangle2D.Double(x * scaling, y * scaling, w * scaling, h * scaling)
    }

    private fun transformShape(shape: Shape): Shape {
        val shape = shape.transformedBy(transform)
        val bounds = shape.bounds2D
        return shape.transformedBy(getBoundsTransform(bounds, scaleBounds(bounds)))
    }

    private fun drawTextLayout(textLayout: TextLayout, x: Float, y: Float) {
        delegate.preserveTransform {
            delegate.scale(scaling)
            delegate.transform(transform)
            // Note: TextLayout.draw() doesn't mess with the total advance, in contrast to delegate.drawString().
            textLayout.draw(delegate, x, y)
        }
    }

    private fun scaledClip() =
        this.clip?.let { clip ->
            val bounds = clip.bounds2D
            clip.transformedBy(getBoundsTransform(bounds, scaleBounds(bounds)))
        }

    fun settleDeferred() {
        if (parent != null)
            return
        delegate.withNewG2 { g2 ->
            g2.composite = AlphaComposite.SrcOver
            for ((shape, clip, paint) in deferredFills) {
                g2.clip = clip
                g2.paint = paint
                g2.fill(shape)
            }
        }
        deferredFills.clear()
    }

    // The following methods override Graphics2D methods and call the delegate Graphics2D object.

    override fun create() =
        ScaledGraphics2D(this, delegate.create() as Graphics2D, scaling)

    override fun dispose() {
        settleDeferred()
        delegate.dispose()
    }

    override fun draw(s: Shape?) {
        if (s != null)
            delegate.draw(transformShape(s))
    }

    override fun fill(s: Shape?) {
        if (s == null)
            return

        // Defer fill calls for thin lines (like borders or outlines) to later, in order to prevent them from being
        // obscured by backgrounds etc.
        val callingFrame = StackWalker.getInstance().walk { stack ->
            stack.filter { frame -> frame.className != ScaledGraphics2D::class.java.name }.findFirst().getOrNull()
        }
        if (callingFrame?.className == MatteBorder::class.java.name && callingFrame.methodName == "paintBorder")
            deferredFills.add(Triple(transformShape(s), scaledClip(), paint))
        else if (callingFrame?.className == FlatUIUtils::class.java.name && callingFrame.methodName == "paintOutline") {
            // Additional special logic for outlines to avoid them vanishing on some sides. We scale the outline's outer
            // bounds as usual, but determine the scaled inner bounds by manually shrinking the scaled outer bounds by
            // the scaled line width.
            val subShapes = splitShapeAtClose(s.transformedBy(transform))
            check(subShapes.size == 2)
            val (outerShape, innerShape) = subShapes
            val outerBounds = outerShape.bounds2D
            val innerBounds = innerShape.bounds2D
            val lw = innerBounds.x - outerBounds.x
            val slw = if (abs(round(lw) - lw) < 0.001) round(lw * scaling).coerceAtLeast(1.0) else lw * scaling
            val scaledOuterBounds = scaleBounds(outerBounds)
            val scaledInnerBounds =
                scaledOuterBounds.run { Rectangle2D.Double(x + slw, y + slw, width - 2 * slw, height - 2 * slw) }
            val scaledShape = Path2D.Float(Path2D.WIND_EVEN_ODD)
            scaledShape.append(outerShape.transformedBy(getBoundsTransform(outerBounds, scaledOuterBounds)), false)
            scaledShape.append(innerShape.transformedBy(getBoundsTransform(innerBounds, scaledInnerBounds)), false)
            deferredFills.add(Triple(scaledShape, scaledClip(), paint))
            return
        }

        delegate.fill(transformShape(s))
    }

    override fun drawImage(img: Image?, xform: AffineTransform?, obs: ImageObserver?): Boolean {
        if (img == null)
            return true
        val tr = AffineTransform(transform).apply { xform?.let(::concatenate) }
        val bounds = Rectangle(getDimension(img)).transformedBy(tr).bounds2D
        tr.preConcatenate(getBoundsTransform(bounds, scaleBounds(bounds)))
        return delegate.drawImage(img, tr, obs)
    }

    override fun drawString(str: String?, x: Float, y: Float) {
        if (!str.isNullOrEmpty())
            drawTextLayout(TextLayout(str, font, fontRenderContext), x, y)
    }

    override fun drawString(iterator: AttributedCharacterIterator?, x: Float, y: Float) {
        if (iterator != null)
            drawTextLayout(TextLayout(iterator, fontRenderContext), x, y)
    }

    override fun translate(x: Int, y: Int) = transform.translate(x.toDouble(), y.toDouble())
    override fun translate(tx: Double, ty: Double) = transform.translate(tx, ty)
    override fun rotate(theta: Double) = transform.rotate(theta)
    override fun rotate(theta: Double, x: Double, y: Double) = transform.rotate(theta, x, y)
    override fun scale(sx: Double, sy: Double) = transform.scale(sx, sy)
    override fun shear(shx: Double, shy: Double) = transform.shear(shx, shy)
    override fun transform(Tx: AffineTransform) = transform.concatenate(Tx)
    override fun setTransform(Tx: AffineTransform) = transform.setTransform(Tx)
    override fun getTransform() = AffineTransform(transform)

    override fun getClip() = clip?.run { transformedBy(transform.createInverse()) }
    override fun getClipBounds() = getClip()?.bounds

    override fun setClip(clip: Shape?) {
        this.clip = clip?.transformedBy(transform)
        delegate.clip = scaledClip()
    }

    override fun clip(s: Shape?) {
        val s = (s ?: return).transformedBy(transform)
        clip = when (val c = clip) {
            null -> s
            is Rectangle if s is Rectangle -> c.intersection(s)
            is Rectangle2D if s is Rectangle2D -> c.createIntersection(s)
            else -> Area(c).apply { intersect(Area(s)) }
        }
        delegate.clip = scaledClip()
    }

    override fun getDeviceConfiguration() = delegate.deviceConfiguration
    override fun setComposite(comp: Composite?) = delegate.setComposite(comp)
    override fun setPaint(paint: Paint?) = delegate.setPaint(paint)
    override fun setStroke(s: Stroke?) = delegate.setStroke(s)
    override fun setRenderingHint(hintKey: RenderingHints.Key?, value: Any?) = delegate.setRenderingHint(hintKey, value)
    override fun getRenderingHint(hintKey: RenderingHints.Key?) = delegate.getRenderingHint(hintKey)
    override fun setRenderingHints(hints: Map<*, *>?) = delegate.setRenderingHints(hints)
    override fun addRenderingHints(hints: Map<*, *>?) = delegate.addRenderingHints(hints)
    override fun getRenderingHints() = delegate.renderingHints
    override fun getPaint() = delegate.paint
    override fun getComposite() = delegate.composite
    override fun setBackground(color: Color?) = delegate.setBackground(color)
    override fun getBackground() = delegate.background
    override fun getStroke() = delegate.stroke
    override fun getFontRenderContext() = delegate.fontRenderContext
    override fun getColor() = delegate.color
    override fun setColor(c: Color?) = delegate.setColor(c)
    override fun setPaintMode() = delegate.setPaintMode()
    override fun setXORMode(c1: Color?) = delegate.setXORMode(c1)
    override fun getFont() = delegate.font
    override fun setFont(font: Font?) = delegate.setFont(font)
    override fun getFontMetrics(f: Font?) = delegate.getFontMetrics(f)

    // The following methods are either unsupported or delegate to the above methods.

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        draw(Line2D.Double(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble()))
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        draw(Rectangle(x, y, width, height))
    }

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        fill(Rectangle(x, y, width, height))
    }

    override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
        val c = composite
        val p = paint
        composite = AlphaComposite.Src
        color = getBackground()
        fill(Rectangle(x, y, width, height))
        paint = p
        composite = c
    }

    override fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        draw(makeRoundRect(x, y, width, height, arcWidth, arcHeight))
    }

    override fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) {
        fill(makeRoundRect(x, y, width, height, arcWidth, arcHeight))
    }

    override fun drawOval(x: Int, y: Int, width: Int, height: Int) {
        draw(makeOval(x, y, width, height))
    }

    override fun fillOval(x: Int, y: Int, width: Int, height: Int) {
        fill(makeOval(x, y, width, height))
    }

    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        draw(makeArc(x, y, width, height, startAngle, arcAngle, Arc2D.OPEN))
    }

    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) {
        fill(makeArc(x, y, width, height, startAngle, arcAngle, Arc2D.PIE))
    }

    override fun drawPolyline(xPoints: IntArray?, yPoints: IntArray?, nPoints: Int) {
        throw UnsupportedOperationException()
    }

    override fun drawPolygon(xPoints: IntArray?, yPoints: IntArray?, nPoints: Int) {
        if (xPoints != null && yPoints != null)
            draw(Polygon(xPoints, yPoints, nPoints))
    }

    override fun fillPolygon(xPoints: IntArray?, yPoints: IntArray?, nPoints: Int) {
        if (xPoints != null && yPoints != null)
            fill(Polygon(xPoints, yPoints, nPoints))
    }

    override fun drawImage(img: BufferedImage?, op: BufferedImageOp?, x: Int, y: Int) {
        throw UnsupportedOperationException()
    }

    override fun drawImage(img: Image?, x: Int, y: Int, observer: ImageObserver?): Boolean {
        return drawImage(img, AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()), observer)
    }

    override fun drawImage(img: Image?, x: Int, y: Int, width: Int, height: Int, observer: ImageObserver?): Boolean {
        if (img == null || width <= 0 || height <= 0)
            return true
        val dim = getDimension(img)
        val tr = AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble())
        tr.scale(width / dim.width.toDouble(), height / dim.height.toDouble())
        return drawImage(img, tr, observer)
    }

    override fun drawImage(img: Image?, x: Int, y: Int, bgcolor: Color?, observer: ImageObserver?): Boolean {
        throw UnsupportedOperationException()
    }

    override fun drawImage(
        img: Image?, x: Int, y: Int, width: Int, height: Int, bgcolor: Color?, observer: ImageObserver?
    ): Boolean {
        throw UnsupportedOperationException()
    }

    override fun drawImage(
        img: Image?, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int,
        observer: ImageObserver?
    ): Boolean {
        throw UnsupportedOperationException()
    }

    override fun drawImage(
        img: Image?, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int, bgcolor: Color?,
        observer: ImageObserver?
    ): Boolean {
        throw UnsupportedOperationException()
    }

    override fun drawRenderedImage(img: RenderedImage?, xform: AffineTransform?) {
        throw UnsupportedOperationException()
    }

    override fun drawRenderableImage(img: RenderableImage?, xform: AffineTransform?) {
        throw UnsupportedOperationException()
    }

    override fun drawString(str: String, x: Int, y: Int) {
        drawString(str, x.toFloat(), y.toFloat())
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Int, y: Int) {
        drawString(iterator, x.toFloat(), y.toFloat())
    }

    override fun drawGlyphVector(g: GlyphVector?, x: Float, y: Float) {
        throw UnsupportedOperationException()
    }

    override fun hit(rect: Rectangle?, s: Shape?, onStroke: Boolean): Boolean {
        throw UnsupportedOperationException()
    }

    override fun clipRect(x: Int, y: Int, width: Int, height: Int) {
        clip(Rectangle(x, y, width, height))
    }

    override fun setClip(x: Int, y: Int, width: Int, height: Int) {
        setClip(Rectangle(x, y, width, height))
    }

    override fun copyArea(x: Int, y: Int, width: Int, height: Int, dx: Int, dy: Int) {
        throw UnsupportedOperationException()
    }

}
