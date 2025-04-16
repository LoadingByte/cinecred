package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.DeferredImage.Coat
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.Y.Companion.plus
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BodyLayout.*
import com.loadingbyte.cinecred.project.GridColUnderoccupancy.*
import com.loadingbyte.cinecred.project.GridStructure.EQUAL_WIDTH_COLS
import com.loadingbyte.cinecred.project.GridStructure.SQUARE_CELLS
import com.loadingbyte.cinecred.project.HarmonizeExtent.ACROSS_BLOCKS
import com.loadingbyte.cinecred.project.HarmonizeExtent.WITHIN_BLOCK
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.*
import kotlin.math.max
import kotlin.math.min


class DrawnBody(val defImage: DeferredImage, val lines: List<DrawnBodyLine>)


sealed class DrawnBodyLine {

    abstract val x: Double
    protected abstract val yBaseline: Double?
    abstract val width: Double
    abstract val height: Double

    fun yBaselineForAppendage(vJustify: AppendageVJustify, appendage: FormattedString): Double =
        when (vJustify) {
            AppendageVJustify.TOP -> appendage.heightAboveBaseline
            AppendageVJustify.MIDDLE -> appendage.heightAboveBaseline + (height - appendage.height) / 2.0
            AppendageVJustify.BOTTOM -> appendage.heightAboveBaseline + (height - appendage.height)
            AppendageVJustify.BASELINE -> yBaseline ?: yBaselineForAppendage(AppendageVJustify.MIDDLE, appendage)
        }

}


fun drawBodies(
    styling: Styling,
    blocks: List<Block>
): Map<Block, DrawnBody> {
    val drawnBodies = HashMap<Block, DrawnBody>(2 * blocks.size)

    // Draw body images for blocks with the "grid" or "flow" body layout.
    drawBodyImagesWithGridBodyLayout(
        drawnBodies, styling,
        blocks.filter { block -> block.style.bodyLayout == GRID }
    )
    drawBodyImagesWithFlowBodyLayout(
        drawnBodies, styling,
        blocks.filter { block -> block.style.bodyLayout == FLOW }
    )

    // Draw body images for blocks with the "paragraphs" body layout.
    for (block in blocks)
        if (block.style.bodyLayout == PARAGRAPHS)
            drawnBodies[block] = drawBodyImageWithParagraphsBodyLayout(styling, block)

    return drawnBodies
}


