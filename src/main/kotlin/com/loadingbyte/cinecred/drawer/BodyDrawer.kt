package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BodyElementConform.*
import java.util.*
import kotlin.math.max


fun drawBodyImagesWithGridBodyLayout(
    fonts: Map<FontSpec, RichFont>,
    blocks: List<Block>
): Map<Block, DeferredImage> {
    // We assume that only blocks which share the same style are laid out together.
    require(blocks.all { block -> block.style == blocks[0].style })
    val style = blocks[0].style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "grid body layout".
    require(style.bodyLayout == BodyLayout.GRID)

    // Step 1:
    // Determine the width of each grid column (these widths are shared across blocks). Also determine the height of
    // each row of each block (these heights are of course not shared across blocks). Depending on what "body element
    // conform" the user has configured, there may be only one width resp. height that is shared across all columns
    // resp. across all rows in all blocks. For example, the user might have chosen to share column widths in order
    // to obtain a layout like the following, where the head line (in the example 'Extras') is nice and centered:
    //                 Extras
    //     |Nathanial A.|  |   Tim C.   |
    //     | Richard B. |  |  Sarah D.  |
    val bodyFont = fonts[style.bodyFontSpec]!!
    val numBodyCols = style.bodyLayoutColsHJustify.size
    val bodyPartitions = blocks.associateWith { block -> partitionIntoCols(block.body, numBodyCols) }
    val numBodyRows = bodyPartitions.mapValues { (_, part) -> part[0].size }

    fun independentBodyColWidths() = List(numBodyCols) { col ->
        bodyPartitions.values.maxOf { part ->
            part[col].maxOf { bodyElem -> bodyElem.getWidth(bodyFont) }
        }
    }

    fun conformedBodyColWidth() =
        blocks.maxOf { block ->
            block.body.maxOf { bodyElem -> bodyElem.getWidth(bodyFont) }
        }

    fun independentBodyRowHeights() = blocks.associateWith { block ->
        List(numBodyRows[block]!!) { row ->
            bodyPartitions[block]!!.maxOf { partCol ->
                partCol.getOrNull(row)?.getHeight(bodyFont) ?: 0
            }
        }
    }

    fun conformedBodyRowHeight() =
        blocks.maxOf { block ->
            block.body.maxOf { bodyElem -> bodyElem.getHeight(bodyFont) }
        }

    fun forEachBodyCol(width: Int) = List(numBodyCols) { width }
    fun forEachBodyRow(height: Int) = blocks.associateWith { block -> List(numBodyRows[block]!!) { height } }

    val bodyColWidths: List<Int>
    val bodyRowHeights: Map<Block, List<Int>>
    when (style.bodyLayoutElemConform) {
        NOTHING -> {
            bodyColWidths = independentBodyColWidths()
            bodyRowHeights = independentBodyRowHeights()
        }
        WIDTH -> {
            bodyColWidths = forEachBodyCol(conformedBodyColWidth())
            bodyRowHeights = independentBodyRowHeights()
        }
        HEIGHT -> {
            bodyColWidths = independentBodyColWidths()
            bodyRowHeights = forEachBodyRow(conformedBodyRowHeight())
        }
        WIDTH_AND_HEIGHT -> {
            bodyColWidths = forEachBodyCol(conformedBodyColWidth())
            bodyRowHeights = forEachBodyRow(conformedBodyRowHeight())
        }
        SQUARE -> {
            val size = max(conformedBodyColWidth(), conformedBodyRowHeight())
            bodyColWidths = forEachBodyCol(size)
            bodyRowHeights = forEachBodyRow(size)
        }
    }

    val bodyImageWidth = (numBodyCols - 1) * style.bodyLayoutHorizontalGapPx + bodyColWidths.sum()

    // Step 2:
    // Draw a deferred image for the body of each block.
    return blocks.associateWith { block ->
        // If there are no body columns, there is no place to put the body.
        // So we can just return an empty image.
        if (numBodyCols == 0)
            return@associateWith DeferredImage()

        val bodyImage = DeferredImage()
        bodyImage.setMinWidth(bodyImageWidth)

        var x = 0f
        val bodyPartitionsIter = bodyPartitions[block]!!.iterator()
        for ((justifyCol, colWidth) in style.bodyLayoutColsHJustify.zip(bodyColWidths)) {
            var y = 0f
            for ((bodyElem, rowHeight) in bodyPartitionsIter.next().zip(bodyRowHeights[block]!!)) {
                bodyImage.drawJustifiedBodyElem(
                    bodyElem, bodyFont, justifyCol, style.bodyLayoutElemVJustify,
                    x, y, colWidth.toFloat(), rowHeight.toFloat()
                )
                // Draw a guide that shows the edges of the body element space.
                bodyImage.drawRect(BODY_ELEM_GUIDE_COLOR, x, y, colWidth.toFloat(), rowHeight.toFloat(), isGuide = true)
                // Advance to the next line in the current column.
                y += rowHeight + style.bodyLayoutLineGapPx
            }
            // Advance to the next column.
            x += colWidth + style.bodyLayoutHorizontalGapPx
        }

        bodyImage
    }
}


