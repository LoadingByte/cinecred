package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.FormattedString
import com.loadingbyte.cinecred.imaging.Y
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


fun drawPages(project: Project, credits: Credits): List<DrawnPage> {
    val styling = project.styling
    val global = styling.global

    require(credits in project.credits)
    val pages = credits.pages
    val runtimeGroups = credits.runtimeGroups

    // First generate a body and then a block image for each block. This has to be done for all blocks at the same time
    // because heads, bodies, and tails can harmonize various widths and heights between them.
    val blocks = buildList {
        for (page in pages)
            for (stage in page.stages)
                for (compound in stage.compounds)
                    for (spine in compound.spines)
                        addAll(spine.blocks)
    }
    val drawnBodies = drawBodies(styling, blocks)
    val drawnBlocks = drawBlocks(styling, drawnBodies, blocks)

    // Generate a stage image for each stage. These stage images already contain the vertical gaps between the stages.
    var drawnStages: MutableMap<Stage, DrawnStage> = HashMap()
    for (page in pages)
        for ((stageIdx, stage) in page.stages.withIndex()) {
            val prevStage = page.stages.getOrNull(stageIdx - 1)
            val nextStage = page.stages.getOrNull(stageIdx + 1)
            drawnStages[stage] = drawStage(global.resolution, drawnBlocks, stage, prevStage, nextStage)
        }

    // If requested, adjust some vertical gaps to best match a specified runtime.
    if (runtimeGroups.isNotEmpty() || global.runtimeFrames.isActive) {
        // Run a first layout pass to determine how many frames each scrolling stage will scroll for.
        val prelimStageLayouts = HashMap<Stage, StageLayout>()
        for (page in pages)
            prelimStageLayouts.putAll(layoutStages(global.resolution, drawnStages, page).second)
        // Use that information to scale the vertical gaps.
        drawnStages = matchRuntime(pages, drawnStages, prelimStageLayouts, global.runtimeFrames, runtimeGroups)
    }

    // Finally, do the real layout pass with potentially changed stage images and combine the stage images
    // to page images.
    return pages.map { page ->
        val (pageImageHeight, stageLayouts) = layoutStages(global.resolution, drawnStages, page)
        val pageImage = drawPage(global, drawnStages, stageLayouts, page, pageImageHeight)
        DrawnPage(page, pageImage, stageLayouts.values.map(StageLayout::info).toPersistentList())
    }
}


