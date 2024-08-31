package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.requireIsInstance
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.usableBounds
import com.loadingbyte.cinecred.ui.styling.StyleForm
import com.loadingbyte.cinecred.ui.styling.StyleFormAdjuster
import java.awt.Color
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.Thread.sleep
import javax.swing.JFrame
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.collections.immutable.persistentListOf as persListOf


abstract class StyleSettingsDemo<S : Style>(
    private val styleClass: Class<S>,
    filename: String,
    format: Format,
    private val settings: List<StyleSetting<S, *>>,
    private val pageScaling: Double = 1.0,
    private val pageWidth: Int = 700,
    private val pageHeight: Int = 0,
    private val pageExtend: Int = 20,
    private val pageGuides: Boolean = false
) : Demo(filename, format) {

    protected abstract fun styles(): List<S>
    protected open val suffixes: List<String> get() = emptyList()
    protected open fun credits(style: S): Pair<Global, Page>? = null
    protected open fun augmentStyling(styling: Styling): Styling = styling

    final override fun doGenerate() {
        // These variables are continuously updated while iterating through the list of styles().
        var curStyling: Styling? = null
        var curStyle: S? = null
        var curStyleIdx = 0
        var siblingStyles = emptyList<S>()

        // Set up the StyleForm.
        lateinit var form: StyleForm<S>
        lateinit var frame: JFrame
        edt {
            @Suppress("UNCHECKED_CAST")
            form = StyleForm(
                styleClass, constSettings = if (!LayerStyle::class.java.isAssignableFrom(styleClass)) emptyMap() else
                    mapOf(LayerStyle::name.st() to "", LayerStyle::collapsed.st() to false)
                            as Map<StyleSetting<in S, *>, Any>
            )
            frame = JFrame(gCfg).apply {
                contentPane.add(form)
                setSize(800, gCfg.usableBounds.height)
                isVisible = true
            }
        }
        val formAdjuster = StyleFormAdjuster(
            listOf(form), { curStyling }, { curStyle }, {},
            @Suppress("DEPRECATION")
            object : StyleFormAdjuster.StyleIdxAndSiblingsOverride {
                override fun <S : Style> getStyleIdxAndSiblings(style: S): Pair<Int, List<S>> =
                    Pair(curStyleIdx, siblingStyles.requireIsInstance(style.javaClass))
            }
        )
        formAdjuster.activeForm = form
        sleep(500)
        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }

        // Iterate through the list of styles() and capture material (form & optionally page) for each style.
        for (style in styles()) {
            val credits = credits(style)
            curStyling = credits?.let { (global, page) -> extractStyling(global, page) } ?: when (style) {
                is Global -> Styling(style, persListOf(), persListOf(), persListOf())
                is PageStyle -> Styling(PRESET_GLOBAL, persListOf(style), persListOf(), persListOf())
                is ContentStyle -> Styling(PRESET_GLOBAL, persListOf(), persListOf(style), persListOf())
                is LetterStyle -> Styling(PRESET_GLOBAL, persListOf(), persListOf(), persListOf(style))
                else -> throw IllegalStateException()
            }
            curStyling = augmentStyling(curStyling)
            curStyle = style
            if (style is Layer) {
                siblingStyles = curStyling.letterStyles.first { style in it.layers }.layers
                    .requireIsInstance(styleClass)
                curStyleIdx = siblingStyles.indexOf(style)
            }
            edt { form.open(style) }
            formAdjuster.onLoadStyling()
            formAdjuster.onLoadStyleIntoActiveForm()
            capture(form, curStyling, credits)
        }

        // Write out the images using the captured material.
        render()

        // Clean up.
        settImgs.clear()
        pageDefImgsAndGroundings.clear()
        edt { frame.dispose() }
    }

    private fun capture(form: StyleForm<S>, curStyling: Styling, credits: Pair<Global, Page>?) {
        require(settImgs.isEmpty() || pageDefImgsAndGroundings.isEmpty() == (credits == null)) {
            "Whether credits are supplied or not must be consistent across all capture() calls."
        }

        // Only remove the notices if they could actually slide into view (which can only happen if there are multiple
        // settings being captures). This is necessary because the uppercase exceptions text area doesn't grow to
        // multiple lines if its notice is removed (... for some reason).
        if (settings.size > 1)
            for (sett in settings)
                form.getFormRowFor(sett).apply {
                    notice = null
                    noticeOverride = null
                }

        sleep(500)

        val compBounds = Rectangle(0, 0, -1, -1)
        for (sett in settings) {
            compBounds.add(form.getFormRowFor(sett).labelComp.bounds)
            for (comp in form.getWidgetFor(sett).components)
                if (comp.isVisible)
                    compBounds.add(comp.bounds)
        }
        val settImg = buildImage(compBounds.width, compBounds.height, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
            g2.translate(-compBounds.x, -compBounds.y)
            printWithPopups(form, g2)
        }
        settImgs.add(settImg)

        if (credits != null) {
            val (_, page) = credits
            val creditsObj = Credits("", persListOf(page), persListOf())
            val project = Project(curStyling, persListOf(creditsObj))
            val pageDefImage = drawPages(project, creditsObj).single().defImage.copy(universeScaling = pageScaling)
            pageDefImgsAndGroundings.add(Pair(pageDefImage, curStyling.global.grounding))
        }
    }

    private fun render() {
        val settExtendX = 20
        val settExtendY = 10

        val settBounds = settImgs.fold(Dimension()) { d, img ->
            Dimension(max(d.width, img.width), max(d.height, img.height))
        }
        val pageBounds = pageDefImgsAndGroundings.fold(Dimension()) { d, (defImg, _) ->
            Dimension(max(d.width, defImg.width.roundToInt()), max(d.height, defImg.height.resolve().roundToInt()))
        }
        val pageLayers = mutableListOf(STATIC, TAPES)
        if (pageGuides)
            pageLayers.add(GUIDES)

        for ((imgIdx, settImg) in settImgs.withIndex()) {
            val suffix = suffixes.getOrElse(imgIdx) { "" }
            val settBgColor = Color(settImg.getRGB(0, 0))
            val img = if (pageDefImgsAndGroundings.isEmpty()) {
                val imgW = settBounds.width + 2 * settExtendX
                val imgH = settBounds.height + 2 * settExtendY
                buildImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
                    g2.color = settBgColor
                    g2.fillRect(0, 0, imgW, imgH)
                    g2.drawImage(settImg, settExtendX, settExtendY, null)
                }
            } else {
                val (pageDefImg, grounding) = pageDefImgsAndGroundings[imgIdx]
                val imgW = if (pageWidth != 0) pageWidth else pageBounds.width
                val imgH = settBounds.height + 2 * settExtendY +
                        2 * pageExtend + if (pageHeight != 0) pageHeight else pageBounds.height
                val settX = (imgW - settBounds.width) / 2
                val settH = settBounds.height + 2 * settExtendY
                val pageImg = defImageToImage(
                    grounding, pageLayers,
                    DeferredImage(imgW.toDouble(), (imgH - settH).toDouble().toY()).apply {
                        drawDeferredImage(pageDefImg, (imgW - pageBounds.width) / 2.0, pageExtend.toDouble().toY())
                    }
                )
                buildImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
                    g2.drawImage(pageImg, 0, settH, null)
                    g2.color = settBgColor
                    g2.fillRect(0, 0, imgW, settH)
                    g2.clipRect(settX, 0, imgW, settH)
                    g2.drawImage(settImg, settX, settExtendY, null)
                }
            }
            write(img, suffix)
        }
    }

    private val settImgs = mutableListOf<BufferedImage>()
    private val pageDefImgsAndGroundings = mutableListOf<Pair<DeferredImage, Color4f>>()

}
