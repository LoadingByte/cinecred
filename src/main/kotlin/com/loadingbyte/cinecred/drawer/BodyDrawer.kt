package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.ceilDiv
import com.loadingbyte.cinecred.common.maxOfOr
import com.loadingbyte.cinecred.common.sumOf
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.plus
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.GridColUnderoccupancy.*
import com.loadingbyte.cinecred.project.GridStructure.EQUAL_WIDTH_COLS
import com.loadingbyte.cinecred.project.GridStructure.SQUARE_CELLS
import com.loadingbyte.cinecred.project.MatchExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.MatchExtent.WITHIN_BLOCK
import java.util.*
import kotlin.math.max
import kotlin.math.min


class DrawnBody(val defImage: DeferredImage, val numRows: Int, val firstRowHeight: Double, val lastRowHeight: Double)


fun drawBodies(
    contentStyles: List<ContentStyle>,
    letterStyles: List<LetterStyle>,
    textCtx: TextContext,
    blocks: List<Block>
): Map<Block, DrawnBody> {
    val drawnBodies = HashMap<Block, DrawnBody>(2 * blocks.size)

    // Draw body images for blocks with the "grid" or "flow" body layout.
    drawBodyImagesWithGridBodyLayout(
        drawnBodies, contentStyles, textCtx,
        blocks.filter { block -> block.style.bodyLayout == GRID }
    )
    drawBodyImagesWithFlowBodyLayout(
        drawnBodies, contentStyles, letterStyles, textCtx,
        blocks.filter { block -> block.style.bodyLayout == FLOW }
    )

    // Draw body images for blocks with the "paragraphs" body layout.
    for (block in blocks)
        if (block.style.bodyLayout == PARAGRAPHS)
            drawnBodies[block] = drawBodyImageWithParagraphsBodyLayout(textCtx, block)

    return drawnBodies
}


