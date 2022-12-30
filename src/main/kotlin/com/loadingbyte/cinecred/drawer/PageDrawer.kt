package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GROUNDING
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.FormattedString
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.imaging.Y.Companion.toElasticY
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL
import kotlinx.collections.immutable.toPersistentList
import java.awt.Font
import java.awt.geom.Path2D
import java.util.*
import javax.swing.UIManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor


private class StageLayout(val y: Y, val info: DrawnStageInfo)
private class DrawnSpine(val defImage: DeferredImage, val spineXInImage: Double)


fun drawPages(project: Project): List<DrawnPage> {
    val styling = project.styling
    val global = styling.global
    val pages = project.pages
    val runtimeGroups = project.runtimeGroups

    val textCtx = makeTextCtx(global.locale, global.uppercaseExceptions, project.stylingCtx)

    // First generate a body and then a block image for each block. This has to be done for all blocks at the same time
    // because heads, bodies, and tails can harmonize various widths and heights between them.
    val blocks = buildList {
        for (page in pages)
            for (stage in page.stages)
                for (segment in stage.segments)
                    for (spine in segment.spines)
                        addAll(spine.blocks)
    }
    val drawnBodies = drawBodies(project.styling.contentStyles, project.styling.letterStyles, textCtx, blocks)
    val drawnBlocks = drawBlocks(project.styling.contentStyles, textCtx, drawnBodies, blocks)

    // Generate a stage image for each stage. These stage images already contain the vertical gaps between the stages.
    var stageImages: MutableMap<Stage, DeferredImage> = HashMap()
    for (page in pages)
        page.stages.withIndex().associateTo(stageImages) { (stageIdx, stage) ->
            val prevStage = page.stages.getOrNull(stageIdx - 1)
            val nextStage = page.stages.getOrNull(stageIdx + 1)
            stage to drawStage(global, drawnBlocks, stage, prevStage, nextStage)
        }

    val pageTopStages = pages.mapTo(HashSet()) { page -> page.stages.first() }
    val pageBotStages = pages.mapTo(HashSet()) { page -> page.stages.last() }

    // If requested, adjust some vertical gaps to best match a specified runtime.
    if (runtimeGroups.isNotEmpty() || global.runtimeFrames.isActive) {
        // Run a first layout pass to determine how many frames each scrolling stage will scroll for.
        val prelimStageLayouts = HashMap<Stage, StageLayout>()
        for (page in pages)
            prelimStageLayouts.putAll(layoutStages(global, stageImages, page).second)
        // Use that information to scale the vertical gaps.
        stageImages = matchRuntime(
            pages, stageImages, prelimStageLayouts, pageTopStages, pageBotStages,
            global.runtimeFrames, runtimeGroups
        )
    }

    // Finally, do the real layout pass with potentially changed stage images and combine the stage images
    // to page images.
    return pages.map { page ->
        val (pageImageHeight, stageLayouts) = layoutStages(global, stageImages, page)
        val pageImage = drawPage(global, stageImages, stageLayouts, pageTopStages, pageBotStages, page, pageImageHeight)
        DrawnPage(pageImage, stageLayouts.values.map(StageLayout::info).toPersistentList())
    }
}


private fun drawStage(
    global: Global,
    drawnBlocks: Map<Block, DrawnBlock>,
    stage: Stage,
    prevStage: Stage?,
    nextStage: Stage?
): DeferredImage {
    val stageImage = DeferredImage(width = global.widthPx.toDouble())
    var y = 0.0.toY()

    // If this stage is a scroll stage is preceded by a card stage, add the vertical gap behind the card stage to the
    // front of this stage because card stage images are not allowed to have extremal vertical gaps baked into them.
    if (stage.style.behavior != CARD && prevStage != null && prevStage.style.behavior == CARD)
        y += prevStage.vGapAfterPx.toElasticY()

    for ((segmentIdx, segment) in stage.segments.withIndex()) {
        var maxHeight = 0.0.toY()
        for (spine in segment.spines) {
            val drawnSpine = drawSpine(drawnBlocks, spine)
            val spineXInPageImage = global.widthPx / 2.0 + spine.posOffsetPx
            val x = spineXInPageImage - drawnSpine.spineXInImage
            stageImage.drawDeferredImage(drawnSpine.defImage, x, y)
            maxHeight = maxHeight.max(drawnSpine.defImage.height)
        }
        y += maxHeight
        if (segmentIdx != stage.segments.lastIndex)
            y += segment.vGapAfterPx.toElasticY()
    }

    // If this stage is a scroll stage and not the last stage on the page, add the gap behind it to its image.
    if (stage.style.behavior != CARD && nextStage != null)
        y += stage.vGapAfterPx.toElasticY()

    // Set the stage image's height.
    stageImage.height = y

    return stageImage
}


