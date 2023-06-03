package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.ui.ProjectController
import java.awt.GraphicsConfiguration
import java.awt.event.KeyEvent
import java.nio.file.Path


interface MasterCtrlComms {

    fun onGlobalKeyEvent(event: KeyEvent): Boolean
    fun showWelcomeFrame(openProjectDir: Path? = null, tab: WelcomeTab? = null)
    fun showPreferences()
    fun tryCloseProjectsAndDisposeAllFrames(force: Boolean = false): Boolean

    // ========== FOR WELCOME CTRL ==========

    fun onCloseWelcomeFrame()
    /** @throws ProjectController.ProjectInitializationAbortedException */
    fun openProject(projectDir: Path, openOnScreen: GraphicsConfiguration)
    fun isProjectDirOpen(projectDir: Path): Boolean

}
