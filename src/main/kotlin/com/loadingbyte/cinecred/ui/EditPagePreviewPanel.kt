package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Global
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt


class EditPagePreviewPanel(zoom: Float, showGuides: Boolean, showSafeAreas: Boolean) :
    JScrollPane(VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_ALWAYS) {

    private val imagePanel = ImagePanel().also { setViewportView(it) }

    private var rasterDirty = true
    private var cachedRasterImage: BufferedImage? = null
    private var cachedZoomedRasterImage: BufferedImage? = null
    private var cachedZoomedRasterImageZoom = -1f
    private val paintingJobSlot = JobSlot()

    private var globalWidth = 0
    private var globalHeight = 0
    private var backgroundColor = Color.BLACK
    private var defImage = DeferredImage()
    private var stageInfo = emptyList<DrawnStageInfo>()

    fun setContent(global: Global, drawnPage: DrawnPage) {
        // Make sure that the scroll pane remembers its previous scroll height.
        val scrollHeight = verticalScrollBar.value

        globalWidth = global.widthPx
        globalHeight = global.heightPx
        backgroundColor = global.background
        defImage = drawnPage.defImage
        stageInfo = drawnPage.stageInfo
        rasterDirty = true
        imagePanel.revalidate()  // This changes the scrollbar length when the page height changes.
        imagePanel.repaint()

        verticalScrollBar.value = scrollHeight
    }

    var zoom = zoom
        set(value) {
            val oldZoom = field
            if (value != oldZoom) {
                val oldView = viewport.viewRect
                field = value
                viewport.viewPosition = Point(
                    ((oldView.x + oldView.width / 2f) * value / oldZoom - oldView.width / 2f)
                        .roundToInt().coerceAtLeast(0),
                    ((oldView.y + oldView.height / 2f) * value / oldZoom - oldView.height / 2f)
                        .roundToInt().coerceAtLeast(0)
                )
            }
        }
    var showGuides = showGuides
        set(value) {
            field = value; rasterDirty = true; imagePanel.repaint()
        }
    var showSafeAreas = showSafeAreas
        set(value) {
            field = value; imagePanel.repaint()
        }

    init {
        // When the scroll pane's width changes, mark it as dirty and repaint. Note that we cannot just
        // listen for changes to the image panel's width because that also triggers when zooming.
        var prevWidth = width
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (prevWidth != width) {
                    prevWidth = width
                    rasterDirty = true
                    imagePanel.repaint()
                }
            }
        })
    }


    private inner class ImagePanel : JPanel() {

        private val unzoomedWidth: Int get() = viewport.width
        private val unzoomedHeight: Int get() = (defImage.height * viewport.width / globalWidth).roundToInt()

        // The parent scroll pane uses this information to decide on the lengths of the scrollbars.
        override fun getPreferredSize() = Dimension(
            (zoom * unzoomedWidth).roundToInt(),
            (zoom * unzoomedHeight).roundToInt()
        )

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val curCachedRasterImage = cachedRasterImage

            // Use two intermediate cached raster images. We first paint a scaled version of the deferred image onto a
            // raster image two times the size of the panel. Then, we paint that raster image onto another raster image
            // which has the zoomed size of the panel. That final image is then painted onto the panel.
            // This way, we avoid materializing the deferred image over and over again whenever the user zooms, which
            // can be very expensive when the deferred image contains, e.g., PDFs. We also avoid scaling the first
            // cached raster image over and over again whenever the user scrolls.
            // Additionally, because the first raster image has two times the size of the panel (which in total most
            // of the time amounts to a width of ~1920), we avoid directly drawing a very scaled-down version of the
            // deferred image, which could lead to text kerning issues etc.
            if (rasterDirty) {
                rasterDirty = false
                val curZoom = zoom
                paintingJobSlot.submit {
                    val newRasterImage = paintRasterImage()
                    val newZoomedRasterImage = paintZoomedRasterImage(newRasterImage, curZoom)
                    // Use invokeLater() to make sure that when the following code is executed, we are definitely
                    // no longer in the outer paintComponent() method. This way, we can go without explicit locking.
                    // Also, we set the cached image variables in the AWT thread, which alleviates the need to declare
                    // them as volatile.
                    SwingUtilities.invokeLater {
                        cachedRasterImage = newRasterImage
                        cachedZoomedRasterImage = newZoomedRasterImage
                        cachedZoomedRasterImageZoom = curZoom
                        repaint()
                    }
                }
                // This method will be called once again once the background thread has finished painting the
                // intermediate images. We will now go on painting an old version of some cached image.
            }

            val curCachedZoomedRasterImage = cachedZoomedRasterImage
            if (curCachedZoomedRasterImage != null && cachedZoomedRasterImageZoom == zoom) {
                // If the we already have a cached version of the raster image for the current zoom,
                // paint that onto the panel.
                g.drawImage(curCachedZoomedRasterImage, 0, 0, null)
            } else if (curCachedRasterImage != null) {
                // If we have a cached version of the raster image, but not for the current zoom (because the user
                // adjusted the zoom), create such a zoomed image, cache it, and paint it onto the panel. We do
                // the painting in the AWT thread because it's very fast, so when zooming, the user won't notice
                // the delay.
                // Note that if instead the images are adjusted as a consequence of a styling change, the current
                // cached zoomed image has been created in a background thread, so the user doesn't get noticeable
                // hick-ups when, e.g., adjusting spinners.
                val newZoomedRasterImage = paintZoomedRasterImage(curCachedRasterImage, zoom)
                cachedZoomedRasterImage = newZoomedRasterImage
                cachedZoomedRasterImageZoom = zoom
                g.drawImage(newZoomedRasterImage, 0, 0, null)
            } else {
                // If the preview panel has just been created, it doesn't have a cached image yet.
                // In that case, we just don't draw an empty background.
                g.color = backgroundColor
                g.fillRect(0, 0, (zoom * unzoomedWidth).roundToInt(), (zoom * unzoomedHeight).roundToInt())
            }
            // If requested, paint the action safe and title safe areas.
            if (showSafeAreas)
                paintSafeAreas(g)
        }

        private fun paintRasterImage(): BufferedImage {
            val rasterImage = gCfg.createCompatibleImage(2 * unzoomedWidth, 2 * unzoomedHeight)
            rasterImage.withG2 { g2 ->
                g2.setHighQuality()
                // Fill the background.
                g2.color = backgroundColor
                g2.fillRect(0, 0, rasterImage.width, rasterImage.height)
                // Paint a scaled version of the deferred image onto the raster image.
                val scaledDefImage = DeferredImage()
                scaledDefImage.drawDeferredImage(defImage, 0f, 0f, rasterImage.width / globalWidth.toFloat())
                // Note: We compute the guide stroke width such that they have a nice thin width irrespective of the
                // configured film dimensions.
                scaledDefImage.materialize(g2, showGuides, guideStrokeWidth = globalWidth / 1024f)
            }
            return rasterImage
        }

        private fun paintZoomedRasterImage(rasterImage: BufferedImage, zoom: Float) =
            gCfg.createCompatibleImage((zoom * unzoomedWidth).roundToInt(), (zoom * unzoomedHeight).roundToInt())
                .withG2 { g2 ->
                    g2.setHighQuality()
                    g2.drawImage(rasterImage, AffineTransform.getScaleInstance(zoom * 0.5, zoom * 0.5), null)
                }

        private fun paintSafeAreas(g: Graphics) {
            g.withNewG2 { g2 ->
                g2.color = Color.GRAY
                if (zoom != 1f)
                    g2.scale(zoom.toDouble(), zoom.toDouble())

                // By asking the graphics object itself to scale such that we accommodate for the
                // smaller preview size, we can work in regular page units in the rest of this method.
                val previewScaling = unzoomedWidth / globalWidth.toDouble()
                g2.scale(previewScaling, previewScaling)

                val actionSafeWidth = globalWidth * 0.93f
                val titleSafeWidth = globalWidth * 0.9f
                val actionSafeHeight = globalHeight * 0.93f
                val titleSafeHeight = globalHeight * 0.9f
                val actionSafeX1 = (globalWidth - actionSafeWidth) / 2f
                val titleSafeX1 = (globalWidth - titleSafeWidth) / 2f
                val actionSafeX2 = actionSafeX1 + actionSafeWidth
                val titleSafeX2 = titleSafeX1 + titleSafeWidth

                // Draw full safe area hints for each card stage.
                for (middleY in stageInfo.filterIsInstance<DrawnStageInfo.Card>().map { it.middleY }) {
                    val actionSafeY1 = middleY - actionSafeHeight / 2f
                    val titleSafeY1 = middleY - titleSafeHeight / 2f
                    g2.draw(Rectangle2D.Float(actionSafeX1, actionSafeY1, actionSafeWidth, actionSafeHeight))
                    g2.draw(Rectangle2D.Float(titleSafeX1, titleSafeY1, titleSafeWidth, titleSafeHeight))
                    val titleSafeY2 = titleSafeY1 + titleSafeHeight
                    val d = globalWidth / 200f
                    g2.draw(Line2D.Float(titleSafeX1 - d, middleY, titleSafeX1 + d, middleY))
                    g2.draw(Line2D.Float(titleSafeX2 - d, middleY, titleSafeX2 + d, middleY))
                    g2.draw(Line2D.Float(globalWidth / 2f, titleSafeY1 - d, globalWidth / 2f, titleSafeY1 + d))
                    g2.draw(Line2D.Float(globalWidth / 2f, titleSafeY2 - d, globalWidth / 2f, titleSafeY2 + d))
                }

                // Draw left and right safe area strips if there are scroll stages on the page. If the page starts
                // resp. ends with a card stage, make sure to NOT draw the strips to the page's very top resp. bottom.
                if (stageInfo.any { it is DrawnStageInfo.Scroll }) {
                    val firstStageInfo = stageInfo.first()
                    val lastStageInfo = stageInfo.last()
                    val firstCardMiddleY = if (firstStageInfo is DrawnStageInfo.Card) firstStageInfo.middleY else null
                    val lastCardMiddleY = if (lastStageInfo is DrawnStageInfo.Card) lastStageInfo.middleY else null
                    val actionSafeY1 = firstCardMiddleY?.let { it - actionSafeHeight / 2f } ?: 0f
                    val titleSafeY1 = firstCardMiddleY?.let { it - titleSafeHeight / 2f } ?: 0f
                    val actionSafeY2 = lastCardMiddleY?.let { it + actionSafeHeight / 2f } ?: 0f
                    val titleSafeY2 = lastCardMiddleY?.let { it + titleSafeHeight / 2f } ?: 0f
                    g2.draw(Line2D.Float(actionSafeX1, actionSafeY1, actionSafeX1, actionSafeY2))
                    g2.draw(Line2D.Float(actionSafeX2, actionSafeY1, actionSafeX2, actionSafeY2))
                    g2.draw(Line2D.Float(titleSafeX1, titleSafeY1, titleSafeX1, titleSafeY2))
                    g2.draw(Line2D.Float(titleSafeX2, titleSafeY1, titleSafeX2, titleSafeY2))
                }
            }
        }

    }

    companion object {
        private val gCfg = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }

}
