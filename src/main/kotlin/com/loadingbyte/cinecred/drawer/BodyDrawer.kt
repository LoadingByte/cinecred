package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.common.REF_FRC
import com.loadingbyte.cinecred.common.Y
import com.loadingbyte.cinecred.common.Y.Companion.plus
import com.loadingbyte.cinecred.common.Y.Companion.toElasticY
import com.loadingbyte.cinecred.common.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BodyElementBoxConform.*
import java.awt.font.LineBreakMeasurer
import java.util.*
import kotlin.math.max


class DrawnBody(val defImage: DeferredImage, val firstRowHeight: Float, val lastRowHeight: Float)


fun drawBodyImagesWithGridBodyLayout(
    textCtx: TextContext,
    blocks: List<Block>
): Map<Block, DrawnBody> {
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
    val numCols = style.gridElemHJustifyPerCol.size
    val bodyPartitions = blocks.associateWith { block ->
        partitionIntoCols(block.body, numCols, style.gridFillingOrder)
    }
    val numRows = bodyPartitions.mapValues { (_, cols) -> cols.maxOf { it.size } }

    fun independentColWidths() = List(numCols) { colIdx ->
        bodyPartitions.values.maxOf { cols ->
            cols[colIdx].maxOfOrNull { bodyElem -> bodyElem.getWidth(textCtx) } ?: 0f
        }
    }

    fun conformedColWidth() =
        blocks.maxOf { block ->
            block.body.maxOf { bodyElem -> bodyElem.getWidth(textCtx) }
        }

    fun independentRowHeights() = blocks.associateWith { block ->
        List(numRows.getValue(block)) { rowIdx ->
            bodyPartitions.getValue(block).maxOf { col ->
                col.getOrNull(rowIdx)?.getHeight() ?: 0f
            }
        }
    }

    fun conformedRowHeight() =
        blocks.maxOf { block ->
            block.body.maxOf { bodyElem -> bodyElem.getHeight() }
        }

    fun forEachCol(width: Float) = List(numCols) { width }
    fun forEachRow(height: Float) = blocks.associateWith { block -> List(numRows.getValue(block)) { height } }

    val colWidths: List<Float>
    val rowHeights: Map<Block, List<Float>>
    when (style.gridElemBoxConform) {
        NOTHING -> {
            colWidths = independentColWidths()
            rowHeights = independentRowHeights()
        }
        WIDTH -> {
            colWidths = forEachCol(conformedColWidth())
            rowHeights = independentRowHeights()
        }
        HEIGHT -> {
            colWidths = independentColWidths()
            rowHeights = forEachRow(conformedRowHeight())
        }
        WIDTH_AND_HEIGHT -> {
            colWidths = forEachCol(conformedColWidth())
            rowHeights = forEachRow(conformedRowHeight())
        }
        SQUARE -> {
            val size = max(conformedColWidth(), conformedRowHeight())
            colWidths = forEachCol(size)
            rowHeights = forEachRow(size)
        }
    }

    val bodyImageWidth = (numCols - 1) * style.gridColGapPx + colWidths.sum()

    // Step 2:
    // Draw a deferred image for the body of each block.
    return blocks.associateWith { block ->
        // If there are no columns, there is no content, so we can just return an empty image.
        if (numCols == 0)
            return@associateWith DrawnBody(DeferredImage(), 0f, 0f)

        val blockRowHeights = rowHeights.getValue(block)
        val blockCols = bodyPartitions.getValue(block)

        val bodyImage = DeferredImage(
            width = bodyImageWidth,
            height = blockRowHeights.sum() + ((numRows.getValue(block) - 1) * style.gridRowGapPx).toElasticY()
        )

        var x = 0f
        for ((col, justifyCol, colWidth) in zip(blockCols, style.gridElemHJustifyPerCol, colWidths)) {
            var y = 0f.toY()
            for ((bodyElem, rowHeight) in col.zip(blockRowHeights)) {
                bodyImage.drawJustifiedBodyElem(
                    textCtx, bodyElem, justifyCol, style.gridElemVJustify, x, y, colWidth, rowHeight.toY()
                )
                // Draw a guide that shows the edges of the body element space.
                bodyImage.drawRect(BODY_ELEM_GUIDE_COLOR, x, y, colWidth, rowHeight.toY(), layer = GUIDES)
                // Advance to the next line in the current column.
                y += rowHeight + style.gridRowGapPx.toElasticY()
            }
            // Advance to the next column.
            x += colWidth + style.gridColGapPx
        }

        DrawnBody(bodyImage, blockRowHeights.first(), blockRowHeights.last())
    }
}


