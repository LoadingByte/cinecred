package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.AlignWithAxis.*
import kotlin.math.max
import kotlin.math.min


class DrawnColumn(val defImage: DeferredImage, val axisXInImage: Float)

private typealias DrawnBlock = DrawnColumn  // Just reuse the class because the structure is the same.


fun drawColumn(
    textCtx: TextContext,
    column: Column,
    alignBodyColsGroupIds: Map<Block, Int>,
    alignHeadTailGroupIds: Map<Block, Int>
): DrawnColumn {
    // This list will be filled shortly. We generate an image for each block's body.
    val drawnBodies = mutableMapOf<Block, DrawnBody>()

    // Step 1:
    // Take the blocks whose bodies are laid out using the "grid body layout". Group blocks that share the same
    // content style and user-defined "body columns alignment group". The "body columns" will be aligned between
    // blocks from the same group.
    val blockGroupsWithGridBodyLayout = column.blocks
        .filter { block -> block.style.bodyLayout == BodyLayout.GRID }
        .groupBy { block -> Pair(block.style, alignBodyColsGroupIds[block]) }
        .values
    // Generate images for blocks whose bodies are laid out using the "grid body layout".
    for (blockGroup in blockGroupsWithGridBodyLayout) {
        // Generate an image for the body of each block in the group. The bodies are laid out together such that,
        // for example, a "left" justification means "left" w.r.t. to the column spanned up by the widest body from
        // the block group. As a consequence, all these images also share the same width.
        drawnBodies.putAll(drawBodyImagesWithGridBodyLayout(textCtx, blockGroup))
    }

    // Step 2:
    // Generate images for blocks whose bodies are laid out using the "flow body layout" or "paragraphs body layout".
    for (block in column.blocks)
        if (block.style.bodyLayout == BodyLayout.FLOW)
            drawnBodies[block] = drawBodyImageWithFlowBodyLayout(textCtx, block)
        else if (block.style.bodyLayout == BodyLayout.PARAGRAPHS)
            drawnBodies[block] = drawBodyImageWithParagraphsBodyLayout(textCtx, block)

    // We now add heads and tails to the body images and thereby generate an image for each block.
    // We also remember the x coordinate of the axis inside each generated image.
    val drawnBlocks = mutableMapOf<Block, DrawnBlock>()

    // Step 3:
    // We start with the blocks that have a horizontal spine. Here, heads/tails that either share a common edge
    // position or are part of blocks which are centered on the head/tail are combined into shared "head/tail columns".
    // Such a "column" is as wide as the largest head/tail it contains. This, for example, allows the user to justify
    // all heads "left" in a meaningful way.
    // First, partition the horizontal spine blocks into two partitions that will be processed separately.
    val (horSpineBlocks1, horSpineBlocks2) = column.blocks
        .filter { block -> block.style.spineOrientation == SpineOrientation.HORIZONTAL }
        .partition { block ->
            val c = block.style.alignWithAxis
            !(c == HEAD_GAP_CENTER || c == BODY_LEFT || c == BODY_RIGHT || c == TAIL_GAP_CENTER)
        }
    // Divide the first partition such that only blocks whose heads or tails should be aligned are in the same group.
    val headOrTailAlignBlockGroups1 = horSpineBlocks1
        .groupBy { block ->
            when (block.style.alignWithAxis) {
                HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT, TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT ->
                    Pair(block.style.alignWithAxis, alignHeadTailGroupIds[block])
                // The heads or tails of these blocks are never aligned. As such, we use the memory address of these
                // blocks as their group keys to make sure that each of them is always sorted into a singleton group.
                OVERALL_CENTER, BODY_CENTER -> System.identityHashCode(block)
                else -> throw IllegalStateException()  // Will never happen because we partitioned beforehand.
            }
        }.values
    // Now process the second partition.
    val headOrTailAlignBlockGroups2 = horSpineBlocks2
        // Divide into "left"-centered and "right"-centered blocks. Also divide by head/tail aligning group.
        .groupBy { block ->
            val c = block.style.alignWithAxis
            Pair(c == HEAD_GAP_CENTER || c == BODY_LEFT, alignHeadTailGroupIds[block])
        }.values
        // Further subdivide such that only blocks whose heads or tails share an edge are in the same group.
        .flatMap { blockGroup ->
            blockGroup.fuzzyGroupBy { block ->
                when (block.style.alignWithAxis) {
                    HEAD_GAP_CENTER -> block.style.headGapPx / 2f
                    BODY_LEFT -> block.style.headGapPx
                    BODY_RIGHT -> block.style.tailGapPx
                    TAIL_GAP_CENTER -> block.style.tailGapPx / 2f
                    else -> throw IllegalStateException()  // Will never happen because we partitioned beforehand.
                }
            }
        }
    // Finally, generate block images for all horizontal blocks. The images for grouped blocks are generated in unison.
    for (blockGroup in headOrTailAlignBlockGroups1 + headOrTailAlignBlockGroups2)
        drawnBlocks.putAll(drawHorizontalSpineBlocks(textCtx, blockGroup, drawnBodies))

    // Step 4: Now generate block images for the blocks which have a vertical spine.
    for (block in column.blocks)
        if (block.style.spineOrientation == SpineOrientation.VERTICAL)
            drawnBlocks[block] = drawVerticalSpineBlock(textCtx, block, drawnBodies.getValue(block))

    // Step 5:
    // Combine the block images for the blocks inside the column to a column image.
    val columnImage = DeferredImage()
    val axisXInColumnImage = column.blocks.maxOf { block -> drawnBlocks.getValue(block).axisXInImage }
    var y = 0f
    for (block in column.blocks) {
        y += block.style.vMarginPx
        val drawnBlock = drawnBlocks.getValue(block)
        val x = axisXInColumnImage - drawnBlock.axisXInImage
        columnImage.drawDeferredImage(drawnBlock.defImage, x, y)
        y += drawnBlock.defImage.height + block.style.vMarginPx + block.vGapAfterPx
    }
    // Draw a guide that shows the column's axis.
    columnImage.drawLine(AXIS_GUIDE_COLOR, axisXInColumnImage, 0f, axisXInColumnImage, y, layer = GUIDES)
    // Ensure that the column image's height also entails the last block's vMarginPx.
    columnImage.height = y

    return DrawnColumn(columnImage, axisXInColumnImage)
}


