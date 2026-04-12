package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import java.awt.GraphicsConfiguration
import java.awt.event.KeyEvent
import java.nio.file.Path


interface MasterCtrlComms {

    fun preGlobalKeyEvent(event: KeyEvent): Boolean
    fun postGlobalKeyEvent(event: KeyEvent): Boolean
    fun showWelcomeFrame(openProjectDir: Path? = null, tab: WelcomeTab? = null)
    fun showOverlayCreation()
    fun showDeliveryDestTemplateCreation()
    fun tryCloseProjectsAndDisposeAllFrames(force: Boolean = false): Boolean

    // ========== FOR WELCOME CTRL ==========

    fun onCloseWelcomeFrame()
    /** @throws ProjectController.ProjectInitializationAbortedException */
    fun openProject(projectDir: Path, openOnScreen: GraphicsConfiguration?, trees: List<DockingFrame.Tree>? = null)
    fun isProjectDirOpen(projectDir: Path): Boolean

}