private fun drawBodyImagesWithGridBodyLayout(
    out: MutableMap<Block, DrawnBody>,
    cs: List<ContentStyle>,
    textCtx: TextContext,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "grid" body layout.
    require(blocks.all { it.style.bodyLayout == GRID })

    // Flow each block's body elements into the grid configured for that block.
    val colsPerBlock = blocks.associateWith { block ->
        flowIntoGridCols(
            block.body, numCols = block.style.gridCellHJustifyPerCol.size,
            block.style.gridFillingOrder, block.style.gridFillingBalanced
        )
    }

    // Grid blocks are free to potentially harmonize their grid column widths and grid row height, permitting the user
    // to create neatly aligned tabular layouts that span multiple blocks. For both "extents" that can be harmonized
    // (i.e., col widths and row height), find which styles should harmonize together.
    val matchColWidthsPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::gridMatchColWidthsAcrossStyles) {
        bodyLayout == GRID && !gridForceColWidthPx.isActive && gridMatchColWidths == ACROSS_BLOCKS
    }
    val matchRowHeightPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::gridMatchRowHeightAcrossStyles) {
        bodyLayout == GRID && !gridForceRowHeightPx.isActive && gridMatchRowHeight == ACROSS_BLOCKS
    }

    // Determine the groups of blocks which should share the same column widths (for simplicity of implementation, this
    // also includes ungrouped blocks whose grid structure mandates square cells, and ungrouped blocks with forced
    // column width), and then find those shared widths.
    fun colWidth(col: List<BodyElement>) = col.maxOfOr(0.0) { bodyElem -> bodyElem.getWidth(textCtx) }
    val sharedColWidthsPerBlock: Map<Block, Array<Extent>> = matchExtent(
        blocks, matchColWidthsPartitionIds,
        matchWithinBlock = {
            gridStructure == EQUAL_WIDTH_COLS || gridStructure == SQUARE_CELLS || gridForceColWidthPx.isActive
        },
        sharedBlockExtent = { block ->
            val force = block.style.gridForceColWidthPx
            val cols = colsPerBlock.getValue(block)
            Array(cols.size) { colIdx -> Extent(force.orElse { colWidth(cols[colIdx]) }) }
        },
        sharedGroupExtent = { group ->
            // This piece of code takes a group of blocks and produces the list of shared column widths.
            val colWidths = Array(group.maxOf { block -> colsPerBlock.getValue(block).size }) { Extent(0.0) }
            for (block in group) {
                val cols = colsPerBlock.getValue(block)
                // If the blocks in the group have differing amounts of columns, the user can decide whether he'd like
                // to match each block with the leftmost or rightmost shared columns.
                val offset = if (block.style.gridMatchColUnderoccupancy.alignRight) colWidths.size - cols.size else 0
                for ((colIdx, col) in cols.withIndex()) {
                    val extent = colWidths[offset + colIdx]
                    extent.value = max(extent.value, colWidth(col))
                }
            }
            colWidths
        }
    )

    // Determine the blocks which have uniform row height, optionally shared across multiple blocks, and then find
    // those shared heights.
    fun maxElemHeight(block: Block) = block.body.maxOf { bodyElem -> bodyElem.getHeight() }
    val sharedRowHeightPerBlock: Map<Block, Extent> = matchExtent(
        blocks, matchRowHeightPartitionIds,
        matchWithinBlock = {
            gridForceRowHeightPx.isActive || gridMatchRowHeight == WITHIN_BLOCK || gridStructure == SQUARE_CELLS
        },
        sharedBlockExtent = { block -> Extent((block.style.gridForceRowHeightPx.orElse { maxElemHeight(block) })) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxElemHeight)) }
    )

    // Harmonize the column widths of blocks configured as "equal width cols"
    // Harmonize the column widths and row height of blocks configured as "square cells".
    // For an example of the first setting, consider that the user might have chosen to share column widths in order
    // to obtain a layout like the following, where the head line (in the example 'Extras') is nice and centered:
    //                 Extras
    //     |Nathanial A.|  |   Tim C.   |
    //     | Richard B. |  |  Sarah D.  |
    for (block in blocks)
        if (block.style.gridStructure.let { it == EQUAL_WIDTH_COLS || it == SQUARE_CELLS }) {
            val numCols = colsPerBlock.getValue(block).size
            if (numCols == 0)
                continue
            val colWidths = sharedColWidthsPerBlock.getValue(block)
            val startIdx = if (block.style.gridMatchColUnderoccupancy.alignRight) colWidths.size - numCols else 0
            val endIdx = startIdx + numCols
            if (block.style.gridStructure == EQUAL_WIDTH_COLS) {
                val startColWidth = colWidths[startIdx]
                for (idx in startIdx + 1..<endIdx)
                    startColWidth.link(colWidths[idx])
            } else if (block.style.gridStructure == SQUARE_CELLS) {
                val rowHeight = sharedRowHeightPerBlock.getValue(block)
                for (idx in startIdx..<endIdx)
                    rowHeight.link(colWidths[idx])
            }
        }

    // Draw a deferred image for the body of each block.
    blocks.associateWithTo(out) { block ->
        drawBodyImageWithGridBodyLayout(
            textCtx, block, colsPerBlock.getValue(block), sharedColWidthsPerBlock[block], sharedRowHeightPerBlock[block]
        )
    }
}


