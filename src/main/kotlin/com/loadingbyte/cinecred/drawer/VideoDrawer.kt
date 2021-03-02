package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.FOREGROUND
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.DrawnStageInfo
import com.loadingbyte.cinecred.project.Project
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt


open class VideoDrawer(
    private val project: Project,
    drawnPages: List<DrawnPage>,
    private val scaling: Float = 1f,
    private val transparent: Boolean = false,
    private val previewMode: Boolean = false
) {

    /* **************************************
       ************ INITIALIZATION **********
       ************************************** */

    // Note: A pageIdx of -1 indicates an empty frame.
    private class Insn(val pageIdx: Int, val imgTopY: Float, val alpha: Float)

    private val insns = ArrayList<Insn>()

    init {
        // Write frames for each page as has been configured.
        for ((pageIdx, page) in project.pages.withIndex()) {
            var prevScrollActualImgTopYStop: Float? = null
            for ((stageIdx, stage) in page.stages.withIndex())
                when (val stageInfo = drawnPages[pageIdx].stageInfo[stageIdx]) {
                    is DrawnStageInfo.Card -> {
                        val imgTopY = scaling * (stageInfo.middleY - project.styling.global.heightPx / 2f)
                        if (stageIdx == 0)
                            writeFade(pageIdx, imgTopY, stage.style.cardFadeInFrames, fadeOut = false)
                        writeStatic(pageIdx, imgTopY, stage.style.cardDurationFrames)
                        if (stageIdx == page.stages.lastIndex)
                            writeFade(pageIdx, imgTopY, stage.style.cardFadeOutFrames, fadeOut = true)
                        prevScrollActualImgTopYStop = null
                    }
                    is DrawnStageInfo.Scroll -> {
                        val imgTopYStart = prevScrollActualImgTopYStop
                            ?: scaling * (stageInfo.scrollStartY - project.styling.global.heightPx / 2f)
                        val imgTopYStop = scaling * (stageInfo.scrollStopY - project.styling.global.heightPx / 2f)
                        prevScrollActualImgTopYStop = writeScroll(
                            pageIdx, imgTopYStart, imgTopYStop, scaling * stage.style.scrollPxPerFrame
                        )
                    }
                }

            if (pageIdx != project.pages.lastIndex)
                writeStatic(-1, 0f, page.stages.last().style.afterwardSlugFrames)
        }

        // Write one final empty frame at the end.
        insns.add(Insn(-1, 0f, 1f))
    }

    private fun writeStatic(pageIdx: Int, imgTopY: Float, numFrames: Int) {
        for (frame in 0 until numFrames)
            insns.add(Insn(pageIdx, imgTopY, 1f))
    }

    private fun writeFade(pageIdx: Int, imgTopY: Float, numFrames: Int, fadeOut: Boolean) {
        for (frame in 0 until numFrames) {
            // Choose alpha such that the fade sequence doesn't contain a fully empty or fully opaque frame.
            var alpha = (frame + 1).toFloat() / (numFrames + 1)
            if (fadeOut)
                alpha = 1f - alpha
            insns.add(Insn(pageIdx, imgTopY, alpha))
        }
    }

    /**
     * Returns the `imgTopY` where the scroll actually stopped.
     */
    private fun writeScroll(
        pageIdx: Int, imgTopYStart: Float, imgTopYStop: Float, scrollPxPerFrame: Float
    ): Float {
        // Choose imgTopY such that the scroll sequence never contains imgTopYStart nor imgTopYStop.
        var imgTopY = imgTopYStart
        while (imgTopY + scrollPxPerFrame < imgTopYStop) {
            imgTopY += scrollPxPerFrame
            insns.add(Insn(pageIdx, imgTopY, 1f))
        }
        return imgTopY
    }

    val numFrames = insns.size

    val width = (scaling * project.styling.global.widthPx).roundToInt()
    val height = (scaling * project.styling.global.heightPx).roundToInt()


    /* **************************************
       ************ RASTER DRAWING **********
       ************************************** */

    private val scaledPageDefImages = drawnPages.map {
        DeferredImage().apply { drawDeferredImage(it.defImage, 0f, 0f, scaling) }
    }

    private val shiftedPageImages = Array(drawnPages.size) { HashMap<Float, BufferedImage>() }

    init {
        // In preview mode, only 0-shifted rasterized page images are used. We already precompute these now
        // to avoid wait times later when pages must be drawn in real-time.
        if (previewMode)
            for (pageIdx in 0 until project.pages.size)
                getShiftedPageImage(pageIdx, 0f)
    }

    private fun getShiftedPageImage(pageIdx: Int, shift: Float): BufferedImage {
        val cache = shiftedPageImages[pageIdx]
        return cache.getOrPut(shift) {
            // If the cache is full, remove one cached image (doesn't matter which one we remove).
            if (cache.size >= MAX_SHIFTED_PAGE_IMAGES)
                cache.iterator().run { next(); remove() }

            require(shift >= 0f && shift < 1f)
            val scaledPageDefImg = scaledPageDefImages[pageIdx]
            // Note: We add 1 to the height to make room for the shift (which is between 0 and 1).
            val imageHeight = ceil(scaledPageDefImg.height).toInt() + 1
            createIntermediateImage(width, imageHeight).withG2 { g2 ->
                g2.setHighQuality()
                if (!transparent) {
                    // If the image should not be transparent, we have to fill in the background.
                    // Note that we can't simply use the deferred image's background layer because that doesn't extend
                    // to the whole height of the raster image, since the raster image is higher than the deferred
                    // image to make room for the shift.
                    g2.color = project.styling.global.background
                    g2.fillRect(0, 0, width, imageHeight)
                }
                g2.translate(0.0, shift.toDouble())
                scaledPageDefImg.materialize(g2, layers = listOf(FOREGROUND))
            }
        }
    }

    /**
     * This method can (and should be) overwritten by users of this class who exactly know onto what kind of
     * graphics object they will later draw their frames.
     */
    protected open fun createIntermediateImage(width: Int, height: Int): BufferedImage {
        val imageType = if (transparent) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        return BufferedImage(width, height, imageType)
    }

    fun drawFrame(g2: Graphics2D, frameIdx: Int) {
        require(frameIdx in 0..numFrames) { "Frame #$frameIdx exceeds number of available frames ($numFrames)." }

        val insn = insns[frameIdx]

        if (!transparent) {
            g2.color = project.styling.global.background
            g2.fillRect(0, 0, width, height)
        }

        // Note: pageIdx == -1 would mean that the frame should be empty.
        if (insn.pageIdx != -1) {
            if (insn.alpha != 1f)
                g2.composite = AlphaComposite.SrcOver.derive(insn.alpha)

            val shift = -insn.imgTopY
            if (!previewMode) {
                g2.drawImage(getShiftedPageImage(insn.pageIdx, shift - floor(shift)), 0, floor(shift).toInt(), null)
            } else {
                val tx = AffineTransform.getTranslateInstance(0.0, shift.toDouble())
                g2.drawImage(getShiftedPageImage(insn.pageIdx, 0f), tx, null)
            }
        }
    }


    companion object {
        private const val MAX_SHIFTED_PAGE_IMAGES = 16
    }

}
