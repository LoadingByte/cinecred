package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.requireIsInstance
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.drawer.drawVideo
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Canvas
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.StyleForm
import com.loadingbyte.cinecred.ui.styling.StyleFormAdjuster
import kotlinx.collections.immutable.toPersistentList
import java.awt.*
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.*
import java.lang.Thread.sleep
import javax.swing.JFrame
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.collections.immutable.persistentListOf as pl
import java.awt.color.ColorSpace as AWTColorSpace


abstract class PageDemo(
    filename: String,
    format: Format,
    private val pageWidth: Int = 700,
    private val pageHeight: Int = 0,
    private val pageExtendY: Int = 20,
    private val pageCenterY: Boolean = false,
    private val pageGuides: Boolean = false
) : Demo(filename, format) {

    protected abstract fun credits(): List<Pair<Global, Page>>
    protected open val suffixes: List<String> get() = emptyList()

    final override fun doGenerate() {
        val pageDefImgsAndGroundings = credits().map(::buildPageDefImgAndGrounding)
        renderDefImages(pageDefImgsAndGroundings, pageWidth, pageHeight, pageExtendY, pageCenterY, pageGuides)
            .forEachIndexed { idx, img -> write(img, suffixes.getOrElse(idx) { "" }) }
    }

}


abstract class StyleSettingsDemo<S : Style>(
    styleClass: Class<S>,
    filename: String,
    format: Format,
    private val settings: List<StyleSetting<S, *>>,
    private val pageScaling: Double = 1.0,
    private val pageWidth: Int = 700,
    private val pageHeight: Int = 0,
    private val pageExtendY: Int = 20,
    private val pageGuides: Boolean = false
) : Demo(filename, format) {

    protected abstract fun styles(): List<S>
    protected open val suffixes: List<String> get() = emptyList()
    protected open fun credits(style: S): Pair<Global, Page>? = null
    protected open fun augmentStyling(styling: Styling): Styling = styling
    protected open val pictureLoaders: Collection<Picture.Loader> get() = emptyList()
    protected open val tapes: Collection<Tape> get() = emptyList()

    final override fun doGenerate() {
        val styles = styles()
        val settImgs = renderStyleSettings(
            settings, styles, { credits(it)?.let(::extractStyling) }, ::augmentStyling, pictureLoaders, tapes
        )
        val pageDefImgsAndGroundings =
            styles.mapNotNull(::credits).map { buildPageDefImgAndGrounding(it, ::augmentStyling, pageScaling) }
        val pageImgs = renderDefImages(pageDefImgsAndGroundings, pageWidth, pageHeight, pageExtendY, false, pageGuides)
        val rows = listOf(settImgs.toList()) +
                if (pageDefImgsAndGroundings.isEmpty()) emptyList() else listOf(pageImgs.asIterable())
        stackImages(*rows.toTypedArray(), extendX = listOf(20), extendY = listOf(10))
            .forEachIndexed { idx, img -> write(img, suffixes.getOrElse(idx) { "" }) }
    }

}


abstract class VideoDemo(filename: String, format: Format) : Demo(filename, format) {

    protected abstract fun credits(): Pair<Global, Page>

    final override fun doGenerate() {
        val project = buildProject(credits())
        val video = drawVideo(project, drawPages(project, project.credits.single()))
        renderDefVideo(video, project.styling.global.grounding).forEach(::write)
    }

}