private fun <E> partitionIntoCols(list: List<E>, numCols: Int): List<List<E>> {
    val cols = (0 until numCols).map { mutableListOf<E>() }
    var colIdx = 0
    for (elem in list) {
        cols[colIdx++].add(elem)
        if (colIdx == numCols)
            colIdx = 0
    }
    return cols
}


fun drawBodyImageWithFlowBodyLayout(
    fonts: Map<FontSpec, RichFont>,
    block: Block
): DeferredImage {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow body layout".
    require(block.style.bodyLayout == BodyLayout.FLOW)

    val bodyFont = fonts[block.style.bodyFontSpec]!!
    val horGap = block.style.bodyLayoutHorizontalGapPx
    val sepStr = block.style.bodyLayoutSeparator

    // Find the maximum width resp. height over all body elements.
    val maxElemWidth = block.body.maxOf { bodyElem -> bodyElem.getWidth(bodyFont) }
    val maxElemHeight = block.body.maxOf { bodyElem -> bodyElem.getHeight(bodyFont) }

    // The width of the body image must be at least the width of the widest body element, because otherwise,
    // that element could not even fit into one line of the body.
    val bodyImageWidth = block.style.bodyLayoutBodyWidthPx.coerceAtLeast(maxElemWidth.toFloat())

    // Determine which body elements should lie on which line. We use the simplest possible
    // text flow algorithm for this.
    val bodyLines = partitionIntoLines(block.body, bodyImageWidth, horGap) { bodyElem ->
        when (block.style.bodyLayoutElemConform) {
            NOTHING, HEIGHT -> bodyElem.getWidth(bodyFont).toFloat()
            WIDTH, WIDTH_AND_HEIGHT -> maxElemWidth.toFloat()
            SQUARE -> max(maxElemWidth, maxElemHeight).toFloat()
        }
    }

    // Draw the actual image.
    val bodyImage = DeferredImage()
    bodyImage.setMinWidth(bodyImageWidth)
    var y = 0f
    for (bodyLine in bodyLines) {
        // Determine the width of all rigid elements in the body line, that is, the total width of all body elements
        // and separator strings.
        val totalRigidWidth = when (block.style.bodyLayoutElemConform) {
            NOTHING, HEIGHT -> bodyLine.sumOf { bodyElem -> bodyElem.getWidth(bodyFont) }
            WIDTH, WIDTH_AND_HEIGHT -> bodyLine.size * maxElemWidth
            SQUARE -> bodyLine.size * max(maxElemWidth, maxElemHeight)
        }

        // If the body uses full justification, we use this "glue" to adjust the horizontal gap around the separator
        // such that the body line fills the whole width of the body image.
        val horGlue = if (block.style.bodyLayoutLineHJustify != LineHJustify.FULL) 0f else
            (bodyImageWidth - totalRigidWidth) / (bodyLine.size - 1) - horGap

        // Find the filled width as well as the height of the current body line.
        val bodyLineWidth = totalRigidWidth + (bodyLine.size - 1) * (horGap + horGlue)
        val bodyLineHeight = when (block.style.bodyLayoutElemConform) {
            NOTHING, WIDTH -> bodyLine.maxOf { bodyElem -> bodyElem.getHeight(bodyFont) }
            HEIGHT, WIDTH_AND_HEIGHT -> maxElemHeight
            SQUARE -> max(maxElemWidth, maxElemHeight)
        }

        // Find the x coordinate of the first body element depending on the justification.
        // For left or full justification, we start at the leftmost position.
        // For center or right justification, we start such that the body line is centered or right-justified.
        var x = when (block.style.bodyLayoutLineHJustify) {
            LineHJustify.LEFT, LineHJustify.FULL -> 0f
            LineHJustify.CENTER -> (bodyImageWidth - bodyLineWidth) / 2f
            LineHJustify.RIGHT -> bodyImageWidth - bodyLineWidth
        }

        // Actually draw the body line using the measurements from above.
        for ((idx, bodyElem) in bodyLine.withIndex()) {
            val areaWidth = when (block.style.bodyLayoutElemConform) {
                NOTHING, HEIGHT -> bodyElem.getWidth(bodyFont)
                WIDTH, WIDTH_AND_HEIGHT, SQUARE -> maxElemWidth
            } + (if (idx == 0 || idx == bodyLine.lastIndex) horGlue / 2f else horGlue)

            // Draw the current body element.
            bodyImage.drawJustifiedBodyElem(
                bodyElem, bodyFont, block.style.bodyLayoutElemHJustify, block.style.bodyLayoutElemVJustify, x, y,
                areaWidth, bodyLineHeight.toFloat(),
            )

            // Draw a guide that shows the edges of the current body element space.
            bodyImage.drawRect(BODY_ELEM_GUIDE_COLOR, x, y, areaWidth, bodyLineHeight.toFloat(), isGuide = true)

            if (idx != bodyLine.lastIndex) {
                // Advance to the separator.
                x += areaWidth
                // Draw the separator.
                if (sepStr.isNotEmpty())
                    bodyImage.drawJustifiedString(
                        bodyFont, null, sepStr, HJustify.CENTER, block.style.bodyLayoutElemVJustify, x, y,
                        horGap, bodyLineHeight.toFloat()
                    )
                // Advance to the next element on the line.
                x += horGap
            }
        }

        // Advance to the next body line.
        y += bodyLineHeight + block.style.bodyLayoutLineGapPx
    }

    // Draw guides that show the body's left an right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0f, 0f, 0f, bodyImage.height, isGuide = true)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0f, bodyImageWidth, bodyImage.height, isGuide = true)

    return bodyImage
}


