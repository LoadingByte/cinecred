package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.imaging.Transition
import com.loadingbyte.cinecred.project.Project


fun drawVideo(project: Project, drawnPages: List<DrawnPage>): DeferredVideo {
    val video = DeferredVideo(project.styling.global.resolution, project.styling.global.fps)

    if (project.styling.global.blankFirstFrame && drawnPages.isNotEmpty())
        video.playBlank(1)

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
                    if (firstStage) {
                        val transition = project.styling.transitionStyles
                            .find { it.name == stage.style.cardFadeInTransitionStyleName }?.graph ?: Transition.LINEAR
                        video.playFade(drawnPage.defImage, fadeInFrames, shift, transition, fadeIn = true)
                    }
                    video.playStatic(drawnPage.defImage, opaqueFrames, shift, 1.0)
                    if (lastStage) {
                        val transition = project.styling.transitionStyles
                            .find { it.name == stage.style.cardFadeOutTransitionStyleName }?.graph ?: Transition.LINEAR
                        video.playFade(drawnPage.defImage, fadeOutFrames, shift, transition, fadeIn = false)
                    }
                }
                is DrawnStageInfo.Scroll -> {
                    val steadyStartY = stageInfo.scrollStartY.resolve() + stageInfo.startRampHeight
                    val steadyStopY = stageInfo.scrollStopY.resolve() - stageInfo.stopRampHeight
                    val prevStage = page.stages.getOrNull(stageIdx - 1)
                    if (prevStage?.transitionAfterStyle != null)
                        video.playScrollRamp(
                            drawnPage.defImage, prevStage.transitionAfterFrames,
                            startShift = stageInfo.scrollStartY.resolve() - video.resolution.heightPx / 2.0,
                            stopShift = steadyStartY - video.resolution.heightPx / 2.0,
                            prevStage.transitionAfterStyle.graph, speedUp = true, alpha = 1.0
                        )
                    video.playScroll(
                        drawnPage.defImage, stageInfo.steadyFrames, stage.style.scrollPxPerFrame,
                        startShift = steadyStartY - video.resolution.heightPx / 2.0,
                        stopShift = steadyStopY - video.resolution.heightPx / 2.0,
                        alpha = 1.0
                    )
                    if (stage.transitionAfterStyle != null)
                        video.playScrollRamp(
                            drawnPage.defImage, stage.transitionAfterFrames,
                            startShift = steadyStopY - video.resolution.heightPx / 2.0,
                            stopShift = stageInfo.scrollStopY.resolve() - video.resolution.heightPx / 2.0,
                            stage.transitionAfterStyle.graph, speedUp = false, alpha = 1.0
                        )
                }
            }
        if (pageIdx != drawnPages.lastIndex)
            video.playBlank(page.gapAfterFrames)
    }

    if (project.styling.global.blankLastFrame && drawnPages.isNotEmpty())
        video.playBlank(1)

    return video
}