private fun drawBodyImageWithGridBodyLayout(
    textCtx: TextContext,
    block: Block,
    cols: List<List<BodyElement>>,
    sharedColWidths: Array<Extent>?,
    sharedRowHeight: Extent?
): DrawnBody {
    val style = block.style
    val unocc = style.gridMatchColUnderoccupancy

    val numCols = cols.size
    val numRows = cols.maxOf { col -> col.size }
    val rowHeights = DoubleArray(numRows) { rowIdx ->
        sharedRowHeight?.value ?: cols.maxOf { col -> if (rowIdx < col.size) col[rowIdx].getHeight() else 0.0 }
    }

    val bodyImage = DeferredImage(height = ((numRows - 1) * style.gridRowGapPx).toElasticY() + rowHeights.sum())

    // Draw each column. If the block shares the same column widths as other blocks and the user requested to retain
    // unoccupied columns, do that by either prepending negative column indices or appending too big ones. Only empty
    // guides will be drawn for these out-of-bounds column indices.
    var x = 0.0
    val startColIdx = if (sharedColWidths != null && unocc == RIGHT_RETAIN) numCols - sharedColWidths.size else 0
    val endColIdx = if (sharedColWidths != null && unocc == LEFT_RETAIN) sharedColWidths.size else numCols
    for (colIdx in startColIdx..<endColIdx) {
        // Either get the column's shared width, or compute it now if the block's column widths are not shared.
        val colWidth = when (sharedColWidths) {
            null -> cols[colIdx].maxOfOr(0.0) { bodyElem -> bodyElem.getWidth(textCtx) }
            else -> sharedColWidths[colIdx + if (unocc.alignRight) sharedColWidths.size - numCols else 0].value
        }
        // Draw each row cell in the column.
        var y = 0.0.toY()
        for ((rowIdx, rowHeight) in rowHeights.withIndex()) {
            cols.getOrNull(colIdx)?.getOrNull(rowIdx)?.let { bodyElem ->
                bodyImage.drawJustifiedBodyElem(
                    textCtx, bodyElem, style.gridCellHJustifyPerCol[colIdx], style.gridCellVJustify,
                    x, y, colWidth, rowHeight.toY()
                )
            }
            // Draw a guide that shows the edges of the body cell.
            bodyImage.drawRect(BODY_CELL_GUIDE_COLOR, x, y, colWidth, rowHeight.toY(), layer = GUIDES)
            // Advance to the next row in the current column.
            y += rowHeight + style.gridRowGapPx.toElasticY()
        }
        // Advance to the next column.
        x += colWidth
        if (colIdx != endColIdx - 1)
            x += style.gridColGapPx
    }

    // Set the width of the body image.
    bodyImage.width = x

    return DrawnBody(bodyImage, rowHeights.size, rowHeights.first(), rowHeights.last())
}


private val GridColUnderoccupancy.alignRight
    get() = when (this) {
        RIGHT_OMIT, RIGHT_RETAIN -> true
        LEFT_OMIT, LEFT_RETAIN -> false
    }


private fun <E> flowIntoGridCols(
    list: List<E>,
    numCols: Int,
    order: GridFillingOrder,
    balanced: Boolean
): List<List<E>> {
    val numRows = ceilDiv(list.size, numCols)
    // First fill the columns irrespective of left-to-right / right-to-left.
    val cols = when (order) {
        GridFillingOrder.L2R_T2B, GridFillingOrder.R2L_T2B -> {
            val cols = List(numCols) { ArrayList<E>(numRows) }
            var idx = 0
            repeat(numRows) {
                val remaining = list.size - idx
                if (!balanced || remaining >= numCols)
                    for (colIdx in 0..<min(numCols, remaining))
                        cols[colIdx].add(list[idx++])
                else {
                    val skipColIdx = if (numCols % 2 == 1 && remaining % 2 == 0) numCols / 2 else -1
                    var colIdx = (numCols - remaining) / 2
                    while (idx < list.size) {
                        if (colIdx != skipColIdx)
                            cols[colIdx].add(list[idx++])
                        colIdx++
                    }
                }
            }
            cols
        }
        GridFillingOrder.T2B_L2R, GridFillingOrder.T2B_R2L -> {
            // lRow = last row
            val lRowElems = list.size % numCols
            val lRowStart = (numCols - lRowElems) / 2
            val lRowSkip = if (numCols % 2 == 1 && lRowElems % 2 == 0) numCols / 2 else -1
            val lRowStop = lRowStart + lRowElems + if (lRowSkip == -1) 0 else 1
            var idx = 0
            List(numCols) { colIdx ->
                val useLastRow = !balanced || lRowElems == 0 || colIdx in lRowStart..<lRowStop && colIdx != lRowSkip
                val take = numRows - if (useLastRow) 0 else 1
                val col = if (idx < list.size) list.subList(idx, min(idx + take, list.size)) else emptyList()
                idx += take
                col
            }
        }
    }

    // Then, when the direction is right-to-left, just reverse the column order.
    return when (order) {
        GridFillingOrder.L2R_T2B, GridFillingOrder.T2B_L2R -> cols
        GridFillingOrder.R2L_T2B, GridFillingOrder.T2B_R2L -> cols.asReversed()
    }
}


