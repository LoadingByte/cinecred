package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.SUPPORTED_LOCALES
import com.loadingbyte.cinecred.drawer.draw
import com.loadingbyte.cinecred.l10n
import com.loadingbyte.cinecred.project.Picture
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.projectio.*
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.WatchEvent
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.JOptionPane
import javax.swing.SwingUtilities


object Controller {

    private var isEditTabActive = false
    private var isEditStylingDialogVisible = true

    private var projectDir: Path? = null
    private var stylingFile: Path? = null
    private var creditsFile: Path? = null

    private var styling: Styling? = null
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
                    SwingUtilities.invokeLater(::reloadCreditsFileAndRedraw)
                kind == ENTRY_DELETE ->
                    SwingUtilities.invokeLater { if (tryRemoveAuxFile(file)) reloadCreditsFileAndRedraw() }
                else ->
                    SwingUtilities.invokeLater { if (tryReloadAuxFile(file)) reloadCreditsFileAndRedraw() }
            }
        }
    }

    private val previewGenerationJob = LinkedBlockingQueue<Runnable>()

    init {
        Thread({
            while (true)
                previewGenerationJob.take().run()
        }, "PreviewGenerationThread").apply { isDaemon = true }.start()
    }

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

    fun openProjectDir(projectDir: Path) {
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

        this.projectDir = projectDir
        this.stylingFile = stylingFile
        this.creditsFile = creditsFile

        fonts.clear()
        pictureLoaders.clear()

        MainFrame.onOpenProjectDir()
        DeliverConfigurationForm.onOpenProjectDir(projectDir)

        // Load the initially present auxiliary files (project fonts and pictures).
        for (projectFile in Files.walk(projectDir))
            tryReloadAuxFile(projectFile)

        // Load the initial state of the styling and credits files (the latter is invoked by the former).
        tryReloadStylingFileAndRedraw()

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

    /**
     * Returns whether the styling file has successfully been reloaded.
     * The return value doesn't tell anything about whether the redrawing was successful.
     */
    fun tryReloadStylingFileAndRedraw(): Boolean {
        if (!Files.exists(stylingFile!!)) {
            JOptionPane.showMessageDialog(
                MainFrame, l10n("ui.edit.missingStylingFile.msg", stylingFile),
                l10n("ui.edit.missingStylingFile.title"), JOptionPane.ERROR_MESSAGE
            )
            return false
        }

        val styling = readStyling(stylingFile!!)
        this.styling = styling
        EditPanel.onLoadStyling()
        EditStylingPanel.onLoadStyling(styling)
        reloadCreditsFileAndRedraw()
        return true
    }

    fun editStylingAndRedraw(styling: Styling) {
        this.styling = styling
        EditPanel.onEditStyling()
        reloadCreditsFileAndRedraw()
    }

    fun saveStyling() {
        writeStyling(stylingFile!!, styling!!)
    }

    private fun reloadCreditsFileAndRedraw() {
        if (!Files.exists(creditsFile!!)) {
            JOptionPane.showMessageDialog(
                MainFrame, l10n("ui.edit.missingCreditsFile.msg", creditsFile),
                l10n("ui.edit.missingCreditsFile.title"), JOptionPane.ERROR_MESSAGE
            )
            tryExit()
            return
        }

        val styling = this.styling!!
        val fontsByName = fonts.values.associateBy { font -> font.getFontName(Locale.US) }
        val pictureLoadersByRelPath = pictureLoaders.mapKeys { (path, _) -> projectDir!!.relativize(path) }

        // Execute the reading and drawing in another thread to not block the UI thread.
        previewGenerationJob.clear()
        previewGenerationJob.add {
            val (log, pages) = readCredits(creditsFile!!, styling, pictureLoadersByRelPath)

            val project = Project(styling, fontsByName, pages ?: emptyList())
            val pageDefImages = when (pages) {
                null -> emptyList()
                else -> draw(project)
            }

            // Make sure to update the UI from the UI thread because Swing is not thread-safe.
            SwingUtilities.invokeLater {
                EditPanel.updateProjectAndLog(project, pageDefImages, log)
                DeliverConfigurationForm.updateProject(project, pageDefImages)
            }
        }
    }

}