private inline fun <E> partitionIntoLines(
    list: List<E>,
    maxWidth: Float,
    minSepWidth: Float,
    getWidth: (E) -> Float
): List<List<E>> {
    val queue: Queue<E> = LinkedList(list)
    val lines = mutableListOf<MutableList<E>>(mutableListOf())

    var x = -minSepWidth
    while (queue.isNotEmpty()) {
        val elem = queue.remove()
        val elemWidth = getWidth(elem)
        x += minSepWidth + elemWidth
        if (x <= maxWidth) {
            // Case 1: The element still fits on the current line.
            lines.last().add(elem)
        } else {
            // Case 2: The element does not fit on the current line. Start a new line.
            lines.add(mutableListOf(elem))
            x = elemWidth
        }
    }

    return lines
}


private fun BodyElement.getWidth(bodyFont: RichFont): Int = when (this) {
    is BodyElement.Str -> bodyFont.metrics.stringWidth(str)
    is BodyElement.Pic -> when (pic) {
        is Picture.Raster -> pic.img.width
    }
}

private fun BodyElement.getHeight(bodyFont: RichFont): Int = when (this) {
    is BodyElement.Str -> bodyFont.spec.heightPx
    is BodyElement.Pic -> when (pic) {
        is Picture.Raster -> pic.img.height
    }
}


private fun DeferredImage.drawJustifiedBodyElem(
    elem: BodyElement, bodyFont: RichFont, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Float, areaWidth: Float, areaHeight: Float
) = when (elem) {
    is BodyElement.Str ->
        drawJustifiedString(bodyFont, null, elem.str, hJustify, vJustify, areaX, areaY, areaWidth, areaHeight)
    is BodyElement.Pic -> when (elem.pic) {
        is Picture.Raster ->
            drawJustified(
                hJustify, vJustify, areaX, areaY, areaWidth, areaHeight, elem.pic.img.width.toFloat(),
                elem.pic.img.height.toFloat()
            ) { objX, objY -> drawBufferedImage(elem.pic.img, objX, objY, elem.pic.scaling) }
    }
}