private fun drawSpine(
    drawnBlocks: Map<Block, DrawnBlock>,
    spine: Spine
): DrawnSpine {
    // Combine the block images of the blocks that are attached to the spine to a spine image.
    val spineXInSpineImage = spine.blocks.maxOf { block -> drawnBlocks.getValue(block).spineXInImage }
    val spineImageWidth = spineXInSpineImage +
            spine.blocks.maxOf { block -> drawnBlocks.getValue(block).run { defImage.width - spineXInImage } }
    val spineImage = DeferredImage(width = spineImageWidth)
    var y = 0.0.toY()
    for ((blockIdx, block) in spine.blocks.withIndex()) {
        y += block.style.vMarginPx.toElasticY()
        val drawnBlock = drawnBlocks.getValue(block)
        val x = spineXInSpineImage - drawnBlock.spineXInImage
        spineImage.drawDeferredImage(drawnBlock.defImage, x, y)
        y += drawnBlock.defImage.height
        if (blockIdx != spine.blocks.lastIndex)
            y += (block.style.vMarginPx + block.vGapAfterPx).toElasticY()
    }
    // Set the spine image's height.
    spineImage.height = y

    // Draw a guide that shows the spine position.
    spineImage.drawLine(SPINE_GUIDE_COLOR, spineXInSpineImage, 0.0.toY(), spineXInSpineImage, y, layer = GUIDES)

    return DrawnSpine(spineImage, spineXInSpineImage)
}


