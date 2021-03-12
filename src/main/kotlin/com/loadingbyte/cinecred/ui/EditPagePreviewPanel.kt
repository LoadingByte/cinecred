package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.BACKGROUND
import com.loadingbyte.cinecred.common.DeferredImage.Companion.FOREGROUND
import com.loadingbyte.cinecred.common.DeferredImage.Layer
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.PRESET_GLOBAL
import com.loadingbyte.cinecred.ui.helper.DeferredImagePanel
import kotlinx.collections.immutable.persistentListOf
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import kotlin.math.min


class EditPagePreviewPanel(maxZoom: Float) : JPanel() {

    companion object {
        val UNIFORM_SAFE_AREAS = Layer(isHelperLayer = true)
        val CUT_SAFE_AREA_16_9 = Layer(isHelperLayer = true)
        val CUT_SAFE_AREA_4_3 = Layer(isHelperLayer = true)
    }

    private val imagePanel = DeferredImagePanel(maxZoom).apply {
        layers = persistentListOf(BACKGROUND, FOREGROUND)
    }

    init {
        layout = BorderLayout()
        add(imagePanel, BorderLayout.CENTER)
    }

    private var global = PRESET_GLOBAL
    private var drawnPage = DrawnPage(DeferredImage(), persistentListOf())

    fun setContent(global: Global, drawnPage: DrawnPage) {
        this.global = global
        this.drawnPage = drawnPage

        val image = DeferredImage()
        image.drawDeferredImage(drawnPage.defImage, 0f, 0f)
        drawSafeAreas(image)
        imagePanel.setImage(image)
    }

    var zoom by imagePanel::zoom
    val zoomListeners by imagePanel::zoomListeners

    fun setLayerVisible(layer: Layer, visible: Boolean) {
        var layers = imagePanel.layers
        layers = if (visible) layers.add(layer) else layers.remove(layer)
        imagePanel.layers = layers
    }

    private fun drawSafeAreas(image: DeferredImage) {
        drawCropMarkers(image, UNIFORM_SAFE_AREAS, global.widthPx * 0.93f, global.heightPx * 0.93f)
        drawCropMarkers(image, UNIFORM_SAFE_AREAS, global.widthPx * 0.9f, global.heightPx * 0.9f, ticks = true)
        drawCropMarkers(image, UNIFORM_SAFE_AREAS, global.widthPx * 0.8f, global.heightPx * 0.8f, ticks = true)

        drawCutSafeArea(image, CUT_SAFE_AREA_16_9, 16f / 9f)
        drawCutSafeArea(image, CUT_SAFE_AREA_4_3, 4f / 3f)
    }

    private fun drawCutSafeArea(image: DeferredImage, layer: Layer, aspect: Float) {
        drawCropMarkers(
            image, layer,
            cropWidth = min(global.widthPx.toFloat(), global.heightPx * aspect),
            cropHeight = min(global.heightPx.toFloat(), global.widthPx / aspect)
        )
    }

    private fun drawCropMarkers(
        image: DeferredImage, layer: Layer, cropWidth: Float, cropHeight: Float, ticks: Boolean = false
    ) {
        val c = Color.GRAY

        val cropX1 = (global.widthPx - cropWidth) / 2f
        val cropX2 = cropX1 + cropWidth

        // Draw full crop area hints for each card stage.
        for (cardInfo in drawnPage.stageInfo.filterIsInstance<DrawnStageInfo.Card>()) {
            val middleY = cardInfo.middleY
            val cropY1 = middleY - cropHeight / 2f
            val cropY2 = cropY1 + cropHeight

            image.drawRect(c, cropX1, cropY1, cropWidth, cropHeight, layer = layer)

            if (ticks) {
                val d = global.widthPx / 200f
                image.drawLine(c, cropX1 - d, middleY, cropX1 + d, middleY, layer = layer)
                image.drawLine(c, cropX2 - d, middleY, cropX2 + d, middleY, layer = layer)
                image.drawLine(c, global.widthPx / 2f, cropY1 - d, global.widthPx / 2f, cropY1 + d, layer = layer)
                image.drawLine(c, global.widthPx / 2f, cropY2 - d, global.widthPx / 2f, cropY2 + d, layer = layer)
            }
        }

        // Draw left and right crop area strips if there are scroll stages on the page. If the page starts
        // resp. ends with a card stage, make sure to NOT draw the strips to the page's very top resp. bottom.
        if (drawnPage.stageInfo.any { it is DrawnStageInfo.Scroll }) {
            val firstStageInfo = drawnPage.stageInfo.first()
            val lastStageInfo = drawnPage.stageInfo.last()

            var cropY1 = 0f
            var cropY2 = image.height
            if (firstStageInfo is DrawnStageInfo.Card)
                cropY1 = firstStageInfo.middleY - cropHeight / 2f
            if (lastStageInfo is DrawnStageInfo.Card)
                cropY2 = lastStageInfo.middleY + cropHeight / 2f

            image.drawLine(c, cropX1, cropY1, cropX1, cropY2, layer = layer)
            image.drawLine(c, cropX2, cropY1, cropX2, cropY2, layer = layer)
        }
    }

}
