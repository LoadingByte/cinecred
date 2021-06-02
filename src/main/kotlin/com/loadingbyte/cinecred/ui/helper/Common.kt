package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.SystemInfo
import com.formdev.flatlaf.util.UIScale
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Rectangle2D
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.table.TableCellRenderer


val PALETTE_RED: Color = Color.decode("#C75450")
val PALETTE_GREEN: Color = Color.decode("#499C54")


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


fun JComponent.setTableCellBackground(table: JTable, rowIdx: Int) {
    background = if (rowIdx % 2 == 0) table.background else UIManager.getColor("Table.alternateRowColor")
}


class WordWrapCellRenderer(allowHtml: Boolean = false) : TableCellRenderer {

    private val comp = when (allowHtml) {
        false -> newLabelTextArea()
        true -> JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
        }
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
    ) = comp.apply {
        text = value as String
        setSize(table.columnModel.getColumn(colIdx).width, preferredSize.height)
        if (table.getRowHeight(rowIdx) < preferredSize.height)
            table.setRowHeight(rowIdx, preferredSize.height)
        setTableCellBackground(table, rowIdx)
    }

}


class CustomToStringListCellRenderer<E>(
    private val itemClass: Class<E>,
    private val toString: (E) -> String
) : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = value?.let { toString(itemClass.cast(it)) } ?: ""
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
            val g2 = g as Graphics2D
            val fontMetrics = c.getFontMetrics(list.font)

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


class CustomToStringKeySelectionManager<E>(
    private val itemClass: Class<E>,
    private val toString: (E) -> String
) : JComboBox.KeySelectionManager {

    private var lastTime = 0L
    private var prefix = ""

    override fun selectionForKey(key: Char, model: ComboBoxModel<*>): Int {
        var startIdx = model.getElements().indexOfFirst { it === model.selectedItem }

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

    private fun ComboBoxModel<*>.getElements(startIdx: Int = 0, endIdx: Int = -1): List<E> = mutableListOf<E>().apply {
        val endIdx2 = if (endIdx == -1) size else endIdx
        for (idx in startIdx until endIdx2)
            add(itemClass.cast(getElementAt(idx)))
    }

}


fun Window.setupToSnapToSide(onScreen: GraphicsConfiguration, rightSide: Boolean) {
    val screenBounds = onScreen.bounds
    val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(onScreen)

    // We want to determine the bounds of the window.
    // Start with the bounds of the screen on which it should reside.
    // Subtract the screen's insets (mainly caused by the taskbar) from the bounds.
    // Also, the window should only occupy half of the screen horizontally.
    val winBounds = Rectangle(
        screenBounds.x + screenInsets.left,
        screenBounds.y + screenInsets.top,
        (screenBounds.width - screenInsets.left - screenInsets.right) / 2,
        screenBounds.height - screenInsets.top - screenInsets.bottom
    )

    // If the window should snap to the right side, move its x coordinate.
    if (rightSide)
        winBounds.x += winBounds.width

    // Apply the computed window bounds.
    bounds = winBounds

    // On Windows 10, windows have a thick invisible border which resides inside the window bounds.
    // If we do nothing about it, the window is not flush with the sides of the screen, but there's a thick strip
    // of empty space between the window and the left, right, and bottom sides of the screen.
    // To fix this, we find the exact thickness of the border by querying the window's insets (which we can only do
    // after the window has been opened, hence the listener) and add those insets to the window bounds on the left,
    // right, and bottom sides.
    if (SystemInfo.isWindows_10_orLater)
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                val winInsets = insets
                setBounds(
                    winBounds.x - winInsets.left,
                    winBounds.y,
                    winBounds.width + winInsets.left + winInsets.right,
                    winBounds.height + winInsets.bottom
                )
            }
        })
}


fun trySetTaskbarIconBadge(badge: Int) {
    if (Taskbar.Feature.ICON_BADGE_NUMBER.isSupported)
        taskbar!!.setIconBadge(if (badge == 0) null else badge.toString())
}

fun trySetTaskbarProgress(window: Window, percent: Int) {
    if (Taskbar.Feature.PROGRESS_VALUE.isSupported)
        taskbar!!.setProgressValue(percent)
    if (Taskbar.Feature.PROGRESS_VALUE_WINDOW.isSupported)
        taskbar!!.setWindowProgressValue(window, percent)
}

fun tryRequestUserAttentionInTaskbar(window: Window) {
    if (Taskbar.Feature.USER_ATTENTION.isSupported)
        taskbar!!.requestUserAttention(true, false)
    if (Taskbar.Feature.USER_ATTENTION_WINDOW.isSupported)
        taskbar!!.requestWindowUserAttention(window)
}

private val taskbar = if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else null

private val Taskbar.Feature.isSupported
    get() = taskbar != null && taskbar.isSupported(this)