private fun layoutStages(
    global: Global,
    stageImages: Map<Stage, DeferredImage>,
    page: Page
): Pair<Y, Map<Stage, StageLayout>> {
    // Determine each stage's top and bottom y coordinates in the future page image.
    // Also find the height of the whole future page image if that is given explicitly.
    val stageImageBounds = mutableListOf<Pair<Y, Y>>()
    var pageImageHeight = 0.0.toY()
    var y = 0.0.toY()
    for ((stageIdx, stage) in page.stages.withIndex()) {
        val stageHeight = stageImages.getValue(stage).height
        // Special handling for card stages...
        if (stage.style.behavior == CARD) {
            // The amount of padding that needs to be added above and below the card's stage image such that
            // it is centered on the screen. Notice that as scrolling from/to a card starts/ends at the card's center,
            // the padding is irrelevant for runtime matching and can hence be rigid.
            val cardPaddingHeight = (global.heightPx - stageHeight.resolve()) / 2.0
            // If this card stage is the first and/or the last stage, make sure that there is extra padding below
            // and above the card stage such that its content is centered vertically.
            if (stageIdx == 0)
                y += cardPaddingHeight
            if (stageIdx == page.stages.lastIndex)
                pageImageHeight += cardPaddingHeight
        }
        // Record the stage content's tightest top and bottom y coordinates in the overall page image.
        stageImageBounds.add(Pair(y, y + stageHeight))
        // Advance to the next stage image.
        y += stageHeight
    }
    pageImageHeight += y

    // Find for each stage:
    //   - If it's a card stage, its middle y coordinate in the overall page image.
    //   - If it's a scroll stage:
    //       - The y coordinates in the overall page image that are at the center of the screen when the scrolling of
    //         this stage starts respectively stops.
    //       - Recall that scroll stages can scroll into melted card stages. As such, these coordinates can lie inside
    //         of card stages. For some purposes however, we need the portion of the scrolled height which is truly
    //         occupied by the scroll stage.
    //       - The number of frames that make up the scroll, and a fraction of a frame that should be skipped prior to
    //         the beginning of the scroll.
    var nextScrollInitAdvance = Double.NaN
    val stageInfo = page.stages.mapIndexed { stageIdx, stage ->
        val (topY, botY) = stageImageBounds[stageIdx]
        when (stage.style.behavior) {
            CARD -> DrawnStageInfo.Card((topY + botY) / 2.0)
            SCROLL -> {
                val prevStageBehavior = page.stages.getOrNull(stageIdx - 1)?.run { style.behavior }
                val nextStageBehavior = page.stages.getOrNull(stageIdx + 1)?.run { style.behavior }
                // Find the scroll start and end y coordinates.
                val scrollStartY = when (prevStageBehavior) {
                    CARD -> stageImageBounds[stageIdx - 1].let { (aTopY, aBotY) -> (aTopY + aBotY) / 2.0 }
                    SCROLL, null -> topY - global.heightPx / 2.0
                }
                val scrollStopY = when (nextStageBehavior) {
                    CARD -> stageImageBounds[stageIdx + 1].let { (bTopY, bBotY) -> (bTopY + bBotY) / 2.0 }
                    SCROLL -> botY - global.heightPx / 2.0
                    null -> botY + global.heightPx / 2.0
                }
                // Find the portion of the scrolled height which really belongs to the scroll stage itself. If you are
                // confused, recall that scroll stages can scroll into melted card stages.
                val ownedScrollStartY = if (prevStageBehavior == CARD) topY else scrollStartY
                val ownedScrollStopY = if (nextStageBehavior == CARD) botY else scrollStopY
                val ownedScrollHeight = ownedScrollStopY - ownedScrollStartY
                // Find (a) how much of a single frame to advance the scroll prior to the first frame actually being
                // rendered (initAdvance) and (b) the number of frames required for the scroll.
                // To understand this, consider a scenario with the following immediate sequence of scroll stages:
                //  1. height = 10px & scroll speed = 2px per frame
                //  2. height = 10px & scroll speed = 3px per frame
                //  3. height =  9px & scroll speed = 2px per frame
                // This sequence is surrounded either by nothing or card stages. In either case, the scroll itself
                // should only contain "moving" frames, since the static ones (blank screen or still card) are already
                // fully covered by the frame gaps between pages or the card stages.
                //  1. We start with the first scroll stage. Its "moving" frames (i.e., all frames except the start
                //     and end ones) perfectly fit into 4 frames, with a 1 frame gap to both the start and end frames.
                //     The pixel offsets of each frame are 2, 4, 6, and 8, with the offsets 0 and 10 being excluded.
                //     In the code, initAdvance is 1 (because the previous stage's behavior is not scroll), meaning that
                //     the scroll skips the start frame at 0px offset. fracFrames comes out as 4.0, and hence frames is
                //     4 and the next scroll stage's initAdvance is set to 0.0.
                //  2. The second stage follows a scroll stage, so this time its start frame is a "moving" one and needs
                //     to be included. Since the first stage had a perfect 1 frame gap to its excluded end frame, we
                //     include the second stage's topmost frame, which is the same frame. This is realized by the
                //     initAdvance of 0.0 inherited from before. In the second stage however, the "moving" do not fit
                //     perfectly into any number of frames since fracFrames, i.e. height / scroll speed, is fractional,
                //     namely 3.33... This tells us that (a) we need 4 frames to represent the moving part (pixel
                //     offsets are 0, 3, 6, 9) and (b) the last frame only has a 0.33 gap to the excluded end frame at
                //     10px offset. In the code, frames is indeed ceil(3.33) = 4, and the next stage's initAdvance is
                //     0.66 to pay for the 1-0.33 residual frames left by the shorter gap to the excluded end frame.
                //  3. Hence, the third stage skips the first 0.66 frames. The pixel offsets are then 1.33, 3.33, 5.33,
                //     and 7.33; as always, the end frame at 9px offset is excluded. In the code, fracFrames comes out
                //     as "9 / 2 - 0.66 = 3.833", and frames is indeed ceil(3.833) = 4.
                val initAdvance = if (prevStageBehavior == SCROLL) nextScrollInitAdvance else 1.0
                val fracFrames =
                    (scrollStopY.resolve() - scrollStartY.resolve()) / stage.style.scrollPxPerFrame - initAdvance
                // If fracFrames is just slightly larger than an integer, we want it to be handled as if it was that
                // integer to compensate for floating point inaccuracy. That is why we have the -0.01 in there.
                val frames = safeCeil(fracFrames)
                nextScrollInitAdvance = (-fracFrames).mod(1.0)  // Using mod() ensures that the result is positive.
                // Construct the info object.
                DrawnStageInfo.Scroll(scrollStartY, ownedScrollHeight, frames, initAdvance)
            }
        }
    }

    val stageLayouts = page.stages.indices.associate { stageIdx ->
        page.stages[stageIdx] to StageLayout(stageImageBounds[stageIdx].first, stageInfo[stageIdx])
    }
    return Pair(pageImageHeight, stageLayouts)
}


