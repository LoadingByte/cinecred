package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyTemplate
import com.loadingbyte.cinecred.projectio.locateCreditsFile
import com.loadingbyte.cinecred.ui.ProjectController.Companion.STYLING_FILE_NAME
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import javax.swing.JOptionPane


object OpenController {

    private val prefs = Preferences.userRoot().node("com/loadingbyte/cinecred")
    private val prefsProjectDirs = prefs.node("projectDirs")

    private val projectCtrls = mutableListOf<ProjectController>()

    fun getMemorizedProjectDirs(): List<Path> {
        val dirs = prefsProjectDirs.keys()
            .sortedBy(String::toInt)
            .map { key -> Path.of(prefsProjectDirs.get(key, null)) }
            .toMutableList()
        // Remove all memorized directories which are no longer project directories.
        // Do not base this decision on the existence of a credits file because this file might just be
        // moved around a little by the user at the same time this function is called.
        val modified = dirs.removeAll { dir ->
            !Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(STYLING_FILE_NAME))
        }
        if (modified)
            setMemorizedProjectDirs(dirs)
        return dirs
    }

    private fun setMemorizedProjectDirs(dirs: List<Path>) {
        prefsProjectDirs.clear()
        for ((idx, dir) in dirs.withIndex())
            prefsProjectDirs.put(idx.toString(), dir.toString())
    }

    fun getProjectCtrls(): List<ProjectController> = projectCtrls

    fun showOpenFrame() {
        OpenPanel.onShow()
        OpenFrame.isVisible = true
    }

    fun tryOpenProject(projectDir: Path) {
        if (Files.isRegularFile(projectDir))
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

        // Memorize the opened project directory at the first position in the list.
        setMemorizedProjectDirs(
            getMemorizedProjectDirs().toMutableList().apply {
                remove(projectDir)
                add(0, projectDir)
            }
        )

        val projectCtrl = ProjectController(projectDir)
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