private const val EPS = 0.01f

// We cannot use regular groupBy for floats as floating point inaccuracy might cause them to differ ever so little
// even though logically, they are the same.
private inline fun <E> List<E>.fuzzyGroupBy(keySelector: (E) -> Float): List<List<E>> {
    val groups = mutableListOf<List<E>>()

    val ungrouped = toMutableList()
    while (ungrouped.isNotEmpty()) {
        val startElem = ungrouped.removeAt(0)
        val startElemKey = keySelector(startElem)
        var lower = startElemKey - EPS
        var upper = startElemKey + EPS

        val group = mutableListOf(startElem)
        var groupHasGrown = true
        while (groupHasGrown) {
            groupHasGrown = false
            val iter = ungrouped.iterator()
            while (iter.hasNext()) {
                val elem = iter.next()
                val elemKey = keySelector(elem)
                if (elemKey in lower..upper) {
                    group.add(elem)
                    iter.remove()
                    lower = min(lower, elemKey - EPS)
                    upper = max(upper, elemKey + EPS)
                    groupHasGrown = true
                }
            }
        }

        groups.add(group)
    }

    return groups
}


private fun drawHorizontalSpineBlocks(
    textCtx: TextContext,
    blocks: List<Block>,
    drawnBodies: Map<Block, DrawnBody>,
): Map<Block, DrawnBlock> {
    // This will be the return value.
    val drawnBlocks = mutableMapOf<Block, DrawnBlock>()

    // Step 1:
    // In the drawColumnImage() function, the blocks have been grouped such that in this function, either the heads or
    // tails or nothing should be contained in a merged single-width "column". For this, depending on what should be
    // aligned with the axis, we first determine the width of the head sub-column or the tail sub-column or nothing by
    // taking the maximum width of all head/tail strings coming from all blocks from the block group.
    val headSharedWidth = when (blocks[0].style.alignWithAxis) {
        HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT, HEAD_GAP_CENTER, BODY_LEFT ->
            blocks.maxOf { block ->
                if (block.style.spineOrientation == SpineOrientation.VERTICAL || block.head == null) 0f
                else block.head.getWidth(textCtx)
            }
        else -> null
    }
    val tailSharedWidth = when (blocks[0].style.alignWithAxis) {
        BODY_RIGHT, TAIL_GAP_CENTER, TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT ->
            blocks.maxOf { block ->
                if (block.style.spineOrientation == SpineOrientation.VERTICAL || block.tail == null) 0f
                else block.tail.getWidth(textCtx)
            }
        else -> null
    }

    // Step 2:
    // Draw a deferred image for each block.
    for (block in blocks) {
        val drawnBody = drawnBodies.getValue(block)
        val bodyImage = drawnBody.defImage

        val headWidth = headSharedWidth ?: block.head?.getWidth(textCtx) ?: 0f
        val tailWidth = tailSharedWidth ?: block.tail?.getWidth(textCtx) ?: 0f

        val headStartX = 0f
        val headEndX = headStartX + headWidth
        val bodyStartX = headEndX + (if (!block.style.hasHead) 0f else block.style.headGapPx)
        val bodyEndX = bodyStartX + bodyImage.width
        val tailStartX = bodyEndX + (if (!block.style.hasHead) 0f else block.style.tailGapPx)
        val tailEndX = tailStartX + tailWidth

        // Used later on for vertically justifying the head and tail.
        fun getReferenceHeight(vJustify: VJustify) =
            when (vJustify) {
                VJustify.TOP -> drawnBody.firstRowHeight
                VJustify.MIDDLE -> null
                VJustify.BOTTOM -> drawnBody.lastRowHeight
            }

        // Draw the block image.
        val blockImage = DeferredImage()
        var y = 0f
        // Draw the block's head.
        if (block.head != null) {
            blockImage.drawJustifiedStyledString(
                textCtx, block.head, block.style.headHJustify, block.style.headVJustify,
                0f, y, headWidth, bodyImage.height, getReferenceHeight(block.style.headVJustify)
            )
            // Draw a guide that shows the edges of the head space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, 0f, y, headWidth, bodyImage.height, layer = GUIDES)
        }
        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, y)
        if (block.style.spineOrientation == SpineOrientation.VERTICAL)
            y += bodyImage.height
        // Draw the block's tail.
        if (block.tail != null) {
            blockImage.drawJustifiedStyledString(
                textCtx, block.tail, block.style.tailHJustify, block.style.tailVJustify,
                tailStartX, y, tailWidth, bodyImage.height, getReferenceHeight(block.style.tailVJustify)
            )
            // Draw a guide that shows the edges of the tail space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, tailStartX, y, tailWidth, bodyImage.height, layer = GUIDES)
        }

        // Find the x coordinate of the axis in the generated image for the current block.
        val axisXInImage = when (block.style.alignWithAxis) {
            OVERALL_CENTER -> (headStartX + tailEndX) / 2f
            HEAD_LEFT -> headStartX
            HEAD_CENTER -> (headStartX + headEndX) / 2f
            HEAD_RIGHT -> headEndX
            HEAD_GAP_CENTER -> (headEndX + bodyStartX) / 2f
            BODY_LEFT -> bodyStartX
            BODY_CENTER -> (bodyStartX + bodyEndX) / 2f
            BODY_RIGHT -> bodyEndX
            TAIL_GAP_CENTER -> (bodyEndX + tailStartX) / 2f
            TAIL_LEFT -> tailStartX
            TAIL_CENTER -> (tailStartX + tailEndX) / 2f
            TAIL_RIGHT -> tailEndX
        }

        drawnBlocks[block] = DrawnBlock(blockImage, axisXInImage)
    }

    return drawnBlocks
}


