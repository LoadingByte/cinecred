package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project


fun drawVideo(project: Project, drawnPages: List<DrawnPage>): DeferredVideo {
    val video = DeferredVideo(project.styling.global.resolution, project.styling.global.fps)

    // Write frames for each page as has been configured.
    for ((pageIdx, page) in project.pages.withIndex()) {
        val drawnPage = drawnPages[pageIdx]
        for ((stageIdx, stage) in page.stages.withIndex())
            when (val stageInfo = drawnPage.stageInfo[stageIdx]) {
                is DrawnStageInfo.Card -> {
                    val shift = stageInfo.middleY.resolve() - video.resolution.heightPx / 2.0
                    if (stageIdx == 0)
                        video.playFade(drawnPage.defImage, stage.style.cardFadeInFrames, shift, 0.0, 1.0)
                    video.playStatic(drawnPage.defImage, stage.style.cardDurationFrames, shift, 1.0)
                    if (stageIdx == page.stages.lastIndex)
                        video.playFade(drawnPage.defImage, stage.style.cardFadeOutFrames, shift, 1.0, 0.0)
                }
                is DrawnStageInfo.Scroll ->
                    video.playScroll(
                        drawnPage.defImage, stageInfo.frames, stage.style.scrollPxPerFrame,
                        startShift = stageInfo.scrollStartY.resolve() - video.resolution.heightPx / 2.0,
                        stopShift = stageInfo.scrollStopY.resolve() - video.resolution.heightPx / 2.0,
                        stageInfo.initialAdvance, alpha = 1.0
                    )
            }
        if (pageIdx != project.pages.lastIndex)
            video.playBlank(page.stages.last().style.afterwardSlugFrames)
    }

    return video
}
