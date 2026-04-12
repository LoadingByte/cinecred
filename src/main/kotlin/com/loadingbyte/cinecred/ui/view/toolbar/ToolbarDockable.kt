package com.loadingbyte.cinecred.ui.view.toolbar

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nEnum
import com.loadingbyte.cinecred.ui.ConfigurableWindowLayout
import com.loadingbyte.cinecred.ui.Overlay
import com.loadingbyte.cinecred.ui.Shortcut.*
import com.loadingbyte.cinecred.ui.WindowLayout
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.view.playback.PlaybackControlsPanel
import net.miginfocom.layout.PlatformDefaults
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


class ToolbarDockable(private val toolbarCtrl: ToolbarCtrlComms, playbackCtrl: PlaybackCtrlComms) :
    DockingFrame.Dockable(
        dockableId = DockableId.TOOLBAR.name,
        title = l10n("ui.toolbar.title"),
        icon = DockableId.TOOLBAR.icon,
        vResizable = false,
        collapsible = false
    ),
    ToolbarViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedUndoStylingButton get() = undoStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedRedoStylingButton get() = redoStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedSaveStylingButton get() = saveStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedResetStylingButton get() = resetStylingButton
    @Deprecated("ENCAPSULATION LEAK") val leakedGuidesButton get() = guidesToggleButton
    @Deprecated("ENCAPSULATION LEAK") val leakedOverlaysButton get() = overlaysButton
    @Deprecated("ENCAPSULATION LEAK") val leakedPreviewButton get() = dockableToggleButtons[DockableId.PREVIEW]!!
    @Deprecated("ENCAPSULATION LEAK") val leakedLogButton get() = dockableToggleButtons[DockableId.LOG]!!
    @Deprecated("ENCAPSULATION LEAK") val leakedStylingButton get() = dockableToggleButtons[DockableId.STYLING]!!
    @Deprecated("ENCAPSULATION LEAK") val leakedPlaybackButton get() = dockableToggleButtons[DockableId.PLAYBACK]!!
    @Deprecated("ENCAPSULATION LEAK") val leakedDeliveryButton get() = dockableToggleButtons[DockableId.DELIVERY]!!
    @Deprecated("ENCAPSULATION LEAK") val leakedPlaybackControls get() = playbackControls
    // =========================================

    private val pollCreditsButton =
        newToolbarButton(REFRESH_ICON, l10n("ui.edit.pollCredits"), TOOLBAR_POLL_CREDITS, toolbarCtrl::pollCredits)
    private val openCreditsButton =
        newToolbarButton(TABLE_ICON, l10n("ui.edit.openCreditsFile"), TOOLBAR_OPEN_CREDITS, toolbarCtrl::openCredits)
    private val browseProjectDirButton =
        newToolbarButton(
            FOLDER_ICON, l10n("ui.edit.browseProjectDir"), TOOLBAR_BROWSE_PROJECT_DIR, toolbarCtrl::browseProjectDir
        )

    private val undoStylingButton =
        newToolbarButton(UNDO_ICON, l10n("ui.edit.undoStyling"), TOOLBAR_UNDO_STYLING, toolbarCtrl::undoStyling)
    private val redoStylingButton =
        newToolbarButton(REDO_ICON, l10n("ui.edit.redoStyling"), TOOLBAR_REDO_STYLING, toolbarCtrl::redoStyling)
    private val saveStylingButton =
        newToolbarButton(SAVE_ICON, l10n("ui.edit.saveStyling"), TOOLBAR_SAVE_STYLING, toolbarCtrl::saveStyling)
    private val resetStylingButton =
        newToolbarButton(RESET_ICON, l10n("ui.edit.resetStyling"), TOOLBAR_RESET_STYLING, toolbarCtrl::resetStyling)

    // Clipping the string instead of having ellipsis is much nicer for a very short string like this one.
    private val unsavedStylingLabel = JLabel(noEllipsisLabel(l10n("ui.edit.unsavedChanges"))).apply {
        isVisible = false
        toolTipText = text
        putClientProperty(STYLE_CLASS, "small")
    }

    private val zoomSlider = ZoomSlider().apply {
        isFocusable = false
        addChangeListener { toolbarCtrl.onChangeZoom(zoom) }
    }

    private val guidesToggleButton = newToolbarToggleButton(
        GUIDES_ICON, tooltip = "<html>" + l10n("ui.edit.guidesTooltip.title") + "<br>" +
                "<font color=\"#FF64FF\">\u2014</font> " + l10n("ui.edit.guidesTooltip.page") + "<br>" +
                "<font color=\"#00FFFF\">\u2014</font> " + l10n("ui.edit.guidesTooltip.spine") + "<br>" +
                "<font color=\"FF6500\">\u2014</font> " + l10n("ui.edit.guidesTooltip.bodyCell") + "<br>" +
                "<font color=\"#FF0000\">\u2014</font> " + l10n("ui.edit.guidesTooltip.bodyWidth") + "<br>" +
                "<font color=\"#00CA00\">\u2014</font> " + l10n("ui.edit.guidesTooltip.headTail") + "</html>",
        TOOLBAR_TOGGLE_GUIDES, listener = toolbarCtrl::onToggleGuides
    )

    private val overlaysButton = newToolbarButton(ADVANCED_ICON, l10n("ui.edit.overlaysTooltip"), TOOLBAR_OVERLAY_MENU)
    private val overlaysMenu: DropdownPopupMenu = DropdownPopupMenu(overlaysButton)
        .apply { addMouseListenerTo(overlaysButton) }

    private val runtimeLabel = JLabel("\u2014").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        putClientProperty(STYLE_CLASS, "monospaced")
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            }
        })
    }

    private val windowLayoutsButton = newToolbarButton(WINDOW_LAYOUT_ICON, l10n("ui.toolbar.windowLayoutsTooltip"))
    private val windowLayoutsMenu: DropdownPopupMenu = DropdownPopupMenu(windowLayoutsButton)
        .apply { addMouseListenerTo(windowLayoutsButton) }

    private val dockableToggleButtons =
        listOf(
            Triple(DockableId.PREVIEW, "ui.preview.title", null),
            Triple(DockableId.LOG, "ui.log.title", null),
            Triple(DockableId.STYLING, "ui.styling.title", TOOLBAR_TOGGLE_STYLING_DOCKABLE),
            Triple(DockableId.PLAYBACK, "ui.video.title", TOOLBAR_TOGGLE_PLAYBACK_DOCKABLE),
            Triple(DockableId.DELIVERY, "ui.delivery.title", TOOLBAR_TOGGLE_DELIVERY_DOCKABLE)
        ).associate { (dockableId, l10nKey, shortcut) ->
            val tooltip = l10n("ui.toolbar.toggleDockable", l10n(l10nKey))
            dockableId to newToolbarToggleButton(dockableId.icon, tooltip, shortcut) { selected ->
                toolbarCtrl.onChangeDockableCollapsed(dockableId, !selected)
            }
        }

    private val homeButton = newToolbarButton(HOME_ICON, l10n("ui.edit.home"), TOOLBAR_HOME) {
        toolbarCtrl.showWelcomeFrame()
    }

    private val playbackControls = PlaybackControlsPanel(playbackCtrl).apply {
        isVisible = false
    }

    init {
        toolbarCtrl.registerView(this)

        // Credits polling is usually disabled; it will be enabled when it's available.
        setCreditsPollable(false)

        val zoomTooltip = l10n("ui.edit.zoom") + " (" +
                l10nEnum(TOOLBAR_ZOOM_IN.hint, TOOLBAR_ZOOM_OUT.hint, TOOLBAR_ZOOM_RESET.hint) + ")"
        zoomSlider.toolTipText = zoomTooltip

        val runtimeDescLabel = JLabel(l10n("ui.edit.runtime")).apply {
            toolTipText = text
        }

        val row1Cols = """
                []0[]0[]
                6[]6
                []0[]0[]0[]6[]
                12[]12
                []6[]6[]0[]
        """
        val row1 = JPanel(MigLayout("insets 0", row1Cols)).apply {
            add(pollCreditsButton)
            add(openCreditsButton)
            add(browseProjectDirButton)
            add(JSeparator(JSeparator.VERTICAL), "growy")
            add(undoStylingButton)
            add(redoStylingButton)
            add(saveStylingButton)
            add(resetStylingButton)
            add(unsavedStylingLabel)
            add(JSeparator(JSeparator.VERTICAL), "growy")
            add(JLabel(ZOOM_ICON).apply { toolTipText = zoomTooltip })
            add(zoomSlider, "width 50")
            add(guidesToggleButton)
            add(overlaysButton)
        }

        val row2Cols = """
                []6[]
                12[]6
                []${"0[]".repeat(dockableToggleButtons.size)}
                8[]5
                []
        """
        val row2 = JPanel(MigLayout("insets 0", row2Cols)).apply {
            add(runtimeDescLabel, "wmin min(pref,60)")
            add(runtimeLabel)
            add(JSeparator(JSeparator.VERTICAL), "growy, wmin pref")
            add(windowLayoutsButton)
            dockableToggleButtons.values.forEach(::add)
            add(JSeparator(JSeparator.VERTICAL), "growy, wmin pref")
            add(homeButton)
        }

        val rowSep = JPanel(BorderLayout()).apply {
            add(JSeparator(JSeparator.VERTICAL), BorderLayout.CENTER)
            border = BorderFactory.createEmptyBorder(0, 6, 0, 12)
        }
        val autoWrappingRowsPanel = JPanel().apply {
            layout = AutoWrappingRowsLayout(vGap = PlatformDefaults.getUnitValueX("rel").value.roundToInt())
            add(row1)
            add(row2)
            add(rowSep)
        }

        layout = MigLayout("fillx", "[fill]")
        add(autoWrappingRowsPanel)
        add(playbackControls, "newline, gapx 8 8, hidemode 3")
    }

    private fun getSelectedOverlays(): List<Overlay> = buildList {
        for (idx in 0..<overlaysMenu.componentCount) {
            val menuItem = overlaysMenu.getComponent(idx)
            if (menuItem is DropdownPopupMenuCheckBoxItem<*> && menuItem.isSelected)
                add(menuItem.item as Overlay)
        }
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setCreditsPollable(pollable: Boolean) {
        pollCreditsButton.isEnabled = pollable
    }

    override fun setCreditsOpenable(openable: Boolean) {
        openCreditsButton.isEnabled = openable
    }

    override fun setStylingUnsaved(isUnsaved: Boolean) {
        resetStylingButton.isEnabled = isUnsaved
        unsavedStylingLabel.isVisible = isUnsaved
    }

    override fun setStylingUnReDoable(isUndoable: Boolean, isRedoable: Boolean) {
        undoStylingButton.isEnabled = isUndoable
        redoStylingButton.isEnabled = isRedoable
    }

    override fun setAvailableOverlays(availableOverlays: List<Overlay>) {
        val selectedUUIDs = getSelectedOverlays().mapTo(HashSet(), Overlay::uuid)
        overlaysMenu.removeAll()
        for (overlay in availableOverlays) {
            val menuItem = object : DropdownPopupMenuCheckBoxItem<Overlay>(
                overlaysMenu, overlay, overlay.label, overlay.icon, isSelected = overlay.uuid in selectedUUIDs
            ) {
                override fun onToggle() {
                    toolbarCtrl.onSelectOverlays(getSelectedOverlays())
                }
            }
            overlaysMenu.add(menuItem)
        }
        overlaysMenu.add(JSeparator())
        overlaysMenu.add(JMenuItem(l10n("ui.edit.overlaysAdd"), ARROW_RIGHT_ICON).apply {
            addActionListener { toolbarCtrl.showOverlayCreation() }
        })
        overlaysMenu.pack()
        toolbarCtrl.onSelectOverlays(getSelectedOverlays())
    }

    override fun setAvailableWindowLayouts(availableLayouts: List<WindowLayout>) {
        windowLayoutsMenu.removeAll()
        for (layout in availableLayouts)
            windowLayoutsMenu.add(JMenuItem(layout.label, WINDOW_LAYOUT_ICON).also {
                it.addActionListener { toolbarCtrl.onSelectWindowLayout(layout, graphicsConfiguration) }
            })
        windowLayoutsMenu.add(JSeparator())
        val saveMenuItem = JMenuItem(l10n("ui.toolbar.windowLayoutsSave"), ADD_ICON)
        saveMenuItem.addActionListener {
            val comboBox = JComboBox(
                availableLayouts.filterIsInstance<ConfigurableWindowLayout>().map(WindowLayout::name).toTypedArray()
            ).apply {
                isEditable = true
                selectedItem = ""
            }
            val c = JPanel(MigLayout("insets 0, wrap")).apply {
                add(JLabel(l10n("ui.toolbar.windowLayoutsSaveName.msg")))
                add(comboBox, "growx")
            }
            val parent = SwingUtilities.getWindowAncestor(this)
            val title = l10n("ui.toolbar.windowLayoutsSaveName.title")
            if (JOptionPane.showConfirmDialog(parent, c, title, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                toolbarCtrl.saveWindowLayout(comboBox.selectedItem as String)
        }
        windowLayoutsMenu.add(saveMenuItem)
        windowLayoutsMenu.pack()
    }

    // @formatter:off
    override fun setPlaybackControlsVisible(visible: Boolean) { playbackControls.isVisible = visible }
    override fun setZoom(zoom: Double) { zoomSlider.zoom = zoom }
    override fun toggleGuides() { guidesToggleButton.isSelected = !guidesToggleButton.isSelected }
    override fun toggleOverlaysMenu() = overlaysMenu.toggle()
    // @formatter:on

    override fun setRuntime(timecode: String?, frames: Int) {
        runtimeLabel.text = timecode ?: "\u2014"
        runtimeLabel.toolTipText = timecode?.let { l10n("ui.edit.runtimeTooltip", frames) }
    }

    override fun setDockableCollapsed(dockableId: DockableId, collapsed: Boolean) {
        dockableToggleButtons[dockableId]?.isSelected = !collapsed
    }

    override fun showResetUnsavedChangesQuestion(): Boolean {
        val options = arrayOf(l10n("ui.edit.resetUnsavedChangesWarning.discard"), l10n("cancel"))
        val selectedOption = JOptionPane.showOptionDialog(
            SwingUtilities.getWindowAncestor(this), l10n("ui.edit.resetUnsavedChangesWarning.msg"),
            l10n("ui.edit.unsavedChangesWarnings.title"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]
        )
        return selectedOption != JOptionPane.CLOSED_OPTION && selectedOption != 1
    }

    override fun showCloseDespiteUnsavedChangesQuestion(allowCancel: Boolean): CloseDespiteUnsavedChangesAnswer {
        val options = mutableListOf(
            l10n("ui.edit.openUnsavedChangesWarning.save"), l10n("ui.edit.openUnsavedChangesWarning.discard")
        )
        if (allowCancel)
            options.add(l10n("cancel"))
        val selectedOption = JOptionPane.showOptionDialog(
            SwingUtilities.getWindowAncestor(this), l10n("ui.edit.openUnsavedChangesWarning.msg"),
            l10n("ui.edit.unsavedChangesWarnings.title"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options.toTypedArray(), options[0]
        )
        return when (selectedOption) {
            0 -> CloseDespiteUnsavedChangesAnswer.SAVE
            1 -> CloseDespiteUnsavedChangesAnswer.DISCARD
            else -> CloseDespiteUnsavedChangesAnswer.CANCEL
        }
    }


    private class AutoWrappingRowsLayout(private val vGap: Int) : LayoutManager {

        override fun addLayoutComponent(name: String, comp: Component) {}
        override fun removeLayoutComponent(comp: Component) {}
        override fun minimumLayoutSize(parent: Container) = preferredLayoutSize(parent)

        override fun preferredLayoutSize(parent: Container): Dimension {
            val row1Pref = parent.row1.preferredSize
            val row2Pref = parent.row2.preferredSize
            return Dimension(
                max(row1Pref.width, row2Pref.width),
                if (oneLine(parent)) max(row1Pref.height, row2Pref.height) else row1Pref.height + row2Pref.height + vGap
            )
        }

        override fun layoutContainer(parent: Container) {
            val row1 = parent.row1
            val row2 = parent.row2
            val sep = parent.sep
            val row1Pref = row1.preferredSize
            val row2Pref = row2.preferredSize
            val oneLine = oneLine(parent)
            if (oneLine) {
                val sepPref = sep.preferredSize
                // Row 2 can shrink.
                val row2Width = min(row2Pref.width, parent.width - row1Pref.width - sepPref.width)
                val row1X = (parent.width - (row1Pref.width + row2Width + sepPref.width)) / 2
                row1.setBounds(row1X, 0, row1Pref.width, row1Pref.height)
                row2.setBounds(row1X + row1Pref.width + sepPref.width, 0, row2Width, row2Pref.height)
                sep.setBounds(row1X + row1Pref.width, 0, sepPref.width, max(row1Pref.height, row2Pref.height))
            } else {
                val row1Width = min(row1Pref.width, parent.width)
                val row2Width = min(row2Pref.width, parent.width)
                row1.setBounds((parent.width - row1Width) / 2, 0, row1Width, row1Pref.height)
                row2.setBounds((parent.width - row2Width) / 2, row1Pref.height + vGap, row2Width, row2Pref.height)
            }
            if (sep.isVisible != oneLine) {
                sep.isVisible = oneLine
                // Wrapping layouts are tricky, because preferredSize() is usually called BEFORE the width of the parent
                // container has been adjusted, and layoutContainer() is called AFTERWARDS; hence, the first might
                // determine there's no wrapping needed (thus returning a preferred height of a single row), while the
                // second then actually goes on putting components on a second row. The only way to reliably get around
                // that issue in this situation turned out to just force another layout pass if the wrapping changes.
                SwingUtilities.invokeLater { parent.revalidate() }
            }
        }

        private fun oneLine(parent: Container) =
            parent.width >=
                    parent.row1.preferredSize.width + parent.row2.minimumSize.width + parent.sep.preferredSize.width

        private val Container.row1 get() = getComponent(0)
        private val Container.row2 get() = getComponent(1)
        private val Container.sep get() = getComponent(2)

    }


    private class ZoomSlider : JSlider(0, MAX_ZOOM * 100, 0) {

        var zoom: Double
            get() = 1.0 + value * (MAX_ZOOM - 1.0) / maximum
            set(newZoom) {
                value = ((newZoom - 1.0) * maximum / (MAX_ZOOM - 1.0)).roundToInt()
            }

    }

}
