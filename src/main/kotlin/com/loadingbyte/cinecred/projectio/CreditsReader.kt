package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.Severity.WARN
import com.loadingbyte.cinecred.project.*
import org.apache.commons.csv.CSVFormat
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


class ParserMsg(val lineNo: Int, val severity: Severity, val msg: String)


fun readCredits(csvFile: Path, styling: Styling): Pair<List<ParserMsg>, List<Page>?> {
    // Note: We use maps whose keys are case insensitive here because style references should be case insensitive.
    val pageStyleMap = styling.pageStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }
    val contentStyleMap = styling.contentStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }

    // We trim the unicode character "ZERO WIDTH NO-BREAK SPACE" which is added by Excel for some reason.
    val csvStr = Files.readString(csvFile).trim(0xFEFF.toChar())

    // Get the records starting with the header line.
    val lines = CSVFormat.DEFAULT.parse(StringReader(csvStr))
        .records
        .dropWhile { record -> !record.any { cell -> cell.trim().startsWith("@") } }

    // We will collect parser messages in this list.
    val log = mutableListOf<ParserMsg>()

    // If no table header has been encountered, log that.
    if (lines.isEmpty()) {
        val msg = "No table header is present in the CSV. Please refer to the sample CSV for information" +
                "on how to build a proper CSV."
        log.add(ParserMsg(1, Severity.ERROR, msg))
        return Pair(log, null)
    }

    val table = Table(
        log, lines,
        nullableColNames = listOf(
            "@Page Style", "@Break Align", "@Column Pos", "@Content Style", "@VGap", "@Head", "@Body", "@Tail"
        ),
        removeInterspersedEmptyLines = false
    )

    // The current content style. This variable is special because a content style stays valid until the next
    // explicit content style declaration.
    var contentStyle: ContentStyle? = null

    // The vertical gap that should be inserted AFTER the next CONCLUDED credits element. If multiple credits
    // elements will be concluded at the same time (e.g., a block, a column, and a section), the most significant
    // credits element will receive the gap (in our example, that would be the section).
    var vGapAccumulator = 0f

    // This variable is set to true when the current block should be concluded as soon as a row with some non-empty
    // body line arrives. It is used in cases where the previous block is known to be complete (e.g., because the
    // content style changed or there was an empty row), but it cannot be concluded yet because it is unknown whether
    // only the block or the block and more higher-order elements like a section will be concluded at the same time.
    // In the latter case, the vertical gap accumulator would go towards the section and not the block. So we have
    // to wait and see.
    var isBlockConclusionMarked = false

    // Final result
    val pages = mutableListOf<Page>()
    // Current page
    var pageStyle: PageStyle? = null
    val pageSections = mutableListOf<Section>()
    val pageAlignBodyColsGroupsGroups = mutableListOf<List<Block>>()
    val pageAlignHeadTailGroupsGroups = mutableListOf<List<Block>>()
    // Section and block groups
    var alignBodyColsGroupsGroup = mutableListOf<Block>()
    var alignHeadTailGroupsGroup = mutableListOf<Block>()
    // Current section
    val sectionColumns = mutableListOf<Column>()
    // Current column
    var columnPosOffsetPx = 0f
    val columnBlocks = mutableListOf<Block>()
    // Current block
    var blockHead: String? = null
    val blockBody = mutableListOf<String>()
    var blockTail: String? = null

    // Keep track of the conclusion of blocks.
    var didConcludeBlock = false

    fun concludeBlock(vGapAfter: Float) {
        if (blockBody.isNotEmpty()) {
            val block = Block(contentStyle!!, blockHead, blockBody, blockTail, vGapAfter)
            columnBlocks.add(block)
            alignBodyColsGroupsGroup.add(block)
            alignHeadTailGroupsGroup.add(block)
        }
        blockHead = null
        blockBody.clear()
        blockTail = null
        didConcludeBlock = true
    }

    fun concludeColumn() {
        if (columnBlocks.isNotEmpty())
            sectionColumns.add(Column(columnPosOffsetPx, columnBlocks))
        columnPosOffsetPx = 0f
        columnBlocks.clear()
    }

    fun concludeSection(vGapAfter: Float) {
        if (sectionColumns.isNotEmpty())
            pageSections.add(Section(sectionColumns, vGapAfter))
        sectionColumns.clear()
    }

    fun concludeAlignBodyColsGroupsGroup() {
        if (alignBodyColsGroupsGroup.isNotEmpty())
            pageAlignBodyColsGroupsGroups.add(alignBodyColsGroupsGroup)
        alignBodyColsGroupsGroup = mutableListOf()
    }

    fun concludeAlignHeadTailGroupsGroup() {
        if (alignHeadTailGroupsGroup.isNotEmpty())
            pageAlignHeadTailGroupsGroups.add(alignHeadTailGroupsGroup)
        alignHeadTailGroupsGroup = mutableListOf()
    }

    fun concludePage(newPageStyle: PageStyle) {
        if (pageSections.isNotEmpty()) {
            val page = Page(pageStyle!!, pageSections, pageAlignBodyColsGroupsGroups, pageAlignHeadTailGroupsGroups)
            pages.add(page)
        }
        pageStyle = newPageStyle
        pageSections.clear()
        pageAlignBodyColsGroupsGroups.clear()
        pageAlignHeadTailGroupsGroups.clear()
    }

    for (row in 0 until table.numRows) {
        // Helper function to shorten logging calls.
        fun log(kind: Severity, msg: String) = log.add(ParserMsg(table.getLineNo(row), kind, msg))

        // An empty row implicitly means a vertical gap of one unit after the appropriate credits element.
        if (table.isEmpty(row))
            vGapAccumulator += styling.global.unitVGapPx
        // Also add explicit vertical gaps to the accumulator.
        table.getFiniteFloat(row, "@VGap", nonNegative = true)?.let {
            vGapAccumulator += it * styling.global.unitVGapPx
        }

        // If the page style cell is non-empty, conclude the previous page (if there was any) and start a new one.
        table.getLookup(row, "@Page Style", pageStyleMap)?.let { newPageStyle ->
            concludeBlock(0f)
            concludeColumn()
            concludeSection(0f)
            concludeAlignBodyColsGroupsGroup()
            concludeAlignHeadTailGroupsGroup()
            concludePage(newPageStyle)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
        }

        // If the first page's style is not explicitly declared, issue a warning and fall back to the
        // page style defined first in the page style table, or, if that table is empty, to the standard page style.
        if (pageStyle == null) {
            val msg = if (styling.pageStyles.isEmpty()) {
                pageStyle = STANDARD_PAGE_STYLE
                "No page styles are available. Will default to the fallback page style '${pageStyle!!.name}'."
            } else {
                pageStyle = styling.pageStyles.firstOrNull()
                "For the first page, no page style is specified. Will default to the page style defined first " +
                        "in the page style table, which is '${pageStyle!!.name}'."
            }
            log(WARN, msg)
        }

        // If the column cell is non-empty, conclude the previous column (if there was any) and start a new one.
        // If the column cell contains "Wrap", also conclude the previous section and start a new one.
        val getColumnTypeDesc = "a column position offset from the screen center in pixels, optionally preceded by " +
                "\"Wrap\" to put the new column beneath all previous columns (so, e.g., \"-400\" or \"Wrap -400\")"
        table.get(row, "@Column Pos", { getColumnTypeDesc }) { str ->
            when {
                str.equals("wrap", ignoreCase = true) ->
                    Pair(true, 0f)
                str.contains("wrap", ignoreCase = true) ->
                    Pair(true, str.replace("wrap", "", ignoreCase = true).trim().toFiniteFloat())
                else ->
                    Pair(false, str.toFiniteFloat())
            }
        }?.let { (wrap, posOffsetPx) ->
            concludeBlock(0f)
            concludeColumn()
            if (wrap)
                concludeSection(vGapAccumulator)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
            columnPosOffsetPx = posOffsetPx
        }

        // If the content style cell is non-empty, mark the previous block for conclusion (if there was any).
        // We will actually apply the new content style later. This is because the previous block has not actually
        // been concluded yet.
        val newContentStyle = table.getLookup(row, "@Content Style", contentStyleMap)
        if (newContentStyle != null)
            isBlockConclusionMarked = true

        val newHead = table.getString(row, "@Head")
        val newTail = table.getString(row, "@Tail")
        val bodyLine = table.getString(row, "@Body")

        // If either of the head or tail cells is non-empty, or if the body line is non empty and the conclusion of the
        // previous block has been marked, conclude the previous block (if there was any) and start a new one.
        if (newHead != null || newTail != null || (isBlockConclusionMarked && bodyLine != null)) {
            concludeBlock(vGapAccumulator)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
            blockHead = newHead
            blockTail = newTail
        }

        // If the content style was changed in this row, use the new content style from now on
        // until the next explicit content style declaration.
        if (newContentStyle != null)
            contentStyle = newContentStyle

        // If no content style has been declared at the point where the first block starts, issue a warning and
        // fall back to the content style defined first in the content style table, or, if that table is empty,
        // to the standard content style.
        if (contentStyle == null && (newHead != null || newTail != null || bodyLine != null)) {
            val msg = if (styling.contentStyles.isEmpty()) {
                contentStyle = STANDARD_CONTENT_STYLE
                "No content styles are available. Will default to the fallback content style '${contentStyle.name}'."
            } else {
                contentStyle = styling.contentStyles[0]
                "No content style has been specified even though the first head-body-tail block starts here. " +
                        "Will default to the content style defined first in the content style table, which is " +
                        "'${contentStyle.name}'."
            }
            log(WARN, msg)
        }

        // If the line has a head or tail even though the current content style doesn't support it,
        // issue a warning and discard the head resp. tail.
        if (newHead != null && !contentStyle!!.hasHead) {
            blockHead = null
            val msg = "Head text is used even though the content style '${contentStyle.name}' doesn't support head " +
                    "text. Will discard the head text '$newHead'."
            log(WARN, msg)
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            val msg = "Tail text is used even though the content style '${contentStyle.name}' doesn't support tail " +
                    "text. Will discard the tail text '$newTail'."
            log(WARN, msg)
        }

        // If the body cell is non-empty, add its content to the current block.
        if (bodyLine != null)
            blockBody.add(bodyLine)
        // Otherwise, if the row didn't just start a new block,
        // mark the previous block for conclusion (if there was any).
        else if (newHead == null && newTail == null)
            isBlockConclusionMarked = true

        // If the content style is changed at a non-standard position, issue a warning.
        if (newContentStyle != null && !didConcludeBlock)
            log(WARN, "Changing content style in a row where no new head-body-tail block starts.")

        // If the break alignment cell is non-empty, conclude the specified previous alignment group.
        table.get(row, "@Break Align", { "one of 'Body Columns'/'Head and Tail'" }) { str ->
            val lc = str.toLowerCase()
            require(lc == "body columns" || lc == "head and tail")
            lc
        }?.let { str ->
            if (str == "body columns") {
                concludeAlignBodyColsGroupsGroup()
                if (!didConcludeBlock)
                    log(WARN, "Breaking body column alignment in a row where no new head-body-tail block starts.")
            } else {
                concludeAlignHeadTailGroupsGroup()
                if (!didConcludeBlock)
                    log(WARN, "Breaking head and tail alignment in a row where no new head-body-tail block starts.")
            }
        }

        didConcludeBlock = false
    }

    // Conclude all open credits elements that haven't been concluded yet.
    concludeBlock(0f)
    concludeColumn()
    concludeSection(0f)
    concludeAlignBodyColsGroupsGroup()
    concludeAlignHeadTailGroupsGroup()
    concludePage(pageStyle!! /* we just need to put anything in here */)

    return Pair(log, pages)
}
