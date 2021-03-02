package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.Picture
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nAll
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.apache.commons.csv.CSVFormat
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*


class ParserMsg(val lineNo: Int, val severity: Severity, val msg: String)

private enum class BreakAlign { BODY_COLUMNS, HEAD_AND_TAIL }


private const val WRAP_KEY = "projectIO.credits.table.wrap"
private val WRAP_KEYWORDS = l10nAll(WRAP_KEY)
private val WRAP_PRIMARY_KW = l10n(WRAP_KEY)
private val WRAP_ALT_KWS = WRAP_KEYWORDS.toMutableSet()
    .apply { remove(WRAP_PRIMARY_KW) }.joinToString("/") { "\"$it\"" }

private const val CROP_KEY = "projectIO.credits.table.crop"
private val CROP_KEYWORDS = l10nAll(CROP_KEY).toCollection(TreeSet(String.CASE_INSENSITIVE_ORDER))
private val CROP_PRIMARY_KW = l10n(CROP_KEY)
private val CROP_ALT_KWS = l10nAll(CROP_KEY).toMutableSet()
    .apply { remove(CROP_PRIMARY_KW) }.joinToString("/") { "\"$it\"" }


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

    // Helper function to shorten logging calls.
    fun warn(row: Int, msg: String) = log.add(ParserMsg(table.getLineNo(row), Severity.WARN, msg))

    // The current content style. This variable is special because a content style stays valid until the next
    // explicit content style declaration.
    var contentStyle: ContentStyle? = null

    // The vertical gap that should be inserted AFTER the next CONCLUDED credits element. If multiple credits
    // elements will be concluded at the same time (e.g., a block, a column, and a segment), the most significant
    // credits element will receive the gap (in our example, that would be the segment).
    var vGapAccumulator = 0f

    // This variable is set to true when the current block should be concluded as soon as a row with some non-empty
    // body cell arrives. It is used in cases where the previous block is known to be complete (e.g., because the
    // content style changed or there was an empty row), but it cannot be concluded yet because it is unknown whether
    // only the block or the block and more higher-order elements like a segment will be concluded at the same time.
    // In the latter case, the vertical gap accumulator would go towards the segment and not the block. So we have
    // to wait and see.
    var isBlockConclusionMarked = false

    // This variable is set to true when a "@Break Align" indication is encountered. It will cause the respective
    // current group to be concluded as soon as the current block is concluded.
    var isBodyColsGroupConclusionMarked = false
    var isHeadTailGroupConclusionMarked = false

    // Final result
    val pages = mutableListOf<Page>()
    // Current page
    val pageStages = mutableListOf<Stage>()
    val pageAlignBodyColsGroupsGroups = mutableListOf<ImmutableList<Block>>()
    val pageAlignHeadTailGroupsGroups = mutableListOf<ImmutableList<Block>>()
    // Segment and block groups
    var alignBodyColsGroupsGroup = mutableListOf<Block>()
    var alignHeadTailGroupsGroup = mutableListOf<Block>()
    // Current stage
    var stageStyle: PageStyle? = null
    val stageSegments = mutableListOf<Segment>()
    // Current segment
    val segmentColumns = mutableListOf<Column>()
    // Current column
    var columnPosOffsetPx = 0f
    val columnBlocks = mutableListOf<Block>()
    // Current block
    var blockStyle: ContentStyle? = null
    var blockHead: String? = null
    val blockBody = mutableListOf<BodyElement>()
    var blockTail: String? = null
    // Keep track where the current head and tail have been declared. This is used by an error message.
    var blockHeadDeclaredRow = 0
    var blockTailDeclaredRow = 0

    fun concludePage() {
        // Note: In concludeStage(), we allow empty scroll stages. However, empty scroll stages do only make sense
        // when they don't sit next to another scroll stages and when they are not alone on a page.
        // We remove the empty scroll stages that don't make sense.
        if (pageStages.isNotEmpty() && !(pageStages.size == 1 && pageStages[0].segments.isEmpty())) {
            var idx = 0
            while (idx < pageStages.size) {
                val prevStageDoesNotScroll = idx == 0 || pageStages[idx - 1].style.behavior != PageBehavior.SCROLL
                val nextStageDoesNotScroll = idx == pageStages.lastIndex ||
                        pageStages[idx + 1].style.behavior != PageBehavior.SCROLL
                if (pageStages[idx].segments.isNotEmpty() || prevStageDoesNotScroll && nextStageDoesNotScroll)
                    idx++
                else
                    pageStages.removeAt(idx)
            }
            val page = Page(
                pageStages.toImmutableList(),
                pageAlignBodyColsGroupsGroups.toImmutableList(),
                pageAlignHeadTailGroupsGroups.toImmutableList()
            )
            pages.add(page)
        }
        pageStages.clear()
        pageAlignBodyColsGroupsGroups.clear()
        pageAlignHeadTailGroupsGroups.clear()
    }

    fun concludeAlignBodyColsGroupsGroup() {
        if (alignBodyColsGroupsGroup.isNotEmpty())
            pageAlignBodyColsGroupsGroups.add(alignBodyColsGroupsGroup.toImmutableList())
        alignBodyColsGroupsGroup = mutableListOf()
    }

    fun concludeAlignHeadTailGroupsGroup() {
        if (alignHeadTailGroupsGroup.isNotEmpty())
            pageAlignHeadTailGroupsGroups.add(alignHeadTailGroupsGroup.toImmutableList())
        alignHeadTailGroupsGroup = mutableListOf()
    }

    fun concludeStage(vGapAfter: Float, newStageStyle: PageStyle) {
        // Note: We allow that empty scroll stages to connect card stages.
        if (stageSegments.isNotEmpty() || stageStyle?.behavior == PageBehavior.SCROLL)
            pageStages.add(Stage(stageStyle!!, stageSegments.toImmutableList(), vGapAfter))
        stageStyle = newStageStyle
        stageSegments.clear()
    }

    fun concludeSegment(vGapAfter: Float) {
        if (segmentColumns.isNotEmpty())
            stageSegments.add(Segment(segmentColumns.toImmutableList(), vGapAfter))
        segmentColumns.clear()
    }

    fun concludeColumn() {
        if (columnBlocks.isNotEmpty())
            segmentColumns.add(Column(columnPosOffsetPx, columnBlocks.toImmutableList()))
        columnPosOffsetPx = 0f
        columnBlocks.clear()
    }

    fun concludeBlock(vGapAfter: Float) {
        if (blockBody.isNotEmpty()) {
            val block = Block(blockStyle!!, blockHead, blockBody.toImmutableList(), blockTail, vGapAfter)
            columnBlocks.add(block)
            alignBodyColsGroupsGroup.add(block)
            alignHeadTailGroupsGroup.add(block)
        } else {
            if (blockHead != null)
                warn(blockHeadDeclaredRow, l10n("projectIO.credits.unusedHead", blockHead))
            if (blockTail != null)
                warn(blockTailDeclaredRow, l10n("projectIO.credits.unusedTail", blockTail))
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
        // An empty row implicitly means a vertical gap of one unit after the appropriate credits element.
        if (table.isEmpty(row))
            vGapAccumulator += styling.global.unitVGapPx
        // Also add explicit vertical gaps to the accumulator.
        table.getFiniteFloat(row, "vGap", nonNeg = true)?.let {
            vGapAccumulator += it * styling.global.unitVGapPx
        }

        // If the page style cell is non-empty, conclude the previous stage (if there was any) and start a new one.
        table.getLookup(row, "pageStyle", pageStyleMap)?.let { newPageStyle ->
            concludeBlock(0f)
            concludeColumn()
            concludeSegment(0f)
            concludeStage(vGapAccumulator, newPageStyle)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false

            // If we are not melting the just concluded stage with the next one, also conclude the page.
            if (pageStages.lastOrNull()?.style?.meltWithNext != true && !newPageStyle.meltWithPrev) {
                concludeAlignBodyColsGroupsGroup()
                concludeAlignHeadTailGroupsGroup()
                concludePage()
            }
        }

        // If the first stage's style is not explicitly declared, issue a warning and fall back to the
        // standard page style.
        if (stageStyle == null) {
            stageStyle = STANDARD_PAGE_STYLE
            if (styling.pageStyles.isEmpty())
                warn(row, l10n("projectIO.credits.noPageStylesAvailable"))
            else
                warn(row, l10n("projectIO.credits.noPageStyleSpecified"))
        }

        // If the column cell is non-empty, conclude the previous column (if there was any) and start a new one.
        // If the column cell contains "Wrap" (or any localized variant of the same keyword), also conclude the
        // previous segment and start a new one.
        table.get(row, "columnPos") { str ->
            // Remove all occurrences of all wrap keywords from the string.
            var modStr = str
            for (wrapKeyword in l10nAll(WRAP_KEY))
                modStr = modStr.replace(wrapKeyword, "", ignoreCase = true)
            // If we have removed something, there was a wrap keyword.
            val wrap = modStr != str
            try {
                // Convert the remaining position offset to a float, or use 0 if the str only contained a wrap keyword.
                val posOffsetPx = if (modStr.isBlank()) 0f else modStr.trim().toFiniteFloat()
                // Yield the result.
                Pair(wrap, posOffsetPx)
            } catch (_: NumberFormatException) {
                throw Table.IllFormattedCell(
                    l10n("projectIO.credits.illFormattedColumnPos", WRAP_PRIMARY_KW, WRAP_ALT_KWS)
                )
            }
        }?.let { (wrap, posOffsetPx) ->
            concludeBlock(0f)
            concludeColumn()
            if (wrap)
                concludeSegment(vGapAccumulator)
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
        val bodyElem = table.get(row, "body") { str ->
            // Remove up to four space-separated suffixes from the string and each time check whether the remaining
            // string is a picture. If that is the case, try to parse the suffixes as scaling and cropping hints.
            // Theoretically, we could stop after removing two suffixes and not having found a picture by then because
            // there are only two possible suffixes available (scaling and cropping). However, sometimes the user
            // might accidentally add more suffixes. If we find that this is the case, we throw an exception and
            // thereby warn the user.
            var picName = str
            repeat(5) {
                pictureLoaderMap[picName]?.let { picLoader ->
                    val origPic = picLoader.value
                        ?: throw Table.IllFormattedCell(l10n("projectIO.credits.illFormattedBodyElemCorrupt"))

                    var pic = origPic
                    val picHints = str.drop(picName.length).split(' ')
                    // Step 1: Crop the picture if the user specified it.
                    if (picHints.any { it in CROP_KEYWORDS })
                        pic = when (pic) {
                            is Picture.SVG -> pic.cropped()
                            is Picture.PDF -> pic.cropped()
                            // Raster images cannot be cropped.
                            is Picture.Raster -> throw Table.IllFormattedCell(
                                l10n("projectIO.credits.illFormattedBodyElemRasterCrop", CROP_PRIMARY_KW, CROP_ALT_KWS)
                            )
                        }
                    // Step 2: Only now, after the picture has potentially been cropped, we can apply scaling hints.
                    for (hint in picHints)
                        try {
                            pic = when {
                                hint.isBlank() || hint in CROP_KEYWORDS -> continue
                                hint.startsWith('x') ->
                                    pic.scaled(hint.drop(1).toFiniteFloat(nonNeg = true, non0 = true) / pic.height)
                                hint.endsWith('x') ->
                                    pic.scaled(hint.dropLast(1).toFiniteFloat(nonNeg = true, non0 = true) / pic.width)
                                // The user supplied an unknown suffix.
                                else -> throw IllegalArgumentException()
                            }
                        } catch (_: IllegalArgumentException) {
                            throw Table.IllFormattedCell(
                                l10n("projectIO.credits.illFormattedBodyElem", CROP_PRIMARY_KW, CROP_ALT_KWS)
                            )
                        }
                    return@get BodyElement.Pic(pic)
                }
                picName = picName.substringBeforeLast(' ').trim()
            }

            // We could not find an image in the body element string. Just use the string as-is.
            BodyElement.Str(str)
        }

        // If either of the head or tail cells is non-empty, or if the body cell is non empty and the conclusion of the
        // previous block has been marked, conclude the previous block (if there was any) and start a new one.
        if (newHead != null || newTail != null || (isBlockConclusionMarked && bodyElem != null)) {
            concludeBlock(vGapAccumulator)
            vGapAccumulator = 0f
            isBlockConclusionMarked = false
            if (newHead != null) {
                blockHead = newHead
                blockHeadDeclaredRow = row
            }
            if (newTail != null) {
                blockTail = newTail
                blockTailDeclaredRow = row
            }
        }

        // If no content style has been declared at the point where the first block starts, issue a warning and
        // fall back to the standard content style.
        if (contentStyle == null && (newHead != null || newTail != null || bodyElem != null)) {
            contentStyle = STANDARD_CONTENT_STYLE
            blockStyle = contentStyle
            if (styling.contentStyles.isEmpty())
                warn(row, l10n("projectIO.credits.noContentStylesAvailable"))
            else
                warn(row, l10n("projectIO.credits.noContentStyleSpecified"))
        }

        // If the line has a head or tail even though the current content style doesn't support it,
        // issue a warning and discard the head resp. tail.
        if (newHead != null && !contentStyle!!.hasHead) {
            blockHead = null
            warn(row, l10n("projectIO.credits.headUnsupported", newHead, contentStyle!!.name))
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            warn(row, l10n("projectIO.credits.tailUnsupported", newTail, contentStyle!!.name))
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
    concludeSegment(0f)
    concludeAlignBodyColsGroupsGroup()
    concludeAlignHeadTailGroupsGroup()
    concludeStage(0f, stageStyle!! /* we just need to put anything in here */)
    concludePage()

    return Pair(log, pages)
}
