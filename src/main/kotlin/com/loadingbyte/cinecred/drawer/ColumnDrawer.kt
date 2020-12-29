package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.CenterOn.*
import kotlin.math.max
import kotlin.math.min


fun drawColumnImage(
    fonts: Map<FontSpec, RichFont>,
    column: Column,
    alignBodyColsGroupIds: Map<Block, Int>,
    alignHeadTailGroupIds: Map<Block, Int>
): Pair<DeferredImage, Float> {
    // This list will be filled shortly. We generate an image for each block's body.
    val bodyImages = mutableMapOf<Block, DeferredImage>()

    // Step 1:
    // Take the blocks whose bodies are laid out using the "column body layout". Group blocks that share the same
    // content style and user-defined "body columns alignment group". The "body columns" will be aligned between
    // blocks from the same group.
    val blockGroupsWithColBodyLayout = column.blocks
        .filter { block -> block.style.bodyLayout == BodyLayout.COLUMNS }
        .groupBy { block -> Pair(block.style, alignBodyColsGroupIds[block]) }
        .values
    // Generate images for blocks whose bodies are laid out using the "column body layout".
    for (blockGroup in blockGroupsWithColBodyLayout) {
        // Generate an image for the body of each block in the group. The bodies are laid out together such that,
        // for example, a "left" justification means "left" w.r.t. to the column spanned up by the widest body from
        // the block group. As a consequence, all these images also share the same width.
        bodyImages.putAll(drawBodyImagesWithColBodyLayout(fonts, blockGroup))
    }

    // Step 2:
    // Generate images for blocks whose bodies are laid out using the "flow body layout".
    for (block in column.blocks)
        if (block.style.bodyLayout == BodyLayout.FLOW)
            bodyImages[block] = drawBodyImageWithFlowBodyLayout(fonts, block)

    // We now add heads and tails to the body images and thereby generate an image for each block.
    // We also remember the x coordinate of the center line inside each generated image.
    val blockImagesWithCenterLineXs = mutableMapOf<Block, Pair<DeferredImage, Float>>()

    // Step 3:
    // We start with the blocks that have a horizontal spine. Here, heads/tails that either share a common edge
    // position or are part of blocks which are centered on the head/tail are combined into shared "head/tail columns".
    // Such a "column" is as wide as the largest head/tail it contains. This, for example, allows the user to justify
    // all heads "left" in a meaningful way.
    // First, partition the horizontal spine blocks into two partitions that will be processed separately.
    val (horSpineBlocks1, horSpineBlocks2) = column.blocks
        .filter { block -> block.style.spineDir == SpineDir.HORIZONTAL }
        .partition { block ->
            val c = block.style.centerOn
            !(c == HEAD_GAP || c == BODY_START || c == BODY_END || c == TAIL_GAP)
        }
    // Divide the first partition such that only blocks whose heads or tails should be aligned are in the same group.
    val headOrTailAlignBlockGroups1 = horSpineBlocks1
        .groupBy { block ->
            when (block.style.centerOn) {
                HEAD_START, HEAD, HEAD_END, TAIL_START, TAIL, TAIL_END ->
                    Pair(block.style.centerOn, alignHeadTailGroupIds[block])
                // The heads or tails of these blocks are never aligned. As such, we use the memory address of these
                // blocks as their group keys to make sure that each of them is always sorted into a singleton group.
                EVERYTHING, BODY -> System.identityHashCode(block)
                else -> throw IllegalStateException()  // Will never happen because we partitioned beforehand.
            }
        }.values
    // Now process the second partition.
    val headOrTailAlignBlockGroups2 = horSpineBlocks2
        // Divide into "left"-centered and "right"-centered blocks. Also divide by head/tail aligning group.
        .groupBy { block ->
            val c = block.style.centerOn
            Pair(c == HEAD_GAP || c == BODY_START, alignHeadTailGroupIds[block])
        }.values
        // Further subdivide such that only blocks whose heads or tails share an edge are in the same group.
        .flatMap { blockGroup ->
            blockGroup.fuzzyGroupBy { block ->
                when (block.style.centerOn) {
                    HEAD_GAP -> block.style.headGapPx / 2f
                    BODY_START -> block.style.headGapPx
                    BODY_END -> block.style.tailGapPx
                    TAIL_GAP -> block.style.tailGapPx / 2f
                    else -> throw IllegalStateException()  // Will never happen because we partitioned beforehand.
                }
            }
        }
    // Finally, generate block images for all horizontal blocks. The images for grouped blocks are generated in unison.
    for (blockGroup in headOrTailAlignBlockGroups1 + headOrTailAlignBlockGroups2)
        blockImagesWithCenterLineXs.putAll(drawHorizontalSpineBlockImages(fonts, blockGroup, bodyImages))

    // Step 4: Now generate block images for the blocks which have a vertical spine.
    for (block in column.blocks)
        if (block.style.spineDir == SpineDir.VERTICAL)
            blockImagesWithCenterLineXs[block] = drawVerticalSpineBlockImage(fonts, block, bodyImages[block]!!)

    // Step 5:
    // Combine the block images for the blocks inside the column to a column image.
    val columnImage = DeferredImage()
    val centerLineXInColumnImage = column.blocks.minOf { block -> blockImagesWithCenterLineXs[block]!!.second }
    var y = 0f
    for (block in column.blocks) {
        y += block.style.vMarginPx
        val (blockImage, centerLineXInBlockImage) = blockImagesWithCenterLineXs[block]!!
        val x = centerLineXInColumnImage - centerLineXInBlockImage
        columnImage.drawDeferredImage(blockImage, x, y, 1f)
        y += blockImage.height + block.style.vMarginPx + block.vGapAfterPx
    }
    // Draw a guide that shows the column's center line.
    columnImage.drawLine(CTRLINE_GUIDE_COLOR, centerLineXInColumnImage, 0f, centerLineXInColumnImage, y, isGuide = true)

    return Pair(columnImage, centerLineXInColumnImage)
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


private fun drawHorizontalSpineBlockImages(
    fonts: Map<FontSpec, RichFont>,
    blocks: List<Block>,
    bodyImages: Map<Block, DeferredImage>,
): Map<Block, Pair<DeferredImage, Float>> {
    // This will be the return value.
    val blockImagesWithCenterLineXs = mutableMapOf<Block, Pair<DeferredImage, Float>>()

    // Step 1:
    // In the drawColumnImage() function, the blocks have been grouped such that in this function, either the heads or
    // tails or nothing should be contained in a merged single-width "column". For this, depending on what should be
    // aligned, we first determine the width the head sub-column or the tail sub-column or nothing by taking the
    // maximum width of all head/tail strings coming from all blocks from the block group.
    val headSharedWidth = when (blocks[0].style.centerOn) {
        HEAD_START, HEAD, HEAD_END, HEAD_GAP, BODY_START ->
            blocks.maxOf { block ->
                if (block.style.spineDir == SpineDir.VERTICAL || block.head == null) 0f
                else fonts[block.style.headFontSpec]!!.metrics.stringWidth(block.head).toFloat()
            }
        else -> null
    }
    val tailSharedWidth = when (blocks[0].style.centerOn) {
        BODY_END, TAIL_GAP, TAIL_START, TAIL, TAIL_END ->
            blocks.maxOf { block ->
                if (block.style.spineDir == SpineDir.VERTICAL || block.tail == null) 0f
                else fonts[block.style.tailFontSpec]!!.metrics.stringWidth(block.tail).toFloat()
            }
        else -> null
    }

    // Step 2:
    // Draw a deferred image for each block.
    for (block in blocks) {
        val bodyImage = bodyImages[block]!!
        val headFont = fonts[block.style.headFontSpec]!!
        val tailFont = fonts[block.style.tailFontSpec]!!

        val headWidth = headSharedWidth ?: block.head?.let { headFont.metrics.stringWidth(it).toFloat() } ?: 0f
        val tailWidth = tailSharedWidth ?: block.tail?.let { tailFont.metrics.stringWidth(it).toFloat() } ?: 0f

        val headStartX = 0f
        val headEndX = headStartX + headWidth
        val bodyStartX = if (headWidth == 0f) 0f else headEndX + block.style.headGapPx
        val bodyEndX = bodyStartX + bodyImage.width
        val tailStartX = bodyEndX + (if (tailWidth == 0f) 0f else bodyEndX + block.style.tailGapPx)
        val tailEndX = tailStartX + tailWidth

        // Draw the block image.
        val blockImage = DeferredImage()
        var y = 0f
        // Draw the block's head.
        if (block.head != null) {
            blockImage.drawJustifiedString(
                headFont, block.style.bodyFontSpec, block.style.headHJustify, block.style.headVJustify, block.head,
                0f, y, headWidth, bodyImage.height
            )
            // Draw a guide that shows the edges of the head space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, 0f, y, headWidth, bodyImage.height, isGuide = true)
        }
        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, y, 1f)
        if (block.style.spineDir == SpineDir.VERTICAL)
            y += bodyImage.height
        // Draw the block's tail.
        if (block.tail != null) {
            blockImage.drawJustifiedString(
                tailFont, block.style.bodyFontSpec, block.style.tailHJustify, block.style.tailVJustify, block.tail,
                tailStartX, y, tailWidth, bodyImage.height
            )
            // Draw a guide that shows the edges of the tail space.
            blockImage.drawRect(HEAD_TAIL_GUIDE_COLOR, tailStartX, y, tailWidth, bodyImage.height, isGuide = true)
        }

        // Find the x coordinate of the center line in the generated image for the current block.
        val centerLineXInImage = when (block.style.centerOn) {
            EVERYTHING -> (headStartX + tailEndX) / 2f
            HEAD_START -> headStartX
            HEAD -> (headStartX + headEndX) / 2f
            HEAD_END -> headEndX
            HEAD_GAP -> (headEndX + bodyStartX) / 2f
            BODY_START -> bodyStartX
            BODY -> (bodyStartX + bodyEndX) / 2f
            BODY_END -> bodyEndX
            TAIL_GAP -> (bodyEndX + tailStartX) / 2f
            TAIL_START -> tailStartX
            TAIL -> (tailStartX + tailEndX) / 2f
            TAIL_END -> tailEndX
        }

        blockImagesWithCenterLineXs[block] = Pair(blockImage, centerLineXInImage)
    }

    return blockImagesWithCenterLineXs
}


