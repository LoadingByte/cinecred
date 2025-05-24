package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.isSameFileAsSafely
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
import javax.swing.JComboBox
import javax.swing.JTree
import javax.swing.text.JTextComponent


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

    override fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        if (event.isConsumed ||
            event.id != KeyEvent.KEY_PRESSED ||
            // Some components consume the KEY_TYPED instead of the KEY_PRESSED event. If we didn't do anything about
            // this, non-modifier key bindings (like L for playing the video preview) would activate while, e.g., typing
            // in a text field. So as a workaround, we just block any non-modifier key events when any component
            // susceptible to this behavior is focused.
            event.modifiersEx == 0 && event.component.let { it is JTextComponent || it is JComboBox<*> || it is JTree }
        ) return false
        welcomeCtrl?.let { if (it.onGlobalKeyEvent(event)) return true }
        return projectCtrls.any { it.onGlobalKeyEvent(event) }
    }

    override fun showWelcomeFrame(openProjectDir: Path?, tab: WelcomeTab?) {
        if (welcomeCtrl == null)
            welcomeCtrl = uiFactory.welcomeCtrlViewPair(this)
        tab?.let(welcomeCtrl!!::setTab)
        welcomeCtrl!!.commence(openProjectDir)
    }

    override fun showOverlayCreation() {
        showWelcomeFrame()
        welcomeCtrl!!.showOverlayCreation()
    }

    override fun showDeliveryDestTemplateCreation() {
        showWelcomeFrame()
        welcomeCtrl!!.showDeliveryDestTemplateCreation()
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
        projectCtrls.any { it.projectDir.isSameFileAsSafely(projectDir) }

}
