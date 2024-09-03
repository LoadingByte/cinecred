package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.throwableAwareTask
import com.loadingbyte.cinecred.common.walkSafely
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.projectio.ProjectIntake.Callbacks
import com.loadingbyte.cinecred.projectio.RecursiveFileWatcher.Event.DELETE
import com.loadingbyte.cinecred.projectio.RecursiveFileWatcher.Event.MODIFY
import com.loadingbyte.cinecred.projectio.service.SERVICES
import com.loadingbyte.cinecred.projectio.service.SERVICE_LINK_EXTS
import com.loadingbyte.cinecred.projectio.service.ServiceWatcher
import com.loadingbyte.cinecred.projectio.service.readServiceLink
import java.awt.Font
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*


/**
 * Upon creation of a new instance of this class, the callbacks [Callbacks.pushProjectFonts],
 * [Callbacks.pushPictureLoaders], and [Callbacks.pushTapes] are immediately called from the same thread before the
 * constructor returns.
 * In contrast, the callback [Callbacks.pushCreditsSpreadsheets] will be called later from a separate thread.
 *
 * Neither the constructor nor any method in this class throws exceptions. Instead, errors are gracefully handled and,
 * if reasonable, the user is notified via the log.
 */
class ProjectIntake(private val projectDir: Path, private val callbacks: Callbacks) {

    interface Callbacks {
        fun creditsPolling(possible: Boolean)
        fun pushCreditsSpreadsheets(creditsSpreadsheets: List<Spreadsheet>, log: List<ParserMsg>)
        fun pushProjectFonts(projectFonts: Collection<Font>)
        fun pushPictureLoaders(pictureLoaders: Collection<PictureLoader>)
        fun pushTapes(tapes: Collection<Tape>)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProjectIntake").apply { isDaemon = true }
    }

    private var currentCreditsFile: Path? = null
    private var linkedCreditsWatcher: ServiceWatcher? = null

    private val auxFileEventBatch = AtomicReference(HashMap<Path, RecursiveFileWatcher.Event>())
    private val auxFileEventBatchProcessor = AtomicReference<ScheduledFuture<*>?>()

    private val projectFonts = HashMap<Path, List<Font>>()
    private val pictureLoaders = HashMap<Path, PictureLoader>()
    private val tapes = HashMap<Path, Tape>()

    // These are set to true to force calls to the corresponding pushX() method upon initialization.
    private var projectFontsChanged = true
    private var pictureLoadersChanged = true
    private var tapesChanged = true

    init {
        // Load the initially present auxiliary files (project fonts, pictures, tapes) in the current thread, which is
        // required by the class's contract. After that, all actions will be performed in the executor thread, which is
        // why the class doesn't need locking mechanisms.
        for (projectFileOrDir in projectDir.walkSafely())
            reloadAuxFileOrDir(projectFileOrDir)
        pushAuxiliaryFileChanges()

        // Load the initially present credits file in the executor thread.
        executor.submit(throwableAwareTask { reloadCreditsFile(Path("")) })

        // Watch for future changes in the new project dir.
        RecursiveFileWatcher.watch(projectDir) { event: RecursiveFileWatcher.Event, file: Path ->
            if (hasCreditsFilename(file)) {
                // Process changes to the credits file in the executor thread.
                // Also wait a moment so that the file has been fully written.
                executor.schedule(throwableAwareTask { reloadCreditsFile(file) }, 100, TimeUnit.MILLISECONDS)
            } else {
                // Changes to auxiliary files are batched to reduce the number of pushes when, e.g., a long image
                // sequence is copied into the project dir.
                // For this, we first record the event to a map, overriding the previous event for that file if there
                // was any. We also record events for the changed file's parent dir to account for image sequences.
                val batch = auxFileEventBatch.get()
                val parent = file.parent
                batch[file] = event
                batch[parent] = if (parent.exists()) MODIFY else DELETE
                // We then schedule a task that will later apply the batched changes in one go.
                val newProcessor = executor.schedule(throwableAwareTask {
                    for ((defFile, defEvent) in auxFileEventBatch.getAndSet(HashMap())) {
                        removeAuxFileOrDir(defFile)
                        if (defEvent == MODIFY)
                            reloadAuxFileOrDir(defFile)
                    }
                    pushAuxiliaryFileChanges()
                }, 500, TimeUnit.MILLISECONDS)
                // Cancel the previous task if it hasn't started yet
                auxFileEventBatchProcessor.getAndSet(newProcessor)?.cancel(false)
            }
        }
    }

    fun pollCredits() {
        linkedCreditsWatcher?.poll()
    }

    fun close() {
        // Don't close the intake twice, as the call to executor.submit() would throw an exception.
        if (executor.isShutdown)
            return

        // Cancel the previous project dir change watching order.
        RecursiveFileWatcher.unwatch(projectDir)

        executor.submit(throwableAwareTask {
            // Dispose of all loaded pictures.
            for (pictureLoader in pictureLoaders.values)
                pictureLoader.dispose()

            // Close all recognized tapes.
            for (tape in tapes.values)
                tape.close()

            // Stop watching for online changes.
            linkedCreditsWatcher?.cancel()
            linkedCreditsWatcher = null
        })

        executor.shutdown()
    }

