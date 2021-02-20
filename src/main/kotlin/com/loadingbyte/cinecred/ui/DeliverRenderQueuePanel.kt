package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer


object DeliverRenderQueuePanel : JPanel() {

    private val startButton = JToggleButton(l10n("ui.deliverRenderQueue.process"), PLAY_ICON).apply {
        addActionListener {
            icon = if (isSelected) PAUSE_ICON else PLAY_ICON
            RenderQueue.isPaused = !isSelected
        }
    }

    init {
        val jobTable = JTable(JobTableModel).apply {
            // Disable cell selection because it looks weird with the custom cell renderers.
            cellSelectionEnabled = false
            // Prevent the user from dragging the columns around.
            tableHeader.reorderingAllowed = false
            columnModel.apply {
                // These cells will be rendered using special components.
                getColumn(0).cellRenderer = WordWrapCellRenderer()
                getColumn(1).cellRenderer = WordWrapCellRenderer()
                getColumn(2).cellRenderer = ProgressCellRenderer
                getColumn(3).apply { cellRenderer = CancelButtonCellRenderer; cellEditor = CancelButtonCellEditor }
                // Set some sensible default column widths for all but the progress columns.
                getColumn(0).width = 200
                getColumn(1).width = 400
                getColumn(3).maxWidth = 24
            }
            // The progress column should be the one that receives all remaining width.
            tableHeader.resizingColumn = columnModel.getColumn(2)
        }

        layout = MigLayout()
        add(startButton)
        add(JScrollPane(jobTable), "newline, grow, push")
    }

    val renderJobs: Sequence<RenderJob>
        get() = JobTableModel.rows.asSequence().map { it.job }

    fun addRenderJobToQueue(job: RenderJob, formatLabel: String, destination: String) {
        val row = JobTableModel.Row(job, formatLabel, destination)
        JobTableModel.rows.add(row)
        JobTableModel.fireTableRowsInserted(JobTableModel.rowCount - 1, JobTableModel.rowCount - 1)

        tryUpdateTaskbarBadge()

        fun setProgress(progress: Any) {
            val rowIdx = JobTableModel.rows.indexOf(row)
            // When we receive a progress notification for the first time, we record the current timestamp.
            if (row.startTime == null)
                row.startTime = Instant.now()
            // Even though we still receive an update from the render thread (which is additionally delayed by
            // SwingUtilities.invokeLater), the job might have been cancelled and its row might have been removed
            // from the table. To avoid issues, we make sure to only fire updates for rows that are still present
            // in the table.
            if (rowIdx != -1) {
                row.progress = progress
                JobTableModel.fireTableCellUpdated(rowIdx, 2)
                if (progress is Float)
                    trySetTaskbarProgress(progress.toPercent())
            }
        }

        fun onFinish(exc: Exception?) {
            setProgress(exc ?: FINISHED)
            tryUpdateTaskbarBadge()
            trySetTaskbarProgress(-1)

            // If we just finished the last remaining job, deselect the toggle button and request user attention.
            if (JobTableModel.rows.all { it.isFinished }) {
                if (startButton.isSelected)
                    startButton.doClick()
                RenderQueue.isPaused = true
                tryRequestUserAttentionInTaskbar()
            }
        }

        RenderQueue.submitJob(
            job,
            invokeLater = { SwingUtilities.invokeLater(it) },
            progressCallback = ::setProgress,
            finishCallback = ::onFinish
        )
    }

    private fun tryUpdateTaskbarBadge() {
        trySetTaskbarIconBadge(JobTableModel.rows.count { !it.isFinished })
    }

    private val FINISHED = Any()

    private fun Float.toPercent() = (this * 100f).toInt().coerceIn(0, 100)

    fun onTryExit(): Boolean =
        if (!RenderQueue.isPaused && JobTableModel.rows.any { row -> row.progress.let { it is Float && it != 1f } }) {
            val options = arrayOf(l10n("ui.deliverRenderQueue.runningWarning.stop"), l10n("general.cancel"))
            val selectedOption = showOptionDialog(
                MainFrame, l10n("ui.deliverRenderQueue.runningWarning.msg"),
                l10n("ui.deliverRenderQueue.runningWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            if (selectedOption == 0) {
                RenderQueue.stop()
                true
            } else
                false
        } else
            true


    private object JobTableModel : AbstractTableModel() {

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


    private object ProgressCellRenderer : TableCellRenderer {

        private val progressBar = JProgressBar()
        private val defaultProgressBarForeground = progressBar.foreground
        private val wordWrapCellRenderer = WordWrapCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable, row: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ): JComponent = when (val progress = (row as JobTableModel.Row).progress) {
            is Float -> progressBar.apply {
                val percentage = progress.toPercent()
                model.value = percentage
                foreground = defaultProgressBarForeground
                if (row.startTime != null) {
                    isStringPainted = true
                    string = if (percentage == 0)
                        "$percentage %"
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
                foreground = Color.decode("#499C54")
                isStringPainted = false
            }
            is Exception -> wordWrapCellRenderer.getTableCellRendererComponent(
                table, "${progress.javaClass.simpleName}: ${progress.localizedMessage ?: ""}",
                isSelected, hasFocus, rowIdx, colIdx
            ).apply { foreground = Color.decode("#C75450") }
            else -> throw IllegalArgumentException()
        }

    }


    private object CancelButtonCellRenderer : TableCellRenderer {

        private val button = JButton(CANCEL_ICON).apply {
            toolTipText = l10n("ui.deliverRenderQueue.cancelTooltip")
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ) = button

    }


    private object CancelButtonCellEditor : AbstractCellEditor(), TableCellEditor {

        override fun getCellEditorValue() = null
        override fun shouldSelectCell(anEvent: EventObject) = false

        override fun getTableCellEditorComponent(
            table: JTable, value: Any, isSelected: Boolean, rowIdx: Int, colIdx: Int
        ) = JButton(CANCEL_ICON).apply {
            toolTipText = l10n("ui.deliverRenderQueue.cancelTooltip")
            addActionListener {
                val modelRow = JobTableModel.rows[rowIdx]
                RenderQueue.cancelJob(modelRow.job)
                JobTableModel.rows.remove(modelRow)
                JobTableModel.fireTableRowsDeleted(rowIdx, rowIdx)
                tryUpdateTaskbarBadge()
            }
        }

    }

}