private fun drawBodyImagesWithFlowBodyLayout(
    out: MutableMap<Block, DrawnBody>,
    cs: List<ContentStyle>,
    letterStyles: List<LetterStyle>,
    textCtx: TextContext,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow" body layout.
    require(blocks.all { it.style.bodyLayout == FLOW })

    // Flow blocks are free to potentially harmonize their cell width and height. This allows the user to create neatly
    // aligned grid-like yet dynamic layouts that can even span multiple blocks. For both "extents" that can be
    // harmonized (i.e., cell width and height), find which styles should harmonize together.
    val matchCellWidthPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::flowMatchCellWidthAcrossStyles) {
        bodyLayout == FLOW && !flowForceCellWidthPx.isActive && flowMatchCellWidth == ACROSS_BLOCKS
    }
    val matchCellHeightPartitionIds = partitionToTransitiveClosures(cs, ContentStyle::flowMatchCellHeightAcrossStyles) {
        bodyLayout == FLOW && !flowForceCellHeightPx.isActive && flowMatchCellHeight == ACROSS_BLOCKS
    }

    // Determine the blocks which have uniform cell width or height, optionally shared across multiple blocks, and then
    // find those shared widths or heights.
    fun maxElemWidth(block: Block) = block.body.maxOf { bodyElem -> bodyElem.getWidth(textCtx) }
    fun maxElemHeight(block: Block) = block.body.maxOf { bodyElem -> bodyElem.getHeight() }
    val sharedCellWidthPerBlock: Map<Block, Extent> = matchExtent(
        blocks, matchCellWidthPartitionIds,
        matchWithinBlock = { flowForceCellWidthPx.isActive || flowMatchCellWidth == WITHIN_BLOCK || flowSquareCells },
        sharedBlockExtent = { block -> Extent(block.style.flowForceCellWidthPx.orElse { maxElemWidth(block) }) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxElemWidth)) }
    )
    val sharedCellHeightPerBlock: Map<Block, Extent> = matchExtent(
        blocks, matchCellHeightPartitionIds,
        matchWithinBlock = { flowForceCellHeightPx.isActive || flowMatchCellHeight == WITHIN_BLOCK || flowSquareCells },
        sharedBlockExtent = { block -> Extent(block.style.flowForceCellHeightPx.orElse { maxElemHeight(block) }) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxElemHeight)) }
    )

    // Harmonize the cell width and height of square cells.
    for (block in blocks)
        if (block.style.flowSquareCells)
            sharedCellWidthPerBlock.getValue(block).link(sharedCellHeightPerBlock.getValue(block))

    // Draw a deferred image for the body of each block.
    blocks.associateWithTo(out) { block ->
        drawBodyImageWithFlowBodyLayout(
            letterStyles, textCtx, block, sharedCellWidthPerBlock[block], sharedCellHeightPerBlock[block]
        )
    }
}