private fun layoutStages(
    resolution: Resolution,
    drawnStages: Map<Stage, DrawnStage>,
    page: Page
): Pair<Y, Map<Stage, StageLayout>> {
    data class StageRange(val top: Y, val bot: Y)

    // Determine each stage's top and bottom y coordinates in the future page image.
    // Also find the height of the whole future page image if that is given explicitly.
    val stageRanges = mutableListOf<StageRange>()
    var pageImageHeight = 0.0.toY()
    var y = 0.0.toY()
    for ((stageIdx, stage) in page.stages.withIndex()) {
        val drawnStage = drawnStages.getValue(stage)
        val stageHeight = drawnStage.defImage.height
        // Special handling for card stages:
        // If this card stage is the first and/or the last stage, make sure that there is extra padding below and above
        // the card stage such that its content is vertically positioned as requested by the user.
        // Notice that as scrolling from/to a card starts/ends at the card's center, the padding is irrelevant for
        // runtime matching and can hence be rigid.
        if (stage.style.behavior == CARD) {
            val middleY = drawnStage.middleYInImage!!.resolve()
            if (stageIdx == 0)
                y += resolution.heightPx / 2.0 - middleY
            if (stageIdx == page.stages.lastIndex)
                pageImageHeight += resolution.heightPx / 2.0 - (stageHeight.resolve() - middleY)
        }
        // Record the stage content's tightest top and bottom y coordinates in the overall page image.
        stageRanges.add(StageRange(y, y + stageHeight))
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
    //       - The number of frames that make up the scroll.
    val stageInfo = page.stages.mapIndexed { stageIdx, stage ->
        val (topY, botY) = stageRanges[stageIdx]
        when (stage.style.behavior) {
            CARD -> DrawnStageInfo.Card(topY + drawnStages.getValue(stage).middleYInImage!!)
            SCROLL -> {
                val prevStage = page.stages.getOrNull(stageIdx - 1)
                val nextStage = page.stages.getOrNull(stageIdx + 1)
                val prevStageBehavior = prevStage?.style?.behavior
                val nextStageBehavior = nextStage?.style?.behavior
                // Find the scroll start and end y coordinates.
                val scrollStartY = when (prevStageBehavior) {
                    CARD -> stageRanges[stageIdx - 1].top + drawnStages.getValue(prevStage).middleYInImage!!
                    null -> topY - resolution.heightPx / 2.0
                    SCROLL -> throw IllegalStateException("Scroll stages can't be melted back to back.")
                }
                val scrollStopY = when (nextStageBehavior) {
                    CARD -> stageRanges[stageIdx + 1].top + drawnStages.getValue(nextStage).middleYInImage!!
                    null -> botY + resolution.heightPx / 2.0
                    SCROLL -> throw IllegalStateException("Scroll stages can't be melted back to back.")
                }
                // Find the portion of the scrolled height which really belongs to the scroll stage itself. If you are
                // confused, recall that scroll stages can scroll into melted card stages.
                val ownedScrollStartY = if (prevStageBehavior == CARD) topY else scrollStartY
                val ownedScrollStopY = if (nextStageBehavior == CARD) botY else scrollStopY
                val ownedScrollHeight = ownedScrollStopY - ownedScrollStartY
                // Find the number of frames required for the scroll. The scroll should only contain "moving" frames,
                // since the static ones (blank screen or still card) are already fully covered by the frame gaps
                // between pages or the card stages.
                // For example, if there is a scroll height "scrollStopY - scrollStartY" of exactly 10 pixels, and we
                // scroll 2 pixels per frame, dividing the first by the second would yield 5 frames. However, we only
                // want 4 frames (at the pixel offsets 2, 4, 6, 8), as the frames at the offsets 0 and 10 are not
                // "moving" frames. So we subtract 1 here.
                // In accordance with this, DeferredVideo is programmed to start drawing at the pixel offset 2, and the
                // matchRuntimeInOneDir() further down in this file performs the same -1.
                val fracFrames = (scrollStopY.resolve() - scrollStartY.resolve()) / stage.style.scrollPxPerFrame - 1.0
                // If fracFrames is just slightly larger than an integer, we want it to be handled as if it was that
                // integer to compensate for floating point inaccuracy. That is why we have the -0.01 in there.
                val frames = safeCeil(fracFrames)
                // Construct the info object.
                DrawnStageInfo.Scroll(scrollStartY, scrollStopY, ownedScrollHeight, frames)
            }
        }
    }

    val stageLayouts = page.stages.indices.associate { stageIdx ->
        page.stages[stageIdx] to StageLayout(stageRanges[stageIdx].top, stageInfo[stageIdx])
    }
    return Pair(pageImageHeight, stageLayouts)
}


private fun matchRuntime(
    pages: List<Page>,
    drawnStages: Map<Stage, DrawnStage>,
    stageLayouts: Map<Stage, StageLayout>,
    globalDesiredFrames: Opt<Int>,
    runtimeGroups: List<RuntimeGroup>
): MutableMap<Stage, DrawnStage> {
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

    // This function removes the card stages from a runtime group and subtracts their fixed runtime from the
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
                CARD -> desiredScrollFrames -= stage.cardRuntimeFrames
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
                pages.subList(0, pages.size - 1).sumOf(Page::gapAfterFrames)
        globalGroup = addGroup(remStages, globalGroupFrames)
    }

    // Run the runtime matching.
    val (adjustedDrawnStages, shortfall) =
        matchRuntime(pages, drawnStages, stageLayouts, prevStages, nextStages, shrinkGroups, expandGroups)
    // If there is no global runtime adjustment happening (either because it is deactivated or because there are no
    // scroll stages remaining), or if all runtimes were matched perfectly, return the result
    if (globalGroup == null || shortfall == 0)
        return adjustedDrawnStages

    // If we are here, shortfall is non-zero, which means that some stages did not exactly attain their desired runtime.
    // Apart from some very rare situations where an exact match is impossible, this means that some runtime groups have
    // run out of vertical gaps to expense for decreasing their runtime.
    // However, the global runtime group expects all other runtime groups to have exact matches. Thus, we need to
    // compensate for the other groups' shortfall in the global group's desired runtime.
    shrinkGroups.remove(globalGroup)
    expandGroups.remove(globalGroup)
    addGroup(remStages, globalGroupFrames + shortfall)
    return matchRuntime(pages, drawnStages, stageLayouts, prevStages, nextStages, shrinkGroups, expandGroups).first
}