private fun <E> partitionIntoCols(list: List<E>, numCols: Int, order: GridFillingOrder): List<List<E>> {
    // First fill the columns irrespective of left-to-right / right-to-left.
    val cols = when (order) {
        GridFillingOrder.L2R_T2B, GridFillingOrder.R2L_T2B -> {
            val cols = (0 until numCols).map { mutableListOf<E>() }
            for ((idx, elem) in list.withIndex())
                cols[idx % cols.size].add(elem)
            cols
        }
        GridFillingOrder.T2B_L2R, GridFillingOrder.T2B_R2L -> {
            val numRows = (list.size + (numCols - 1)) / numCols  // equivalent to "ceil(list.size / numCols)"
            List(numCols) { colIdx ->
                list.subList(
                    (colIdx * numRows).coerceAtMost(list.size),
                    ((colIdx + 1) * numRows).coerceAtMost(list.size)
                )
            }
        }
    }

    // Then, when the direction is right-to-left, just reverse the column order.
    return when (order) {
        GridFillingOrder.L2R_T2B, GridFillingOrder.T2B_L2R -> cols
        GridFillingOrder.R2L_T2B, GridFillingOrder.T2B_R2L -> cols.asReversed()
    }
}


fun drawBodyImageWithFlowBodyLayout(
    textCtx: TextContext,
    block: Block
): DrawnBody {
    val style = block.style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow body layout".
    require(style.bodyLayout == BodyLayout.FLOW)

    val horGap = style.flowHGapPx

    val bodyLetterStyle = textCtx.fonts.keys.find { it.name == style.bodyLetterStyleName } ?: PLACEHOLDER_LETTER_STYLE
    val sepStr = style.flowSeparator
    val sepStyledStr = if (sepStr.isBlank()) null else listOf(Pair(sepStr, bodyLetterStyle))

    // Find the maximum width resp. height over all body elements.
    val maxElemWidth = block.body.maxOf { bodyElem -> bodyElem.getWidth(textCtx) }
    val maxElemHeight = block.body.maxOf { bodyElem -> bodyElem.getHeight() }
    val maxElemSideLength = max(maxElemWidth, maxElemHeight)

    // The width of the body image must be at least the width of the widest body element, because otherwise,
    // that element could not even fit into one line of the body.
    val bodyImageWidth = style.flowLineWidthPx
        .coerceAtLeast(if (style.flowElemBoxConform == SQUARE) maxElemSideLength else maxElemWidth)

    // Determine which body elements should lie on which line. We use the simplest possible
    // text flow algorithm for this.
    val lines = partitionIntoLines(block.body, style.flowDirection, bodyImageWidth, horGap) { bodyElem ->
        when (style.flowElemBoxConform) {
            NOTHING, HEIGHT -> bodyElem.getWidth(textCtx)
            WIDTH, WIDTH_AND_HEIGHT -> maxElemWidth
            SQUARE -> maxElemSideLength
        }
    }

    // We will later use this function to find the height of a specific line.
    fun getLineHeight(line: List<BodyElement>) =
        when (style.flowElemBoxConform) {
            NOTHING, WIDTH -> line.maxOf { bodyElem -> bodyElem.getHeight() }
            HEIGHT, WIDTH_AND_HEIGHT -> maxElemHeight
            SQUARE -> maxElemSideLength
        }

    // Start drawing the actual image.
    val bodyImage = DeferredImage(width = bodyImageWidth)

    var y = 0f.toY()
    for ((lineIdx, line) in lines.withIndex()) {
        // Determine the justification of the current line.
        val curLineHJustify = style.flowLineHJustify.toSingleLineHJustify(lastLine = lineIdx == lines.lastIndex)

        // Determine the width of all rigid elements in the line, that is, the total width of all body elements
        // and separator strings.
        val totalRigidWidth = when (style.flowElemBoxConform) {
            NOTHING, HEIGHT -> line.sumByFloat { bodyElem -> bodyElem.getWidth(textCtx) }
            WIDTH, WIDTH_AND_HEIGHT -> line.size * maxElemWidth
            SQUARE -> line.size * maxElemSideLength
        }

        // If the body uses full justification, we use this "glue" to adjust the horizontal gap around the separator
        // such that the line fills the whole width of the body image.
        val horGlue = if (curLineHJustify != SingleLineHJustify.FULL) 0f else
            (bodyImageWidth - totalRigidWidth) / (line.size - 1) - horGap

        // Find the filled width as well as the height of the current line.
        val lineWidth = totalRigidWidth + (line.size - 1) * (horGap + horGlue)
        val lineHeight = getLineHeight(line)

        // Find the x coordinate of the leftmost body element depending on the justification.
        // For left or full justification, we start at the leftmost position.
        // For center or right justification, we start such that the line is centered or right-justified.
        var x = when (curLineHJustify) {
            SingleLineHJustify.LEFT, SingleLineHJustify.FULL -> 0f
            SingleLineHJustify.CENTER -> (bodyImageWidth - lineWidth) / 2f
            SingleLineHJustify.RIGHT -> bodyImageWidth - lineWidth
        }

        // Actually draw the line using the measurements from above.
        for ((bodyElemIdx, bodyElem) in line.withIndex()) {
            val areaWidth = when (style.flowElemBoxConform) {
                NOTHING, HEIGHT -> bodyElem.getWidth(textCtx)
                WIDTH, WIDTH_AND_HEIGHT -> maxElemWidth
                SQUARE -> maxElemSideLength
            }

            // Draw the current body element.
            bodyImage.drawJustifiedBodyElem(
                textCtx, bodyElem, style.flowElemHJustify, style.flowElemVJustify, x, y,
                areaWidth, lineHeight.toY(),
            )

            // Draw a guide that shows the edges of the current body element space.
            bodyImage.drawRect(BODY_ELEM_GUIDE_COLOR, x, y, areaWidth, lineHeight.toY(), layer = GUIDES)

            if (bodyElemIdx != line.lastIndex) {
                // Advance to the separator.
                x += areaWidth
                // Draw the separator.
                if (sepStyledStr != null)
                    bodyImage.drawJustifiedStyledString(
                        textCtx, sepStyledStr, HJustify.CENTER, style.flowElemVJustify, x, y,
                        horGap + horGlue, lineHeight.toY()
                    )
                // Advance to the next element on the line.
                x += horGap + horGlue
            }
        }

        // Advance to the next line.
        y += lineHeight + style.flowLineGapPx.toElasticY()
    }
    y -= style.flowLineGapPx.toElasticY()

    // Set the height of the body image.
    bodyImage.height = y

    // Draw guides that show the body's left an right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0f, 0f.toY(), 0f, y, layer = GUIDES)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0f.toY(), bodyImageWidth, y, layer = GUIDES)

    return DrawnBody(bodyImage, getLineHeight(lines.first()), getLineHeight(lines.last()))
}


