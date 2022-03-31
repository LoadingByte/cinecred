package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyTemplate
import com.loadingbyte.cinecred.projectio.locateCreditsFile
import com.loadingbyte.cinecred.ui.ProjectController.Companion.STYLING_FILE_NAME
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists


object OpenController {

    private var openFrame: OpenFrame? = null
    private var openFrameBounds: Rectangle? = null
    private val projectCtrls = mutableListOf<ProjectController>()

    /**
     * When the user clicks on a project opening button multiple times while he is waiting for the project window to
     * open, we do not want this to really trigger multiple project openings, because that would confront him with
     * error messages like "this project is already opened". Hence, after the user opened a project, we just block
     * all other openings until the OpenFrame is again explicitly shown, in which case we unblock opening a project.
     */
    private var blockOpening = false

    fun getOpenFrame(): OpenFrame? = openFrame
    fun getProjectCtrls(): List<ProjectController> = projectCtrls

    fun showOpenFrame() {
        if (openFrame == null) {
            blockOpening = false
            openFrame = OpenFrame()
        }
        if (openFrameBounds == null) {
            val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val openFrameWidth = maxWinBounds.width * 9 / 20
            val openFrameHeight = maxWinBounds.height / 2
            openFrameBounds = Rectangle(
                (maxWinBounds.width - openFrameWidth) / 2,
                (maxWinBounds.height - openFrameHeight) / 2,
                openFrameWidth, openFrameHeight
            )
        }
        openFrame!!.bounds = openFrameBounds
        openFrame!!.isVisible = true
    }

    fun tryOpenProject(projectDir: Path) {
        if (blockOpening || projectDir.isRegularFile())
            return

        if (projectCtrls.any { it.projectDir == projectDir }) {
            JOptionPane.showMessageDialog(
                openFrame, l10n("ui.open.alreadyOpen.msg", projectDir), l10n("ui.open.alreadyOpen.title"),
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        // If the two required project files don't exist yet, create them.
        val stylingFile = projectDir.resolve(STYLING_FILE_NAME)
        if (projectDir.notExists() || stylingFile.notExists() || locateCreditsFile(projectDir).first == null) {
            // If the user cancels the dialog, cancel opening the project directory.
            val (locale, format) = CreateForm.showDialog() ?: return
            copyTemplate(
                projectDir, locale, format,
                // Verify a second time that the two required project files still do not exists,
                // in case the user has created them manually in the meantime.
                copyStyling = stylingFile.notExists(), copyCredits = locateCreditsFile(projectDir).first == null
            )
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
        val openOnScreen = openFrame!!.graphicsConfiguration

        val projectCtrl = ProjectController(projectDir, openOnScreen)
        projectCtrls.add(projectCtrl)

        onCloseOpenFrame()
    }

    // Must only be called from ProjectController.
    fun onCloseProject(projectCtrl: ProjectController) {
        projectCtrls.remove(projectCtrl)

        // When there are no more open projects, let the OpenFrame reappear.
        if (projectCtrls.isEmpty())
            showOpenFrame()
    }

    fun onCloseOpenFrame() {
        openFrameBounds = openFrame!!.bounds
        openFrame!!.dispose()
        openFrame = null
    }

    fun tryCloseProjectsAndDisposeAllFrames(force: Boolean = false) {
        for (projectCtrl in projectCtrls.toMutableList() /* copy to avoid concurrent modification */)
            if (!projectCtrl.tryCloseProject(force) && !force)
                return

        onCloseOpenFrame()

        if (force)
            for (window in Window.getWindows())
                window.dispose()
    }

    fun onGlobalKeyEvent(event: KeyEvent) =
        projectCtrls.any { it.onGlobalKeyEvent(event) }

}
