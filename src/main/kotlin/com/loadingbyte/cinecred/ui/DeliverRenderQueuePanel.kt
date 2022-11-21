package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer


class DeliverRenderQueuePanel(private val ctrl: ProjectController) : JPanel() {

    private val startButton = JToggleButton(l10n("ui.deliverRenderQueue.process"), PLAY_ICON).apply {
        addItemListener {
            if (jobTableModel.rows.none { !it.isFinished })
                isSelected = false
            else {
                icon = if (isSelected) PAUSE_ICON else PLAY_ICON
                RenderQueue.setPaused(ctrl.projectDir, !isSelected)
            }
        }
    }

    private val jobTableModel = JobTableModel()

    init {
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
            columnModel.apply {
                // These cells will be rendered using special components.
                getColumn(1).cellRenderer = WordWrapCellRenderer()
                getColumn(2).cellRenderer = ProgressCellRenderer()
                getColumn(3).apply {
                    cellRenderer = CancelButtonCell()
                    cellEditor = CancelButtonCell(::removeRowFromQueue)
                }
                // Set some sensible default column widths for all but the progress columns.
                getColumn(0).width = 200
                getColumn(1).width = 400
                getColumn(3).apply { minWidth = 24; maxWidth = 24 }
            }
            // The progress column should be the one that receives all remaining width.
            tableHeader.resizingColumn = columnModel.getColumn(2)
        }