private fun matchRuntime(
    pages: List<Page>,
    stageImages: Map<Stage, DeferredImage>,
    stageLayouts: Map<Stage, StageLayout>,
    pageTopStages: Set<Stage>,
    pageBotStages: Set<Stage>,
    globalDesiredFrames: Opt<Int>,
    runtimeGroups: List<RuntimeGroup>
): MutableMap<Stage, DeferredImage> {
    // Prepare two maps which enable simple access to the two stages before and after any scroll stage.
    val prevStages = HashMap<Stage, Stage>()
    val nextStages = HashMap<Stage, Stage>()
    for (page in pages)
        for ((scrollIdx, scroll) in page.stages.withIndex())
            if (scroll.style.behavior == SCROLL) {
                val prevStage = page.stages.getOrNull(scrollIdx - 1)
                val nextStage = page.stages.getOrNull(scrollIdx + 1)
                if (prevStage != null) prevStages[scroll] = prevStage
                if (nextStage != null) nextStages[scroll] = nextStage
            }

    // This function removes the card stages from a runtime group and subtract their fixed show/hide runtime from the
    // runtime group's desired runtime. The function then sorts the group into one of two sets: those whose runtime will
    // shrink, and those whose runtime will expand. This is required to fulfill a later assumption. Groups whose runtime
    // is already attained are discarded.
    val shrinkGroups = HashSet<RuntimeGroup>()
    val expandGroups = HashSet<RuntimeGroup>()
    fun addGroup(stages: List<Stage>, frames: Int): RuntimeGroup? {
        val scrolls = mutableListOf<Stage>()
        var desiredScrollFrames = frames
        for (stage in stages)
            when (stage.style.behavior) {
                CARD -> desiredScrollFrames -= getCardFrames(pageTopStages, pageBotStages, stage)
                SCROLL -> scrolls.add(stage)
            }
        if (scrolls.isEmpty())
            return null
        val group = RuntimeGroup(scrolls.toPersistentList(), desiredScrollFrames)
        val currentScrollFrames =
            scrolls.sumOf { scroll -> (stageLayouts.getValue(scroll).info as DrawnStageInfo.Scroll).frames }
        when {
            desiredScrollFrames < currentScrollFrames -> shrinkGroups.add(group)
            desiredScrollFrames > currentScrollFrames -> expandGroups.add(group)
            // If the desired frames are already attained, discard the group.
        }
        return group
    }

    // Preprocess all user-defined runtime groups accordingly.
    for (group in runtimeGroups)
        addGroup(group.stages, group.runtimeFrames)

    // If the global desired runtime is activated, add a runtime group with all remaining stages not covered by
    // user-defined runtime groups.
    var globalGroup: RuntimeGroup? = null
    var globalGroupFrames = 0
    val remStages = buildList { for (p in pages) for (s in p.stages) if (runtimeGroups.none { s in it.stages }) add(s) }
    if (globalDesiredFrames.isActive) {
        // Subtract the desired runtime of the user-defined runtime groups and the runtime in between pages from the
        // desired global runtime to obtain the desired runtime of the new group.
        globalGroupFrames = globalDesiredFrames.value -
                runtimeGroups.sumOf(RuntimeGroup::runtimeFrames) -
                pages.dropLast(1).sumOf { it.stages.last().style.afterwardSlugFrames }
        globalGroup = addGroup(remStages, globalGroupFrames)
    }

    // Run the runtime matching.
    val (adjustedStageImages, shortfall) =
        matchRuntime(pages, stageImages, stageLayouts, prevStages, nextStages, shrinkGroups, expandGroups)
    // If there is no global runtime adjustment happening (either because it is deactivated or because there are no
    // scroll stages remaining), or if all runtimes were matched perfectly, return the result
    if (globalGroup == null || shortfall == 0)
        return adjustedStageImages

    // If we are here, shortfall is non-zero, which means that some stages did not exactly attain their desired runtime.
    // Apart from some very rare situations where an exact match is impossible, this means that some runtime groups have
    // run out of vertical gaps to expense for decreasing their runtime.
    // However, the global runtime group expects all other runtime groups to have exact matches. Thus, we need to
    // compensate for the other groups' shortfall in the global group's desired runtime.
    shrinkGroups.remove(globalGroup)
    expandGroups.remove(globalGroup)
    addGroup(remStages, globalGroupFrames + shortfall)
    return matchRuntime(pages, stageImages, stageLayouts, prevStages, nextStages, shrinkGroups, expandGroups).first
}


