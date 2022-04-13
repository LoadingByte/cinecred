package com.loadingbyte.cinecred.ui

import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.*
import javax.swing.JComponent
import javax.swing.UIManager


object MasterController {

    private var welcomeCtrl: WelcomeController? = null
    private val _projectCtrls = mutableListOf<ProjectController>()

    fun onGlobalKeyEvent(event: KeyEvent) =
        _projectCtrls.any { it.onGlobalKeyEvent(event) }

    fun applyUILocaleWish() {
        val locale = PreferencesStorage.uiLocaleWish.resolve()
        Locale.setDefault(locale)
        UIManager.getDefaults().defaultLocale = locale
        UIManager.getLookAndFeelDefaults().defaultLocale = locale
        JComponent.setDefaultLocale(locale)
    }

    fun showWelcomeFrame(): WelcomeController {
        if (welcomeCtrl == null)
            welcomeCtrl = WelcomeController(onClose = { welcomeCtrl = null }, MasterController::onOpenProject)
        return welcomeCtrl!!
    }

    private fun onOpenProject(projectDir: Path, openOnScreen: GraphicsConfiguration) {
        lateinit var projectCtrl: ProjectController
        projectCtrl = ProjectController(projectDir, openOnScreen, onClose = { onCloseProject(projectCtrl) })
        _projectCtrls.add(projectCtrl)
    }

    private fun onCloseProject(projectCtrl: ProjectController) {
        _projectCtrls.remove(projectCtrl)
        // When there are no more open projects, let the OpenFrame reappear.
        if (_projectCtrls.isEmpty())
            showWelcomeFrame()
    }

    val projectCtrls: List<ProjectController>
        get() = _projectCtrls

    fun tryCloseProjectsAndDisposeAllFrames(force: Boolean = false) {
        for (projectCtrl in _projectCtrls.toMutableList() /* copy to avoid concurrent modification */)
            if (!projectCtrl.tryCloseProject(force) && !force)
                return

        welcomeCtrl?.close()

        if (force)
            for (window in Window.getWindows())
                window.dispose()
    }

}
