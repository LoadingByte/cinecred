package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import java.awt.Graphics2D
import java.awt.RenderingHints
import kotlin.math.ceil
import kotlin.math.floor


fun Graphics2D.setHighQuality() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}


fun getDurationFrames(project: Project, pageDefImages: List<DeferredImage>) =
    project.pages.zip(pageDefImages).sumBy { (page, pageDefImage) ->
        when (page.style.behavior) {
            PageBehavior.CARD ->
                page.style.cardDurationFrames + page.style.cardFadeInFrames + page.style.cardFadeOutFrames
            PageBehavior.SCROLL ->
                floor((project.styling.global.heightPx + ceil(pageDefImage.height)) / page.style.scrollPxPerFrame).toInt()
        }
    } + project.pages.dropLast(1).sumBy { page -> page.style.afterwardSlugFrames } + 1
