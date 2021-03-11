package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.BACKGROUND
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.toImmutableList
import java.awt.Font
import java.awt.geom.Path2D


fun drawPage(
    global: Global,
    fonts: Map<LetterStyle, Font>,
    page: Page
): DrawnPage {
    // Convert the aligning group lists to maps that map from block to group id.
    val alignBodyColsGroupIds = page.alignBodyColsGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()
    val alignHeadTailGroupIds = page.alignHeadTailGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()

    // Generate an image for each column.
    // Also remember the x coordinate of the axis inside each generated image.
    val drawnColumns = page.stages
        .flatMap { stage -> stage.segments }.flatMap { segment -> segment.columns }
        .associateWith { column -> drawColumn(fonts, column, alignBodyColsGroupIds, alignHeadTailGroupIds) }

    val pageImage = DeferredImage()
    // First, for each stage, combine the column images to a stage image, and then combine the stage images to the
    // page image. Also record for each stage its tightest top and bottom y coordinates in the overall image.
    val stageImageBounds = mutableListOf<Pair<Float, Float>>()
    var y = 0f
    for ((stageIdx, stage) in page.stages.withIndex()) {
        // Generate an image for the current stage.
        val stageImage = drawStage(global, drawnColumns, stage)
        // Special handling for card stages...
        if (stage.style.behavior == PageBehavior.CARD) {
            // The amount of padding that needs to be added above and below the card's stage image such that
            // it is centered on the screen.
            val cardPaddingHeight = (global.heightPx - stageImage.height) / 2f
            // If this card stage is the first and/or the last stage, make sure that there is extra padding below
            // and above the card stage such that its content is centered vertically.
            if (stageIdx == 0)
                y += cardPaddingHeight
            if (stageIdx == page.stages.lastIndex)
                pageImage.height = y + stageImage.height + cardPaddingHeight
            // Draw guides that show the boundaries of the screen as they will be when this card will be shown.
            // Note: We subtract 1 from the width and height; if we don't, the right and lower lines of the
            // rectangle are often rendered only partially or not at all.
            pageImage.drawRect(
                CARD_GUIDE_COLOR, 0f, y - cardPaddingHeight, global.widthPx - 1f, global.heightPx - 1f, layer = GUIDES
            )
            // If the card is an intermediate stage, also draw arrows that indicate that the card is intermediate.
            if (stageIdx != 0)
                pageImage.drawMeltedCardArrowGuide(global, y - cardPaddingHeight)
            if (stageIdx != page.stages.lastIndex)
                pageImage.drawMeltedCardArrowGuide(global, y + stageImage.height + cardPaddingHeight)
        }
        // Record the stage content's tightest top and bottom y coordinates in the overall page image.
        stageImageBounds.add(Pair(y, y + stageImage.height))
        // Actually draw the stage image onto the page image.
        pageImage.drawDeferredImage(stageImage, 0f, y)
        // Advance to the next stage image.
        y += stageImage.height + stage.vGapAfterPx
    }
    // Enforce that the page image has exactly the global width. This is necessary because the user might draw
    // stuff outside the bounds defined by the global width, and we don't want that stuff to enlarge the page.
    pageImage.width = global.widthPx.toFloat()

    // Fill the background of the page image.
    pageImage.drawRect(
        global.background, 0f, 0f, pageImage.width, pageImage.height, fill = true, layer = BACKGROUND
    )

    // Find for each stage:
    //   - If it's a card stage, its middle y coordinate in the overall page image.
    //   - If it's a scroll stage, the y coordinates in the overall page image that are at the center of the screen
    //     when the scrolling of this stage starts resp. stops.
    val stageInfo = page.stages.mapIndexed { stageIdx, stage ->
        val (topY, botY) = stageImageBounds[stageIdx]
        when (stage.style.behavior) {
            PageBehavior.CARD -> DrawnStageInfo.Card((topY + botY) / 2f)
            PageBehavior.SCROLL -> {
                val scrollStartY = when (page.stages.getOrNull(stageIdx - 1)?.style?.behavior) {
                    PageBehavior.CARD -> stageImageBounds[stageIdx - 1].let { (aTopY, aBotY) -> (aTopY + aBotY) / 2f }
                    PageBehavior.SCROLL -> stageImageBounds[stageIdx - 1].let { (_, aBotY) -> (aBotY + topY) / 2f }
                    null -> topY /* will always be 0 */ - global.heightPx / 2f
                }
                val scrollStopY = when (page.stages.getOrNull(stageIdx + 1)?.style?.behavior) {
                    PageBehavior.CARD -> stageImageBounds[stageIdx + 1].let { (bTopY, bBotY) -> (bTopY + bBotY) / 2f }
                    PageBehavior.SCROLL -> stageImageBounds[stageIdx + 1].let { (bTopY, _) -> (botY + bTopY) / 2f }
                    null -> botY + global.heightPx / 2f
                }
                DrawnStageInfo.Scroll(scrollStartY, scrollStopY)
            }
        }
    }

    return DrawnPage(pageImage, stageInfo.toImmutableList())
}


private fun drawStage(
    global: Global,
    columnImages: Map<Column, DrawnColumn>,
    stage: Stage
): DeferredImage {
    val stageImage = DeferredImage()
    var y = 0f
    for (segment in stage.segments) {
        for (column in segment.columns) {
            val axisXInPageImage = global.widthPx / 2f + column.posOffsetPx
            val drawnColumn = columnImages.getValue(column)
            val x = axisXInPageImage - drawnColumn.axisXInImage
            stageImage.drawDeferredImage(drawnColumn.defImage, x, y)
        }
        y = stageImage.height + segment.vGapAfterPx
    }
    return stageImage
}


private fun DeferredImage.drawMeltedCardArrowGuide(global: Global, y: Float) {
    val triangle = Path2D.Float().apply {
        moveTo(-0.5, -0.45)
        lineTo(0.5, -0.45)
        lineTo(0.0, 0.55)
        closePath()
    }
    drawShape(
        CARD_GUIDE_COLOR, triangle, global.widthPx / 2f, y, global.widthPx / 100f, fill = true, layer = GUIDES
    )
}