private fun drawBodyImagesWithGridBodyLayout(
    out: MutableMap<Block, DrawnBody>,
    styling: Styling,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "grid" body layout.
    require(blocks.all { it.style.bodyLayout == GRID })

    // Flow each block's body elements into the grid configured for that block.
    val colsPerBlock = blocks.associateWith { block ->
        val s = block.style
        flowIntoGridCols(sort(block.body, s.sort), s.gridCols, s.gridFillingOrder, s.gridFillingBalanced)
    }

    // Grid blocks are free to potentially harmonize their grid column widths and grid row height, permitting the user
    // to create neatly aligned tabular layouts that span multiple blocks. For both "extents" that can be harmonized
    // (i.e., col widths and row height), find which styles should harmonize together.
    val harmonizeColWidthsPartitionIds = styling.contentStyles
        .filter { bodyLayout == GRID && !gridForceColWidthPx.isActive && gridHarmonizeColWidths == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::gridHarmonizeColWidthsAcrossStyles)
    val harmonizeRowHeightPartitionIds = styling.contentStyles
        .filter { bodyLayout == GRID && !gridForceRowHeightPx.isActive && gridHarmonizeRowHeight == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::gridHarmonizeRowHeightAcrossStyles)

    // Determine the groups of blocks which should share the same column widths (for simplicity of implementation, this
    // also includes ungrouped blocks whose grid structure mandates square cells, and ungrouped blocks with forced
    // column width), and then find those shared widths.
    fun colWidth(col: List<BodyElement>) = col.maxOfOr(0.0) { bodyElem -> bodyElem.getWidth(styling) }
    val sharedColWidthsPerBlock: Map<Block, Array<Extent>> = harmonizeExtent(
        blocks, harmonizeColWidthsPartitionIds,
        harmonizeWithinBlock = {
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
                // to harmonize each block with the leftmost or rightmost shared columns.
                val shift = if (block.style.gridHarmonizeColUnderoccupancy.alignRight) colWidths.size - cols.size else 0
                for ((colIdx, col) in cols.withIndex()) {
                    val extent = colWidths[shift + colIdx]
                    extent.value = max(extent.value, colWidth(col))
                }
            }
            colWidths
        }
    )

    // Determine the blocks which have uniform row height, optionally shared across multiple blocks, and then find
    // those shared heights.
    // Instead of calling LineGauge() on the list of all cells, we call it once per row and then maximize over rows.
    // To see why this is necessary, think about the cells in one row having a font with 10 ascent and 2 descent, while
    // the font in another row has 2 ascent and 10 descent. Both rows should naturally have the same height of 12. But
    // if we'd look at all the cells in one go, the largest ascent and descent would sum to a row height of 20.
    fun maxRowHeight(block: Block): Double {
        val cols = colsPerBlock.getValue(block)
        var rowIdx = 0
        var max = 0.0
        while (true) {
            val row = cols.mapNotNull { col -> col.getOrNull(rowIdx) }
            rowIdx++
            if (row.isEmpty())
                return max
            max = max(max, LineGauge.getHeight(row, styling))
        }
    }

    val sharedRowHeightPerBlock: Map<Block, Extent> = harmonizeExtent(
        blocks, harmonizeRowHeightPartitionIds,
        harmonizeWithinBlock = {
            gridForceRowHeightPx.isActive || gridHarmonizeRowHeight == WITHIN_BLOCK || gridStructure == SQUARE_CELLS
        },
        sharedBlockExtent = { block -> Extent((block.style.gridForceRowHeightPx.orElse { maxRowHeight(block) })) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxRowHeight)) }
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
            val startIdx = if (block.style.gridHarmonizeColUnderoccupancy.alignRight) colWidths.size - numCols else 0
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
            styling, block, colsPerBlock.getValue(block), sharedColWidthsPerBlock[block], sharedRowHeightPerBlock[block]
        )
    }
}