private fun drawVerticalSpineBlockImage(
    fonts: Map<FontSpec, RichFont>,
    block: Block,
    bodyImage: DeferredImage
): Pair<DeferredImage, Float> {
    val headFont = fonts[block.style.headFontSpec]!!
    val tailFont = fonts[block.style.tailFontSpec]!!

    // Will store the start and end x coordinates of the head resp. tail if it exists.
    var headXs = Pair(0f, 0f)
    var tailXs = Pair(0f, 0f)

    // Draw the body image.
    val blockImage = DeferredImage()
    var y = 0f
    // Draw the block's head.
    if (block.head != null) {
        headXs = blockImage.drawJustifiedString(headFont, block.style.headHJustify, block.head, 0f, y, bodyImage.width)
        // Draw guides that show the edges of the head space.
        val y2 = blockImage.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0f, 0f, 0f, y2, isGuide = true)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, bodyImage.width, 0f, bodyImage.width, y2, isGuide = true)
        // Advance to the body.
        y += headFont.spec.run { heightPx + extraLineSpacingPx } + block.style.headGapPx
    }
    // Draw the block's body.
    blockImage.drawDeferredImage(bodyImage, 0f, y, 1f)
    y += bodyImage.height
    // Draw the block's tail.
    if (block.tail != null) {
        y += block.style.tailGapPx
        tailXs = blockImage.drawJustifiedString(tailFont, block.style.tailHJustify, block.tail, 0f, y, bodyImage.width)
        // Draw guides that show the edges of the tail space.
        val y2 = blockImage.height
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, 0f, y, 0f, y2, isGuide = true)
        blockImage.drawLine(HEAD_TAIL_GUIDE_COLOR, bodyImage.width, y, bodyImage.width, y2, isGuide = true)
    }

    // Find the x coordinate of the center line in the generated image for the current block.
    val centerLineXInImage = when (block.style.centerOn) {
        BODY_START -> 0f
        EVERYTHING, HEAD_GAP, BODY, TAIL_GAP -> bodyImage.width / 2f
        BODY_END -> bodyImage.width
        HEAD_START -> headXs.first
        HEAD -> (headXs.first + headXs.second) / 2f
        HEAD_END -> headXs.second
        TAIL_START -> tailXs.first
        TAIL -> (tailXs.first + tailXs.second) / 2f
        TAIL_END -> tailXs.second
    }

    return Pair(blockImage, centerLineXInImage)
}


