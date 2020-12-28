package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*


fun drawColumnImages(
    fonts: Map<FontSpec, RichFont>,
    blockGroupIds: Map<Block, Int>,
    columns: List<Column>
): Map<Column, Pair<DeferredImage, Float>> {
    val blocks = columns.flatMap(Column::blocks)

    // This map will be filled shortly. We generate an image for each block. We also remember the x coordinate of
    // the center line inside each generated image.
    val blockImagesWithCenterLineXs = mutableMapOf<Block, Pair<DeferredImage, Float>>()

    // Step 1:
    // Take the blocks whose bodies are laid out using the "column body layout". Group blocks that are inside the same
    // user-defined block group and share the same CenterOn setting. Certain block elements will be aligned between
    // blocks from the same group.
    val blockGroupsWithColBodyLayout = blocks
        .filter { block -> block.style.bodyLayout == BodyLayout.COLUMNS }
        .groupBy { block ->
            val effectiveCenterOn = when {
                // Depending on the block style and availability of a block head/tail, multiple CenterOn settings
                // are effectively the same. In these cases, we coerce CenterOn to one canonical setting such that the
                // grouping really groups together all blocks that are centered on basically the same thing.
                block.style.spineDir == SpineDir.VERTICAL -> CenterOn.EVERYTHING
                block.head == null && block.tail == null -> CenterOn.EVERYTHING
                block.head == null && block.style.centerOn == CenterOn.HEAD -> CenterOn.HEAD_GAP
                block.tail == null && block.style.centerOn == CenterOn.TAIL -> CenterOn.TAIL_GAP
                else -> block.style.centerOn
            }
            Pair(blockGroupIds[block], effectiveCenterOn)
        }
        .values
    // Generate images for blocks whose bodies are laid out using the "column body layout".
    for (blockGroup in blockGroupsWithColBodyLayout) {
        // Generate an image for the body of each block in the group. The bodies are laid out together such that,
        // for example, a "center" justification means "center" w.r.t. all bodies from the block group.
        // Also, all these images share the same width.
        val (bodyImages, bodyColWidth) = drawBodyImagesWithColBodyLayout(fonts, blockGroup)
        // Store the resulting images in the overall result.
        blockImagesWithCenterLineXs += drawBlockImages(fonts, blockGroup, bodyImages, bodyColWidth)
    }

    // Step 2:
    // Take the blocks whose bodies are laid out using the "flow body layout".
    val blocksWithFlowBodyLayout = blocks
        .filter { block -> block.style.bodyLayout == BodyLayout.FLOW }
    // Generate images for blocks whose bodies are laid out using the "flow body layout".
    for (block in blocksWithFlowBodyLayout) {
        val (bodyImage, bodyColWidth) = drawBodyImageWithFlowBodyLayout(fonts, block)
        // Store the resulting image in the overall result.
        blockImagesWithCenterLineXs += drawBlockImages(fonts, listOf(block), mapOf(block to bodyImage), bodyColWidth)
    }

    // Step 3:
    // For each column, combine the block images for the blocks inside the column to a column image.
    return columns.map { column ->
        val columnImage = DeferredImage()
        val centerLineXInColumnImage = column.blocks.minOf { block -> blockImagesWithCenterLineXs[block]!!.second }

        var y = 0f
        for (block in column.blocks) {
            val (blockImage, centerLineXInBlockImage) = blockImagesWithCenterLineXs[block]!!
            val x = centerLineXInColumnImage - centerLineXInBlockImage
            columnImage.drawDeferredImage(blockImage, x, y, 1f)
            y += blockImage.height + block.vGapAfterPx
        }

        column to Pair(columnImage, centerLineXInColumnImage)
    }.toMap()
}


