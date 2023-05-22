package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.walkSafely
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.projectio.ProjectIntake.Callbacks
import com.loadingbyte.cinecred.projectio.service.ProviderAndLinkCoords
import com.loadingbyte.cinecred.projectio.service.SERVICE_PROVIDERS
import com.loadingbyte.cinecred.projectio.service.ServiceProvider
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*


/**
 * Upon creation of a new instance of this class, the callbacks [Callbacks.pushProjectFonts] and
 * [Callbacks.pushPictureLoaders] are immediately called from the same thread before the constructor returns.
 * In contrast, the callback [Callbacks.pushCreditsSpreadsheet] will be called later from a separate thread.
 *
 * Neither the constructor nor any method in this class throws exceptions. Instead, errors are gracefully handled and,
 * if reasonable, the user is notified via the log.
 */
class ProjectIntake(private val projectDir: Path, private val callbacks: Callbacks) {

    interface Callbacks {
        fun pushCreditsSpreadsheet(creditsSpreadsheet: Spreadsheet?, log: List<ParserMsg>)
        fun pushProjectFonts(projectFonts: Collection<Font>)
        fun pushPictureLoaders(pictureLoaders: Collection<PictureLoader>)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProjectIntake").apply { isDaemon = true }
    }

    private var currentCreditsFile: Path? = null
    private var linkedCreditsLoader: LinkedCreditsLoader? = null

    private val projectFonts = HashMap<Path, List<Font>>()
    private val pictureLoaders = HashMap<Path, PictureLoader>()

