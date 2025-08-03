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
import com.loadingbyte.cinecred.project.VBodyFragment.*
import com.loadingbyte.cinecred.project.VTextFragment.*
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
            group.maxOf { block -> if (block.head == null) 0.0 else block.head.maxOf { it.formatted(styling).width } }
        })
    val sharedTailWidths = harmonizeWidth(
        blocks, harmonizeTailWidthPartitionIds, Block::harmonizeTailPartitionId,
        sharedGroupWidth = { group ->
            group.maxOf { block -> if (block.tail == null) 0.0 else block.tail.maxOf { it.formatted(styling).width } }
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
        val tailStartX = bodyEndX + (if (!style.hasTail) 0.0 else style.tailGapPx)
        val tailEndX = tailStartX + tailWidth

        // Draw the block image.
        val blockImageHeight = bodyImage.height
        val blockImage = DeferredImage(width = tailEndX - headStartX, height = blockImageHeight)

        fun drawHGuide(x: Double, y: Y, width: Double) {
            blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, x, y, x + width, y, dash = true, layer = GUIDES)
        }

        fun drawLeader(
            fmtStr: FormattedString,
            hJustify: HJustifyCrumbs,
            leftMargin: Double,
            rightMargin: Double,
            spacing: Double,
            leftX: Double,
            rightX: Double,
            yBaseline: Y
        ) {
            val areaWidth = (rightX - leftX) - (leftMargin + rightMargin)
            val count = floor((areaWidth + spacing) / (fmtStr.width + spacing)).toInt()
            if (count <= 0)
                return
            var x = leftX + leftMargin
            val advance: Double
            if (hJustify == HJustifyCrumbs.FULL)
                advance = (areaWidth - fmtStr.width) / (count - 1)
            else {
                x += justify(hJustify.toHJustify(), areaWidth, count * fmtStr.width + (count - 1) * spacing)
                advance = fmtStr.width + spacing
            }
            repeat(count) {
                blockImage.drawString(fmtStr, x, yBaseline)
                x += advance
            }
        }

        fun drawHeadTailLines(
            isHead: Boolean,
            strLines: List<StyledString>,
            hJustify: HJustify,
            vJustifyBodyFrag: VBodyFragment,
            vJustifyHeadTailFrag: VTextFragment,
            vJustify: VJustifyText,
            leader: String,
            leaderLetterStyleName: String,
            leaderHJustify: HJustifyCrumbs,
            leaderVJustify: VJustifyText,
            leaderLeftMargin: Double,
            leaderRightMargin: Double,
            leaderSpacing: Double,
            areaX: Double,
            areaWidth: Double
        ) {
            val fmtStrLines = strLines.map { str -> str.formatted(styling) }
            var y = when (vJustifyHeadTailFrag) {
                ALL_LINES -> drawnBody.yForHeadTail(fmtStrLines.sumOf { it.height }, vJustifyBodyFrag, vJustify, isHead)
                FIRST_LINE -> drawnBody.yForHeadTail(fmtStrLines.first(), vJustifyBodyFrag, vJustify, isHead)
                LAST_LINE -> drawnBody.yForHeadTail(fmtStrLines.last(), vJustifyBodyFrag, vJustify, isHead) -
                        fmtStrLines.subList(0, fmtStrLines.size - 1).sumOf(FormattedString::height)
            }
            val leaderLineIdx = if (leader.isBlank() || !vJustifyBodyFrag.isLine) -1 else when (vJustifyHeadTailFrag) {
                ALL_LINES -> -1
                FIRST_LINE -> 0
                LAST_LINE -> fmtStrLines.lastIndex
            }
            for ((lineIdx, fmtStr) in fmtStrLines.withIndex()) {
                val x = areaX + justify(hJustify, areaWidth, fmtStr.width)
                blockImage.drawString(fmtStr, x, y + fmtStr.heightAboveBaseline)
                if (lineIdx == leaderLineIdx) {
                    val drawnBodyLine = drawnBody.selectLine(vJustifyBodyFrag, isHead)
                    val leaderLetterStyle = styling.letterStyles.find { it.name == leaderLetterStyleName }
                        ?: PLACEHOLDER_LETTER_STYLE
                    val leaderFmtStr = format(leader, leaderLetterStyle, styling)
                    drawLeader(
                        leaderFmtStr,
                        leaderHJustify,
                        leaderLeftMargin,
                        leaderRightMargin,
                        leaderSpacing,
                        leftX = if (isHead) x + fmtStr.width else bodyStartX + drawnBodyLine.x + drawnBodyLine.width,
                        rightX = if (isHead) bodyStartX + drawnBodyLine.x else x,
                        yBaseline = drawnBodyLine.yForHeadTail(leaderFmtStr, leaderVJustify) +
                                leaderFmtStr.heightAboveBaseline
                    )
                }
                y += fmtStr.height
            }
            // Draw a guide that shows the edges of the head/tail space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, areaX, 0.0.toY(), areaWidth, blockImageHeight, layer = GUIDES)
            // Draw additional guides that show the edges of the vertical head/tail fragment justification space.
            when (vJustifyBodyFrag) {
                ALL_ROWS -> {}
                FIRST_ROW -> if (drawnBody.rows.size > 1) drawHGuide(areaX, drawnBody.rows[0].height.toY(), areaWidth)
                LAST_ROW -> if (drawnBody.rows.size > 1) drawHGuide(areaX, drawnBody.rows.last().y, areaWidth)
                else -> {
                    val drawnBodyLine = drawnBody.selectLine(vJustifyBodyFrag, isHead)
                    val y1 = drawnBodyLine.y
                    val y2 = drawnBodyLine.y + drawnBodyLine.height
                    // Note: Resolving here is no big deal because we're essentially pretending that we're working with
                    // traditional fixed, non-elastic floats for vertical coordinates, which is sufficient for
                    // determining whether y1/y2 are at the very top or bottom of the block.
                    if (y1.resolve() > 0.001) drawHGuide(areaX, y1, areaWidth)
                    if (y2.resolve() < blockImageHeight.resolve() - 0.001) drawHGuide(areaX, y2, areaWidth)
                }
            }
        }

        // Draw the block's head.
        if (block.head != null)
            drawHeadTailLines(
                isHead = true,
                block.head,
                style.headHJustify,
                style.headVJustifyBodyFragment,
                style.headVJustifyHeadFragment,
                style.headVJustify,
                style.headLeader,
                style.headLeaderLetterStyleName.orElse { style.headLetterStyleName },
                style.headLeaderHJustify,
                style.headLeaderVJustify,
                style.headLeaderMarginLeftPx,
                style.headLeaderMarginRightPx,
                style.headLeaderSpacingPx,
                headStartX,
                headWidth
            )

        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, 0.0.toY())

        // Draw the block's tail.
        if (block.tail != null)
            drawHeadTailLines(
                isHead = false,
                block.tail,
                style.tailHJustify,
                style.tailVJustifyBodyFragment,
                style.tailVJustifyTailFragment,
                style.tailVJustify,
                style.tailLeader,
                style.tailLeaderLetterStyleName.orElse { style.tailLetterStyleName },
                style.tailLeaderHJustify,
                style.tailLeaderVJustify,
                style.tailLeaderMarginLeftPx,
                style.tailLeaderMarginRightPx,
                style.tailLeaderSpacingPx,
                tailStartX,
                tailWidth
            )

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
        grouper.computeIfAbsent(key) { mutableListOf() }.add(block)
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
        return strLines.maxOf { line -> line.formatted(styling).width }
    return 0.0
}


