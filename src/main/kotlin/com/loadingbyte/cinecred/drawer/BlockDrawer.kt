package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.plus
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.HeadTailVJustify.*
import com.loadingbyte.cinecred.project.MatchExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.SpineAttachment.*


class DrawnBlock(val defImage: DeferredImage, val spineXInImage: Double)


fun drawBlocks(
    contentStyles: List<ContentStyle>,
    textCtx: TextContext,
    drawnBodies: Map<Block, DrawnBody>,
    blocks: List<Block>
): Map<Block, DrawnBlock> {
    val drawnBlocks = HashMap<Block, DrawnBlock>(2 * blocks.size)

    // Draw blocks which have horizontal orientation.
    drawHorizontalBlocks(
        drawnBlocks, contentStyles, textCtx, drawnBodies,
        blocks.filter { block -> block.style.blockOrientation == HORIZONTAL }
    )

    // Draw blocks which have vertical orientation.
    for (block in blocks)
        if (block.style.blockOrientation == VERTICAL)
            drawnBlocks[block] = drawVerticalBlock(textCtx, block, drawnBodies.getValue(block))

    return drawnBlocks
}


private fun drawHorizontalBlocks(
    out: MutableMap<Block, DrawnBlock>,
    cs: List<ContentStyle>,
    textCtx: TextContext,
    drawnBodies: Map<Block, DrawnBody>,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks which have horizontal orientation.
    require(blocks.all { it.style.blockOrientation == HORIZONTAL })

    // Horizontal blocks are free to potentially harmonize their head and tail width. This, for example, could allow the
    // user to justify all heads "left" in a meaningful way. For both widths that can be harmonized (i.e., head and tail
    // width), find which styles should harmonize together.
    val matchHeadWidthPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::headMatchWidthAcrossStyles) {
        blockOrientation == HORIZONTAL && headMatchWidth == ACROSS_BLOCKS
    }
    val matchTailWidthPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::tailMatchWidthAcrossStyles) {
        blockOrientation == HORIZONTAL && tailMatchWidth == ACROSS_BLOCKS
    }

    // Determine the groups of blocks which should share the same head/tail width, and of course also find those widths.
    val sharedHeadWidths = matchWidth(blocks, matchHeadWidthPartitionIds, Block::matchHeadPartitionId) { group ->
        group.maxOf { block -> if (block.head == null) 0.0 else block.head.formatted(textCtx).width }
    }
    val sharedTailWidths = matchWidth(blocks, matchTailWidthPartitionIds, Block::matchTailPartitionId) { group ->
        group.maxOf { block -> if (block.tail == null) 0.0 else block.tail.formatted(textCtx).width }
    }

    // Draw a deferred image for each block.
    blocks.associateWithTo(out) { block ->
        val drawnBody = drawnBodies.getValue(block)
        val bodyImage = drawnBody.defImage

        val headWidth = sharedHeadWidths[block] ?: block.head?.run { formatted(textCtx).width } ?: 0.0
        val tailWidth = sharedTailWidths[block] ?: block.tail?.run { formatted(textCtx).width } ?: 0.0

        val headStartX = 0.0
        val headEndX = headStartX + headWidth
        val bodyStartX = headEndX + (if (!block.style.hasHead) 0.0 else block.style.headGapPx)
        val bodyEndX = bodyStartX + bodyImage.width
        val tailStartX = bodyEndX + (if (!block.style.hasHead) 0.0 else block.style.tailGapPx)
        val tailEndX = tailStartX + tailWidth

        // Draw the block image.
        val blockImageHeight = bodyImage.height
        val blockImage = DeferredImage(width = tailEndX - headStartX, height = blockImageHeight)

        fun drawHeadTail(str: StyledString, hJustify: HJustify, vJustify: HeadTailVJustify, x: Double, width: Double) {
            val areaY: Y
            val areaHeight: Y
            val extraGuideY: Y?
            when (vJustify) {
                FIRST_TOP, FIRST_MIDDLE, FIRST_BOTTOM -> {
                    areaY = 0.0.toY()
                    areaHeight = drawnBody.firstRowHeight.toY()
                    extraGuideY = areaHeight
                }
                OVERALL_MIDDLE -> {
                    areaY = 0.0.toY()
                    areaHeight = blockImageHeight
                    extraGuideY = null
                }
                LAST_TOP, LAST_MIDDLE, LAST_BOTTOM -> {
                    areaY = blockImageHeight - drawnBody.lastRowHeight.toY()
                    areaHeight = drawnBody.lastRowHeight.toY()
                    extraGuideY = areaY
                }
            }
            val reducedVJustify = when (vJustify) {
                FIRST_TOP, LAST_TOP -> VJustify.TOP
                FIRST_MIDDLE, OVERALL_MIDDLE, LAST_MIDDLE -> VJustify.MIDDLE
                FIRST_BOTTOM, LAST_BOTTOM -> VJustify.BOTTOM
            }
            val fmtStr = str.formatted(textCtx)
            blockImage.drawJustifiedString(fmtStr, hJustify, reducedVJustify, x, areaY, width, areaHeight)
            // Draw a guide that shows the edges of the head/tail space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, x, 0.0.toY(), width, blockImageHeight, layer = GUIDES)
            // Draw an additional guide that shows the edge of the vertical head alignment space.
            if (extraGuideY != null && drawnBody.numRows != 1)
                blockImage.drawLine(
                    HEAD_TAIL_GUIDE_COLOR, x, extraGuideY, x + headWidth, extraGuideY, dash = true, layer = GUIDES
                )
        }

        // Draw the block's head.
        if (block.head != null)
            drawHeadTail(block.head, block.style.headHJustify, block.style.headVJustify, headStartX, headWidth)
        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, 0.0.toY())
        if (block.tail != null)
            drawHeadTail(block.tail, block.style.tailHJustify, block.style.tailVJustify, tailStartX, tailWidth)

        // Find the x coordinate of the spine in the generated image for the current block.
        val spineXInImage = when (block.style.spineAttachment) {
            OVERALL_CENTER -> (headStartX + tailEndX) / 2.0
            HEAD_LEFT -> headStartX
            HEAD_CENTER -> (headStartX + headEndX) / 2.0
            HEAD_RIGHT -> headEndX
            HEAD_GAP_CENTER -> (headEndX + bodyStartX) / 2.0
            BODY_LEFT -> bodyStartX
            BODY_CENTER -> (bodyStartX + bodyEndX) / 2.0
            BODY_RIGHT -> bodyEndX
            TAIL_GAP_CENTER -> (bodyEndX + tailStartX) / 2.0
            TAIL_LEFT -> tailStartX
            TAIL_CENTER -> (tailStartX + tailEndX) / 2.0
            TAIL_RIGHT -> tailEndX
        }

        DrawnBlock(blockImage, spineXInImage)
    }
}


