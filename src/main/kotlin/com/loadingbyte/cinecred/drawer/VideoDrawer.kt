package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project


fun drawVideo(project: Project, drawnPages: List<DrawnPage>): DeferredVideo {
    val video = DeferredVideo(project.styling.global.resolution, project.styling.global.fps)

    // Write frames for each page as has been configured.
    for ((pageIdx, drawnPage) in drawnPages.withIndex()) {
        val page = drawnPage.page
        for ((stageIdx, stage) in page.stages.withIndex())
            when (val stageInfo = drawnPage.stageInfo[stageIdx]) {
                is DrawnStageInfo.Card -> {
                    val shift = stageInfo.middleY.resolve() - video.resolution.heightPx / 2.0
                    val firstStage = stageIdx == 0
                    val lastStage = stageIdx == page.stages.lastIndex
                    var opaqueFrames = stage.cardRuntimeFrames
                    var fadeInFrames = stage.style.cardFadeInFrames
                    var fadeOutFrames = stage.style.cardFadeOutFrames
                    if (firstStage) opaqueFrames -= fadeInFrames
                    if (lastStage) opaqueFrames -= fadeOutFrames
                    if (opaqueFrames < 0) when {
                        firstStage && lastStage -> {
                            val sub = -opaqueFrames
                            val half = sub / 2
                            fadeInFrames -= half
                            fadeOutFrames -= sub - half
                        }
                        firstStage -> fadeInFrames += opaqueFrames
                        lastStage -> fadeOutFrames += opaqueFrames
                    }
                    if (firstStage)
                        video.playFade(drawnPage.defImage, fadeInFrames, shift, 0.0, 1.0)
                    video.playStatic(drawnPage.defImage, opaqueFrames, shift, 1.0)
                    if (stageIdx == page.stages.lastIndex)
                        video.playFade(drawnPage.defImage, fadeOutFrames, shift, 1.0, 0.0)
                }
                is DrawnStageInfo.Scroll ->
                    video.playScroll(
                        drawnPage.defImage, stageInfo.frames, stage.style.scrollPxPerFrame,
                        startShift = stageInfo.scrollStartY.resolve() - video.resolution.heightPx / 2.0,
                        stopShift = stageInfo.scrollStopY.resolve() - video.resolution.heightPx / 2.0,
                        stageInfo.initialAdvance, alpha = 1.0
                    )
            }
        if (pageIdx != drawnPages.lastIndex)
            video.playBlank(page.gapAfterFrames)
    }

    return video
}