abstract class StyleSettingsTimelineDemo(
    filename: String,
    format: Format,
    private val settings: List<StyleSetting<TapeStyle, *>>
) : Demo(filename, format) {

    protected abstract fun styles(): List<TapeStyle>
    protected abstract fun credits(style: TapeStyle): Pair<Global, Page>

    final override fun doGenerate() {
        val styles = styles()
        var settImgs = renderStyleSettings(settings, styles, { extractStyling(credits(it)) })
        settImgs = stackImages(settImgs.toList(), extendX = listOf(20), extendY = listOf(10))
        styles.zip(settImgs.asIterable()) { style, settImg ->
            val project = buildProject(credits(style))
            val video = drawVideo(project, drawPages(project, project.credits.single()))
            val videoImgs = renderDefVideo(video, project.styling.global.grounding)
            val tlImgs = generateTimeline(style, video)
            stackImages(List(video.numFrames) { settImg }, tlImgs.asIterable(), videoImgs.asIterable()).forEach(::write)
        }
    }

    private fun generateTimeline(style: TapeStyle, video: DeferredVideo): Sequence<BufferedImage> {
        val timeframe = video.numFrames - (style.leftTemporalMarginFrames + style.rightTemporalMarginFrames)
        val avail = style.tape.tape!!.availableRange
        var slice = (style.slice.outPoint.orElse { avail.endExclusive }.toFrames(video.fps).frames -
                style.slice.inPoint.orElse { avail.start }.toFrames(video.fps).frames).coerceAtMost(timeframe)
        var leftMargin = style.leftTemporalMarginFrames
        if (style.temporallyJustify == HJustify.CENTER) leftMargin += (timeframe - slice) / 2
        if (style.temporallyJustify == HJustify.RIGHT) leftMargin += timeframe - slice
        val rightMargin = video.numFrames - (leftMargin + slice)
        return renderTimeline(
            video.resolution.widthPx, video.numFrames, leftMargin, rightMargin, style.leftTemporalMarginFrames,
            style.rightTemporalMarginFrames, style.fadeInFrames, style.fadeOutFrames
        )
    }

}


/* *******************************************
   ********** INTERMEDIATE BUILDERS **********
   ******************************************* */


private fun extractStyling(globalAndPage: Pair<Global, Page>): Styling {
    val (global, page) = globalAndPage
    val pageStyles = HashSet<PageStyle>()
    val contentStyles = HashSet<ContentStyle>()
    val letterStyles = HashSet<LetterStyle>()
    val pictureStyles = HashSet<PictureStyle>()
    val tapeStyles = HashSet<TapeStyle>()
    for (stage in page.stages) {
        pageStyles.add(stage.style)
        for (compound in stage.compounds)
            for (spine in compound.spines)
                for (block in spine.blocks) {
                    contentStyles.add(block.style)
                    for (elem in block.body)
                        when (elem) {
                            is BodyElement.Nil -> letterStyles.add(elem.sty)
                            is BodyElement.Str -> for (str in elem.lines) for ((_, sty) in str) letterStyles.add(sty)
                            is BodyElement.Pic -> pictureStyles.add(elem.sty)
                            is BodyElement.Tap -> tapeStyles.add(elem.sty)
                            is BodyElement.Mis -> {}
                        }
                    for (str in block.head.orEmpty()) for ((_, style) in str) letterStyles.add(style)
                    for (str in block.tail.orEmpty()) for ((_, style) in str) letterStyles.add(style)
                }
    }
    return Styling(
        global,
        pageStyles.toPersistentList(),
        contentStyles.toPersistentList(),
        letterStyles.toPersistentList(),
        pictureStyles.toPersistentList(),
        tapeStyles.toPersistentList()
    )
}


private fun buildProject(globalAndPage: Pair<Global, Page>, augmentStyling: ((Styling) -> Styling)? = null): Project {
    var styling = extractStyling(globalAndPage)
    if (augmentStyling != null)
        styling = augmentStyling(styling)
    val credits = Credits("", pl(globalAndPage.second), pl())
    return Project(styling, pl(credits))
}


private fun buildPageDefImgAndGrounding(
    globalAndPage: Pair<Global, Page>, augmentStyling: ((Styling) -> Styling)? = null, scaling: Double = 1.0
): Pair<DeferredImage, Color4f> {
    val project = buildProject(globalAndPage, augmentStyling)
    val pageDefImg = drawPages(project, project.credits.single()).single().defImage.copy(universeScaling = scaling)
    return Pair(pageDefImg, project.styling.global.grounding)
}


