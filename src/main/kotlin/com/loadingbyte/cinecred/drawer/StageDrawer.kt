package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.Block
import com.loadingbyte.cinecred.project.Lateral
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.Spine
import com.loadingbyte.cinecred.project.Stage


class DrawnStage(val defImage: DeferredImage, val middleYInImage: Y? /* for cards only */)
private class DrawnSpine(val defImage: DeferredImage, val spineXInImage: Double)


fun drawCardStage(
    resolution: Resolution,
    drawnBlocks: Map<Block, DrawnBlock>,
    stage: Stage.Card
): DrawnStage {
    val compoundImages = ArrayList<DeferredImage>(stage.compounds.size)
    var middleYInStageImage = 0.0.toY()
    for (compound in stage.compounds) {
        val compoundImage = drawLaterals(resolution, drawnBlocks, compound.laterals, null, null)
        compoundImages.add(compoundImage)
        val m = compoundImage.height / 2.0 - compound.vOffsetPx
        middleYInStageImage = middleYInStageImage.max(m)
    }

    val stageImage = DeferredImage(width = resolution.widthPx.toDouble())
    for ((compoundIdx, compound) in stage.compounds.withIndex()) {
        val compoundImage = compoundImages[compoundIdx]
        val compoundMiddleY = middleYInStageImage + compound.vOffsetPx
        stageImage.drawDeferredImage(compoundImage, 0.0, compoundMiddleY - compoundImage.height / 2.0)
        stageImage.height = stageImage.height.max(compoundMiddleY + compoundImage.height / 2.0)
    }
    return DrawnStage(stageImage, middleYInStageImage)
}


fun drawScrollStage(
    resolution: Resolution,
    drawnBlocks: Map<Block, DrawnBlock>,
    stage: Stage.Scroll,
    prevStage: Stage?,
    nextStage: Stage?
): DrawnStage {
    // If this stage is a scroll stage is preceded by a card stage, add the vertical gap behind the card stage to the
    // front of this stage because card stage images are not allowed to have extremal vertical gaps baked into them.
    val topPad = if (prevStage != null && prevStage.style.behavior == CARD) prevStage.vGapAfterPx.toElasticY() else null
    // If this stage is a scroll stage and not the last stage on the page, add the gap behind it to its image.
    val botPad = if (nextStage != null) stage.vGapAfterPx.toElasticY() else null
    return DrawnStage(drawLaterals(resolution, drawnBlocks, stage.laterals, topPad, botPad), null)
}


private fun drawLaterals(
    resolution: Resolution,
    drawnBlocks: Map<Block, DrawnBlock>,
    laterals: List<Lateral>,
    topPadding: Y?,
    botPadding: Y?
): DeferredImage {
    val lateralsImage = DeferredImage(width = resolution.widthPx.toDouble())
    var y = topPadding ?: 0.0.toY()

    for ((lateralIdx, lateral) in laterals.withIndex()) {
        var maxHeight = 0.0.toY()
        for (spine in lateral.spines) {
            val drawnSpine = drawSpine(drawnBlocks, spine)
            val spineXInPageImage = resolution.widthPx / 2.0 + spine.hOffsetPx
            val x = spineXInPageImage - drawnSpine.spineXInImage
            lateralsImage.drawDeferredImage(drawnSpine.defImage, x, y)
            maxHeight = maxHeight.max(drawnSpine.defImage.height)
        }
        y += maxHeight
        if (lateralIdx != laterals.lastIndex)
            y += lateral.vGapAfterPx.toElasticY()
    }

    if (botPadding != null)
        y += botPadding
    lateralsImage.height = y
    return lateralsImage
}


private fun drawSpine(
    drawnBlocks: Map<Block, DrawnBlock>,
    spine: Spine
): DrawnSpine {
    // Combine the block images of the blocks that are attached to the spine to a spine image.
    val spineXInSpineImage = spine.blocks.maxOf { block -> drawnBlocks.getValue(block).spineXInImage }
    val spineImageWidth = spineXInSpineImage +
            spine.blocks.maxOf { block -> drawnBlocks.getValue(block).run { defImage.width - spineXInImage } }
    val spineImage = DeferredImage(width = spineImageWidth)
    var y = 0.0.toY()
    for ((blockIdx, block) in spine.blocks.withIndex()) {
        val drawnBlock = drawnBlocks.getValue(block)
        val x = spineXInSpineImage - drawnBlock.spineXInImage
        spineImage.drawDeferredImage(drawnBlock.defImage, x, y)
        y += drawnBlock.defImage.height
        if (blockIdx != spine.blocks.lastIndex)
            y += maxOf(block.vGapAfterPx, block.style.vMarginPx, spine.blocks[blockIdx + 1].style.vMarginPx)
                .toElasticY()
    }
    // Set the spine image's height.
    spineImage.height = y

    // Draw a guide that shows the spine position.
    spineImage.drawLine(SPINE_GUIDE_COLOR, spineXInSpineImage, 0.0.toY(), spineXInSpineImage, y, layer = GUIDES)

    return DrawnSpine(spineImage, spineXInSpineImage)
}
