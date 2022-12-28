package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.DeferredImage.Layer
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import kotlin.math.*


/**
 * Because JScrollPane turns out to have a lot of subtle issues for what we're trying to accomplish,
 * we devise our own scrollable panel for displaying a [DeferredImage]. We also implement additional features
 * like panning (via clicking and dragging) and zooming.
 */
class DeferredImagePanel(private val maxZoom: Double, private val zoomIncrement: Double) :
    JPanel(MigLayout("gap 0, insets 0")) {

    var image: DeferredImage?
        get() = _image
        set(image) {
            require(image == null || image.width != 0.0 && image.height.resolve() != 0.0)
            _image = image
            coerceViewportAndCalibrateScrollbars()
            // Rematerialize will call canvas.repaint() once it's done.
            rematerialize(contentChanged = true)
        }

    var layers: List<Layer> = listOf()
        set(value) {
            field = value
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

    // Use and cache an intermediate materialized image of the current sizing. We first paint
    // a properly scaled version of the deferred image onto the raster image. Then, we directly paint that
    // raster image onto the canvas. This way, we avoid materializing the deferred image over and over again
    // whenever the user scrolls, which can be very expensive when the deferred image contains, e.g., PDFs.
    // Additionally, if a raster image of the entire deferred image would be too large to comfortably fit into
    // memory, we only rasterize the portion around the currently visible viewport, but keep a low-res version
    // of the entire image that we momentarily show when the user scrolls out of the rasterized portion too fast.
    private var materialized: BufferedImage? = null
    // In image coordinates:
    private var materializedStartY = Double.NaN
    private var materializedStopY = Double.NaN
    private var lowResMaterialized: BufferedImage? = null
    private val materializingJobSlot = JobSlot()

    private val canvas = Canvas()
    private val xScrollbar = JScrollBar(JScrollBar.HORIZONTAL)
    private val yScrollbar = JScrollBar(JScrollBar.VERTICAL)
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

        // When the user clicks on the scrollbar, the viewport should page. The default increment of 10 however is way
        // too small. We set it to this value, which scrolls 500 pixels each time.
        xScrollbar.blockIncrement = 500 * SCROLLBAR_MULT.toInt()
        yScrollbar.blockIncrement = 500 * SCROLLBAR_MULT.toInt()

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

        // When the user scrolls inside the canvas, adjust the zoom.
        canvas.addMouseWheelListener {
            zoom -= it.preciseWheelRotation * zoomIncrement
        }

        // When the user clicks and drags inside the canvas, move the viewport center.
        val canvasDragListener = object : MouseAdapter() {
            private var startPoint: Point? = null
            private var startViewportCenterX = 0.0
            private var startViewportCenterY = 0.0

            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    startPoint = e.point
                    startViewportCenterX = viewportCenterX
                    startViewportCenterY = viewportCenterY
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1)
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
        get() = imageScaling * UIScale.getSystemScaleFactor(canvas.graphics as Graphics2D)

    private fun coerceViewportAndCalibrateScrollbars() {
        val image = this.image

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            // Disable the scrollbars because their change listeners depend on the size of the image
            // and will throw errors when trying to access a null image.
            xScrollbar.isEnabled = false
            yScrollbar.isEnabled = false
        } else {
            // Setters do the coercion:
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
        }
    }

    private fun rematerialize(contentChanged: Boolean) {
        // Capture these variables.
        val image = this.image
        val viewportCenterX = this.viewportCenterX
        val viewportCenterY = this.viewportCenterY

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            materialized = null
            canvas.repaint()
        } else {
            // Update the low-res version if either the content changed or there is not a low-res version yet. Note that
            // submitMaterializeJob() only actually materializes the low-res version when the regular "mat" is just a
            // portion of the whole deferred image. Otherwise, the function returns null, and as a consequence, the
            // previous low-res version is discarded.
            val lowRes = contentChanged || lowResMaterialized == null
            submitMaterializeJob(
                materializingJobSlot, image, physicalImageScaling, viewportHeight, viewportCenterY, lowRes, layers
            ) { mat, matStartY, matStopY, lowResMat ->
                SwingUtilities.invokeLater {
                    materialized = mat
                    materializedStartY = matStartY
                    materializedStopY = matStopY
                    if (lowRes) lowResMaterialized = lowResMat
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
                    if (this.image == image)
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
    }


    companion object {

        private const val SCROLLBAR_MULT = 1024.0
        private const val MIN_IMG_BUFFER = 400
        private const val MAX_MAT_PIXELS = 5_000_000

        // We use this external static method to absolutely ensure all variables have been captured.
        // We can now use these variables from another thread without fearing they might change suddenly.
        private fun submitMaterializeJob(
            jobSlot: JobSlot, image: DeferredImage,
            physicalImageScaling: Double, viewportHeight: Double, viewportCenterY: Double, lowRes: Boolean,
            layers: List<Layer>, onFinish: (BufferedImage, Double, Double, BufferedImage?) -> Unit
        ) {
            jobSlot.submit {
                val imgHeight = image.height.resolve()

                // If a raster image with the given physical scaling would exceed MAX_PIXELS, we only materialize the
                // portion of the deferred image around the current viewport. For that, we find the height of the
                // portion and its top y, both in image coordinates.
                val clippedImgHeight = min(
                    imgHeight,
                    max(viewportHeight + MIN_IMG_BUFFER, MAX_MAT_PIXELS / (image.width * physicalImageScaling.pow(2)))
                )
                val startY = if (clippedImgHeight == imgHeight) Double.NaN else
                    (viewportCenterY - clippedImgHeight / 2.0).coerceIn(0.0, imgHeight - clippedImgHeight)

                // Materialize the image or a portion thereof.
                // Use max(1, ...) to ensure that the raster image dimensions don't drop to 0.
                val matWidth = max(1, (physicalImageScaling * image.width).roundToInt())
                val matHeight = max(1, (physicalImageScaling * clippedImgHeight).roundToInt())
                val materialized = gCfg.createCompatibleImage(matWidth, matHeight, Transparency.OPAQUE).withG2 { g2 ->
                    g2.setHighQuality()
                    // If only a portion is materialized, scroll the deferred image to that portion.
                    if (!startY.isNaN())
                        g2.translate(0.0, physicalImageScaling * -startY)
                    // If only a portion is materialized, cull the rest to improve performance.
                    val culling = if (startY.isNaN()) null else Rectangle2D.Double(
                        0.0, physicalImageScaling * startY, matWidth.toDouble(), matHeight.toDouble()
                    )
                    // Paint a scaled version of the deferred image onto the raster image.
                    image.copy(universeScaling = physicalImageScaling).materialize(g2, layers, culling)
                }

                // If only a portion is materialized, we generate another more low-res image that covers the whole
                // deferred image. We momentarily paint this placeholder image when the user scrolls out of the
                // materialized portion too quickly.
                val lowResMaterialized = if (!lowRes || startY.isNaN()) null else {
                    val lowResScaling = sqrt(MAX_MAT_PIXELS / (image.width * imgHeight))
                    // Again use max(1, ...) to ensure that the raster image dimensions do not drop to 0.
                    val lowResMatWidth = max(1, (lowResScaling * image.width).roundToInt())
                    val lowResMatHeight = max(1, (lowResScaling * imgHeight).roundToInt())
                    gCfg.createCompatibleImage(lowResMatWidth, lowResMatHeight, Transparency.OPAQUE).withG2 { g2 ->
                        g2.setHighQuality()
                        image.copy(universeScaling = lowResScaling).materialize(g2, layers)
                    }
                }

                onFinish(materialized, startY, startY + clippedImgHeight, lowResMaterialized)
            }
        }

    }


    private inner class Canvas : JPanel() {

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            // Capture these variables.
            val image = this@DeferredImagePanel.image
            val materialized = this@DeferredImagePanel.materialized
            val lowResMaterialized = this@DeferredImagePanel.lowResMaterialized

            if (image != null && materialized != null) {
                // Find the top and bottom edges of the current viewport.
                val viewportTopY = viewportCenterY - viewportHeight / 2.0
                val viewportBotY = viewportCenterY + viewportHeight / 2.0
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

                    // Scroll to the current viewport.
                    g2.translate(
                        -((viewportCenterX - minViewportCenterX) * imageScaling),
                        -((viewportCenterY - minViewportCenterY) * imageScaling)
                    )

                    // If the materialized image doesn't cover the whole deferred image and the user has scrolled past
                    // the covered area, momentarily paint a backup low-res image using fast nearest-neighbor
                    // interpolation until the materialization of the now visible area has caught up. Notice that if
                    // only part of the viewport is not covered, the regular painting further below will still paint the
                    // full-resolution version of the covered part.
                    if (lowResMaterialized != null && !materializedStartY.isNaN() &&
                        (viewportTopY < materializedStartY || viewportBotY > materializedStopY)
                    ) {
                        // This scaling factor maps from low-res to deferred image coordinates. It can be prepended to
                        // imageScaling to obtain a map from low-res to canvas coordinates.
                        val invertedLowResScaling = image.width / lowResMaterialized.width
                        val s = invertedLowResScaling * imageScaling
                        g2.drawImage(lowResMaterialized, AffineTransform.getScaleInstance(s, s), null)
                    }

                    // Again, if the materialized image doesn't cover the whole deferred image, translate such that the
                    // partial materialized image is at the right position.
                    if (!materializedStartY.isNaN())
                        g2.translate(0.0, materializedStartY * imageScaling)

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
                        g2.scale(extraScaling)
                    }

                    g2.drawImage(materialized, 0, 0, null)
                }
            }
        }

    }

}