private inline fun matchWidth(
    blocks: List<Block>,
    styleMatchPartitionIds: Map<ContentStyle, PartitionId>,
    blockMatchPartitionId: (Block) -> PartitionId,
    sharedGroupWidth: (List<Block>) -> Double
): Map<Block, Double> {
    val grouper = HashMap<Any, MutableList<Block>>()
    for (block in blocks) {
        // If the block's style is in some partition (!= null), matching the head/tail width across blocks is enabled
        // for that style. Hence, group the block according to both (a) the style's partition and (b) the global
        // head/tail match partition which arises from the "@Break Match" column in the credits table.
        val key = Pair(styleMatchPartitionIds[block.style] ?: continue, blockMatchPartitionId(block))
        grouper.computeIfAbsent(key) { ArrayList() }.add(block)
    }
    // Now that the grouping is done, record the shared extent of each group.
    val groupWidths = HashMap<Block, Double>(2 * blocks.size)
    for (group in grouper.values) {
        val width = sharedGroupWidth(group)
        group.associateWithTo(groupWidths) { width }
    }
    return groupWidths
}


private fun drawVerticalBlock(
    textCtx: TextContext,
    block: Block,
    drawnBody: DrawnBody
): DrawnBlock {
    // In this function, we only concern ourselves with blocks which have vertical orientation.
    require(block.style.blockOrientation == VERTICAL)

    val bodyImage = drawnBody.defImage

    // Draw the body image.
    val blockImageWidth = bodyImage.width
    val blockImage = DeferredImage(blockImageWidth)
    var y = 0.0.toY()
    // Draw the block's head.
    if (block.head != null) {
        blockImage.drawJustifiedString(block.head.formatted(textCtx), block.style.headHJustify, 0.0, y, blockImageWidth)
        // Draw guides that show the edges of the head space.
        val headHeight = block.head.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0.0, y, 0.0, y + headHeight, layer = GUIDES)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, blockImageWidth, y, blockImageWidth, y + headHeight, layer = GUIDES)
        // Advance to the body.
        y += headHeight + block.style.headGapPx.toElasticY()
    }
    // Draw the block's body.
    blockImage.drawDeferredImage(bodyImage, 0.0, y)
    y += bodyImage.height
    // Draw the block's tail.
    if (block.tail != null) {
        y += block.style.tailGapPx.toElasticY()
        blockImage.drawJustifiedString(block.tail.formatted(textCtx), block.style.tailHJustify, 0.0, y, blockImageWidth)
        // Draw guides that show the edges of the tail space.
        val tailHeight = block.tail.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0.0, y, 0.0, y + tailHeight, layer = GUIDES)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, blockImageWidth, y, blockImageWidth, y + tailHeight, layer = GUIDES)
        // Advance to below the tail.
        y += tailHeight
    }
    blockImage.height = y

    // Find the x coordinate of the spine in the generated image for the current block.
    val spineXInImage = when (block.style.spineAttachment) {
        HEAD_LEFT, BODY_LEFT, TAIL_LEFT -> 0.0
        OVERALL_CENTER, HEAD_CENTER, HEAD_GAP_CENTER, BODY_CENTER, TAIL_GAP_CENTER, TAIL_CENTER -> bodyImage.width / 2.0
        HEAD_RIGHT, BODY_RIGHT, TAIL_RIGHT -> bodyImage.width
    }

    return DrawnBlock(blockImage, spineXInImage)
}