private fun drawBlockImages(
    fonts: Map<FontSpec, RichFont>,
    blocks: List<Block>,
    bodyImages: Map<Block, DeferredImage>,
    bodyColWidth: Float
): Map<Block, Pair<DeferredImage, Float>> {
    val blockImagesWithCenterLineXs = mutableMapOf<Block, Pair<DeferredImage, Float>>()

    // Step 1:
    // We want to make sure that the body sub-columns as well as all horizontal head and tail sub-columns are
    // aligned. For this, we first determine the width of the head sub-column and tail sub-column by taking the
    // maximum width of all head/tail strings coming from all blocks from the block group.
    val hHeadColWidth = blocks.maxOf { block ->
        if (block.style.spineDir == SpineDir.VERTICAL || block.head == null) 0f
        else fonts[block.style.headFontSpec]!!.metrics.stringWidth(block.head).toFloat()
    }
    val hTailColWidth = blocks.maxOf { block ->
        if (block.style.spineDir == SpineDir.VERTICAL || block.tail == null) 0f
        else fonts[block.style.tailFontSpec]!!.metrics.stringWidth(block.tail).toFloat()
    }
    // We also determine the maximum horizontal gaps between the head resp. tail column and the body column
    // over all blocks with a horizontal spine.
    val hHeadGap = blocks.maxOf { block ->
        if (block.style.spineDir == SpineDir.VERTICAL) 0f
        else block.style.headGapPx
    }
    val hTailGap = blocks.maxOf { block ->
        if (block.style.spineDir == SpineDir.VERTICAL) 0f
        else block.style.tailGapPx
    }

    val hHeadStartX = 0f
    val hHeadEndX = hHeadColWidth
    val bodyStartX = if (hHeadColWidth == 0f) 0f else hHeadColWidth + hHeadGap
    val bodyEndX = bodyStartX + bodyColWidth
    val hTailStartX = bodyEndX + (if (hTailColWidth == 0f) 0f else hTailColWidth + hTailGap)
    val hTailEndX = hTailStartX + hTailColWidth

    // Step 2: Find the x coordinate of the center line in the generated images for the current block group.
    val centerOn = blocks[0].style.centerOn
    val centerLineXInImage = when (centerOn) {
        CenterOn.EVERYTHING -> (hHeadStartX + hTailEndX) / 2
        CenterOn.HEAD -> (hHeadStartX + hHeadEndX) / 2
        CenterOn.HEAD_GAP -> (hHeadEndX + bodyStartX) / 2
        CenterOn.BODY -> (bodyStartX + bodyEndX) / 2
        CenterOn.TAIL_GAP -> (bodyEndX + hTailStartX) / 2
        CenterOn.TAIL -> (hTailStartX + hTailEndX) / 2
    }

    // Step 3:
    // Draw a deferred image for each block.
    for (block in blocks) {
        val blockImage = DeferredImage()
        blockImagesWithCenterLineXs[block] = Pair(blockImage, centerLineXInImage)

        val bodyImage = bodyImages[block]!!
        var y = 0f

        if (block.head != null) {
            val headFont = fonts[block.style.headFontSpec]!!
            // If the block's spine is horizontal, draw the head in a separate sub-column.
            if (block.style.spineDir == SpineDir.HORIZONTAL)
                blockImage.drawJustifiedString(
                    headFont, block.style.bodyFontSpec, block.style.headHJustify, block.style.headVJustify, block.head,
                    0f, y, hHeadColWidth, bodyImage.height
                )
            // If the block's spine is vertical, draw the head over the whole width of the column.
            else {
                blockImage.drawJustifiedString(headFont, block.style.headHJustify, block.head, 0f, y, bodyColWidth)
                y += headFont.spec.run { heightPx + extraLineSpacingPx } + block.style.headGapPx
            }
        }

        // Draw the block's body.
        blockImage.drawDeferredImage(bodyImage, bodyStartX, y, 1f)
        if (block.style.spineDir == SpineDir.VERTICAL)
            y += bodyImage.height

        if (block.tail != null) {
            val tailFont = fonts[block.style.tailFontSpec]!!
            // If the block's spine is horizontal, draw the tail in a separate sub-column.
            if (block.style.spineDir == SpineDir.HORIZONTAL)
                blockImage.drawJustifiedString(
                    tailFont, block.style.bodyFontSpec, block.style.tailHJustify, block.style.tailVJustify, block.tail,
                    hTailStartX, y, hTailColWidth, bodyImage.height
                )
            // If the block's spine is vertical, draw the tail over the whole width of the column.
            else {
                y += block.style.tailGapPx
                blockImage.drawJustifiedString(tailFont, block.style.tailHJustify, block.tail, 0f, y, bodyColWidth)
            }
        }
    }

    return blockImagesWithCenterLineXs
}


private fun DeferredImage.drawJustifiedString(
    font: RichFont, hJustify: HJustify, string: String,
    x: Float, y: Float, width: Float
) {
    val justifiedX = when (hJustify) {
        HJustify.LEFT -> x
        HJustify.CENTER -> x + (width - font.metrics.stringWidth(string)) / 2f
        HJustify.RIGHT -> x + width - font.metrics.stringWidth(string)
    }
    drawString(font, string, justifiedX, y, 1f)
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