private fun drawBodyImageWithFlowBodyLayout(
    letterStyles: List<LetterStyle>,
    textCtx: TextContext,
    block: Block,
    sharedCellWidth: Extent?,
    sharedCellHeight: Extent?
): DrawnBody {
    val style = block.style
    val horGap = style.flowHGapPx

    val bodyLetterStyle = letterStyles.find { it.name == style.bodyLetterStyleName } ?: PLACEHOLDER_LETTER_STYLE
    val sepStr = style.flowSeparator
    val sepFmtStr = if (sepStr.isBlank()) null else listOf(Pair(sepStr, bodyLetterStyle)).formatted(textCtx)

    // The width of the body image must be at least the width of the widest body element, because otherwise,
    // that element could not even fit into one line of the body.
    val bodyImageWidth = style.flowLineWidthPx
        .coerceAtLeast(sharedCellWidth?.value ?: block.body.maxOf { bodyElem -> bodyElem.getWidth(textCtx) })

    // Determine which body elements should lie on which line. We use the simplest possible
    // text flow algorithm for this.
    val lines = flowIntoLines(block.body, style.flowDirection, bodyImageWidth, horGap) { bodyElem ->
        sharedCellWidth?.value ?: bodyElem.getWidth(textCtx)
    }

    // We will later use this function to find the height of a specific line.
    fun getLineHeight(line: List<BodyElement>) =
        sharedCellHeight?.value ?: line.maxOf { bodyElem -> bodyElem.getHeight() }

    // Start drawing the actual image.
    val bodyImage = DeferredImage(width = bodyImageWidth)

    var y = 0.0.toY()
    for ((lineIdx, line) in lines.withIndex()) {
        // Determine the justification of the current line.
        val curLineHJustify = style.flowLineHJustify.toSingleLineHJustify(lastLine = lineIdx == lines.lastIndex)

        // Determine the width of all rigid elements in the line, that is, the total width of all body elements
        // and separator strings.
        val totalRigidWidth =
            if (sharedCellWidth != null) line.size * sharedCellWidth.value
            else line.sumOf { bodyElem -> bodyElem.getWidth(textCtx) }

        // If the body uses full justification, we use this "glue" to adjust the horizontal gap around the separator
        // such that the line fills the whole width of the body image.
        val horGlue = if (curLineHJustify != SingleLineHJustify.FULL) 0.0 else
            (bodyImageWidth - totalRigidWidth) / (line.size - 1) - horGap

        // Find the filled width as well as the height of the current line.
        val lineWidth = totalRigidWidth + (line.size - 1) * (horGap + horGlue)
        val lineHeight = getLineHeight(line)

        // Find the x coordinate of the leftmost body element depending on the justification.
        // For left or full justification, we start at the leftmost position.
        // For center or right justification, we start such that the line is centered or right-justified.
        var x = when (curLineHJustify) {
            SingleLineHJustify.LEFT, SingleLineHJustify.FULL -> 0.0
            SingleLineHJustify.CENTER -> (bodyImageWidth - lineWidth) / 2.0
            SingleLineHJustify.RIGHT -> bodyImageWidth - lineWidth
        }

        // Actually draw the line using the measurements from above.
        for ((bodyElemIdx, bodyElem) in line.withIndex()) {
            val areaWidth = sharedCellWidth?.value ?: bodyElem.getWidth(textCtx)

            // Draw the current body element.
            bodyImage.drawJustifiedBodyElem(
                textCtx, bodyElem, style.flowCellHJustify, style.flowCellVJustify, x, y,
                areaWidth, lineHeight.toY(),
            )

            // Draw a guide that shows the edges of the current body cell.
            bodyImage.drawRect(BODY_CELL_GUIDE_COLOR, x, y, areaWidth, lineHeight.toY(), layer = GUIDES)

            if (bodyElemIdx != line.lastIndex) {
                // Advance to the separator.
                x += areaWidth
                // Draw the separator.
                if (sepFmtStr != null)
                    bodyImage.drawJustifiedString(
                        sepFmtStr, HJustify.CENTER, style.flowCellVJustify, x, y,
                        horGap + horGlue, lineHeight.toY()
                    )
                // Advance to the next element on the line.
                x += horGap + horGlue
            }
        }

        // Advance to the next line.
        y += lineHeight
        if (lineIdx != lines.lastIndex)
            y += style.flowLineGapPx.toElasticY()
    }

    // Set the height of the body image.
    bodyImage.height = y

    // Draw guides that show the body's left and right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0.0, 0.0.toY(), 0.0, y, layer = GUIDES)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0.0.toY(), bodyImageWidth, y, layer = GUIDES)

    return DrawnBody(bodyImage, lines.size, getLineHeight(lines.first()), getLineHeight(lines.last()))
}


