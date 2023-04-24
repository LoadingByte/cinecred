package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.toHex24
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_16_9
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.CUT_SAFE_AREA_4_3
import com.loadingbyte.cinecred.ui.EditPagePreviewPanel.Companion.UNIFORM_SAFE_AREAS
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
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
        private const val ZOOM_INCREMENT = 0.1
    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedUndoStylingButton get() = undoStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedRedoStylingButton get() = redoStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedSaveStylingButton get() = saveStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedResetStylingButton get() = resetStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedGuidesButton get() = guidesToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedUniformSafeAreasButton get() = uniformSafeAreasToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedCutSafeArea16to9Button get() = cutSafeArea16to9ToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedCutSafeArea4to3Button get() = cutSafeArea4to3ToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStylingDialogButton get() = stylingDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedVideoDialogButton get() = videoDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedDeliveryDialogButton get() = deliveryDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedSplitPane: JSplitPane
    @Deprecated("ENCAPSULATION LEAK") val leakedPageTabs get() = pageTabs
    @Deprecated("ENCAPSULATION LEAK") val leakedPreviewPanels get() = previewPanels
    @Deprecated("ENCAPSULATION LEAK") val leakedCreditsLog: JTable
    // =========================================

    private val keyListeners = mutableListOf<KeyListener>()

    fun onKeyEvent(event: KeyEvent): Boolean =
        keyListeners.any { it.onKeyEvent(event) }

    private val undoStylingButton = newToolbarButtonWithKeyListener(
        UNDO_ICON, l10n("ui.edit.undoStyling"),
        VK_Z, CTRL_DOWN_MASK
    ) {
        ctrl.stylingHistory.undoAndRedraw()
    }
    private val redoStylingButton = newToolbarButtonWithKeyListener(
        REDO_ICON, l10n("ui.edit.redoStyling"),
        VK_Z, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
    ) {
        ctrl.stylingHistory.redoAndRedraw()
    }
    private val saveStylingButton = newToolbarButtonWithKeyListener(
        SAVE_ICON, l10n("ui.edit.saveStyling"),
        VK_S, CTRL_DOWN_MASK
    ) {
        ctrl.stylingHistory.save()
    }
    private val resetStylingButton = newToolbarButtonWithKeyListener(
        RESET_ICON, l10n("ui.edit.resetStyling"),
        VK_R, CTRL_DOWN_MASK
    ) {
        if (unsavedStylingLabel.isVisible) {
            val options = arrayOf(l10n("ui.edit.resetUnsavedChangesWarning.discard"), l10n("cancel"))
            val selectedOption = showOptionDialog(
                ctrl.projectFrame, l10n("ui.edit.resetUnsavedChangesWarning.msg"),
                l10n("ui.edit.resetUnsavedChangesWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            if (selectedOption == CLOSED_OPTION || selectedOption == 1)
                return@newToolbarButtonWithKeyListener
        }
        ctrl.stylingHistory.resetAndRedraw()
    }
    // By enabling HTML in the JLabel, a branch that doesn't add ellipsis but instead clips the string is taken in
    // SwingUtilities.layoutCompoundLabelImpl(). This is much nicer for a very short string like this one.
    private val unsavedStylingLabel = JLabel("<html>" + l10n("ui.edit.unsavedChanges") + "</html>").apply {
        isVisible = false
        toolTipText = text
        putClientProperty(STYLE_CLASS, "small")
    }

    private val zoomSlider = object : JSlider(0, MAX_ZOOM * 100, 0) {
        var zoom: Double
            get() = 1.0 + value * (MAX_ZOOM - 1.0) / maximum
            set(newZoom) {
                value = ((newZoom - 1.0) * maximum / (MAX_ZOOM - 1.0)).roundToInt()
            }
    }.apply {
        isFocusable = false
        addChangeListener { previewPanels.forEach { it.zoom = zoom } }
    }

    private val guidesToggleButton = newToolbarToggleButtonWithKeyListener(
        GUIDES_ICON, tooltip = l10n(
            "ui.edit.guidesTooltip",
            STAGE_GUIDE_COLOR.brighter().toHex24(),
            SPINE_GUIDE_COLOR.brighter().toHex24(),
            BODY_CELL_GUIDE_COLOR.brighter().brighter().toHex24(),
            BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toHex24(),
            HEAD_TAIL_GUIDE_COLOR.brighter().brighter().toHex24()
        ), VK_G, CTRL_DOWN_MASK, isSelected = true
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(GUIDES, isSelected) }
    }
    private val uniformSafeAreasToggleButton = newToolbarToggleButtonWithKeyListener(
        UNIFORM_SAFE_AREAS_ICON, l10n("ui.edit.uniformSafeAreasTooltip"),
        VK_M, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(UNIFORM_SAFE_AREAS, isSelected) }
    }
    private val cutSafeArea16to9ToggleButton = newToolbarToggleButtonWithKeyListener(
        X_16_TO_9_ICON, l10n("ui.edit.cutSafeAreaTooltip", "16:9"),
        VK_9, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_16_9, isSelected) }
    }
    private val cutSafeArea4to3ToggleButton = newToolbarToggleButtonWithKeyListener(
        X_4_TO_3_ICON, l10n("ui.edit.cutSafeAreaTooltip", "4:3"),
        VK_3, CTRL_DOWN_MASK, isSelected = false
    ) { isSelected ->
        previewPanels.forEach { it.setLayerVisible(CUT_SAFE_AREA_4_3, isSelected) }
    }

    private val runtimeLabel = JLabel("\u2014").apply {
        putClientProperty(STYLE_CLASS, "monospaced")
    }

    private val stylingDialogToggleButton = newToolbarToggleButtonWithKeyListener(
        PROJECT_DIALOG_STYLING_ICON, tooltip = l10n("ui.edit.toggleStylingDialog"),
        VK_E, CTRL_DOWN_MASK, isSelected = true
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.STYLING, selected)
    }
    private val videoDialogToggleButton = newToolbarToggleButtonWithKeyListener(
        PROJECT_DIALOG_VIDEO_ICON, tooltip = l10n("ui.edit.toggleVideoDialog"),
        VK_P, CTRL_DOWN_MASK, isSelected = false
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.VIDEO, selected)
    }
    private val deliveryDialogToggleButton = newToolbarToggleButtonWithKeyListener(
        PROJECT_DIALOG_DELIVERY_ICON, tooltip = l10n("ui.edit.toggleDeliveryDialog"),
        VK_O, CTRL_DOWN_MASK, isSelected = false
    ) { selected ->
        ctrl.setDialogVisible(ProjectDialogType.DELIVERY, selected)
    }
    private val browseProjectDirButton = newToolbarButtonWithKeyListener(
        FOLDER_ICON, l10n("ui.edit.browseProjectDir"),
        VK_F, CTRL_DOWN_MASK
    ) {
        tryOpen(ctrl.projectDir)
    }

    private val homeButton = newToolbarButtonWithKeyListener(
        HOME_ICON, l10n("ui.edit.home"),
        VK_W, CTRL_DOWN_MASK
    ) {
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

    private fun newToolbarButtonWithKeyListener(
        icon: Icon,
        tooltip: String,
        shortcutKeyCode: Int,
        shortcutModifiers: Int,
        listener: () -> Unit
    ): JButton {
        keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers, listener))
        return newToolbarButton(icon, tooltip, shortcutKeyCode, shortcutModifiers, listener)
    }

    private fun newToolbarToggleButtonWithKeyListener(
        icon: Icon,
        tooltip: String,
        shortcutKeyCode: Int,
        shortcutModifiers: Int,
        isSelected: Boolean,
        listener: (Boolean) -> Unit
    ): JToggleButton {
        val btn = newToolbarToggleButton(icon, tooltip, shortcutKeyCode, shortcutModifiers, isSelected, listener)
        keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers) { btn.isSelected = !btn.isSelected })
        return btn
    }

    init {
        val zoomTooltip = l10n("ui.edit.zoom") + " (" + intArrayOf(VK_PLUS, VK_MINUS, VK_0).joinToString {
            getModifiersExText(CTRL_DOWN_MASK) + "+" + getKeyText(it)
        } + ")"
        zoomSlider.toolTipText = zoomTooltip

        val runtimeDescLabel = JLabel(l10n("ui.edit.runtime")).apply {
            toolTipText = text
        }

        // Note: The shrink group with shrinkprio 200 appears to implicitly include shrinkable gaps.
        val topPanelCols = """
                []push
                []0[]0[]0[]rel[shrinkprio 200]
                0:unrel:[]unrel
                []rel[]rel[]0[]0[]0[]
                rel[]rel:unrel:
                [shrinkprio 300]0:rel:[]
                rel:unrel:[]rel
                []0[]0[]0[]
                0:rel:[shrinkprio 200]0:rel-1:
                []
                push[]
        """
        val topPanel = JPanel(MigLayout("", topPanelCols)).apply {
            add(undoStylingButton, "skip 1")
            add(redoStylingButton)
            add(saveStylingButton)
            add(resetStylingButton)
            add(unsavedStylingLabel, "wmin 15")
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(JLabel(ZOOM_ICON).apply { toolTipText = zoomTooltip })
            add(zoomSlider, "width 50!")
            add(guidesToggleButton)
            add(uniformSafeAreasToggleButton)
            add(cutSafeArea16to9ToggleButton)
            add(cutSafeArea4to3ToggleButton)
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(runtimeDescLabel, "wmin 0")
            add(runtimeLabel)
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(stylingDialogToggleButton)
            add(videoDialogToggleButton)
            add(deliveryDialogToggleButton)
            add(browseProjectDirButton)
            add(JSeparator(JSeparator.VERTICAL), "growy")
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
            // Center the record number, column name, and cell value columns.
            for (colIdx in 1..3)
                columnModel.getColumn(colIdx).cellRenderer =
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
        leakedSplitPane = splitPane
        @Suppress("DEPRECATION")
        leakedCreditsLog = logTable

        keyListeners.add(KeyListener(VK_PLUS, CTRL_DOWN_MASK) { zoomSlider.zoom += ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_ADD, CTRL_DOWN_MASK) { zoomSlider.zoom += ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_MINUS, CTRL_DOWN_MASK) { zoomSlider.zoom -= ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_SUBTRACT, CTRL_DOWN_MASK) { zoomSlider.zoom -= ZOOM_INCREMENT })
        keyListeners.add(KeyListener(VK_0, CTRL_DOWN_MASK) { zoomSlider.zoom = 1.0 })
        keyListeners.add(KeyListener(VK_NUMPAD0, CTRL_DOWN_MASK) { zoomSlider.zoom = 1.0 })
    }

    fun onSetDialogVisible(type: ProjectDialogType, isVisible: Boolean) {
        when (type) {
            ProjectDialogType.STYLING -> stylingDialogToggleButton
            ProjectDialogType.VIDEO -> videoDialogToggleButton
            ProjectDialogType.DELIVERY -> deliveryDialogToggleButton
        }.isSelected = isVisible
    }

    fun onTryCloseProject(force: Boolean): Boolean =
        if (unsavedStylingLabel.isVisible) {
            val options = mutableListOf(
                l10n("ui.edit.openUnsavedChangesWarning.save"), l10n("ui.edit.openUnsavedChangesWarning.discard")
            )
            if (!force)
                options.add(l10n("cancel"))
            val selectedOption = showOptionDialog(
                ctrl.projectFrame, l10n("ui.edit.openUnsavedChangesWarning.msg"),
                l10n("ui.edit.openUnsavedChangesWarning.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options.toTypedArray(), options[0]
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
        setStylingUnsaved(isUnsaved)
        undoStylingButton.isEnabled = isUndoable
        redoStylingButton.isEnabled = isRedoable
    }

    fun onStylingSave() {
        setStylingUnsaved(false)
    }

    private fun setStylingUnsaved(isUnsaved: Boolean) {
        unsavedStylingLabel.isVisible = isUnsaved
        resetStylingButton.isEnabled = isUnsaved
        // On macOS, show an unsaved indicator inside the "close window" button.
        rootPane.putClientProperty("Window.documentModified", isUnsaved)
    }

    fun updateProject(drawnProject: DrawnProject?, log: List<ParserMsg>, error: ProjectController.Error?) {
        // Adjust the total runtime label.
        if (drawnProject == null) {
            runtimeLabel.preferredSize = Dimension(runtimeLabel.width, 0)
            runtimeLabel.text = "\u2014"
            runtimeLabel.toolTipText = null
        } else {
            val global = drawnProject.project.styling.global
            val runtime = drawnProject.video.numFrames
            val tc = formatTimecode(global.fps, global.timecodeFormat, runtime)
            val tooltip = l10n("ui.edit.runtimeTooltip", runtime)
            runtimeLabel.preferredSize = null
            runtimeLabel.text = tc
            runtimeLabel.toolTipText = tooltip
        }

        // Update the pages tabs or show a big error notice.
        val errorText = when {
            log.any { it.severity == Severity.ERROR } -> l10n("ui.edit.creditsError")
            else -> when (error) {
                ProjectController.Error.STYLING -> l10n("ui.edit.stylingError")
                ProjectController.Error.NO_PAGES -> l10n("ui.edit.noPagesError")
                ProjectController.Error.EXCESSIVE_PAGE_SIZE -> l10n("ui.edit.excessivePageSizeError")
                null -> null
            }
        }
        if (errorText != null) {
            pageErrorLabel.text = errorText
            pagePanelCards.show(pagePanel, "Error")
        } else {
            pagePanelCards.show(pagePanel, "Pages")
            updatePageTabs(drawnProject)
        }

        // Put the new parser log messages into the log table.
        logTableModel.log = log.sortedWith(compareByDescending(ParserMsg::severity).thenBy(ParserMsg::recordNo))
    }

    private fun updatePageTabs(drawnProject: DrawnProject?) {
        // First adjust the number of tabs to the number of pages.
        val numPages = drawnProject?.drawnPages?.size ?: 0
        while (pageTabs.tabCount > numPages)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < numPages) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val previewPanel = EditPagePreviewPanel(MAX_ZOOM.toDouble(), ZOOM_INCREMENT).apply {
                zoom = zoomSlider.zoom
                setLayerVisible(GUIDES, guidesToggleButton.isSelected)
                setLayerVisible(UNIFORM_SAFE_AREAS, uniformSafeAreasToggleButton.isSelected)
                setLayerVisible(CUT_SAFE_AREA_16_9, cutSafeArea16to9ToggleButton.isSelected)
                setLayerVisible(CUT_SAFE_AREA_4_3, cutSafeArea4to3ToggleButton.isSelected)
                zoomListeners.add { zoom -> zoomSlider.zoom = zoom }
            }
            pageTabs.addTab(tabTitle, PAGE_ICON, previewPanel)
        }
        // Then fill each tab with its corresponding page.
        if (drawnProject != null)
            for ((drawnPage, previewPanel) in drawnProject.drawnPages.zip(previewPanels))
                previewPanel.setContent(drawnProject.project.styling.global, drawnPage)
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
