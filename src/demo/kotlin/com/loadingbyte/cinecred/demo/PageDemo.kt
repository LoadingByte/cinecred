package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.Credits
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Page
import com.loadingbyte.cinecred.project.Project
import kotlinx.collections.immutable.persistentListOf
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt


abstract class PageDemo(
    filename: String,
    format: Format,
    private val pageWidth: Int = 700,
    private val pageHeight: Int = 0,
    private val pageExtend: Int = 20,
    private val pageGuides: Boolean = false
) : Demo(filename, format) {

    protected abstract fun credits(): List<Pair<Global, Page>>
    protected open val suffixes: List<String> get() = emptyList()

    final override fun doGenerate() {
        // Capture material for each page.
        for ((global, page) in credits())
            capture(global, page)
        // Write out the images using the captured material.
        render()
        // Clean up.
        pageDefImgsAndGroundings.clear()
    }

    private fun capture(global: Global, page: Page) {
        val styling = extractStyling(global, page)
        val credits = Credits("", persistentListOf(page), persistentListOf())
        val project = Project(styling, BundledFontsStylingContext, persistentListOf(credits))
        val pageDefImage = drawPages(project, credits).single().defImage
        pageDefImgsAndGroundings.add(Pair(pageDefImage, styling.global.grounding))
    }

    private fun render() {
        val pageBounds = pageDefImgsAndGroundings.fold(Dimension()) { d, (defImg, _) ->
            Dimension(max(d.width, defImg.width.roundToInt()), max(d.height, defImg.height.resolve().roundToInt()))
        }
        val pageLayers = mutableListOf(STATIC, TAPES)
        if (pageGuides)
            pageLayers.add(GUIDES)

        for ((imgIdx, pageDefImgsAndGrounding) in pageDefImgsAndGroundings.withIndex()) {
            val suffix = suffixes.getOrElse(imgIdx) { "" }
            val (pageDefImg, grounding) = pageDefImgsAndGrounding
            val imgW = if (pageWidth > 0) pageWidth else pageBounds.width
            val imgH = 2 * pageExtend + if (pageHeight > 0) pageHeight else pageBounds.height
            val img = buildImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
                g2.color = grounding
                g2.fillRect(0, 0, imgW, imgH)
            }
            DeferredImage(imgW.toDouble(), imgH.toDouble().toY()).apply {
                drawDeferredImage(pageDefImg, (imgW - pageBounds.width) / 2.0, ((imgH - pageBounds.height) / 2.0).toY())
            }.materialize(img, pageLayers)
            write(img, suffix)
        }
    }

    private val pageDefImgsAndGroundings = mutableListOf<Pair<DeferredImage, Color>>()

}
