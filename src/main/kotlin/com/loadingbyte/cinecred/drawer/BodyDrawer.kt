package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*
import java.awt.FontMetrics
import java.util.*


fun drawBodyImagesWithColBodyLayout(
    fonts: Map<FontSpec, RichFont>,
    blocks: List<Block>
): Pair<Map<Block, DeferredImage>, Float> {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "column body layout".
    if (blocks.any { block -> block.style.bodyLayout != BodyLayout.COLUMNS })
        throw IllegalArgumentException()

    // Step 1:
    // First of all, we want all body columns inside some block to having the same width as that makes the
    // head line (in the following example 'Extras') in designs like the following nice and centered:
    //                 Extras
    //     |Nathanial A.|  |   Tim C.   |
    //     | Richard B. |  |  Sarah D.  |
    //
    // We also want to make sure that all body columns in the block layout group are aligned in the following way,
    // especially when blocks with different numbers of body calls are present in the "blocks" list:
    //     Block 1:  |              Body Col 1              |
    //     Block 2:  |              Body Col 1              |
    //     Block 3:  |   Body Col 1    |  |    Body Col 2   |
    //     Block 4:  |   Body Col 1    |  |    Body Col 2   |
    //     Block 5:  |Body Col 1|  |Body Col 2|  |Body Col 3|
    //     Block 6:  |Body Col 1|  |Body Col 2|  |Body Col 3|
    //
    // For this, we first group the blocks which are laid out using the "column body layout" by their number of body
    // columns. For each such group, we then determine the gap between columns as the maximum gap over all blocks
    // from the respective block group.
    val bodyColGaps = blocks
        .groupBy { block -> block.style.colsBodyLayoutColTypes.size }
        .mapValues { (_, blockGrp) -> blockGrp.maxOf { block -> block.style.colsBodyLayoutColGapPx } }
    // We next determine the width shared by all body images by taking the maximum width of all body lines of all
    // blocks times the number of body columns of the respective block (plus the appropriate body column gaps).
    val bodyImageWidth = blocks.maxOf { block ->
        val numBodyCols = block.style.colsBodyLayoutColTypes.size
        val bodyFontMetrics = fonts[block.style.bodyFontSpec]!!.metrics
        numBodyCols * block.body.maxOf { bodyLine -> bodyFontMetrics.stringWidth(bodyLine) } +
                (numBodyCols - 1) * bodyColGaps[numBodyCols]!!
    }

    // Step 2:
    // Draw a deferred image for the body of each block.
    val bodyImages = blocks.map { block ->
        val bodyFont = fonts[block.style.bodyFontSpec]!!
        val numBodyCols = block.style.colsBodyLayoutColTypes.size
        val numNonVacantBodyCols = block.style.colsBodyLayoutColTypes.count { it != ColType.VACANT }

        // If there are no non-vacant body columns, there is no place to put the body.
        // So we can just return an empty image.
        if (numNonVacantBodyCols == 0)
            return@map block to DeferredImage()

        val bodyColGap = bodyColGaps[numBodyCols]!!
        val bodyColWidth = (bodyImageWidth - (numBodyCols - 1) * bodyColGap) / numBodyCols
        val bodyPartitions = partitionBodyIntoCols(block.body, numNonVacantBodyCols)

        val bodyImage = DeferredImage()

        var x = 0f
        val bodyPartitionsIter = bodyPartitions.iterator()
        for (colType in block.style.colsBodyLayoutColTypes) {
            if (colType != ColType.VACANT) {
                var y = 0f
                for (bodyLine in bodyPartitionsIter.next()) {
                    val justifiedX = when (colType) {
                        ColType.LEFT -> x
                        ColType.CENTER -> x + (bodyColWidth - bodyFont.metrics.stringWidth(bodyLine)) / 2f
                        ColType.RIGHT -> x + bodyColWidth - bodyFont.metrics.stringWidth(bodyLine)
                        ColType.VACANT -> throw IllegalStateException()  // will never happen
                    }
                    bodyImage.drawString(bodyFont, bodyLine, justifiedX, y, 1f)
                    y += bodyFont.spec.heightPx + bodyFont.spec.extraLineSpacingPx
                }
            }
            x += bodyColWidth + bodyColGap
        }

        block to bodyImage
    }.toMap()

    return Pair(bodyImages, bodyImageWidth)
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
): Pair<DeferredImage, Float> {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow body layout".
    if (block.style.bodyLayout != BodyLayout.FLOW)
        throw IllegalArgumentException()

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
            bodyImage.drawString(bodyFont, bodyElem, x, y, 1f)
            x += bodyFont.metrics.stringWidth(bodyElem)
            if (idx != bodyLine.size - 1) {
                x += sepSpacing
                if (sepStr.isNotEmpty()) {
                    bodyImage.drawString(bodyFont, sepStr, x, y, 1f)
                    x += sepStrWidth
                }
                x += sepSpacing
            }
        }

        // Advance to the next body line.
        y += bodyFont.spec.heightPx + bodyFont.spec.extraLineSpacingPx
    }

    return Pair(bodyImage, bodyImageWidth)
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
