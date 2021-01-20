package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.getRuntimeFrames
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.toString2
import net.miginfocom.swing.MigLayout
import java.awt.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer


object EditPanel : JPanel() {

    private val toggleEditStylingDialogButton = JToggleButton(EDIT_ICON).apply {
        isSelected = true
        toolTipText = l10n("ui.edit.toggleStyling")
        addActionListener {
            Controller.setEditStylingDialogVisible(isSelected)
        }
    }
    private val unsavedStylingLabel = JLabel(l10n("ui.edit.unsavedChanges")).apply {
        isVisible = false
        font = font.deriveFont(font.size * 0.8f)
    }

    private val zoomSlider = object : JSlider(0, 50, 0) {
        val zoom get() = 1f + value / 50f
    }.apply {
        preferredSize = Dimension(50, preferredSize.height)
        addChangeListener { previewPanels.forEach { it.zoom = zoom } }
    }

    private val layoutGuidesToggleButton = JToggleButton(l10n("ui.edit.layoutGuides"), true).apply {
        toolTipText = l10n(
            "ui.edit.layoutGuidesTooltip",
            CARD_GUIDE_COLOR.brighter().toString2(), AXIS_GUIDE_COLOR.toString2(),
            BODY_ELEM_GUIDE_COLOR.brighter().toString2(), BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toString2(),
            HEAD_TAIL_GUIDE_COLOR.brighter().toString2()
        )
        addActionListener { previewPanels.forEach { it.showGuides = isSelected } }
    }
    private val safeAreasToggleButton = JToggleButton(l10n("ui.edit.safeAreas"), false).apply {
        toolTipText = l10n("ui.edit.safeAreasTooltip")
        addActionListener { previewPanels.forEach { it.showSafeAreas = isSelected } }
    }
    private val runtimeLabel = JLabel()
    private val pageTabs = JTabbedPane()

    // Utility to quickly get all PagePreviewPanels from the tabbed pane.
    private val previewPanels get() = pageTabs.components.map { it as EditPagePreviewPanel }

    init {
        val saveStylingButton = JButton(SAVE_ICON).apply {
            toolTipText = l10n("ui.edit.saveStyling")
            addActionListener {
                Controller.saveStyling()
                unsavedStylingLabel.isVisible = false
            }
        }
        val reloadStylingButton = JButton(RESET_ICON).apply {
            toolTipText = l10n("ui.edit.resetStyling")
            addActionListener {
                if (unsavedStylingLabel.isVisible) {
                    val option = showConfirmDialog(
                        MainFrame, l10n("ui.edit.resetUnsavedChangesWarning.msg"),
                        l10n("ui.edit.resetUnsavedChangesWarning.title"), YES_NO_OPTION
                    )
                    if (option == NO_OPTION)
                        return@addActionListener
                }
                if (Controller.tryReloadStylingFileAndRedraw())
                    unsavedStylingLabel.isVisible = false
            }
        }

        val topPanel = JPanel(MigLayout("", "[]unrel:30lp[][][][][]unrel:30lp[][][][]unrel:30lp[]push[]")).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")).apply { font = font.deriveFont(font.size * 0.8f) })
            add(JLabel(l10n("ui.edit.styling")))
            add(toggleEditStylingDialogButton, "width :2*pref")
            add(saveStylingButton, "width :2*pref")
            add(reloadStylingButton)
            add(unsavedStylingLabel)
            add(JLabel(ZOOM_ICON).apply { toolTipText = l10n("ui.edit.zoom") })
            add(zoomSlider)
            add(layoutGuidesToggleButton)
            add(safeAreasToggleButton)
            add(runtimeLabel)
            add(pageTabs, "newline, span, grow, push")
        }

