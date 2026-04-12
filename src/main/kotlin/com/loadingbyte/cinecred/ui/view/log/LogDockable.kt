package com.loadingbyte.cinecred.ui.view.log

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.comms.DockableId
import com.loadingbyte.cinecred.ui.comms.LogCtrlComms
import com.loadingbyte.cinecred.ui.comms.LogViewComms
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import com.loadingbyte.cinecred.ui.helper.WordWrapCellRenderer
import com.loadingbyte.cinecred.ui.helper.icon
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.min


class LogDockable(logCtrl: LogCtrlComms) :
    DockingFrame.Dockable(
        dockableId = DockableId.LOG.name,
        title = l10n("ui.log.title"),
        icon = DockableId.LOG.icon
    ),
    LogViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedLogTable: JTable
    // =========================================

    private val logTableModel = LogTableModel()

    init {
        logCtrl.registerView(this)

        val logTable = JTable(logTableModel).apply {
            isFocusable = false
            // Disable cell selection because it looks weird with the custom WordWrapCellRenderer.
            cellSelectionEnabled = false
            // Prevent the user from dragging the columns around.
            tableHeader.reorderingAllowed = false
            // Lock the widths of the severity and row columns, initialize the widths of the filename, spreadsheet name,
            // column name, and cell columns with a small minimum width, and initially distribute all remaining width
            // to the message column.
            columnModel.getColumn(0).apply { minWidth = 24; maxWidth = 24 }
            columnModel.getColumn(1).apply { minWidth = 64; width = 64 }
            columnModel.getColumn(2).apply { minWidth = 64; width = 64 }
            columnModel.getColumn(3).apply { minWidth = 48; maxWidth = 48 }
            columnModel.getColumn(4).apply { minWidth = 96; width = 96 }
            columnModel.getColumn(5).apply { minWidth = 96; width = 96 }
            tableHeader.resizingColumn = columnModel.getColumn(6)
            // Center the filename, spreadsheet name, record number, column name, and cell value columns.
            for (colIdx in 1..5)
                columnModel.getColumn(colIdx).cellRenderer =
                    DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.CENTER }
            // Allow for word wrapping and HTML display in the message column.
            columnModel.getColumn(6).cellRenderer = WordWrapCellRenderer(allowHtml = true, shrink = true)
        }

        val scrollPane = JScrollPane(logTable).apply {
            val bw = UIManager.getInt("Component.borderWidth")
            border = BorderFactory.createMatteBorder(bw, 0, 0, 0, UIManager.getColor("Component.borderColor"))
        }

        layout = BorderLayout()
        add(scrollPane, BorderLayout.CENTER)

        @Suppress("DEPRECATION")
        leakedLogTable = logTable
    }

    override fun getMinimumSize(): Dimension =
        if (isMinimumSizeSet) super.getMinimumSize() else Dimension(450, 50)


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun updateLog(log: List<ParserMsg>) {
        logTableModel.log = log
    }


    private class LogTableModel : AbstractTableModel() {

        var log: List<ParserMsg> = emptyList()
            set(value) {
                val oldRows = field.size
                val newRows = value.size
                val minRows = min(oldRows, newRows)
                val firstUpdatedRow = field.zip(value).indexOfFirst { (old, new) -> old != new }.coerceAtLeast(0)
                field = value
                if (firstUpdatedRow < minRows) fireTableRowsUpdated(firstUpdatedRow, minRows - 1)
                if (newRows < oldRows) fireTableRowsDeleted(newRows, oldRows - 1)
                if (newRows > oldRows) fireTableRowsInserted(oldRows, newRows - 1)
            }

        override fun getRowCount() = log.size
        override fun getColumnCount() = 7

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> ""
            1 -> l10n("file")
            2 -> l10n("ui.edit.sheet")
            3 -> l10n("ui.edit.record")
            4 -> l10n("ui.edit.column")
            5 -> l10n("ui.edit.value")
            6 -> l10n("ui.edit.message")
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> log[rowIdx].severity.icon
            1 -> log[rowIdx].fileName ?: ""
            2 -> log[rowIdx].spreadsheetName ?: ""
            3 -> log[rowIdx].recordNo?.plus(1) ?: ""
            4 -> log[rowIdx].colHeader ?: ""
            5 -> log[rowIdx].cellValue ?: ""
            6 -> log[rowIdx].msg
            else -> throw IllegalArgumentException()
        }

    }

}
