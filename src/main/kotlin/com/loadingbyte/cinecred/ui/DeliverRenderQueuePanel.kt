package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.roundingDiv
import com.loadingbyte.cinecred.delivery.MAX_RENDER_PROGRESS
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.ui.helper.*
import java.awt.BorderLayout
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer


class DeliverRenderQueuePanel(private val ctrl: ProjectController) : JScrollPane() {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK")
    fun leakedProgressSetter(rowIdx: Int, progress: Int = 0, isFinished: Boolean = false) {
        jobTableModel.rows[rowIdx].progress = if (isFinished) FINISHED else progress
        jobTableModel.fireTableCellUpdated(rowIdx, 5)
    }
    // =========================================

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
            // The rows should be tall enough to house two lines of text.
            rowHeight = 40
            columnModel.apply {
                // These cells will be rendered using special components.
                getColumn(4).cellRenderer = WordWrapCellRenderer()
                getColumn(5).cellRenderer = ProgressCellRenderer()
                getColumn(6).apply {
                    cellRenderer = CancelButtonCell()
                    cellEditor = CancelButtonCell(::removeRowFromQueue)
                }
                // Set some sensible default col widths that define which ratio of the available space each col gets.
                getColumn(0).preferredWidth = 120
                getColumn(1).preferredWidth = 80
                getColumn(2).preferredWidth = 80
                getColumn(3).preferredWidth = 150
                getColumn(4).preferredWidth = 450
                getColumn(5).preferredWidth = 220
                getColumn(6).apply { minWidth = 24; maxWidth = 24 }
            }
        }

        setViewportView(jobTable)
    }

    private val hasUnfinishedRenderJobs: Boolean
        get() = jobTableModel.rows.any { row -> !row.isFinished }

    fun setProcessRenderJobs(process: Boolean) {
        if (!hasUnfinishedRenderJobs)
            ctrl.deliveryDialog.panel.processButton.isSelected = false
        else
            RenderQueue.setPaused(ctrl.projectDir, !process)
    }

    fun addRenderJobToQueue(job: RenderJob, info: RenderJobInfo) {
        val row = JobTableModel.Row(job, info)
        jobTableModel.rows.add(row)
        jobTableModel.fireTableRowsInserted(jobTableModel.rowCount - 1, jobTableModel.rowCount - 1)

        var prevProgress: Any? = null
        fun setProgress(progress: Any) {
            if (progress == prevProgress)
                return
            prevProgress = progress
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
                jobTableModel.fireTableCellUpdated(rowIdx, 5)
                if (progress is Int)
                    trySetTaskbarProgress(ctrl.projectFrame, roundingDiv(progress * 100, MAX_RENDER_PROGRESS))
            }
        }

        fun onFinish(exc: Exception?) {
            setProgress(exc ?: FINISHED)
            tryUpdateTaskbarBadge()
            trySetTaskbarProgress(ctrl.projectFrame, -1)

            // If we just finished the last remaining job, deselect the toggle button and request user attention.
            if (!hasUnfinishedRenderJobs) {
                ctrl.deliveryDialog.panel.processButton.isSelected = false
                RenderQueue.setPaused(ctrl.projectDir, true)
                tryRequestUserAttentionInTaskbar(ctrl.projectFrame)
            }
        }

        RenderQueue.submitJob(
            ctrl.projectDir, job,
            progressCallback = { SwingUtilities.invokeLater { setProgress(it) } },
            finishCallback = { SwingUtilities.invokeLater { onFinish(it) } }
        )

        tryUpdateTaskbarBadge()
    }

    private fun removeRowFromQueue(rowIdx: Int) {
        val modelRow = jobTableModel.rows[rowIdx]
        RenderQueue.cancelJob(ctrl.projectDir, modelRow.job)
        jobTableModel.rows.remove(modelRow)
        jobTableModel.fireTableRowsDeleted(rowIdx, rowIdx)
        tryUpdateTaskbarBadge()
    }

    private fun tryUpdateTaskbarBadge() {
        trySetTaskbarIconBadge(RenderQueue.getNumberOfRemainingJobs())
    }

    fun onTryCloseProject(force: Boolean): Boolean {
        if (!force && ctrl.deliveryDialog.panel.processButton.isSelected && hasUnfinishedRenderJobs) {
            val options = arrayOf(l10n("ui.deliverRenderQueue.runningWarning.stop"), l10n("cancel"))
            val selectedOption = showOptionDialog(
                ctrl.deliveryDialog, l10n("ui.deliverRenderQueue.runningWarning.msg"),
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
    }


    class RenderJobInfo(
        val spreadsheet: String,
        val pages: String,
        formatCategory: String,
        format: String,
        resolution: String?,
        fpsAndScan: String?,
        colorSpace: String?,
        val destination: String
    ) {
        val assembledFormat: String = "<html>${formatCategory}<br>${format}</html>".replace(" ", "&nbsp;")
        val assembledSpecs: String = buildString {
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


    private class JobTableModel : AbstractTableModel() {

        class Row(val job: RenderJob, val info: RenderJobInfo) {
            var startTime: Instant? = null
            var progress: Any = 0
            val isFinished get() = progress !is Int
        }

        val rows = mutableListOf<Row>()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 7

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> l10n("ui.deliverConfig.spreadsheet")
            1 -> l10n("ui.deliverConfig.pages")
            2 -> l10n("ui.deliverRenderQueue.format")
            3 -> l10n("ui.deliverRenderQueue.specs")
            4 -> l10n("ui.deliverRenderQueue.destination")
            5 -> l10n("ui.deliverRenderQueue.progress")
            6 -> ""
            else -> throw IllegalArgumentException()
        }

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> rows[rowIdx].info.spreadsheet
            1 -> rows[rowIdx].info.pages
            2 -> rows[rowIdx].info.assembledFormat
            3 -> rows[rowIdx].info.assembledSpecs
            4 -> rows[rowIdx].info.destination
            5 -> rows[rowIdx]
            6 -> ""
            else -> throw IllegalArgumentException()
        }

        // Only the cancel button column should be editable, making the cancel buttons clickable.
        override fun isCellEditable(rowIndex: Int, colIndex: Int) = colIndex == 6

    }


    private class ProgressCellRenderer : TableCellRenderer {

        private val progressBar = JProgressBar(0, MAX_RENDER_PROGRESS).apply {
            putClientProperty(PROGRESS_BAR_SQUARE, true)
        }
        private val wordWrapCellRenderer = WordWrapCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable, row: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
        ): JComponent = when (val progress = (row as JobTableModel.Row).progress) {
            is Int -> progressBar.apply {
                model.value = progress
                putClientProperty(STYLE, null)  // Unset explicit foreground color.
                setTableCellBackground(table, rowIdx)
                if (row.startTime != null) {
                    isStringPainted = true
                    val percentage = DecimalFormat("0.0 %").format(progress / MAX_RENDER_PROGRESS.toDouble())
                    string = if (progress == 0)
                        percentage
                    else {
                        val d = Duration.between(row.startTime, Instant.now())
                            .multipliedBy(MAX_RENDER_PROGRESS - progress.toLong()).dividedBy(progress.toLong())
                        val timeRemaining = l10n(
                            "ui.deliverRenderQueue.timeRemaining",
                            "%02d:%02d:%02d".format(d.toHours(), d.toMinutesPart(), d.toSecondsPart())
                        )
                        "$percentage  \u2013  $timeRemaining"
                    }
                } else
                    isStringPainted = false
            }
            FINISHED -> progressBar.apply {
                model.value = MAX_RENDER_PROGRESS
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
