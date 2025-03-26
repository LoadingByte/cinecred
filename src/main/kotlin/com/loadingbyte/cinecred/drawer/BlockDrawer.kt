package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.FormattedString
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BlockOrientation.HORIZONTAL
import com.loadingbyte.cinecred.project.BlockOrientation.VERTICAL
import com.loadingbyte.cinecred.project.HarmonizeExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.SpineAttachment.*
import kotlin.math.floor


class DrawnBlock(val defImage: DeferredImage, val spineXInImage: Double)


fun drawBlocks(
    styling: Styling,
    drawnBodies: Map<Block, DrawnBody>,
    blocks: List<Block>
): Map<Block, DrawnBlock> {
    val drawnBlocks = HashMap<Block, DrawnBlock>(2 * blocks.size)

    // Draw blocks which have horizontal orientation.
    drawHorizontalBlocks(
        drawnBlocks, styling, drawnBodies,
        blocks.filter { block -> block.style.blockOrientation == HORIZONTAL }
    )

    // Draw blocks which have vertical orientation.
    for (block in blocks)
        if (block.style.blockOrientation == VERTICAL)
            drawnBlocks[block] = drawVerticalBlock(styling, block, drawnBodies.getValue(block))

    return drawnBlocks
}


private fun drawHorizontalBlocks(
    out: MutableMap<Block, DrawnBlock>,
    styling: Styling,
    drawnBodies: Map<Block, DrawnBody>,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks which have horizontal orientation.
    require(blocks.all { it.style.blockOrientation == HORIZONTAL })

    // Horizontal blocks are free to potentially harmonize their head and tail width. This, for example, could allow the
    // user to justify all heads "left" in a meaningful way. For both widths that can be harmonized (i.e., head and tail
    // width), find which styles should harmonize together.
    val harmonizeHeadWidthPartitionIds = styling.contentStyles
        .filter { blockOrientation == HORIZONTAL && !headForceWidthPx.isActive && headHarmonizeWidth == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::headHarmonizeWidthAcrossStyles)
    val harmonizeTailWidthPartitionIds = styling.contentStyles
        .filter { blockOrientation == HORIZONTAL && !tailForceWidthPx.isActive && tailHarmonizeWidth == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::tailHarmonizeWidthAcrossStyles)

    // Determine the groups of blocks which should share the same head/tail width, and of course also find those widths.
    val sharedHeadWidths = harmonizeWidth(
        blocks, harmonizeHeadWidthPartitionIds, Block::harmonizeHeadPartitionId,
        sharedGroupWidth = { group ->
            group.maxOf { block -> if (block.head == null) 0.0 else block.head.first().formatted(styling).width }
        })
    val sharedTailWidths = harmonizeWidth(
        blocks, harmonizeTailWidthPartitionIds, Block::harmonizeTailPartitionId,
        sharedGroupWidth = { group ->
            group.maxOf { block -> if (block.tail == null) 0.0 else block.tail.first().formatted(styling).width }
        })

    // Draw a deferred image for each block.
    blocks.associateWithTo(out) { block ->
        val style = block.style
        val drawnBody = drawnBodies.getValue(block)
        val bodyImage = drawnBody.defImage

        val headWidth = resolveWidth(styling, block, style.headForceWidthPx, sharedHeadWidths, block.head)
        val tailWidth = resolveWidth(styling, block, style.tailForceWidthPx, sharedTailWidths, block.tail)

        val headStartX = 0.0
        val headEndX = headStartX + headWidth
        val bodyStartX = headEndX + (if (!style.hasHead) 0.0 else style.headGapPx)
        val bodyEndX = bodyStartX + bodyImage.width
        val tailStartX = bodyEndX + (if (!style.hasHead) 0.0 else style.tailGapPx)
        val tailEndX = tailStartX + tailWidth

        // Draw the block image.
        val blockImageHeight = bodyImage.height
        val blockImage = DeferredImage(width = tailEndX - headStartX, height = blockImageHeight)

        fun yBaselineForAppendage(fmtStr: FormattedString, vShelve: AppendageVShelve, vJustify: AppendageVJustify): Y =
            when (vShelve) {
                AppendageVShelve.FIRST ->
                    drawnBody.lines.first().yBaselineForAppendage(vJustify, fmtStr).toY()
                AppendageVShelve.LAST ->
                    drawnBody.lines.last().yBaselineForAppendage(vJustify, fmtStr).toY() +
                            blockImageHeight - drawnBody.lines.last().height
                AppendageVShelve.OVERALL_MIDDLE ->
                    (blockImageHeight - fmtStr.height) / 2.0 + fmtStr.heightAboveBaseline
            }

        fun drawHeadTail(
            strLines: List<StyledString>, hJustify: HJustify, vShelve: AppendageVShelve, vJustify: AppendageVJustify,
            areaX: Double, areaWidth: Double
        ): DoubleArray {
            val fmtStr = strLines.first().formatted(styling)
            val x = areaX + justify(hJustify, areaWidth, fmtStr.width)
            val yBaseline = yBaselineForAppendage(fmtStr, vShelve, vJustify)
            blockImage.drawString(fmtStr, x, yBaseline)
            // Draw a guide that shows the edges of the head/tail space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, areaX, 0.0.toY(), areaWidth, blockImageHeight, layer = GUIDES)
            // Draw an additional guide that shows the edge of the vertical head/tail alignment space.
            val extraGuideY = when (vShelve) {
                AppendageVShelve.FIRST -> drawnBody.lines.first().height.toY()
                AppendageVShelve.LAST -> blockImageHeight - drawnBody.lines.last().height
                AppendageVShelve.OVERALL_MIDDLE -> null
            }
            if (extraGuideY != null && drawnBody.lines.size != 1)
                blockImage.drawLine(
                    HEAD_TAIL_GUIDE_COLOR, areaX, extraGuideY, areaX + areaWidth, extraGuideY,
                    dash = true, layer = GUIDES
                )
            return doubleArrayOf(x, x + fmtStr.width)
        }

        fun drawLeader(
            str: String, letterStyleName: String,
            hJustify: SingleLineHJustify, vShelve: AppendageVShelve, vJustify: AppendageVJustify,
            leftMargin: Double, rightMargin: Double, spacing: Double,
            leftX: Double, rightX: Double
        ) {
            val letterStyle = styling.letterStyles.find { it.name == letterStyleName } ?: PLACEHOLDER_LETTER_STYLE
            val fmtStr = format(str, letterStyle, styling)
            val areaWidth = (rightX - leftX) - (leftMargin + rightMargin)
            val count = floor((areaWidth + spacing) / (fmtStr.width + spacing)).toInt()
            if (count <= 0)
                return
            var x = leftX + leftMargin
            val advance: Double
            if (hJustify == SingleLineHJustify.FULL)
                advance = (areaWidth - fmtStr.width) / (count - 1)
            else {
                x += justify(hJustify.toHJustify(), areaWidth, count * fmtStr.width + (count - 1) * spacing)
                advance = fmtStr.width + spacing
            }
            val yBaseline = yBaselineForAppendage(fmtStr, vShelve, vJustify)
            repeat(count) {
                blockImage.drawString(fmtStr, x, yBaseline)
                x += advance
            }
        }

        // Draw the block's head.
        if (block.head != null) {
            val (_, headContentEndX) = drawHeadTail(
                block.head, style.headHJustify, style.headVShelve, style.headVJustify, headStartX, headWidth
            )
            // Draw the block's head leader.
            if (style.headLeader.isNotBlank()) {
                val bodyContentStartX = bodyStartX + when (style.headVShelve) {
                    AppendageVShelve.FIRST -> drawnBody.lines.first().x
                    AppendageVShelve.LAST -> drawnBody.lines.last().x
                    AppendageVShelve.OVERALL_MIDDLE -> drawnBody.lines.minOf { line -> line.x }
                }
                drawLeader(
                    style.headLeader, style.headLeaderLetterStyleName.orElse { style.headLetterStyleName },
                    style.headLeaderHJustify, style.headVShelve, style.headLeaderVJustify,
                    style.headLeaderMarginLeftPx, style.headLeaderMarginRightPx, style.headLeaderSpacingPx,
                    headContentEndX, bodyContentStartX
                )
            }
        }

        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, 0.0.toY())

        // Draw the block's tail.
        if (block.tail != null) {
            val (tailContentStartX, _) = drawHeadTail(
                block.tail, style.tailHJustify, style.tailVShelve, style.tailVJustify, tailStartX, tailWidth
            )
            // Draw the block's tail leader.
            if (style.tailLeader.isNotBlank()) {
                val bodyContentEndX = bodyStartX + when (style.tailVShelve) {
                    AppendageVShelve.FIRST -> drawnBody.lines.first().let { line -> line.x + line.width }
                    AppendageVShelve.LAST -> drawnBody.lines.last().let { line -> line.x + line.width }
                    AppendageVShelve.OVERALL_MIDDLE -> drawnBody.lines.maxOf { line -> line.x + line.width }
                }
                drawLeader(
                    style.tailLeader, style.tailLeaderLetterStyleName.orElse { style.tailLetterStyleName },
                    style.tailLeaderHJustify, style.tailVShelve, style.tailLeaderVJustify,
                    style.tailLeaderMarginLeftPx, style.tailLeaderMarginRightPx, style.tailLeaderSpacingPx,
                    bodyContentEndX, tailContentStartX
                )
            }
        }

        // Find the x coordinate of the spine in the generated image for the current block.
        val spineXInImage = when (style.spineAttachment) {
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


private inline fun harmonizeWidth(
    blocks: List<Block>,
    styleHarmonizationPartitionIds: Map<ContentStyle, PartitionId>,
    blockHarmonizationPartitionId: (Block) -> PartitionId,
    sharedGroupWidth: (List<Block>) -> Double
): Map<Block, Double> {
    val grouper = HashMap<Any, MutableList<Block>>()
    for (block in blocks) {
        // If the block's style is in some partition (!= null), harmonizing the head/tail width across blocks is enabled
        // for that style. Hence, group the block according to both (a) the style's partition and (b) the global
        // head/tail harmonization partition which arises from the "@Break Harmonization" column in the credits table.
        val key = Pair(styleHarmonizationPartitionIds[block.style] ?: continue, blockHarmonizationPartitionId(block))
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


private fun resolveWidth(
    styling: Styling,
    block: Block,
    force: Opt<Double>,
    shared: Map<Block, Double>,
    strLines: List<StyledString>?
): Double {
    if (force.isActive)
        return force.value
    shared[block]?.let { return it }
    if (strLines != null)
        return strLines.first().formatted(styling).width
    return 0.0
}


private fun drawVerticalBlock(
    styling: Styling,
    block: Block,
    drawnBody: DrawnBody
): DrawnBlock {
    // In this function, we only concern ourselves with blocks which have vertical orientation.
    require(block.style.blockOrientation == VERTICAL)

    val bodyImage = drawnBody.defImage

    // Draw the block image.
    val blockImageWidth = bodyImage.width
    val blockImage = DeferredImage(blockImageWidth)
    var y = 0.0.toY()

    fun drawHeadTailLines(strLines: List<StyledString>, hJustify: HJustify) {
        for (str in strLines) {
            val fmtStr = str.formatted(styling)
            val lineH = fmtStr.height
            val yBaseline = y + fmtStr.heightAboveBaseline
            blockImage.drawString(fmtStr, justify(hJustify, blockImageWidth, fmtStr.width), yBaseline)
            // Draw guides that show the edges of the head/tail space.
            blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0.0, y, 0.0, y + lineH, layer = GUIDES)
            blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, blockImageWidth, y, blockImageWidth, y + lineH, layer = GUIDES)
            // Advance to the next line.
            y += lineH
        }
    }

    // Draw the block's head.
    if (block.head != null) {
        drawHeadTailLines(block.head, block.style.headHJustify)
        y += block.style.headGapPx.toElasticY()
    }
    // Draw the block's body.
    blockImage.drawDeferredImage(bodyImage, 0.0, y)
    y += bodyImage.height
    // Draw the block's tail.
    if (block.tail != null) {
        y += block.style.tailGapPx.toElasticY()
        drawHeadTailLines(block.tail, block.style.tailHJustify)
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