        layout = MigLayout()
        add(startButton)
        add(JScrollPane(jobTable), "newline, grow, push")
    }

    val renderJobs: Sequence<RenderJob>
        get() = jobTableModel.rows.asSequence().map { it.job }

    fun addRenderJobToQueue(job: RenderJob, formatLabel: String, destination: String) {
        val row = JobTableModel.Row(job, formatLabel, destination)
        jobTableModel.rows.add(row)
        jobTableModel.fireTableRowsInserted(jobTableModel.rowCount - 1, jobTableModel.rowCount - 1)

        tryUpdateTaskbarBadge()

        fun setProgress(progress: Any) {
            val rowIdx = jobTableModel.rows.indexOf(row)
            // When we receive a progress notification for the first time, we record the current timestamp.
            if (row.startTime == null)
                row.startTime = Instant.now()
            // Even though we still receive an update from the render thread (which is additionally delayed by
            // SwingUtilities.invokeLater), the job might have been cancelled and its row might have been removed
            // from the table. To avoid issues, we make sure to only fire updates for rows that are still present
            // in the table.
            if (rowIdx != -1) {
                row.progress = progress
                jobTableModel.fireTableCellUpdated(rowIdx, 2)
                if (progress is Float)
                    trySetTaskbarProgress(ctrl.projectFrame, progress.toPercent())
            }
        }

        fun onFinish(exc: Exception?) {
            setProgress(exc ?: FINISHED)
            tryUpdateTaskbarBadge()
            trySetTaskbarProgress(ctrl.projectFrame, -1)

            // If we just finished the last remaining job, deselect the toggle button and request user attention.
            if (jobTableModel.rows.all { it.isFinished }) {
                startButton.isSelected = false
                RenderQueue.setPaused(ctrl.projectDir, true)
                tryRequestUserAttentionInTaskbar(ctrl.projectFrame)
            }
        }

        RenderQueue.submitJob(
            ctrl.projectDir, job,
            progressCallback = { SwingUtilities.invokeLater { setProgress(it) } },
            finishCallback = { SwingUtilities.invokeLater { onFinish(it) } }
        )
    }

    private fun removeRowFromQueue(rowIdx: Int) {
        val modelRow = jobTableModel.rows[rowIdx]
        RenderQueue.cancelJob(ctrl.projectDir, modelRow.job)
        jobTableModel.rows.remove(modelRow)
        jobTableModel.fireTableRowsDeleted(rowIdx, rowIdx)
        tryUpdateTaskbarBadge()
    }

    private fun tryUpdateTaskbarBadge() {
        trySetTaskbarIconBadge(RenderQueue.getRemainingJobs().size)
    }

    fun onTryCloseProject(): Boolean {
        if (startButton.isSelected && jobTableModel.rows.any { row -> !row.isFinished }) {
            val options = arrayOf(l10n("ui.deliverRenderQueue.runningWarning.stop"), l10n("cancel"))
            val selectedOption = showOptionDialog(
                ctrl.projectFrame, l10n("ui.deliverRenderQueue.runningWarning.msg"),
                l10n("ui.deliverRenderQueue.runningWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            if (selectedOption == 1)
                return false
        }
        RenderQueue.cancelAllJobs(ctrl.projectDir)
        return true
    }

    companion object {
        private val FINISHED = Any()
        private fun Float.toPercent() = (this * 100f).toInt().coerceIn(0, 100)
    }


    private class JobTableModel : AbstractTableModel() {

        class Row(val job: RenderJob, val formatLabel: String, val destination: String) {
            var startTime: Instant? = null
            var progress: Any = 0f
            val isFinished get() = progress !is Float
        }

        val rows = mutableListOf<Row>()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> l10n("ui.deliverRenderQueue.format")
            1 -> l10n("ui.deliverRenderQueue.destination")
            2 -> l10n("ui.deliverRenderQueue.progress")
            3 -> ""
            else -> throw IllegalArgumentException()
        }

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> rows[rowIdx].formatLabel
            1 -> rows[rowIdx].destination
            2 -> rows[rowIdx]
            3 -> ""
            else -> throw IllegalArgumentException()
        }

        // Only the cancel button column should be editable, making the cancel buttons clickable.
        override fun isCellEditable(rowIndex: Int, colIndex: Int) = colIndex == 3

    }


    private class ProgressCellRenderer : TableCellRenderer {

        private val progressBar = JProgressBar().apply { putClientProperty(PROGRESS_BAR_SQUARE, true) }
        private val wordWrapCellRenderer = WordWrapCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable, row: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ): JComponent = when (val progress = (row as JobTableModel.Row).progress) {
            is Float -> progressBar.apply {
                val percentage = progress.toPercent()
                model.value = percentage
                putClientProperty(STYLE, null)  // Unset explicit foreground color.
                setTableCellBackground(table, rowIdx)
                if (row.startTime != null) {
                    isStringPainted = true
                    string = if (percentage == 0)
                        "0 %"
                    else {
                        val d = Duration.between(row.startTime, Instant.now())
                            .multipliedBy(100L - percentage).dividedBy(percentage.toLong())
                        val timeRemaining = l10n(
                            "ui.deliverRenderQueue.timeRemaining",
                            "%02d:%02d:%02d".format(d.toHours(), d.toMinutesPart(), d.toSecondsPart())
                        )
                        "$percentage %  \u2013  $timeRemaining"
                    }
                } else
                    isStringPainted = false
            }
            FINISHED -> progressBar.apply {
                model.value = 100
                putClientProperty(STYLE, "foreground: $PALETTE_GREEN")
                setTableCellBackground(table, rowIdx)
                isStringPainted = false
            }
            is Exception -> wordWrapCellRenderer.getTableCellRendererComponent(
                table, "${progress.javaClass.simpleName}: ${progress.localizedMessage ?: ""}",
                isSelected, hasFocus, rowIdx, colIdx
            ).apply { putClientProperty(STYLE, "foreground: $PALETTE_RED") }
            else -> throw IllegalArgumentException()
        }

    }


    private class CancelButtonCell(private val callback: ((Int) -> Unit)? = null) :
        TableCellRenderer, AbstractCellEditor(), TableCellEditor {

        private val panel: JPanel
        private var curRowIdx = 0

        init {
            val button = JButton(CANCEL_ICON).apply {
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
                isFocusable = false
                isRolloverEnabled = false
                toolTipText = l10n("ui.deliverRenderQueue.cancelTooltip")
                addActionListener { callback!!(curRowIdx) }
            }
            panel = JPanel(BorderLayout()).apply {
                add(button, BorderLayout.CENTER)
            }
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ) = panel.apply { setTableCellBackground(table, rowIdx) }

        override fun getCellEditorValue() = null
        override fun shouldSelectCell(anEvent: EventObject) = false

        override fun getTableCellEditorComponent(
            table: JTable, value: Any, isSelected: Boolean, rowIdx: Int, colIdx: Int
        ) = panel.apply { setTableCellBackground(table, rowIdx) }.also { curRowIdx = rowIdx }

    }

}
