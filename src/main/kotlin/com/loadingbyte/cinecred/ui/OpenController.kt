package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyTemplate
import com.loadingbyte.cinecred.projectio.locateCreditsFile
import com.loadingbyte.cinecred.ui.ProjectController.Companion.STYLING_FILE_NAME
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane


object OpenController {

    private val projectCtrls = mutableListOf<ProjectController>()

    /**
     * When the user clicks on a project opening button multiple times while he is waiting for the project window to
     * open, we do not want this to really trigger multiple project openings, because that would confront him with
     * error messages like "this project is already opened". Hence, after the user opened a project, we just block
     * all other openings until the OpenFrame is again explicitly shown, in which case we unblock opening a project.
     */
    private var blockOpening = false

    fun getProjectCtrls(): List<ProjectController> = projectCtrls

    fun showOpenFrame() {
        blockOpening = false
        OpenPanel.onShow()
        OpenFrame.isVisible = true
    }

    fun tryOpenProject(projectDir: Path) {
        if (blockOpening || Files.isRegularFile(projectDir))
            return

        if (projectCtrls.any { it.projectDir == projectDir }) {
            JOptionPane.showMessageDialog(
                OpenFrame, l10n("ui.open.alreadyOpen.msg", projectDir), l10n("ui.open.alreadyOpen.title"),
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        // If the two required project files don't exist yet, create them.
        val projectDirExists = Files.exists(projectDir)
        val stylingFile = projectDir.resolve(STYLING_FILE_NAME)
        val creditsFile = if (!projectDirExists) null else locateCreditsFile(projectDir).first
        val stylingFileExists = Files.exists(stylingFile)
        val creditsFileExists = creditsFile != null
        if (Files.notExists(projectDir) || !stylingFileExists || !creditsFileExists) {
            // If the user cancels the dialog, cancel opening the project directory.
            val (locale, format) = CreateForm.showDialog() ?: return
            copyTemplate(projectDir, locale, format, !stylingFileExists, !creditsFileExists)
        }

        blockOpening = true

        // Memorize the opened project directory at the first position in the list.
        PreferencesController.memorizedProjectDirs =
            PreferencesController.memorizedProjectDirs.toMutableList().apply {
                remove(projectDir)
                add(0, projectDir)
            }

        // Find the screen on which the OpenFrame currently occupies the most area.
        // The project will be opened on that screen.
        val openOnScreen = OpenFrame.findMostOccupiedScreen()

        val projectCtrl = ProjectController(projectDir, openOnScreen)
        projectCtrls.add(projectCtrl)

        OpenFrame.isVisible = false
    }

    // Must only be called from ProjectController.
    fun onCloseProject(projectCtrl: ProjectController) {
        projectCtrls.remove(projectCtrl)

        // When there are no more open projects, let the OpenFrame reappear.
        if (projectCtrls.isEmpty())
            showOpenFrame()
    }

    fun onCloseOpenFrame() {
        if (projectCtrls.isEmpty())
            tryExit()
        else
            OpenFrame.isVisible = false
    }

    fun tryExit(force: Boolean = false) {
        for (projectCtrl in projectCtrls.toMutableList() /* copy to avoid concurrent modification */)
            if (!projectCtrl.tryCloseProject(force) && !force)
                return

        OpenFrame.dispose()

        if (force)
            for (window in Window.getWindows())
                window.dispose()
    }

}
