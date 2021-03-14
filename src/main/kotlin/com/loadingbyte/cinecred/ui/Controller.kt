package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Picture
import com.loadingbyte.cinecred.common.SUPPORTED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.draw
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.ui.helper.FontFamilies
import com.loadingbyte.cinecred.ui.helper.JobSlot
import com.loadingbyte.cinecred.ui.styling.EditStylingDialog
import com.loadingbyte.cinecred.ui.styling.EditStylingPanel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.WatchEvent
import java.util.*
import javax.swing.JOptionPane
import javax.swing.SwingUtilities


object Controller {

    private var isEditTabActive = false
    private var isEditStylingDialogVisible = true

    private var projectDir: Path? = null
    private var stylingFile: Path? = null
    private var creditsFile: Path? = null

    private val fonts = mutableMapOf<Path, Font>()
    private val pictureLoaders = mutableMapOf<Path, Lazy<Picture?>>()

    private val projectDirWatcher = object : RecursiveFileWatcher() {
        override fun onEvent(file: Path, kind: WatchEvent.Kind<*>) {
            val isCreditsFile = try {
                Files.isSameFile(file, creditsFile!!)
            } catch (_: IOException) {
                false
            }
            when {
                isCreditsFile ->
                    SwingUtilities.invokeLater(::tryReloadCreditsFileAndRedraw)
                kind == ENTRY_DELETE ->
                    SwingUtilities.invokeLater { if (tryRemoveAuxFile(file)) tryReloadCreditsFileAndRedraw() }
                else ->
                    SwingUtilities.invokeLater { if (tryReloadAuxFile(file)) tryReloadCreditsFileAndRedraw() }
            }
        }
    }

    private val reloadCreditsFileAndRedrawJobSlot = JobSlot()

    fun onChangeTab(changedToEdit: Boolean) {
        isEditTabActive = changedToEdit
        EditStylingDialog.isVisible = changedToEdit && isEditStylingDialogVisible
    }

    fun setEditStylingDialogVisible(isVisible: Boolean) {
        isEditStylingDialogVisible = isVisible
        EditStylingDialog.isVisible = isEditTabActive && isVisible
        EditPanel.onSetEditStylingDialogVisible(isVisible)
    }

    fun tryExit() {
        if (EditPanel.onTryOpenProjectDirOrExit() && DeliverRenderQueuePanel.onTryExit()) {
            MainFrame.dispose()
            EditStylingDialog.dispose()
        }
    }

    fun tryOpenProjectDir(projectDir: Path) {
        if (!EditPanel.onTryOpenProjectDirOrExit())
            return

        val stylingFile = projectDir.resolve(Path.of("Styling.toml"))
        val creditsFile = projectDir.resolve(Path.of("Credits.csv"))

        // If the two required project files don't exist yet, create them.
        if (!Files.exists(stylingFile) || !Files.exists(creditsFile))
            if (!tryCopyTemplate(projectDir, stylingFile, creditsFile)) {
                // The user cancelled the project creation.
                return
            }

        // Cancel the previous project dir change watching instruction.
        projectDirWatcher.clear()

        // Remove the previous project from the UI to make sure that the user doesn't see things from the
        // previous project while the new project is loaded.
        EditPanel.updateProjectAndLog(null, emptyList(), emptyList())
        VideoPanel.updateProject(null, emptyList())
        DeliverConfigurationForm.updateProject(null, emptyList())

        this.projectDir = projectDir
        this.stylingFile = stylingFile
        this.creditsFile = creditsFile
        fonts.clear()
        pictureLoaders.clear()
        StylingHistory.clear()

        MainFrame.onOpenProjectDir()
        EditPanel.onOpenProjectDir()
        DeliverConfigurationForm.onOpenProjectDir(projectDir)

        // Load the initially present auxiliary files (project fonts and pictures).
        for (projectFile in Files.walk(projectDir))
            tryReloadAuxFile(projectFile)

        // Load the initial state of the styling and credits files (the latter is invoked by the former).
        StylingHistory.tryResetAndRedraw()

        // Watch for future changes in the new project dir.
        projectDirWatcher.watch(projectDir)
    }

    private fun tryCopyTemplate(projectDir: Path, stylingFile: Path, creditsFile: Path): Boolean {
        // Find the supported locale which is closest to the user's default locale.
        val defaultSupportedLocale = Locale.lookup(
            listOf(Locale.LanguageRange(Locale.getDefault().toLanguageTag())), SUPPORTED_LOCALES.keys
        )

        // Wrapping locales in these objects allows us to provide custom a toString() method.
        class WrappedLocale(val locale: Locale, val label: String) {
            override fun toString() = label
        }

        val localeChoices = SUPPORTED_LOCALES.map { (locale, label) -> WrappedLocale(locale, label) }.toTypedArray()
        val defaultLocaleChoice = localeChoices[SUPPORTED_LOCALES.keys.indexOf(defaultSupportedLocale)]
        val choice = JOptionPane.showInputDialog(
            MainFrame, l10n("ui.open.chooseLocale.msg"), l10n("ui.open.chooseLocale.title"),
            JOptionPane.QUESTION_MESSAGE, null, localeChoices, defaultLocaleChoice
        ) ?: return false  // If the user cancelled the dialog, cancel opening the project directory.
        val locale = (choice as WrappedLocale).locale

        if (!Files.exists(stylingFile))
            copyStylingTemplate(projectDir, locale)
        if (!Files.exists(creditsFile))
            copyCreditsTemplate(projectDir, locale)

        return true
    }

