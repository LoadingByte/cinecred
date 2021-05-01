package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.Picture
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nAll
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension


private val CREDITS_FILE_EXTS = listOf("csv")

fun locateCreditsFile(projectDir: Path): Pair<Path?, List<ParserMsg>> {
    fun supExtsStr() = CREDITS_FILE_EXTS.joinToString(" | ")

    var creditsFile: Path? = null
    val log = mutableListOf<ParserMsg>()

    val candidates = getCreditsFileCandidates(projectDir).iterator()
    if (!candidates.hasNext())
        log.add(ParserMsg(null, null, null, WARN, l10n("projectIO.credits.noCreditsFile", supExtsStr())))
    else {
        creditsFile = candidates.next()
        if (candidates.hasNext()) {
            val msg = l10n("projectIO.credits.multipleCreditsFiles", supExtsStr(), creditsFile.fileName)
            log.add(ParserMsg(null, null, null, WARN, msg))
        }
    }

    return Pair(creditsFile, log)
}

fun getCreditsFileCandidates(projectDir: Path): Stream<Path> =
    Files.list(projectDir)
        .filter { Files.isRegularFile(it) && hasCreditsFileName(it) }
        .sorted(compareBy { CREDITS_FILE_EXTS.indexOf(it.extension) })

fun hasCreditsFileName(file: Path) =
    file.nameWithoutExtension.equals("Credits", ignoreCase = true) && file.extension in CREDITS_FILE_EXTS


fun loadCreditsFile(csvFile: Path): List<CSVRecord> {
    // We trim the unicode character "ZERO WIDTH NO-BREAK SPACE" which is added by Excel for some reason.
    val csvStr = Files.readString(csvFile).trim(0xFEFF.toChar())

    // Parse the CSV file into a list of records.
    return CSVFormat.DEFAULT.parse(StringReader(csvStr)).records
}


fun readCredits(
    csv: List<CSVRecord>,
    styling: Styling,
    pictureLoaders: Map<Path, Lazy<Picture?>>
): Pair<List<Page>?, List<ParserMsg>> {
    // Try to find the table in the CSV.
    val table = Table(
        csv, l10nPrefix = "projectIO.credits.table.",
        l10nColNames = listOf("pageStyle", "breakAlign", "columnPos", "contentStyle", "vGap", "head", "body", "tail")
    )

    // Read the table.
    val pages = CreditsReader(table, styling, pictureLoaders).read()
    return Pair(pages, table.log)
}