private fun drawBodyImageWithGridBodyLayout(
    styling: Styling,
    block: Block,
    cols: List<List<BodyElement>>,
    sharedColWidths: Array<Extent>?,
    sharedRowHeight: Extent?
): DrawnBody {
    val style = block.style
    val unocc = style.gridHarmonizeColUnderoccupancy

    val numCols = cols.size
    val numRows = cols.maxOf { col -> col.size }
    val rowGauges = List(numRows) { rowIdx ->
        val row = cols.mapNotNull { col -> col.getOrNull(rowIdx) }
        LineGauge(style.gridCellVJustify, row, sharedRowHeight?.value, styling)
    }

    val bodyImage = DeferredImage(
        height = ((numRows - 1) * style.gridRowGapPx).toElasticY() + rowGauges.sumOf(LineGauge::height)
    )

    // Draw each column. If the block shares the same column widths as other blocks and the user requested to retain
    // unoccupied columns, do that by either prepending negative column indices or appending too big ones. Only empty
    // guides will be drawn for these out-of-bounds column indices.
    var x = 0.0
    val startColIdx = if (sharedColWidths != null && unocc == RIGHT_RETAIN) numCols - sharedColWidths.size else 0
    val endColIdx = if (sharedColWidths != null && unocc == LEFT_RETAIN) sharedColWidths.size else numCols
    for (colIdx in startColIdx..<endColIdx) {
        // Either get the column's shared width, or compute it now if the block's column widths are not shared.
        val colWidth = when (sharedColWidths) {
            null -> cols[colIdx].maxOfOr(0.0) { bodyElem -> bodyElem.getWidth(styling) }
            else -> sharedColWidths[colIdx + if (unocc.alignRight) sharedColWidths.size - numCols else 0].value
        }
        // Draw each row cell in the column.
        var y = 0.0.toY()
        for ((rowIdx, rowGauge) in rowGauges.withIndex()) {
            cols.getOrNull(colIdx)?.getOrNull(rowIdx)?.let { bodyElem ->
                val bodyElemWidth = bodyElem.getWidth(styling)
                val bodyElemX = x + justify(style.gridCellHJustifyPerCol[colIdx], colWidth, bodyElemWidth)
                rowGauge.drawVJustifiedBodyElem(bodyImage, bodyElem, bodyElemX, y)
                // Record the area in this row that is actually drawn to.
                if (rowGauge.x.isNaN())
                    rowGauge.x = bodyElemX
                rowGauge.width = bodyElemX + bodyElemWidth - rowGauge.x
            }
            // Draw a guide that shows the edges of the body cell.
            bodyImage.drawRect(BODY_CELL_GUIDE_COLOR, x, y, colWidth, rowGauge.height.toY(), layer = GUIDES)
            // Advance to the next row in the current column.
            y += rowGauge.height + style.gridRowGapPx.toElasticY()
        }
        // Advance to the next column.
        x += colWidth
        if (colIdx != endColIdx - 1)
            x += style.gridColGapPx
    }

    // Set the width of the body image.
    bodyImage.width = x

    return DrawnBody(bodyImage, rowGauges)
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
    styling: Styling,
    blocks: List<Block>
) {
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the "flow" body layout.
    require(blocks.all { it.style.bodyLayout == FLOW })

    // Flow blocks are free to potentially harmonize their cell width and height. This allows the user to create neatly
    // aligned grid-like yet dynamic layouts that can even span multiple blocks. For both "extents" that can be
    // harmonized (i.e., cell width and height), find which styles should harmonize together.
    val harmonizeCellWidthPartitionIds = styling.contentStyles
        .filter { bodyLayout == FLOW && !flowForceCellWidthPx.isActive && flowHarmonizeCellWidth == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::flowHarmonizeCellWidthAcrossStyles)
    val harmonizeCellHeightPartitionIds = styling.contentStyles
        .filter { bodyLayout == FLOW && !flowForceCellHeightPx.isActive && flowHarmonizeCellHeight == ACROSS_BLOCKS }
        .partitionIntoTransitiveClosures(ContentStyle::flowHarmonizeCellHeightAcrossStyles)

    // Determine the blocks which have uniform cell width, optionally shared across multiple blocks, and then find
    // those shared widths.
    fun maxElemWidth(block: Block) = block.body.maxOf { bodyElem -> bodyElem.getWidth(styling) }
    val sharedCellWidthPerBlock: Map<Block, Extent> = harmonizeExtent(
        blocks, harmonizeCellWidthPartitionIds,
        harmonizeWithinBlock = {
            flowForceCellWidthPx.isActive || flowHarmonizeCellWidth == WITHIN_BLOCK || flowSquareCells
        },
        sharedBlockExtent = { block -> Extent(block.style.flowForceCellWidthPx.orElse { maxElemWidth(block) }) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxElemWidth)) }
    )

    // We will later use this function to flow a block's body cells into lines.
    fun flowIntoLines(block: Block): List<List<BodyElement>> {
        // Determine which body elements should lie on which line. We use the simplest possible flow algorithm for this.
        val s = block.style
        val sharedCellWidth = sharedCellWidthPerBlock[block]
        return flowIntoLines(sort(block.body, s.sort), s.flowDirection, s.flowLineWidthPx, s.flowHGapPx) { bodyElem ->
            sharedCellWidth?.value ?: bodyElem.getWidth(styling)
        }
    }

    // Flow the body cells of all non-square blocks into lines now. The lines will be needed for cell height
    // harmonization in the next step. For an explanation, see the row height harmonization comment in
    // drawBodyImagesWithGridBodyLayout().
    val linesPerBlock = HashMap<Block, List<List<BodyElement>>>()
    for (block in blocks)
        if (!block.style.flowSquareCells)
            linesPerBlock[block] = flowIntoLines(block)

    // Determine the blocks which have uniform cell height, optionally shared across multiple blocks, and then find
    // those shared heights.
    fun maxLineHeight(block: Block) =
        // For blocks with square cells, consider all blocks to be in the same line for simplicity.
        if (block.style.flowSquareCells) LineGauge.getHeight(block.body, styling)
        else linesPerBlock.getValue(block).maxOf { line -> LineGauge.getHeight(line, styling) }

    val sharedCellHeightPerBlock: Map<Block, Extent> = harmonizeExtent(
        blocks, harmonizeCellHeightPartitionIds,
        harmonizeWithinBlock = {
            flowForceCellHeightPx.isActive || flowHarmonizeCellHeight == WITHIN_BLOCK || flowSquareCells
        },
        sharedBlockExtent = { block -> Extent(block.style.flowForceCellHeightPx.orElse { maxLineHeight(block) }) },
        sharedGroupExtent = { group -> Extent(group.maxOf(::maxLineHeight)) }
    )

    // Harmonize the cell width and height of square cells.
    for (block in blocks)
        if (block.style.flowSquareCells)
            sharedCellWidthPerBlock.getValue(block).link(sharedCellHeightPerBlock.getValue(block))

    // Now that we also know the widths of square cells, also flow the blocks containing them into lines.
    for (block in blocks)
        if (block.style.flowSquareCells)
            linesPerBlock[block] = flowIntoLines(block)

    // Draw a deferred image for the body of each block.
    blocks.associateWithTo(out) { block ->
        drawBodyImageWithFlowBodyLayout(
            styling, block, linesPerBlock.getValue(block),
            sharedCellWidthPerBlock[block], sharedCellHeightPerBlock[block]
        )
    }
}


