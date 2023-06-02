package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D


class DrawnStage(val defImage: DeferredImage, val middleYInImage: Y? /* for cards only */)
private class DrawnSpine(val defImage: DeferredImage, val spineXInImage: Double)
private class CardSpineInfo(val drawnSpine: DrawnSpine, val x: Double, val y: Y) {
    val drawVAnchors = HashSet<VAnchor>()
}


fun drawCardStage(
    resolution: Resolution,
    drawnBlocks: Map<Block, DrawnBlock>,
    stage: Stage.Card
): DrawnStage {
    val w = resolution.widthPx.toDouble()
    val s = resolution.widthPx / 200.0

    val compoundImages = LinkedHashMap<Compound, DeferredImage>()  // retains insertion order
    for (compound in stage.compounds) {
        val compoundX = w / 2.0 + compound.hOffsetPx

        val spineInfo = LinkedHashMap<Spine.Card, CardSpineInfo>()  // retains insertion order
        for (spine in compound.spines) {
            val drawnSpine = drawSpine(drawnBlocks, spine)
            val x: Double
            val y: Y
            if (spine.hookTo == null) {
                x = compoundX
                y = 0.0.toY()
            } else {
                val hookToInfo = spineInfo.getValue(spine.hookTo)
                x = hookToInfo.x + spine.hOffsetPx
                y = hookToInfo.y + spine.hookVAnchor.yIn(hookToInfo.drawnSpine.defImage.height) +
                        spine.vOffsetPx - spine.selfVAnchor.yIn(drawnSpine.defImage.height)
                hookToInfo.drawVAnchors.add(spine.hookVAnchor)
            }
            spineInfo[spine] = CardSpineInfo(drawnSpine, x, y)
        }

        val compoundImage = DeferredImage(width = w)
        drawWithNegativeYs(
            compoundImage, spineInfo.entries,
            y = { (_, info) -> info.y },
            h = { (_, info) -> info.drawnSpine.defImage.height },
            draw = { (spine, info), imgY ->
                compoundImage.drawDeferredImage(info.drawnSpine.defImage, info.x - info.drawnSpine.spineXInImage, imgY)
                // Draw guides that shows where hooks towards other spines are starting out.
                val spineHeight = info.drawnSpine.defImage.height
                for (anchor in info.drawVAnchors)
                    compoundImage.drawShape(
                        SPINE_GUIDE_COLOR,
                        Path2D.Double().apply { moveTo(-s, -s); lineTo(s, s); moveTo(-s, s); lineTo(s, -s) },
                        x = info.x, y = imgY + anchor.yIn(spineHeight), fill = false, layer = GUIDES
                    )
                // If this is a hooked spine, draw guides that show the hook's target and line.
                if (spine.hookTo != null) {
                    val startY = imgY + spine.selfVAnchor.yIn(spineHeight)
                    for (r in doubleArrayOf(0.5 * s, s))
                        compoundImage.drawShape(
                            SPINE_GUIDE_COLOR, Ellipse2D.Double(-r, -r, 2.0 * r, 2.0 * r),
                            x = info.x, y = startY, fill = false, layer = GUIDES
                        )
                    compoundImage.drawLine(
                        SPINE_GUIDE_COLOR, info.x, startY, info.x - spine.hOffsetPx, startY - spine.vOffsetPx,
                        dash = true, layer = GUIDES
                    )
                }
            }
        )

        // Draw a guide that shows where the entire compound is anchored.
        val diamond = when (compound.vAnchor) {
            VAnchor.TOP ->
                Path2D.Double().apply { moveTo(-s, 0.0); lineTo(s, 0.0); lineTo(0.0, 2.0 * s); closePath() }
            VAnchor.BOTTOM ->
                Path2D.Double().apply { moveTo(-s, 0.0); lineTo(s, 0.0); lineTo(0.0, -2.0 * s); closePath() }
            VAnchor.MIDDLE ->
                Path2D.Double().apply { moveTo(-s, 0.0); lineTo(0.0, -s); lineTo(s, 0.0); lineTo(0.0, s); closePath() }
        }
        compoundImage.drawShape(
            SPINE_GUIDE_COLOR, diamond, compoundX, compound.vAnchor.yIn(compoundImage.height + 0.4 * s) - 0.2 * s,
            fill = true, layer = GUIDES
        )

        compoundImages[compound] = compoundImage
    }

    val stageImage = DeferredImage(width = w)
    val middleYInStageImage = drawWithNegativeYs(
        stageImage, compoundImages.entries,
        y = { (compound, compoundImage) -> compound.vOffsetPx.toY() - compound.vAnchor.yIn(compoundImage.height) },
        h = { (_, compoundImage) -> compoundImage.height },
        draw = { (_, compoundImage), imgY -> stageImage.drawDeferredImage(compoundImage, 0.0, imgY) }
    )

    return DrawnStage(stageImage, middleYInStageImage)
}

private fun VAnchor.yIn(h: Y) = when (this) {
    VAnchor.TOP -> 0.0.toY()
    VAnchor.MIDDLE -> h / 2.0
    VAnchor.BOTTOM -> h
}

private inline fun <T> drawWithNegativeYs(
    image: DeferredImage, elems: Collection<T>, y: (T) -> Y, h: (T) -> Y, draw: (T, Y) -> Unit
): Y {
    var originY = 0.0.toY()
    for (elem in elems)
        originY = originY.max(-y(elem))
    for (elem in elems) {
        val imgY = originY + y(elem)
        draw(elem, imgY)
        image.height = image.height.max(imgY + h(elem))
    }
    return originY
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

    val lateralsImage = DeferredImage(width = resolution.widthPx.toDouble())
    var y = topPad ?: 0.0.toY()
    for ((lateralIdx, lateral) in stage.laterals.withIndex()) {
        var maxHeight = 0.0.toY()
        for (spine in lateral.spines) {
            val drawnSpine = drawSpine(drawnBlocks, spine)
            val x = resolution.widthPx / 2.0 + spine.hOffsetPx - drawnSpine.spineXInImage
            lateralsImage.drawDeferredImage(drawnSpine.defImage, x, y)
            maxHeight = maxHeight.max(drawnSpine.defImage.height)
        }
        y += maxHeight
        if (lateralIdx != stage.laterals.lastIndex)
            y += lateral.vGapAfterPx.toElasticY()
    }
    if (botPad != null)
        y += botPad
    lateralsImage.height = y

    return DrawnStage(lateralsImage, null)
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
