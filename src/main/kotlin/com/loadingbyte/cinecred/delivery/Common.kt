package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import kotlin.math.floor
import kotlin.math.round


open class RenderFormat(val label: String, val fileExts: List<String>)


/**
 * Note: This only gives an estimate and not an exact value.
 * Still, the final render usually differs by at most 1 frame.
 */
fun getRuntimeFrames(project: Project, drawnPages: List<DrawnPage>): Int =
    project.pages.zip(drawnPages).sumBy { (page, drawnPage) ->
        var runtime = 0

        val firstStage = page.stages.first()
        val lastStage = page.stages.last()
        if (firstStage.style.behavior == PageBehavior.CARD)
            runtime += firstStage.style.cardFadeInFrames
        if (lastStage.style.behavior == PageBehavior.CARD)
            runtime += lastStage.style.cardFadeOutFrames

        for ((stage, info) in page.stages.zip(drawnPage.stageInfo))
            runtime += when (info) {
                is DrawnStageInfo.Card -> stage.style.cardDurationFrames
                is DrawnStageInfo.Scroll ->
                    floor(round(info.scrollStopY - info.scrollStartY) / stage.style.scrollPxPerFrame).toInt()
            }

        runtime
    } + project.pages.dropLast(1).sumBy { page -> page.stages.last().style.afterwardSlugFrames } + 1