        val logTable = JTable(LogTableModel).apply {
            // Disable cell selection because it looks weird with the custom WordWrapCellRenderer.
            cellSelectionEnabled = false
            // When manually the window, only the message column should be resized to accommodate
            // for the change. All free space should be absorbed by the message column.
            // Also prevent the user from individually resizing the columns and dragging them around.
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.apply {
                resizingAllowed = false
                reorderingAllowed = false
                resizingColumn = columnModel.getColumn(2)
            }
            // Center the line column.
            columnModel.getColumn(1).cellRenderer =
                DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.CENTER }
            // Allow for word wrapping in the message column.
            columnModel.getColumn(2).cellRenderer = WordWrapCellRenderer()
        }
        val logTablePanel = JPanel(MigLayout()).apply {
            add(JScrollPane(logTable), "grow, push")
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, topPanel, logTablePanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater {
            splitPane.setDividerLocation(0.85)
        }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    fun onSetEditStylingDialogVisible(isVisible: Boolean) {
        toggleEditStylingDialogButton.isSelected = isVisible
    }

    fun onLoadStyling() {
        unsavedStylingLabel.isVisible = false
    }

    fun onEditStyling() {
        unsavedStylingLabel.isVisible = true
    }

    fun onTryOpenProjectDirOrExit(): Boolean =
        if (unsavedStylingLabel.isVisible) {
            val option = showConfirmDialog(
                MainFrame, l10n("ui.edit.openUnsavedChangesWarning.msg"),
                l10n("ui.edit.openUnsavedChangesWarning.title"), YES_NO_CANCEL_OPTION
            )
            when (option) {
                YES_OPTION -> {
                    Controller.saveStyling()
                    unsavedStylingLabel.isVisible = false
                    true
                }
                NO_OPTION -> {
                    unsavedStylingLabel.isVisible = false
                    true
                }
                else /* Cancel option */ -> false
            }
        } else
            true

    fun updateProjectAndLog(project: Project?, drawnPages: List<DrawnPage>, log: List<ParserMsg>) {
        // Adjust the total runtime label.
        if (project == null || project.pages.isEmpty())
            runtimeLabel.apply {
                text = null
                toolTipText = null
            }
        else {
            val fps = project.styling.global.fps.frac
            val durOnlyFrames = getRuntimeFrames(project, drawnPages)
            var durSeconds = (durOnlyFrames / fps).toInt()
            val durMinutes = durSeconds / 60
            val durFrames = durOnlyFrames - (durSeconds * fps).toInt()
            durSeconds -= durMinutes * 60
            runtimeLabel.apply {
                text = l10n("ui.edit.runtime", "%02d:%02d+%02d".format(durMinutes, durSeconds, durFrames))
                toolTipText = l10n("ui.edit.runtimeTooltip", durMinutes, durSeconds, durFrames, durOnlyFrames)
            }
        }

        // First adjust the number of tabs to the number of pages.
        while (pageTabs.tabCount > drawnPages.size)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < drawnPages.size) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val previewPanel = EditPagePreviewPanel(
                zoomSlider.zoom, layoutGuidesToggleButton.isSelected, safeAreasToggleButton.isSelected
            )
            pageTabs.addTab(tabTitle, PAGE_ICON, previewPanel)
        }
        // Then fill each tab with its corresponding page.
        for ((drawnPage, previewPanel) in drawnPages.zip(previewPanels))
            previewPanel.setContent(project!!.styling.global, drawnPage)

        // Put the new parser log messages into the log table.
        LogTableModel.log = log.sortedWith { a, b ->
            b.severity.compareTo(a.severity).let { if (it != 0) it else a.lineNo.compareTo(b.lineNo) }
        }
    }


    private object LogTableModel : AbstractTableModel() {

        var log: List<ParserMsg> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = log.size
        override fun getColumnCount() = 3

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> l10n("ui.edit.severity")
            1 -> l10n("ui.edit.line")
            2 -> l10n("ui.edit.message")
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> SEVERITY_ICON[log[rowIdx].severity]!!
            1 -> log[rowIdx].lineNo
            2 -> log[rowIdx].msg
            else -> throw IllegalArgumentException()
        }

    }

}