private inline fun <E> flowIntoLines(
    list: List<E>,
    direction: FlowDirection,
    maxWidth: Double,
    minSepWidth: Double,
    getWidth: (E) -> Double
): List<List<E>> {
    if (list.isEmpty())
        return emptyList()

    val queue: Queue<E> = ArrayDeque(list)
    val lines = ArrayList<MutableList<E>>()
    var x: Double

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
            lines.add(arrayListOf(elem))
            x = elemWidth
        }
    }

    // When the direction is reversed, just reverse the element order in each line.
    if (direction == FlowDirection.R2L)
        for (line in lines)
            line.reverse()

    return lines
}


private fun drawBodyImageWithParagraphsBodyLayout(
    textCtx: TextContext,
    block: Block
): DrawnBody {
    val style = block.style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the
    // "paragraphs" body layout.
    require(style.bodyLayout == PARAGRAPHS)

    val bodyImageWidth = style.paragraphsLineWidthPx

    val bodyImage = DeferredImage(width = bodyImageWidth)

    // Use this to remember the number of body rows and the height of the first and the last body row.
    // Later return these two values alongside the created body image.
    var numRows = 0
    var firstRowHeight = 0.0
    var lastRowHeight = 0.0
    fun recordRowHeight(h: Double) {
        numRows++
        if (firstRowHeight == 0.0)
            firstRowHeight = h
        lastRowHeight = h
    }

    var y = 0.0.toY()
    for (bodyElem in block.body) {
        // Case 1: The body element is a string. Determine line breaks and draw it as a paragraph.
        if (bodyElem is BodyElement.Str) {
            for (fmtStr in bodyElem.str.formatted(textCtx).split(LINE_DELIMITERS)) {
                val lineBreaks = fmtStr.breakLines(bodyImageWidth)
                for ((lineStartPos, lineEndPos) in lineBreaks.zipWithNext()) {
                    // Note: If the line contains only whitespace, this skips to the next line.
                    val lineFmtStr = fmtStr.sub(lineStartPos, lineEndPos)?.trim() ?: continue

                    val isLastLine = lineEndPos == fmtStr.string.length
                    val curLineHJustify = style.paragraphsLineHJustify.toSingleLineHJustify(isLastLine)

                    // Case 1a: Full justification.
                    if (curLineHJustify == SingleLineHJustify.FULL)
                        bodyImage.drawString(lineFmtStr.justify(bodyImageWidth), 0.0, y)
                    // Case 1b: Left, center, or right justification.
                    else {
                        val hJustify = curLineHJustify.toHJustify()
                        bodyImage.drawJustifiedString(lineFmtStr, hJustify, 0.0, y, bodyImageWidth)
                    }

                    // Advance to the next line.
                    val lineHeight = lineFmtStr.height
                    y += lineHeight + style.paragraphsLineGapPx.toElasticY()

                    recordRowHeight(lineHeight)
                }
            }
            y -= style.paragraphsLineGapPx.toElasticY()
        }
        // Case 2: The body element is not a string. Just draw it regularly.
        else {
            val hJustify = style.paragraphsLineHJustify.toSingleLineHJustify(lastLine = false).toHJustify()
            bodyImage.drawJustifiedBodyElem(
                textCtx, bodyElem, hJustify, VJustify.TOP, 0.0, y, bodyImageWidth, 0.0.toY()
            )
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

    // Draw guides that show the body's left and right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0.0, 0.0.toY(), 0.0, y, layer = GUIDES)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0.0.toY(), bodyImageWidth, y, layer = GUIDES)

    return DrawnBody(bodyImage, numRows, firstRowHeight, lastRowHeight)
}


private class Extent(value: Double) {

    private var group = Group(value)

    var value: Double
        get() = group.value
        set(value) {
            group.value = value
        }

    /** The [value] of both extents will initially be the max across them, and it will remain the same in the future. */
    fun link(other: Extent) {
        val group1 = this.group
        val group2 = other.group
        if (group1 === group2)
            return
        val max = max(value, other.value)
        val group1Extents = group1.extents
        val group2Extents = group2.extents
        if (group1Extents == null)
            if (group2Extents == null) {
                other.group = group1
                group1.value = max
                group1.extents = ArrayList<Extent>().apply { add(this@Extent); add(other) }
            } else {
                this.group = group2
                group2.value = max
                group2Extents.add(this)
            }
        else
            if (group2Extents == null) {
                other.group = group1
                group1.value = max
                group1Extents.add(other)
            } else if (group1Extents.size >= group2Extents.size) {
                for (extent in group2Extents)
                    extent.group = group1
                group1.value = max
                group1Extents.addAll(group2Extents)
            } else {
                for (extent in group1Extents)
                    extent.group = group2
                group2.value = max
                group2Extents.addAll(group1Extents)
            }
    }

    private class Group(var value: Double) {
        var extents: MutableList<Extent>? = null
    }

}


private inline fun <T> matchExtent(
    blocks: List<Block>,
    styleMatchPartitionIds: Map<ContentStyle, PartitionId>,
    matchWithinBlock: ContentStyle.() -> Boolean,
    sharedBlockExtent: (Block) -> T,
    sharedGroupExtent: (List<Block>) -> T
): Map<Block, T> {
    val groupExtents = HashMap<Block, T>(2 * blocks.size)
    val grouper = HashMap<Any /* group key */, MutableList<Block>>()
    for (block in blocks) {
        val stylePartitionId = styleMatchPartitionIds[block.style]
        // If the block's style is in some partition (!= null), matching the specific extent across blocks is enabled
        // for that style. Hence, group the block according to both (a) the style's partition and (b) the global body
        // match partition which arises from the "@Break Match" column in the credits table.
        if (stylePartitionId != null)
            grouper.computeIfAbsent(Pair(stylePartitionId, block.matchBodyPartitionId)) { ArrayList() }.add(block)
        // Otherwise, if the block is at least configured to internally match its extents, record the shared extent now.
        else if (matchWithinBlock(block.style))
            groupExtents[block] = sharedBlockExtent(block)
    }
    // Now that the grouping is done, record the shared extent of each group.
    for (group in grouper.values) {
        val extent = sharedGroupExtent(group)
        group.associateWithTo(groupExtents) { extent }
    }
    return groupExtents
}


private fun BodyElement.getWidth(textCtx: TextContext): Double = when (this) {
    is BodyElement.Nil -> 0.0
    is BodyElement.Str -> str.formatted(textCtx).width
    is BodyElement.Pic -> pic.width
    is BodyElement.Tap -> emb.resolution.widthPx.toDouble()
}

private fun BodyElement.getHeight(): Double = when (this) {
    is BodyElement.Nil -> sty.heightPx
    is BodyElement.Str -> str.height
    is BodyElement.Pic -> pic.height
    is BodyElement.Tap -> emb.resolution.heightPx.toDouble()
}


private fun DeferredImage.drawJustifiedBodyElem(
    textCtx: TextContext,
    elem: BodyElement, hJustify: HJustify, vJustify: VJustify,
    areaX: Double, areaY: Y, areaWidth: Double, areaHeight: Y
) = when (elem) {
    is BodyElement.Nil -> {}
    is BodyElement.Str ->
        drawJustifiedString(elem.str.formatted(textCtx), hJustify, vJustify, areaX, areaY, areaWidth, areaHeight)
    is BodyElement.Pic ->
        drawJustified(
            hJustify, vJustify, areaX, areaY, areaWidth, areaHeight, elem.pic.width, elem.pic.height.toY()
        ) { objX, objY -> drawEmbeddedPicture(elem.pic, objX, objY) }
    is BodyElement.Tap ->
        drawJustified(
            hJustify, vJustify, areaX, areaY, areaWidth, areaHeight, elem.getWidth(textCtx), elem.getHeight().toY()
        ) { objX, objY -> drawEmbeddedTape(elem.emb, objX, objY) }
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


private inline fun Opt<Double>.orElse(block: () -> Double) = if (isActive) value else block()
