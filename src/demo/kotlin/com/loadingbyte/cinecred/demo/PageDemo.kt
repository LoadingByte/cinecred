package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.imaging.DeferredImage
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
        val project = Project(styling, BundledFontsStylingContext, persistentListOf(page), persistentListOf())
        val pageDefImage = drawPages(project).single().defImage
        pageDefImgsAndGroundings.add(Pair(pageDefImage, styling.global.grounding))
    }

    private fun render() {
        val pageBounds = pageDefImgsAndGroundings.fold(Dimension()) { d, (defImg, _) ->
            Dimension(max(d.width, defImg.width.roundToInt()), max(d.height, defImg.height.resolve().roundToInt()))
        }
        val pageLayers = DeferredImage.DELIVERED_LAYERS.toMutableList()
        if (pageGuides)
            pageLayers.add(DeferredImage.GUIDES)

        for ((imgIdx, pageDefImgsAndGrounding) in pageDefImgsAndGroundings.withIndex()) {
            val suffix = suffixes.getOrElse(imgIdx) { "" }
            val (pageDefImg, grounding) = pageDefImgsAndGrounding
            val imgW = if (pageWidth != 0) pageWidth else pageBounds.width
            val imgH = 2 * pageExtend + pageBounds.height
            val img = buildImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
                g2.setHighQuality()
                g2.color = grounding
                g2.fillRect(0, 0, imgW, imgH)
                g2.translate((imgW - pageBounds.width) / 2.0, pageExtend.toDouble())
                pageDefImg.materialize(g2, pageLayers)
            }
            write(img, suffix)
        }
    }

    private val pageDefImgsAndGroundings = mutableListOf<Pair<DeferredImage, Color>>()

}