private fun DrawnBody.selectLine(vBodyFragment: VBodyFragment, left: Boolean): DrawnBodyLine =
    when (vBodyFragment) {
        ALL_ROWS, FIRST_ROW, LAST_ROW -> throw IllegalArgumentException()
        FIRST_ROW_FIRST_LINE -> rows.first().run { if (left) topLeftLine else topRightLine }
        FIRST_ROW_LAST_LINE -> rows.first().run { if (left) bottomLeftLine else bottomRightLine }
        LAST_ROW_FIRST_LINE -> rows.last().run { if (left) topLeftLine else topRightLine }
        LAST_ROW_LAST_LINE -> rows.last().run { if (left) bottomLeftLine else bottomRightLine }
    }

private fun DrawnBody.yForHeadTail(
    objHeight: Double,
    vJustifyBodyFragment: VBodyFragment,
    vJustify: VJustifyText,
    left: Boolean
): Y =
    when (vJustifyBodyFragment) {
        ALL_ROWS -> justify(vJustify.toVJustify(), defImage.height, objHeight)
        FIRST_ROW -> rows.first().yForHeadTail(objHeight, vJustify.toVJustify())
        LAST_ROW -> rows.last().yForHeadTail(objHeight, vJustify.toVJustify())
        else -> selectLine(vJustifyBodyFragment, left).yForHeadTail(objHeight, vJustify.toVJustify())
    }

private fun DrawnBody.yForHeadTail(
    fmtStr: FormattedString,
    vJustifyBodyFragment: VBodyFragment,
    vJustify: VJustifyText,
    left: Boolean
): Y =
    when (vJustifyBodyFragment) {
        ALL_ROWS -> justify(vJustify.toVJustify(), defImage.height, fmtStr.height)
        FIRST_ROW -> rows.first().yForHeadTail(fmtStr.height, vJustify.toVJustify())
        LAST_ROW -> rows.last().yForHeadTail(fmtStr.height, vJustify.toVJustify())
        else -> selectLine(vJustifyBodyFragment, left).yForHeadTail(fmtStr, vJustify)
    }


private fun DrawnBodyRow.yForHeadTail(objHeight: Double, vJustify: VJustify): Y =
    y + justify(vJustify, height, objHeight)


private fun DrawnBodyLine.yForHeadTail(objHeight: Double, vJustify: VJustify): Y =
    y + justify(vJustify, height, objHeight)

private fun DrawnBodyLine.yForHeadTail(fmtStr: FormattedString, vJustify: VJustifyText): Y =
    when (vJustify) {
        VJustifyText.BASELINE -> when (yBaseline) {
            null -> yForHeadTail(fmtStr.height, VJustify.MIDDLE)
            else -> yBaseline - fmtStr.heightAboveBaseline
        }
        else -> yForHeadTail(fmtStr.height, vJustify.toVJustify())
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
