package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.GUIDES
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import com.loadingbyte.cinecred.ui.helper.tryEdit
import com.loadingbyte.cinecred.ui.helper.tryOpen
import com.loadingbyte.cinecred.ui.helper.usableBounds
import java.awt.GraphicsConfiguration
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import kotlin.io.path.toPath


class ToolbarCtrl(private val projectCtrl: ProjectController) : ToolbarCtrlComms {

    private val views = mutableListOf<ToolbarViewComms>()

    private var drawnProject: DrawnProject? = null
    private var isStylingUnsaved = false

    private var zoom = 1.0
    private var guides = false
    private var selectedOverlays = emptyList<Overlay>()
    private var creditsBookURI: URI? = null

    private fun pushVisibleLayers() {
        val layers = mutableListOf<DeferredImage.Layer>()
        val overlays = mutableListOf<DeferredImage.Layer>()
        for (overlay in selectedOverlays)
            (if (overlay is ImageOverlay && overlay.underlay) layers else overlays) += overlay
        layers += STATIC
        layers += TAPES
        if (guides)
            layers += GUIDES
        layers += overlays
        projectCtrl.previewCtrl.setVisibleLayers(layers)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun registerView(view: ToolbarViewComms) {
        views += view
    }

    override fun ready() {
        // Hide the playback controls when there are no DeckLink cards available.
        projectCtrl.playbackCtrl.registerView(object : PlaybackViewComms {
            override fun setDeckLinks(deckLinks: List<DeckLink>) {
                for (view in views) view.setPlaybackControlsVisible(deckLinks.isNotEmpty())
            }
        })
        // The guides should be enabled by default.
        toggleGuides()
        // The window layout should be locked by default.
        for (view in views) view.setWindowLayoutLocked(true)
    }

    override fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject
    }

    override fun onTryCloseProject(force: Boolean): Boolean =
        if (isStylingUnsaved)
            when (views.firstNotNullOf { view -> view.showCloseDespiteUnsavedChangesQuestion(!force) }) {
                CloseDespiteUnsavedChangesAnswer.SAVE -> {
                    saveStyling()
                    true
                }
                CloseDespiteUnsavedChangesAnswer.DISCARD -> true
                CloseDespiteUnsavedChangesAnswer.CANCEL -> false
            }
        else
            true

    override fun setCreditsPollable(pollable: Boolean) = views.forEach { view -> view.setCreditsPollable(pollable) }

    override fun setStylingUnsaved(isUnsaved: Boolean) {
        isStylingUnsaved = isUnsaved
        for (view in views) view.setStylingUnsaved(isUnsaved)
        // On macOS, show an unsaved indicator inside the "close window" button.
        projectCtrl.projectFrame.setMacOSModifiedIndicator(isUnsaved)
    }

    override fun setStylingUnReDoable(isUndoable: Boolean, isRedoable: Boolean) {
        for (view in views) view.setStylingUnReDoable(isUndoable, isRedoable)
    }

    override fun setAvailableOverlays(availableOverlays: List<Overlay>) {
        for (view in views) view.setAvailableOverlays(availableOverlays)
    }

    override fun setAvailableWindowLayouts(availableLayouts: List<WindowLayout>) {
        for (view in views) view.setAvailableWindowLayouts(availableLayouts)
    }

    override fun setSelectedDrawnCredits(drawnCreditsBook: DrawnCreditsBook?, drawnCredits: DrawnCredits?) {
        creditsBookURI = drawnCreditsBook?.creditsBook?.uri
        for (view in views) view.setCreditsOpenable(creditsBookURI != null)

        val drawnProject = this.drawnProject
        if (drawnProject == null || drawnCredits == null)
            for (view in views) view.setRuntime(null, 0)
        else {
            val global = drawnProject.project.styling.global
            val runtime = drawnCredits.video.numFrames
            val tc = formatTimecode(global.fps, global.timecodeFormat, runtime)
            for (view in views) view.setRuntime(tc, runtime)
        }
    }

    override fun setZoom(zoom: Double) = views.forEach { view -> view.setZoom(zoom) }
    override fun zoom(zoomIn: Boolean) = setZoom(zoom + if (zoomIn) ZOOM_INCREMENT else -ZOOM_INCREMENT)
    override fun toggleGuides() = views.forEach { view -> view.toggleGuides() }
    override fun toggleOverlaysMenu() = views.forEach { view -> view.toggleOverlaysMenu() }
    override fun flashWindowLayoutLockedButton() = views.forEach { view -> view.flashWindowLayoutLockedButton() }
    override fun setDockableCollapsed(dockableId: DockableId, collapsed: Boolean) =
        views.forEach { view -> view.setDockableCollapsed(dockableId, collapsed) }

    override fun pollCredits() = projectCtrl.pollCredits()

    override fun openCredits() {
        creditsBookURI?.let { uri ->
            try {
                tryEdit(uri.toPath())
            } catch (_: FileSystemNotFoundException) {
                tryBrowse(uri)
            }
        }
    }

    override fun browseProjectDir() = tryOpen(projectCtrl.projectDir)
    override fun undoStyling() = projectCtrl.stylingHistory.undoAndRedraw()
    override fun redoStyling() = projectCtrl.stylingHistory.redoAndRedraw()
    override fun saveStyling() = projectCtrl.stylingHistory.save()

    override fun resetStyling() {
        if (!isStylingUnsaved || views.any { view -> view.showResetUnsavedChangesQuestion() })
            projectCtrl.stylingHistory.resetAndRedraw()
    }

    override fun onChangeZoom(zoom: Double) {
        this.zoom = zoom
        projectCtrl.previewCtrl.setZoom(zoom)
    }

    override fun onToggleGuides(guides: Boolean) {
        this.guides = guides
        pushVisibleLayers()
    }

    override fun onSelectOverlays(selectedOverlays: List<Overlay>) {
        this.selectedOverlays = selectedOverlays
        pushVisibleLayers()
    }

    override fun onSelectWindowLayout(selectedLayout: WindowLayout, onScreen: GraphicsConfiguration) {
        projectCtrl.windowLayoutTrees = selectedLayout.trees(onScreen.usableBounds)
    }

    override fun saveWindowLayout(name: String) {
        if (name.isBlank())
            return
        val layouts = WINDOW_LAYOUTS_PREFERENCE.get().toMutableList()
        layouts.removeIf { it.name == name }
        layouts.add(ConfigurableWindowLayout(name, projectCtrl.windowLayoutTrees))
        WINDOW_LAYOUTS_PREFERENCE.set(layouts)
    }

    override fun onToggleWindowLayoutLocked(locked: Boolean) {
        projectCtrl.projectFrame.isLocked = locked
    }

    override fun onChangeDockableCollapsed(dockableId: DockableId, collapsed: Boolean) {
        projectCtrl.setDockableCollapsed(dockableId, collapsed)
    }

    override fun showWelcomeFrame() = projectCtrl.masterCtrl.showWelcomeFrame()
    override fun showOverlayCreation() = projectCtrl.masterCtrl.showOverlayCreation()

}
