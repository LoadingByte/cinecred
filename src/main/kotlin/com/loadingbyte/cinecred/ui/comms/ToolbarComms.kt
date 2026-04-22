package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.ui.Overlay
import com.loadingbyte.cinecred.ui.WindowLayout
import java.awt.GraphicsConfiguration


interface ToolbarCtrlComms {

    fun registerView(view: ToolbarViewComms)

    fun ready()
    fun updateProject(drawnProject: DrawnProject)
    fun onTryCloseProject(force: Boolean): Boolean
    fun setCreditsPollable(pollable: Boolean)
    fun setStylingUnsaved(isUnsaved: Boolean)
    fun setStylingUnReDoable(isUndoable: Boolean, isRedoable: Boolean)
    fun setAvailableOverlays(availableOverlays: List<Overlay>)
    fun setAvailableWindowLayouts(availableLayouts: List<WindowLayout>)
    fun setSelectedDrawnCredits(drawnCreditsBook: DrawnCreditsBook?, drawnCredits: DrawnCredits?)

    fun setZoom(zoom: Double)
    fun zoom(zoomIn: Boolean)
    fun toggleGuides()
    fun toggleOverlaysMenu()
    fun flashWindowLayoutLockedButton()
    fun setDockableCollapsed(dockableId: DockableId, collapsed: Boolean)

    fun pollCredits()
    fun openCredits()
    fun browseProjectDir()
    fun undoStyling()
    fun redoStyling()
    fun saveStyling()
    fun resetStyling()
    fun onChangeZoom(zoom: Double)
    fun onToggleGuides(guides: Boolean)
    fun onSelectOverlays(selectedOverlays: List<Overlay>)
    fun onSelectWindowLayout(selectedLayout: WindowLayout, onScreen: GraphicsConfiguration)
    fun saveWindowLayout(name: String)
    fun onToggleWindowLayoutLocked(locked: Boolean)
    fun onChangeDockableCollapsed(dockableId: DockableId, collapsed: Boolean)
    fun showWelcomeFrame()
    fun showOverlayCreation()

}


interface ToolbarViewComms {

    fun setCreditsPollable(pollable: Boolean)
    fun setCreditsOpenable(openable: Boolean)
    fun setStylingUnsaved(isUnsaved: Boolean)
    fun setStylingUnReDoable(isUndoable: Boolean, isRedoable: Boolean)
    fun setAvailableOverlays(availableOverlays: List<Overlay>)
    fun setAvailableWindowLayouts(availableLayouts: List<WindowLayout>)
    fun setPlaybackControlsVisible(visible: Boolean)

    fun setZoom(zoom: Double)
    fun toggleGuides()
    fun toggleOverlaysMenu()
    fun setRuntime(timecode: String?, frames: Int)
    fun setWindowLayoutLocked(locked: Boolean)
    fun flashWindowLayoutLockedButton()
    fun setDockableCollapsed(dockableId: DockableId, collapsed: Boolean)

    fun showResetUnsavedChangesQuestion(): Boolean
    fun showCloseDespiteUnsavedChangesQuestion(allowCancel: Boolean): CloseDespiteUnsavedChangesAnswer?

}


enum class CloseDespiteUnsavedChangesAnswer { SAVE, DISCARD, CANCEL }