/* *************************************
   ********** IMAGE RENDERERS **********
   ************************************* */


private fun renderDefImages(
    defImgsAndGroundings: List<Pair<DeferredImage, Color4f>>,
    width: Int, height: Int, extendY: Int, centerY: Boolean, guides: Boolean
) = sequence<BufferedImage> {
    val bounds = defImgsAndGroundings.fold(Dimension()) { dim, (defImg, _) ->
        Dimension(max(dim.width, defImg.width.roundToInt()), max(dim.height, defImg.height.resolve().roundToInt()))
    }
    val layers = mutableListOf(STATIC, TAPES)
    if (guides)
        layers.add(GUIDES)

    for ((defImg, grounding) in defImgsAndGroundings) {
        val imgW = if (width > 0) width else bounds.width
        val imgH = 2 * extendY + if (height > 0) height else bounds.height
        yield(defImageToImage(grounding, layers, DeferredImage(imgW.toDouble(), imgH.toDouble().toY()).apply {
            val x = (imgW - bounds.width) / 2.0
            val y = (if (centerY) ((imgH - bounds.height) / 2.0) else extendY.toDouble()).toY()
            drawDeferredImage(defImg, x, y)
        }))
    }
}


private fun renderDefVideo(defVideo: DeferredVideo, grounding: Color4f) = sequence<BufferedImage> {
    DeferredVideo.BitmapBackend(
        defVideo, listOf(STATIC), listOf(TAPES), grounding, Bitmap.Spec(defVideo.resolution, BGR24_REPRESENTATION)
    ).use { backend ->
        for (frameIdx in 0..<defVideo.numFrames)
            backend.materializeFrame(frameIdx)!!.use { bitmap -> yield(bgr24BitmapToImage(bitmap)) }
    }
}


private fun <S : Style> renderStyleSettings(
    settings: List<StyleSetting<S, *>>,
    styles: List<S>,
    buildStyling: ((S) -> Styling?)?,
    augmentStyling: ((Styling) -> Styling)? = null,
    pictureLoaders: Collection<Picture.Loader> = emptyList(),
    tapes: Collection<Tape> = emptyList()
) = sequence<BufferedImage> {
    val styleClass = styles[0].javaClass

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
            styleClass, latent = when {
                PopupStyle::class.java.isAssignableFrom(styleClass) ->
                    setOf(PopupStyle::volatile.st()) as Set<StyleSetting<in S, *>>
                LayerStyle::class.java.isAssignableFrom(styleClass) ->
                    setOf(LayerStyle::name.st(), LayerStyle::collapsed.st()) as Set<StyleSetting<in S, *>>
                else -> emptySet()
            }
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
    formAdjuster.updatePictureLoaders(pictureLoaders)
    formAdjuster.updateTapes(tapes)
    sleep(500)
    edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }

    // Iterate through the list of styles() and capture a form screenshot for each style.
    for (style in styles) {
        curStyling = buildStyling?.invoke(style) ?: when (style) {
            is Global -> Styling(style, pl(), pl(), pl(), pl(), pl())
            is PageStyle -> Styling(PRESET_GLOBAL, pl(style), pl(), pl(), pl(), pl())
            is ContentStyle -> Styling(PRESET_GLOBAL, pl(), pl(style), pl(), pl(), pl())
            is LetterStyle -> Styling(PRESET_GLOBAL, pl(), pl(), pl(style), pl(), pl())
            is PictureStyle -> Styling(PRESET_GLOBAL, pl(), pl(), pl(), pl(style), pl())
            is TapeStyle -> Styling(PRESET_GLOBAL, pl(), pl(), pl(), pl(), pl(style))
            else -> throw IllegalStateException()
        }
        if (augmentStyling != null)
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
        val settImg = BufferedImage(compBounds.width, compBounds.height, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.translate(-compBounds.x, -compBounds.y)
            printWithPopups(form, g2)
        }
        yield(settImg)
    }

    edt { frame.dispose() }
}


