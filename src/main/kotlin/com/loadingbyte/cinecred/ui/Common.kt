package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.loadingbyte.cinecred.Severity
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics2D
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.table.TableCellRenderer
import kotlin.math.max
import kotlin.math.roundToInt


fun String.ensureEndsWith(suffixes: List<String>, ignoreCase: Boolean = true) =
    if (suffixes.isEmpty() || suffixes.any { endsWith(it, ignoreCase) }) this else this + suffixes[0]

fun String.ensureDoesntEndWith(suffixes: List<String>, ignoreCase: Boolean = true): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
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
val REFRESH_ICON = SVGIcon.load("/icons/refresh.svg")
val REMOVE_ICON = SVGIcon.load("/icons/remove.svg")
val RESET_ICON = SVGIcon.load("/icons/reset.svg")
val SAVE_ICON = SVGIcon.load("/icons/save.svg")


val SEVERITY_ICON = mapOf(
    Severity.INFO to SVGIcon.load("/icons/info.svg"),
    Severity.WARN to SVGIcon.load("/icons/warn.svg"),
    Severity.ERROR to SVGIcon.load("/icons/error.svg")
)


class SVGIcon private constructor(val svg: GraphicsNode, val width: Int, val height: Int) :
    FlatAbstractIcon(width, height, null) {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        svg.paint(g2)
    }

    companion object {
        fun load(name: String): SVGIcon {
            val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
                .createDocument(null, SVGIcon::class.java.getResourceAsStream(name)) as SVGOMDocument
            val ctx = BridgeContext(UserAgentAdapter())
            val svg = GVTBuilder().build(ctx, doc)
            return SVGIcon(svg, ctx.documentSize.width.roundToInt(), ctx.documentSize.height.roundToInt())
        }
    }

}


class DualSVGIcon constructor(val left: SVGIcon, val right: SVGIcon) :
    FlatAbstractIcon(left.width + 4 + right.width, max(left.height, right.height), null) {
    override fun paintIcon(c: Component, g2: Graphics2D) {
        left.svg.paint(g2)
        val prevTransform = g2.transform
        g2.translate(left.width + 4, 0)
        right.svg.paint(g2)
        g2.transform = prevTransform
    }
}


fun newLabelTextArea() = JTextArea().apply {
    background = null
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    // Without setting an explicit minimum width, the component would never ever again shrink once it has grown.
    // This would of course lead to trouble when first enlarging and then shrinking a container which contains
    // a label text area. By setting an explicit minimum width, we turn off this undesired behavior.
    minimumSize = Dimension(0, 0)
}


class WordWrapCellRenderer : TableCellRenderer {

    private val textArea = newLabelTextArea()

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
    ) = textArea.apply {
        text = value as String
        setSize(table.columnModel.getColumn(colIdx).width, preferredSize.height)
        if (table.getRowHeight(rowIdx) != preferredSize.height)
            table.setRowHeight(rowIdx, preferredSize.height)
    }

}