private fun drawBodyImageWithFlowBodyLayout(
    styling: Styling,
    block: Block,
    lines: List<List<BodyElement>>,
    sharedCellWidth: Extent?,
    sharedCellHeight: Extent?
): DrawnBody {
    val style = block.style
    val horGap = style.flowHGapPx

    val sepLetterStyleName = style.flowSeparatorLetterStyleName.orElse { style.bodyLetterStyleName }
    val sepLetterStyle = styling.letterStyles.find { it.name == sepLetterStyleName } ?: PLACEHOLDER_LETTER_STYLE
    val sepStr = style.flowSeparator
    val sepFmtStr = if (sepStr.isBlank()) null else format(sepStr, sepLetterStyle, styling)

    // The width of the body image must be at least the width of the widest body element, because otherwise,
    // that element could not even fit into one line of the body.
    val bodyImageWidth = style.flowLineWidthPx
        .coerceAtLeast(sharedCellWidth?.value ?: block.body.maxOf { bodyElem -> bodyElem.getWidth(styling) })

    val lineGauges = lines.map { line -> LineGauge(style.flowCellVJustify, line, sharedCellHeight?.value, styling) }

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
            else line.sumOf { bodyElem -> bodyElem.getWidth(styling) }

        // If the body uses full justification, we use this "glue" to adjust the horizontal gap around the separator
        // such that the line fills the whole width of the body image.
        val horGlue = if (curLineHJustify != SingleLineHJustify.FULL) 0.0 else
            (bodyImageWidth - totalRigidWidth) / (line.size - 1) - horGap

        // Find the filled width as well as the height of the current line.
        val lineWidth = totalRigidWidth + (line.size - 1) * (horGap + horGlue)
        val lineGauge = lineGauges[lineIdx]

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
            val bodyElemWidth = bodyElem.getWidth(styling)
            val areaWidth = sharedCellWidth?.value ?: bodyElemWidth

            // Draw the current body element.
            val bodyElemX = x + justify(style.flowCellHJustify, areaWidth, bodyElemWidth)
            lineGauge.drawVJustifiedBodyElem(bodyImage, bodyElem, bodyElemX, y)

            // Record the area in this line that is actually drawn to.
            if (bodyElemIdx == 0)
                lineGauge.x = bodyElemX
            if (bodyElemIdx == line.lastIndex)
                lineGauge.width = bodyElemX + bodyElemWidth - lineGauge.x

            // Draw a guide that shows the edges of the current body cell.
            bodyImage.drawRect(BODY_CELL_GUIDE_COLOR, x, y, areaWidth, lineGauge.height.toY(), layer = GUIDES)

            if (bodyElemIdx != line.lastIndex) {
                // Advance to the separator.
                x += areaWidth
                // Draw the separator.
                if (sepFmtStr != null)
                    bodyImage.drawString(
                        sepFmtStr,
                        x + (horGap + horGlue - sepFmtStr.width) / 2.0,
                        y + lineGauge.yBaselineForAppendage(style.flowSeparatorVJustify, sepFmtStr)
                    )
                // Advance to the next element on the line.
                x += horGap + horGlue
            }
        }

        // Advance to the next line.
        y += lineGauge.height
        if (lineIdx != lines.lastIndex)
            y += style.flowLineGapPx.toElasticY()
    }

    // Set the height of the body image.
    bodyImage.height = y

    // Draw guides that show the body's left and right edges.
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, 0.0, 0.0.toY(), 0.0, y, layer = GUIDES)
    bodyImage.drawLine(BODY_WIDTH_GUIDE_COLOR, bodyImageWidth, 0.0.toY(), bodyImageWidth, y, layer = GUIDES)

    return DrawnBody(bodyImage, lineGauges)
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
    styling: Styling,
    block: Block
): DrawnBody {
    val style = block.style
    // In this function, we only concern ourselves with blocks whose bodies are laid out using the
    // "paragraphs" body layout.
    require(style.bodyLayout == PARAGRAPHS)

    val bodyImageWidth = style.paragraphsLineWidthPx

    val bodyImage = DeferredImage(width = bodyImageWidth)
    val drawnBodyLines = mutableListOf<DrawnBodyLine>()

    var y = 0.0.toY()
    for (bodyElem in block.body) {
        // Case 1: The body element is a string. Determine line breaks and draw it as a paragraph.
        if (bodyElem is BodyElement.Str) {
            for (str in bodyElem.lines) {
                val fmtStr = str.formatted(styling)
                val lineBreaks = fmtStr.breakLines(bodyImageWidth)
                for ((lineStartPos, lineEndPos) in lineBreaks.zipWithNext()) {
                    // Note: If the line contains only whitespace, this skips to the next line.
                    var lineFmtStr = fmtStr.sub(lineStartPos, lineEndPos)?.trim() ?: continue

                    val isLastLine = lineEndPos == fmtStr.string.length
                    val curLineHJustify = style.paragraphsLineHJustify.toSingleLineHJustify(isLastLine)

                    var x = 0.0
                    // Case 1a: Full justification.
                    if (curLineHJustify == SingleLineHJustify.FULL)
                        lineFmtStr = lineFmtStr.justify(bodyImageWidth)
                    // Case 1b: Left, center, or right justification.
                    else
                        x = justify(curLineHJustify.toHJustify(), bodyImageWidth, lineFmtStr.width)
                    bodyImage.drawString(lineFmtStr, x, y + lineFmtStr.heightAboveBaseline)

                    // Advance to the next line.
                    y += lineFmtStr.height + style.paragraphsLineGapPx.toElasticY()

                    drawnBodyLines += DrawnBodyLineRecord(
                        x, lineFmtStr.heightAboveBaseline, lineFmtStr.width, lineFmtStr.height
                    )
                }
            }
            y -= style.paragraphsLineGapPx.toElasticY()
        }
        // Case 2: The body element is not a string. Just draw it regularly.
        else {
            val hJustify = style.paragraphsLineHJustify.toSingleLineHJustify(lastLine = false).toHJustify()
            val bodyElemWidth = bodyElem.getWidth(styling)
            val bodyElemHeight = bodyElem.getHeight(styling)
            val x = justify(hJustify, bodyImageWidth, bodyElemWidth)
            when (bodyElem) {
                is BodyElement.Nil, is BodyElement.Str -> {}
                is BodyElement.Pic -> bodyElem.sty.toEmbedded()?.also { bodyImage.drawEmbeddedPicture(it, x, y) }
                is BodyElement.Tap -> bodyElem.sty.toEmbedded(styling)?.also { bodyImage.drawEmbeddedTape(it, x, y) }
                is BodyElement.Mis -> null
            } ?: bodyImage.drawMissing(x, y)
            y += bodyElemHeight
            drawnBodyLines += DrawnBodyLineRecord(x, null, bodyElemWidth, bodyElemHeight)
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

    return DrawnBody(bodyImage, drawnBodyLines)
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


private inline fun <T> harmonizeExtent(
    blocks: List<Block>,
    styleHarmonizationPartitionIds: Map<ContentStyle, PartitionId>,
    harmonizeWithinBlock: ContentStyle.() -> Boolean,
    sharedBlockExtent: (Block) -> T,
    sharedGroupExtent: (List<Block>) -> T
): Map<Block, T> {
    val groupExtents = HashMap<Block, T>(2 * blocks.size)
    val grouper = HashMap<Any /* group key */, MutableList<Block>>()
    for (block in blocks) {
        val stylePartitionId = styleHarmonizationPartitionIds[block.style]
        // If the block's style is in some partition (!= null), harmonizing the specific extent across blocks is enabled
        // for that style. Hence, group the block according to both (a) the style's partition and (b) the global body
        // harmonization partition which arises from the "@Break Harmonization" column in the credits table.
        if (stylePartitionId != null)
            grouper.computeIfAbsent(Pair(stylePartitionId, block.harmonizeBodyPartitionId)) { ArrayList() }.add(block)
        // Else, if the block is at least configured to internally harmonize its extents, record the shared extent now.
        else if (harmonizeWithinBlock(block.style))
            groupExtents[block] = sharedBlockExtent(block)
    }
    // Now that the grouping is done, record the shared extent of each group.
    for (group in grouper.values) {
        val extent = sharedGroupExtent(group)
        group.associateWithTo(groupExtents) { extent }
    }
    return groupExtents
}


private fun BodyElement.getWidth(styling: Styling): Double = when (this) {
    is BodyElement.Nil -> 0.0
    is BodyElement.Str -> lines.first().formatted(styling).width
    is BodyElement.Pic -> sty.toEmbedded()?.width ?: MISSING_RECT.width
    is BodyElement.Tap -> sty.toEmbedded(styling)?.run { resolution.widthPx.toDouble() } ?: MISSING_RECT.width
    is BodyElement.Mis -> MISSING_RECT.width
}

private fun BodyElement.getHeight(styling: Styling): Double = when (this) {
    is BodyElement.Nil -> sty.heightPx
    is BodyElement.Str -> lines.first().formatted(styling).height
    is BodyElement.Pic -> sty.toEmbedded()?.height ?: MISSING_RECT.height
    is BodyElement.Tap -> sty.toEmbedded(styling)?.run { resolution.heightPx.toDouble() } ?: MISSING_RECT.height
    is BodyElement.Mis -> MISSING_RECT.height
}


private fun sort(body: List<BodyElement>, sort: Sort): List<BodyElement> = when (sort) {
    Sort.OFF -> body
    Sort.ASCENDING -> body.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ::sortingSelector))
    Sort.DESCENDING -> body.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER, ::sortingSelector))
}

