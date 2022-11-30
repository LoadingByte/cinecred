package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GROUNDING
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.common.FormattedString
import com.loadingbyte.cinecred.common.Y
import com.loadingbyte.cinecred.common.Y.Companion.toElasticY
import com.loadingbyte.cinecred.common.Y.Companion.toY
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.PageBehavior.CARD
import com.loadingbyte.cinecred.project.PageBehavior.SCROLL
import kotlinx.collections.immutable.toPersistentList
import java.awt.Font
import java.awt.geom.Path2D
import java.util.*
import javax.swing.UIManager
import kotlin.math.ceil


private class StageLayout(val y: Y, val info: DrawnStageInfo)
private class DrawnSpine(val defImage: DeferredImage, val spineXInImage: Float)


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
    val stageImages = HashMap<Stage, DeferredImage>()
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

        // Adjust the stages collected in runtime groups.
        for (runtimeGroup in runtimeGroups)
            matchRuntime(
                pages, stageImages, prelimStageLayouts, pageTopStages, pageBotStages,
                desiredFrames = runtimeGroup.runtimeFrames,
                activeStages = runtimeGroup.stages, passiveStages = emptyList()
            )
        // If requested, adjust all remaining stages to best achieve the desired overall runtime of the whole sequence.
        if (global.runtimeFrames.isActive) {
            val pauseFrames = pages.dropLast(1).sumOf { it.stages.last().style.afterwardSlugFrames }
            val runtimeStages = runtimeGroups.flatMap(RuntimeGroup::stages)
            val allStages = stageImages.keys
            matchRuntime(
                pages, stageImages, prelimStageLayouts, pageTopStages, pageBotStages,
                // Just subtract the frames in between pages from the number of desired frames.
                desiredFrames = global.runtimeFrames.value - pauseFrames,
                activeStages = allStages - runtimeStages, passiveStages = runtimeStages
            )
        }
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
    val stageImage = DeferredImage(width = global.widthPx.toFloat())
    var y = 0f.toY()

    // If this stage is a scroll stage is preceded by a card stage, add the vertical gap behind the card stage to the
    // front of this stage because card stage images are not allowed to have extremal vertical gaps baked into them.
    if (stage.style.behavior != CARD && prevStage != null && prevStage.style.behavior == CARD)
        y += prevStage.vGapAfterPx.toY()

    for ((segmentIdx, segment) in stage.segments.withIndex()) {
        var maxHeight = 0f.toY()
        for (spine in segment.spines) {
            val drawnSpine = drawSpine(drawnBlocks, spine)
            val spineXInPageImage = global.widthPx / 2f + spine.posOffsetPx
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
        y += when (nextStage.style.behavior) {
            CARD -> stage.vGapAfterPx.toY()
            SCROLL -> stage.vGapAfterPx.toElasticY()
        }

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
    var y = 0f.toY()
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
    spineImage.drawLine(SPINE_GUIDE_COLOR, spineXInSpineImage, 0f.toY(), spineXInSpineImage, y, layer = GUIDES)

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
    var pageImageHeight = 0f.toY()
    var y = 0f.toY()
    for ((stageIdx, stage) in page.stages.withIndex()) {
        var stageHeight = stageImages.getValue(stage).height
        // Special handling for card stages...
        if (stage.style.behavior == CARD) {
            // Card stages are rigid and not elastic.
            val resolvedStageHeight = stageHeight.resolve()
            stageHeight = resolvedStageHeight.toY()
            // The amount of padding that needs to be added above and below the card's stage image such that
            // it is centered on the screen.
            val cardPaddingHeight = (global.heightPx - resolvedStageHeight) / 2f
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
    //   - If it's a scroll stage, the y coordinates in the overall page image that are at the center of the screen
    //     when the scrolling of this stage starts respectively stops. Also, the number of frames that make up the
    //     scroll, and a fraction of a frame that should be skipped prior to the beginning of the scroll.
    var nextScrollInitAdvance = Float.NaN
    val stageInfo = page.stages.mapIndexed { stageIdx, stage ->
        val (topY, botY) = stageImageBounds[stageIdx]
        when (stage.style.behavior) {
            CARD -> DrawnStageInfo.Card((topY + botY) / 2f)
            SCROLL -> {
                val prevStageBehavior = page.stages.getOrNull(stageIdx - 1)?.run { style.behavior }
                val nextStageBehavior = page.stages.getOrNull(stageIdx + 1)?.run { style.behavior }
                // Find the scroll start and end y coordinates.
                val scrollStartY = when (prevStageBehavior) {
                    CARD -> stageImageBounds[stageIdx - 1].let { (aTopY, aBotY) -> (aTopY + aBotY) / 2f }
                    SCROLL -> stageImageBounds[stageIdx - 1].let { (_, aBotY) -> (aBotY + topY) / 2f }
                    null -> topY /* will always be 0 */ - global.heightPx / 2f
                }
                val scrollStopY = when (nextStageBehavior) {
                    CARD -> stageImageBounds[stageIdx + 1].let { (bTopY, bBotY) -> (bTopY + bBotY) / 2f }
                    SCROLL -> stageImageBounds[stageIdx + 1].let { (bTopY, _) -> (botY + bTopY) / 2f }
                    null -> botY + global.heightPx / 2f
                }
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
                val initAdvance = if (prevStageBehavior == SCROLL) nextScrollInitAdvance else 1f
                val fracFrames =
                    (scrollStopY.resolve() - scrollStartY.resolve()) / stage.style.scrollPxPerFrame - initAdvance
                // If fracFrames is just slightly larger than an integer, we want it to be handled as if it was that
                // integer to compensate for floating point inaccuracy. That is why we have the -0.01 in there.
                val frames = ceil(fracFrames - 0.01f).toInt()
                nextScrollInitAdvance = (-fracFrames).mod(1f)  // Using mod() ensures that the result is positive.
                // Construct the info object.
                DrawnStageInfo.Scroll(scrollStartY, scrollStopY, frames, initAdvance)
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
    stageImages: MutableMap<Stage, DeferredImage>,
    prelimStageLayouts: Map<Stage, StageLayout>,
    pageTopStages: Set<Stage>,
    pageBotStages: Set<Stage>,
    desiredFrames: Int,
    activeStages: Collection<Stage>,
    passiveStages: Collection<Stage>
) {
    // Here, we do not store a stretchable length in a Y object, but instead a stretchable number of frames.
    var frames = 0f.toY()

    for (page in pages)
        for ((stageIdx, stage) in page.stages.withIndex()) {
            val prevStage = page.stages.getOrNull(stageIdx - 1)
            // activate stages -> rigid & elastic  /  passive stages -> rigid only
            if (stage in activeStages)
                frames += getStageFrames(prelimStageLayouts, pageTopStages, pageBotStages, stage, prevStage)
            else if (stage in passiveStages)
                frames += getStageFrames(prelimStageLayouts, pageTopStages, pageBotStages, stage, prevStage).resolve()
        }

    val elasticScaling = frames.deresolve(desiredFrames.toFloat())

    for (stage in activeStages)
        if (stage.style.behavior == SCROLL)
            stageImages[stage] = stageImages.getValue(stage).copy(elasticScaling = elasticScaling)
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
    val pageImage = DeferredImage(global.widthPx.toFloat(), pageImageHeight)

    val framesMargin = global.widthPx / 100f
    fun drawFrames(frames: Int, y: Y) {
        val str = formatTimecode(global.fps, global.timecodeFormat, frames)
        // Note: Obtaining a Font object for the bold monospaced font is a bit convoluted because the final object must
        // not contain a font weight attribute, or else the FormattedString would complain.
        val fontName = UIManager.getFont("monospaced.font").deriveFont(Font.BOLD).getFontName(Locale.ROOT)
        val font = FormattedString.Font(STAGE_GUIDE_COLOR, Font(fontName, Font.PLAIN, 1), global.widthPx / 80f)
        val fmtStr = FormattedString.Builder(Locale.ROOT).apply {
            append(str, FormattedString.Attribute(font, emptySet(), null))
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
            val cardTopY = stageLayout.info.middleY - global.heightPx / 2f
            val cardBotY = stageLayout.info.middleY + global.heightPx / 2f
            // Draw guides that show the boundaries of the screen as they will be when this card will be shown.
            // Note: We subtract 1 from the width and height; if we don't, the right and lower lines of the
            // rectangle are often rendered only partially or not at all.
            pageImage.drawRect(
                STAGE_GUIDE_COLOR, 0f, cardTopY, global.widthPx - 1f, (global.heightPx - 1f).toY(),
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
                is DrawnStageInfo.Card -> prevStageInfo.middleY + global.heightPx / 2f + framesMargin
                is DrawnStageInfo.Scroll -> stageLayout.y
                null -> stageLayout.y + framesMargin
            }
            drawFrames(stageLayout.info.frames, y)
        }
        // Actually draw the stage image onto the page image.
        pageImage.drawDeferredImage(stageImage, 0f, stageLayout.y)
    }

    // Draw the grounding of the page image.
    pageImage.drawRect(
        global.grounding, 0f, 0f.toY(), pageImage.width, pageImage.height, fill = true, layer = GROUNDING
    )

    return pageImage
}


private fun DeferredImage.drawMeltedCardArrowGuide(global: Global, y: Y) {
    val s = global.widthPx / 100f
    val triangle = Path2D.Float().apply {
        moveTo(-0.5f * s, -0.45f * s)
        lineTo(0.5f * s, -0.45f * s)
        lineTo(0f * s, 0.55f * s)
        closePath()
    }
    drawShape(
        STAGE_GUIDE_COLOR, triangle, global.widthPx / 2f, y, fill = true, layer = GUIDES
    )
}


private fun getStageFrames(
    stageLayouts: Map<Stage, StageLayout>,
    pageTopStages: Set<Stage>,
    pageBotStages: Set<Stage>,
    stage: Stage,
    prevStage: Stage?
): Y =
    when (stage.style.behavior) {
        CARD -> getCardFrames(pageTopStages, pageBotStages, stage).toFloat().toY()
        SCROLL -> getScrollFrames(stageLayouts, stage, prevStage)
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


private fun getScrollFrames(
    stageLayouts: Map<Stage, StageLayout>,
    stage: Stage,
    prevStage: Stage?
): Y {
    val stageInfo = stageLayouts.getValue(stage).info as DrawnStageInfo.Scroll
    // Notice that the scroll frames do not contain the frame at scrollStopY. For example, if there is a scroll height
    // "scrollStopY - scrollStartY" of 10 pixels, and we scroll 2 pixels per frame, the first frame at the top is
    // included, but the last frame at the bottom is not.
    var frames = (stageInfo.scrollStopY - stageInfo.scrollStartY) / stage.style.scrollPxPerFrame
    // If the stage is not preceded by another scroll stage, but either by void or by a card stage, also drop the frame
    // at scrollStartY. As a result, the scroll now contains only "moving" frames along the continuous stretch of scroll
    // stages, but not the still frames at its start and end.
    if (prevStage == null || prevStage.style.behavior != SCROLL)
        frames -= 1f
    return frames
}
