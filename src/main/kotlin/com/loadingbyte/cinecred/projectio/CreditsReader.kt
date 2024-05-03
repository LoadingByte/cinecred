package com.loadingbyte.cinecred.projectio

import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.text.DecimalFormat
import java.util.*
import kotlin.io.path.name


fun readCredits(
    spreadsheet: Spreadsheet,
    styling: Styling,
    pictureLoaders: Collection<PictureLoader>,
    tapes: Collection<Tape>
): Pair<Credits, List<ParserMsg>> {
    // Try to find the table in the spreadsheet.
    val table = Table(
        spreadsheet, l10nPrefix = "projectIO.credits.table.", l10nColNames = listOf(
            "head", "body", "tail", "vGap", "contentStyle", "breakMatch", "spinePos",
            "pageStyle", "pageRuntime", "pageGap"
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
    val credits = CreditsReader(table, styling, pictureLoaders, tapes).read()
    return Pair(credits, table.log)
}


private class CreditsReader(
    val table: Table,
    val styling: Styling,
    pictureLoaders: Collection<PictureLoader>,
    tapes: Collection<Tape>
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

    // Prepare resolvers for pictures and tape.
    val picResolver = AuxiliaryFileResolver(pictureLoaders, PictureLoader::filename)
    val tapeResolver = AuxiliaryFileResolver(tapes) { tape -> tape.fileOrDir.name }


    /* *****************************************
       ********** STATE + CONCLUSIONS **********
       ***************************************** */

    // The row that is currently being read.
    var row = 0

    // The page style and runtime configuration to use for the next started stage.
    var nextStageStyle: PageStyle? = null
    var nextStageRuntimeFrames: Int? = null
    var nextStageRuntimeGroupName: String? = null
    // The compound positioning configuration to use for the next started compound.
    var nextCompoundVAnchor = VAnchor.MIDDLE
    var nextCompoundHOffsetPx = 0.0
    var nextCompoundVOffsetPx = 0.0
    // The spine positioning configuration to use for the next started spine.
    var nextSpineHookTo = 0
    var nextSpineHookVAnchor = VAnchor.MIDDLE
    var nextSpineSelfVAnchor = VAnchor.MIDDLE
    var nextSpineHOffsetPx = 0.0
    var nextSpineVOffsetPx = 0.0
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
    // (e.g., a block, a spine, and a compound), the most significant credits element will receive the gap
    // (in our example, that would be the compound).
    var explicitVGapPx: Double? = null
    var implicitVGapPx: Double = 0.0

    // This variable is set to true when the current block should be concluded as soon as a row with some non-empty
    // body cell arrives. It is used in cases where the previous block is known to be complete (e.g., because the
    // content style changed or there was an empty row), but it cannot be concluded yet because it is unknown whether
    // only the block or the block and more higher-order elements like a compound will be concluded at the same time.
    // In the latter case, the vertical gap accumulator would be given to the compound and not the block. So we have
    // to wait and see.
    var isBlockConclusionMarked = false
    // These variables work similar to the one above, but not only cause the conclusion of the current block, but also
    // the current spine, compound, or stage. They are used when a new spine, compound, or stage is started, but it does
    // not have its first block defined yet, leaving room for additional vertical gaps in the meantime, which should of
    // course still count to the vGapAfter of the just concluded spine, compound, or stage.
    var isSpineConclusionMarked = false
    var isCompoundConclusionMarked = false
    var isStageConclusionMarked = false

    // Final result
    val pages = mutableListOf<Page>()
    val unnamedRuntimeGroups = mutableListOf<RuntimeGroup>()
    val namedRuntimeGroups = mutableMapOf<String, RuntimeGroup>()

    // Current page
    val pageStages = mutableListOf<Stage>()
    var pageGapAfterFrames: Int? = null

    // Current stage
    var stageStyle: PageStyle? = null
    val stageCompounds = mutableListOf<Compound>()
    var stageRuntimeFrames: Int? = null
    var stageRuntimeGroupName: String? = null
    var stageMeltWithNext = false

    // Current compound
    var compoundVAnchor = VAnchor.MIDDLE
    var compoundHOffsetPx = 0.0
    var compoundVOffsetPx = 0.0
    val compoundSpines = mutableListOf<Spine>()

    // Current spine
    var spineHookTo = 0
    var spineHookVAnchor = VAnchor.MIDDLE
    var spineSelfVAnchor = VAnchor.MIDDLE
    var spineHOffsetPx = 0.0
    var spineVOffsetPx = 0.0
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
    // Keep track where the melting of stages has been declared, for use in an error message.
    var stageMeltDeclaredRow = 0
    // Keep track where the current head and tail have been declared. This is used by an error message.
    var blockHeadDeclaredRow = 0
    var blockTailDeclaredRow = 0

    fun concludePage() {
        // Note: In concludeStage(), we allow empty scroll stages. However, empty scroll stages do only make sense
        // when they don't sit next to another scroll stage and when they are not alone on a page.
        // We remove the empty scroll stages that don't make sense.
        fun isEmptyScroll(stage: Stage) = stage.style.behavior == PageBehavior.SCROLL && stage.compounds.isEmpty()
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
            val gapAfterFrames = pageGapAfterFrames ?: if (stageStyle == null) 0 else
                pageStages.last().style.subsequentGapFrames
            val page = Page(pageStages.toPersistentList(), gapAfterFrames)
            pages.add(page)
        }
        pageStages.clear()
        pageGapAfterFrames = null
    }

    fun concludeStage(vGapAfter: Double) {
        // Note: We allow empty scroll stages to connect card stages.
        if (stageStyle?.behavior == PageBehavior.SCROLL || stageCompounds.isNotEmpty()) {
            val stageStyle = this.stageStyle!!
            when (stageStyle.behavior) {
                PageBehavior.CARD -> {
                    val cardRuntimeFrames = stageRuntimeFrames ?: stageStyle.cardRuntimeFrames
                    pageStages += Stage(stageStyle, cardRuntimeFrames, stageCompounds.toPersistentList(), vGapAfter)
                }
                PageBehavior.SCROLL -> {
                    val stage = Stage(stageStyle, 0, stageCompounds.toPersistentList(), vGapAfter)
                    pageStages += stage
                    // If directed, add the new stage to a runtime group.
                    val groupName = stageRuntimeGroupName
                    if (groupName != null && groupName in namedRuntimeGroups) {
                        val oldGroup = namedRuntimeGroups.getValue(groupName)
                        namedRuntimeGroups[groupName] = RuntimeGroup(oldGroup.stages.add(stage), oldGroup.runtimeFrames)
                    } else {
                        val groupFrames = stageRuntimeFrames
                            ?: stageStyle.scrollRuntimeFrames.run { if (isActive) value else null }
                        if (groupFrames != null)
                            if (groupName != null)
                                namedRuntimeGroups[groupName] = RuntimeGroup(persistentListOf(stage), groupFrames)
                            else
                                unnamedRuntimeGroups.add(RuntimeGroup(persistentListOf(stage), groupFrames))
                    }
                }
            }
        }
        stageStyle = nextStageStyle
        stageRuntimeFrames = nextStageRuntimeFrames
        stageRuntimeGroupName = nextStageRuntimeGroupName
        stageDeclaredRow = nextStageDeclaredRow
        stageCompounds.clear()
        nextStageStyle = null
        nextStageRuntimeFrames = null
        nextStageRuntimeGroupName = null
        isStageConclusionMarked = false
    }

    fun concludeCompound(vGapAfter: Double) {
        if (compoundSpines.isNotEmpty())
            stageCompounds += when (stageStyle!!.behavior) {
                PageBehavior.CARD -> Compound.Card(
                    compoundVAnchor, compoundHOffsetPx, compoundVOffsetPx, compoundSpines.toPersistentList()
                )
                PageBehavior.SCROLL -> Compound.Scroll(
                    compoundHOffsetPx, compoundSpines.toPersistentList(), vGapAfter
                )
            }
        compoundVAnchor = nextCompoundVAnchor
        compoundHOffsetPx = nextCompoundHOffsetPx
        compoundVOffsetPx = nextCompoundVOffsetPx
        compoundSpines.clear()
        nextCompoundVAnchor = VAnchor.MIDDLE
        nextCompoundHOffsetPx = 0.0
        nextCompoundVOffsetPx = 0.0
        isCompoundConclusionMarked = false
    }

    fun concludeSpine() {
        if (spineBlocks.isNotEmpty())
            compoundSpines += Spine(
                compoundSpines.getOrNull(spineHookTo), spineHookVAnchor, spineSelfVAnchor,
                spineHOffsetPx, spineVOffsetPx, spineBlocks.toPersistentList()
            )
        spineHookTo = nextSpineHookTo
        spineHookVAnchor = nextSpineHookVAnchor
        spineSelfVAnchor = nextSpineSelfVAnchor
        spineHOffsetPx = nextSpineHOffsetPx
        spineVOffsetPx = nextSpineVOffsetPx
        spineBlocks.clear()
        nextSpineHookTo = 0
        nextSpineHookVAnchor = VAnchor.MIDDLE
        nextSpineSelfVAnchor = VAnchor.MIDDLE
        nextSpineHOffsetPx = 0.0
        nextSpineVOffsetPx = 0.0
        isSpineConclusionMarked = false
    }

    fun concludeBlock(vGapAfter: Double) {
        if (blockBody.isNotEmpty())
            spineBlocks += Block(
                blockStyle!!, blockHead, blockBody.toPersistentList(), blockTail, vGapAfter,
                blockMatchHeadPartitionId, blockMatchBodyPartitionId, blockMatchTailPartitionId
            )
        else {
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

    fun read(): Credits {
        for (row in 0..<table.numRows) {
            this.row = row
            readRow()
        }

        // Conclude all open credits elements that haven't been concluded yet.
        concludeBlock(0.0)
        concludeSpine()
        concludeCompound(0.0)
        concludeStage(0.0)
        concludePage()

        // If there is not a single page, that's an error.
        if (pages.isEmpty())
            table.log(null, null, WARN, l10n("projectIO.credits.noPages"))

        // Collect the runtime groups.
        val runtimeGroups = unnamedRuntimeGroups + namedRuntimeGroups.values

        return Credits(table.spreadsheet.name, pages.toPersistentList(), runtimeGroups.toPersistentList())
    }

    fun readRow() {
        // A row without head, body, and tail implicitly means a 1-unit vertical gap after the previous credits element.
        val isHBTFreeRow = table.isEmpty(row, "head") && table.isEmpty(row, "body") && table.isEmpty(row, "tail")
        if (isHBTFreeRow)
            implicitVGapPx += styling.global.unitVGapPx
        // The user may explicitly specify the vertical gap size. Per gap, only one specification is permitted.
        table.getString(row, "vGap")?.let { str ->
            if (!isHBTFreeRow)
                table.log(row, "vGap", WARN, l10n("projectIO.credits.vGapInContentRow"))
            else
                try {
                    val vGap = when {
                        str.endsWith("px") -> str.dropLast(2).trimEnd().toFiniteDouble(nonNeg = true)
                        else -> str.toFiniteDouble(nonNeg = true) * styling.global.unitVGapPx
                    }
                    if (explicitVGapPx == null)
                        explicitVGapPx = vGap
                    else
                        table.log(row, "vGap", WARN, l10n("projectIO.credits.vGapAlreadySet", explicitVGapPx))
                } catch (_: IllegalArgumentException) {
                    table.log(row, "vGap", WARN, l10n("projectIO.credits.illFormattedVGap", "px"))
                }
        }

        // If the page style cell is non-empty, mark the previous stage for conclusion (if there was any). Use the
        // specified page style for the stage that starts immediately afterwards. Also reset the spine positioning info.
        table.getLookup(row, "pageStyle", pageStyleMap, "projectIO.credits.unavailablePageStyle")?.let { newPageStyle ->
            if (stageMeltWithNext && newPageStyle === stageStyle) {
                table.log(row, "pageStyle", WARN, l10n("projectIO.credits.redundantMeltedScroll"))
                return@let
            }
            nextStageStyle = newPageStyle
            nextStageDeclaredRow = row
            nextSpineHookTo = 0
            nextSpineHookVAnchor = VAnchor.MIDDLE
            nextSpineSelfVAnchor = VAnchor.MIDDLE
            nextSpineHOffsetPx = 0.0
            nextSpineVOffsetPx = 0.0
            isStageConclusionMarked = true
        }

        table.getString(row, "pageRuntime")?.let { str ->
            var runtimeGroupName: String? = null
            if (nextStageDeclaredRow != row)
                table.log(row, "pageRuntime", WARN, l10n("projectIO.credits.pageRuntimeInIntermediateRow"))
            else if (str in namedRuntimeGroups || str == stageRuntimeGroupName)
                runtimeGroupName = str
            else {
                val fps = styling.global.fps
                val timecodeFormat = styling.global.timecodeFormat
                try {
                    if (' ' in str) {
                        val parts = str.split(' ')
                        val timecode = parts.last()
                        runtimeGroupName = parts.subList(0, parts.size - 1).joinToString(" ")
                        if (runtimeGroupName in namedRuntimeGroups || runtimeGroupName == stageRuntimeGroupName) {
                            val msg = l10n("projectIO.credits.pageRuntimeGroupRedeclared", runtimeGroupName)
                            table.log(row, "pageRuntime", WARN, msg)
                        } else
                            nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, timecode)
                    } else
                        nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, str)
                } catch (_: IllegalArgumentException) {
                    val msg = l10n("projectIO.credits.illFormattedPageRuntime", timecodeFormatLabel, sampleTimecode)
                    table.log(row, "pageRuntime", WARN, msg)
                }
            }
            if (runtimeGroupName != null)
                if (nextStageStyle!!.behavior == PageBehavior.CARD)
                    table.log(row, "pageRuntime", WARN, l10n("projectIO.credits.cardInRuntimeGroup"))
                else
                    nextStageRuntimeGroupName = runtimeGroupName
        }

        table.getString(row, "pageGap")?.let { str ->
            if (!table.isEmpty(row, "pageStyle"))
                table.log(row, "pageGap", WARN, l10n("projectIO.credits.pageGapInNewPageRow"))
            else if (pageGapAfterFrames != null || stageMeltWithNext)
                table.log(row, "pageGap", WARN, l10n("projectIO.credits.pageGapAlreadySet"))
            else if (str in MELT_KW) {
                stageMeltWithNext = true
                stageMeltDeclaredRow = row
            } else
                try {
                    val fps = styling.global.fps
                    val timecodeFormat = styling.global.timecodeFormat
                    val c0 = str[0]
                    val tcStr = if (c0 == '+' || c0 == '-' || c0 == '\u2212') str.substring(1) else str
                    val n = parseTimecode(fps, timecodeFormat, tcStr)
                    pageGapAfterFrames = if (c0 == '-' || c0 == '\u2212') -n else n
                } catch (_: IllegalArgumentException) {
                    val msg = l10n(
                        "projectIO.credits.illFormattedPageGap",
                        timecodeFormatLabel, sampleTimecode, "-$sampleTimecode", l10n(MELT_KW.key)
                    )
                    table.log(row, "pageGap", WARN, msg)
                }
        }

        // If the spine pos cell is non-empty, conclude the previous spine (if there was any) and start a new one.
        // Also conclude the previous compound if applicable.
        table.getString(row, "spinePos")?.let { str ->
            val parts = str.split(' ')
            val hookKw = parts[0] in HOOK_KW

            val nss = nextStageStyle
            val onCard = nss == null && stageStyle?.behavior == PageBehavior.CARD || nss?.behavior == PageBehavior.CARD
            val u = compoundSpines.size + 1

            var hook = false
            var warn = false
            try {
                if (hookKw) {
                    if (isStageConclusionMarked) {
                        table.log(row, "spinePos", WARN, l10n("projectIO.credits.hookAtNewPage", parts[0]))
                        return@let
                    }
                    hook = true
                    if (parts.size > 1) {
                        val i = parts[1].toInt()
                        if (i in 1..u)
                            nextSpineHookTo = u - i
                        else
                            table.log(row, "spinePos", WARN, l10n("projectIO.credits.invalidHookOrdinal", i, u))
                    }
                    if (parts.size > 2) {
                        val anchors = parts[2].split('-')
                        when (anchors[0]) {
                            in TOP_KW -> nextSpineHookVAnchor = VAnchor.TOP
                            in MIDDLE_KW -> nextSpineHookVAnchor = VAnchor.MIDDLE
                            in BOTTOM_KW -> nextSpineHookVAnchor = VAnchor.BOTTOM
                            else -> warn = true
                        }
                        if (anchors.size > 1)
                            when (anchors[1]) {
                                in TOP_KW -> nextSpineSelfVAnchor = VAnchor.TOP
                                in MIDDLE_KW -> nextSpineSelfVAnchor = VAnchor.MIDDLE
                                in BOTTOM_KW -> nextSpineSelfVAnchor = VAnchor.BOTTOM
                                else -> warn = true
                            }
                        if (anchors.size != 2)
                            warn = true
                    }
                    if (parts.size > 3)
                        nextSpineHOffsetPx = parts[3].toFiniteDouble()
                    if (parts.size > 4)
                        nextSpineVOffsetPx = parts[4].toFiniteDouble()
                    if (parts.size !in 3..5)
                        warn = true
                } else {
                    val hOffsetPx = parts[0].toFiniteDouble()
                    if (parts.size > 1 && parts[1] in PARALLEL_KW) {
                        if (isStageConclusionMarked) {
                            table.log(row, "spinePos", WARN, l10n("projectIO.credits.hookAtNewPage", parts[1]))
                            return@let
                        }
                        hook = true
                        nextSpineHookTo = 0
                        nextSpineHookVAnchor = VAnchor.TOP
                        nextSpineSelfVAnchor = VAnchor.TOP
                        nextSpineHOffsetPx = hOffsetPx - compoundHOffsetPx
                        nextSpineVOffsetPx = 0.0
                        if (parts.size > 2)
                            warn = true
                    } else {
                        nextCompoundHOffsetPx = hOffsetPx
                        if (onCard) {
                            if (parts.size > 1)
                                nextCompoundVOffsetPx = parts[1].toFiniteDouble()
                            if (parts.size > 2)
                                when (parts[2]) {
                                    in BELOW_KW -> nextCompoundVAnchor = VAnchor.TOP
                                    in ABOVE_KW -> nextCompoundVAnchor = VAnchor.BOTTOM
                                    else -> warn = true
                                }
                            if (parts.size > 3)
                                warn = true
                        } else
                            if (parts.size > 1)
                                warn = true
                    }
                }
            } catch (_: IllegalArgumentException) {
                warn = true
            }
            if (warn) {
                val msg = when {
                    hookKw -> l10n(
                        "projectIO.credits.illFormattedSpinePosHook", parts[0], u,
                        l10n(TOP_KW.key), l10n(MIDDLE_KW.key), l10n(BOTTOM_KW.key)
                    )
                    onCard -> l10n(
                        "projectIO.credits.illFormattedSpinePosCard",
                        l10n(BELOW_KW.key), l10n(ABOVE_KW.key), l10n(PARALLEL_KW.key), l10n(HOOK_KW.key)
                    )
                    else -> l10n(
                        "projectIO.credits.illFormattedSpinePosScroll",
                        l10n(PARALLEL_KW.key), l10n(HOOK_KW.key)
                    )
                }
                table.log(row, "spinePos", WARN, msg)
            }
            if (hook) isSpineConclusionMarked = true else isCompoundConclusionMarked = true
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
        table.getString(row, "breakMatch")?.let { str ->
            val parts = str.split(' ')
            val unknown = mutableListOf<String>()
            for (part in parts)
                when (part) {
                    in HEAD_KW -> matchHeadPartitionId++
                    in BODY_KW -> matchBodyPartitionId++
                    in TAIL_KW -> matchTailPartitionId++
                    else -> unknown.add(part)
                }
            if (unknown.size != parts.size)
                isBlockConclusionMarked = true
            if (unknown.isNotEmpty()) {
                val opts = "${l10n(HEAD_KW.key)}, ${l10n(BODY_KW.key)}, ${l10n(TAIL_KW.key)}"
                val msg = l10n("projectIO.credits.unknownBreakMatchKeyword", unknown.joinToString(" "), opts)
                table.log(row, "breakMatch", WARN, msg)
            }
        }

        // Get the body element, which may either be a styled string or a (optionally scaled) picture.
        val bodyElem = getBodyElement("body", contentStyle?.bodyLetterStyleName)

        // Get the head and tail, which may only be styled strings.
        val newHead = (getBodyElement("head", contentStyle?.headLetterStyleName, onlyS = true) as BodyElement.Str?)?.str
        val newTail = (getBodyElement("tail", contentStyle?.tailLetterStyleName, onlyS = true) as BodyElement.Str?)?.str

        // If either head or tail is available, or if a body is available and the conclusion of the previous block
        // has been marked, conclude the previous block (if there was any) and start a new one.
        val isConclusionMarked = isBlockConclusionMarked || isSpineConclusionMarked || isCompoundConclusionMarked ||
                isStageConclusionMarked
        if (newHead != null || newTail != null || (isConclusionMarked && bodyElem != null)) {
            // Pull the accumulated vertical gap.
            val vGap = explicitVGapPx ?: implicitVGapPx
            explicitVGapPx = null
            implicitVGapPx = 0.0

            // If the conclusion of the previous spine, compound, or stage has been marked, also conclude that
            // and give the accumulated virtual gap to the concluded element of the highest order.
            if (isStageConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                concludeCompound(0.0)
                // Determine whether the stage that we'll conclude in a moment is the last one on its page.
                val currStyle = stageStyle
                val nextStyle = nextStageStyle!!
                val isLastOnPage = when {
                    stageMeltWithNext ->
                        if (currStyle?.behavior == PageBehavior.CARD && nextStyle.behavior == PageBehavior.CARD) {
                            table.log(stageMeltDeclaredRow, "pageGap", WARN, l10n("projectIO.credits.cannotMeltCards"))
                            true
                        } else false
                    // If no page gap has been declared and the two adjacent page styles still have the legacy melting
                    // flag set, we also want to melt the stages.
                    pageGapAfterFrames == null -> {
                        val c = currStyle?.behavior == PageBehavior.SCROLL && currStyle.scrollMeltWithNext
                        val n = nextStyle.behavior == PageBehavior.SCROLL && nextStyle.scrollMeltWithPrev
                        if (c || n) {
                            val msd = if (c) MigrationDataSource(currStyle!!, PageStyle::scrollMeltWithNext.st())
                            else MigrationDataSource(nextStyle, PageStyle::scrollMeltWithPrev.st())
                            table.logMigrationPut(row - 1, "pageGap", l10n(MELT_KW.key), msd)
                            false
                        } else true
                    }
                    else -> true
                }
                stageMeltWithNext = false
                concludeStage(vGap)
                if (isLastOnPage)
                    concludePage()
            } else if (isCompoundConclusionMarked) {
                concludeBlock(0.0)
                concludeSpine()
                concludeCompound(vGap)
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
            "{{${l10n(BLANK_KW.key)}}}, {{${l10n(STYLE_KW.key)}}}, {{${l10n(PIC_KW.key)}}}, {{${l10n(VIDEO_KW.key)}}}"
        )

        val str = table.getString(row, l10nColName) ?: return null
        val initLetterStyle = initLetterStyleName?.let { letterStyleMap[it] } ?: PLACEHOLDER_LETTER_STYLE

        var curLetterStyle: LetterStyle? = null
        val styledStr = mutableListOf<Pair<String, LetterStyle>>()
        var blankTagKey: String? = null
        var multipleBlanks = false
        var pictureOrVideoTagKey: String? = null
        var embeddedPic: Picture.Embedded? = null
        var embeddedTape: Tape.Embedded? = null
        var multiplePicturesOrVideos = false
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
                    // When we encounter a picture or video tag, read it and remember the loaded picture/tape for now.
                    // We can't immediately return because we want to issue a warning if the tag is not lone.
                    in PIC_KW, in VIDEO_KW -> when {
                        onlyS -> table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagDisallowed", tagKey))
                        else -> when (pictureOrVideoTagKey) {
                            null -> {
                                pictureOrVideoTagKey = tagKey
                                when (tagKey) {
                                    in PIC_KW -> embeddedPic = getEmbeddedPic(l10nColName, tagKey, tagVal)
                                    in VIDEO_KW -> embeddedTape = getEmbeddedTape(l10nColName, tagKey, tagVal)
                                }
                            }
                            else -> multiplePicturesOrVideos = true
                        }
                    }
                    else -> table.log(row, l10nColName, WARN, unknownTagMsg(tagKey))
                }
            }
        }

        val isStyledStringBlank = styledStr.all { (run, _) -> run.isBlank() }
        return when {
            blankTagKey != null -> {
                if (!isStyledStringBlank || curLetterStyle != null || multipleBlanks || pictureOrVideoTagKey != null)
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagNotLone", blankTagKey))
                BodyElement.Nil(initLetterStyle)
            }
            pictureOrVideoTagKey != null -> {
                if (!isStyledStringBlank || curLetterStyle != null || multiplePicturesOrVideos)
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.tagNotLone", pictureOrVideoTagKey))
                embeddedPic?.let(BodyElement::Pic)
                    ?: embeddedTape?.let(BodyElement::Tap)
                    ?: BodyElement.Str(listOf("???" to PLACEHOLDER_LETTER_STYLE))
            }
            !isStyledStringBlank -> BodyElement.Str(styledStr)
            else -> {
                table.log(row, l10nColName, WARN, l10n("projectIO.credits.effectivelyEmpty"))
                BodyElement.Str(listOf("???" to PLACEHOLDER_LETTER_STYLE))
            }
        }
    }

    fun getEmbeddedPic(l10nColName: String, tagKey: String, tagVal: String?): Picture.Embedded? = picResolver.resolve(
        l10nColName, tagVal,
        prepare = { picLoader -> picLoader.picture?.let { pic -> Picture.Embedded(pic) } },
        applyHints = { embPic0, hints ->
            var embPic = embPic0
            while (hints.hasNext()) {
                val hint = hints.next()
                embPic = try {
                    when {
                        // Crop the picture.
                        hint in CROP_KW -> when (embPic) {
                            is Picture.Embedded.Vector -> embPic.cropped()
                            // Raster images cannot be cropped.
                            is Picture.Embedded.Raster -> {
                                val msg = l10n("projectIO.credits.pictureRasterCrop", l10n(CROP_KW.key))
                                table.log(row, l10nColName, WARN, msg)
                                continue
                            }
                        }
                        // Apply scaling hints.
                        hint.startsWith('x') -> embPic.withHeightPreservingAspectRatio(hint.drop(1).toDouble())
                        hint.endsWith('x') -> embPic.withWidthPreservingAspectRatio(hint.dropLast(1).toDouble())
                        else -> continue
                    }
                } catch (_: IllegalArgumentException) {
                    continue
                }
                hints.remove()
            }
            embPic
        },
        illFormattedMsg = { l10n("projectIO.credits.pictureIllFormatted", l10n(CROP_KW.key), tagKey) }
    )

    fun getEmbeddedTape(l10nColName: String, tagKey: String, tagVal: String?): Tape.Embedded? = tapeResolver.resolve(
        l10nColName, tagVal,
        prepare = { tape ->
            try {
                Tape.Embedded(tape)
            } catch (_: Exception) {
                null
            }
        },
        applyHints = { embTape0, hints ->
            var embTape = embTape0
            while (hints.hasNext()) {
                val hint = hints.next()
                val isMargin = hint in MARGIN_KW
                val isFade = hint in FADE_KW
                val isIn = hint in IN_KW
                val isOut = hint in OUT_KW
                val isMid = hint in MIDDLE_KW
                val isEnd = hint in END_KW
                if (isMargin || isFade) {
                    hints.remove()
                    val projFPS = styling.global.fps
                    val projTcFmt = styling.global.timecodeFormat
                    try {
                        require(hints.hasNext())
                        val lFrames = parseTimecode(projFPS, projTcFmt, hints.next().also { hints.remove() })
                        var rFrames = lFrames
                        if (hints.hasNext())
                            try {
                                rFrames = parseTimecode(projFPS, projTcFmt, hints.peek())
                                hints.next()
                                hints.remove()
                            } catch (_: IllegalArgumentException) {
                                // rFrames just stays the same as lFrames.
                            }
                        embTape = when {
                            isMargin -> embTape.copy(leftMarginFrames = lFrames, rightMarginFrames = rFrames)
                            else -> embTape.copy(leftFadeFrames = lFrames, rightFadeFrames = rFrames)
                        }
                    } catch (_: IllegalArgumentException) {
                        val ex = formatTimecode(projFPS, projTcFmt, 7127)
                        val msg = l10n("projectIO.credits.videoIllFormattedRecTimecode", hint, projTcFmt.label, ex)
                        table.log(row, l10nColName, WARN, msg)
                    }
                } else if (isIn || isOut) {
                    hints.remove()
                    val tcHint = if (hints.hasNext()) hints.next().also { hints.remove() } else null
                    parseTapeTimecode(l10nColName, hint, tcHint, embTape.tape)?.let { tc ->
                        try {
                            embTape = if (isIn) embTape.withInPoint(tc) else embTape.withOutPoint(tc)
                        } catch (_: IllegalArgumentException) {
                            val msg = l10n("projectIO.credits.videoIllegalSrcInOut", hint, tcHint)
                            table.log(row, l10nColName, WARN, msg)
                        }
                    }
                } else if (isMid || isEnd) {
                    embTape = embTape.copy(align = if (isMid) Tape.Embedded.Align.MIDDLE else Tape.Embedded.Align.END)
                    hints.remove()
                } else {
                    embTape = try {
                        when {
                            // Apply scaling hints.
                            hint.startsWith('x') -> embTape.withHeightPreservingAspectRatio(hint.drop(1).toInt())
                            hint.endsWith('x') -> embTape.withWidthPreservingAspectRatio(hint.dropLast(1).toInt())
                            else -> continue
                        }
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    hints.remove()
                }
            }
            embTape
        },
        illFormattedMsg = {
            l10n(
                "projectIO.credits.videoIllFormatted",
                l10n(MARGIN_KW.key), l10n(FADE_KW.key), styling.global.timecodeFormat.label,
                l10n(IN_KW.key), l10n(OUT_KW.key), l10n(MIDDLE_KW.key), l10n(END_KW.key), tagKey
            )
        }
    )

    private fun parseTapeTimecode(l10nColName: String, kw: String, str: String?, tape: Tape): Timecode? {
        val fps = if (tape.fileSeq) styling.global.fps else tape.fps
        val permittedTcFmts = if (tape.fileSeq) EnumSet.of(TimecodeFormat.FRAMES) else
            (fps ?: FPS(1, 2)).canonicalTimecodeFormats.apply { remove(TimecodeFormat.FRAMES) }

        fun permittedTcSamples() = permittedTcFmts.joinToString { formatTimecode(fps ?: FPS(30000, 1001), it, 7127) }

        for (tcFmt in TimecodeFormat.entries) {
            val tc = try {
                parseTimecode(tcFmt, str ?: continue)
            } catch (_: IllegalArgumentException) {
                continue
            }
            // If parsing has been successful, check whether the timecode format is actually permitted for the
            // tape's FPS, and notify the user if it isn't.
            if (tcFmt !in permittedTcFmts) {
                val msg = l10n(
                    "projectIO.credits.videoWrongSrcTimecodeFormat",
                    fps.prettyPrint(), kw, permittedTcFmts.joinToString { it.label }, tcFmt.label, permittedTcSamples()
                )
                table.log(row, l10nColName, WARN, msg)
                return null
            }
            return when (tc) {
                // If the user used the timecode format we need, immediately return.
                is Timecode.Frames, is Timecode.Clock -> tc
                // If the user used a SMPTE timecode, convert it to the clock format using the fixed FPS.
                is Timecode.SMPTEDropFrame, is Timecode.SMPTENonDropFrame ->
                    try {
                        // We can only get here when FPS is not a random fraction, so FPS is known to be non-null.
                        tc.toClock(fps!!)
                    } catch (_: IllegalArgumentException) {
                        val msg = l10n("projectIO.credits.videoNonExistentSrcTimecode", str, fps.prettyPrint())
                        table.log(row, l10nColName, WARN, msg)
                        null
                    }
                // If the user used an exact frames in second timecode, convert it to the clock format by looking up the
                // referenced frame and taking its clock timecode.
                is Timecode.ExactFramesInSecond ->
                    try {
                        tape.toClockTimecode(tc) ?: null.also {
                            val msg = l10n("projectIO.credits.videoNonExistentSrcTimecode", str, fps.prettyPrint())
                            table.log(row, l10nColName, WARN, msg)
                        }
                    } catch (_: Exception) {
                        // If the tape has internal errors, it can't be rendered anyway, so don't bother the user.
                        null
                    }
            }
        }

        // If the string doesn't match any of the known timecode formats, inform the user.
        val msg = l10n(
            "projectIO.credits.videoUnknownSrcTimecodeFormat",
            fps.prettyPrint(), kw, permittedTcFmts.joinToString { it.label }, permittedTcSamples()
        )
        table.log(row, l10nColName, WARN, msg)
        return null
    }


    /* ***********************************
       ********** MISCELLANEOUS **********
       *********************************** */

    private val timecodeFormatLabel get() = styling.global.timecodeFormat.label
    private val sampleTimecode get() = formatTimecode(styling.global.fps, styling.global.timecodeFormat, 7127)


    companion object {

        val MELT_KW = Keyword("projectIO.credits.table.melt")
        val BELOW_KW = Keyword("projectIO.credits.table.below")
        val ABOVE_KW = Keyword("projectIO.credits.table.above")
        val HOOK_KW = Keyword("projectIO.credits.table.hook")
        val TOP_KW = Keyword("projectIO.credits.table.top")
        val MIDDLE_KW = Keyword("projectIO.credits.table.middle")
        val BOTTOM_KW = Keyword("projectIO.credits.table.bottom")
        val PARALLEL_KW = Keyword("projectIO.credits.table.parallel")
        val HEAD_KW = Keyword("projectIO.credits.table.head")
        val BODY_KW = Keyword("projectIO.credits.table.body")
        val TAIL_KW = Keyword("projectIO.credits.table.tail")
        val BLANK_KW = Keyword("blank")
        val STYLE_KW = Keyword("projectIO.credits.table.style")
        val PIC_KW = Keyword("projectIO.credits.table.pic")
        val CROP_KW = Keyword("projectIO.credits.table.crop")
        val VIDEO_KW = Keyword("projectIO.credits.table.video")
        val MARGIN_KW = Keyword("projectIO.credits.table.margin")
        val FADE_KW = Keyword("projectIO.credits.table.fade")
        val END_KW = Keyword("projectIO.credits.table.end")
        val IN_KW = Keyword("projectIO.credits.table.in")
        val OUT_KW = Keyword("projectIO.credits.table.out")

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

        private fun FPS?.prettyPrint(): String =
            if (this == null) "?" else DecimalFormat("0.##").format(frac)

    }


    class Keyword(val key: String) {
        private val kwSet = TRANSLATED_LOCALES.mapTo(TreeSet(String.CASE_INSENSITIVE_ORDER)) { l ->
            l10n(key, l).also { kw -> require(' ' !in kw) { "Keyword '$kw' from $key @ $l contains whitespace" } }
        }

        operator fun contains(str: String) = str in kwSet
    }


    inner class AuxiliaryFileResolver<A>(auxiliaries: Collection<A>, name: (A) -> String) {

        // Put the auxiliaries into a map whose keys are the filenames. Also record all duplicate filenames.
        // Use a map with case-insensitive keys to ease the user experience.
        private val auxMap = TreeMap<String, A>(String.CASE_INSENSITIVE_ORDER)
        private val dupSet = TreeSet(String.CASE_INSENSITIVE_ORDER)

        init {
            for (aux in auxiliaries) {
                val filename = name(aux)
                if (filename !in dupSet)
                    if (filename !in auxMap)
                        auxMap[filename] = aux
                    else {
                        auxMap.remove(filename)
                        dupSet.add(filename)
                    }
            }
        }

        fun <R> resolve(
            l10nColName: String,
            tagVal: String?,
            prepare: (A) -> R?,
            applyHints: (R, PeekingIterator<String>) -> R,
            illFormattedMsg: () -> String
        ): R? {
            if (tagVal == null) {
                table.log(row, l10nColName, WARN, illFormattedMsg())
                return null
            }

            // Remove arbitrary many space-separated suffixes from the string and each time check whether the remaining
            // string is a filename. If that is the case, try to parse the suffixes as hints.
            var splitIdx = tagVal.length
            do {
                val filename = tagVal.take(splitIdx).trim()

                // If the filename is used multiple times in the project folder, abort and inform the user.
                if (filename in dupSet) {
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.auxDuplicate", filename))
                    return null
                }

                // If the filename belongs to exactly one auxiliary file, apply the hint suffixes and return the result.
                auxMap[filename]?.let { aux ->
                    var res = prepare(aux)
                    if (res == null) {
                        table.log(row, l10nColName, WARN, l10n("projectIO.credits.auxCorrupt", filename))
                        return null
                    }
                    val unrecognizedHints = tagVal.substring(splitIdx).splitToSequence(' ')
                        .filterTo(mutableListOf(), String::isNotBlank)
                    res = applyHints(res, Iterators.peekingIterator(unrecognizedHints.iterator()))
                    // If some hints could not be recognized, warn the user.
                    if (unrecognizedHints.isNotEmpty()) {
                        val msg = l10n("projectIO.credits.auxHintsUnrecognized", unrecognizedHints.joinToString(" "))
                        table.log(row, l10nColName, WARN, msg + " " + illFormattedMsg())
                    }
                    return res
                }

                splitIdx = filename.lastIndexOf(' ')
            } while (splitIdx != -1)

            // The tag value doesn't contain a known filename.
            table.log(row, l10nColName, WARN, l10n("projectIO.credits.auxNotFound") + " " + illFormattedMsg())
            return null
        }

    }

}
