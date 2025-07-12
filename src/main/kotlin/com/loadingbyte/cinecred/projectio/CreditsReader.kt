package com.loadingbyte.cinecred.projectio

import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.*


fun readCredits(
    spreadsheet: Spreadsheet,
    styling: Styling,
    pictureLoaders: Map<String, Picture.Loader>,
    tapes: Map<String, Tape>
): Pair<Credits, List<ParserMsg>> {
    // Try to find the table in the spreadsheet.
    val table = Table(
        spreadsheet, l10nPrefix = "projectIO.credits.table.", l10nColNames = listOf(
            "head", "body", "tail", "vGap", "contentStyle", "breakHarmonization", "spinePos",
            "pageStyle", "pageRuntime", "pageGap"
        ), legacyColNames = mapOf(
            // 1.2.0 -> 1.3.0: The vertical gap is no longer abbreviated.
            "vGap" to listOf("Vert. Gap", "Senkr. Lücke"),
            // 1.2.0 -> 1.3.0: Cross-block alignment is renamed to matching.
            // 1.7.0 -> 1.8.0: Cross-block matching is renamed to harmonization
            "breakHarmonization" to listOf(
                "Break Align", "Breche Ausrichtung",
                "Break Match", "Porušit sjednocení", "Breche Angleichung", "Briser l’adaptation", "断开区块匹配"
            ),
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
    pictureLoaders: Map<String, Picture.Loader>,
    tapes: Map<String, Tape>
) {

    /* ************************************
       ********** CREATE LOOKUPS **********
       ************************************ */

    // Note: We use maps whose keys are case-insensitive here because style references should be case-insensitive.
    // We also reverse the list so that if there are duplicate names, the first style from the list will survive.
    inline fun <S> List<S>.sm(n: (S) -> String) = asReversed().associateByTo(TreeMap(ROOT_CASE_INSENSITIVE_COLLATOR), n)
    val pageStyleMap = styling.pageStyles.sm(PageStyle::name)
    val contentStyleMap = styling.contentStyles.sm(ContentStyle::name)
    val letterStyleMap = styling.letterStyles.sm(LetterStyle::name)
    val transitionStyleMap = styling.transitionStyles.sm(TransitionStyle::name)
    val pictureStyleMap = styling.pictureStyles.sm(PictureStyle::name)
    val tapeStyleMap = styling.tapeStyles.sm(TapeStyle::name)

    // Prepare resolvers for pictures and tape.
    val pictureStyleResolver = AuxiliaryStyleResolver(
        pictureLoaders, isDirectory = { false }, pictureStyleMap,
        { name -> PRESET_PICTURE_STYLE.copy(name = name) },
        { name, pictureLoader -> PRESET_PICTURE_STYLE.copy(name = name, picture = PictureRef(pictureLoader)) }
    )
    val tapeStyleResolver = AuxiliaryStyleResolver(
        tapes, isDirectory = Tape::fileSeq, tapeStyleMap,
        { name -> PRESET_TAPE_STYLE.copy(name = name) },
        { name, tape ->
            val tcFmt = if (tape.fileSeq) TimecodeFormat.FRAMES else try {
                tape.fps?.let { TimecodeFormat.SMPTE_NON_DROP_FRAME }
            } catch (_: IllegalArgumentException) {
                null
            } ?: TimecodeFormat.EXACT_FRAMES_IN_SECOND
            val pt = Opt(false, zeroTimecode(tcFmt))
            PRESET_TAPE_STYLE.copy(name = name, tape = TapeRef(tape), slice = TapeSlice(pt, pt))
        }
    )


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
    // remain valid until the next "@Break Harmonization" indication.
    var harmonizeHeadPartitionId = 0
    var harmonizeBodyPartitionId = 0
    var harmonizeTailPartitionId = 0

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
    var stageTransitionAfterFrames = 0
    var stageTransitionAfterStyle: TransitionStyle? = null

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
    var blockHead: PersistentList<StyledString>? = null
    val blockBody = mutableListOf<BodyElement>()
    var blockTail: PersistentList<StyledString>? = null
    var blockHarmonizeHeadPartitionId = 0
    var blockHarmonizeBodyPartitionId = 0
    var blockHarmonizeTailPartitionId = 0

    // Keep track where each stage has been declared, for use in an error message.
    var nextStageDeclaredRow = 0
    var stageDeclaredRow = 0
    // Keep track where the melting of stages has been declared, for use in an error message.
    var stageMeltDeclaredRow = 0
    // Keep track where the current head and tail have been declared. This is used by an error message.
    var blockHeadDeclaredRow = 0
    var blockTailDeclaredRow = 0

    fun concludePage() {
        // We allow empty scroll stages as long as there's at least one card stage on the page. This is useful to, e.g.,
        // have a card scroll in (using an empty scroll stage) and then fade out.
        if (pageStages.any { stage -> stage.compounds.isNotEmpty() }) {
            val gapAfterFrames = pageGapAfterFrames ?: if (stageStyle == null) 0 else
                pageStages.last().style.subsequentGapFrames
            val page = Page(pageStages.toPersistentList(), gapAfterFrames)
            pages.add(page)
        } else {
            // When discarding a page, remove its stages from all runtime groups. Otherwise, these orphaned stages might
            // confuse the subsequent processing steps.
            unnamedRuntimeGroups.replaceAll { g -> RuntimeGroup(g.stages.removeAll(pageStages), g.runtimeFrames) }
            namedRuntimeGroups.replaceAll { _, g -> RuntimeGroup(g.stages.removeAll(pageStages), g.runtimeFrames) }
        }
        pageStages.clear()
        pageGapAfterFrames = null
    }

    fun concludeStage(vGapAfter: Double) {
        // Note: We allow empty scroll stages. Pages that are fully empty will be filtered out by concludePage().
        if (stageCompounds.isNotEmpty() || stageStyle?.behavior == PageBehavior.SCROLL) {
            val stageStyle = this.stageStyle!!
            val cardRuntimeFrames = stageRuntimeFrames ?: stageStyle.cardRuntimeFrames
            val stage = Stage(
                stageStyle, cardRuntimeFrames, stageCompounds.toPersistentList(), vGapAfter,
                stageTransitionAfterFrames, stageTransitionAfterStyle
            )
            pageStages += stage
            when (stageStyle.behavior) {
                PageBehavior.CARD -> {}
                PageBehavior.SCROLL -> {
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
        } else if (stageStyle != null)
            table.log(stageDeclaredRow, "pageStyle", WARN, l10n("projectIO.credits.emptyCardPage"))
        stageStyle = nextStageStyle
        stageRuntimeFrames = nextStageRuntimeFrames
        stageRuntimeGroupName = nextStageRuntimeGroupName
        stageDeclaredRow = nextStageDeclaredRow
        stageCompounds.clear()
        stageTransitionAfterFrames = 0
        stageTransitionAfterStyle = null
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
                blockHarmonizeHeadPartitionId, blockHarmonizeBodyPartitionId, blockHarmonizeTailPartitionId
            )
        else {
            if (blockHead != null)
                table.log(blockHeadDeclaredRow, "head", WARN, l10n("projectIO.credits.unusedHead"))
            if (blockTail != null)
                table.log(blockTailDeclaredRow, "tail", WARN, l10n("projectIO.credits.unusedTail"))
        }
        blockStyle = contentStyle
        blockHead = null
        blockBody.clear()
        blockTail = null
        blockHarmonizeHeadPartitionId = harmonizeHeadPartitionId
        blockHarmonizeBodyPartitionId = harmonizeBodyPartitionId
        blockHarmonizeTailPartitionId = harmonizeTailPartitionId
        isBlockConclusionMarked = false
    }


    /* ************************************
       ********** ACTUAL PARSING **********
       ************************************ */

    fun read(): Credits {
        legacyAddStylesForUnhintedAuxFiles()

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
            table.log(null, null, ERROR, l10n("projectIO.credits.noPages"))

        // Collect the runtime groups.
        val runtimeGroups = unnamedRuntimeGroups + namedRuntimeGroups.values

        return Credits(table.spreadsheet.name, pages.toPersistentList(), runtimeGroups.toPersistentList())
    }

    fun legacyAddStylesForUnhintedAuxFiles() {
        for (row in 0..<table.numRows) {
            parseTaggedString(
                table.getString(row, "body") ?: continue,
                visitPlain = {},
                visitTag = { tag ->
                    val (tagKey, tagVal) = tag.split(' ', limit = 2).also { if (it.size != 2) return@parseTaggedString }
                    when (tagKey) {
                        in PIC_KW -> pictureStyleResolver.legacyAddStyleForUnhinted(tagVal)
                        in VIDEO_KW -> tapeStyleResolver.legacyAddStyleForUnhinted(tagVal)
                    }
                })
        }
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
                        table.log(row, "vGap", WARN, l10n("projectIO.credits.vGapAlreadySet", explicitVGapPx!!))
                } catch (_: IllegalArgumentException) {
                    table.log(row, "vGap", WARN, l10n("projectIO.credits.illFormattedVGap", "<i>px</i>"))
                }
        }

        // If the page style cell is non-empty, mark the previous stage for conclusion (if there was any). Use the
        // specified page style for the stage that starts immediately afterwards. Also reset the spine positioning info.
        table.getLookup(row, "pageStyle", pageStyleMap, "projectIO.credits.unavailablePageStyle")?.let { newPageStyle ->
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
                            val msg = l10n("projectIO.credits.pageRuntimeGroupRedeclared", l10nQuoted(runtimeGroupName))
                            table.log(row, "pageRuntime", WARN, msg)
                        } else
                            nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, timecode)
                    } else
                        nextStageRuntimeFrames = parseTimecode(fps, timecodeFormat, str)
                } catch (_: IllegalArgumentException) {
                    val examples = l10nEnumQuoted(sampleTimecode, "XYZ $sampleTimecode", "XYZ")
                    val msg = l10n("projectIO.credits.illFormattedPageRuntime", "<i>$timecodeFormatLabel</i>", examples)
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
            fun illFormattedPageGapMsg(): String {
                val melt = l10n(MELT_KW.key)
                return l10n(
                    "projectIO.credits.illFormattedPageGap",
                    "<i>$timecodeFormatLabel</i>", l10nQuoted(sampleTimecode), l10nQuoted("-$sampleTimecode"),
                    "<i>$melt</i>", l10nQuoted(melt),
                    l10nQuoted("$melt $sampleTimecode ${l10n("project.template.transitionStyleLinear")}")
                )
            }

            fun unavailableTransitionStyleMsg(name: String) = l10n(
                "projectIO.credits.unavailableTransitionStyle",
                l10nQuoted(name), "<i>${l10nEnum(transitionStyleMap.keys)}</i>"
            )

            if (!table.isEmpty(row, "pageStyle"))
                table.log(row, "pageGap", WARN, l10n("projectIO.credits.pageGapInNewPageRow"))
            else if (pageGapAfterFrames != null || stageMeltWithNext)
                table.log(row, "pageGap", WARN, l10n("projectIO.credits.pageGapAlreadySet"))
            else if (str.substringBefore(' ') in MELT_KW) {
                stageMeltWithNext = true
                stageMeltDeclaredRow = row
                val parts = str.split(' ', limit = 3)
                when (parts.size) {
                    2 -> table.log(row, "pageGap", WARN, illFormattedPageGapMsg())
                    3 -> {
                        try {
                            stageTransitionAfterFrames =
                                parseTimecode(styling.global.fps, styling.global.timecodeFormat, parts[1])
                        } catch (_: IllegalArgumentException) {
                            table.log(row, "pageGap", WARN, illFormattedPageGapMsg())
                            return@let
                        }
                        stageTransitionAfterStyle = transitionStyleMap[parts[2]]
                        if (stageTransitionAfterStyle == null)
                            table.log(row, "pageGap", WARN, unavailableTransitionStyleMsg(parts[2]))
                    }
                }
            } else
                try {
                    val c0 = str[0]
                    val tcStr = if (c0 == '+' || c0 == '-' || c0 == '\u2212') str.substring(1) else str
                    val n = parseTimecode(styling.global.fps, styling.global.timecodeFormat, tcStr)
                    pageGapAfterFrames = if (c0 == '-' || c0 == '\u2212') -n else n
                } catch (_: IllegalArgumentException) {
                    table.log(row, "pageGap", WARN, illFormattedPageGapMsg())
                }
        }

        // If the spine pos cell is non-empty, conclude the previous spine (if there was any) and start a new one.
        // Also conclude the previous compound if applicable.
        table.getString(row, "spinePos")?.let { str ->
            fun hookAtNewPageMsg(kw: String) = l10n("projectIO.credits.hookAtNewPage", l10nQuoted(kw))

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
                        table.log(row, "spinePos", WARN, hookAtNewPageMsg(parts[0]))
                        return@let
                    }
                    hook = true
                    if (parts.size > 1) {
                        val i = parts[1].toInt()
                        if (i in 1..u)
                            nextSpineHookTo = u - i
                        else {
                            val msg = l10n("projectIO.credits.invalidHookOrdinal", l10nQuoted(i), u)
                            table.log(row, "spinePos", WARN, msg)
                        }
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
                            table.log(row, "spinePos", WARN, hookAtNewPageMsg(parts[1]))
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
                    hookKw -> {
                        val kw = parts[0]
                        val top = l10n(TOP_KW.key)
                        val mid = l10n(MIDDLE_KW.key)
                        val bot = l10n(BOTTOM_KW.key)
                        l10n(
                            "projectIO.credits.illFormattedSpinePosHook", l10nQuoted(kw), u,
                            "<i>$top</i>", "<i>$mid</i>", "<i>$bot</i>",
                            l10nEnumQuoted("$kw 1 $bot-$top", "$kw 1 $top-$top 800", "$kw 2 $bot-$mid 800 100")
                        )
                    }
                    onCard -> {
                        val below = l10n(BELOW_KW.key)
                        val parallel = l10n(PARALLEL_KW.key)
                        val hook = l10n(HOOK_KW.key)
                        l10n(
                            "projectIO.credits.illFormattedSpinePosCard",
                            "<i>$below</i>", "<i>${l10n(ABOVE_KW.key)}</i>", "<i>$parallel</i>", "<i>$hook</i>",
                            l10nEnumQuoted("-400", "-400 200", "-400 200 $below", "-400 $parallel", "$hook \u2026")
                        )
                    }
                    else -> {
                        val parallel = l10n(PARALLEL_KW.key)
                        val hook = l10n(HOOK_KW.key)
                        l10n(
                            "projectIO.credits.illFormattedSpinePosScroll",
                            "<i>$parallel</i>", "<i>$hook</i>",
                            l10nEnumQuoted("-400", "-400 $parallel", "$hook \u2026")
                        )
                    }
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

        // If the break harmonization cell is used, start a new harmonization partition for the head, body, and/or tail,
        // and mark the previous block for conclusion (if there was any).
        table.getString(row, "breakHarmonization")?.let { str ->
            val parts = str.split(' ')
            val unknown = mutableListOf<String>()
            for (part in parts)
                when (part) {
                    in HEAD_KW -> harmonizeHeadPartitionId++
                    in BODY_KW -> harmonizeBodyPartitionId++
                    in TAIL_KW -> harmonizeTailPartitionId++
                    else -> unknown.add(part)
                }
            if (unknown.size != parts.size)
                isBlockConclusionMarked = true
            if (unknown.isNotEmpty()) {
                val kws = l10nEnumQuoted(unknown)
                val opts = "<i>${l10nEnum(l10n(HEAD_KW.key), l10n(BODY_KW.key), l10n(TAIL_KW.key))}</i>"
                val msg = l10n("projectIO.credits.unknownBreakHarmonizationKeyword", unknown.size, kws, opts)
                table.log(row, "breakHarmonization", WARN, msg)
            }
        }

        // Get the body element, which may either be a styled string or a picture or tape.
        val bodyOnly1Line = contentStyle?.bodyLayout != BodyLayout.PARAGRAPHS
        val bodyElem = getBodyElement("body", contentStyle?.bodyLetterStyleName, bodyOnly1Line)

        // Get the head and tail, which may only be styled strings.
        val ht1L = contentStyle?.blockOrientation != BlockOrientation.VERTICAL
        val newHead = (getBodyElement("head", contentStyle?.headLetterStyleName, ht1L, true) as BodyElement.Str?)?.lines
        val newTail = (getBodyElement("tail", contentStyle?.tailLetterStyleName, ht1L, true) as BodyElement.Str?)?.lines

        // If either head or tail is available, or if a body is available and the conclusion of the previous block
        // has been marked, conclude the previous block (if there was any) and start a new one.
        val isConclusionMarked = isBlockConclusionMarked || isSpineConclusionMarked || isCompoundConclusionMarked
        if (newHead != null || newTail != null || (bodyElem != null && isConclusionMarked) || isStageConclusionMarked) {
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
                var isLastOnPage = when {
                    stageMeltWithNext ->
                        if (currStyle?.behavior == nextStyle.behavior) {
                            val msg = l10n("projectIO.credits.cannotMeltSameBehavior")
                            table.log(stageMeltDeclaredRow, "pageGap", WARN, msg)
                            true
                        } else false
                    // If no page gap has been declared and the two adjacent page styles still have the legacy melting
                    // flag set, we also want to melt the stages.
                    pageGapAfterFrames == null -> {
                        val c = currStyle?.behavior == PageBehavior.SCROLL && currStyle.scrollMeltWithNext
                        val n = nextStyle.behavior == PageBehavior.SCROLL && nextStyle.scrollMeltWithPrev
                        if ((c || n) && currStyle?.behavior != nextStyle.behavior) {
                            val msd = if (c) MigrationDataSource(currStyle, PageStyle::scrollMeltWithNext.st())
                            else MigrationDataSource(nextStyle, PageStyle::scrollMeltWithPrev.st())
                            table.logMigrationPut(row - 1, "pageGap", l10n(MELT_KW.key), msd)
                            false
                        } else true
                    }
                    else -> true
                }
                stageMeltWithNext = false
                concludeStage(vGap)
                // If the last stage was dropped (probably because it was an empty card stage) and we would now be left
                // with two back-to-back scroll stages, also conclude the page.
                if (nextStyle.behavior == PageBehavior.SCROLL &&
                    pageStages.lastOrNull()?.style?.behavior == PageBehavior.SCROLL
                ) isLastOnPage = true
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
            table.log(row, "head", WARN, l10n("projectIO.credits.headUnsupported", "<i>${contentStyle!!.name}</i>"))
        }
        if (newTail != null && !contentStyle!!.hasTail) {
            blockTail = null
            table.log(row, "tail", WARN, l10n("projectIO.credits.tailUnsupported", "<i>${contentStyle!!.name}</i>"))
        }

        // If the body cell is non-empty, add its content to the current block.
        if (bodyElem != null)
            blockBody.add(bodyElem)
        // Otherwise, if the row didn't just start a new block,
        // mark the previous block for conclusion (if there was any).
        else if (isHBTFreeRow)
            isBlockConclusionMarked = true
    }

    fun getBodyElement(
        l10nColName: String, initLetterStyleName: String?, only1Line: Boolean, onlyStr: Boolean = false
    ): BodyElement? {
        fun unavailableLetterStyleMsg(name: String) = l10n(
            "projectIO.credits.unavailableLetterStyle", l10nQuoted(name), "<i>${l10nEnum(letterStyleMap.keys)}</i>"
        )

        fun unknownTagMsg(tagKey: String) = l10n(
            "projectIO.credits.unknownTagKeyword", l10nQuoted("{{$tagKey …}}"), l10nQuoted("\\{{$tagKey …}}"),
            "<i>" + l10nEnum(listOf(BLANK_KW, STYLE_KW, PIC_KW, VIDEO_KW).map { "{{${l10n(it.key)}}}" }) + "</i>"
        )

        fun tagDisallowedMsg(tagKey: String) = l10n("projectIO.credits.tagDisallowed", l10nQuoted("{{$tagKey …}}"))
        fun tagNotLoneMsg(tagKey: String) = l10n("projectIO.credits.tagNotLone", l10nQuoted("{{$tagKey …}}"))

        val str = table.getString(row, l10nColName) ?: return null
        val initLetterStyle = initLetterStyleName?.let { letterStyleMap[it] } ?: PLACEHOLDER_LETTER_STYLE

        var curLetterStyle: LetterStyle? = null
        val styledLines = mutableListOf(mutableListOf<Pair<String, LetterStyle>>())
        var blankTagKey: String? = null
        var multipleBlanks = false
        var pictureOrVideoTagKey: String? = null
        var pictureStyle: PictureStyle? = null
        var tapeStyle: TapeStyle? = null
        var multiplePicturesOrVideos = false
        parseTaggedString(
            str,
            visitPlain = { plain ->
                // When we encounter plaintext, add it to the styled string list using the current letter style.
                // If it contains line delimiters, terminate the current line and commence new ones.
                for ((l, plainLine) in plain.split("\n", "\r\n").withIndex()) {
                    if (l != 0)
                        styledLines.add(mutableListOf())
                    if (plainLine.isNotEmpty())
                        styledLines.last().add(Pair(plainLine, curLetterStyle ?: initLetterStyle))
                }
            },
            visitTag = { tag ->
                val tagParts = tag.split(' ', limit = 2)
                val tagVal = tagParts.getOrNull(1)
                when (val tagKey = tagParts[0]) {
                    // When we encounter a blank tag, remember it.
                    // We can't immediately return because we want to issue a warning if the blank tag is not lone.
                    in BLANK_KW -> when {
                        onlyStr -> table.log(row, l10nColName, WARN, tagDisallowedMsg(tagKey))
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
                        onlyStr -> table.log(row, l10nColName, WARN, tagDisallowedMsg(tagKey))
                        else -> when (pictureOrVideoTagKey) {
                            null -> {
                                pictureOrVideoTagKey = tagKey
                                when (tagKey) {
                                    in PIC_KW -> pictureStyle = getPictureStyle(l10nColName, tagKey, tagVal)
                                    in VIDEO_KW -> tapeStyle = getTapeStyle(l10nColName, tagKey, tagVal)
                                }
                            }
                            else -> multiplePicturesOrVideos = true
                        }
                    }
                    else -> table.log(row, l10nColName, WARN, unknownTagMsg(tagKey))
                }
            })

        styledLines.removeIf { it.isEmpty() }
        val hasPlaintext = styledLines.isNotEmpty()
        return when {
            blankTagKey != null -> {
                if (hasPlaintext || curLetterStyle != null || multipleBlanks || pictureOrVideoTagKey != null)
                    table.log(row, l10nColName, WARN, tagNotLoneMsg(blankTagKey))
                BodyElement.Nil(initLetterStyle)
            }
            pictureOrVideoTagKey != null -> {
                if (hasPlaintext || curLetterStyle != null || multiplePicturesOrVideos)
                    table.log(row, l10nColName, WARN, tagNotLoneMsg(pictureOrVideoTagKey))
                pictureStyle?.let(BodyElement::Pic) ?: tapeStyle?.let(BodyElement::Tap) ?: BodyElement.Mis
            }
            hasPlaintext -> {
                if (only1Line && styledLines.size != 1)
                    table.log(row, l10nColName, WARN, l10n("projectIO.credits.linebreakUnsupported"))
                val missingGlyphLines = styledLines.filter { it.formatted(styling).missesGlyphs }
                if (missingGlyphLines.isNotEmpty()) {
                    val ns = missingGlyphLines.flatten().mapTo(TreeSet()) { it.second.name }
                    val msg = l10n("project.styling.constr.missingGlyphs", ns.size, l10nEnumQuoted(ns))
                    table.log(row, l10nColName, WARN, msg)
                }
                BodyElement.Str(styledLines.toPersistentList())
            }
            else -> {
                table.log(row, l10nColName, WARN, l10n("projectIO.credits.effectivelyEmpty"))
                BodyElement.Str(persistentListOf(listOf(Pair("???", PLACEHOLDER_LETTER_STYLE))))
            }
        }
    }

    fun getPictureStyle(l10nColName: String, tagKey: String, tagVal: String?) = pictureStyleResolver.resolve(
        l10nColName, tagKey, tagVal,
        applyHints = { style0, hints ->
            var style = style0
            while (hints.hasNext()) {
                val hint = hints.next()
                style = try {
                    when {
                        // Crop the picture.
                        hint in CROP_KW -> style.copy(cropBlankSpace = true)
                        // Apply scaling hints.
                        hint.startsWith('x') -> style.copy(heightPx = Opt(true, hint.drop(1).toDouble()))
                        hint.endsWith('x') -> style.copy(widthPx = Opt(true, hint.dropLast(1).toDouble()))
                        else -> continue
                    }
                } catch (_: IllegalArgumentException) {
                    continue
                }
                hints.remove()
            }
            style
        },
        illFormattedMsg = {
            l10n(
                "projectIO.credits.pictureIllFormatted",
                l10nEnumQuoted("{{$tagKey Cinecred Logo}}", "{{$tagKey Cinecred Logo.svg Large}}")
            )
        }
    )

    fun getTapeStyle(l10nColName: String, tagKey: String, tagVal: String?) = tapeStyleResolver.resolve(
        l10nColName, tagKey, tagVal,
        applyHints = { style0, hints ->
            var style = style0
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
                        style = when {
                            isMargin ->
                                style.copy(leftTemporalMarginFrames = lFrames, rightTemporalMarginFrames = rFrames)
                            else -> {
                                val linearName = l10n("project.template.transitionStyleLinear")
                                style.copy(
                                    fadeInFrames = lFrames, fadeInTransitionStyleName = linearName,
                                    fadeOutFrames = rFrames, fadeOutTransitionStyleName = linearName
                                )
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        // Pass
                    }
                } else if (isIn || isOut) {
                    hints.remove()
                    val tcHint = if (hints.hasNext()) hints.next().also { hints.remove() } else null
                    if (tcHint != null)
                        for (tcFmt in TimecodeFormat.entries) {
                            val tc = try {
                                parseTimecode(tcFmt, tcHint)
                            } catch (_: IllegalArgumentException) {
                                continue
                            }
                            val slice = style.slice
                            val z = Opt(false, zeroTimecode(tcFmt))
                            val i = if (isIn) Opt(true, tc) else if (slice.inPoint.isActive) slice.inPoint else z
                            val o = if (isOut) Opt(true, tc) else if (slice.outPoint.isActive) slice.outPoint else z
                            try {
                                style = style.copy(slice = TapeSlice(i, o))
                            } catch (_: IllegalArgumentException) {
                            }
                            break
                        }
                } else if (isMid || isEnd) {
                    style = style.copy(temporallyJustify = if (isMid) HJustify.CENTER else HJustify.RIGHT)
                    hints.remove()
                } else {
                    style = try {
                        when {
                            // Apply scaling hints.
                            hint.startsWith('x') -> style.copy(heightPx = Opt(true, hint.drop(1).toInt()))
                            hint.endsWith('x') -> style.copy(widthPx = Opt(true, hint.dropLast(1).toInt()))
                            else -> continue
                        }
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    hints.remove()
                }
            }
            style
        },
        illFormattedMsg = {
            l10n(
                "projectIO.credits.videoIllFormatted",
                l10nEnumQuoted("{{$tagKey Blooper 3}}", "{{$tagKey Blooper 3.mov Large}}")
            )
        }
    )


    /* ***********************************
       ********** MISCELLANEOUS **********
       *********************************** */

    private val timecodeFormatLabel get() = styling.global.timecodeFormat.label

    private val sampleTimecode: String
        get() = try {
            formatTimecode(styling.global.fps, styling.global.timecodeFormat, 7127)
        } catch (_: IllegalArgumentException) {
            "???"
        }


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
        val CROP_KW = legacyKeyword("Crop", "Oříznutí", "Stutzen", "Rogner", "裁剪")
        val VIDEO_KW = Keyword("projectIO.credits.table.video")
        val MARGIN_KW = legacyKeyword("Margin", "Odsazení", "Rand", "Marge", "边缘")
        val FADE_KW = legacyKeyword("Fade", "Blende", "Fondu", "淡入")
        val END_KW = legacyKeyword("End", "Konec", "Ende", "Fin", "末尾")
        val IN_KW = legacyKeyword("In", "Entrée", "入点")
        val OUT_KW = legacyKeyword("Out", "Sortie", "出点")

        private fun legacyKeyword(vararg kwSet: String) =
            TreeSet(ROOT_CASE_INSENSITIVE_COLLATOR).apply { addAll(kwSet) }

        fun String.toFiniteDouble(nonNeg: Boolean = false): Double {
            val f = replace(',', '.').toDouble()
            if (!f.isFinite() || nonNeg && f < 0.0)
                throw NumberFormatException()
            return f
        }

    }


    class Keyword(val key: String) {
        private val kwSet = TRANSLATED_LOCALES.mapTo(TreeSet(ROOT_CASE_INSENSITIVE_COLLATOR)) { l ->
            l10n(key, l).also { kw -> require(' ' !in kw) { "Keyword '$kw' from $key @ $l contains whitespace" } }
        }

        operator fun contains(str: String) = str in kwSet
    }


    inner class AuxiliaryStyleResolver<S : PopupStyle, A : Any>(
        auxiliaries: Map<String, A>,
        isDirectory: (A) -> Boolean,
        private val styleMap: MutableMap<String, S>,
        private val makeStyle1: (String) -> S,
        private val makeStyle2: (String, A) -> S
    ) {

        // Put the auxiliaries into a map whose keys are the filenames. Also record all duplicate filenames.
        // Use a map with case-insensitive keys to ease the user experience.
        private val auxMap = TreeMap<String, A>(ROOT_CASE_INSENSITIVE_COLLATOR)
        private val dupSet = TreeSet(ROOT_CASE_INSENSITIVE_COLLATOR)

        init {
            fun registerFilename(filename: String, aux: A) {
                if (filename !in dupSet)
                    if (filename !in auxMap)
                        auxMap[filename] = aux
                    else {
                        auxMap.remove(filename)
                        dupSet.add(filename)
                    }
            }

            for ((name, aux) in auxiliaries)
                registerFilename(name, aux)

            val primaryFilenames = TreeSet(auxMap.navigableKeySet()) // freeze
            for ((name, aux) in auxiliaries) {
                val stem = name.substringBeforeLast(".", "")
                if (!isDirectory(aux) && !stem.isEmpty() && stem !in primaryFilenames)
                    registerFilename(stem, aux)
            }
        }

        fun legacyAddStyleForUnhinted(tagVal: String) {
            auxMap[tagVal]?.let { aux ->
                styleMap.computeIfAbsent(tagVal) {
                    makeStyle2(tagVal, aux).copy(PopupStyle::volatile.st().notarize(true))
                }
            }
        }

        fun resolve(
            l10nColName: String,
            tagKey: String,
            tagVal: String?,
            applyHints: (S, PeekingIterator<String>) -> S,
            illFormattedMsg: () -> String
        ): S? {
            // If the tag value is empty, abort.
            if (tagVal == null) {
                table.log(row, l10nColName, WARN, illFormattedMsg())
                return null
            }

            // If a style with this tag value already exists, return that.
            styleMap[tagVal]?.let { return it }

            var style: S? = null

            // Remove arbitrary many space-separated suffixes from the string and each time check whether the remaining
            // string is a filename. If that is the case, try to parse the suffixes as hints.
            var splitIdx = tagVal.length
            do {
                val filename = tagVal.take(splitIdx).trim()
                // If the filename belongs to an auxiliary file, apply the hint suffixes and return the result.
                val rawStyle = auxMap[filename]?.let { makeStyle2(filename, it) }
                    ?: if (filename in dupSet) makeStyle1(filename) else null
                if (rawStyle != null) {
                    val unrecognizedHints = tagVal.substring(splitIdx).splitToSequence(' ')
                        .filterTo(mutableListOf(), String::isNotBlank)
                    style = applyHints(rawStyle, Iterators.peekingIterator(unrecognizedHints.iterator()))
                    style = style.copy(PopupStyle::volatile.st().notarize(style == rawStyle))
                    if (unrecognizedHints.isNotEmpty())
                        style = style.copy(PopupStyle::name.st().notarize(tagVal))
                    break
                }
                splitIdx = filename.lastIndexOf(' ')
            } while (splitIdx != -1)

            // The tag value doesn't contain a known filename.
            if (style == null)
                style = makeStyle1(tagVal).copy(PopupStyle::volatile.st().notarize(true))

            var i = 1
            while (true) {
                val rename = style.name + if (i == 1) "" else " $i"
                val renameNotar = PopupStyle::name.st().notarize(rename)
                val renamedSty = style.copy(renameNotar)
                val foundStyle = styleMap.putIfAbsent(rename, renamedSty)
                if (foundStyle == null ||
                    // Change name before comparing because == on it is case-sensitive.
                    foundStyle.copy(renameNotar, PopupStyle::volatile.st().notarize(renamedSty.volatile))
                        .equalsIgnoreIneffectiveSettings(styling, renamedSty)
                ) {
                    if (!ROOT_CASE_INSENSITIVE_COLLATOR.equals(rename, tagVal))
                        table.logMigrationPut(row, l10nColName, "{{$tagKey $rename}}")
                    return foundStyle ?: renamedSty
                }
                i++
            }
        }

    }

}
