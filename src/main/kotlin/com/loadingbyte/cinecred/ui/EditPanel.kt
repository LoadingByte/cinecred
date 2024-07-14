package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.DrawnCredits
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.view.playback.PlaybackControlsPanel
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    @Deprecated("ENCAPSULATION LEAK") val leakedOverlaysButton get() = overlaysButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStylingDialogButton get() = stylingDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedVideoDialogButton get() = videoDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedDeliveryDialogButton get() = deliveryDialogToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedPlaybackControls get() = playbackControls
    @Deprecated("ENCAPSULATION LEAK") val leakedSplitPane: JSplitPane
    @Deprecated("ENCAPSULATION LEAK") val leakedPageTabs get() = creditsTabs.getComponentAt(0) as JTabbedPane
    @Deprecated("ENCAPSULATION LEAK") val leakedImagePanels get() = imagePanels
    @Deprecated("ENCAPSULATION LEAK") val leakedCreditsLog: JTable
    // =========================================

    private val keyListeners = mutableListOf<KeyListener>()

    fun onKeyEvent(event: KeyEvent): Boolean =
        keyListeners.any { it.onKeyEvent(event) }

    private val pollCreditsButton = newToolbarButtonWithKeyListener(
        REFRESH_ICON, l10n("ui.edit.pollCredits"),
        VK_F5, 0
    ) {
        ctrl.pollCredits()
    }

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
                l10n("ui.edit.unsavedChangesWarnings.title"),
                DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
            )
            if (selectedOption == CLOSED_OPTION || selectedOption == 1)
                return@newToolbarButtonWithKeyListener
        }
        ctrl.stylingHistory.resetAndRedraw()
    }
    // Clipping the string instead of having ellipsis is much nicer for a very short string like this one.
    private val unsavedStylingLabel = JLabel(noEllipsisLabel(l10n("ui.edit.unsavedChanges"))).apply {
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
        addChangeListener { imagePanels.forEach { it.zoom = zoom } }
    }

    private val guidesToggleButton = newToolbarToggleButtonWithKeyListener(
        GUIDES_ICON, tooltip = "<html>" + l10n("ui.edit.guidesTooltip.title") + "<br>" +
                "<font color=\"#FF64FF\">\u2014</font> " + l10n("ui.edit.guidesTooltip.page") + "<br>" +
                "<font color=\"#00FFFF\">\u2014</font> " + l10n("ui.edit.guidesTooltip.spine") + "<br>" +
                "<font color=\"FF6500\">\u2014</font> " + l10n("ui.edit.guidesTooltip.bodyCell") + "<br>" +
                "<font color=\"#FF0000\">\u2014</font> " + l10n("ui.edit.guidesTooltip.bodyWidth") + "<br>" +
                "<font color=\"#00CA00\">\u2014</font> " + l10n("ui.edit.guidesTooltip.headTail") + "</html>",
        VK_G, CTRL_DOWN_MASK, isSelected = true
    ) {
        refreshVisibleLayers()
    }
    private val overlaysButton = newToolbarButton(
        ADVANCED_ICON, l10n("ui.edit.overlaysTooltip"),
        VK_O, CTRL_DOWN_MASK
    )
    private val overlaysMenu: DropdownPopupMenu = DropdownPopupMenu(overlaysButton)

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
        VK_Q, CTRL_DOWN_MASK, isSelected = false
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

    private val playbackControls = PlaybackControlsPanel(ctrl.playbackCtrl).apply {
        isVisible = false
    }

    private val creditsTabs = newPreviewTabbedPane().apply {
        addChangeListener {
            displayRuntimeOfSelectedCredits()
            updateDeferredImagePanelPresentationStatuses()
        }
    }

    private val previewPanelCards = CardLayout()
    private val previewPanel = JPanel(previewPanelCards).apply {
        val loadingLabel = JLabel(l10n("ui.edit.loading")).apply { putClientProperty(STYLE, "font: bold \$h0.font") }
        add(JPanel(MigLayout()).apply { add(loadingLabel, "push, center") }, "Loading")
        add(JPanel(MigLayout()).apply { add(JLabel(ERROR_ICON.getScaledIcon(4.0)), "push, center") }, "Error")
        add(creditsTabs, "Preview")
    }

    private val logTableModel = LogTableModel()

    // Utility to quickly get all DeferredImagePanels of all credits spreadsheets.
    private val imagePanels: List<DeferredImagePanel>
        get() = buildList(256) {
            for (creditsIdx in 0..<creditsTabs.tabCount) {
                val pageTabs = creditsTabs.getComponentAt(creditsIdx) as JTabbedPane
                for (pageIdx in 0..<pageTabs.tabCount)
                    add(pageTabs.getComponentAt(pageIdx) as DeferredImagePanel)
            }
        }

    private val highResCache = DeferredImage.CanvasMaterializationCache()
    private val lowResCache = DeferredImage.CanvasMaterializationCache()

    private var drawnProject: DrawnProject? = null

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

    private fun newPreviewTabbedPane() = JTabbedPane().apply {
        isFocusable = false
        tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        putClientProperty(TABBED_PANE_TAB_TYPE, TABBED_PANE_TAB_TYPE_CARD)
        putClientProperty(TABBED_PANE_SCROLL_BUTTONS_POLICY, TABBED_PANE_POLICY_AS_NEEDED)
    }

    init {
        // Credits polling is usually disabled; it will be enabled when it's available.
        updateCreditsPolling(false)

        // Hide the playback controls when there are no DeckLink cards available.
        ctrl.playbackCtrl.registerView(object : PlaybackViewComms {
            override fun setDeckLinks(deckLinks: List<DeckLink>) {
                playbackControls.isVisible = deckLinks.isNotEmpty()
            }
        })

        val zoomTooltip = l10n("ui.edit.zoom") + " (" + intArrayOf(VK_PLUS, VK_MINUS, VK_0).joinToString {
            getModifiersExText(CTRL_DOWN_MASK) + "+" + getKeyText(it)
        } + ")"
        zoomSlider.toolTipText = zoomTooltip

        overlaysButton.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e))
                    SwingUtilities.invokeLater { overlaysMenu.toggle() }
            }
        })

        val runtimeDescLabel = JLabel(l10n("ui.edit.runtime")).apply {
            toolTipText = text
        }

        // Note: The shrink group with shrinkprio 200 appears to implicitly include shrinkable gaps.
        val topPanelCols = """
                []push
                []
                0:rel[]0:rel
                []0[]0[]0[]rel[shrinkprio 200]
                0:unrel:[]unrel
                []rel[]rel[]0[]
                rel[]rel:unrel:
                [shrinkprio 300]0:rel:[]
                rel:unrel:[]rel
                []0[]0[]0[]
                0:rel:[shrinkprio 200]0:rel-1:
                []
                push[]
        """
        val topPanel = JPanel(MigLayout("", topPanelCols)).apply {
            add(pollCreditsButton, "skip 1")
            add(JSeparator(JSeparator.VERTICAL), "growy")
            add(undoStylingButton)
            add(redoStylingButton)
            add(saveStylingButton)
            add(resetStylingButton)
            add(unsavedStylingLabel, "wmin 15")
            add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
            add(JLabel(ZOOM_ICON).apply { toolTipText = zoomTooltip })
            add(zoomSlider, "width 50!")
            add(guidesToggleButton)
            add(overlaysButton)
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
            add(playbackControls, "newline, span, growx, gapx 8 8, hidemode 3")
            add(JSeparator(), "newline, span, growx")
            add(previewPanel, "newline, span, grow, push")
        }

        val logTable = JTable(logTableModel).apply {
            isFocusable = false
            // Disable cell selection because it looks weird with the custom WordWrapCellRenderer.
            cellSelectionEnabled = false
            // Prevent the user from dragging the columns around.
            tableHeader.reorderingAllowed = false
            // Lock the widths of the severity and row columns, initialize the widths of the spreadsheet name,
            // column name, and cell columns with a small minimum width, and initially distribute all remaining width
            // to the message column.
            columnModel.getColumn(0).apply { minWidth = 24; maxWidth = 24 }
            columnModel.getColumn(1).apply { minWidth = 64; width = 64 }
            columnModel.getColumn(2).apply { minWidth = 48; maxWidth = 48 }
            columnModel.getColumn(3).apply { minWidth = 96; width = 96 }
            columnModel.getColumn(4).apply { minWidth = 96; width = 96 }
            tableHeader.resizingColumn = columnModel.getColumn(5)
            // Center the spreadsheet name, record number, column name, and cell value columns.
            for (colIdx in 1..4)
                columnModel.getColumn(colIdx).cellRenderer =
                    DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.CENTER }
            // Allow for word wrapping and HTML display in the message column.
            columnModel.getColumn(5).cellRenderer = WordWrapCellRenderer(allowHtml = true, shrink = true)
        }
        val logTablePanel = JPanel(MigLayout()).apply {
            add(JScrollPane(logTable), "grow, push")
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, topPanel, logTablePanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater {
            splitPane.setDividerLocation(0.85)
            SwingUtilities.invokeLater { splitPane.setDividerLocation(0.85) }
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
        keyListeners.add(KeyListener(VK_O, CTRL_DOWN_MASK) { overlaysMenu.toggle() })
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
                l10n("ui.edit.unsavedChangesWarnings.title"),
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

    fun updateCreditsPolling(possible: Boolean) {
        pollCreditsButton.isEnabled = possible
    }

    fun updateLog(log: List<ParserMsg>) {
        // Put the new parser log messages into the log table.
        logTableModel.log = log
        // If there are errors in the log and updateProject() isn't called, an erroneous project has been opened.
        // In that case, show the big error mark.
        if (log.any { it.severity == Severity.ERROR } && drawnProject == null)
            previewPanelCards.show(previewPanel, "Error")
    }

    fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject
        // Update the pages tabs.
        previewPanelCards.show(previewPanel, "Preview")
        refreshCreditsTabs()
        // Adjust the total runtime label.
        displayRuntimeOfSelectedCredits()
        updateDeferredImagePanelPresentationStatuses()
    }

    private fun displayRuntimeOfSelectedCredits() {
        val drawnProject = this.drawnProject ?: return
        val drawnCredits = drawnProject.drawnCredits.getOrNull(creditsTabs.selectedIndex) ?: return
        val global = drawnProject.project.styling.global
        val runtime = drawnCredits.video.numFrames
        val tc = formatTimecode(global.fps, global.timecodeFormat, runtime)
        val tooltip = l10n("ui.edit.runtimeTooltip", runtime)
        runtimeLabel.text = tc
        runtimeLabel.toolTipText = tooltip
    }

    private fun updateDeferredImagePanelPresentationStatuses() {
        for (creditsIdx in 0..<creditsTabs.tabCount) {
            val pageTabs = creditsTabs.getComponentAt(creditsIdx) as JTabbedPane
            for (pageIdx in 0..<pageTabs.tabCount)
                (pageTabs.getComponentAt(pageIdx) as DeferredImagePanel).isPresented =
                    creditsTabs.selectedIndex == creditsIdx && pageTabs.selectedIndex == pageIdx
        }
    }

    private fun refreshCreditsTabs() {
        val drawnProject = this.drawnProject ?: return
        // First adjust the number of tabs to the number of credits spreadsheets.
        val numCredits = drawnProject.drawnCredits.size
        while (creditsTabs.tabCount > numCredits)
            creditsTabs.removeTabAt(creditsTabs.tabCount - 1)
        while (creditsTabs.tabCount < numCredits) {
            val pageTabs = newPreviewTabbedPane().apply {
                putClientProperty(TABBED_PANE_SHOW_CONTENT_SEPARATOR, false)
                addChangeListener { updateDeferredImagePanelPresentationStatuses() }
            }
            creditsTabs.addTab("", TABLE_ICON, pageTabs)
        }
        // Then fill each tab with its corresponding credits.
        for ((idx, drawnCredits) in drawnProject.drawnCredits.withIndex()) {
            creditsTabs.setTitleAt(idx, drawnCredits.credits.spreadsheetName)
            refreshPageTabs(creditsTabs.getComponentAt(idx) as JTabbedPane, drawnProject, drawnCredits)
        }
    }

    private fun refreshPageTabs(pageTabs: JTabbedPane, drawnProject: DrawnProject, drawnCredits: DrawnCredits) {
        // First adjust the number of tabs to the number of pages.
        val numPages = drawnCredits.drawnPages.size
        while (pageTabs.tabCount > numPages)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < numPages) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val imagePanel = DeferredImagePanel(MAX_ZOOM.toDouble(), ZOOM_INCREMENT, highResCache, lowResCache).apply {
                zoom = zoomSlider.zoom
                layers = getVisibleLayers()
                zoomListeners.add { zoom -> zoomSlider.zoom = zoom }
            }
            pageTabs.addTab(tabTitle, PAGE_ICON, imagePanel)
        }
        // Then fill each tab with its corresponding page, which also now has the overlays drawn onto it.
        // Also make the currently selected layers and overlays visible.
        val layers = getVisibleLayers()
        for ((idx, drawnPage) in drawnCredits.drawnPages.withIndex()) {
            val image = drawnPage.defImage.copy()
            for (overlay in availableOverlays)
                overlay.draw(drawnProject.project.styling.global.resolution, drawnPage.stageInfo, image)
            val imagePanel = pageTabs.getComponentAt(idx) as DeferredImagePanel
            imagePanel.setImageAndGroundingAndLayers(image, drawnProject.project.styling.global.grounding, layers)
        }
    }

    var availableOverlays: List<Overlay> = emptyList()
        set(overlays) {
            if (field == overlays)
                return
            field = overlays
            val selectedUUIDs = getSelectedOverlays().mapTo(HashSet(), Overlay::uuid)
            overlaysMenu.removeAll()
            for (overlay in overlays) {
                val menuItem = object : DropdownPopupMenuCheckBoxItem<Overlay>(
                    overlaysMenu, overlay, overlay.label, overlay.icon, isSelected = overlay.uuid in selectedUUIDs
                ) {
                    override fun onToggle() {
                        refreshVisibleLayers()
                    }
                }
                overlaysMenu.add(menuItem)
            }
            overlaysMenu.add(JSeparator())
            overlaysMenu.add(JMenuItem(l10n("ui.edit.overlaysAdd"), ARROW_RIGHT_ICON).apply {
                addActionListener { ctrl.masterCtrl.showWelcomeFrame(tab = WelcomeTab.PREFERENCES) }
            })
            overlaysMenu.pack()
            // Re-draw the overlays onto the page images.
            refreshCreditsTabs()
        }

    private fun refreshVisibleLayers() {
        val visibleLayers = getVisibleLayers()
        for (imagePanel in imagePanels)
            imagePanel.layers = visibleLayers
    }

    private fun getVisibleLayers(): List<DeferredImage.Layer> {
        val layers = mutableListOf<DeferredImage.Layer>()
        val overlays = mutableListOf<DeferredImage.Layer>()
        for (overlay in getSelectedOverlays())
            (if (overlay is ImageOverlay && overlay.underlay) layers else overlays) += overlay
        layers += STATIC
        layers += TAPES
        if (guidesToggleButton.isSelected)
            layers += GUIDES
        layers += overlays
        return layers
    }

    private fun getSelectedOverlays(): List<Overlay> = buildList {
        for (idx in 0..<overlaysMenu.componentCount) {
            val menuItem = overlaysMenu.getComponent(idx)
            if (menuItem is DropdownPopupMenuCheckBoxItem<*> && menuItem.isSelected)
                add(menuItem.item as Overlay)
        }
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
        override fun getColumnCount() = 6

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> ""
            1 -> l10n("ui.edit.sheet")
            2 -> l10n("ui.edit.record")
            3 -> l10n("ui.edit.column")
            4 -> l10n("ui.edit.value")
            5 -> l10n("ui.edit.message")
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> log[rowIdx].severity.icon
            1 -> log[rowIdx].spreadsheetName ?: ""
            2 -> log[rowIdx].recordNo?.plus(1) ?: ""
            3 -> log[rowIdx].colHeader ?: ""
            4 -> log[rowIdx].cellValue ?: ""
            5 -> log[rowIdx].msg
            else -> throw IllegalArgumentException()
        }

    }

}
