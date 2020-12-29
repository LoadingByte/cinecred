package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.drawer.draw
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.projectio.readCredits
import com.loadingbyte.cinecred.projectio.readStyling
import com.loadingbyte.cinecred.projectio.writeStyling
import java.nio.file.*
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.SwingUtilities


object Controller {

    private val previewGenerationJob = LinkedBlockingQueue<Runnable>()
    private val watcher = FileSystems.getDefault().newWatchService()

    private var creditsFile: Path? = null
    private var stylingFile: Path? = null
    private var projectDirWatchKey: WatchKey? = null

    private var styling: Styling? = null

    init {
        Thread({
            while (true)
                previewGenerationJob.take().run()
        }, "PreviewGenerationThread").apply { isDaemon = true }.start()

        Thread({
            while (true) {
                val key = watcher.take()
                for (event in key.pollEvents())
                    if (event.kind() != StandardWatchEventKinds.OVERFLOW &&
                        // The context path is relative to the watched directory. As such, this condition checks
                        // whether the context path is equal to the name of the watched file.
                        event.context() as Path == Path.of("credits.csv")
                    )
                        SwingUtilities.invokeLater(::reloadCreditsFile)
                key.reset()
            }
        }, "FileWatcherThread").apply { isDaemon = true }.start()
    }

    fun openProjectDir(projectDir: Path) {
        if (!EditPanel.onTryOpenProjectDirOrExit() || !DeliverRenderQueuePanel.onTryOpenProjectDirOrExit())
            return

        // Cancel the previous project dir change watching instruction.
        projectDirWatchKey?.cancel()

        val creditsFile = projectDir.resolve(Path.of("credits.csv"))
        val stylingFile = projectDir.resolve(Path.of("styling.toml"))
        this.creditsFile = creditsFile
        this.stylingFile = stylingFile

        // If the two required project files don't exist yet, create them.
        if (!Files.exists(creditsFile))
            Files.copy(javaClass.getResourceAsStream("/template/credits.csv"), creditsFile)
        if (!Files.exists(stylingFile))
            Files.copy(javaClass.getResourceAsStream("/template/styling.toml"), stylingFile)

        MainFrame.onOpenProjectDir()
        DeliverConfigurationForm.onOpenProjectDir(projectDir)

        // Load the initial state of the styling and credits files.
        reloadStylingFile()
        reloadCreditsFile()

        // Watch for future changes in the new project dir.
        projectDirWatchKey = projectDir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
    }

    fun reloadCreditsFile() {
        val styling = this.styling!!

        // Execute the actual reading and drawing in another thread to not block the UI thread.
        previewGenerationJob.clear()
        previewGenerationJob.add {
            val (log, pages) = readCredits(creditsFile!!, styling)

            var project: Project? = null
            var pageDefImages = emptyList<DeferredImage>()
            if (pages != null) {
                project = Project(styling, pages)
                pageDefImages = draw(project)
            }

            // Make sure to update the UI from the UI thread because Swing is not thread-safe.
            SwingUtilities.invokeLater {
                if (project != null)
                    DeliverConfigurationForm.updateProject(project, pageDefImages)
                EditPanel.updateProjectAndLog(styling, pages, pageDefImages, log)
            }
        }
    }

    fun reloadStylingFile() {
        val styling = readStyling(stylingFile!!)
        this.styling = styling
        reloadCreditsFile()
        EditPanel.onLoadStyling()
        EditStylingPanel.onLoadStyling(styling)
    }

    fun editStyling(styling: Styling) {
        this.styling = styling
        reloadCreditsFile()
        EditPanel.onEditStyling()
    }

    fun saveStyling() {
        writeStyling(stylingFile!!, styling!!)
    }

    fun tryExit() {
        if (EditPanel.onTryOpenProjectDirOrExit() && DeliverRenderQueuePanel.onTryOpenProjectDirOrExit())
            MainFrame.dispose()
    }

}
