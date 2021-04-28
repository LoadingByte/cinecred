package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyCreditsTemplate
import com.loadingbyte.cinecred.projectio.copyStylingTemplate
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.prefs.Preferences
import javax.swing.JOptionPane


object OpenController {

    private val prefs = Preferences.userRoot().node("com/loadingbyte/cinecred")
    private val prefsProjectDirs = prefs.node("projectDirs")

    private val projectCtrls = mutableListOf<ProjectController>()

    fun getStylingFile(projectDir: Path): Path = projectDir.resolve(Path.of("Styling.toml"))
    fun getCreditsFile(projectDir: Path): Path = projectDir.resolve(Path.of("Credits.csv"))

    fun getMemorizedProjectDirs(): List<Path> {
        val dirs = prefsProjectDirs.keys()
            .sortedBy(String::toInt)
            .map { key -> Path.of(prefsProjectDirs.get(key, null)) }
            .toMutableList()
        // Remove all memorized directories which are no longer project directories.
        val modified = dirs.removeAll { dir ->
            !Files.isDirectory(dir) || !Files.isRegularFile(getStylingFile(dir)) ||
                    !Files.isRegularFile(getCreditsFile(dir))
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
        if (projectCtrls.any { it.projectDir == projectDir }) {
            JOptionPane.showMessageDialog(
                OpenFrame, l10n("ui.open.alreadyOpen.msg", projectDir), l10n("ui.open.alreadyOpen.title"),
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        val stylingFile = getStylingFile(projectDir)
        val creditsFile = getCreditsFile(projectDir)

        // If the two required project files don't exist yet, create them.
        if (!Files.exists(stylingFile) || !Files.exists(creditsFile))
            if (!tryCopyTemplate(projectDir, stylingFile, creditsFile)) {
                // The user cancelled the project creation.
                return
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

    private fun tryCopyTemplate(projectDir: Path, stylingFile: Path, creditsFile: Path): Boolean {
        // Wrapping locales in these objects allows us to provide custom a toString() method.
        class WrappedLocale(val locale: Locale, val label: String) {
            override fun toString() = label
        }

        val localeChoices = TRANSLATED_LOCALES.map { WrappedLocale(it, it.displayName) }.toTypedArray()
        val defaultLocaleChoice = localeChoices[TRANSLATED_LOCALES.indexOf(Locale.getDefault())]
        val choice = JOptionPane.showInputDialog(
            OpenFrame, l10n("ui.open.chooseLocale.msg"), l10n("ui.open.chooseLocale.title"),
            JOptionPane.QUESTION_MESSAGE, null, localeChoices, defaultLocaleChoice
        ) ?: return false  // If the user cancelled the dialog, cancel opening the project directory.
        val locale = (choice as WrappedLocale).locale

        if (!Files.exists(stylingFile))
            copyStylingTemplate(projectDir, locale)
        if (!Files.exists(creditsFile))
            copyCreditsTemplate(projectDir, locale)

        return true
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