    private fun tryReloadAuxFile(file: Path): Boolean {
        // If the file has been generated by a render job, don't reload the project. Otherwise, generating image
        // sequences would be very expensive because we would constantly reload the project. Note that we do not
        // only consider the current render job, but all render jobs in the render job list. This ensures that even
        // the last file generated by a render job doesn't reload the project even when the render job has already
        // been marked as complete by the time the OS notifies us about the newly generated file.
        if (DeliverRenderQueuePanel.renderJobs.any { it.generatesFile(file) })
            return false

        tryReadFont(file)?.let { font ->
            fonts[file] = font
            EditStylingPanel.updateProjectFontFamilies(FontFamilies(fonts.values))
            return true
        }
        tryReadPictureLoader(file)?.let { pictureLoader ->
            pictureLoaders[file] = pictureLoader
            return true
        }
        return false
    }

    private fun tryRemoveAuxFile(file: Path): Boolean {
        if (fonts.remove(file) != null) {
            EditStylingPanel.updateProjectFontFamilies(FontFamilies(fonts.values))
            return true
        }
        if (pictureLoaders.remove(file) != null)
            return true
        return false
    }

    private fun tryReloadCreditsFileAndRedraw() {
        if (!Files.exists(creditsFile!!)) {
            JOptionPane.showMessageDialog(
                MainFrame, l10n("ui.edit.missingCreditsFile.msg", creditsFile),
                l10n("ui.edit.missingCreditsFile.title"), JOptionPane.ERROR_MESSAGE
            )
            tryExit()
            return
        }

        // Capture these variables in the state they are in when the function is called.
        val projectDir = this.projectDir!!
        val creditsFile = this.creditsFile!!
        val styling = StylingHistory.current!!
        val fonts = this.fonts
        val pictureLoaders = this.pictureLoaders

        // Execute the reading and drawing in another thread to not block the UI thread.
        reloadCreditsFileAndRedrawJobSlot.submit {
            // We only now build these maps because it is expensive to build them and we don't want to do it
            // each time the function is called, but only when the issued reload & redraw actually gets through
            // (which is quite a lot less because function is often called multiple times in rapid succession).
            val fontsByName = fonts.mapKeys { (_, font) -> font.getFontName(Locale.US) }
            val pictureLoadersByRelPath = pictureLoaders.mapKeys { (path, _) -> projectDir.relativize(path) }

            val (log, pages) = readCredits(creditsFile, styling, pictureLoadersByRelPath)

            val project = Project(styling, fontsByName.toImmutableMap(), (pages ?: emptyList()).toImmutableList())
            val drawnPages = when (pages) {
                null -> emptyList()
                else -> draw(project)
            }

            // Make sure to update the UI from the UI thread because Swing is not thread-safe.
            SwingUtilities.invokeLater {
                EditPanel.updateProjectAndLog(project, drawnPages, log)
                VideoPanel.updateProject(project, drawnPages)
                DeliverConfigurationForm.updateProject(project, drawnPages)
            }
        }
    }


    object StylingHistory {

        private val history = mutableListOf<Styling>()
        private var currentIdx = -1
        private var saved: Styling? = null

        val current: Styling?
            get() = history.getOrNull(currentIdx)

        fun clear() {
            history.clear()
            currentIdx = -1
            saved = null
        }

        fun editedAndRedraw(new: Styling) {
            if (new != current) {
                while (history.lastIndex != currentIdx)
                    history.removeAt(history.lastIndex)
                history.add(new)
                currentIdx++
                onStylingChange()
            }
        }

        fun undoAndRedraw() {
            if (currentIdx != 0) {
                currentIdx--
                onStylingChange()
                EditStylingPanel.setStyling(current!!)
            }
        }

        fun redoAndRedraw() {
            if (currentIdx != history.lastIndex) {
                currentIdx++
                onStylingChange()
                EditStylingPanel.setStyling(current!!)
            }
        }

        fun tryResetAndRedraw() {
            if (!Files.exists(stylingFile!!)) {
                JOptionPane.showMessageDialog(
                    MainFrame, l10n("ui.edit.missingStylingFile.msg", stylingFile),
                    l10n("ui.edit.missingStylingFile.title"), JOptionPane.ERROR_MESSAGE
                )
                return
            }

            val new = readStyling(stylingFile!!)
            if (new != current) {
                saved = new
                editedAndRedraw(new)
                EditStylingPanel.setStyling(new)
            }
        }

        fun save() {
            saved = current
            writeStyling(stylingFile!!, current!!)
            EditPanel.onStylingSave()
        }

        private fun onStylingChange() {
            EditPanel.onStylingChange(current != saved, currentIdx != 0, currentIdx != history.lastIndex)
            tryReloadCreditsFileAndRedraw()
        }

    }

}