    init {
        // Load the initially present auxiliary files (project fonts and pictures) in the current thread, which is
        // required by the class's contract. After that, all actions will be performed in the executor thread, which is
        // why the class doesn't need locking mechanisms.
        for (projectFile in projectDir.walkSafely())
            if (projectFile.isRegularFile())
                reloadAuxFile(projectFile, push = false)
        callbacks.pushProjectFonts(projectFonts.values.flatten())
        callbacks.pushPictureLoaders(pictureLoaders.values)

        // Load the initially present credits file in the executor thread.
        executor.submit { reloadCreditsFile(Path("")) }

        // Watch for future changes in the new project dir.
        RecursiveFileWatcher.watch(projectDir) { event: RecursiveFileWatcher.Event, file: Path ->
            // Process the change in the executor thread. Also wait a moment so that the file has been fully written.
            executor.schedule({
                when {
                    hasCreditsFilename(file) -> reloadCreditsFile(file)
                    event == RecursiveFileWatcher.Event.MODIFY -> reloadAuxFile(file, push = true)
                    event == RecursiveFileWatcher.Event.DELETE -> removeAuxFile(file)
                }
            }, 100, TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        // Cancel the previous project dir change watching order.
        RecursiveFileWatcher.unwatch(projectDir)

        executor.submit {
            // Dispose of all loaded pictures.
            for (pictureLoader in pictureLoaders.values)
                pictureLoader.dispose()

            // Stop watching for online changes.
            linkedCreditsLoader?.cancel()
            linkedCreditsLoader = null
        }

        executor.shutdown()
    }

    private fun reloadCreditsFile(changedFile: Path) {
        val (activeFile, locatingLog) = locateCreditsFile(projectDir)
        if (activeFile != null &&
            (changedFile == activeFile || currentCreditsFile.let { it == null || !safeIsSameFile(it, activeFile) })
        ) {
            linkedCreditsLoader?.cancel()
            linkedCreditsLoader = null
            val fileExt = activeFile.extension
            try {
                if (fileExt == ProviderAndLinkCoords.FILE_EXT) {
                    val coords = ProviderAndLinkCoords.read(activeFile)
                    linkedCreditsLoader = LinkedCreditsLoader(callbacks, coords, locatingLog)
                } else {
                    val fmt = SPREADSHEET_FORMATS.first { fmt -> fmt.fileExt.equals(fileExt, ignoreCase = true) }
                    val (spreadsheet, loadingLog) = fmt.read(activeFile)
                    callbacks.pushCreditsSpreadsheet(spreadsheet, locatingLog + loadingLog)
                }
            } catch (e: Exception) {
                // General exceptions can occur if the credits file is ill-formatted.
                // An IO exception can occur if the credits file has disappeared in the meantime. If that happens,
                // the file watcher should quickly trigger a call to this method again. Still, we add a push an
                // error message in case something else goes wrong too.
                val msg = l10n("projectIO.credits.cannotReadCreditsFile", activeFile.fileName, e.toString())
                callbacks.pushCreditsSpreadsheet(null, locatingLog + ParserMsg(null, null, null, ERROR, msg))
            }
        } else
            callbacks.pushCreditsSpreadsheet(null, locatingLog)
        currentCreditsFile = activeFile
    }

    private fun reloadAuxFile(file: Path, push: Boolean) {
        // If the file has been generated by a render job, don't reload the project. Otherwise, generating image
        // sequences would be very expensive because we would constantly reload the project. Note that we do not
        // only consider the current render job, but all render jobs in the render job list. This ensures that even
        // the last file generated by a render job doesn't reload the project even when the render job has already
        // been marked as complete by the time the OS notifies us about the newly generated file.
        if (RenderQueue.isRenderedFile(file))
            return

        val newFonts = tryReadFonts(file)
        if (newFonts.isNotEmpty()) {
            projectFonts[file] = newFonts
            if (push)
                callbacks.pushProjectFonts(projectFonts.values.flatten())
            return
        }

        tryReadPictureLoader(file)?.let { pictureLoader ->
            val prevPictureLoader = pictureLoaders.put(file, pictureLoader)
            prevPictureLoader?.dispose()
            if (push)
                callbacks.pushPictureLoaders(pictureLoaders.values)
            return
        }
    }

    private fun removeAuxFile(file: Path) {
        val remFonts = projectFonts.remove(file)
        if (remFonts != null) {
            callbacks.pushProjectFonts(projectFonts.values.flatten())
            return
        }

        val pictureLoader = pictureLoaders.remove(file)
        if (pictureLoader != null) {
            pictureLoader.dispose()
            callbacks.pushPictureLoaders(pictureLoaders.values)
            return
        }
    }


    companion object {

        private val CREDITS_EXTS = SPREADSHEET_FORMATS.map(SpreadsheetFormat::fileExt) + ProviderAndLinkCoords.FILE_EXT

        fun locateCreditsFile(projectDir: Path): Pair<Path?, List<ParserMsg>> {
            fun availExtsStr() = CREDITS_EXTS.joinToString()

            var creditsFile: Path? = null
            val log = mutableListOf<ParserMsg>()

            val candidates = getCreditsFileCandidates(projectDir)
            if (candidates.isEmpty()) {
                val msg = l10n("projectIO.credits.noCreditsFile", availExtsStr())
                log.add(ParserMsg(null, null, null, ERROR, msg))
            } else {
                creditsFile = candidates.first()
                if (candidates.size > 1) {
                    val msg = l10n("projectIO.credits.multipleCreditsFiles", availExtsStr(), creditsFile.fileName)
                    log.add(ParserMsg(null, null, null, WARN, msg))
                }
            }

            return Pair(creditsFile, log)
        }

        private fun getCreditsFileCandidates(projectDir: Path): List<Path> {
            val files = try {
                projectDir.listDirectoryEntries()
            } catch (e: IOException) {
                if (e !is NoSuchFileException)
                    LOGGER.error("Cannot list files in project dir '{}'.", projectDir, e)
                return emptyList()
            }
            return files
                .filter { file -> file.isRegularFile() && hasCreditsFilename(file) }
                .sortedBy { file ->
                    val fileExt = file.extension
                    CREDITS_EXTS.indexOfFirst { ext -> ext.equals(fileExt, ignoreCase = true) }
                }
        }

        fun hasCreditsFilename(file: Path): Boolean {
            val fileExt = file.extension
            return file.nameWithoutExtension.equals("Credits", ignoreCase = true) &&
                    CREDITS_EXTS.any { ext -> ext.equals(fileExt, ignoreCase = true) }
        }

        private fun safeIsSameFile(p1: Path, p2: Path) =
            try {
                Files.isSameFile(p1, p2)
            } catch (_: IOException) {
                false
            }

    }


    private class LinkedCreditsLoader(
        callbacks: Callbacks,
        coords: ProviderAndLinkCoords,
        private val locatingLog: List<ParserMsg>
    ) : ServiceProvider.WatcherCallbacks {

        private val callbacks = AtomicReference<Callbacks?>(callbacks)
        private val watcher: ServiceProvider.Watcher

        init {
            val provider = SERVICE_PROVIDERS.find { it.id == coords.providerId }
                ?: throw IOException("Unknown service provider: ${coords.providerId}")
            watcher = provider.watch(coords.linkCoords, this)
        }

        fun cancel() {
            callbacks.set(null)
            watcher.cancel()
        }

        override fun content(spreadsheet: Spreadsheet) {
            callbacks.get()?.pushCreditsSpreadsheet(spreadsheet, locatingLog)
        }

        override fun status(down: Boolean) {
            val loadingLog = if (!down) emptyList() else
                listOf(ParserMsg(null, null, null, ERROR, l10n("projectIO.credits.serviceUnresponsive")))
            callbacks.get()?.pushCreditsSpreadsheet(null, locatingLog + loadingLog)
        }

        override fun inaccessible() {
            val loadingLog = listOf(ParserMsg(null, null, null, ERROR, l10n("projectIO.credits.noServiceGrantsAccess")))
            callbacks.get()?.pushCreditsSpreadsheet(null, locatingLog + loadingLog)
        }

    }

}