private fun renderTimeline(
    imgW: Int, frames: Int,
    leftMargin: Int, rightMargin: Int, leftHatch: Int, rightHatch: Int, leftFade: Int, rightFade: Int
) = sequence<BufferedImage> {
    fun roundRect(x: Int, y: Int, w: Int, h: Int, arc: Int) = RoundRectangle2D.Double(
        x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), arc.toDouble(), arc.toDouble()
    )

    val stopperH = 40
    val stopper = Path2D.Double().apply {
        moveTo(0.0, 0.0)
        lineTo(10.0, 0.0)
        lineTo(10.0, 6.0)
        lineTo(2.0, 14.0)
        lineTo(2.0, stopperH.toDouble())
        lineTo(0.0, stopperH.toDouble())
        closePath()
    }
    val playHead = Path2D.Double().apply {
        moveTo(-4.0, 0.0)
        lineTo(5.0, 0.0)
        lineTo(5.0, 6.0)
        lineTo(1.0, 10.0)
        lineTo(1.0, stopperH.toDouble())
        lineTo(0.0, stopperH.toDouble())
        lineTo(0.0, 10.0)
        lineTo(-4.0, 6.0)
        closePath()
    }
    val hatchTex = BufferedImage(5, 5, BufferedImage.TYPE_4BYTE_ABGR).withG2 { g2 ->
        g2.setHighQuality()
        g2.color = PALETTE_GRAY_COLOR
        g2.drawLine(-1, 5, 5, -1)
    }

    val imgH = 50
    val timelineW = frames
    val timelineX = (imgW - timelineW) / 2
    val timelineY = 10
    val clipY = 16
    val clipH = 20
    val clipArc = 5

    val baseImg = BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
        g2.setHighQuality()

        g2.color = UIManager.getColor("Panel.background")
        g2.fillRect(0, 0, imgW, imgH)

        g2.translate(timelineX, timelineY)

        // Hatch
        g2.paint = TexturePaint(hatchTex, Rectangle(hatchTex.width, hatchTex.height))
        if (leftHatch > 0)
            g2.fill(Rectangle(0, clipY, leftHatch, clipH))
        if (rightHatch > 0)
            g2.fill(Rectangle(timelineW - rightHatch, clipY, rightHatch, clipH))

        // Clip
        g2.color = PALETTE_BLUE_COLOR
        g2.fill(roundRect(leftMargin, clipY, timelineW - leftMargin - rightMargin, clipH, clipArc))

        // Fades
        g2.color = Color.WHITE
        if (leftFade > 0) {
            val rect = roundRect(leftMargin, clipY, leftFade, clipH - 1, clipArc)
            g2.draw(rect)
            g2.clip(rect)
            g2.drawLine(leftMargin, clipY + clipH, leftMargin + leftFade, clipY)
            g2.clip = null
        }
        if (rightFade > 0) {
            val rect = roundRect(timelineW - rightMargin - rightFade, clipY, rightFade, clipH - 1, clipArc)
            g2.draw(rect)
            g2.clip(rect)
            g2.drawLine(timelineW - rightMargin - rightFade, clipY, timelineW - rightMargin, clipY + clipH)
            g2.clip = null
        }

        // Stoppers
        g2.color = PALETTE_GRAY_COLOR
        g2.scale(-1.0, 1.0)
        g2.fill(stopper)
        g2.scale(-1.0, 1.0)
        g2.translate(timelineW, 0)
        g2.fill(stopper)
    }

    for (frame in 0..<frames)
        yield(BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.drawImage(baseImg, 0, 0, null)
            // Play head
            g2.color = PALETTE_RED_COLOR
            g2.translate(timelineX + frame, timelineY)
            g2.fill(playHead)
        })
}


