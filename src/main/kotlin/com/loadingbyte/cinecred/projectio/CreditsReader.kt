package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.Severity.WARN
import com.loadingbyte.cinecred.project.*
import org.apache.commons.csv.CSVFormat
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


private const val DEFAULT_HGAP = "800"


class ParserMsg(val lineNo: Int, val severity: Severity, val msg: String)


fun readCredits(csvFile: Path, styling: Styling): Pair<List<ParserMsg>, List<Page>?> {
    // Note: We use maps whose keys are case insensitive here because style references should be case insensitive.
    val pageStyleMap = styling.pageStyles.map { it.name to it }.toMap(TreeMap(String.CASE_INSENSITIVE_ORDER))
    val contentStyleMap = styling.contentStyles.map { it.name to it }.toMap(TreeMap(String.CASE_INSENSITIVE_ORDER))

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
        defaultedColNames = mapOf("@hgap" to DEFAULT_HGAP),
        nullableColNames = listOf(
            "@page style", "@reset layout", "@column", "@content style", "@vgap", "@head", "@body", "@tail"
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
    val pageSectionGroups = mutableListOf<List<Section>>()
    val pageBlockGroups = mutableListOf<List<Block>>()
    // Section and block groups
    var sectionGroup = mutableListOf<Section>()
    var blockGroup = mutableListOf<Block>()
    // Current section
    val sectionColumns = TreeMap<Int, Column>()  // sorted by keys
    // Current column
    var columnId = 1
    val columnBlocks = mutableListOf<Block>()
    // Current block
    var blockHead: String? = null
    val blockBody = mutableListOf<String>()
    var blockTail: String? = null

    // Keep track of the conclusion of sections and blocks.
    var didConcludeSection = false
    var didConcludeBlock = false

    fun concludeBlock(vGapAfter: Float) {
        if (blockBody.isNotEmpty()) {
            val block = Block(contentStyle!!, blockHead, blockBody, blockTail, vGapAfter)
            columnBlocks.add(block)
            blockGroup.add(block)
        }
        blockHead = null
        blockBody.clear()
        blockTail = null
        didConcludeBlock = true
    }

    fun concludeColumn(hGapAfter: Float, newColumnId: Int) {
        if (columnBlocks.isNotEmpty())
            sectionColumns[columnId] = Column(columnBlocks, hGapAfter)
        columnId = newColumnId
        columnBlocks.clear()
    }

    fun concludeSection(vGapAfter: Float) {
        if (sectionColumns.isNotEmpty()) {
            val section = Section(sectionColumns.values.toList(), vGapAfter)
            pageSections.add(section)
            sectionGroup.add(section)
        }
        sectionColumns.clear()
        didConcludeSection = true
    }

    fun concludeBlockGroup() {
        if (blockGroup.isNotEmpty())
            pageBlockGroups.add(blockGroup)
        blockGroup = mutableListOf()
    }

    fun concludeSectionGroup() {
        if (sectionGroup.isNotEmpty())
            pageSectionGroups.add(sectionGroup)
        sectionGroup = mutableListOf()
    }

    fun concludePage(newPageStyle: PageStyle) {
        if (pageSections.isNotEmpty()) {
            val page = Page(pageStyle!!, pageSections, pageSectionGroups, pageBlockGroups)
            pages.add(page)
        }
        pageStyle = newPageStyle
        pageSections.clear()
        pageSectionGroups.clear()
        pageBlockGroups.clear()
    }

    for (row in 0 until table.numRows) {
        // Helper function to shorten logging calls.
        fun log(kind: Severity, msg: String) = log.add(ParserMsg(table.getLineNo(row), kind, msg))

        // An empty row implicitly means a vertical gap of one unit after the appropriate credits element.
        if (table.isEmpty(row))
            vGapAccumulator += styling.global.unitVGapPx
        // Also add explicit vertical gaps to the accumulator.
        table.getFiniteFloat(row, "@vgap", nonNegative = true)?.let {
            vGapAccumulator += it * styling.global.unitVGapPx
        }

        // If the page style cell is non-empty, conclude the previous page (if there was any) and start a new one.
        table.getLookup(row, "@page style", pageStyleMap)?.let { newPageStyle ->
            concludeBlock(0f)
            concludeColumn(0f, 1)
            concludeSection(0f)
            concludeBlockGroup()
            concludeSectionGroup()
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
        table.getInt(row, "@column", nonNegative = true, nonZero = true)?.let { newColumnId ->
            // If the new column ID matches that of a column already present in the current section, we will not
            // only conclude a column here, but also the previous section (if there was any) and start a new one.
            val willConcludeSection = newColumnId in sectionColumns || newColumnId == columnId

            // If the previous column is followed by another column, the user must supply a horizontal gap
            // between the two columns.
            val hGapAfter =
                if (willConcludeSection) 0f
                else table.getFiniteFloat(row, "@hgap", nonNegative = true)!!

            concludeBlock(0f)
            concludeColumn(hGapAfter * styling.global.unitHGapPx, newColumnId)
            if (willConcludeSection)
                concludeSection(vGapAccumulator)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
        }

        // If the content style cell is non-empty, mark the previous block for conclusion (if there was any).
        // We will actually apply the new content style later. This is because the previous block has not actually
        // been concluded yet.
        val newContentStyle = table.getLookup(row, "@content style", contentStyleMap)
        if (newContentStyle != null)
            isBlockConclusionMarked = true

        val newHead = table.getString(row, "@head")
        val newTail = table.getString(row, "@tail")
        val bodyLine = table.getString(row, "@body")

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
        // Otherwise, mark the previous block for conclusion (if there was any).
        if (bodyLine != null)
            blockBody.add(bodyLine)
        else
            isBlockConclusionMarked = true

        // If the content style is changed at a non-standard position, issue a warning.
        if (newContentStyle != null && !didConcludeBlock)
            log(WARN, "Changing content style in a row where no new head-body-tail block starts.")

        // If the reset cell is non-empty, conclude the previous shared body layout region
        // resp. shared column layout region.
        table.getString(row, "@reset layout")?.also { reset ->
            when (reset) {
                "body" -> {
                    concludeBlockGroup()
                    if (!didConcludeBlock)
                        log(WARN, "Resetting body layout in a row where no new head-body-tail block starts.")
                }
                "column" -> {
                    concludeBlockGroup()
                    concludeSectionGroup()
                    if (!didConcludeSection)
                        log(WARN, "Resetting column layout in a row where no new section starts.")
                }
                else -> log(WARN, "'$reset' in column '@reset layout' is not body/column.")
            }
        }

        didConcludeSection = false
        didConcludeBlock = false
    }

    // Conclude all open credits elements that haven't been concluded yet.
    concludeBlock(0f)
    concludeColumn(0f, 1)
    concludeSection(0f)
    concludeBlockGroup()
    concludeSectionGroup()
    concludePage(pageStyle!! /* we just need to put anything in here */)

    return Pair(log, pages)
}
