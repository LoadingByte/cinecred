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
import kotlinx.collections.immutable.toImmutableList
import java.awt.Font
import java.awt.geom.Path2D
import java.util.*
import javax.swing.UIManager


private class StageLayout(val y: Y, val info: DrawnStageInfo)


fun draw(project: Project): List<DrawnPage> {
    val global = project.styling.global
    val pages = project.pages
    val runtimeGroups = project.runtimeGroups

    val textCtx = makeTextCtx(project.styling.global.locale, project.styling.global.uppercaseExceptions, project.fonts)

    // Generate a stage image for each stage. These stage images already contain the vertical gaps between the stages.
    val stageImages = HashMap<Stage, DeferredImage>()
    for (page in pages)
        stageImages.putAll(drawStages(global, project.styling.letterStyles, textCtx, page))

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
                stageImages, prelimStageLayouts, pageTopStages, pageBotStages,
                desiredFrames = runtimeGroup.runtimeFrames,
                activeStages = runtimeGroup.stages, passiveStages = emptyList()
            )
        // If requested, adjust all remaining stages to best achieve the desired overall runtime of the whole sequence.
        if (global.runtimeFrames.isActive) {
            val pauseFrames = pages.dropLast(1).sumOf { it.stages.last().style.afterwardSlugFrames }
            val runtimeStages = runtimeGroups.flatMap(RuntimeGroup::stages)
            val allStages = stageImages.keys
            matchRuntime(
                stageImages, prelimStageLayouts, pageTopStages, pageBotStages,
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
        DrawnPage(pageImage, stageLayouts.values.map(StageLayout::info).toImmutableList())
    }
}


private fun drawStages(
    global: Global,
    letterStyles: List<LetterStyle>,
    textCtx: TextContext,
    page: Page
): Map<Stage, DeferredImage> {
    // Convert the aligning group lists to maps that map from block to group id.
    val alignBodyColsGroupIds = page.alignBodyColsGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()
    val alignHeadTailGroupIds = page.alignHeadTailGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()

    // Generate an image for each spine.
    // Also remember the x coordinate of the spine inside each generated image.
    val drawnSpines = page.stages
        .flatMap { stage -> stage.segments }.flatMap { segment -> segment.spines }
        .associateWith { spin -> drawSpine(letterStyles, textCtx, spin, alignBodyColsGroupIds, alignHeadTailGroupIds) }

    // For each stage, combine the spine images to a stage image.
    return page.stages.withIndex().associate { (stageIdx, stage) ->
        val prevStage = page.stages.getOrNull(stageIdx - 1)
        val nextStage = page.stages.getOrNull(stageIdx + 1)
        stage to drawStage(global, drawnSpines, stage, prevStage, nextStage)
    }
}


private fun drawStage(
    global: Global,
    drawnSpines: Map<Spine, DrawnSpine>,
    stage: Stage,
    prevStage: Stage?,
    nextStage: Stage?
): DeferredImage {
    val stageImage = DeferredImage(width = global.widthPx.toFloat())
    var y = 0f.toY()

    // If this stage is a scroll stage and is preceded by another scroll stage, add half the preceding vertical gap
    // as elastic space. If it is preceded by a card stage, add the full preceding vertical gap as rigid space
    // since card stage images are not allowed to have vertical gaps baked into them.
    if (prevStage != null && stage.style.behavior != PageBehavior.CARD)
        y += when (prevStage.style.behavior) {
            PageBehavior.CARD -> prevStage.vGapAfterPx.toY()
            PageBehavior.SCROLL -> (prevStage.vGapAfterPx / 2f).toElasticY()
        }

    for (segment in stage.segments) {
        var maxHeight = 0f.toY()
        for (spine in segment.spines) {
            val spineXInPageImage = global.widthPx / 2f + spine.posOffsetPx
            val drawnSpine = drawnSpines.getValue(spine)
            val x = spineXInPageImage - drawnSpine.spineXInImage
            stageImage.drawDeferredImage(drawnSpine.defImage, x, y)
            maxHeight = maxHeight.max(drawnSpine.defImage.height)
        }
        y += maxHeight + segment.vGapAfterPx.toElasticY()
    }

    // Same as for the preceding vertical gap above.
    if (nextStage != null && stage.style.behavior != PageBehavior.CARD)
        y += when (nextStage.style.behavior) {
            PageBehavior.CARD -> stage.vGapAfterPx.toY()
            PageBehavior.SCROLL -> (stage.vGapAfterPx / 2f).toElasticY()
        }

    stageImage.height = y
    return stageImage
}


