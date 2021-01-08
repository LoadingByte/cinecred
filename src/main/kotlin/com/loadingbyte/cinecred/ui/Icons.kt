package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.drawer.setHighQuality
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.ext.awt.image.GraphicsUtil
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
    listOf(16, 20, 32, 40, 48, 64, 128, 256).map { size ->
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = GraphicsUtil.createGraphics(img)
        try {
            g2.setHighQuality()
            val scale = size / ctx.documentSize.width
            g2.transform(AffineTransform.getScaleInstance(scale, scale))
            logo.paint(g2)
        } finally {
            g2.dispose()
        }
        img
    }
}


val ADD_ICON = SVGIcon.load("/icons/add.svg")
val CANCEL_ICON = SVGIcon.load("/icons/cancel.svg")
val DELIVER_ICON = SVGIcon.load("/icons/deliver.svg")
val EDIT_ICON = SVGIcon.load("/icons/edit.svg")
val EYE_ICON = SVGIcon.load("/icons/eye.svg")
val FILMSTRIP_ICON = SVGIcon.load("/icons/filmstrip.svg")
val FOLDER_ICON = SVGIcon.load("/icons/folder.svg")
val GLOBE_ICON = SVGIcon.load("/icons/globe.svg")
val LAYOUT_ICON = SVGIcon.load("/icons/layout.svg")
val PAGE_ICON = SVGIcon.load("/icons/page.svg")
val PLAY_ICON = SVGIcon.load("/icons/play.svg")
val REMOVE_ICON = SVGIcon.load("/icons/remove.svg")
val RESET_ICON = SVGIcon.load("/icons/reset.svg")
val SAVE_ICON = SVGIcon.load("/icons/save.svg")


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
    FlatAbstractIcon(width, height, null) {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        svg.paint(g2)
    }

    companion object {
        fun load(name: String): SVGIcon {
            val (svg, ctx) = loadSVGResource(name)
            return SVGIcon(svg, ctx.documentSize.width.roundToInt(), ctx.documentSize.height.roundToInt())
        }
    }

}


class DualSVGIcon constructor(private val left: SVGIcon, private val right: SVGIcon) :
    FlatAbstractIcon(left.width + 4 + right.width, max(left.height, right.height), null) {
    override fun paintIcon(c: Component, g2: Graphics2D) {
        left.svg.paint(g2)
        val prevTransform = g2.transform
        g2.translate(left.width + 4, 0)
        right.svg.paint(g2)
        g2.transform = prevTransform
    }
}
