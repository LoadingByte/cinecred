package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*
import java.awt.FontMetrics
import java.util.*


fun drawBodyImagesWithColBodyLayout(
    fonts: Map<FontSpec, RichFont>,
    blocks: List<Block>
): Map<Block, DeferredImage> {
    // We assume that only blocks which share the same style are laid out together.
    require(blocks.all { block -> block.style == blocks[0].style })
    val style = blocks[0].style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "column body layout".
    require(style.bodyLayout == BodyLayout.COLUMNS)

    // Step 1:
    // First of all, we want all body columns inside some block to have the same width as that makes the
    // head line (in the following example 'Extras') in designs like the following nice and centered:
    //                 Extras
    //     |Nathanial A.|  |   Tim C.   |
    //     | Richard B. |  |  Sarah D.  |
    //
    // For this, we first determine the width shared by all body columns inside all body images images by taking the
    // maximum width of all body lines of all blocks. We then obtain the width shared by all body images by
    // multiplying with the number of body columns (plus the appropriate body column gaps).
    val numBodyCols = style.colsBodyLayoutColJustifies.size
    val bodyFont = fonts[style.bodyFontSpec]!!
    val bodyColWidth = blocks.maxOf { block -> block.body.maxOf { bodyLine -> bodyFont.metrics.stringWidth(bodyLine) } }
    val bodyImageWidth = (numBodyCols - 1) * style.colsBodyLayoutColGapPx + numBodyCols * bodyColWidth

    // Step 2:
    // Draw a deferred image for the body of each block.
    return blocks.associateWith { block ->
        // If there are no body columns, there is no place to put the body.
        // So we can just return an empty image.
        if (numBodyCols == 0)
            return@associateWith DeferredImage()
        val bodyPartitions = partitionBodyIntoCols(block.body, numBodyCols)

        val bodyImage = DeferredImage()
        bodyImage.setMinWidth(bodyImageWidth)

        var x = 0f
        val bodyPartitionsIter = bodyPartitions.iterator()
        for (colJustify in style.colsBodyLayoutColJustifies) {
            var y = 0f
            for (bodyLine in bodyPartitionsIter.next()) {
                val justifiedX = when (colJustify) {
                    HJustify.LEFT -> x
                    HJustify.CENTER -> x + (bodyColWidth - bodyFont.metrics.stringWidth(bodyLine)) / 2f
                    HJustify.RIGHT -> x + bodyColWidth - bodyFont.metrics.stringWidth(bodyLine)
                }
                bodyImage.drawString(bodyFont, bodyLine, justifiedX, y)
                // Advance to the next line in the current column.
                y += bodyFont.spec.heightPx + bodyFont.spec.extraLineSpacingPx
            }
            // Draw guides that show the edges of the body column.
            bodyImage.drawLine(BODY_GUIDE_COLOR, x, 0f, x, y, isGuide = true)
            bodyImage.drawLine(BODY_GUIDE_COLOR, x + bodyColWidth, 0f, x + bodyColWidth, y, isGuide = true)
            // Advance to the next column.
            x += bodyColWidth + style.colsBodyLayoutColGapPx
        }

        bodyImage
    }
}


private fun partitionBodyIntoCols(body: List<String>, numBodyCols: Int): List<List<String>> {
    val partitions = (0 until numBodyCols).map { mutableListOf<String>() }
    var col = 0
    for (bodyLine in body) {
        partitions[col++].add(bodyLine)
        if (col == numBodyCols)
            col = 0
    }
    return partitions
}