// Returns the start and end x coordinates of the drawn string.
private fun DeferredImage.drawJustifiedString(
    font: RichFont, hJustify: HJustify, string: String,
    x: Float, y: Float, width: Float
): Pair<Float, Float> {
    val stringWidth = font.metrics.stringWidth(string)
    val justifiedX = when (hJustify) {
        HJustify.LEFT -> x
        HJustify.CENTER -> x + (width - stringWidth) / 2f
        HJustify.RIGHT -> x + width - stringWidth
    }
    drawString(font, string, justifiedX, y)
    return Pair(justifiedX, justifiedX + stringWidth)
}


private fun DeferredImage.drawJustifiedString(
    font: RichFont, bodyFontSpec: FontSpec, hJustify: HJustify, vJustify: VJustify, string: String,
    x: Float, y: Float, width: Float, height: Float
) {
    val diff = bodyFontSpec.run { heightPx + extraLineSpacingPx } - font.spec.run { heightPx + extraLineSpacingPx }
    val justifiedY = when (vJustify) {
        VJustify.TOP_TOP -> y
        VJustify.TOP_MIDDLE -> y + diff / 2f
        VJustify.TOP_BOTTOM -> y + diff
        VJustify.MIDDLE -> y + (height - font.spec.heightPx) / 2f
        VJustify.BOTTOM_TOP -> y + height - font.spec.heightPx - diff
        VJustify.BOTTOM_MIDDLE -> y + height - font.spec.heightPx - diff / 2f
        VJustify.BOTTOM_BOTTOM -> y + height - font.spec.heightPx
    }
    drawJustifiedString(font, hJustify, string, x, justifiedY, width)
}
