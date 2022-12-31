package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project


fun drawVideo(project: Project, drawnPages: List<DrawnPage>): DeferredVideo {
    val video = DeferredVideo(project.styling.global.widthPx, project.styling.global.heightPx)

    // Write frames for each page as has been configured.
    for ((pageIdx, page) in project.pages.withIndex()) {
        val drawnPage = drawnPages[pageIdx]
        val builder = DeferredImageInstructionBuilder(drawnPage.defImage)
        for ((stageIdx, stage) in page.stages.withIndex())
            when (val stageInfo = drawnPage.stageInfo[stageIdx]) {
                is DrawnStageInfo.Card -> {
                    val shift = stageInfo.middleY.resolve() - project.styling.global.heightPx / 2.0
                    if (stageIdx == 0)
                        builder.playFade(shift, stage.style.cardFadeInFrames, fadeOut = false)
                    builder.playStatic(shift, stage.style.cardDurationFrames)
                    if (stageIdx == page.stages.lastIndex)
                        builder.playFade(shift, stage.style.cardFadeOutFrames, fadeOut = true)
                }
                is DrawnStageInfo.Scroll ->
                    builder.playScroll(
                        shiftStart = stageInfo.scrollStartY.resolve() - project.styling.global.heightPx / 2.0,
                        stageInfo.frames, stageInfo.initialAdvance, stage.style.scrollPxPerFrame
                    )
            }
        builder.build(video)

        if (pageIdx != project.pages.lastIndex)
            video.playBlank(page.stages.last().style.afterwardSlugFrames)
    }

    return video
}


private class DeferredImageInstructionBuilder(private val image: DeferredImage) {

    private var numFrames = 0
    private var shifts = DoubleArray(16384)
    private var alphas = DoubleArray(16384)

    private fun playFrame(shift: Double, alpha: Double) {
        if (shifts.size == numFrames) {
            shifts = shifts.copyOf(numFrames * 2)
            alphas = alphas.copyOf(numFrames * 2)
        }
        shifts[numFrames] = shift
        alphas[numFrames] = alpha
        numFrames++
    }

    fun playStatic(shift: Double, numFrames: Int) {
        for (frame in 0 until numFrames)
            playFrame(shift, 1.0)
    }

    fun playFade(shift: Double, numFrames: Int, fadeOut: Boolean) {
        for (frame in 0 until numFrames) {
            // Choose alpha such that the fade sequence doesn't contain a fully empty or fully opaque frame.
            var alpha = (frame + 1).toDouble() / (numFrames + 1)
            if (fadeOut)
                alpha = 1.0 - alpha
            playFrame(shift, alpha)
        }
    }

    fun playScroll(shiftStart: Double, numFrames: Int, initialAdvance: Double, scrollPxPerFrame: Double) {
        for (frame in 0 until numFrames) {
            val shift = shiftStart + (frame + initialAdvance) * scrollPxPerFrame
            playFrame(shift, 1.0)
        }
    }

    fun build(video: DeferredVideo) {
        video.playDeferredImage(image, shifts.copyOf(numFrames), alphas.copyOf(numFrames))
    }

}