private inline fun <E> partitionIntoLines(
    list: List<E>,
    direction: FlowDirection,
    maxWidth: Float,
    minSepWidth: Float,
    getWidth: (E) -> Float
): List<List<E>> {
    if (list.isEmpty())
        return emptyList()

    val queue: Queue<E> = LinkedList(list)
    val lines = ArrayList<MutableList<E>>()
    var x: Float

    // We extracted this code from the loop below to ensure that the first line list is never an empty list,
    // even if there are floating point inaccuracies.
    val firstElem = queue.remove()
    x = getWidth(firstElem)
    lines.add(arrayListOf(firstElem))

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

    // When the direction is reversed, just reverse the element order in each line.
    if (direction == FlowDirection.R2L)
        for (line in lines)
            line.reverse()

    return lines
}


private inline fun <E> Iterable<E>.sumByFloat(selector: (E) -> Float): Float {
    var sum = 0f
    for (elem in this) {
        sum += selector(elem)
    }
    return sum
}


fun drawBodyImageWithParagraphsBodyLayout(
    textCtx: TextContext,
    block: Block
): DrawnBody {
    val style = block.style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the
    // "paragraphs body layout".
    require(style.bodyLayout == BodyLayout.PARAGRAPHS)

    val bodyImageWidth = style.paragraphsLineWidthPx

    val bodyImage = DeferredImage(width = bodyImageWidth)

    // Use this to remember the height of the first and the last body row.
    // Later return these two values alongside the created body image.
    var firstRowHeight = 0f
    var lastRowHeight = 0f
    fun recordRowHeight(h: Float) {
        if (firstRowHeight == 0f)
            firstRowHeight = h
        lastRowHeight = h
    }

    var y = 0f.toY()
    for (bodyElem in block.body) {
        // Case 1: The body element is a string. Determine line breaks and draw it as a paragraph.
        if (bodyElem is BodyElement.Str) {
            // Employ a LineBreakMeasurer to find the best spots to insert a newline.
            val attrCharIter = bodyElem.str.toAttributedString(textCtx).iterator
            val lineMeasurer = LineBreakMeasurer(attrCharIter, REF_FRC)
            while (lineMeasurer.position < attrCharIter.endIndex) {
                val lineStartPos = lineMeasurer.position
                val lineEndPos = lineMeasurer.nextOffset(bodyImageWidth)
                lineMeasurer.position = lineEndPos

                val lineStyledStr = bodyElem.str.substring(lineStartPos, lineEndPos).trim()

                val isLastLine = lineEndPos == attrCharIter.endIndex
                val curLineHJustify = style.paragraphsLineHJustify.toSingleLineHJustify(isLastLine)

                // Case 1a: Full justification.
                if (curLineHJustify == SingleLineHJustify.FULL)
                    bodyImage.drawStyledString(textCtx, lineStyledStr, 0f, y, justificationWidth = bodyImageWidth)
                // Case 1b: Left, center, or right justification.
                else {
                    val hJustify = curLineHJustify.toHJustify()
                    bodyImage.drawJustifiedStyledString(textCtx, lineStyledStr, hJustify, 0f, y, bodyImageWidth)
                }

                // Advance to the next line.
                val lineHeight = lineStyledStr.getHeight().toFloat()
                y += lineHeight + style.paragraphsLineGapPx.toElasticY()

                recordRowHeight(lineHeight)
            }
            y -= style.paragraphsLineGapPx.toElasticY()
        }
        // Case 2: The body element is not a string. Just draw it regularly.
        else {
            val hJustify = style.paragraphsLineHJustify.toSingleLineHJustify(lastLine = false).toHJustify()
            bodyImage.drawJustifiedBodyElem(textCtx, bodyElem, hJustify, VJustify.TOP, 0f, y, bodyImageWidth, 0f.toY())
            val bodyElemHeight = bodyElem.getHeight()
            y += bodyElemHeight
            recordRowHeight(bodyElemHeight)
        }

        // Advance to the next paragraph.
        y += style.paragraphsParaGapPx.toElasticY()
    }
    y -= style.paragraphsParaGapPx.toElasticY()

    // Set the height of the body image.
    bodyImage.height = y

    // Draw guides that show the body's left an right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0f, 0f.toY(), 0f, y, layer = GUIDES)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0f.toY(), bodyImageWidth, y, layer = GUIDES)

    return DrawnBody(bodyImage, firstRowHeight, lastRowHeight)
}