fun drawBodyImageWithFlowBodyLayout(
    fonts: Map<FontSpec, RichFont>,
    block: Block
): DeferredImage {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow body layout".
    require(block.style.bodyLayout == BodyLayout.FLOW)

    val bodyFont = fonts[block.style.bodyFontSpec]!!

    // The width of the body image must be at least the width of the widest body element, because otherwise,
    // that element could not even fit into one line of the body.
    val bodyImageWidth = block.style.flowBodyLayoutBodyWidthPx
        .coerceAtLeast(block.body.maxOf { bodyElem -> bodyFont.metrics.stringWidth(bodyElem) }.toFloat())

    // Retrieve the separator string (trimmed, if necessary) and find its width.
    val sepStr = block.style.flowBodyLayoutSeparator.trim()
    val sepStrWidth = bodyFont.metrics.stringWidth(sepStr)

    // Determine which body elements should occur on which line. We use the simplest possible text flow algorithm
    // for this.
    val minSepWidth = 2 * block.style.flowBodyLayoutSeparatorSpacingPx + (if (sepStr.isNotEmpty()) sepStrWidth else 0)
    val bodyLines = partitionBodyIntoLines(block.body, bodyFont.metrics, bodyImageWidth, minSepWidth)

    // Draw the actual image.
    val bodyImage = DeferredImage()
    bodyImage.setMinWidth(bodyImageWidth)
    var y = 0f
    for (bodyLine in bodyLines) {
        // Determine the width of all rigid elements in the body line, that is, the total width of all body elements
        // and separator strings.
        val totalRigidWidth =
            bodyLine.sumOf { bodyElem -> bodyFont.metrics.stringWidth(bodyElem) } +
                    (bodyLine.size - 1) * sepStrWidth

        // If the body uses full justification, we adjust the separator spacing such that the body line
        // fills the whole width of the body image.
        val sepSpacing = when (block.style.flowBodyLayoutJustify) {
            FlowJustify.FULL -> (bodyImageWidth - totalRigidWidth) / ((bodyLine.size - 1) * 2)
            else -> block.style.flowBodyLayoutSeparatorSpacingPx
        }

        // Find the x coordinate of the first body element depending on the justification.
        // For left or full justification, we start at the leftmost position.
        // For center or right justification, we start such that the body line is centered or right-justified.
        val bodyLineWidth = totalRigidWidth + (bodyLine.size - 1) * 2 * sepSpacing
        var x = when (block.style.flowBodyLayoutJustify) {
            FlowJustify.LEFT, FlowJustify.FULL -> 0f
            FlowJustify.CENTER -> (bodyImageWidth - bodyLineWidth) / 2
            FlowJustify.RIGHT -> bodyImageWidth - bodyLineWidth
        }

        // Actually draw the body line using the measurements made above.
        for ((idx, bodyElem) in bodyLine.withIndex()) {
            bodyImage.drawString(bodyFont, bodyElem, x, y)
            x += bodyFont.metrics.stringWidth(bodyElem)
            if (idx != bodyLine.size - 1) {
                x += sepSpacing
                if (sepStr.isNotEmpty()) {
                    bodyImage.drawString(bodyFont, sepStr, x, y)
                    x += sepStrWidth
                }
                x += sepSpacing
            }
        }

        // Advance to the next body line.
        y += bodyFont.spec.heightPx + bodyFont.spec.extraLineSpacingPx
    }

    // Draw guides that show the body's edges.
    bodyImage.drawLine(BODY_GUIDE_COLOR, 0f, 0f, 0f, y, isGuide = true)
    bodyImage.drawLine(BODY_GUIDE_COLOR, bodyImageWidth, 0f, bodyImageWidth, y, isGuide = true)

    return bodyImage
}


private fun partitionBodyIntoLines(
    body: List<String>,
    bodyFontMetrics: FontMetrics,
    bodyImageWidth: Float,
    minSepWidth: Float
): List<List<String>> {
    val bodyQueue: Queue<String> = LinkedList(body)
    val bodyLines = mutableListOf<MutableList<String>>(mutableListOf())

    var x = -minSepWidth
    while (bodyQueue.isNotEmpty()) {
        val bodyElem = bodyQueue.remove()
        val bodyElemWidth = bodyFontMetrics.stringWidth(bodyElem).toFloat()
        x += minSepWidth + bodyElemWidth
        if (x <= bodyImageWidth) {
            // Case 1: The body element still fits in the current line.
            bodyLines.last().add(bodyElem)
        } else {
            // Case 2: The body element does not fit. Start a new line.
            bodyLines.add(mutableListOf(bodyElem))
            x = bodyElemWidth
        }
    }

    return bodyLines
}
