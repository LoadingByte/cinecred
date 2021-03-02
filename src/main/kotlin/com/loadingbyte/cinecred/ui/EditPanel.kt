package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.toHex24
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_16_9
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_4_3
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.UNIFORM_SAFE_AREAS
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.EditStylingDialog
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.roundToInt


object EditPanel : JPanel() {

    private const val MAX_ZOOM = 2f

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

    private val zoomSlider = object : JSlider(0, 100, 0) {
        var zoom: Float
            get() = 1f + value * (MAX_ZOOM - 1f) / 100f
            set(newZoom) {
                value = ((newZoom - 1f) * 100f / (MAX_ZOOM - 1f)).roundToInt()
            }
    }.apply {
        preferredSize = preferredSize.apply { width = 50 }
        toolTipText = l10n("ui.edit.zoom")
        addChangeListener { previewPanels.forEach { it.zoom = zoom } }
    }

    private val layoutGuidesToggleButton = JToggleButton(l10n("ui.edit.layoutGuides"), true).apply {
        toolTipText = l10n(
            "ui.edit.layoutGuidesTooltip",
            CARD_GUIDE_COLOR.brighter().toHex24(), AXIS_GUIDE_COLOR.toHex24(),
            BODY_ELEM_GUIDE_COLOR.brighter().toHex24(), BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toHex24(),
            HEAD_TAIL_GUIDE_COLOR.brighter().toHex24()
        )
        addActionListener { previewPanels.forEach { it.setLayerVisible(GUIDES, isSelected) } }
    }
    private val uniformSafeAreasToggleButton = JToggleButton(UNIFORM_SAFE_AREAS_ICON, false).apply {
        toolTipText = l10n("ui.edit.uniformSafeAreasTooltip")
        addActionListener { previewPanels.forEach { it.setLayerVisible(UNIFORM_SAFE_AREAS, isSelected) } }
    }
    private val cutSafeArea16to9ToggleButton = JToggleButton(X_16_TO_9_ICON, false).apply {
        toolTipText = l10n("ui.edit.cutSafeAreaTooltip", "16:9")
        addActionListener { previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_16_9, isSelected) } }
    }
    private val cutSafeArea4to3ToggleButton = JToggleButton(X_4_TO_3_ICON, false).apply {
        toolTipText = l10n("ui.edit.cutSafeAreaTooltip", "4:3")
        addActionListener { previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_4_3, isSelected) } }
    }
    private val runtimeLabel1 = JLabel().apply {
        text = l10n("ui.edit.runtime")
    }
    private val runtimeLabel2 = JLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val pageTabs = JTabbedPane()

    // Utility to quickly get all PagePreviewPanels from the tabbed pane.
    private val previewPanels get() = pageTabs.components.map { it as EditPagePreviewPanel }

    init {
        fun makeAction(
            name: String, icon: Icon, shortcutKeyCode: Int = -1, shortcutModifiers: Int = 0, listener: () -> Unit
        ) = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                listener()
            }
        }.also {
            var tooltip = l10n("ui.edit.$name")
            if (shortcutKeyCode != -1) {
                tooltip += " (${getModifiersExText(shortcutModifiers)}+${getKeyText(shortcutKeyCode)})"
                for (c in arrayOf(MainFrame.contentPane as JComponent, EditStylingDialog.contentPane as JComponent)) {
                    c.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                        .put(KeyStroke.getKeyStroke(shortcutKeyCode, shortcutModifiers), name)
                    c.actionMap.put(name, it)
                }
            }
            it.putValue(Action.SMALL_ICON, icon)
            it.putValue(Action.SHORT_DESCRIPTION, tooltip)
        }

        val undoStylingAction = makeAction("undoStyling", UNDO_ICON, VK_Z, CTRL_DOWN_MASK) {
            Controller.StylingHistory.undoAndRedraw()
        }
        val redoStylingAction = makeAction("redoStyling", REDO_ICON, VK_Z, CTRL_DOWN_MASK or SHIFT_DOWN_MASK) {
            Controller.StylingHistory.redoAndRedraw()
        }
        val saveStylingAction = makeAction("saveStyling", SAVE_ICON, VK_S, CTRL_DOWN_MASK) {
            Controller.StylingHistory.save()
        }
        val resetStylingAction = makeAction("resetStyling", RESET_ICON) {
            if (isStylingUnsaved) {
                val options = arrayOf(l10n("ui.edit.resetUnsavedChangesWarning.discard"), l10n("general.cancel"))
                val selectedOption = showOptionDialog(
                    MainFrame, l10n("ui.edit.resetUnsavedChangesWarning.msg"),
                    l10n("ui.edit.resetUnsavedChangesWarning.title"),
                    DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
                )
                if (selectedOption == CLOSED_OPTION || selectedOption == 1)
                    return@makeAction
            }
            Controller.StylingHistory.tryResetAndRedraw()
        }

        fun JComponent.makeToolbarButton() = apply { putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON) }

        val topPanel = JPanel(MigLayout("", "[]30lp[][]0[]0[]0[]0[]0[]30lp[][][][]0[]0[]30lp[][]push[]")).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")).apply { font = font.deriveFont(font.size * 0.8f) })
            add(JLabel(l10n("ui.edit.styling")))
            add(toggleEditStylingDialogButton.makeToolbarButton())
            add(JButton(undoStylingAction).makeToolbarButton())
            add(JButton(redoStylingAction).makeToolbarButton())
            add(JButton(saveStylingAction).makeToolbarButton())
            add(JButton(resetStylingAction).makeToolbarButton())
            add(unsavedStylingLabel)
            add(JLabel(ZOOM_ICON).apply { toolTipText = l10n("ui.edit.zoom") })
            add(zoomSlider)
            add(layoutGuidesToggleButton)
            add(uniformSafeAreasToggleButton.makeToolbarButton())
            add(cutSafeArea16to9ToggleButton.makeToolbarButton())
            add(cutSafeArea4to3ToggleButton.makeToolbarButton())
            add(runtimeLabel1)
            add(runtimeLabel2)
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

    var isStylingUnsaved: Boolean
        get() = unsavedStylingLabel.isVisible
        set(value) {
            unsavedStylingLabel.isVisible = value
        }

    fun onTryOpenProjectDirOrExit(): Boolean =
        if (isStylingUnsaved) {
            val options = arrayOf(
                l10n("ui.edit.openUnsavedChangesWarning.save"), l10n("ui.edit.openUnsavedChangesWarning.discard"),
                l10n("general.cancel")
            )
            val selectedOption = showOptionDialog(
                MainFrame, l10n("ui.edit.openUnsavedChangesWarning.msg"),
                l10n("ui.edit.openUnsavedChangesWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            when (selectedOption) {
                0 -> {
                    Controller.StylingHistory.save()
                    true
                }
                1 -> true
                else /* Cancel option */ -> false
            }
        } else
            true

    fun updateProjectAndLog(project: Project?, drawnPages: List<DrawnPage>, log: List<ParserMsg>) {
        // Adjust the total runtime label.
        if (project == null || project.pages.isEmpty()) {
            runtimeLabel2.text = null
            runtimeLabel2.toolTipText = null
            runtimeLabel1.toolTipText = null
        } else {
            val runtime = VideoDrawer(project, drawnPages).numFrames
            val tc = Timecode(project.styling.global.fps.frac, runtime)
            val tooltip = l10n("ui.edit.runtimeTooltip", tc.minutes, tc.seconds, tc.frames, runtime)
            runtimeLabel2.text = tc.toString()
            runtimeLabel2.toolTipText = tooltip
            runtimeLabel1.toolTipText = tooltip
        }

        // First adjust the number of tabs to the number of pages.
        while (pageTabs.tabCount > drawnPages.size)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < drawnPages.size) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val previewPanel = EditPagePreviewPanel(MAX_ZOOM).apply {
                zoom = zoomSlider.zoom
                setLayerVisible(GUIDES, layoutGuidesToggleButton.isSelected)
                setLayerVisible(UNIFORM_SAFE_AREAS, uniformSafeAreasToggleButton.isSelected)
                setLayerVisible(CUT_SAFE_AREA_16_9, cutSafeArea16to9ToggleButton.isSelected)
                setLayerVisible(CUT_SAFE_AREA_4_3, cutSafeArea4to3ToggleButton.isSelected)
                zoomListeners.add { zoom -> zoomSlider.zoom = zoom }
            }
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