private class CreditsReader(
    val table: Table,
    val styling: Styling,
    pictureLoaders: Map<Path, Lazy<Picture?>>
) {

    /* **************************************
       ************ CREATE LOOKUPS **********
       ************************************** */

    // Note: We use maps whose keys are case insensitive here because style references should be case insensitive.
    val pageStyleMap = styling.pageStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }
    val contentStyleMap = styling.contentStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }
    val letterStyleMap = styling.letterStyles.associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER)) { it.name }

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


    /* *******************************************
       ************ STATE + CONCLUSIONS **********
       ******************************************* */

    // The row that is currently being read.
    var row = 0

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
    // In the latter case, the vertical gap accumulator would be given to the segment and not the block. So we have
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
    var blockHead: StyledString? = null
    val blockBody = mutableListOf<BodyElement>()
    var blockTail: StyledString? = null

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
                table.log(blockHeadDeclaredRow, "head", WARN, l10n("projectIO.credits.unusedHeadOrTail", blockHead))
            if (blockTail != null)
                table.log(blockTailDeclaredRow, "tail", WARN, l10n("projectIO.credits.unusedHeadOrTail", blockTail))
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


    /* **************************************
       ************ ACTUAL PARSING **********
       ************************************** */

    fun read(): List<Page> {
        for (row in 0 until table.numRows) {
            this.row = row
            readRow()
        }

        // Conclude all open credits elements that haven't been concluded yet.
        concludeBlock(0f)
        concludeColumn()
        concludeSegment(0f)
        concludeAlignBodyColsGroupsGroup()
        concludeAlignHeadTailGroupsGroup()
        concludeStage(0f, PLACEHOLDER_PAGE_STYLE /* we just need to put some arbitrary thing in here */)
        concludePage()

        return pages
    }

    fun readRow() {
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

            // If we are not melting the previous stage with the future one, concluded the stage and the current page.
            val prevPageStyle = pageStages.lastOrNull()?.style
            if (!(prevPageStyle?.behavior == PageBehavior.SCROLL && prevPageStyle.scrollMeltWithNext) &&
                !(newPageStyle.behavior == PageBehavior.SCROLL && newPageStyle.scrollMeltWithPrev)
            ) {
                concludeAlignBodyColsGroupsGroup()
                concludeAlignHeadTailGroupsGroup()
                concludePage()
            }
        }

        // If the first stage's style is not explicitly declared, issue a warning and fall back to the
        // placeholder page style.
        if (stageStyle == null) {
            stageStyle = PLACEHOLDER_PAGE_STYLE
            if (styling.pageStyles.isEmpty())
                table.log(row, null, WARN, l10n("projectIO.credits.noPageStylesAvailable"))
            else
                table.log(row, null, WARN, l10n("projectIO.credits.noPageStyleSpecified"))
        }

        // If the column cell is non-empty, conclude the previous column (if there was any) and start a new one.
        // If the column cell contains "Wrap" (or any localized variant of the same keyword), also conclude the
        // previous segment and start a new one.
        table.getString(row, "columnPos")?.let { str ->
            var wrap = false
            var posOffsetPx = 0f
            var erroneous = false
            for (hint in str.split(' '))
                when {
                    hint.isBlank() -> continue
                    hint in WRAP_KW -> wrap = true
                    else -> try {
                        posOffsetPx = hint.toFiniteFloat()
                    } catch (_: NumberFormatException) {
                        erroneous = true
                    }
                }

            if (erroneous) {
                val msg = l10n("projectIO.credits.illFormattedColumnPos", WRAP_KW.msgPrimary, WRAP_KW.msgAlt)
                table.log(row, "columnPos", WARN, msg)
            }

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

        // Get the body element, which may either be a styled string or a (optionally scaled) picture.
        // If the last block's conclusion is marked, use the current content style and not the last block's
        // content style, which no longer applies to the newly read body element.
        val effectiveBlockStyle = if (isBlockConclusionMarked) contentStyle else blockStyle
        val bodyElem = getBodyElement("body", effectiveBlockStyle?.bodyLetterStyleName, noPic = false)

        // Get the head and tail, which may only be styled strings.
        // Because a non-empty head or tail always starts a new block, we can use the current content style.
        val newHead = (getBodyElement("head", contentStyle?.headLetterStyleName, noPic = true) as BodyElement.Str?)?.str
        val newTail = (getBodyElement("tail", contentStyle?.tailLetterStyleName, noPic = true) as BodyElement.Str?)?.str

        // If either head or tail is available, or if a body is available and the conclusion of the previous block
        // has been marked, conclude the previous block (if there was any) and start a new one.
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
        // fall back to the placeholder content style.
        if (contentStyle == null && (newHead != null || newTail != null || bodyElem != null)) {
            contentStyle = PLACEHOLDER_CONTENT_STYLE
            blockStyle = contentStyle
            if (styling.contentStyles.isEmpty())
                table.log(row, null, WARN, l10n("projectIO.credits.noContentStylesAvailable"))
            else
                table.log(row, null, WARN, l10n("projectIO.credits.noContentStyleSpecified"))
        }

        // If the line has a head or tail even though the current content style doesn't support it,
        // issue a warning and discard the head resp. tail.
        if (newHead != null && !contentStyle!!.hasHead) {
            blockHead = null
            table.log(row, "head", WARN, l10n("projectIO.credits.headOrTailUnsupported", contentStyle!!.name))
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            table.log(row, "tail", WARN, l10n("projectIO.credits.headOrTailUnsupported", contentStyle!!.name))
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

    fun getBodyElement(l10nColName: String, initLetterStyleName: String?, noPic: Boolean): BodyElement? {
        fun unknownLetterStyleMsg(name: String) =
            l10n("projectIO.credits.unknownLetterStyle", name, letterStyleMap.keys.joinToString(" | "))

        fun unknownTagMsg(tagKey: String) = l10n(
            "projectIO.credits.unknownTagKeyword",
            tagKey, "${STYLE_KW.msgPrimary} | ${PIC_KW.msgPrimary}", "${STYLE_KW.msgAlt} || ${PIC_KW.msgAlt}"
        )

        val str = table.getString(row, l10nColName) ?: return null
        val initLetterStyle = initLetterStyleName?.let { letterStyleMap[it] } ?: PLACEHOLDER_LETTER_STYLE

        var curLetterStyle = initLetterStyle
        val styledStr = mutableListOf<Pair<String, LetterStyle>>()
        var picture: Picture? = null
        parseTaggedString(str) { plain, tagKey, tagVal ->
            when {
                // When we encounter plaintext, add it to the styled string list using the current letter style.
                plain != null -> styledStr.add(Pair(plain, curLetterStyle))
                tagKey != null -> when (tagKey) {
                    // When we encounter a style tag, change the current letter style to the desired one.
                    in STYLE_KW -> when (tagVal) {
                        null -> curLetterStyle = initLetterStyle
                        else -> when (val newLetterStyle = letterStyleMap[tagVal]) {
                            null -> table.log(row, l10nColName, WARN, unknownLetterStyleMsg(tagVal))
                            else -> curLetterStyle = newLetterStyle
                        }
                    }
                    // When we encounter a picture tag, read it and remember the loaded picture for now.
                    // We can't immediately return because we want to issue a warning if the picture tag is not lone.
                    in PIC_KW -> when {
                        noPic -> table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureDisallowed"))
                        else -> when (picture) {
                            null -> picture = getPicture(l10nColName, tagVal)
                            else -> table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureNotLone"))
                        }
                    }
                    else -> table.log(row, l10nColName, WARN, unknownTagMsg(tagKey))
                }
            }
        }

        return when {
            picture != null -> {
                if (styledStr.isNotEmpty())
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureNotLone"))
                BodyElement.Pic(picture!!)
            }
            styledStr.isNotEmpty() -> BodyElement.Str(styledStr)
            else -> null
        }
    }

    fun getPicture(l10nColName: String, tagVal: String?): Picture? {
        fun illFormattedMsg() = l10n("projectIO.credits.pictureIllFormatted", CROP_KW.msgPrimary, CROP_KW.msgAlt)
        fun rasterCropMsg() = l10n("projectIO.credits.pictureRasterCrop", CROP_KW.msgPrimary, CROP_KW.msgAlt)
        fun hintsUnknownMsg(hints: List<String>) =
            l10n("projectIO.credits.pictureHintsUnknown", hints.joinToString(" "), illFormattedMsg())

        if (tagVal == null) {
            table.log(row, l10nColName, WARN, illFormattedMsg())
            return null
        }

        // Remove arbitrary many space-separated suffixes from the string and each time check whether the remaining
        // string is a picture. If that is the case, try to parse the suffixes as scaling and cropping hints.
        // Theoretically, we could stop after removing two suffixes and not having found a picture by then because
        // there are only two possible suffixes available (scaling and cropping). However, sometimes the user
        // might accidentally add more suffixes. If we find that this is the case, we inform the user.
        var splitIdx = tagVal.length
        do {
            val picName = tagVal.take(splitIdx).trim()

            pictureLoaderMap[picName]?.let { picLoader ->
                var pic = picLoader.value
                if (pic == null) {
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureCorrupt", picName))
                    return null
                }

                val picHints = tagVal.drop(splitIdx).split(' ')

                // Step 1: Crop the picture if the user wishes that.
                if (picHints.any { it in CROP_KW })
                    when (pic) {
                        is Picture.SVG -> pic = pic.cropped()
                        is Picture.PDF -> pic = pic.cropped()
                        // Raster images cannot be cropped.
                        is Picture.Raster -> table.log(row, l10nColName, WARN, rasterCropMsg())
                    }

                // Step 2: Only now, after the picture has potentially been cropped, we can apply the scaling hint.
                val unknownHints = mutableListOf<String>()
                for (hint in picHints)
                    try {
                        return when {
                            hint.isBlank() || hint in CROP_KW -> continue
                            hint.startsWith('x') ->
                                pic.scaled(hint.drop(1).toFiniteFloat(nonNeg = true, non0 = true) / pic.height)
                            hint.endsWith('x') ->
                                pic.scaled(hint.dropLast(1).toFiniteFloat(nonNeg = true, non0 = true) / pic.width)
                            else -> throw IllegalArgumentException()
                        }
                    } catch (_: IllegalArgumentException) {
                        // Note that this catch clause is mainly here because toFiniteFloat() may throw exceptions.
                        unknownHints.add(hint)
                    }
                // If unknown hints were encountered, warn the user.
                if (unknownHints.isNotEmpty())
                    table.log(row, l10nColName, WARN, hintsUnknownMsg(unknownHints))

                return pic
            }

            splitIdx = picName.lastIndexOf(' ')
        } while (splitIdx != -1)

        // No picture matching the tag value found.
        table.log(row, l10nColName, WARN, illFormattedMsg())
        return null
    }


    /* *************************************
       ************ MISCELLANEOUS **********
       ************************************* */

    enum class BreakAlign { BODY_COLUMNS, HEAD_AND_TAIL }


    companion object {

        val WRAP_KW = Keyword("projectIO.credits.table.wrap")
        val CROP_KW = Keyword("projectIO.credits.table.crop")
        val STYLE_KW = Keyword("projectIO.credits.table.style")
        val PIC_KW = Keyword("projectIO.credits.table.pic")

        inline fun parseTaggedString(str: String, callback: (String?, String?, String?) -> Unit) {
            var idx = 0

            while (true) {
                val tagStartIdx = str.indexOfUnescaped('{', startIdx = idx)
                if (tagStartIdx == -1)
                    break
                val tagEndIdx = str.indexOfUnescaped('}', startIdx = tagStartIdx + 1)
                if (tagEndIdx == -1)
                    break

                if (tagStartIdx != idx)
                    callback(str.substring(idx, tagStartIdx).unescape('{'), null, null)

                val tag = str.substring(tagStartIdx + 1, tagEndIdx).unescape('}').trim()
                val keyEndIdx = tag.indexOf(' ')
                if (keyEndIdx == -1)
                    callback(null, tag, null)
                else
                    callback(null, tag.substring(0, keyEndIdx), tag.substring(keyEndIdx + 1))

                idx = tagEndIdx + 1
            }

            if (idx != str.length)
                callback(str.substring(idx), null, null)
        }

        fun String.indexOfUnescaped(char: Char, startIdx: Int): Int {
            val idx = indexOf(char, startIdx)
            if (idx <= 0)
                return idx

            val numBackslashes = idx - 1 - indexOfLast(startIdx = idx - 1) { it != '\\' }
            return if (numBackslashes % 2 == 0)
                idx
            else
                indexOfUnescaped(char, idx + 1)
        }

        inline fun String.indexOfLast(startIdx: Int, predicate: (Char) -> Boolean): Int {
            for (idx in startIdx.coerceIn(0, lastIndex) downTo 0) {
                if (predicate(this[idx])) {
                    return idx
                }
            }
            return -1
        }

        fun String.unescape(char: Char): String {
            var escIdx = indexOf("\\$char")
            if (escIdx == -1)
                if (isEmpty() || last() != '\\')
                    return this
                else
                    escIdx = length - 1

            val numBackslashes = escIdx - indexOfLast(startIdx = escIdx) { it != '\\' }
            return substring(0, escIdx - (numBackslashes - 1) / 2) + substring(escIdx + 1).unescape(char)
        }

    }


    class Keyword(key: String) {

        private val kwSet: Set<String>
        val msgPrimary: String
        val msgAlt: String

        init {
            val primaryKw = l10n(key)
            val allKws = l10nAll(key)
            kwSet = allKws.toCollection(TreeSet(String.CASE_INSENSITIVE_ORDER))
            msgPrimary = primaryKw
            msgAlt = allKws.filter { it != primaryKw }.joinToString(" | ")
        }

        operator fun contains(str: String) = str in kwSet

    }

}