private fun matchRuntime(
    pages: List<Page>,
    stageImages: Map<Stage, DeferredImage>,
    stageLayouts: Map<Stage, StageLayout>,
    prevStages: Map<Stage, Stage>,
    nextStages: Map<Stage, Stage>,
    shrinkGroups: Set<RuntimeGroup>,
    expandGroups: Set<RuntimeGroup>
): Pair<MutableMap<Stage, DeferredImage>, Int> {
    // Naturally, we do not modify the vertical gaps of standalone cards. Further, we only adjust the gaps of a melted
    // card if it either borders exactly one scroll stage (in which case it is basically treated exactly the same as the
    // scroll stage) or if it borders two scroll stages which both need to shrink or both need to expand (in which case
    // the card will shrink/expand as far as the more modest of the two scroll stages).
    // To enforce this policy, we "freeze" the vertical height of all stages that will not be adjusted, that is, we make
    // the stages' heights rigid and remember that we've frozen the stages.
    val frozenCards = HashSet<Stage>()
    for (page in pages)
        for ((cardIdx, card) in page.stages.withIndex()) {
            if (card.style.behavior == CARD) {
                operator fun Iterable<RuntimeGroup>.contains(stage: Stage) = any { stage in it.stages }
                val prevStage = page.stages.getOrNull(cardIdx - 1)
                val nextStage = page.stages.getOrNull(cardIdx + 1)
                if (// Case 1: The card is standalone and not melted with any scroll stage.
                    prevStage == null && nextStage == null ||
                    // Case 2: The card is melted on both sides, and the scrolls on both sides must neither both shrink
                    //         nor both expand.
                    prevStage != null && nextStage != null &&
                    (prevStage !in shrinkGroups || nextStage !in shrinkGroups) &&
                    (prevStage !in expandGroups || nextStage !in expandGroups)
                ) {
                    frozenCards.add(card)
                    stageImages.getValue(card).apply { height = height.resolve().toY() }
                }
            }
        }

    // Now comes the real deal: for the groups which shrink and for those who expand separately, we collectively match
    // the desired runtime.
    val img = HashMap(stageImages)
    var shortfall = 0
    shortfall += matchRuntimeInOneDir(stageLayouts, prevStages, nextStages, img, HashSet(shrinkGroups), frozenCards)
    shortfall += matchRuntimeInOneDir(stageLayouts, prevStages, nextStages, img, HashSet(expandGroups), frozenCards)
    return Pair(img, shortfall)
}