private fun drawVerticalSpineBlock(
    textCtx: TextContext,
    block: Block,
    drawnBody: DrawnBody
): DrawnBlock {
    val bodyImage = drawnBody.defImage

    // Draw the body image.
    val blockImage = DeferredImage()
    var y = 0f
    // Draw the block's head.
    if (block.head != null) {
        blockImage.drawJustifiedStyledString(
            textCtx, block.head, block.style.headHJustify, 0f, y, bodyImage.width
        )
        // Draw guides that show the edges of the head space.
        val y2 = blockImage.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0f, 0f, 0f, y2, layer = GUIDES)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, bodyImage.width, 0f, bodyImage.width, y2, layer = GUIDES)
        // Advance to the body.
        y += block.head.getHeight() + block.style.headGapPx
    }
    // Draw the block's body.
    blockImage.drawDeferredImage(bodyImage, 0f, y)
    y += bodyImage.height
    // Draw the block's tail.
    if (block.tail != null) {
        y += block.style.tailGapPx
        blockImage.drawJustifiedStyledString(
            textCtx, block.tail, block.style.tailHJustify, 0f, y, bodyImage.width
        )
        // Draw guides that show the edges of the tail space.
        val y2 = blockImage.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0f, y, 0f, y2, layer = GUIDES)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, bodyImage.width, y, bodyImage.width, y2, layer = GUIDES)
    }

    // Find the x coordinate of the axis in the generated image for the current block.
    val axisXInImage = when (block.style.alignWithAxis) {
        HEAD_LEFT, BODY_LEFT, TAIL_LEFT -> 0f
        OVERALL_CENTER, HEAD_CENTER, HEAD_GAP_CENTER, BODY_CENTER, TAIL_GAP_CENTER, TAIL_CENTER -> bodyImage.width / 2f
        HEAD_RIGHT, BODY_RIGHT, TAIL_RIGHT -> bodyImage.width
    }

    return DrawnBlock(blockImage, axisXInImage)
}
