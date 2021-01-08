package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.Severity.WARN
import com.loadingbyte.cinecred.l10n
import com.loadingbyte.cinecred.l10nAll
import com.loadingbyte.cinecred.project.*
import org.apache.commons.csv.CSVFormat
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


class ParserMsg(val lineNo: Int, val severity: Severity, val msg: String)

private enum class BreakAlign { BODY_COLUMNS, HEAD_AND_TAIL }


fun readCredits(
    csvFile: Path,
    styling: Styling,
    pictureLoaders: Map<Path, Lazy<Picture?>>
): Pair<List<ParserMsg>, List<Page>?> {
    // Note: We use maps whose keys are case insensitive here because style references should be case insensitive.
    val pageStyleMap = styling.pageStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }
    val contentStyleMap = styling.contentStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }

    // Put the picture loaders into a map whose keys are all possible variations of referencing the picture loaders.
    // Once again use a map with case-insensitive keys.
    val pictureLoaderMap = pictureLoaders.asSequence().flatMap { (path, pictureLoader) ->
        // Allow the user to use an arbitrary number of parent components.
        // For example, the path "a/b/c.png" could be expressed as "c.png", "", "b/c.png", or "a/b/c.png".
        (0 until path.nameCount).asSequence()
            .map { idx -> path.subpath(idx, path.nameCount).toString() }
            // Allow both Windows an Unix file separators.
            .flatMap { key ->
                listOf(
                    key.replace(File.separatorChar, '/') to pictureLoader,
                    key.replace(File.separatorChar, '\\') to pictureLoader
                )
            }
    }.toMap(TreeMap(String.CASE_INSENSITIVE_ORDER))

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
        log.add(ParserMsg(1, Severity.ERROR, l10n("projectIO.credits.noTableHeader")))
        return Pair(log, null)
    }

    val table = Table(
        log, lines, l10nPrefix = "projectIO.credits.table.",
        l10nColNames = listOf("pageStyle", "breakAlign", "columnPos", "contentStyle", "vGap", "head", "body", "tail")
    )

    // The current content style. This variable is special because a content style stays valid until the next
    // explicit content style declaration.
    var contentStyle: ContentStyle? = null

    // The vertical gap that should be inserted AFTER the next CONCLUDED credits element. If multiple credits
    // elements will be concluded at the same time (e.g., a block, a column, and a section), the most significant
    // credits element will receive the gap (in our example, that would be the section).
    var vGapAccumulator = 0f

    // This variable is set to true when the current block should be concluded as soon as a row with some non-empty
    // body cell arrives. It is used in cases where the previous block is known to be complete (e.g., because the
    // content style changed or there was an empty row), but it cannot be concluded yet because it is unknown whether
    // only the block or the block and more higher-order elements like a section will be concluded at the same time.
    // In the latter case, the vertical gap accumulator would go towards the section and not the block. So we have
    // to wait and see.
    var isBlockConclusionMarked = false

    // This variable is set to true when a "@Break Align" indication is encountered. It will cause the respective
    // current group to be concluded as soon as the current block is concluded.
    var isBodyColsGroupConclusionMarked = false
    var isHeadTailGroupConclusionMarked = false

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
    var blockStyle: ContentStyle? = null
    var blockHead: String? = null
    val blockBody = mutableListOf<BodyElement>()
    var blockTail: String? = null

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

    fun concludeSection(vGapAfter: Float) {
        if (sectionColumns.isNotEmpty())
            pageSections.add(Section(sectionColumns, vGapAfter))
        sectionColumns.clear()
    }

    fun concludeColumn() {
        if (columnBlocks.isNotEmpty())
            sectionColumns.add(Column(columnPosOffsetPx, columnBlocks))
        columnPosOffsetPx = 0f
        columnBlocks.clear()
    }

    fun concludeBlock(vGapAfter: Float) {
        if (blockBody.isNotEmpty()) {
            val block = Block(blockStyle!!, blockHead, blockBody, blockTail, vGapAfter)
            columnBlocks.add(block)
            alignBodyColsGroupsGroup.add(block)
            alignHeadTailGroupsGroup.add(block)
        }
        blockStyle = contentStyle
        blockHead = null
        blockBody.clear()
        blockTail = null

        if (isBodyColsGroupConclusionMarked)
            concludeAlignBodyColsGroupsGroup()
        if (isHeadTailGroupConclusionMarked)
            concludeAlignHeadTailGroupsGroup()
        isBodyColsGroupConclusionMarked = false
        isHeadTailGroupConclusionMarked = false
    }

    for (row in 0 until table.numRows) {
        // Helper function to shorten logging calls.
        fun log(kind: Severity, msg: String) = log.add(ParserMsg(table.getLineNo(row), kind, msg))

        // An empty row implicitly means a vertical gap of one unit after the appropriate credits element.
        if (table.isEmpty(row))
            vGapAccumulator += styling.global.unitVGapPx
        // Also add explicit vertical gaps to the accumulator.
        table.getFiniteFloat(row, "vGap", nonNegative = true)?.let {
            vGapAccumulator += it * styling.global.unitVGapPx
        }

        // If the page style cell is non-empty, conclude the previous page (if there was any) and start a new one.
        table.getLookup(row, "pageStyle", pageStyleMap)?.let { newPageStyle ->
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
                l10n("projectIO.credits.noPageStylesAvailable", pageStyle!!.name)
            } else {
                pageStyle = styling.pageStyles.firstOrNull()
                l10n("projectIO.credits.noPageStyleSpecified", pageStyle!!.name)
            }
            log(WARN, msg)
        }

        // If the column cell is non-empty, conclude the previous column (if there was any) and start a new one.
        // If the column cell contains "Wrap" (or any localized variant of the same keyword), also conclude the
        // previous section and start a new one.
        val wrapKey = "projectIO.credits.table.wrap"
        table.get(row, "columnPos", {
            val primary = l10n(wrapKey)
            val alternatives = l10nAll(wrapKey).toMutableSet().apply { remove(primary) }.joinToString("/") { "\"$it\"" }
            l10n("projectIO.credits.columnPosTypeDesc", primary, alternatives)
        }) { str ->
            // Remove all occurrences of all wrap keywords from the string.
            var modStr = str
            for (wrapKeyword in l10nAll(wrapKey))
                modStr = modStr.replace(wrapKeyword, "", ignoreCase = true)
            // If we have removed something, there was a wrap keyword.
            val wrap = modStr != str
            // Convert the remaining position offset to a float, or use 0 if the str only contained a wrap keyword.
            val posOffsetPx = if (modStr.isBlank()) 0f else modStr.trim().toFiniteFloat()
            // Yield the result.
            Pair(wrap, posOffsetPx)
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
        // Use the new content style from now on until the next explicit content style declaration.
        table.getLookup(row, "contentStyle", contentStyleMap)?.let { newContentStyle ->
            contentStyle = newContentStyle
            isBlockConclusionMarked = true
        }

        val newHead = table.getString(row, "head")
        val newTail = table.getString(row, "tail")

        // Get the body element, which may either be a string or a (optionally scaled) picture.
        val bodyElem = table.get(row, "body", { l10n("projectIO.credits.bodyElemTypeDesc") }) { str ->
            // Case 1: Unscaled picture
            pictureLoaderMap[str]?.value?.let { pic -> return@get BodyElement.Pic(pic) }
            // Case 2: Scaled picture
            pictureLoaderMap[str.substringBeforeLast(' ').trim()]?.value?.let { pic ->
                val scaleHint = str.substringAfterLast(' ').trim()
                val scaledPic = when {
                    scaleHint.startsWith('x') ->
                        pic.scaled(scaleHint.drop(1).toFiniteFloat(nonNegative = true, nonZero = true) / pic.height)
                    scaleHint.endsWith('x') ->
                        pic.scaled(scaleHint.dropLast(1).toFiniteFloat(nonNegative = true, nonZero = true) / pic.width)
                    else -> throw IllegalArgumentException()
                }
                return@get BodyElement.Pic(scaledPic)
            }
            // Case 3: String
            BodyElement.Str(str)
        }

        // If either of the head or tail cells is non-empty, or if the body cell is non empty and the conclusion of the
        // previous block has been marked, conclude the previous block (if there was any) and start a new one.
        if (newHead != null || newTail != null || (isBlockConclusionMarked && bodyElem != null)) {
            concludeBlock(vGapAccumulator)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
            blockHead = newHead
            blockTail = newTail
        }

        // If no content style has been declared at the point where the first block starts, issue a warning and
        // fall back to the content style defined first in the content style table, or, if that table is empty,
        // to the standard content style.
        if (contentStyle == null && (newHead != null || newTail != null || bodyElem != null)) {
            val msg = if (styling.contentStyles.isEmpty()) {
                contentStyle = STANDARD_CONTENT_STYLE
                l10n("projectIO.credits.noContentStylesAvailable", contentStyle!!.name)
            } else {
                contentStyle = styling.contentStyles[0]
                l10n("projectIO.credits.noContentStyleSpecified", contentStyle!!.name)
            }
            blockStyle = contentStyle
            log(WARN, msg)
        }

        // If the line has a head or tail even though the current content style doesn't support it,
        // issue a warning and discard the head resp. tail.
        if (newHead != null && !contentStyle!!.hasHead) {
            blockHead = null
            log(WARN, l10n("projectIO.credits.headUnsupported", contentStyle!!.name, newHead))
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            log(WARN, l10n("projectIO.credits.tailUnsupported", contentStyle!!.name, newTail))
        }

        // If the body cell is non-empty, add its content to the current block.
        if (bodyElem != null)
            blockBody.add(bodyElem)
        // Otherwise, if the row didn't just start a new block,
        // mark the previous block for conclusion (if there was any).
        else if (newHead == null && newTail == null)
            isBlockConclusionMarked = true

        // If the break alignment cell is non-empty, mark the specified previous alignment group for conclusion.
        // We cannot conclude it right now because we have to wait for the current block to be concluded.
        table.getEnum<BreakAlign>(row, "breakAlign")?.let { breakAlign ->
            when (breakAlign) {
                BreakAlign.BODY_COLUMNS -> isBodyColsGroupConclusionMarked = true
                BreakAlign.HEAD_AND_TAIL -> isHeadTailGroupConclusionMarked = true
            }
        }
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
