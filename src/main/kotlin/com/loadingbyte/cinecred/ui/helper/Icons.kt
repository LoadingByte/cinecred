package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.preserveTransform
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.Component
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt


val WINDOW_ICON_IMAGES = run {
    val (logo, ctx) = loadSVGResource("/logo.svg")
    listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { size ->
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).withG2 { g2 ->
            g2.setHighQuality()
            val scale = size / ctx.documentSize.width
            g2.transform(AffineTransform.getScaleInstance(scale, scale))
            logo.paint(g2)
        }
    }
}


val X_16_TO_9_ICON = SVGIcon.load("/icons/16to9.svg")
val X_4_TO_3_ICON = SVGIcon.load("/icons/4to3.svg")
val ADD_ICON = SVGIcon.load("/icons/add.svg")
val CANCEL_ICON = SVGIcon.load("/icons/cancel.svg")
val DELIVER_ICON = SVGIcon.load("/icons/deliver.svg")
val DUPLICATE_ICON = SVGIcon.load("/icons/duplicate.svg")
val EDIT_ICON = SVGIcon.load("/icons/edit.svg")
val EYE_ICON = SVGIcon.load("/icons/eye.svg")
val FILMSTRIP_ICON = SVGIcon.load("/icons/filmstrip.svg")
val FOLDER_ICON = SVGIcon.load("/icons/folder.svg")
val GLOBE_ICON = SVGIcon.load("/icons/globe.svg")
val LAYOUT_ICON = SVGIcon.load("/icons/layout.svg")
val LETTERS_ICON = SVGIcon.load("/icons/letters.svg")
val PAGE_ICON = SVGIcon.load("/icons/page.svg")
val PAUSE_ICON = SVGIcon.load("/icons/pause.svg")
val PLAY_ICON = SVGIcon.load("/icons/play.svg")
val REDO_ICON = SVGIcon.load("/icons/redo.svg")
val REMOVE_ICON = SVGIcon.load("/icons/remove.svg")
val RESET_ICON = SVGIcon.load("/icons/reset.svg")
val SAVE_ICON = SVGIcon.load("/icons/save.svg")
val TRASH_ICON = SVGIcon.load("/icons/trash.svg")
val UNDO_ICON = SVGIcon.load("/icons/undo.svg")
val UNIFORM_SAFE_AREAS_ICON = SVGIcon.load("/icons/uniformSafeAreas.svg")
val ZOOM_ICON = SVGIcon.load("/icons/zoom.svg")


val SEVERITY_ICON = mapOf(
    Severity.INFO to SVGIcon.load("/icons/info.svg"),
    Severity.WARN to SVGIcon.load("/icons/warn.svg"),
    Severity.ERROR to SVGIcon.load("/icons/error.svg")
)


private fun loadSVGResource(name: String): Pair<GraphicsNode, BridgeContext> {
    val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        .createDocument(null, SVGIcon::class.java.getResourceAsStream(name)) as SVGOMDocument
    val ctx = BridgeContext(UserAgentAdapter())
    return Pair(GVTBuilder().build(ctx, doc), ctx)
}


class SVGIcon private constructor(val svg: GraphicsNode, val width: Int, val height: Int) :
    FlatAbstractIcon(width, height, null), FlatLaf.DisabledIconProvider {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        // TODO: Custom composites are not universally supported. Once they are, use the gray filter to paint disabled icons differently.
        svg.paint(g2)
    }

    override fun getDisabledIcon() = SVGIcon(svg, width, height)


    companion object {

        /*private val DISABLED_COMPOSITE = run {
            val filter = UIManager.get("Component.grayFilter") as RGBImageFilter
            RGBFunctionComposite { rgb -> filter.filterRGB(0, 0, rgb) }
        }*/

        fun load(name: String): SVGIcon {
            val (svg, ctx) = loadSVGResource(name)
            return SVGIcon(svg, ctx.documentSize.width.roundToInt(), ctx.documentSize.height.roundToInt())
        }

    }


    /*private class RGBFunctionComposite(val func: (Int) -> Int) : Composite {
        override fun createContext(
            srcColorModel: ColorModel,
            dstColorModel: ColorModel,
            hints: RenderingHints
        ) = object : CompositeContext {
            override fun dispose() {}
            override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
                // Note: This method assumes that all color models are RGB.
                val width = min(src.width, dstIn.width)
                val height = min(src.height, dstIn.height)
                // Create buffers.
                val srcPixels = IntArray(width)
                val dstInPixels = IntArray(width)
                val dstOutPixels = IntArray(width)
                // Compose each pixel row.
                for (y in 0 until height) {
                    src.getDataElements(0, y, srcPixels)
                    dstIn.getDataElements(0, y, dstInPixels)
                    for (x in 0 until width)
                        dstOutPixels[x] = func(dstInPixels[x])
                    dstOut.setDataElements(0, y, dstOutPixels)
                }
            }
        }
    }*/

}


class DualSVGIcon constructor(private val left: SVGIcon, private val right: SVGIcon) :
    FlatAbstractIcon(left.width + 4 + right.width, max(left.height, right.height), null), FlatLaf.DisabledIconProvider {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        g2.preserveTransform {
            left.svg.paint(g2)
            g2.translate(left.width + 4, 0)
            right.svg.paint(g2)
        }
    }

    override fun getDisabledIcon() = DualSVGIcon(left.disabledIcon, right.disabledIcon)

}
