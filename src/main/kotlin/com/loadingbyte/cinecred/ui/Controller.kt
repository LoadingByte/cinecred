package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.drawer.draw
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
        if (EditPanel.onTryOpenProjectDirOrExit() && DeliverRenderQueuePanel.onTryOpenProjectDirOrExit()) {
            MainFrame.dispose()
            EditStylingDialog.dispose()
        }
    }

    fun openProjectDir(projectDir: Path) {
        if (!EditPanel.onTryOpenProjectDirOrExit() || !DeliverRenderQueuePanel.onTryOpenProjectDirOrExit())
            return

        // Cancel the previous project dir change watching instruction.
        projectDirWatcher.clear()

        val stylingFile = projectDir.resolve(Path.of("styling.toml"))
        val creditsFile = projectDir.resolve(Path.of("credits.csv"))
        this.projectDir = projectDir
        this.stylingFile = stylingFile
        this.creditsFile = creditsFile

        // If the two required project files don't exist yet, create them.
        if (!Files.exists(stylingFile))
            javaClass.getResourceAsStream("/template/styling.toml").use { Files.copy(it, stylingFile) }
        if (!Files.exists(creditsFile))
            javaClass.getResourceAsStream("/template/credits.csv").use { Files.copy(it, creditsFile) }

        MainFrame.onOpenProjectDir()
        DeliverConfigurationForm.onOpenProjectDir(projectDir)

        // Load the initially present auxiliary files (project fonts and pictures).
        for (projectFile in Files.walk(projectDir))
            tryReloadAuxFile(projectFile)

        // Load the initial state of the styling and credits files (the latter is invoked by the former).
        reloadStylingFileAndRedraw()

        // Watch for future changes in the new project dir.
        projectDirWatcher.watch(projectDir)
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

    fun reloadStylingFileAndRedraw() {
        val styling = readStyling(stylingFile!!)
        this.styling = styling
        EditPanel.onLoadStyling()
        EditStylingPanel.onLoadStyling(styling)
        reloadCreditsFileAndRedraw()
    }

    fun editStylingAndRedraw(styling: Styling) {
        this.styling = styling
        EditPanel.onEditStyling()
        reloadCreditsFileAndRedraw()
    }

    fun saveStyling() {
        writeStyling(stylingFile!!, styling!!)
    }

    fun reloadCreditsFileAndRedraw() {
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
                if (pages != null)
                    DeliverConfigurationForm.updateProject(project, pageDefImages)
                EditPanel.updateProjectAndLog(project, pageDefImages, log)
            }
        }
    }

}
