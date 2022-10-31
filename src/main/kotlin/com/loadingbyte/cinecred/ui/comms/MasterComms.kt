package com.loadingbyte.cinecred.ui.comms

import java.awt.GraphicsConfiguration
import java.awt.event.KeyEvent
import java.nio.file.Path


interface MasterCtrlComms {

    fun onGlobalKeyEvent(event: KeyEvent): Boolean
    fun applyUILocaleWish()
    fun showWelcomeFrame(openProjectDir: Path? = null)
    fun showPreferences()
    fun tryCloseProjectsAndDisposeAllFrames(force: Boolean = false)
    fun getNumPendingJobsOfAllProjects(): Int

    // ========== FOR WELCOME CTRL ==========

    fun onCloseWelcomeFrame()
    fun openProject(projectDir: Path, openOnScreen: GraphicsConfiguration)
    fun isProjectDirOpen(projectDir: Path): Boolean
    fun tryPlayProjectHintTrack()

}