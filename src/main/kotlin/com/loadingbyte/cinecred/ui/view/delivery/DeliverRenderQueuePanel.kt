package com.loadingbyte.cinecred.ui.view.delivery

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.MAX_RENDER_PROGRESS
import com.loadingbyte.cinecred.ui.comms.DeliveryCtrlComms
import com.loadingbyte.cinecred.ui.comms.DeliveryViewComms
import com.loadingbyte.cinecred.ui.comms.RenderJobInfo
import com.loadingbyte.cinecred.ui.comms.RenderJobStatus
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.view.delivery.DeliverConfigurationForm.Companion.label
import java.text.DecimalFormat
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer


class DeliverRenderQueuePanel(deliveryCtrl: DeliveryCtrlComms) : JScrollPane(), DeliveryViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK")
    fun leakedProgressSetter(rowIdx: Int, progress: Int = 0, isFinished: Boolean = false) {
        jobTableModel.rows[rowIdx].status =
            if (isFinished) RenderJobStatus.Done(null) else RenderJobStatus.Running(progress, null)
        jobTableModel.fireTableCellUpdated(rowIdx, 5)
    }
    // =========================================

    private val jobTableModel = JobTableModel()

    init {
        deliveryCtrl.registerView(this)

        val jobTable = JTable(jobTableModel).apply {
            // We disable focusing the table for two reasons:
            //   - When it gains focus, it for some reason cannot lose it via tabbing.
            //   - If it were focusable, clicking a cancel button would require two clicks: the first focuses the table,
            //     the second actually clicks the button. By disabling focus, the user only has to click once.
            isFocusable = false
            // Disable cell selection because it looks weird with the custom cell renderers.
            cellSelectionEnabled = false
            // Prevent the user from dragging the columns around.
            tableHeader.reorderingAllowed = false
            // The rows should be tall enough to house two lines of text.
            rowHeight = 40
            columnModel.apply {
                // These cells will be rendered using special components.
                getColumn(5).cellRenderer = WordWrapCellRenderer()
                getColumn(6).cellRenderer = ProgressCellRenderer()
                getColumn(7).apply {
                    cellRenderer = CancelButtonCell()
                    cellEditor = CancelButtonCell { deliveryCtrl.onClickCancelRenderJob(jobTableModel.rows[it].info) }
                }
                // Set some sensible default col widths that define which ratio of the available space each col gets.
                getColumn(0).preferredWidth = 120
                getColumn(1).preferredWidth = 120
                getColumn(2).preferredWidth = 80
                getColumn(3).preferredWidth = 80
                getColumn(4).preferredWidth = 180
                getColumn(5).preferredWidth = 450
                getColumn(6).preferredWidth = 230
                getColumn(7).apply { minWidth = 24; maxWidth = 24 }
            }
        }

        setViewportView(jobTable)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun addRenderJobToQueue(jobInfo: RenderJobInfo, jobStatus: RenderJobStatus) {
        val row = JobTableModel.Row(jobInfo, jobStatus)
        jobTableModel.rows.add(row)
        jobTableModel.fireTableRowsInserted(jobTableModel.rowCount - 1, jobTableModel.rowCount - 1)
    }

    override fun setRenderJobStatus(jobInfo: RenderJobInfo, jobStatus: RenderJobStatus) {
        val rowIdx = jobTableModel.rows.indexOfFirst { row -> row.info === jobInfo }
        if (rowIdx == -1) return
        val row = jobTableModel.rows[rowIdx]
        row.status = jobStatus
        jobTableModel.fireTableCellUpdated(rowIdx, 6)
    }

    override fun removeRenderJobFromQueue(jobInfo: RenderJobInfo) {
        val rowIdx = jobTableModel.rows.indexOfFirst { row -> row.info === jobInfo }
        if (rowIdx == -1) return
        jobTableModel.rows.removeAt(rowIdx)
        jobTableModel.fireTableRowsDeleted(rowIdx, rowIdx)
    }


    private class JobTableModel : AbstractTableModel() {

        class Row(val info: RenderJobInfo, var status: RenderJobStatus)

        val rows = mutableListOf<Row>()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 8

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> l10n("file")
            1 -> l10n("ui.edit.sheet")
            2 -> l10n("ui.deliverConfig.pages")
            3 -> l10n("ui.deliverRenderQueue.format")
            4 -> l10n("ui.deliverRenderQueue.specs")
            5 -> l10n("ui.deliverRenderQueue.destination")
            6 -> l10n("ui.deliverRenderQueue.progress")
            7 -> ""
            else -> throw IllegalArgumentException()
        }

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> rows[rowIdx].info.creditsId.fileName
            1 -> rows[rowIdx].info.creditsId.spreadsheetName
            2 -> rows[rowIdx].info.pages
            3 -> rows[rowIdx].info.run { "<html>${formatCategory.label}<br>${format}</html>".replace(" ", "&nbsp;") }
            4 -> rows[rowIdx].info.run {
                buildString {
                    append("<html>")
                    resolution?.let(::append)
                    if (resolution != null && fpsAndScan != null)
                        append(" @ ")
                    fpsAndScan?.let(::append)
                    if ((resolution != null || fpsAndScan != null) && colorSpace != null)
                        append("<br>")
                    colorSpace?.let(::append)
                    append("</html>")
                }.replace(" ", "&nbsp;")
            }
            5 -> rows[rowIdx].info.destination
            6 -> rows[rowIdx]
            7 -> ""
            else -> throw IllegalArgumentException()
        }

        // Only the cancel button column should be editable, making the cancel buttons clickable.
        override fun isCellEditable(rowIndex: Int, colIndex: Int) = colIndex == 7

    }


    private class ProgressCellRenderer : TableCellRenderer {

        private val progressBar = JProgressBar(0, MAX_RENDER_PROGRESS).apply {
            putClientProperty(PROGRESS_BAR_SQUARE, true)
        }
        private val wordWrapCellRenderer = WordWrapCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable, row: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ): JComponent = when (val status = (row as JobTableModel.Row).status) {
            is RenderJobStatus.Queued -> progressBar.apply {
                model.value = 0
                putClientProperty(STYLE, null)  // Unset explicit foreground color.
                setTableCellBackground(table, rowIdx)
                isStringPainted = false
            }
            is RenderJobStatus.Running -> progressBar.apply {
                model.value = status.progress
                putClientProperty(STYLE, null)  // Unset explicit foreground color.
                setTableCellBackground(table, rowIdx)
                isStringPainted = true
                val percentage = DecimalFormat("0.0 %").format(status.progress / MAX_RENDER_PROGRESS.toDouble())
                val d = status.timeRemaining
                string = if (d == null) percentage else {
                    val timeRemaining = l10n(
                        "ui.deliverRenderQueue.timeRemaining",
                        "%02d:%02d:%02d".format(d.toHours(), d.toMinutesPart(), d.toSecondsPart())
                    )
                    "$percentage  \u2013  $timeRemaining"
                }
            }
            is RenderJobStatus.Done -> progressBar.apply {
                model.value = MAX_RENDER_PROGRESS
                putClientProperty(STYLE, "foreground: $PALETTE_GREEN")
                setTableCellBackground(table, rowIdx)
                val d = status.timeTaken
                if (d != null) {
                    isStringPainted = true
                    string = l10n(
                        "ui.deliverRenderQueue.timeTaken",
                        "%02d:%02d:%02d".format(d.toHours(), d.toMinutesPart(), d.toSecondsPart())
                    )
                } else
                    isStringPainted = false
            }
            is RenderJobStatus.Failed -> wordWrapCellRenderer.getTableCellRendererComponent(
                table, "${status.exc.javaClass.simpleName}: ${status.exc.localizedMessage ?: ""}",
                isSelected, hasFocus, rowIdx, colIdx
            ).apply { putClientProperty(STYLE, "foreground: $PALETTE_RED") }
        }

    }


    private class CancelButtonCell(private val callback: ((Int) -> Unit)? = null) :
        TableCellRenderer, AbstractCellEditor(), TableCellEditor {

        private val button: JButton
        private var curRowIdx = 0

        init {
            button = JButton(CANCEL_ICON).apply {
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
                isFocusable = false
                isRolloverEnabled = false
                toolTipText = l10n("ui.deliverRenderQueue.cancelTooltip")
                addActionListener { callback!!(curRowIdx) }
            }
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ) = button.apply { setTableCellBackground(table, rowIdx) }

        override fun getCellEditorValue() = null
        override fun shouldSelectCell(anEvent: EventObject) = false

        override fun getTableCellEditorComponent(
            table: JTable, value: Any, isSelected: Boolean, rowIdx: Int, colIdx: Int
        ) = button.apply { setTableCellBackground(table, rowIdx) }.also { curRowIdx = rowIdx }

    }

}