    private fun reloadCreditsFile(changedFile: Path) {
        val (activeFile, locatingLog) = locateCreditsFile(projectDir)
        if (activeFile != null &&
            (changedFile == activeFile || currentCreditsFile.let { it == null || !safeIsSameFile(it, activeFile) })
        ) {
            linkedCreditsWatcher?.cancel()
            linkedCreditsWatcher = null
            callbacks.creditsPolling(false)
            val fileExt = activeFile.extension
            try {
                if (fileExt in SERVICE_LINK_EXTS) {
                    val link = readServiceLink(activeFile)
                    val service = SERVICES.find { it.canWatch(link) }
                    if (service == null) {
                        val msg = l10n("projectIO.credits.unsupportedServiceLink", link)
                        val msgObj = ParserMsg(null, null, null, null, ERROR, msg)
                        callbacks.pushCreditsSpreadsheets(emptyList(), locatingLog + msgObj)
                    } else {
                        linkedCreditsWatcher = service.watch(link, object : ServiceWatcher.Callbacks {
                            override fun content(spreadsheets: List<Spreadsheet>) {
                                callbacks.pushCreditsSpreadsheets(spreadsheets, locatingLog)
                            }

                            override fun problem(problem: ServiceWatcher.Problem) {
                                val key = when (problem) {
                                    ServiceWatcher.Problem.INACCESSIBLE -> "projectIO.credits.noAccountGrantsAccess"
                                    ServiceWatcher.Problem.DOWN -> "projectIO.credits.serviceUnresponsive"
                                }
                                val msg = ParserMsg(null, null, null, null, ERROR, l10n(key))
                                callbacks.pushCreditsSpreadsheets(emptyList(), locatingLog + msg)
                            }
                        })
                        callbacks.creditsPolling(true)
                    }
                } else {
                    val fmt = SPREADSHEET_FORMATS.first { fmt -> fmt.fileExt.equals(fileExt, ignoreCase = true) }
                    val (spreadsheets, loadingLog) = fmt.read(activeFile, l10n("project.template.spreadsheetName"))
                    callbacks.pushCreditsSpreadsheets(spreadsheets, locatingLog + loadingLog)
                }
            } catch (e: Exception) {
                // General exceptions can occur if the credits file is ill-formatted.
                // An IO exception can occur if the credits file has disappeared in the meantime. If that happens,
                // the file watcher should quickly trigger a call to this method again. Still, we push an
                // error message in case something else goes wrong too.
                val msg = l10n("projectIO.credits.cannotReadCreditsFile", activeFile.fileName, e.toString())
                val msgObj = ParserMsg(null, null, null, null, ERROR, msg)
                callbacks.pushCreditsSpreadsheets(emptyList(), locatingLog + msgObj)
            }
        } else
            callbacks.pushCreditsSpreadsheets(emptyList(), locatingLog)
        currentCreditsFile = activeFile
    }

    private fun reloadAuxFileOrDir(fileOrDir: Path) {
        // If the file has been generated by a render job, don't reload the project. Otherwise, generating image
        // sequences would be very expensive because we would constantly reload the project. Note that we do not
        // only consider the current render job, but all render jobs in the render job list. This ensures that even
        // the last file generated by a render job doesn't reload the project even when the render job has already
        // been marked as complete by the time the OS notifies us about the newly generated file.
        if (RenderQueue.isRenderedFile(fileOrDir))
            return

        val newFonts = tryReadFonts(fileOrDir)
        if (newFonts.isNotEmpty()) {
            projectFonts[fileOrDir] = newFonts
            projectFontsChanged = true
            return
        }

        tryReadPictureLoader(fileOrDir)?.let { pictureLoader ->
            pictureLoaders.put(fileOrDir, pictureLoader)?.dispose()
            pictureLoadersChanged = true
            return
        }

        tryRecognizeTape(fileOrDir)?.let { tape ->
            tapes.put(fileOrDir, tape)?.close()
            tapesChanged = true
            // If this is an image sequence tape, disable all picture loaders inside the sequence folder.
            if (tape.fileSeq)
                pictureLoadersChanged = true
            return
        }
    }

    private fun removeAuxFileOrDir(fileOrDir: Path) {
        projectFonts.remove(fileOrDir)?.let {
            projectFontsChanged = true
            return
        }

        pictureLoaders.remove(fileOrDir)?.let { pictureLoader ->
            pictureLoader.dispose()
            pictureLoadersChanged = true
            return
        }

        tapes.remove(fileOrDir)?.let { tape ->
            tape.close()
            tapesChanged = true
            // If this was an image sequence tape, re-enable all picture loaders inside the sequence folder in case
            // there are still some left.
            if (tape.fileSeq)
                pictureLoadersChanged = true
            return
        }
    }

    private fun pushAuxiliaryFileChanges() {
        if (projectFontsChanged)
            callbacks.pushProjectFonts(projectFonts.values.flatten())

        if (pictureLoadersChanged)
            callbacks.pushPictureLoaders(pictureLoaders.values)

        if (tapesChanged)
            callbacks.pushTapes(tapes.values)

        projectFontsChanged = false
        pictureLoadersChanged = false
        tapesChanged = false
    }


    companion object {

        private val CREDITS_EXTS = SPREADSHEET_FORMATS.map(SpreadsheetFormat::fileExt) + SERVICE_LINK_EXTS

        fun locateCreditsFile(projectDir: Path): Pair<Path?, List<ParserMsg>> {
            fun availExtsStr() = CREDITS_EXTS.joinToString()

            var creditsFile: Path? = null
            val log = mutableListOf<ParserMsg>()

            val candidates = getCreditsFileCandidates(projectDir)
            if (candidates.isEmpty()) {
                val msg = l10n("projectIO.credits.noCreditsFile", availExtsStr())
                log.add(ParserMsg(null, null, null, null, ERROR, msg))
            } else {
                creditsFile = candidates.first()
                if (candidates.size > 1) {
                    val msg = l10n("projectIO.credits.multipleCreditsFiles", availExtsStr(), creditsFile.fileName)
                    log.add(ParserMsg(null, null, null, null, WARN, msg))
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

        private fun hasCreditsFilename(file: Path): Boolean {
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

}
