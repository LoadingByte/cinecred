package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.DeferredImage.Layer
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import net.miginfocom.swing.MigLayout
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.RenderingHints
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
class DeferredImagePanel(val maxZoom: Float) : JPanel(MigLayout("gap 0, insets 0")) {

    private var image: DeferredImage? = null

    fun setImage(image: DeferredImage?) {
        require(image == null || image.width != 0f && image.height != 0f)
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
            if (zoom != newZoom) {
                field = newZoom
                for (listener in zoomListeners)
                    listener(zoom)
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
            zoom -= (it.preciseWheelRotation / 10.0).toFloat()
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
            xScrollbar.model.value = ((value - minViewportCenterX) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
        }
    private var viewportCenterY = 0f
        set(value) {
            field = value.coerceIn(minViewportCenterY, maxViewportCenterY)
            disableScrollbarListeners = true
            yScrollbar.model.value = ((value - minViewportCenterY) * SCROLLBAR_MULT).roundToInt()
            disableScrollbarListeners = false
        }

    // In image coordinates:
    private val viewportWidth get() = image!!.width / zoom
    private val viewportHeight get() = min(image!!.height, canvas.height.also { require(it != 0) } / imageScaling)
    private val minViewportCenterX get() = viewportWidth / 2f
    private val maxViewportCenterX get() = image!!.width - minViewportCenterX
    private val minViewportCenterY get() = viewportHeight / 2f
    private val maxViewportCenterY get() = image!!.height - minViewportCenterY

    private val imageScaling get() = zoom * canvas.width.also { require(it != 0) } / image!!.width

    private fun coerceViewportAndCalibrateScrollbars() {
        val image = this.image

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            // Disable the scrollbars because their change listeners depend on the size of the image
            // and will throw errors when trying to access a null image.
            xScrollbar.isEnabled = false
            yScrollbar.isEnabled = false
        } else {
            viewportCenterX = viewportCenterX.coerceIn(minViewportCenterX, maxViewportCenterX)
            viewportCenterY = viewportCenterY.coerceIn(minViewportCenterY, maxViewportCenterY)

            disableScrollbarListeners = true
            xScrollbar.model.apply {
                maximum = (image.width * SCROLLBAR_MULT).roundToInt()
                extent = (viewportWidth * SCROLLBAR_MULT).roundToInt()
            }
            yScrollbar.model.apply {
                maximum = (image.height * SCROLLBAR_MULT).roundToInt()
                extent = (viewportHeight * SCROLLBAR_MULT).roundToInt()
            }
            // Disable scrollbars if they have no room to scroll.
            xScrollbar.isEnabled = xScrollbar.model.run { maximum != extent }
            yScrollbar.isEnabled = yScrollbar.model.run { maximum != extent }
            disableScrollbarListeners = false
        }
    }

    private fun rematerialize() {
        val image = this.image
        val viewportCenterX = this.viewportCenterX
        val viewportCenterY = this.viewportCenterY

        if (image == null || canvas.width == 0 || canvas.height == 0) {
            materialized = null
            canvas.repaint()
        } else {
            submitMaterializeJob(materializingJobSlot, image, imageScaling, layers, onFinish = { mat ->
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
            jobSlot: JobSlot, image: DeferredImage, imageScaling: Float,
            layers: List<Layer>, onFinish: (BufferedImage) -> Unit
        ) {
            jobSlot.submit {
                // Use max(1, ...) to ensure that the raster image width doesn't drop to 0.
                val matWidth = max(1, (imageScaling * image.width).roundToInt())
                val matHeight = max(1, (imageScaling * image.height).roundToInt())
                val materialized = gCfg.createCompatibleImage(matWidth, matHeight).withG2 { g2 ->
                    g2.setHighQuality()
                    // Paint a scaled version of the deferred image onto the raster image.
                    val scaledImage = DeferredImage()
                    scaledImage.drawDeferredImage(image, 0f, 0f, imageScaling)
                    scaledImage.materialize(g2, layers)
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
                val extraScaling = width / materializedViewportWidth.toDouble()

                g.withNewG2 { g2 ->
                    g2.translate(
                        -((viewportCenterX - minViewportCenterX) * imageScaling).toDouble(),
                        -((viewportCenterY - minViewportCenterY) * imageScaling).toDouble()
                    )
                    if (abs(extraScaling - 1) > 0.001) {
                        // If we have a materialized image, but its size doesn't match the currently requested size (for
                        // a variety of reasons), for now paint a very poorly (but fastly) scaled version of the old
                        // materialized image onto the canvas. When the background thread has finished re-materializing
                        // the image for the current size, another repaint will be triggered and that will be painted.
                        g2.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        )
                        g2.scale(extraScaling, extraScaling)
                    }
                    g2.drawImage(materialized, 0, 0, null)
                }
            }
        }

    }

}
