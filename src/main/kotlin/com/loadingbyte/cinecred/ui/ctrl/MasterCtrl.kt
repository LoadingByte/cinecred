package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.ui.PROJECT_HINT_TRACK_PENDING_PREFERENCE
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


class MasterCtrl(private val uiFactory: UIFactoryComms) : MasterCtrlComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedWelcomeCtrl get() = welcomeCtrl
    @Deprecated("ENCAPSULATION LEAK") val leakedProjectCtrls get() = projectCtrls
    // =========================================

    private var welcomeCtrl: WelcomeCtrlComms? = null
    private val projectCtrls = mutableListOf<ProjectController>()

    init {
        PROJECT_HINT_TRACK_PENDING_PREFERENCE.addListener { pending ->
            if (pending) projectCtrls.firstOrNull()?.let { projectCtrl ->
                makeProjectHintTrack(projectCtrl).play(onPass = { PROJECT_HINT_TRACK_PENDING_PREFERENCE.set(false) })
            }
        }
    }

    override fun onGlobalKeyEvent(event: KeyEvent) =
        welcomeCtrl?.onGlobalKeyEvent(event) ?: false || projectCtrls.any { it.onGlobalKeyEvent(event) }

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
            if (!projectCtrl.tryCloseProject(force))
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
        var projectCtrl: ProjectController? = null
        projectCtrl = ProjectController(
            this, projectDir, openOnScreen, onClose = { projectCtrl?.let(::onCloseProject) }
        )
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

}
