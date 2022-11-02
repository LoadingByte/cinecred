package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.comms.UIFactoryComms
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.makeProjectHintTrack
import com.loadingbyte.cinecred.ui.play
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.*
import javax.swing.JComponent
import javax.swing.UIManager


class MasterCtrl(private val uiFactory: UIFactoryComms) : MasterCtrlComms {

    private var welcomeCtrl: WelcomeCtrlComms? = null
    private val projectCtrls = mutableListOf<ProjectController>()

    override fun onGlobalKeyEvent(event: KeyEvent) =
        welcomeCtrl?.onGlobalKeyEvent(event) ?: false || projectCtrls.any { it.onGlobalKeyEvent(event) }

    override fun applyUILocaleWish() {
        val locale = PersistentStorage.uiLocaleWish.locale
        Locale.setDefault(locale)
        UIManager.getDefaults().defaultLocale = locale
        UIManager.getLookAndFeelDefaults().defaultLocale = locale
        JComponent.setDefaultLocale(locale)
    }

    override fun showWelcomeFrame(openProjectDir: Path?) {
        if (welcomeCtrl == null)
            welcomeCtrl = uiFactory.welcomeCtrlViewPair(this)
        welcomeCtrl!!.commence(openProjectDir)
    }

    override fun showPreferences() {
        showWelcomeFrame()
        welcomeCtrl!!.setTab(WelcomeTab.PREFERENCES)
    }

    override fun tryCloseProjectsAndDisposeAllFrames(force: Boolean): Boolean {
        for (projectCtrl in projectCtrls.toMutableList() /* copy to avoid concurrent modification */)
            if (!projectCtrl.tryCloseProject(force) && !force)
                return false

        welcomeCtrl?.close()

        if (force)
            for (window in Window.getWindows())
                window.dispose()

        return true
    }

    override fun onCloseWelcomeFrame() {
        welcomeCtrl = null
    }

    override fun openProject(projectDir: Path, openOnScreen: GraphicsConfiguration) {
        lateinit var projectCtrl: ProjectController
        projectCtrl = ProjectController(this, projectDir, openOnScreen, onClose = { onCloseProject(projectCtrl) })
        projectCtrls.add(projectCtrl)
    }

    private fun onCloseProject(projectCtrl: ProjectController) {
        projectCtrls.remove(projectCtrl)
        // When there are no more open projects, let the welcome window reappear.
        if (projectCtrls.isEmpty())
            showWelcomeFrame()
    }

    override fun isProjectDirOpen(projectDir: Path): Boolean =
        projectCtrls.any { it.projectDir == projectDir }

    override fun tryPlayProjectHintTrack() {
        val projectCtrl = projectCtrls.firstOrNull() ?: return
        makeProjectHintTrack(projectCtrl).play { PersistentStorage.projectHintTrackPending = false }
    }

}