private fun sortingSelector(bodyElem: BodyElement) = when (bodyElem) {
    is BodyElement.Str -> bodyElem.lines.first().joinToString("") { (run, _) -> run }
    is BodyElement.Nil, is BodyElement.Pic, is BodyElement.Tap, is BodyElement.Mis -> ""
}


private class DrawnBodyLineRecord(
    override val x: Double, override val yBaseline: Double?, override val width: Double, override val height: Double
) : DrawnBodyLine()


private class LineGauge(
    private val vJustify: VJustify, line: List<BodyElement>, forcedHeight: Double?, private val styling: Styling
) : DrawnBodyLine() {

    override var x = Double.NaN
    override val yBaseline: Double?
    override var width = Double.NaN
    override val height: Double

    init {
        var hasStr = false
        var aboveBaseline = 0.0
        var belowBaseline = 0.0
        var eleHeight = 0.0
        for (bodyElem in line)
            if (bodyElem is BodyElement.Str) {
                hasStr = true
                val fmtStr = bodyElem.lines.first().formatted(styling)
                aboveBaseline = max(aboveBaseline, fmtStr.heightAboveBaseline)
                belowBaseline = max(belowBaseline, fmtStr.heightBelowBaseline)
            } else
                eleHeight = max(eleHeight, bodyElem.getHeight(styling))
        val strHeight = aboveBaseline + belowBaseline
        height = forcedHeight ?: max(eleHeight, strHeight)
        yBaseline = if (!hasStr) null else aboveBaseline + justify(vJustify, height, strHeight)
    }

    fun drawVJustifiedBodyElem(defImage: DeferredImage, bodyElem: BodyElement, x: Double, lineY: Y) {
        when (bodyElem) {
            is BodyElement.Nil -> {}
            is BodyElement.Str ->
                defImage.drawString(bodyElem.lines.first().formatted(styling), x, lineY + yBaseline!!)
            is BodyElement.Pic, is BodyElement.Tap, is BodyElement.Mis -> {
                val y = lineY + justify(vJustify, height, bodyElem.getHeight(styling))
                when (bodyElem) {
                    is BodyElement.Pic -> bodyElem.sty.toEmbedded()?.also { defImage.drawEmbeddedPicture(it, x, y) }
                    is BodyElement.Tap -> bodyElem.sty.toEmbedded(styling)?.also { defImage.drawEmbeddedTape(it, x, y) }
                    is BodyElement.Mis -> null
                    else -> {}
                } ?: defImage.drawMissing(x, y)
            }
        }
    }

    companion object {
        fun getHeight(line: List<BodyElement>, styling: Styling) =
            LineGauge(VJustify.MIDDLE, line, null, styling).height
    }

}


