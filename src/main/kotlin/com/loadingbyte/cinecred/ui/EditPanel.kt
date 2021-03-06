package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.formatTimecode
import com.loadingbyte.cinecred.projectio.toHex24
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_16_9
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_4_3
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.UNIFORM_SAFE_AREAS
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.roundToInt


class EditPanel(private val ctrl: ProjectController) : JPanel() {

    companion object {
        private const val MAX_ZOOM = 2f
    }

    // ========== HINT OWNERS ==========
    val toggleStylingHintOwner: Component
    val resetStylingHintOwner: Component
    val layoutGuidesHintOwner: Component
    val pageTabsHintOwner: Component
    val creditsLogHintOwner: Component
    // =================================

    private val keyListeners = mutableListOf<(KeyEvent) -> Boolean>()

    fun onKeyEvent(event: KeyEvent) =
        keyListeners.any { it(event) }

    private val toggleEditStylingDialogButton = JToggleButton(EDIT_ICON).apply {
        toggleStylingHintOwner = this
        isSelected = true
        toolTipText = l10n("ui.edit.toggleStyling")
        isFocusable = false
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
        addActionListener {
            ctrl.setEditStylingDialogVisible(isSelected)
        }
    }
    private val undoStylingButton = makeActToolBtn("undoStyling", UNDO_ICON, VK_Z, CTRL_DOWN_MASK) {
        ctrl.stylingHistory.undoAndRedraw()
    }
    private val redoStylingButton = makeActToolBtn("redoStyling", REDO_ICON, VK_Z, CTRL_DOWN_MASK or SHIFT_DOWN_MASK) {
        ctrl.stylingHistory.redoAndRedraw()
    }
    private val saveStylingButton = makeActToolBtn("saveStyling", SAVE_ICON, VK_S, CTRL_DOWN_MASK) {
        ctrl.stylingHistory.save()
    }
    private val resetStylingButton = makeActToolBtn("resetStyling", RESET_ICON) {
        if (unsavedStylingLabel.isVisible) {
            val options = arrayOf(l10n("ui.edit.resetUnsavedChangesWarning.discard"), l10n("cancel"))
            val selectedOption = showOptionDialog(
                ctrl.projectFrame, l10n("ui.edit.resetUnsavedChangesWarning.msg"),
                l10n("ui.edit.resetUnsavedChangesWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            if (selectedOption == CLOSED_OPTION || selectedOption == 1)
                return@makeActToolBtn
        }
        ctrl.stylingHistory.resetAndRedraw()
    }.also { resetStylingHintOwner = it }
    private val unsavedStylingLabel = JLabel(l10n("ui.edit.unsavedChanges")).apply {
        isVisible = false
        putClientProperty(STYLE_CLASS, "small")
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
        isFocusable = false
        addChangeListener { previewPanels.forEach { it.zoom = zoom } }
    }

    private val layoutGuidesToggleButton = JToggleButton(l10n("ui.edit.layoutGuides"), true).apply {
        layoutGuidesHintOwner = this
        toolTipText = l10n(
            "ui.edit.layoutGuidesTooltip",
            STAGE_GUIDE_COLOR.toHex24(), AXIS_GUIDE_COLOR.toHex24(),
            BODY_ELEM_GUIDE_COLOR.brighter().toHex24(), BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toHex24(),
            HEAD_TAIL_GUIDE_COLOR.brighter().toHex24()
        )
        isFocusable = false
        addActionListener { previewPanels.forEach { it.setLayerVisible(GUIDES, isSelected) } }
    }
    private val uniformSafeAreasToggleButton = JToggleButton(UNIFORM_SAFE_AREAS_ICON, false).apply {
        toolTipText = l10n("ui.edit.uniformSafeAreasTooltip")
        isFocusable = false
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
        addActionListener { previewPanels.forEach { it.setLayerVisible(UNIFORM_SAFE_AREAS, isSelected) } }
    }
    private val cutSafeArea16to9ToggleButton = JToggleButton(X_16_TO_9_ICON, false).apply {
        toolTipText = l10n("ui.edit.cutSafeAreaTooltip", "16:9")
        isFocusable = false
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
        addActionListener { previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_16_9, isSelected) } }
    }
    private val cutSafeArea4to3ToggleButton = JToggleButton(X_4_TO_3_ICON, false).apply {
        toolTipText = l10n("ui.edit.cutSafeAreaTooltip", "4:3")
        isFocusable = false
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
        addActionListener { previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_4_3, isSelected) } }
    }

    private val runtimeLabel1 = JLabel().apply {
        text = l10n("ui.edit.runtime")
    }
    private val runtimeLabel2 = JLabel().apply {
        putClientProperty(STYLE_CLASS, "monospaced")
    }

    private val pageTabs = JTabbedPane().apply {
        pageTabsHintOwner = this
        isFocusable = false
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        putClientProperty(TABBED_PANE_TAB_TYPE, TABBED_PANE_TAB_TYPE_CARD)
        putClientProperty(TABBED_PANE_SCROLL_BUTTONS_POLICY, TABBED_PANE_POLICY_AS_NEEDED)
        putClientProperty(TABBED_PANE_SHOW_CONTENT_SEPARATOR, false)
    }
    private val pageErrorLabel = JLabel().apply {
        putClientProperty(STYLE, "font: bold \$h0.font; foreground: $PALETTE_RED")
    }
    private val pagePanelCards = CardLayout()
    private val pagePanel = JPanel(pagePanelCards).apply {
        add(pageTabs, "Pages")
        add(JPanel(MigLayout()).apply {
            add(pageErrorLabel, "push, center")
        }, "Error")
    }

    private val logTableModel = LogTableModel()

    // Utility to quickly get all PagePreviewPanels from the tabbed pane.
    private val previewPanels: List<EditPagePreviewPanel>
        get() = ArrayList<EditPagePreviewPanel>(pageTabs.tabCount).apply {
            for (tabIdx in 0 until pageTabs.tabCount)
                add(pageTabs.getComponentAt(tabIdx) as EditPagePreviewPanel)
        }

    private fun makeActToolBtn(
        name: String, icon: Icon, shortcutKeyCode: Int = -1, shortcutModifiers: Int = 0, listener: () -> Unit
    ): JButton {
        if (shortcutKeyCode != -1)
            keyListeners.add { e ->
                val match = e.id == KEY_PRESSED && e.keyCode == shortcutKeyCode && e.modifiersEx == shortcutModifiers
                if (match)
                    listener()
                match
            }

        var tooltip = l10n("ui.edit.$name")
        if (shortcutKeyCode != -1)
            tooltip += " (${getModifiersExText(shortcutModifiers)}+${getKeyText(shortcutKeyCode)})"
        return JButton(icon).apply {
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            isFocusable = false
            toolTipText = tooltip
            addActionListener { listener() }
        }
    }

    init {
        val topPanel = JPanel(MigLayout("", "[]30lp[][]0[]0[]0[]0[][]30lp[][][][]0[]0[]push[][]")).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")).apply { putClientProperty(STYLE_CLASS, "small") })
            add(JLabel(l10n("ui.edit.styling")))
            add(toggleEditStylingDialogButton)
            add(undoStylingButton)
            add(redoStylingButton)
            add(saveStylingButton)
            add(resetStylingButton)
            add(unsavedStylingLabel)
            add(JLabel(ZOOM_ICON).apply { toolTipText = l10n("ui.edit.zoom") })
            add(zoomSlider)
            add(layoutGuidesToggleButton)
            add(uniformSafeAreasToggleButton)
            add(cutSafeArea16to9ToggleButton)
            add(cutSafeArea4to3ToggleButton)
            add(runtimeLabel1)
            add(runtimeLabel2)
            add(pagePanel, "newline, span, grow, push")
        }

        val logTable = JTable(logTableModel).apply {
            creditsLogHintOwner = this
            // Disable cell selection because it looks weird with the custom WordWrapCellRenderer.
            cellSelectionEnabled = false
            // Prevent the user from dragging the columns around.
            tableHeader.reorderingAllowed = false
            // Lock the widths of the first two columns (severity and record number), initialize the widths of
            // the col and cell columns with a small minimum width, and initially distribute all remaining width
            // to the message column.
            columnModel.getColumn(0).apply { minWidth = 24; maxWidth = 24 }
            columnModel.getColumn(1).apply { minWidth = 48; maxWidth = 48 }
            columnModel.getColumn(2).apply { minWidth = 96; width = 96 }
            columnModel.getColumn(3).apply { minWidth = 96; width = 96 }
            tableHeader.resizingColumn = columnModel.getColumn(4)
            // Center the record number column.
            columnModel.getColumn(1).cellRenderer =
                DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.CENTER }
            // Allow for word wrapping and HTML display in the message column.
            columnModel.getColumn(4).cellRenderer = WordWrapCellRenderer(allowHtml = true)
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

    fun onTryCloseProject(): Boolean =
        if (unsavedStylingLabel.isVisible) {
            val options = arrayOf(
                l10n("ui.edit.openUnsavedChangesWarning.save"), l10n("ui.edit.openUnsavedChangesWarning.discard"),
                l10n("cancel")
            )
            val selectedOption = showOptionDialog(
                ctrl.projectFrame, l10n("ui.edit.openUnsavedChangesWarning.msg"),
                l10n("ui.edit.openUnsavedChangesWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            when (selectedOption) {
                0 -> {
                    ctrl.stylingHistory.save()
                    true
                }
                1 -> true
                else /* Cancel option */ -> false
            }
        } else
            true

    fun onStylingChange(isUnsaved: Boolean, isUndoable: Boolean, isRedoable: Boolean) {
        unsavedStylingLabel.isVisible = isUnsaved
        undoStylingButton.isEnabled = isUndoable
        redoStylingButton.isEnabled = isRedoable
        resetStylingButton.isEnabled = isUnsaved
    }

    fun onStylingSave() {
        unsavedStylingLabel.isVisible = false
        resetStylingButton.isEnabled = false
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>, stylingError: Boolean, log: List<ParserMsg>) {
        // Adjust the total runtime label.
        if (project == null || project.pages.isEmpty()) {
            runtimeLabel2.text = "\u2013"
            runtimeLabel2.toolTipText = null
            runtimeLabel1.toolTipText = null
        } else {
            val runtime = VideoDrawer(project, drawnPages).numFrames
            val tc = formatTimecode(project.styling.global.fps, project.styling.global.timecodeFormat, runtime)
            val tooltip = l10n("ui.edit.runtimeTooltip", runtime)
            runtimeLabel2.text = tc
            runtimeLabel2.toolTipText = tooltip
            runtimeLabel1.toolTipText = tooltip
        }

        // Update the pages tabs or show a big error notice.
        val creditsError = log.any { it.severity == Severity.ERROR }
        if (stylingError)
            pageErrorLabel.text = l10n("ui.edit.stylingError")
        else if (creditsError)
            pageErrorLabel.text = l10n("ui.edit.creditsError")
        if (stylingError || creditsError)
            pagePanelCards.show(pagePanel, "Error")
        else {
            pagePanelCards.show(pagePanel, "Pages")
            updatePageTabs(project, drawnPages)
        }

        // Put the new parser log messages into the log table.
        logTableModel.log = log.sortedWith(compareByDescending(ParserMsg::severity).thenBy(ParserMsg::recordNo))
    }

    private fun updatePageTabs(project: Project?, drawnPages: List<DrawnPage>) {
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
    }


    private class LogTableModel : AbstractTableModel() {

        var log: List<ParserMsg> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = log.size
        override fun getColumnCount() = 5

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> ""
            1 -> l10n("ui.edit.record")
            2 -> l10n("ui.edit.column")
            3 -> l10n("ui.edit.cell")
            4 -> l10n("ui.edit.message")
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> log[rowIdx].severity.icon
            1 -> log[rowIdx].recordNo?.plus(1) ?: ""
            2 -> log[rowIdx].colHeader ?: ""
            3 -> log[rowIdx].cellValue ?: ""
            4 -> log[rowIdx].msg
            else -> throw IllegalArgumentException()
        }

    }

}
