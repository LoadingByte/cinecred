package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.withNewG2
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Insets
import java.awt.geom.Rectangle2D
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.table.TableCellRenderer


fun String.ensureEndsWith(suffixes: List<String>, ignoreCase: Boolean = true) =
    if (suffixes.isEmpty() || suffixes.any { endsWith(it, ignoreCase) }) this else this + suffixes[0]

fun String.ensureDoesntEndWith(suffixes: List<String>, ignoreCase: Boolean = true): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
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


@Suppress("UNCHECKED_CAST")
class CustomToStringListCellRenderer<E>(private val toString: (E) -> String) : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = value?.let { toString(it as E) } ?: ""
        return this
    }

}


class LabeledListCellRenderer<E>(
    private val wrapped: ListCellRenderer<in E>,
    private val groupSpacing: Int = 0,
    private val getLabelLines: (Int) -> List<String>
) : ListCellRenderer<E> {

    override fun getListCellRendererComponent(
        list: JList<out E>, value: E?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val cell = wrapped.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        // Show the label lines only in the popup menu, but not in the combo box.
        if (index != -1) {
            val labelLines = getLabelLines(index)
            if (labelLines.isNotEmpty())
                (cell as JComponent).border = CompoundBorder(LabelListCellBorder(list, labelLines), cell.border)
        }
        return cell
    }

    /**
     * To be used in list cell components. Displays a separating label above the component.
     * Alternatively displays multiple separating labels above the component, interspersed by
     * non-separating labels. This can be used to show that some list category is empty.
     *
     * Inspired from here:
     * https://github.com/JFormDesigner/FlatLaf/blob/master/flatlaf-demo/src/main/java/com/formdev/flatlaf/demo/intellijthemes/ListCellTitledBorder.java
     */
    private inner class LabelListCellBorder(private val list: JList<*>, val lines: List<String>) : Border {

        override fun isBorderOpaque() = true
        override fun getBorderInsets(c: Component) =
            Insets(lines.size * c.getFontMetrics(list.font).height + (lines.size - 1) / 2 * groupSpacing, 0, 0, 0)

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val fontMetrics = c.getFontMetrics(list.font)

            g.withNewG2 { g2 ->
                // Draw the list background.
                g2.color = list.background
                g2.fillRect(x, y, width, getBorderInsets(c).top)

                FlatUIUtils.setRenderingHints(g2)
                g2.color = UIManager.getColor("Label.disabledForeground")
                g2.font = list.font

                for ((line, text) in lines.withIndex()) {
                    val lineY = y + line * fontMetrics.height + line / 2 * groupSpacing
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
            }
        }

    }

}


@Suppress("UNCHECKED_CAST")
class CustomToStringKeySelectionManager<E>(private val toString: (E) -> String) : JComboBox.KeySelectionManager {

    private var lastTime = 0L
    private var prefix = ""

    override fun selectionForKey(key: Char, model: ComboBoxModel<*>): Int {
        var startIdx = (model as ComboBoxModel<E>).getElements().indexOfFirst { it === model.selectedItem }

        val timeFactor = UIManager.get("ComboBox.timeFactor") as Long? ?: 1000L
        val currTime = System.currentTimeMillis()
        if (currTime - lastTime < timeFactor)
            if (prefix.length == 1 && key == prefix[0])
                startIdx++
            else
                prefix += key
        else {
            startIdx++
            prefix = key.toString()
        }
        lastTime = currTime

        fun startsWith(elem: E) = toString(elem).startsWith(prefix, ignoreCase = true)
        val foundIdx = model.getElements(startIdx).indexOfFirst(::startsWith)
        return if (foundIdx != -1)
            startIdx + foundIdx
        else
            model.getElements(0, startIdx).indexOfFirst(::startsWith)
    }

    private fun ComboBoxModel<E>.getElements(startIdx: Int = 0, endIdx: Int = -1) = sequence<E> {
        val endIdx2 = if (endIdx == -1) size else endIdx
        for (idx in startIdx until endIdx2)
            yield(getElementAt(idx))
    }

}