// Note: "direction" refers to either shrinking or expanding.
private fun matchRuntimeInOneDir(
    stageLayouts: Map<Stage, StageLayout>,
    prevStages: Map<Stage, Stage>,
    nextStages: Map<Stage, Stage>,
    // Manipulated collections:
    stageImages: MutableMap<Stage, DeferredImage>,
    groups: MutableSet<RuntimeGroup>,
    frozenCards: MutableSet<Stage>
): Int {
    fun prevCard(scroll: Stage) = prevStages[scroll]?.let { prev -> if (prev.style.behavior == CARD) prev else null }
    fun nextCard(scroll: Stage) = nextStages[scroll]?.let { next -> if (next.style.behavior == CARD) next else null }

    // If the given card has not been frozen yet, this function first scales its vertical gaps and then freezes it
    // similar to before, i.e., makes the height of the card's image rigid.
    fun tryFreezeCard(card: Stage, elasticScaling: Double) {
        if (card !in frozenCards) {
            frozenCards.add(card)
            stageImages[card] = stageImages.getValue(card).copy(elasticScaling = elasticScaling)
                .apply { height = height.resolve().toY() }
        }
    }

    var shortfall = 0
    // Until all runtime groups have been matched...
    while (groups.isNotEmpty()) {
        // Find the group which needs the most modest elastic scaling to match its desired runtime. Then apply that
        // scaling to the group and all unfrozen adjacent cards (which are consequently frozen). By iteratively
        // advancing to the more and more extreme elastic scaling, we fulfill the policy that each card should be
        // scaled in the same way as the more modest of its two adjacent scroll stages.
        var modestElasticScaling = Double.NaN
        var modestGroup: RuntimeGroup? = null
        var modestDiscreteFrames = -1
        for (group in groups) {
            // Here, we do not store a stretchable length in a Y object, but instead a stretchable number of frames.
            // For each continuous and uninterrupted stretch of adjacent scroll stages, find the number of frames needed
            // by that stretch. Be aware that we assume that group.stages contains the stages in the same order as they
            // appear in the credits.
            val stretchFrames = mutableListOf<Y>()
            for (scroll in group.stages) {
                val prevStage = prevStages[scroll]
                // If the previous stretch ended, start a new one.
                if (prevStage == null || prevStage.style.behavior != SCROLL || prevStage !in group.stages)
                    stretchFrames.add(0.0.toY())
                // The number of frames needed for this scroll stage is the vertical scrolled distance divided by the
                // scrolling speed. Apart from space owned by the scroll stage itself, the distance includes half the
                // space of adjacent cards. Although the precomputed scrollStartY and scrollStopY values could already
                // provide access to this distance, we need to account for the dynamically frozen card heights. As such,
                // we manually collect the scrolled distance here.
                var scrolledDist = (stageLayouts.getValue(scroll).info as DrawnStageInfo.Scroll).ownedScrollHeight
                prevCard(scroll)?.let { prevCard -> scrolledDist += stageImages.getValue(prevCard).height / 2.0 }
                nextCard(scroll)?.let { nextCard -> scrolledDist += stageImages.getValue(nextCard).height / 2.0 }
                var frames = stretchFrames.last() + scrolledDist / scroll.style.scrollPxPerFrame
                // The scroll frames we just computed do not contain the frame at DrawnStageInfo.Scroll.scrollStopY.
                // For example, if there is a scroll height "scrollStopY - scrollStartY" of 10 pixels, and we scroll
                // 2 pixels per frame, the result is 5 frames (at the pixel offsets 0, 2, 4, 6, 8): the first frame at
                // the top is included, but the last frame at the bottom is not.
                // However, if the stage is not preceded by another scroll stage, but instead either by void or by a
                // card stage, we need to also drop the frame at scrollStartY. As a result, the scroll now contains only
                // "moving" frames along the continuous stretch of scroll stages, but not the still frames at the
                // stretch's start and end.
                if (prevStage == null || prevStage.style.behavior != SCROLL)
                    frames -= 1.0
                stretchFrames[stretchFrames.lastIndex] = frames
            }

            // Find the elastic scaling which, when applied, makes the runtime group attain its desired runtime.
            var elasticScaling = stretchFrames.reduce(Y::plus).deresolve(group.runtimeFrames.toDouble())

            // The above operation is only correct if there is only one continuous stretch of scroll stages in the
            // runtime group. If there are however two or more stretches, the total number of frames can no longer be
            // found by just adding up the fractional frames of all stretches. Instead, before the addition, there has
            // to be a ceiling operation applied to each stretch individually to discretize the stretch's frames. This
            // ceiling operation would turn the Y curve that models a stretch's frames into a stepwise function. All
            // stepwise functions would then be added together to an overall stepwise function. As this is difficult to
            // implement explicitly, we instead go another route.
            // We first estimate the elastic scaling using the regular deresolve() function above. Then, we find the
            // actual number of discrete frames under that elastic scaling using the ceiling procedure outlined above.
            // If it doesn't match the desired frames, we take steps up or down the outline overall stepwise function
            // until the desired frames are matched.
            // Notice that due to the stepwise nature of the overall function, there are some very special cases in
            // which the desired frames can't be attained exactly; in those cases, we just stop when we have surpassed
            // the desired frames.
            var discreteFrames = stretchFrames.sumOf { frames -> safeCeil(frames.resolve(elasticScaling)) }
            if (stretchFrames.size > 1) {
                // Find whether we need to increase or decrease the elastic scaling.
                val up = discreteFrames < group.runtimeFrames
                var ctr = 0  // failsafe
                while (
                    ctr++ < 100 &&
                    // Stop if we try to further decrease an already 0 elastic scaling.
                    (up || elasticScaling != 0.0) &&
                    // Stop as soon as the desired runtime is attained (or in some rare cases overshot).
                    if (up) discreteFrames < group.runtimeFrames else discreteFrames > group.runtimeFrames
                ) {
                    // Take one step up or down the overall stepwise function. We do this by adjusting the elastic
                    // scaling such that just one of the stretch's stepwise functions (which make up the overall
                    // function) takes a single step.
                    elasticScaling = when (up) {
                        true -> stretchFrames.minOf { it.deresolve(floor(it.resolve(elasticScaling) + 1.0)) }
                        false -> stretchFrames.maxOf { it.deresolve(ceil(it.resolve(elasticScaling) - 1.0)) }
                    }
                    discreteFrames = stretchFrames.sumOf { frames -> safeCeil(frames.resolve(elasticScaling)) }
                }
            }

            if (modestElasticScaling.isNaN() || abs(elasticScaling - 1.0) < abs(modestElasticScaling - 1.0)) {
                modestElasticScaling = elasticScaling
                modestGroup = group
                modestDiscreteFrames = discreteFrames
            }
        }

        // As stated before, we now apply the most modest elastic scaling to the corresponding group and its adjacent
        // unfrozen card stages, freeze those cards, and take the group out of the equation as it's now matched.
        groups.remove(modestGroup)
        for (scroll in modestGroup!!.stages) {
            stageImages[scroll] = stageImages.getValue(scroll).copy(elasticScaling = modestElasticScaling)
            prevCard(scroll)?.let { prevCard -> tryFreezeCard(prevCard, modestElasticScaling) }
            nextCard(scroll)?.let { nextCard -> tryFreezeCard(nextCard, modestElasticScaling) }
        }

        // Record by how many frames the actually attained runtime diverges from the group's desired one. If there is a
        // divergence, it either stems from the very rare impossibilities outline above, or more likely from a group
        // which does not have enough vertical gaps to expend in order to fully attain a very low desired runtime.
        shortfall += modestGroup.runtimeFrames - modestDiscreteFrames
    }

    return shortfall
}


