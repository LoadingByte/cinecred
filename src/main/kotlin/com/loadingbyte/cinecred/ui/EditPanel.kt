package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.toHex24
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_16_9
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_4_3
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.UNIFORM_SAFE_AREAS
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.min
import kotlin.math.roundToInt


class EditPanel(private val ctrl: ProjectController) : JPanel() {

    companion object {
        private const val MAX_ZOOM = 3
        private const val ZOOM_INCREMENT = 0.1f
        private const val CTRL_SHIFT_DOWN_MASK = CTRL_DOWN_MASK or SHIFT_DOWN_MASK
    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedResetStylingButton get() = resetStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedLayoutGuidesButton get() = layoutGuidesToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStylingDialogButton get() = stylingDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedPageTabs get() = pageTabs
    @Deprecated("ENCAPSULATION LEAK") val leakedCreditsLog: JTable
    // =========================================

    private val keyListeners = mutableListOf<KeyListener>()

    fun onKeyEvent(event: KeyEvent): Boolean =
        keyListeners.any { it.onKeyEvent(event) }

    private val undoStylingButton = makeActToolBtn(l10n("ui.edit.undoStyling"), UNDO_ICON, VK_Z, CTRL_DOWN_MASK) {
        ctrl.stylingHistory.undoAndRedraw()
    }
    private val redoStylingButton = makeActToolBtn(l10n("ui.edit.redoStyling"), REDO_ICON, VK_Z, CTRL_SHIFT_DOWN_MASK) {
        ctrl.stylingHistory.redoAndRedraw()
    }
    private val saveStylingButton = makeActToolBtn(l10n("ui.edit.saveStyling"), SAVE_ICON, VK_S, CTRL_DOWN_MASK) {
        ctrl.stylingHistory.save()
    }
    private val resetStylingButton = makeActToolBtn(l10n("ui.edit.resetStyling"), RESET_ICON, VK_R, CTRL_DOWN_MASK) {
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
    }
    private val unsavedStylingLabel = JLabel(l10n("ui.edit.unsavedChanges")).apply {
        isVisible = false
        putClientProperty(STYLE_CLASS, "small")
    }

    private val zoomSlider = object : JSlider(0, MAX_ZOOM * 100, 0) {
        var zoom: Float
            get() = 1f + value * (MAX_ZOOM - 1f) / maximum
            set(newZoom) {
                value = ((newZoom - 1f) * maximum / (MAX_ZOOM - 1f)).roundToInt()
            }
    }.apply {
        preferredSize = preferredSize.apply { width = 50 }
        isFocusable = false
        addChangeListener { previewPanels.forEach { it.zoom = zoom } }
    }

    private val layoutGuidesToggleButton = makeActToggleBtn(
        label = l10n("ui.edit.layoutGuides"), icon = null, tooltip = l10n(
            "ui.edit.layoutGuidesTooltip",
            STAGE_GUIDE_COLOR.toHex24(), SPINE_GUIDE_COLOR.toHex24(),
            BODY_CELL_GUIDE_COLOR.brighter().toHex24(), BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toHex24(),
            HEAD_TAIL_GUIDE_COLOR.brighter().toHex24()
        ), toolbar = false, VK_G, CTRL_DOWN_MASK, isSelected = true
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(GUIDES, isSelected) }
    }
    private val uniformSafeAreasToggleButton = makeActToggleBtn(
        label = null, UNIFORM_SAFE_AREAS_ICON, l10n("ui.edit.uniformSafeAreasTooltip"), toolbar = true,
        VK_M, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(UNIFORM_SAFE_AREAS, isSelected) }
    }
    private val cutSafeArea16to9ToggleButton = makeActToggleBtn(
        label = null, X_16_TO_9_ICON, l10n("ui.edit.cutSafeAreaTooltip", "16:9"), toolbar = true,
        VK_9, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_16_9, isSelected) }
    }
    private val cutSafeArea4to3ToggleButton = makeActToggleBtn(
        label = null, X_4_TO_3_ICON, l10n("ui.edit.cutSafeAreaTooltip", "4:3"), toolbar = true,
        VK_3, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_4_3, isSelected) }
    }

    private val runtimeLabel1 = JLabel(l10n("ui.edit.runtime"))
    private val runtimeLabel2 = JLabel("\u2013").apply {
        putClientProperty(STYLE_CLASS, "monospaced")
    }

    private val stylingDialogToggleButton = makeActToggleBtn(
        label = null, PROJECT_DIALOG_STYLING_ICON, tooltip = l10n("ui.edit.toggleStylingDialog"), toolbar = true,
        VK_E, CTRL_DOWN_MASK, isSelected = true
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.STYLING, selected)
    }
    private val videoDialogToggleButton = makeActToggleBtn(
        label = null, PROJECT_DIALOG_VIDEO_ICON, tooltip = l10n("ui.edit.toggleVideoDialog"), toolbar = true,
        VK_P, CTRL_DOWN_MASK, isSelected = false
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.VIDEO, selected)
    }
    private val deliveryDialogToggleButton = makeActToggleBtn(
        label = null, PROJECT_DIALOG_DELIVERY_ICON, tooltip = l10n("ui.edit.toggleDeliveryDialog"), toolbar = true,
        VK_O, CTRL_DOWN_MASK, isSelected = false
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.DELIVERY, selected)
    }
    private val homeButton = makeActToolBtn(l10n("ui.edit.home"), HOME_ICON, VK_W, CTRL_DOWN_MASK) {
        ctrl.masterCtrl.showWelcomeFrame()
    }

    private val pageTabs = JTabbedPane().apply {
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
        tooltip: String, icon: Icon, shortcutKeyCode: Int, shortcutModifiers: Int, listener: () -> Unit
    ): JButton {
        val ttip = "$tooltip (${getModifiersExText(shortcutModifiers)}+${getKeyText(shortcutKeyCode)})"
        val btn = JButton(icon).apply {
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            isFocusable = false
            toolTipText = ttip
        }
        btn.addActionListener { listener() }
        keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers, listener))
        return btn
    }

    private fun makeActToggleBtn(
        label: String?, icon: Icon?, tooltip: String, toolbar: Boolean, shortcutKeyCode: Int, shortcutModifiers: Int,
        isSelected: Boolean, listener: (Boolean) -> Unit
    ): JToggleButton {
        val shortcutHint = getModifiersExText(shortcutModifiers) + "+" + getKeyText(shortcutKeyCode)
        val ttip = if ("<br>" !in tooltip) "$tooltip ($shortcutHint)" else {
            val idx = tooltip.indexOf("<br>")
            tooltip.substring(0, idx) + " ($shortcutHint)" + tooltip.substring(idx)
        }
        val btn = JToggleButton(label, icon, isSelected).apply {
            if (toolbar)
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            isFocusable = false
            toolTipText = ttip
        }
        btn.addItemListener { listener(it.stateChange == ItemEvent.SELECTED) }
        keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers) { btn.isSelected = !btn.isSelected })
        return btn
    }

    init {
        val zoomTooltip = l10n("ui.edit.zoom") + " (" + intArrayOf(VK_PLUS, VK_MINUS, VK_0).joinToString {
            getModifiersExText(CTRL_DOWN_MASK) + "+" + getKeyText(it)
        } + ")"
        zoomSlider.toolTipText = zoomTooltip

        val topPanelCols = """
                []push
                []
                unrel[]rel
                []0[]0[]0[]rel[]
                unrel[]unrel
                []rel[]rel[]rel[]0[]0[]
                rel[]unrel
                []rel[]
                unrel[]rel
                []0[]0[]0[]
                push[]
        """
        val topPanel = JPanel(MigLayout("", topPanelCols)).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")).apply { putClientProperty(STYLE_CLASS, "small") }, "skip 1")
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(undoStylingButton)
            add(redoStylingButton)
            add(saveStylingButton)
            add(resetStylingButton)
            add(unsavedStylingLabel)
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(JLabel(ZOOM_ICON).apply { toolTipText = zoomTooltip })
            add(zoomSlider)
            add(layoutGuidesToggleButton)
            add(uniformSafeAreasToggleButton)
            add(cutSafeArea16to9ToggleButton)
            add(cutSafeArea4to3ToggleButton)
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(runtimeLabel1)
            add(runtimeLabel2)
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(stylingDialogToggleButton)
            add(videoDialogToggleButton)
            add(deliveryDialogToggleButton)
            add(homeButton)
            add(JSeparator(), "newline, span, growx")
            add(pagePanel, "newline, span, grow, push")
        }

        val logTable = JTable(logTableModel).apply {
            isFocusable = false
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
            columnModel.getColumn(4).cellRenderer = WordWrapCellRenderer(allowHtml = true, shrink = true)
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

        @Suppress("DEPRECATION")
        leakedCreditsLog = logTable

        keyListeners.add(KeyListener(VK_PLUS, CTRL_DOWN_MASK) { zoomSlider.zoom += ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_ADD, CTRL_DOWN_MASK) { zoomSlider.zoom += ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_MINUS, CTRL_DOWN_MASK) { zoomSlider.zoom -= ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_SUBTRACT, CTRL_DOWN_MASK) { zoomSlider.zoom -= ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_0, CTRL_DOWN_MASK) { zoomSlider.zoom = 1f })
        keyListeners.add(KeyListener(VK_NUMPAD0, CTRL_DOWN_MASK) { zoomSlider.zoom = 1f })
    }

    fun onSetDialogVisible(type: ProjectDialogType, isVisible: Boolean) {
        when (type) {
            ProjectDialogType.STYLING -> stylingDialogToggleButton
            ProjectDialogType.VIDEO -> videoDialogToggleButton
            ProjectDialogType.DELIVERY -> deliveryDialogToggleButton
        }.isSelected = isVisible
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

    fun updateProject(
        project: Project?, drawnPages: List<DrawnPage>, runtime: Int,
        stylingError: Boolean, excessivePageSizeError: Boolean, log: List<ParserMsg>
    ) {
        // Adjust the total runtime label.
        if (project == null || project.pages.isEmpty()) {
            runtimeLabel2.text = "\u2013"
            runtimeLabel2.toolTipText = null
            runtimeLabel1.toolTipText = null
        } else {
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
        else if (excessivePageSizeError)
            pageErrorLabel.text = l10n("ui.edit.excessivePageSizeError")
        if (stylingError || creditsError || excessivePageSizeError)
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
            val previewPanel = EditPagePreviewPanel(MAX_ZOOM.toFloat(), ZOOM_INCREMENT).apply {
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
        override fun getColumnCount() = 5

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> ""
            1 -> l10n("ui.edit.record")
            2 -> l10n("ui.edit.column")
            3 -> l10n("ui.edit.value")
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
