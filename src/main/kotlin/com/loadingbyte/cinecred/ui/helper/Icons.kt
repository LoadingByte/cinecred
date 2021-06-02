package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.loadingbyte.cinecred.common.*
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.FilteredImageSource
import java.awt.image.ImageFilter
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.roundToInt


const val ICON_ICON_GAP = 4

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
val PREFERENCES_ICON = SVGIcon.load("/icons/preferences.svg")
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


class SVGIcon private constructor(val svg: GraphicsNode, val width: Int, val height: Int, val isDisabled: Boolean) :
    FlatAbstractIcon(width, height, null), FlatLaf.DisabledIconProvider {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        if (!isDisabled)
            svg.paint(g2)
        else {
            // Note: Custom composites are not universally supported. Once they are, we can also use the gray filter
            // from inside a custom composite. For now, we first render the icon to an image, then apply the gray
            // filter to that image, and finally draw the filtered image.
            val filter = UIManager.get("Component.grayFilter") as ImageFilter
            // We assume that scaleX and scaleY are always identical.
            val scaling = g2.transform.scaleX
            // Draw the icon to an image.
            val img = gCfg.createCompatibleImage(
                (scaling * width).roundToInt(), (scaling * height).roundToInt(), Transparency.TRANSLUCENT
            ).withG2 { g2i ->
                g2i.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2i.scale(scaling, scaling)
                svg.paint(g2i)
            }
            // Filter the image to make it gray.
            val grayImg = Toolkit.getDefaultToolkit().createImage(FilteredImageSource(img.source, filter))
            // Draw the image into the original graphics object.
            g2.preserveTransform {
                g2.scale(1.0 / scaling, 1.0 / scaling)
                g2.drawImage(grayImg, 0, 0, null)
            }
        }
    }

    override fun getDisabledIcon() = SVGIcon(svg, width, height, true)

    companion object {
        fun load(name: String): SVGIcon {
            val (svg, ctx) = loadSVGResource(name)
            return SVGIcon(svg, ctx.documentSize.width.roundToInt(), ctx.documentSize.height.roundToInt(), false)
        }
    }

}


class DualSVGIcon constructor(private val left: SVGIcon, private val right: SVGIcon) :
    FlatAbstractIcon(left.width + ICON_ICON_GAP + right.width, max(left.height, right.height), null),
    FlatLaf.DisabledIconProvider {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        g2.preserveTransform {
            left.svg.paint(g2)
            g2.translate(left.width + ICON_ICON_GAP, 0)
            right.svg.paint(g2)
        }
    }

    override fun getDisabledIcon() = DualSVGIcon(left.disabledIcon, right.disabledIcon)

}