private fun drawPage(
    global: Global,
    stageImages: Map<Stage, DeferredImage>,
    stageLayouts: Map<Stage, StageLayout>,
    pageTopStages: Set<Stage>,
    pageBotStages: Set<Stage>,
    page: Page,
    pageImageHeight: Y
): DeferredImage {
    val pageImage = DeferredImage(global.widthPx.toDouble(), pageImageHeight)

    val framesMargin = global.widthPx / 100.0
    fun drawFrames(frames: Int, y: Y) {
        val str = formatTimecode(global.fps, global.timecodeFormat, frames)
        // Note: Obtaining a Font object for the bold monospaced font is a bit convoluted because the final object must
        // not contain a font weight attribute, or else the FormattedString would complain.
        val fontName = UIManager.getFont("monospaced.font").deriveFont(Font.BOLD).getFontName(Locale.ROOT)
        val font = FormattedString.Font(STAGE_GUIDE_COLOR, Font(fontName, Font.PLAIN, 1), global.widthPx / 80.0)
        val fmtStr = FormattedString.Builder(Locale.ROOT).apply {
            append(str, FormattedString.Attribute(font, emptyList(), null))
        }.build()
        pageImage.drawString(
            fmtStr, x = global.widthPx - fmtStr.width - framesMargin, y,
            foregroundLayer = GUIDES, backgroundLayer = GUIDES /* irrelevant, since our string has no background */
        )
    }

    for ((stageIdx, stage) in page.stages.withIndex()) {
        val stageImage = stageImages.getValue(stage)
        val stageLayout = stageLayouts.getValue(stage)
        if (stageLayout.info is DrawnStageInfo.Card) {
            val cardTopY = stageLayout.info.middleY - global.heightPx / 2.0
            val cardBotY = stageLayout.info.middleY + global.heightPx / 2.0
            // Draw guides that show the boundaries of the screen as they will be when this card will be shown.
            // Note: We subtract 1 from the width and height; if we don't, the right and lower lines of the
            // rectangle are often rendered only partially or not at all.
            pageImage.drawRect(
                STAGE_GUIDE_COLOR, 0.0, cardTopY, global.widthPx - 1.0, (global.heightPx - 1.0).toY(),
                layer = GUIDES
            )
            // If the card is an intermediate stage, also draw arrows that indicate that the card is intermediate.
            if (stageIdx != 0)
                pageImage.drawMeltedCardArrowGuide(global, cardTopY)
            if (stageIdx != page.stages.lastIndex)
                pageImage.drawMeltedCardArrowGuide(global, cardBotY)
            drawFrames(getCardFrames(pageTopStages, pageBotStages, stage), y = cardTopY + framesMargin)
        } else if (stageLayout.info is DrawnStageInfo.Scroll) {
            val y = when (val prevStageInfo = page.stages.getOrNull(stageIdx - 1)?.let(stageLayouts::getValue)?.info) {
                is DrawnStageInfo.Card -> prevStageInfo.middleY + global.heightPx / 2.0 + framesMargin
                is DrawnStageInfo.Scroll -> stageLayout.y
                null -> stageLayout.y + framesMargin
            }
            drawFrames(stageLayout.info.frames, y)
        }
        // Actually draw the stage image onto the page image.
        pageImage.drawDeferredImage(stageImage, 0.0, stageLayout.y)
    }

    // Draw the grounding of the page image.
    pageImage.drawRect(
        global.grounding, 0.0, 0.0.toY(), pageImage.width, pageImage.height, fill = true, layer = GROUNDING
    )

    return pageImage
}


private fun DeferredImage.drawMeltedCardArrowGuide(global: Global, y: Y) {
    val s = global.widthPx / 100.0
    val triangle = Path2D.Double().apply {
        moveTo(-0.5 * s, -0.45 * s)
        lineTo(0.5 * s, -0.45 * s)
        lineTo(0.0 * s, 0.55 * s)
        closePath()
    }
    drawShape(
        STAGE_GUIDE_COLOR, triangle, global.widthPx / 2.0, y, fill = true, layer = GUIDES
    )
}


private fun getCardFrames(
    pageTopStages: Set<Stage>,
    pageBotStages: Set<Stage>,
    stage: Stage
): Int {
    val style = stage.style
    var frames = style.cardDurationFrames
    if (stage in pageTopStages)
        frames += style.cardFadeInFrames
    if (stage in pageBotStages)
        frames += style.cardFadeOutFrames
    return frames
}


/**
 * If [x] is just slightly larger than an integer, we want it to be handled as if it was that integer to compensate for
 * floating point inaccuracy. That is why we have the slight offset in there.
 */
private fun safeCeil(x: Double): Int = ceil(x - 0.001).toInt()
