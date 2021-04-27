package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyCreditsTemplate
import com.loadingbyte.cinecred.projectio.copyStylingTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.JOptionPane


object OpenController {

    private val projectCtrls = mutableListOf<ProjectController>()

    fun getProjectCtrls(): List<ProjectController> = projectCtrls

    fun showOpenFrame() {
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

        val stylingFile = projectDir.resolve(Path.of("Styling.toml"))
        val creditsFile = projectDir.resolve(Path.of("Credits.csv"))

        // If the two required project files don't exist yet, create them.
        if (!Files.exists(stylingFile) || !Files.exists(creditsFile))
            if (!tryCopyTemplate(projectDir, stylingFile, creditsFile)) {
                // The user cancelled the project creation.
                return
            }

        val projectCtrl = ProjectController(projectDir, stylingFile, creditsFile)
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
            OpenFrame.isVisible = true
    }

    fun onCloseOpenFrame() {
        if (projectCtrls.isEmpty())
            tryExit()
        else
            OpenFrame.isVisible = false
    }

    fun tryExit() {
        for (projectCtrl in projectCtrls.toMutableList() /* copy to avoid concurrent modification */)
            if (!projectCtrl.tryCloseProject())
                return

        OpenFrame.dispose()
    }

}
