package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.Severity
import java.awt.Dimension
import java.awt.Image
import javax.swing.*
import javax.swing.table.TableCellRenderer


fun String.ensureEndsWith(suffixes: List<String>, ignoreCase: Boolean = true) =
    if (suffixes.isEmpty() || suffixes.any { endsWith(it, ignoreCase) }) this else this + suffixes[0]

fun String.ensureDoesntEndWith(suffixes: List<String>, ignoreCase: Boolean = true): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
}


val DIR_ICON: Icon = UIManager.getIcon("FileView.directoryIcon")
val COMPUTER_ICON: Icon = UIManager.getIcon("FileView.computerIcon")
val FLOPPY_ICON: Icon = UIManager.getIcon("FileView.floppyDriveIcon")
val UP_FOLDER_ICON: Icon = UIManager.getIcon("FileChooser.upFolderIcon")

val GLOBAL_ICON: Icon = UIManager.getIcon("FileView.hardDriveIcon")
val PAGE_ICON: Icon = UIManager.getIcon("InternalFrame.icon")
val CONTENT_ICON: Icon = UIManager.getIcon("Slider.verticalThumbIcon")

val SEVERITY_ICON = mapOf(
    Severity.INFO to (UIManager.getIcon("OptionPane.informationIcon") as ImageIcon).rescaled(16, 16),
    Severity.WARN to (UIManager.getIcon("OptionPane.warningIcon") as ImageIcon).rescaled(16, 16),
    Severity.ERROR to (UIManager.getIcon("OptionPane.errorIcon") as ImageIcon).rescaled(16, 16)
)

private fun ImageIcon.rescaled(width: Int, height: Int) =
    ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH))


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