private fun BodyElement.getWidth(textCtx: TextContext): Float = when (this) {
    is BodyElement.Str -> str.getWidth(textCtx)
    is BodyElement.Pic -> pic.width
}

private fun BodyElement.getHeight(): Float = when (this) {
    is BodyElement.Str -> str.getHeight().toFloat()
    is BodyElement.Pic -> pic.height
}


private fun DeferredImage.drawJustifiedBodyElem(
    textCtx: TextContext,
    elem: BodyElement, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Y, areaWidth: Float, areaHeight: Y
) = when (elem) {
    is BodyElement.Str ->
        drawJustifiedStyledString(textCtx, elem.str, hJustify, vJustify, areaX, areaY, areaWidth, areaHeight)
    is BodyElement.Pic ->
        drawJustified(
            hJustify, vJustify, areaX, areaY, areaWidth, areaHeight, elem.pic.width, elem.pic.height.toY()
        ) { objX, objY -> drawPicture(elem.pic, objX, objY) }
}


private enum class SingleLineHJustify { LEFT, CENTER, RIGHT, FULL }

private fun LineHJustify.toSingleLineHJustify(lastLine: Boolean) = when {
    this == LineHJustify.LEFT || lastLine && this == LineHJustify.FULL_LAST_LEFT -> SingleLineHJustify.LEFT
    this == LineHJustify.CENTER || lastLine && this == LineHJustify.FULL_LAST_CENTER -> SingleLineHJustify.CENTER
    this == LineHJustify.RIGHT || lastLine && this == LineHJustify.FULL_LAST_RIGHT -> SingleLineHJustify.RIGHT
    else -> SingleLineHJustify.FULL
}

private fun SingleLineHJustify.toHJustify() = when (this) {
    SingleLineHJustify.LEFT -> HJustify.LEFT
    SingleLineHJustify.CENTER, SingleLineHJustify.FULL -> HJustify.CENTER
    SingleLineHJustify.RIGHT -> HJustify.RIGHT
}


private fun <X, Y, Z> zip(xs: Iterable<X>, ys: Iterable<Y>, zs: Iterable<Z>): Sequence<Triple<X, Y, Z>> {
    val xsIter = xs.iterator()
    val ysIter = ys.iterator()
    val zsIter = zs.iterator()
    return generateSequence {
        if (xsIter.hasNext() && ysIter.hasNext() && zsIter.hasNext())
            Triple(xsIter.next(), ysIter.next(), zsIter.next())
        else null
    }
}