private fun matchRuntime(
    pages: List<Page>,
    drawnStages: Map<Stage, DrawnStage>,
    stageLayouts: Map<Stage, StageLayout>,
    prevStages: Map<Stage, Stage>,
    nextStages: Map<Stage, Stage>,
    shrinkGroups: Set<RuntimeGroup>,
    expandGroups: Set<RuntimeGroup>
): Pair<MutableMap<Stage, DrawnStage>, Int> {
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
                    drawnStages.getValue(card).defImage.apply { height = height.resolve().toY() }
                }
            }
        }

    // Now comes the real deal: for the groups which shrink and for those who expand separately, we collectively match
    // the desired runtime.
    val img = HashMap(drawnStages)
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
    drawnStages: MutableMap<Stage, DrawnStage>,
    groups: MutableSet<RuntimeGroup>,
    frozenCards: MutableSet<Stage>
): Int {
    // If the given card has not been frozen yet, this function first scales its vertical gaps and then freezes it
    // similar to before, i.e., makes the height of the card's image rigid.
    fun tryFreezeCard(card: Stage, elasticScaling: Double) {
        if (card !in frozenCards) {
            frozenCards.add(card)
            val drawnCard = drawnStages.getValue(card)
            drawnStages[card] = DrawnStage(
                drawnCard.defImage.copy(elasticScaling = elasticScaling).apply { height = height.resolve().toY() },
                drawnCard.middleYInImage!!.resolve(elasticScaling).toY()
            )
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
            // For each scroll stage, find the number of frames needed by that stage.
            val scrollFrames = group.stages.map { scroll ->
                // The number of frames needed for this scroll stage is the vertical scrolled distance divided by the
                // scrolling speed. Apart from space owned by the scroll stage itself, the distance includes additional
                // space of adjacent cards. Although the precomputed scrollStartY and scrollStopY values could already
                // provide access to this distance, we need to account for the dynamically frozen card heights. As such,
                // we manually collect the scrolled distance here.
                var scrolledDist = (stageLayouts.getValue(scroll).info as DrawnStageInfo.Scroll).ownedScrollHeight
                prevStages[scroll]?.let { prevCard ->
                    scrolledDist += drawnStages.getValue(prevCard).run { defImage.height - middleYInImage!! }
                }
                nextStages[scroll]?.let { nextCard ->
                    scrolledDist += drawnStages.getValue(nextCard).middleYInImage!!
                }
                // See the computation of fracFrames in the layoutStages() function in this file for the reason why we
                // subtract 1 here.
                scrolledDist / scroll.style.scrollPxPerFrame - 1.0
            }

            // Find the elastic scaling which, when applied, makes the runtime group attain its desired runtime.
            var elasticScaling = scrollFrames.reduce(Y::plus).deresolve(group.runtimeFrames.toDouble())

            // The above operation is only correct if there is only one scroll stage in the runtime group. If there
            // are however two or more scroll stages, the total number of frames can no longer be found by just
            // adding up the fractional frames of all scroll stages. Instead, before the summation, there has to be
            // a ceiling operation applied to each scroll stage individually to discretize the stage's frames. This
            // ceiling operation would turn the Y curve that models a stage's frames into a stepwise function. All
            // stepwise functions would then be added together to an overall stepwise function. As this is difficult to
            // implement explicitly, we instead go another route.
            // We first estimate the elastic scaling using the regular deresolve() function above. Then, we find the
            // actual number of discrete frames under that elastic scaling using the ceiling procedure outlined above.
            // If it doesn't match the desired frames, we take steps up or down the outlined overall stepwise function
            // until the desired frames are matched.
            // Notice that due to the stepwise nature of the overall function, there are some very special cases in
            // which the desired frames can't be attained exactly; in those cases, we just stop when we have surpassed
            // the desired frames.
            var discreteFrames = scrollFrames.sumOf { frames -> safeCeil(frames.resolve(elasticScaling)) }
            if (scrollFrames.size > 1) {
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
                    // scaling such that just one of the scroll stages' stepwise functions (which make up the overall
                    // function) takes a single step.
                    elasticScaling = when (up) {
                        true -> scrollFrames.minOf { it.deresolve(floor(it.resolve(elasticScaling) + 1.0)) }
                        false -> scrollFrames.maxOf { it.deresolve(ceil(it.resolve(elasticScaling) - 1.0)) }
                    }
                    discreteFrames = scrollFrames.sumOf { frames -> safeCeil(frames.resolve(elasticScaling)) }
                }
            }

            if (modestElasticScaling.isNaN() || abs(ln(elasticScaling)) < abs(ln(modestElasticScaling))) {
                modestElasticScaling = elasticScaling
                modestGroup = group
                modestDiscreteFrames = discreteFrames
            }
        }

        // As stated before, we now apply the most modest elastic scaling to the corresponding group and its adjacent
        // unfrozen card stages, freeze those cards, and take the group out of the equation as it's now matched.
        groups.remove(modestGroup)
        for (scroll in modestGroup!!.stages) {
            val drawnScroll = drawnStages.getValue(scroll)
            drawnStages[scroll] = DrawnStage(drawnScroll.defImage.copy(elasticScaling = modestElasticScaling), null)
            prevStages[scroll]?.let { prevCard -> tryFreezeCard(prevCard, modestElasticScaling) }
            nextStages[scroll]?.let { nextCard -> tryFreezeCard(nextCard, modestElasticScaling) }
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
    drawnStages: Map<Stage, DrawnStage>,
    stageLayouts: Map<Stage, StageLayout>,
    page: Page,
    pageImageHeight: Y
): DeferredImage {
    val resolution = global.resolution
    val pageImage = DeferredImage(resolution.widthPx.toDouble(), pageImageHeight)

    val framesMargin = resolution.widthPx / 100.0
    fun drawFrames(frames: Int, y: Y) {
        val str = formatTimecode(global.fps, global.timecodeFormat, frames)
        // Note: Obtaining a Font object for the bold monospaced font is a bit convoluted because the final object must
        // not contain a font weight attribute, or else the FormattedString would complain.
        val fontName = UIManager.getFont("monospaced.font").deriveFont(Font.BOLD).getFontName(Locale.ROOT)
        val font = FormattedString.Font(Font(fontName, Font.PLAIN, 1), resolution.widthPx / 80.0)
        val coloring = FormattedString.Layer.Coloring.Plain(STAGE_GUIDE_COLOR)
        val layer = FormattedString.Layer(coloring, FormattedString.Layer.Shape.Text)
        val fmtStr = FormattedString.Builder(Locale.ROOT).apply {
            append(str, FormattedString.Attribute(font, FormattedString.Design(font, listOf(layer))))
        }.build()
        val x = resolution.widthPx - fmtStr.width - framesMargin
        val yBaseline = y + fmtStr.heightAboveBaseline
        pageImage.drawString(fmtStr, x, yBaseline, layer = GUIDES)
    }

    for ((stageIdx, stage) in page.stages.withIndex()) {
        val stageImage = drawnStages.getValue(stage).defImage
        val stageLayout = stageLayouts.getValue(stage)
        if (stageLayout.info is DrawnStageInfo.Card) {
            val cardTopY = stageLayout.info.middleY - resolution.heightPx / 2.0
            val cardBotY = stageLayout.info.middleY + resolution.heightPx / 2.0
            // Draw guides that show the boundaries of the screen as they will be when this card will be shown.
            // Note: We move each side in by 0.5; if we didn't, the rectangle would only render partially or not at all.
            pageImage.drawRect(
                STAGE_GUIDE_COLOR, 0.5, cardTopY + 0.5, resolution.widthPx - 1.0, (resolution.heightPx - 1.0).toY(),
                layer = GUIDES
            )
            // If the card is an intermediate stage, also draw arrows that indicate that the card is intermediate.
            if (stageIdx != 0)
                pageImage.drawMeltedCardArrowGuide(resolution, cardTopY)
            if (stageIdx != page.stages.lastIndex)
                pageImage.drawMeltedCardArrowGuide(resolution, cardBotY)
            drawFrames(stage.cardRuntimeFrames, y = cardTopY + framesMargin)
        } else if (stageLayout.info is DrawnStageInfo.Scroll) {
            val y = when (val prevStageInfo = page.stages.getOrNull(stageIdx - 1)?.let(stageLayouts::getValue)?.info) {
                is DrawnStageInfo.Card -> prevStageInfo.middleY + resolution.heightPx / 2.0 + framesMargin
                is DrawnStageInfo.Scroll -> stageLayout.y
                null -> stageLayout.y + framesMargin
            }
            drawFrames(stageLayout.info.frames, y)
        }
        // Actually draw the stage image onto the page image.
        pageImage.drawDeferredImage(stageImage, 0.0, stageLayout.y)
    }

    return pageImage
}


private fun DeferredImage.drawMeltedCardArrowGuide(resolution: Resolution, y: Y) {
    val s = resolution.widthPx / 100.0
    val triangle = Path2D.Double().apply {
        moveTo(-0.5 * s, -0.45 * s)
        lineTo(0.5 * s, -0.45 * s)
        lineTo(0.0 * s, 0.55 * s)
        closePath()
    }
    drawShape(STAGE_GUIDE_COLOR, triangle, resolution.widthPx / 2.0, y, fill = true, layer = GUIDES)
}


/**
 * If [x] is just slightly larger than an integer, we want it to be handled as if it was that integer to compensate for
 * floating point inaccuracy. That is why we have the slight offset in there.
 */
private fun safeCeil(x: Double): Int = ceil(x - 0.001).toInt()
