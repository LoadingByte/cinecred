package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.Severity
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.*
import java.awt.geom.Rectangle2D
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
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


open class WordWrapCellRenderer : TableCellRenderer {

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


abstract class LabeledListCellRenderer : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val string = value?.let(::toString) ?: ""
        val cell = super.getListCellRendererComponent(list, string, index, isSelected, cellHasFocus)
        val labelLines = getLabelLines(index)
        if (labelLines.isNotEmpty())
            (cell as JComponent).border = CompoundBorder(LabelListCellBorder(list, labelLines), cell.border)
        return cell
    }

    abstract fun toString(value: Any): String
    abstract fun getLabelLines(index: Int): List<String>

    /**
     * To be used in list cell components. Displays a separating label above the component.
     * Alternatively displays multiple separating labels above the component, interspersed by
     * non-separating labels. This can be used to show that some list category is empty.
     *
     * Inspired from here:
     * https://github.com/JFormDesigner/FlatLaf/blob/master/flatlaf-demo/src/main/java/com/formdev/flatlaf/demo/intellijthemes/ListCellTitledBorder.java
     */
    private class LabelListCellBorder(private val list: JList<*>, val lines: List<String>) : Border {

        override fun isBorderOpaque() = true
        override fun getBorderInsets(c: Component) =
            Insets(lines.size * c.getFontMetrics(list.font).height, 0, 0, 0)

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val fontMetrics = c.getFontMetrics(list.font)
            val g2 = g.create() as Graphics2D
            try {
                // Draw the list background.
                g2.color = list.background
                g2.fillRect(x, y, width, lines.size * fontMetrics.height)

                FlatUIUtils.setRenderingHints(g2)
                g2.color = UIManager.getColor("Label.disabledForeground")

                for ((line, text) in lines.withIndex()) {
                    val lineY = y + line * fontMetrics.height
                    val textWidth = fontMetrics.stringWidth(text)
                    // Draw the centered string.
                    FlatUIUtils.drawString(list, g2, text, x + (width - textWidth) / 2, lineY + fontMetrics.ascent)
                    // On even lines, draw additional separator lines.
                    if (line % 2 == 0) {
                        val sepGap = UIScale.scale(4f)
                        val sepWidth = (width - textWidth) / 2f - 2f * sepGap
                        if (sepWidth > 0) {
                            val sepY = lineY + fontMetrics.height / 2f
                            val sepHeight = UIScale.scale(1f)
                            g2.fill(Rectangle2D.Float(x + sepGap, sepY, sepWidth, sepHeight))
                            g2.fill(Rectangle2D.Float((x + width - sepGap - sepWidth), sepY, sepWidth, sepHeight))
                        }
                    }
                }
            } finally {
                g2.dispose()
            }
        }

    }

}