private fun stackImages(
    vararg rows: Iterable<BufferedImage>, extendX: List<Int> = emptyList(), extendY: List<Int> = emptyList()
) = sequence<BufferedImage> {
    fun dim(img: BufferedImage, rowIdx: Int) = Dimension(
        img.width + 2 * (extendX.getOrElse(rowIdx) { 0 }),
        img.height + 2 * (extendY.getOrElse(rowIdx) { 0 })
    )

    rows.filterIsInstance<Collection<*>>().let { cRows -> require(cRows.all { row -> row.size == cRows[0].size }) }
    val fixedRowBounds = rows.mapIndexed { rowIdx, row ->
        if (row !is Collection<*>) null else row.fold(Dimension()) { d1, img ->
            val d2 = dim(img, rowIdx)
            Dimension(max(d1.width, d2.width), max(d1.height, d2.height))
        }
    }
    val rowIters = rows.map { row -> row.iterator() }
    while (true) {
        val rowImgs = rowIters.map { iter -> if (iter.hasNext()) iter.next() else return@sequence }
        val rowBounds = fixedRowBounds.mapIndexed { rowIdx, bounds -> bounds ?: dim(rowImgs[rowIdx], rowIdx) }
        val imgW = rowBounds.maxOf { it.width }
        val imgH = rowBounds.sumOf { it.height }
        yield(BufferedImage(imgW, imgH, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            var y = 0
            for ((rowIdx, img) in rowImgs.withIndex()) {
                g2.color = Color(img.getRGB(0, 0))
                g2.fillRect(0, y, imgW, rowBounds[rowIdx].height)
                val x = (imgW - rowBounds[rowIdx].width) / 2
                g2.drawImage(img, x + extendX.getOrElse(rowIdx) { 0 }, y + extendY.getOrElse(rowIdx) { 0 }, null)
                y += rowBounds[rowIdx].height
            }
        })
    }
}


/* ***********************************************
   ********** INTERNAL IMAGE CONVERTERS **********
   *********************************************** */


private fun bgr24BitmapToImage(bitmap: Bitmap): BufferedImage {
    require(bitmap.spec.representation == BGR24_REPRESENTATION)
    val (w, h) = bitmap.spec.resolution
    val data = bitmap.getB(w * 3)
    val dataBuffer = DataBufferByte(data, data.size)
    val raster = Raster.createInterleavedRaster(dataBuffer, w, h, w * 3, 3, intArrayOf(2, 1, 0), null)
    val cs = AWTColorSpace.getInstance(AWTColorSpace.CS_sRGB)
    val cm = ComponentColorModel(cs, intArrayOf(8, 8, 8), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE)
    return BufferedImage(cm, raster, false, null)
}


private fun defImageToImage(
    grounding: Color4f, layers: List<DeferredImage.Layer>, defImg: DeferredImage
): BufferedImage {
    val res = Resolution(defImg.width.roundToInt(), defImg.height.resolve().roundToInt())
    val canvasRepresentation = Canvas.compatibleRepresentation(
        ColorSpace.of(ColorSpace.Primaries.BT709, ColorSpace.Transfer.BLENDING)
    )
    val canvasBitmap = Bitmap.allocate(Bitmap.Spec(res, canvasRepresentation))
    val bgr24Bitmap = Bitmap.allocate(Bitmap.Spec(res, BGR24_REPRESENTATION))
    Canvas.forBitmap(canvasBitmap).use { canvas ->
        canvas.fill(Canvas.Shader.Solid(grounding))
        defImg.materialize(canvas, cache = null, layers)
    }
    BitmapConverter.convert(canvasBitmap, bgr24Bitmap, promiseOpaque = true)
    val img = bgr24BitmapToImage(bgr24Bitmap)
    canvasBitmap.close()
    bgr24Bitmap.close()
    return img
}
