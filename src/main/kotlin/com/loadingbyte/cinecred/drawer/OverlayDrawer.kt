package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.DrawnStageInfo
import kotlin.math.min


fun drawSafeAreasOverlay(
    resolution: Resolution,
    stageInfo: List<DrawnStageInfo>,
    image: DeferredImage,
    layer: DeferredImage.Layer,
    color: Color4f,
    actionSafe: Double,
    titleSafe: Double
) {
    drawCropMarkers(
        resolution, stageInfo, image, layer, color,
        cropWidth = resolution.widthPx * actionSafe,
        cropHeight = resolution.heightPx * actionSafe,
        ticks = false
    )
    drawCropMarkers(
        resolution, stageInfo, image, layer, color,
        cropWidth = resolution.widthPx * titleSafe,
        cropHeight = resolution.heightPx * titleSafe,
        ticks = true
    )
}


fun drawAspectRatioOverlay(
    resolution: Resolution,
    stageInfo: List<DrawnStageInfo>,
    image: DeferredImage,
    layer: DeferredImage.Layer,
    color: Color4f,
    aspectRatio: Double
) {
    drawCropMarkers(
        resolution, stageInfo, image, layer, color,
        cropWidth = min(resolution.widthPx.toDouble(), resolution.heightPx * aspectRatio),
        cropHeight = min(resolution.heightPx.toDouble(), resolution.widthPx / aspectRatio),
        ticks = false
    )
}


private fun drawCropMarkers(
    resolution: Resolution,
    stageInfo: List<DrawnStageInfo>,
    image: DeferredImage,
    layer: DeferredImage.Layer,
    color: Color4f,
    cropWidth: Double,
    cropHeight: Double,
    ticks: Boolean
) {
    val cropX1 = (resolution.widthPx - cropWidth) / 2.0
    val cropX2 = cropX1 + cropWidth

    // Draw full crop area hints for each card stage.
    for (cardInfo in stageInfo)
        if (cardInfo is DrawnStageInfo.Card) {
            val middleY = cardInfo.middleY
            val cropY1 = middleY - cropHeight / 2.0
            val cropY2 = cropY1 + cropHeight

            image.drawRect(color, cropX1, cropY1, cropWidth, cropHeight.toY(), layer = layer)

            if (ticks) {
                val d = resolution.widthPx / 200.0
                val middleX = resolution.widthPx / 2.0
                image.drawLine(color, cropX1 - d, middleY, cropX1 + d, middleY, layer = layer)
                image.drawLine(color, cropX2 - d, middleY, cropX2 + d, middleY, layer = layer)
                image.drawLine(color, middleX, cropY1 - d, middleX, cropY1 + d, layer = layer)
                image.drawLine(color, middleX, cropY2 - d, middleX, cropY2 + d, layer = layer)
            }
        }

    // Draw left and right crop area strips if there are scroll stages on the page. If the page starts
    // resp. ends with a card stage, make sure to NOT draw the strips to the page's very top resp. bottom.
    if (stageInfo.any { it is DrawnStageInfo.Scroll }) {
        val firstStageInfo = stageInfo.first()
        val lastStageInfo = stageInfo.last()

        var cropY1 = 0.0.toY()
        var cropY2 = image.height
        if (firstStageInfo is DrawnStageInfo.Card)
            cropY1 = firstStageInfo.middleY - cropHeight / 2.0
        if (lastStageInfo is DrawnStageInfo.Card)
            cropY2 = lastStageInfo.middleY + cropHeight / 2.0

        image.drawLine(color, cropX1, cropY1, cropX1, cropY2, layer = layer)
        image.drawLine(color, cropX2, cropY1, cropX2, cropY2, layer = layer)
    }
}


fun drawLinesOverlay(
    resolution: Resolution,
    stageInfo: List<DrawnStageInfo>,
    image: DeferredImage,
    layer: DeferredImage.Layer,
    color: Color4f,
    hLines: List<Int>,
    vLines: List<Int>
) {
    // Draw horizontal lines on each card stage.
    for (cardInfo in stageInfo)
        if (cardInfo is DrawnStageInfo.Card)
            for (hLine in hLines) {
                val y = cardInfo.middleY + hLine.toDouble()
                image.drawLine(color, 0.0, y, resolution.widthPx.toDouble(), y, layer = layer)
            }

    // Draw vertical lines across the whole page.
    for (vLine in vLines) {
        val x = resolution.widthPx / 2.0 + vLine
        image.drawLine(color, x, 0.0.toY(), x, image.height, layer = layer)
    }
}


fun drawPictureOverlay(
    resolution: Resolution,
    stageInfo: List<DrawnStageInfo>,
    image: DeferredImage,
    layer: DeferredImage.Layer,
    picture: Picture
) {
    for (cardInfo in stageInfo)
        if (cardInfo is DrawnStageInfo.Card)
            image.drawEmbeddedPicture(
                Picture.Embedded(picture),
                x = (resolution.widthPx - picture.width) / 2.0,
                y = cardInfo.middleY - picture.height / 2.0,
                layer
            )
}