private fun LineHJustify.toSingleLineHJustify(lastLine: Boolean) = when {
    this == LineHJustify.LEFT || lastLine && this == LineHJustify.FULL_LAST_LEFT -> SingleLineHJustify.LEFT
    this == LineHJustify.CENTER || lastLine && this == LineHJustify.FULL_LAST_CENTER -> SingleLineHJustify.CENTER
    this == LineHJustify.RIGHT || lastLine && this == LineHJustify.FULL_LAST_RIGHT -> SingleLineHJustify.RIGHT
    else -> SingleLineHJustify.FULL
}


private fun PictureStyle.toEmbedded(): DeferredImage.EmbeddedPicture? {
    val picture = try {
        (this@toEmbedded.picture.loader ?: return null).picture
    } catch (_: IllegalStateException) {
        return null
    }
    val width = if (widthPx.isActive) widthPx.value else null
    val height = if (heightPx.isActive) heightPx.value else null
    val crop = if (picture is Picture.Vector) cropBlankSpace else false
    return DeferredImage.EmbeddedPicture(picture, width, height, crop)
}


private fun TapeStyle.toEmbedded(styling: Styling): DeferredImage.EmbeddedTape? {
    val tap = tape.tape ?: return null
    val res: Resolution
    val rng: OpenEndRange<Timecode>
    try {
        res = tap.spec.resolution
        rng = tap.availableRange
    } catch (_: IllegalStateException) {
        return null
    }
    val fadeInTransition = styling.transitionStyles.find { it.name == fadeInTransitionStyleName }?.graph
        ?: Transition.LINEAR
    val fadeOutTransition = styling.transitionStyles.find { it.name == fadeOutTransitionStyleName }?.graph
        ?: Transition.LINEAR
    var inPoint = coerceTimecode(slice.inPoint, tap, styling) ?: rng.start
    var outPoint = coerceTimecode(slice.outPoint, tap, styling) ?: rng.endExclusive
    if (inPoint >= outPoint || inPoint >= rng.endExclusive || outPoint <= rng.start)
        return null
    if (inPoint < rng.start)
        inPoint = rng.start
    if (outPoint > rng.endExclusive)
        outPoint = rng.endExclusive
    return DeferredImage.EmbeddedTape(
        tap,
        resolution = when {
            !widthPx.isActive && !heightPx.isActive -> res
            !widthPx.isActive -> Resolution(roundingDiv(heightPx.value * res.widthPx, res.heightPx), heightPx.value)
            !heightPx.isActive -> Resolution(widthPx.value, roundingDiv(widthPx.value * res.heightPx, res.widthPx))
            else -> Resolution(widthPx.value, heightPx.value)
        },
        leftTemporalMarginFrames, rightTemporalMarginFrames,
        fadeInFrames, fadeInTransition,
        fadeOutFrames, fadeOutTransition,
        inPoint..<outPoint,
        when (temporallyJustify) {
            HJustify.LEFT -> DeferredImage.EmbeddedTape.Align.START
            HJustify.CENTER -> DeferredImage.EmbeddedTape.Align.MIDDLE
            HJustify.RIGHT -> DeferredImage.EmbeddedTape.Align.END
        }
    )
}