private fun layoutStages(
    global: Global,
    stageImages: Map<Stage, DeferredImage>,
    page: Page
): Pair<Y, Map<Stage, StageLayout>> {
    // Determine each stage's top and bottom y coordinates in the future page image image.
    // Also find the height of the whole future page image if that is given explicitly.
    val stageImageBounds = mutableListOf<Pair<Y, Y>>()
    var pageImageHeight = 0f.toY()
    var y = 0f.toY()
    for ((stageIdx, stage) in page.stages.withIndex()) {
        var stageHeight = stageImages.getValue(stage).height
        // Special handling for card stages...
        if (stage.style.behavior == PageBehavior.CARD) {
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
    //     when the scrolling of this stage starts resp. stops.
    val stageInfo = page.stages.mapIndexed { stageIdx, stage ->
        val (topY, botY) = stageImageBounds[stageIdx]
        when (stage.style.behavior) {
            PageBehavior.CARD -> DrawnStageInfo.Card((topY + botY) / 2f)
            PageBehavior.SCROLL -> {
                val scrollStartY = when (page.stages.getOrNull(stageIdx - 1)?.style?.behavior) {
                    PageBehavior.CARD -> stageImageBounds[stageIdx - 1].let { (aTopY, aBotY) -> (aTopY + aBotY) / 2f }
                    PageBehavior.SCROLL -> stageImageBounds[stageIdx - 1].let { (_, aBotY) -> (aBotY + topY) / 2f }
                    null -> topY /* will always be 0 */ - global.heightPx / 2f
                }
                val scrollStopY = when (page.stages.getOrNull(stageIdx + 1)?.style?.behavior) {
                    PageBehavior.CARD -> stageImageBounds[stageIdx + 1].let { (bTopY, bBotY) -> (bTopY + bBotY) / 2f }
                    PageBehavior.SCROLL -> stageImageBounds[stageIdx + 1].let { (bTopY, _) -> (botY + bTopY) / 2f }
                    null -> botY + global.heightPx / 2f
                }
                DrawnStageInfo.Scroll(scrollStartY, scrollStopY)
            }
        }
    }

    val stageLayouts = page.stages.indices.associate { stageIdx ->
        page.stages[stageIdx] to StageLayout(stageImageBounds[stageIdx].first, stageInfo[stageIdx])
    }
    return Pair(pageImageHeight, stageLayouts)
}


private fun matchRuntime(
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

    for (stage in activeStages)
        frames += getStageFrames(prelimStageLayouts, pageTopStages, pageBotStages, stage) // rigid & elastic
    for (stage in passiveStages)
        frames += getStageFrames(prelimStageLayouts, pageTopStages, pageBotStages, stage).resolve() // rigid only

    val elasticScaling = frames.deresolve(desiredFrames.toFloat())

    for (stage in activeStages)
        if (stage.style.behavior == PageBehavior.SCROLL)
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

    fun drawFrames(frames: Int, y: Y) {
        val str = formatTimecode(global.fps, global.timecodeFormat, frames)
        // Note: Obtaining a Font object for the bold monospaced font is a bit convoluted because the final object must
        // not contain a font weight attribute, or else the FormattedString would complain.
        val fontName = UIManager.getFont("monospaced.font").deriveFont(Font.BOLD).getFontName(Locale.ROOT)
        val font = FormattedString.Font(STAGE_GUIDE_COLOR, Font(fontName, Font.PLAIN, 1), global.widthPx / 80f)
        val fmtStr = FormattedString.Builder(Locale.ROOT).apply {
            append(str, FormattedString.Attribute(font, emptySet(), null))
        }.build()
        val margin = global.widthPx / 100f
        pageImage.drawString(
            fmtStr, x = global.widthPx - fmtStr.width - margin, y = y + margin,
            foregroundLayer = GUIDES, backgroundLayer = GUIDES /* irrelevant, since our string has no background */
        )
    }

    for ((stageIdx, stage) in page.stages.withIndex()) {
        val stageImage = stageImages.getValue(stage)
        val stageLayout = stageLayouts.getValue(stage)
        // Special handling for card stages...
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
            drawFrames(getCardFrames(pageTopStages, pageBotStages, stage), cardTopY)
        } else if (stageLayout.info is DrawnStageInfo.Scroll) {
            val frames = VideoDrawer.discretizeScrollFrames(getScrollFrames(stageLayouts, stage).resolve())
            val y = when (val prevStageInfo = page.stages.getOrNull(stageIdx - 1)?.let(stageLayouts::getValue)?.info) {
                is DrawnStageInfo.Card -> prevStageInfo.middleY + global.heightPx / 2f
                else -> stageLayout.y
            }
            drawFrames(frames, y)
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
    stage: Stage
): Y =
    when (stage.style.behavior) {
        PageBehavior.CARD -> getCardFrames(pageTopStages, pageBotStages, stage).toFloat().toY()
        PageBehavior.SCROLL -> getScrollFrames(stageLayouts, stage)
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
    stage: Stage
): Y {
    val stageInfo = stageLayouts.getValue(stage).info as DrawnStageInfo.Scroll
    // The scroll should neither include the frame at scrollStartY nor the one at scrollStopY, hence the -1.
    return (stageInfo.scrollStopY - stageInfo.scrollStartY) / stage.style.scrollPxPerFrame - 1f
}
