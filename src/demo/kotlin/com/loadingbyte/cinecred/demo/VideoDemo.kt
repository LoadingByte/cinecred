package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.drawer.drawVideo
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Page
import com.loadingbyte.cinecred.project.Project
import kotlinx.collections.immutable.persistentListOf
import java.awt.image.BufferedImage


abstract class VideoDemo(filename: String, format: Format) : Demo(filename, format) {

    protected abstract fun credits(): Pair<Global, Page>

    protected open fun receiveFrame(frame: BufferedImage) = write(frame)
    protected open fun flushFrames() {}

    final override fun doGenerate() {
        // Capture the video.
        val (global, page) = credits()
        val styling = extractStyling(global, page)
        val project = Project(styling, BundledFontsStylingContext, persistentListOf(page), persistentListOf())
        val video = drawVideo(project, drawPages(project))

        // Write out the video.
        val backend = object : DeferredVideo.BufferedImageBackend(video, listOf(STATIC), listOf(TAPES), draft = true) {
            override fun createIntermediateImage(width: Int, height: Int) =
                BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR)
        }
        for (frameIdx in 0..<video.numFrames) {
            val img = BufferedImage(video.resolution.widthPx, video.resolution.heightPx, BufferedImage.TYPE_3BYTE_BGR)
            backend.materializeFrame(img, frameIdx)
            receiveFrame(img)
        }
        flushFrames()
    }

}
