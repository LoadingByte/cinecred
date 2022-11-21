package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.DeferredImage.Layer
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * Because JScrollPane turns out to have a lot of subtle issues for what we're trying to accomplish,
 * we devise our own scrollable panel for displaying a [DeferredImage]. We also implement additional features
 * like panning (via clicking and dragging) and zooming.
 */
class DeferredImagePanel(private val maxZoom: Float, private val zoomIncrement: Float) :
    JPanel(MigLayout("gap 0, insets 0")) {

    private var image: DeferredImage? = null

    fun setImage(image: DeferredImage?) {
        require(image == null || image.width != 0f && image.height.resolve() != 0f)
        this.image = image
        coerceViewportAndCalibrateScrollbars()
        // Rematerialize will call canvas.repaint() once it's done.
        rematerialize()
    }

    var layers: PersistentList<Layer> = persistentListOf()
        set(value) {
            field = value
            // Rematerialize will call canvas.repaint() once it's done.
            rematerialize()
        }

    /**
     * Zoom = 1 means: Show the whole width of the image.
     */
    var zoom = 1f
        set(value) {
            val newZoom = value.coerceIn(1f, maxZoom)
            if (field != newZoom) {
                field = newZoom
                for (listener in zoomListeners)
                    listener(newZoom)
                coerceViewportAndCalibrateScrollbars()
                // Immediately repaint a scaled version of the old materialized image
                // while we wait for the new materialized image.
                canvas.repaint()
                rematerialize()
            }
        }

    val zoomListeners = mutableListOf<(Float) -> Unit>()

    // Use and cache an intermediate materialized image of the current sizing. We first paint
    // a properly scaled version of the deferred image onto the raster image. Then, we directly paint that
    // raster image onto the canvas. This way, we avoid materializing the deferred image over and over again
    // whenever the user scrolls, which can be very expensive when the deferred image contains, e.g., PDFs.
    private var materialized: BufferedImage? = null
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
                rematerialize()
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
            zoom -= it.preciseWheelRotation.toFloat() * zoomIncrement
        }

        // When the user clicks and drags inside the canvas, move the viewport center.
        val canvasDragListener = object : MouseAdapter() {
            private var startPoint: Point? = null
            private var startViewportCenterX = 0f
            private var startViewportCenterY = 0f

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
    private var viewportCenterX = 0f
        set(value) {
            field = value.coerceIn(minViewportCenterX, maxViewportCenterX)
            disableScrollbarListeners = true
            xScrollbar.model.value = ((field - minViewportCenterX) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
        }
    private var viewportCenterY = 0f
        set(value) {
            field = value.coerceIn(minViewportCenterY, maxViewportCenterY)
            disableScrollbarListeners = true
            yScrollbar.model.value = ((field - minViewportCenterY) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
        }

    // In image coordinates:
    private val viewportWidth get() = image!!.width / zoom
    private val viewportHeight
        get() = min(image!!.height.resolve(), canvas.height.also { require(it != 0) } / imageScaling)
    private val minViewportCenterX get() = viewportWidth / 2f
    private val maxViewportCenterX get() = image!!.width - minViewportCenterX
    private val minViewportCenterY get() = viewportHeight / 2f
    private val maxViewportCenterY get() = image!!.height.resolve() - minViewportCenterY

    // The image scaling maps from deferred image coordinates to canvas coordinates as they are used by Swing.
    // These canvas coordinates are however fake if system scaling is enabled in a HiDPI context. Hence, to find out
    // how large the materialized image should be, we need to compensate for that using the physical image scaling.
    private val imageScaling
        get() = zoom * canvas.width.also { require(it != 0) } / image!!.width
    private val physicalImageScaling
        get() = imageScaling * UIScale.getSystemScaleFactor(canvas.graphics as Graphics2D).toFloat()

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

    private fun rematerialize() {
        // Capture these variables.
        val image = this.image
        val viewportCenterX = this.viewportCenterX
        val viewportCenterY = this.viewportCenterY

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            materialized = null
            canvas.repaint()
        } else {
            submitMaterializeJob(materializingJobSlot, image, physicalImageScaling, layers, onFinish = { mat ->
                SwingUtilities.invokeLater {
                    materialized = mat
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
                        val curImage = this.image
                        val curViewportCenterX = this.viewportCenterX
                        val curViewportCenterY = this.viewportCenterY
                        this.image = image
                        this.viewportCenterX = viewportCenterX
                        this.viewportCenterY = viewportCenterY
                        canvas.paintImmediately(0, 0, canvas.width, canvas.height)
                        this.image = curImage
                        this.viewportCenterX = curViewportCenterX
                        this.viewportCenterY = curViewportCenterY
                    }
                }
            })
        }
    }


    companion object {

        private const val SCROLLBAR_MULT = 1024f

        // We use this external static method to absolutely ensure all variables have been captured.
        // We can now use these variables from another thread without fearing they might change suddenly.
        private fun submitMaterializeJob(
            jobSlot: JobSlot, image: DeferredImage, physicalImageScaling: Float,
            layers: List<Layer>, onFinish: (BufferedImage) -> Unit
        ) {
            jobSlot.submit {
                // Use max(1, ...) to ensure that the raster image dimensions don't drop to 0.
                val matWidth = max(1, (physicalImageScaling * image.width).roundToInt())
                val matHeight = max(1, (physicalImageScaling * image.height.resolve()).roundToInt())
                val materialized = gCfg.createCompatibleImage(matWidth, matHeight, Transparency.OPAQUE).withG2 { g2 ->
                    g2.setHighQuality()
                    // Paint a scaled version of the deferred image onto the raster image.
                    image.copy(universeScaling = physicalImageScaling).materialize(g2, layers)
                }

                onFinish(materialized)
            }
        }

    }


    private inner class Canvas : JPanel() {

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            // Capture these variables.
            val image = this@DeferredImagePanel.image
            val materialized = this@DeferredImagePanel.materialized

            if (image != null && materialized != null) {
                // Find the width of the viewport in materialized image coordinates.
                val materializedViewportWidth = (viewportWidth / image.width) * materialized.width
                // Find the amount the materialized image would need to be scaled
                // so that it can be painted onto this canvas.
                val extraScaling = width / materializedViewportWidth

                g.withNewG2 { g2 ->
                    g2.translate(
                        -((viewportCenterX - minViewportCenterX) * imageScaling),
                        -((viewportCenterY - minViewportCenterY) * imageScaling)
                    )
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
                        g2.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        )
                        g2.scale(extraScaling)
                    }
                    g2.drawImage(materialized, 0, 0, null)
                }
            }
        }

    }

}
