package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.*


fun readCredits(
    spreadsheet: Spreadsheet,
    styling: Styling,
    pictureLoaders: Collection<PictureLoader>
): Triple<List<Page>, List<RuntimeGroup>, List<ParserMsg>> {
    // Try to find the table in the spreadsheet.
    val table = Table(
        spreadsheet, l10nPrefix = "projectIO.credits.table.", l10nColNames = listOf(
            "head", "body", "tail", "vGap", "contentStyle", "breakMatch", "spinePos", "vPos", "pageStyle", "pageRuntime"
        ), legacyColNames = mapOf(
            // 1.2.0 -> 1.3.0: The vertical gap is no longer abbreviated.
            "vGap" to listOf("Vert. Gap", "Senkr. Lücke"),
            // 1.2.0 -> 1.3.0: Cross-block alignment is renamed to matching.
            "breakMatch" to listOf("Break Align", "Breche Ausrichtung"),
            // 1.2.0 -> 1.3.0: The column position is renamed to spine position.
            "spinePos" to listOf("Column Pos.", "Spaltenposition")
        )
    )

    // Read the table.
    val (pages, runtimeGroups) = CreditsReader(table, styling, pictureLoaders).read()
    return Triple(pages, runtimeGroups, table.log)
}


private class CreditsReader(
    val table: Table,
    val styling: Styling,
    pictureLoaders: Collection<PictureLoader>
) {

    /* ************************************
       ********** CREATE LOOKUPS **********
       ************************************ */

    // Note: We use maps whose keys are case-insensitive here because style references should be case-insensitive.
    // We also reverse the list so that if there are duplicate names, the first style from the list will survive.
    inline fun <S> List<S>.map(n: (S) -> String) = asReversed().associateByTo(TreeMap(String.CASE_INSENSITIVE_ORDER), n)
    val pageStyleMap = styling.pageStyles.map(PageStyle::name)
    val contentStyleMap = styling.contentStyles.map(ContentStyle::name)
    val letterStyleMap = styling.letterStyles.map(LetterStyle::name)

    // Put the picture loaders into a map whose keys are the picture filenames. Also record all duplicate filenames.
    // Once again use a map with case-insensitive keys.
    val pictureLoaderMap: Map<String, PictureLoader>
    val duplicatePictures: Set<String>

    init {
        val loaderMap = TreeMap<String, PictureLoader>(String.CASE_INSENSITIVE_ORDER)
        val dupSet = TreeSet(String.CASE_INSENSITIVE_ORDER)
        for (pictureLoader in pictureLoaders) {
            val filename = pictureLoader.filename
            if (filename !in dupSet)
                if (filename !in loaderMap)
                    loaderMap[filename] = pictureLoader
                else {
                    loaderMap.remove(filename)
                    dupSet.add(filename)
                }
        }
        pictureLoaderMap = loaderMap
        duplicatePictures = dupSet
    }


    /* *****************************************
       ********** STATE + CONCLUSIONS **********
       ***************************************** */

    // The row that is currently being read.
    var row = 0

    // The page style and runtime configuration to use for the next started stage.
    var nextStageStyle: PageStyle? = null
    var nextStageRuntimeFrames: Int? = null
    var nextStageRuntimeGroupName: String? = null
    // The compound position offset to use for the next started compound.
    var nextCompoundVOffsetPx = 0.0
    // The spine position offset to use for the next started spine.
    var nextSpineHOffsetPx = 0.0
    // The current content style. This variable is special because a content style stays valid until the next
    // explicit content style declaration.
    var contentStyle: ContentStyle? = null

    // These variables keep track of the partitions which the next concluded block should belong to. These variables
    // remain valid until the next "@Break Match" indication.
    var matchHeadPartitionId = 0
    var matchBodyPartitionId = 0
    var matchTailPartitionId = 0

    // These variables keep track of the vertical gap that should be inserted AFTER the next CONCLUDED credits element.
    // If the gap is not specified explicitly in the vGap table column, it will be implicitly inferred from the number
    // of rows without head, body, and tail. If multiple credits elements will be concluded at the same time
    // (e.g., a block, a spine, and a lateral), the most significant credits element will receive the gap
    // (in our example, that would be the lateral).
    var explicitVGapInUnits: Double? = null
    var implicitVGapInUnits: Int = 0

    // This variable is set to true when the current block should be concluded as soon as a row with some non-empty
    // body cell arrives. It is used in cases where the previous block is known to be complete (e.g., because the
    // content style changed or there was an empty row), but it cannot be concluded yet because it is unknown whether
    // only the block or the block and more higher-order elements like a lateral will be concluded at the same time.
    // In the latter case, the vertical gap accumulator would be given to the lateral and not the block. So we have
    // to wait and see.
    var isBlockConclusionMarked = false
    // These variables work similar to the one above, but not only cause the conclusion of the current block, but also
    // the current spine, lateral, compound, or stage. They are used when a new spine, lateral, compound, or stage is
    // started, but it does not have its first block defined yet, leaving room for additional vertical gaps in the
    // meantime, which should of course still count to the vGapAfter of the just concluded spine, lateral, compound,
    // or stage.
    var isSpineConclusionMarked = false
    var isLateralConclusionMarked = false
    var isCompoundConclusionMarked = false
    var isStageConclusionMarked = false

    // Final result
    val pages = mutableListOf<Page>()
    val unnamedRuntimeGroups = mutableListOf<RuntimeGroup>()
    val namedRuntimeGroups = mutableMapOf<String, RuntimeGroup>()

    // Current page
    val pageStages = mutableListOf<Stage>()

    // Current stage
    var stageStyle: PageStyle? = null
    val stageCompounds = mutableListOf<Compound>()
    val stageLaterals = mutableListOf<Lateral>()
    var stageRuntimeFrames: Int? = null
    var stageRuntimeGroupName: String? = null

    // Current compound
    var compoundVOffsetPx = 0.0
    val compoundLaterals = mutableListOf<Lateral>()

    // Current lateral
    val lateralSpines = mutableListOf<Spine>()

    // Current spine
    var spineHOffsetPx = 0.0
    val spineBlocks = mutableListOf<Block>()

    // Current block
    var blockStyle: ContentStyle? = null
    var blockHead: StyledString? = null
    val blockBody = mutableListOf<BodyElement>()
    var blockTail: StyledString? = null
    var blockMatchHeadPartitionId = 0
    var blockMatchBodyPartitionId = 0
    var blockMatchTailPartitionId = 0

    // Keep track where each stage has been declared, for use in an error message.
    var nextStageDeclaredRow = 0
    var stageDeclaredRow = 0
    val stageDeclaredRows = mutableMapOf<Stage, Int>()
    // Keep track where the current head and tail have been declared. This is used by an error message.
    var blockHeadDeclaredRow = 0
    var blockTailDeclaredRow = 0

    fun concludePage() {
        // Note: In concludeStage(), we allow empty scroll stages. However, empty scroll stages do only make sense
        // when they don't sit next to another scroll stage and when they are not alone on a page.
        // We remove the empty scroll stages that don't make sense.
        fun isEmptyScroll(stage: Stage) = stage is Stage.Scroll && stage.laterals.isEmpty()
        if (pageStages.isNotEmpty() && !(pageStages.size == 1 && isEmptyScroll(pageStages[0]))) {
            var idx = 0
            while (idx < pageStages.size) {
                val prevStageDoesNotScroll = idx == 0 || pageStages[idx - 1].style.behavior != PageBehavior.SCROLL
                val nextStageDoesNotScroll = idx == pageStages.lastIndex ||
                        pageStages[idx + 1].style.behavior != PageBehavior.SCROLL
                if (!isEmptyScroll(pageStages[idx]) || prevStageDoesNotScroll && nextStageDoesNotScroll)
                    idx++
                else
                    pageStages.removeAt(idx)
            }
            val page = Page(pageStages.toPersistentList())
            pages.add(page)
        }
        pageStages.clear()
    }

    fun concludeStage(vGapAfter: Double) {
        // Note: We allow empty scroll stages to connect card stages.
        if (stageStyle?.behavior == PageBehavior.SCROLL || stageCompounds.isNotEmpty()) {
            val stage = when (stageStyle!!.behavior) {
                PageBehavior.CARD -> Stage.Card(stageStyle!!, stageCompounds.toPersistentList(), vGapAfter)
                PageBehavior.SCROLL -> Stage.Scroll(stageStyle!!, stageLaterals.toPersistentList(), vGapAfter)
            }
            pageStages.add(stage)
            // Remember where the stage has started.
            stageDeclaredRows[stage] = stageDeclaredRow

            // If directed, add the new stage to a runtime group.
            val stageRtFrames = stageRuntimeFrames
            val stageRtGroupName = stageRuntimeGroupName
            if (stageRtGroupName != null && stageRtGroupName in namedRuntimeGroups) {
                val oldGroup = namedRuntimeGroups.getValue(stageRtGroupName)
                namedRuntimeGroups[stageRtGroupName] = RuntimeGroup(oldGroup.stages.add(stage), oldGroup.runtimeFrames)
            } else if (stageRtGroupName != null && stageRtFrames != null)
                namedRuntimeGroups[stageRtGroupName] = RuntimeGroup(persistentListOf(stage), stageRtFrames)
            else if (stageRtFrames != null)
                unnamedRuntimeGroups.add(RuntimeGroup(persistentListOf(stage), stageRtFrames))
        }
        stageStyle = nextStageStyle
        stageRuntimeFrames = nextStageRuntimeFrames
        stageRuntimeGroupName = nextStageRuntimeGroupName
        stageDeclaredRow = nextStageDeclaredRow
        stageCompounds.clear()
        stageLaterals.clear()
        nextStageStyle = null
        nextStageRuntimeFrames = null
        nextStageRuntimeGroupName = null
        isStageConclusionMarked = false
    }

    fun concludeCompound() {
        if (compoundLaterals.isNotEmpty())
            stageCompounds.add(Compound(compoundVOffsetPx, compoundLaterals.toPersistentList()))
        compoundVOffsetPx = nextCompoundVOffsetPx
        compoundLaterals.clear()
        nextCompoundVOffsetPx = 0.0
        isCompoundConclusionMarked = false
    }

    fun concludeLateral(vGapAfter: Double) {
        if (lateralSpines.isNotEmpty()) {
            val lateral = Lateral(lateralSpines.toPersistentList(), vGapAfter)
            when (stageStyle!!.behavior) {
                PageBehavior.CARD -> compoundLaterals.add(lateral)
                PageBehavior.SCROLL -> stageLaterals.add(lateral)
            }
        }
        lateralSpines.clear()
        isLateralConclusionMarked = false
    }

    fun concludeSpine() {
        if (spineBlocks.isNotEmpty())
            lateralSpines.add(Spine(spineHOffsetPx, spineBlocks.toPersistentList()))
        spineHOffsetPx = nextSpineHOffsetPx
        spineBlocks.clear()
        nextSpineHOffsetPx = 0.0
        isSpineConclusionMarked = false
    }

    fun concludeBlock(vGapAfter: Double) {
        if (blockBody.isNotEmpty()) {
            val block = Block(
                blockStyle!!, blockHead, blockBody.toPersistentList(), blockTail, vGapAfter,
                blockMatchHeadPartitionId, blockMatchBodyPartitionId, blockMatchTailPartitionId
            )
            spineBlocks.add(block)
        } else {
            if (blockHead != null)
                table.log(blockHeadDeclaredRow, "head", WARN, l10n("projectIO.credits.unusedHead", blockHead))
            if (blockTail != null)
                table.log(blockTailDeclaredRow, "tail", WARN, l10n("projectIO.credits.unusedTail", blockTail))
        }
        blockStyle = contentStyle
        blockHead = null
        blockBody.clear()
        blockTail = null
        blockMatchHeadPartitionId = matchHeadPartitionId
        blockMatchBodyPartitionId = matchBodyPartitionId
        blockMatchTailPartitionId = matchTailPartitionId
        isBlockConclusionMarked = false
    }


    /* ************************************
       ********** ACTUAL PARSING **********
       ************************************ */

    fun read(): Pair<List<Page>, List<RuntimeGroup>> {
        for (row in 0 until table.numRows) {
            this.row = row
            readRow()
        }

        // Conclude all open credits elements that haven't been concluded yet.
        concludeBlock(0.0)
        concludeSpine()
        concludeLateral(0.0)
        if (stageStyle?.behavior == PageBehavior.CARD)
            concludeCompound()
        concludeStage(0.0)
        concludePage()

        // If there is not a single page, that's an error.
        if (pages.isEmpty())
            table.log(null, null, ERROR, l10n("projectIO.credits.noPages"))

        // Collect the runtime groups. Warn about those which only contain card stages.
        val runtimeGroups = unnamedRuntimeGroups + namedRuntimeGroups.values
        for (runtimeGroup in runtimeGroups)
            if (runtimeGroup.stages.all { stage -> stage.style.behavior == PageBehavior.CARD }) {
                val declaredRow = stageDeclaredRows.getValue(runtimeGroup.stages.first())
                table.log(declaredRow, "pageRuntime", WARN, l10n("projectIO.credits.pureCardRuntimeGroup"))
            }

        return Pair(pages, runtimeGroups)
    }

    fun readRow() {
        // A row without head, body, and tail implicitly means a 1-unit vertical gap after the previous credits element.
        val isHBTFreeRow = table.isEmpty(row, "head") && table.isEmpty(row, "body") && table.isEmpty(row, "tail")
        if (isHBTFreeRow)
            implicitVGapInUnits += 1
        // The user may explicitly specify the vertical gap size. Per gap, only one specification is permitted.
        table.getFiniteDouble(row, "vGap", nonNeg = true)?.let {
            if (!isHBTFreeRow)
                table.log(row, "vGap", WARN, l10n("projectIO.credits.vGapInContentRow"))
            if (explicitVGapInUnits == null)
                explicitVGapInUnits = it
            else
                table.log(row, "vGap", WARN, l10n("projectIO.credits.vGapAlreadySet", explicitVGapInUnits))
        }

        // If the page style cell is non-empty, mark the previous stage for conclusion (if there was any). Use the
        // specified page style for the stage that starts immediately afterwards. Also reset the spine position offset.
        table.getLookup(row, "pageStyle", pageStyleMap, "projectIO.credits.unavailablePageStyle")?.let { newPageStyle ->
            nextStageStyle = newPageStyle
            nextStageDeclaredRow = row
            nextSpineHOffsetPx = 0.0
            isStageConclusionMarked = true
        }
        table.getString(row, "pageRuntime")?.let { str ->
            if (table.isEmpty(row, "pageStyle"))
                table.log(row, "pageRuntime", WARN, l10n("projectIO.credits.pageRuntimeInIntermediateRow"))
            else if (str in namedRuntimeGroups || str == stageRuntimeGroupName)
                nextStageRuntimeGroupName = str
            else {
                val fps = styling.global.fps
                val timecodeFormat = styling.global.timecodeFormat
                try {
                    if (' ' in str) {
                        val parts = str.split(' ')
                        val timecode = parts.last()
                        val runtimeGroupName = parts.subList(0, parts.size - 1).joinToString(" ")
                        if (runtimeGroupName in namedRuntimeGroups || runtimeGroupName == stageRuntimeGroupName) {
                            nextStageRuntimeGroupName = runtimeGroupName
                            val msg = l10n("projectIO.credits.pageRuntimeGroupRedeclared", runtimeGroupName)
                            table.log(row, "pageRuntime", WARN, msg)
                        } else {
                            nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, timecode)
                            nextStageRuntimeGroupName = runtimeGroupName
                        }
                    } else
                        nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, str)
                } catch (_: IllegalArgumentException) {
                    val f = l10n("project.${TimecodeFormat::class.java.simpleName}.${styling.global.timecodeFormat}")
                    val sampleTc = formatTimecode(fps, timecodeFormat, 7127)
                    table.log(row, "pageRuntime", WARN, l10n("projectIO.credits.illFormattedPageRuntime", f, sampleTc))
                }
            }
        }

        // If the vertical pos cell is non-empty, conclude the previous compound and start a new one.
        table.getFiniteDouble(row, "vPos")?.let { vPos ->
            val nss = nextStageStyle
            if (nss == null && stageStyle?.behavior == PageBehavior.CARD || nss?.behavior == PageBehavior.CARD) {
                nextCompoundVOffsetPx = vPos
                isCompoundConclusionMarked = true
            } else
                table.log(row, "vPos", WARN, l10n("projectIO.credits.vPosOutsideCard"))
        }

        // If the spine cell is non-empty, conclude the previous spine (if there was any) and start a new one.
        // If the spine cell does not contain "Parallel" (or any localized variant of that keyword), also conclude the
        // previous lateral and start a new one.
        table.getString(row, "spinePos")?.let { str ->
            var parallel = false
            var posOffsetPx = 0.0
            try {
                if (' ' in str) {
                    val (offset, hint) = str.split(' ', limit = 2)
                    posOffsetPx = offset.toFiniteDouble()
                    if (hint in PARALLEL_KW)
                        if (isStageConclusionMarked)
                            table.log(row, "spinePos", WARN, l10n("projectIO.credits.parallelAtNewPage", hint))
                        else if (isCompoundConclusionMarked)
                            table.log(row, "spinePos", WARN, l10n("projectIO.credits.parallelAtNewCompound", hint))
                        else
                            parallel = true
                    else
                        throw IllegalArgumentException()
                } else
                    posOffsetPx = str.toFiniteDouble()
            } catch (_: IllegalArgumentException) {
                val msg = l10n("projectIO.credits.illFormattedSpinePos", l10n(PARALLEL_KW.key))
                table.log(row, "spinePos", WARN, msg)
            }

            nextSpineHOffsetPx = posOffsetPx
            if (parallel)
                isSpineConclusionMarked = true
            else
                isLateralConclusionMarked = true
        }

        // If the content style cell is non-empty, mark the previous block for conclusion (if there was any).
        // Use the new content style from now on until the next explicit content style declaration.
        table.getLookup(
            row, "contentStyle", contentStyleMap, "projectIO.credits.unavailableContentStyle",
            fallback = PLACEHOLDER_CONTENT_STYLE
        )?.let { newContentStyle ->
            contentStyle = newContentStyle
            isBlockConclusionMarked = true
        }

        // If the break match cell is non-empty, start a new matching partition for the head, body, and/or tail,
        // and mark the previous block for conclusion (if there was any).
        for (breakMatch in table.getEnumList<BreakMatch>(row, "breakMatch")) {
            when (breakMatch) {
                BreakMatch.HEAD -> matchHeadPartitionId++
                BreakMatch.BODY -> matchBodyPartitionId++
                BreakMatch.TAIL -> matchTailPartitionId++
            }
            isBlockConclusionMarked = true
        }

        // Get the body element, which may either be a styled string or a (optionally scaled) picture.
        val bodyElem = getBodyElement("body", contentStyle?.bodyLetterStyleName)

        // Get the head and tail, which may only be styled strings.
        val newHead = (getBodyElement("head", contentStyle?.headLetterStyleName, onlyS = true) as BodyElement.Str?)?.str
        val newTail = (getBodyElement("tail", contentStyle?.tailLetterStyleName, onlyS = true) as BodyElement.Str?)?.str

        // If either head or tail is available, or if a body is available and the conclusion of the previous block
        // has been marked, conclude the previous block (if there was any) and start a new one.
        val isConclusionMarked = isBlockConclusionMarked || isSpineConclusionMarked || isLateralConclusionMarked ||
                isCompoundConclusionMarked || isStageConclusionMarked
        if (newHead != null || newTail != null || (isConclusionMarked && bodyElem != null)) {
            // Pull the accumulated vertical gap.
            val vGap = (explicitVGapInUnits ?: implicitVGapInUnits.toDouble()) * styling.global.unitVGapPx
            explicitVGapInUnits = null
            implicitVGapInUnits = 0

            // If the conclusion of the previous spine, lateral, compound, or stage has been marked, also conclude that
            // and give the accumulated virtual gap to the concluded element of the highest order.
            if (isStageConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                concludeLateral(0.0)
                if (stageStyle?.behavior == PageBehavior.CARD)
                    concludeCompound()
                concludeStage(vGap)
                // If we are not melting the previous stage with the future one, also conclude the current page.
                val prevStageStyle = pageStages.lastOrNull()?.style
                val currStageStyle = stageStyle!!
                if (!(prevStageStyle?.behavior == PageBehavior.SCROLL && prevStageStyle.scrollMeltWithNext) &&
                    !(currStageStyle.behavior == PageBehavior.SCROLL && currStageStyle.scrollMeltWithPrev)
                )
                    concludePage()
            } else if (isCompoundConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                concludeLateral(vGap)
                concludeCompound()
            } else if (isLateralConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                concludeLateral(vGap)
            } else if (isSpineConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                // Discard the accumulated virtual gap.
            } else
                concludeBlock(vGap)

            // Record the head and tail if they are provided.
            if (newHead != null) {
                blockHead = newHead
                blockHeadDeclaredRow = row
            }
            if (newTail != null) {
                blockTail = newTail
                blockTailDeclaredRow = row
            }
        }

        // If no page or content style has been declared at the point where the first block starts, issue a warning and
        // fall back to the placeholder page or content style.
        if (newHead != null || newTail != null || bodyElem != null) {
            if (stageStyle == null) {
                stageStyle = PLACEHOLDER_PAGE_STYLE
                table.log(row, null, WARN, l10n("projectIO.credits.noPageStyleSpecified"))
            }
            if (contentStyle == null) {
                contentStyle = PLACEHOLDER_CONTENT_STYLE
                blockStyle = contentStyle
                table.log(row, null, WARN, l10n("projectIO.credits.noContentStyleSpecified"))
            }
        }

        // If the line has a head or tail even though the current content style doesn't support it,
        // issue a warning and discard the head resp. tail.
        if (newHead != null && !contentStyle!!.hasHead) {
            blockHead = null
            table.log(row, "head", WARN, l10n("projectIO.credits.headUnsupported", contentStyle!!.name))
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            table.log(row, "tail", WARN, l10n("projectIO.credits.tailUnsupported", contentStyle!!.name))
        }

        // If the body cell is non-empty, add its content to the current block.
        if (bodyElem != null)
            blockBody.add(bodyElem)
        // Otherwise, if the row didn't just start a new block,
        // mark the previous block for conclusion (if there was any).
        else if (isHBTFreeRow)
            isBlockConclusionMarked = true
    }

    fun getBodyElement(l10nColName: String, initLetterStyleName: String?, onlyS: Boolean = false): BodyElement? {
        fun unavailableLetterStyleMsg(name: String) =
            l10n("projectIO.credits.unavailableLetterStyle", name, letterStyleMap.keys.joinToString())

        fun unknownTagMsg(tagKey: String) = l10n(
            "projectIO.credits.unknownTagKeyword", tagKey,
            "{{${l10n(BLANK_KW.key)}}}, {{${l10n(STYLE_KW.key)}}}, {{${l10n(PIC_KW.key)}}}"
        )

        val str = table.getString(row, l10nColName) ?: return null
        val initLetterStyle = initLetterStyleName?.let { letterStyleMap[it] } ?: PLACEHOLDER_LETTER_STYLE

        var curLetterStyle: LetterStyle? = null
        val styledStr = mutableListOf<Pair<String, LetterStyle>>()
        var blankTagKey: String? = null
        var multipleBlanks = false
        var pictureTagKey: String? = null
        var picture: Picture? = null
        var multiplePictures = false
        parseTaggedString(str) { plain, tagKey, tagVal ->
            when {
                // When we encounter plaintext, add it to the styled string list using the current letter style.
                plain != null -> styledStr.add(Pair(plain, curLetterStyle ?: initLetterStyle))
                tagKey != null -> when (tagKey) {
                    // When we encounter a blank tag, remember it.
                    // We can't immediately return because we want to issue a warning if the blank tag is not lone.
                    in BLANK_KW -> when {
                        onlyS -> table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagDisallowed", tagKey))
                        else -> when (blankTagKey) {
                            null -> blankTagKey = tagKey
                            else -> multipleBlanks = true
                        }
                    }
                    // When we encounter a style tag, change the current letter style to the desired one.
                    in STYLE_KW -> when (tagVal) {
                        null -> curLetterStyle = initLetterStyle
                        else -> when (val newLetterStyle = letterStyleMap[tagVal]) {
                            null -> {
                                curLetterStyle = PLACEHOLDER_LETTER_STYLE
                                table.log(row, l10nColName, WARN, unavailableLetterStyleMsg(tagVal))
                            }
                            else -> curLetterStyle = newLetterStyle
                        }
                    }
                    // When we encounter a picture tag, read it and remember the loaded picture for now.
                    // We can't immediately return because we want to issue a warning if the picture tag is not lone.
                    in PIC_KW -> when {
                        onlyS -> table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagDisallowed", tagKey))
                        else -> when (pictureTagKey) {
                            null -> {
                                pictureTagKey = tagKey
                                picture = getPicture(l10nColName, tagKey, tagVal)
                            }
                            else -> multiplePictures = true
                        }
                    }
                    else -> table.log(row, l10nColName, WARN, unknownTagMsg(tagKey))
                }
            }
        }

        val isStyledStringBlank = styledStr.all { (run, _) -> run.isBlank() }
        return when {
            blankTagKey != null -> {
                if (!isStyledStringBlank || curLetterStyle != null || multipleBlanks || pictureTagKey != null)
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagNotLone", blankTagKey))
                BodyElement.Nil(initLetterStyle)
            }
            pictureTagKey != null -> {
                if (!isStyledStringBlank || curLetterStyle != null || multiplePictures)
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagNotLone", pictureTagKey))
                picture?.let(BodyElement::Pic) ?: BodyElement.Str(listOf("???" to PLACEHOLDER_LETTER_STYLE))
            }
            !isStyledStringBlank -> BodyElement.Str(styledStr)
            else -> {
                table.log(row, l10nColName, WARN, l10n("projectIO.credits.effectivelyEmpty"))
                BodyElement.Str(listOf("???" to PLACEHOLDER_LETTER_STYLE))
            }
        }
    }

    fun getPicture(l10nColName: String, tagKey: String, tagVal: String?): Picture? {
        fun illFormattedMsg() = l10n("projectIO.credits.pictureIllFormatted", l10n(CROP_KW.key), tagKey)
        fun notFoundMsg() = l10n("projectIO.credits.pictureNotFound") + " " + illFormattedMsg()
        fun rasterCropMsg() = l10n("projectIO.credits.pictureRasterCrop", l10n(CROP_KW.key))
        fun hintsUnknownMsg(hints: List<String>) =
            l10n("projectIO.credits.pictureHintsUnknown", hints.joinToString(" ")) + " " + illFormattedMsg()

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

            // If the picture is present multiple times in the project folder, abort and inform the user.
            if (picName in duplicatePictures) {
                table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureDuplicate", picName))
                return null
            }

            pictureLoaderMap[picName]?.let { picLoader ->
                val origPic = picLoader.picture
                if (origPic == null) {
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.pictureCorrupt", picName))
                    return null
                }
                var pic: Picture = origPic

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
                        pic = when {
                            hint.isBlank() || hint in CROP_KW -> continue
                            hint.startsWith('x') ->
                                pic.scaled(hint.drop(1).toFiniteDouble(nonNeg = true, non0 = true) / pic.height)
                            hint.endsWith('x') ->
                                pic.scaled(hint.dropLast(1).toFiniteDouble(nonNeg = true, non0 = true) / pic.width)
                            else -> throw IllegalArgumentException()
                        }
                    } catch (_: IllegalArgumentException) {
                        // Note that this catch clause is mainly here because toFiniteDouble() may throw exceptions.
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
        table.log(row, l10nColName, WARN, notFoundMsg())
        return null
    }


    /* ***********************************
       ********** MISCELLANEOUS **********
       *********************************** */

    enum class BreakMatch { HEAD, BODY, TAIL }


    companion object {

        val PARALLEL_KW = Keyword("projectIO.credits.table.parallel")
        val CROP_KW = Keyword("projectIO.credits.table.crop")
        val BLANK_KW = Keyword("projectIO.credits.table.blank")
        val STYLE_KW = Keyword("projectIO.credits.table.style")
        val PIC_KW = Keyword("projectIO.credits.table.pic")

        val TAG_DELIMITERS = listOf("{{", "}}")

        inline fun parseTaggedString(str: String, callback: (String?, String?, String?) -> Unit) {
            var idx = 0

            while (true) {
                val tagStartIdx = str.indexOfUnescaped("{{", startIdx = idx)
                if (tagStartIdx == -1)
                    break
                val tagEndIdx = str.indexOfUnescaped("}}", startIdx = tagStartIdx + 2)
                if (tagEndIdx == -1)
                    break

                if (tagStartIdx != idx)
                    callback(str.substring(idx, tagStartIdx).unescape(TAG_DELIMITERS), null, null)

                val tag = str.substring(tagStartIdx + 2, tagEndIdx).unescape(TAG_DELIMITERS).trim()
                val keyEndIdx = tag.indexOf(' ')
                if (keyEndIdx == -1)
                    callback(null, tag, null)
                else
                    callback(null, tag.substring(0, keyEndIdx), tag.substring(keyEndIdx + 1))

                idx = tagEndIdx + 2
            }

            if (idx != str.length)
                callback(str.substring(idx).unescape(TAG_DELIMITERS), null, null)
        }

        fun String.indexOfUnescaped(seq: String, startIdx: Int): Int {
            val idx = indexOf(seq, startIdx)
            if (idx <= 0)
                return idx

            return if (countPreceding('\\', idx) % 2 == 0)
                idx
            else
                indexOfUnescaped(seq, idx + seq.length)
        }

        fun String.unescape(seqs: Collection<String>): String {
            var escIdx = indexOfAny(seqs.map { "\\$it" })
            if (escIdx == -1)
                if (isEmpty() || last() != '\\')
                    return this
                else
                    escIdx = lastIndex

            val numBackslashes = countPreceding('\\', escIdx + 1)
            return substring(0, escIdx - (numBackslashes - 1) / 2) + substring(escIdx + 1).unescape(seqs)
        }

        // Here, idx is exclusive.
        fun String.countPreceding(char: Char, idx: Int): Int {
            val actualIdx = idx.coerceIn(0, length)
            for (precedingIdx in (actualIdx - 1) downTo 0)
                if (this[precedingIdx] != char)
                    return actualIdx - precedingIdx - 1
            return actualIdx
        }

    }


    class Keyword(val key: String) {
        private val kwSet = TRANSLATED_LOCALES.mapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { l10n(key, it) }
        operator fun contains(str: String) = str in kwSet
    }

}