private fun coerceTimecode(optTc: Opt<Timecode>, tape: Tape, styling: Styling): Timecode? = when {
    !optTc.isActive -> null
    tape.fileSeq ->
        try {
            optTc.value.toFrames(styling.global.fps)
        } catch (_: IllegalArgumentException) {
            null
        }
    else -> when (val tc = optTc.value) {
        // If the user used the timecode format we need, immediately return.
        is Timecode.Clock -> tc
        // If the user used a frames or a SMPTE timecode, convert it to the clock format using the fixed FPS.
        is Timecode.Frames, is Timecode.SMPTEDropFrame, is Timecode.SMPTENonDropFrame ->
            try {
                tape.fps?.let(tc::toClock)
            } catch (_: RuntimeException) {
                null
            }
        // If the user used an exact frames in second timecode, convert it to the clock format by looking up the
        // referenced frame and taking its clock timecode.
        is Timecode.ExactFramesInSecond ->
            try {
                tape.toClockTimecode(tc)
            } catch (_: Exception) {
                // If the tape has internal errors, it can't be rendered anyway, so don't bother the user.
                null
            }
    }
}


private val MISSING_RECT = Rectangle2D.Double(0.0, 0.0, 200.0, 200.0)

private fun DeferredImage.drawMissing(x: Double, y: Y) = drawShape(
    Coat.Gradient(
        Color4f.MISSING_MEDIA_TOP, Color4f.MISSING_MEDIA_BOT,
        Point2D.Double(0.0, 0.0), Point2D.Double(0.0, MISSING_RECT.height)
    ),
    MISSING_RECT, x, y, fill = true
)
