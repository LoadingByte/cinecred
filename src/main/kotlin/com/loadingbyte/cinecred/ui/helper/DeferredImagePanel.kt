package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.scale
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Canvas
import com.loadingbyte.cinecred.imaging.DeferredImage.Layer
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.*


/**
 * Because JScrollPane turns out to have a lot of subtle issues for what we're trying to accomplish,
 * we devise our own scrollable panel for displaying a [DeferredImage]. We also implement additional features
 * like panning (via clicking and dragging) and zooming.
 */
class DeferredImagePanel(
    private val maxZoom: Double,
    private val zoomIncrement: Double,
    private val highResCache: DeferredImage.CanvasMaterializationCache,
    private val lowResCache: DeferredImage.CanvasMaterializationCache
) :
    JPanel(MigLayout("gap 0, insets 0")) {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK")
    fun leakedViewportCenterSetter(x: Double = viewportCenterX, y: Double = viewportCenterY) {
        viewportCenterX = x
        viewportCenterY = y
        canvas.repaint()
    }
    // =========================================

    var image: DeferredImage?
        get() = _image
        set(image) {
            setImageAndGroundingAndLayers(image, grounding, layers)
        }

    var grounding: Color4f
        get() = _grounding
        set(grounding) {
            setImageAndGroundingAndLayers(image, grounding, layers)
        }

    var layers: List<Layer>
        get() = _layers
        set(layers) {
            setImageAndGroundingAndLayers(image, grounding, layers)
        }

    var isPresented: Boolean = true

    fun setImageAndGroundingAndLayers(image: DeferredImage?, grounding: Color4f, layers: List<Layer>) {
        val imageChanged = image !== _image
        if (!imageChanged && _grounding == grounding && _layers == layers) return
        if (imageChanged) require(image == null || image.width != 0.0 && image.height.resolve() != 0.0)
        _image = image
        _grounding = grounding
        _layers = layers
        contentVersion++
        if (imageChanged) coerceViewportAndCalibrateScrollbars()
        // Rematerialize will call canvas.repaint() once it's done.
        rematerialize(contentChanged = true)
    }

    /**
     * Zoom = 1 means: Show the whole width of the image.
     */
    var zoom = 1.0
        set(value) {
            val newZoom = value.coerceIn(1.0, maxZoom)
            if (field != newZoom) {
                field = newZoom
                for (listener in zoomListeners)
                    listener(newZoom)
                coerceViewportAndCalibrateScrollbars()
                // Immediately repaint a scaled version of the old materialized image
                // while we wait for the new materialized image.
                canvas.repaint()
                rematerialize(contentChanged = false)
            }
        }

    val zoomListeners = mutableListOf<(Double) -> Unit>()

    private var _image: DeferredImage? = null
    private var _grounding: Color4f = Color4f.BLACK
    private var _layers: List<Layer> = emptyList()
    private var contentVersion = 0L

    // Use and cache an intermediate materialized image of the current sizing. We first paint
    // a properly scaled version of the deferred image onto the raster image. Then, we directly paint that
    // raster image onto the canvas. This way, we avoid materializing the deferred image over and over again
    // whenever the user scrolls, which can be very expensive when the deferred image contains, e.g., PDFs.
    // Additionally, if a raster image of the entire deferred image would be too large to comfortably fit into
    // memory, we only rasterize the portion around the currently visible viewport, but keep a low-res version
    // of the entire image that we momentarily show when the user scrolls out of the rasterized portion too fast.
    private var materialized: BufferedImage? = null
    private var materializedContentVersion = 0L
    // In image coordinates:
    private var materializedStartY = Double.NaN
    private var materializedStopY = Double.NaN
    private var lowResMaterialized: BufferedImage? = null
    private var lowResMaterializedContentVersion = 0L
    private val immediateMaterializingJobSlot = JobSlot()
    private val delayedHighResMaterializingJobSlot = JobSlot(delay = 200L)
    private val delayedLowResMaterializingJobSlot = JobSlot(delay = 200L)

    private val canvas = CanvasPanel()
    private val xScrollbar = Scrollbar(JScrollBar.HORIZONTAL)
    private val yScrollbar = Scrollbar(JScrollBar.VERTICAL)
    private var disableScrollbarListeners = false

    init {
        add(canvas, "push, grow")
        add(yScrollbar, "growy")
        add(xScrollbar, "newline, growx")

        // When this pane's size changes, coerce the viewport and rematerialize the image.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                coerceViewportAndCalibrateScrollbars()
                // Immediately repaint a scaled version of the old materialized image
                // while we wait for the new materialized image.
                canvas.repaint()
                rematerialize(contentChanged = false)
            }
        })

        // When the user scrolls, adjust the viewport center and repaint the viewport.
        // Note: Because the scrollbars discretize their values to integers, we store the viewport centers
        // multiplied by a factor of SCROLLBAR_MULT to increase the precision.
        xScrollbar.model.addChangeListener {
            if (!disableScrollbarListeners) {
                viewportCenterX = xScrollbar.model.value / SCROLLBAR_MULT + minViewportCenterX
                canvas.repaint()
            }
        }
        yScrollbar.model.addChangeListener {
            if (!disableScrollbarListeners) {
                viewportCenterY = yScrollbar.model.value / SCROLLBAR_MULT + minViewportCenterY
                canvas.repaint()
            }
        }

        // Hovering over the canvas should always display a move cursor.
        canvas.cursor = Cursor(Cursor.MOVE_CURSOR)

        // When the user scrolls inside the canvas, scroll the viewport or adjust the zoom.
        canvas.addMouseWheelListener { e ->
            val block = e.scrollType == WHEEL_BLOCK_SCROLL
            val mult = if (block) e.wheelRotation.sign.toDouble() else e.preciseWheelRotation * e.scrollAmount
            if (e.modifiersEx == CTRL_DOWN_MASK)
                zoom -= zoomIncrement * mult
            else {
                val unitIncrement = min(viewportWidth, viewportHeight) * mult / 50.0
                when (e.modifiersEx) {
                    0 -> viewportCenterY += if (block) viewportHeight * mult else unitIncrement
                    // At least on Windows and macOS, the functions WmMouseWheel() in awt_Component.cpp respectively
                    // handleScrollEvent() in CPlatformResponder.java set the shift modifier when the user scrolls with
                    // a horizontal mouse wheel or touchpad. We read that modifier here.
                    // As a side effect, for Linux we now at least support shift + vert. scrolling = hor. scrolling,
                    // which is a common UI idiom.
                    SHIFT_DOWN_MASK -> viewportCenterX += if (block) viewportWidth * mult else unitIncrement
                    else -> return@addMouseWheelListener
                }
                canvas.repaint()
            }
        }

        // When the user clicks and drags inside the canvas, move the viewport center.
        val canvasDragListener = object : MouseAdapter() {
            private var startPoint: Point? = null
            private var startViewportCenterX = 0.0
            private var startViewportCenterY = 0.0

            override fun mousePressed(e: MouseEvent) {
                if (e.button == BUTTON1) {
                    startPoint = e.point
                    startViewportCenterX = viewportCenterX
                    startViewportCenterY = viewportCenterY
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == BUTTON1)
                    startPoint = null
            }

            override fun mouseDragged(e: MouseEvent) {
                startPoint?.let { s ->
                    viewportCenterX = startViewportCenterX + (s.x - e.x) / imageScaling
                    viewportCenterY = startViewportCenterY + (s.y - e.y) / imageScaling
                    canvas.repaint()
                }
            }
        }
        canvas.addMouseListener(canvasDragListener)
        canvas.addMouseMotionListener(canvasDragListener)
    }

    // In image coordinates:
    private var viewportCenterX = 0.0
        set(value) {
            field = value.coerceIn(minViewportCenterX, maxViewportCenterX)
            disableScrollbarListeners = true
            xScrollbar.model.value = ((field - minViewportCenterX) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
        }
    private var viewportCenterY = 0.0
        set(value) {
            field = value.coerceIn(minViewportCenterY, maxViewportCenterY)
            disableScrollbarListeners = true
            yScrollbar.model.value = ((field - minViewportCenterY) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
            // If only a portion of the deferred image is materialized and the viewport comes close to the portion's
            // edge, queue a rematerialization around the current viewport.
            // reZone is configured such that we rematerialize once the viewport's edge is viewportHeight/2 away from
            // the portion's edge.
            val reZone = viewportHeight
            if (!materializedStartY.isNaN())
                if (field < materializedStartY + reZone && materializedStartY > 0.1 ||
                    field > materializedStopY - reZone && materializedStopY < image!!.height.resolve() - 0.1
                ) rematerialize(contentChanged = false)
        }

    // In image coordinates:
    private val viewportWidth get() = image!!.width / zoom
    private val viewportHeight
        get() = min(image!!.height.resolve(), canvas.height.also { require(it != 0) } / imageScaling)
    private val viewportStartX get() = viewportCenterX - viewportWidth / 2.0
    private val viewportStartY get() = viewportCenterY - viewportHeight / 2.0
    private val viewportStopY get() = viewportCenterY + viewportHeight / 2.0
    private val minViewportCenterX get() = viewportWidth / 2.0
    private val maxViewportCenterX get() = image!!.width - minViewportCenterX
    private val minViewportCenterY get() = viewportHeight / 2.0
    private val maxViewportCenterY get() = image!!.height.resolve() - minViewportCenterY

    // The image scaling maps from deferred image coordinates to canvas coordinates as they are used by Swing.
    // These canvas coordinates are however fake if system scaling is enabled in a HiDPI context. Hence, to find out
    // how large the materialized image should be, we need to compensate for that using the physical image scaling.
    private val imageScaling
        get() = zoom * canvas.width.also { require(it != 0) } / image!!.width
    private val physicalImageScaling
        // Safeguard in case the graphics object is not yet ready or has already been discarded; this happens sometimes.
        get() = imageScaling * ((canvas.graphics as Graphics2D?)?.let(UIScale::getSystemScaleFactor) ?: 1.0)

    private fun coerceViewportAndCalibrateScrollbars() {
        val image = this.image

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            // Disable the scrollbars because their change listeners depend on the size of the image
            // and will throw errors when trying to access a null image.
            xScrollbar.isEnabled = false
            yScrollbar.isEnabled = false
        } else {
            // These setters do the coercion and update the scrollbar positions:
            viewportCenterX = viewportCenterX
            viewportCenterY = viewportCenterY

            disableScrollbarListeners = true
            xScrollbar.model.apply {
                maximum = (image.width * SCROLLBAR_MULT).roundToInt()
                extent = (viewportWidth * SCROLLBAR_MULT).roundToInt()
            }
            yScrollbar.model.apply {
                maximum = (image.height.resolve() * SCROLLBAR_MULT).roundToInt()
                extent = (viewportHeight * SCROLLBAR_MULT).roundToInt()
            }
            // Disable scrollbars if they have no room to scroll.
            xScrollbar.isEnabled = xScrollbar.model.run { maximum != extent }
            yScrollbar.isEnabled = yScrollbar.model.run { maximum != extent }
            disableScrollbarListeners = false

            // Coerce and update the scrollbar positions again.
            // Calling the setters once before and once after is vital to prevent various desyncs we've observed.
            viewportCenterX = viewportCenterX
            viewportCenterY = viewportCenterY
        }
    }

    private fun rematerialize(contentChanged: Boolean) {
        val image = this.image

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            materialized = null
            lowResMaterialized = null
            canvas.repaint()
        } else {
            val imageHeight = image.height.resolve()
            val viewportHeight = this.viewportHeight
            // If this panel is currently being presented to the user, immediately materialize its image. To improve
            // performance, if the deferred image exceeds the viewport, we only immediately materialize the portion
            // covered by the current viewport.
            if (isPresented) {
                // The portion's top y in image coordinates.
                val immediateStartY = if (imageHeight == viewportHeight) Double.NaN else viewportStartY
                submitHighResMaterializingJob(immediateMaterializingJobSlot, immediateStartY, viewportHeight)
                if (immediateStartY.isNaN()) {
                    // In rare cases, it could happen that the delayed job (scheduled some time ago with another zoom)
                    // is already running, but finishes after the just scheduled immediate job, in which case an image
                    // with the wrong zoom would be displayed. However, this is so unlikely and at the same time the
                    // outcome is so un-severe that we don't need to pollute the code with a countermeasure.
                    delayedHighResMaterializingJobSlot.cancel()
                    return
                }
            }
            // If the first step didn't start the materialization of the entire deferred image yet, schedule the
            // materialization of a larger area around the viewport (this allows the user to move around a bit) after
            // some delay has passed. If the image changes again before the waiting time is up, the scheduled job is
            // canceled. This way, when the user is pushing down a spinner and quickly cycles through images, we only
            // spend compute on materializing the current viewport of the currently presented image panel. Only after he
            // has let go of the spinner will the other images catch up and will a larger area be materialized.
            val delayedHeight = min(
                imageHeight,
                // If a raster image of the entire deferred image with the current physical scaling would exceed
                // MAX_PIXELS, we only materialize a portion of the deferred image around the current viewport.
                MAX_MAT_PIXELS / (image.width * physicalImageScaling.pow(2))
            )
            val delayedStartY = if (delayedHeight == imageHeight) Double.NaN else
                (viewportCenterY - delayedHeight / 2.0).coerceIn(0.0, imageHeight - delayedHeight)
            submitHighResMaterializingJob(delayedHighResMaterializingJobSlot, delayedStartY, delayedHeight)
            // Materialize a low-res version if (a) the high-res version doesn't yet cover the entire deferred image and
            // (b) either the content changed or there is not a low-res version yet. We will momentarily paint this
            // low-res placeholder when the user scrolls out of the materialized portion too quickly.
            if (!delayedStartY.isNaN() && (contentChanged || lowResMaterialized == null))
                submitLowResMaterializingJob(delayedLowResMaterializingJobSlot)
        }
    }

    private fun submitHighResMaterializingJob(jobSlot: JobSlot, startY: Double, height: Double) {
        // Abort if the canvas was disposed already.
        val bitmapJ2DBridge = BitmapJ2DBridge(canvas.graphicsConfiguration.colorModel ?: return)
        // Capture these variables.
        val image = this.image!!
        val grounding = this.grounding
        val layers = this.layers
        val contentVersion = this.contentVersion
        val physicalImageScaling = this.physicalImageScaling
        jobSlot.submit {
            // Align the drawn portion with the pixel grid. If we didn't do this, users would notice changes in the
            // antialiasing pattern when we swap the materialized image, for example when going from the narrow
            // immediate image to the taller delayed image.
            val physicalStartY = if (startY.isNaN()) 0.0 else floor(physicalImageScaling * startY)
            val physicalStopY = ceil(physicalImageScaling * ((if (startY.isNaN()) 0.0 else startY) + height))
            // Materialize the image or a portion thereof.
            // Use max(1, ...) to ensure that the raster image dimensions don't drop to 0.
            val matWidth = max(1, (physicalImageScaling * image.width).roundToInt())
            val matHeight = max(1, (physicalStopY - physicalStartY).roundToInt())
            val materialized = drawToBufferedImage(matWidth, matHeight, grounding, bitmapJ2DBridge) { canvas ->
                // Paint a scaled version of the deferred image onto the raster image.
                DeferredImage(matWidth.toDouble(), matHeight.toDouble().toY()).apply {
                    // If only a portion is materialized, scroll the deferred image to that portion.
                    drawDeferredImage(image, y = (-physicalStartY).toY(), universeScaling = physicalImageScaling)
                }.materialize(canvas, highResCache, layers)
            }
            SwingUtilities.invokeLater {
                if (this.materializedContentVersion > contentVersion)
                    return@invokeLater
                this.materialized = materialized
                this.materializedContentVersion = contentVersion
                this.materializedStartY = if (startY.isNaN()) Double.NaN else physicalStartY / physicalImageScaling
                this.materializedStopY = if (startY.isNaN()) Double.NaN else physicalStopY / physicalImageScaling
                // If the materialized high-res image covers the whole deferred image, also use it as the fallback.
                if (startY.isNaN() && this.lowResMaterializedContentVersion < contentVersion) {
                    this.lowResMaterialized = materialized
                    this.lowResMaterializedContentVersion = contentVersion
                }
                // This is a bit hacky. When materialization has finished, we want to repaint the canvas.
                // However, the user might have supplied a new image in the meantime (e.g., because he's pushing
                // down on some style config spinner). To avoid that the repainting of the canvas is done with
                // a wrong image in the this.image variable (which would lead to a wrongly calculated viewport
                // position and hence to awful jitter), we briefly set this.image to the image used as a source
                // for the just generated materialized image. After the canvas has been repainted (we use
                // paintImmediately() so that the painting will be done once the method returns), we set this.image
                // back to its original value. We do the same thing for this.viewportCenterX/Y.
                // Note that this quick change will not interfere with other code setting those variables because
                // they may only be set from the AWT event thread (which we are in right now as well).
                if (this.contentVersion == contentVersion)
                    canvas.repaint()
                else {
                    val curImage = this._image
                    val curViewportCenterX = this.viewportCenterX
                    val curViewportCenterY = this.viewportCenterY
                    this._image = image
                    this.viewportCenterX = viewportCenterX
                    this.viewportCenterY = viewportCenterY
                    canvas.paintImmediately(0, 0, canvas.width, canvas.height)
                    this._image = curImage
                    this.viewportCenterX = curViewportCenterX
                    this.viewportCenterY = curViewportCenterY
                }
            }
        }
    }

    private fun submitLowResMaterializingJob(jobSlot: JobSlot) {
        // Abort if the canvas was disposed already.
        val bitmapJ2DBridge = BitmapJ2DBridge(canvas.graphicsConfiguration.colorModel ?: return)
        // Capture these variables.
        val image = this.image!!
        val grounding = this.grounding
        val layers = this.layers
        val contentVersion = this.contentVersion
        jobSlot.submit {
            val imageHeight = image.height.resolve()
            val theoreticalScaling = sqrt(MAX_MAT_PIXELS / (image.width * imageHeight))
            // Again use max(1, ...) to ensure that the raster image dimensions do not drop to 0.
            val matWidth = max(1, (theoreticalScaling * image.width).toInt())
            val scaling = matWidth / image.width
            val matHeight = max(1, ceil(scaling * imageHeight).toInt())
            val materialized = drawToBufferedImage(matWidth, matHeight, grounding, bitmapJ2DBridge) { canvas ->
                image.copy(universeScaling = scaling).materialize(canvas, lowResCache, layers)
            }
            SwingUtilities.invokeLater {
                if (this.lowResMaterializedContentVersion > contentVersion)
                    return@invokeLater
                this.lowResMaterialized = materialized
                this.lowResMaterializedContentVersion = contentVersion
            }
        }
    }


    companion object {

        private const val SCROLLBAR_MULT = 1024.0
        private const val MAX_MAT_PIXELS = 5_000_000

        private inline fun drawToBufferedImage(
            w: Int, h: Int, grounding: Color4f, bitmapJ2DBridge: BitmapJ2DBridge, draw: (Canvas) -> Unit
        ): BufferedImage {
            val res = Resolution(w, h)
            val canvasCS = ColorSpace.of(ColorSpace.Primaries.BT709, ColorSpace.Transfer.BLENDING)
            val canvasRep = Canvas.compatibleRepresentation(canvasCS)
            Bitmap.allocate(Bitmap.Spec(res, canvasRep)).use { canvasBmp ->
                Bitmap.allocate(Bitmap.Spec(res, bitmapJ2DBridge.nativeRepresentation)).use { nativeBmp ->
                    Canvas.forBitmap(canvasBmp).use { canvas ->
                        canvas.fill(Canvas.Shader.Solid(grounding))
                        draw(canvas)
                    }
                    BitmapConverter.convert(canvasBmp, nativeBmp, promiseOpaque = true, approxTransfer = true)
                    return bitmapJ2DBridge.toNativeImage(nativeBmp)
                }
            }
        }

    }


    private inner class Scrollbar(orientation: Int) : JScrollBar(orientation) {
        // When the user clicks on the scrollbar, the viewport should page. The default block increment of 10 is however
        // way too small. We set it to scroll the entire width/height of the viewport, which is exactly what other
        // components like text panes do.
        override fun getBlockIncrement(direction: Int): Int =
            (SCROLLBAR_MULT * if (orientation == HORIZONTAL) viewportWidth else viewportHeight).toInt()
    }


    private inner class CanvasPanel : JPanel() {

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            // Capture these variables.
            val image = this@DeferredImagePanel.image
            val materialized = this@DeferredImagePanel.materialized
            val lowResMaterialized = this@DeferredImagePanel.lowResMaterialized

            if (image == null || materialized == null) {
                // If the materialized image is not ready yet, draw a loading indicator instead.
                FlatUIUtils.setRenderingHints(g)
                g.font = UIManager.getFont("h0.font").deriveFont(Font.BOLD)
                val m = g.fontMetrics
                val t = l10n("ui.edit.loading")
                FlatUIUtils.drawString(this, g, t, (width - m.stringWidth(t)) / 2, (height - m.height) / 2 + m.ascent)
            } else {
                // Find the width of the viewport in materialized image coordinates.
                val materializedViewportWidth = (viewportWidth / image.width) * materialized.width
                // Find the amount the materialized image would need to be scaled
                // so that it can be painted onto this canvas.
                val extraScaling = width / materializedViewportWidth

                g.withNewG2 { g2 ->
                    // Use nearest-neighbor interpolation for a massive speed improvement when momentarily painting
                    // intermediate images at the wrong scale.
                    g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    )

                    // If the materialized image doesn't cover the whole deferred image and the user has scrolled past
                    // the covered area, momentarily paint a backup low-res image using fast nearest-neighbor
                    // interpolation until the materialization of the now visible area has caught up. Notice that if
                    // only part of the viewport is not covered, the regular painting further below will still paint the
                    // full-resolution version of the covered part.
                    if (lowResMaterialized != null && !materializedStartY.isNaN() &&
                        (viewportStartY < materializedStartY || viewportStopY > materializedStopY)
                    ) {
                        // This scaling factor maps from low-res to deferred image coordinates. It can be prepended to
                        // imageScaling to obtain a map from low-res to canvas coordinates.
                        val invertedLowResScaling = image.width / lowResMaterialized.width
                        // Obtain a version of the materialized low-res image that has its top cut off, such that we do
                        // not have to translate it much in order to draw it at the overall y translation (which is
                        // dictated by the current viewport). This is necessary because Java2D completely refuses to
                        // draw an image if the y translation exceeds 2^16.
                        val cut = (viewportStartY / invertedLowResScaling).toInt()
                            // Some users experienced "cut" exceeding the image height for some reason. We guard against
                            // those crashes by simply clamping "cut".
                            .coerceIn(0, lowResMaterialized.height - 1)
                        val subLowResMat = lowResMaterialized.getSubimage(
                            0, cut, lowResMaterialized.width, lowResMaterialized.height - cut
                        )
                        // Obtain a transformation that performs:
                        //   - The upscaling of the low-res version.
                        //   - The x translation dictated by the viewport position.
                        //   - The remaining y translation.
                        val tx = AffineTransform().apply {
                            scale(imageScaling)
                            translate(-viewportStartX, -viewportStartY)
                            scale(invertedLowResScaling)
                            translate(0.0, cut.toDouble())
                        }
                        // Draw the low-res version.
                        g2.drawImage(subLowResMat, tx, null)
                    }

                    // This transformation will be used for drawing the materialized image.
                    val tx = AffineTransform()
                    // Scroll to the current viewport.
                    tx.translate(
                        -((viewportCenterX - minViewportCenterX) * imageScaling),
                        -((viewportCenterY - minViewportCenterY) * imageScaling)
                    )
                    // Again, if the materialized image doesn't cover the whole deferred image, translate such that the
                    // partial materialized image is at the right position.
                    if (!materializedStartY.isNaN())
                        tx.translate(0.0, materializedStartY * imageScaling)
                    if (abs(extraScaling - 1) > 0.001) {
                        // If we have a materialized image, but the pixel width of the canvas doesn't match the pixel
                        // width of the viewport in the materialized image, this can have one of two reasons:
                        //   - Either we are in a HiDPI environment where system scaling is enabled, which we have
                        //     already considered when creating the materialized image, hence the materialized has the
                        //     right width while the canvas' width is "fake".
                        //   - Or the user resized the viewport but the image hasn't been re-materialized yet to match
                        //     the new viewport size.
                        // In either case, paint a nearest-neighbor interpolated scaled version of the materialized
                        // image onto the canvas. In the first case, this is exactly what is required to paint the full
                        // resolution image. In the second case, the scaled version will look bad, but scaling is fast,
                        // and the quality will improve again once the background thread has finished re-materializing
                        // the image for the current size; another call to this painting method will then be triggered.
                        tx.scale(extraScaling)
                    }
                    g2.drawImage(materialized, tx, null)
                }
            }
        }

    }

}
