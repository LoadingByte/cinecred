package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.imaging.Font
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.projectio.RecursiveFileWatcher.Event.DELETE
import com.loadingbyte.cinecred.projectio.RecursiveFileWatcher.Event.MODIFY
import com.loadingbyte.cinecred.projectio.service.SERVICES
import com.loadingbyte.cinecred.projectio.service.SERVICE_LINK_EXTS
import com.loadingbyte.cinecred.projectio.service.ServiceWatcher
import com.loadingbyte.cinecred.projectio.service.readServiceLink
import java.io.IOException
import java.net.URI
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.*


/**
 * Upon creation of a new instance of this class, the callbacks [Callbacks.pushProjectFonts],
 * [Callbacks.pushPictureLoaders], and [Callbacks.pushTapes] are immediately called from the same thread before the
 * constructor returns.
 * In contrast, the callback [Callbacks.pushCreditsWorkbooks] will be called later from a separate thread.
 *
 * Neither the constructor nor any method in this class throws exceptions. Instead, errors are gracefully handled and,
 * if reasonable, the user is notified via the log.
 */
class ProjectIntake(private val projectDir: Path, private val callbacks: Callbacks) {

    interface Callbacks {
        fun pushCreditsWorkbooks(creditsWorkbooks: Collection<CreditsWorkbook>, log: List<ParserMsg>, pollable: Boolean)
        fun pushProjectFonts(projectFonts: Map<String, Font>)
        fun pushPictureLoaders(pictureLoaders: Map<String, Picture.Loader>)
        fun pushTapes(tapes: Map<String, Tape>)
    }

    class CreditsWorkbook(val fileName: String, val uri: URI, spreadsheets: List<Spreadsheet>) {
        val spreadsheets = spreadsheets.toMutableList().also {
            dedupNames(it, Spreadsheet::name, Spreadsheet::withName)
        }
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ProjectIntake").apply { isDaemon = true }
    }

    private val creditsWorkbooks = HashMap<Path, CreditsWorkbook>()
    private val creditsLogs = HashMap<Path, List<ParserMsg>>()
    private val linkedCreditsWatchers = HashMap<Path, ServiceWatcher>()

    private var auxFileEventBatch = HashMap<Path, RecursiveFileWatcher.Event>()
    private val auxFileEventBatchLock = ReentrantLock()
    private val auxFileEventBatchProcessor = AtomicReference<ScheduledFuture<*>?>()

    private val projectFonts = HashMap<Path, List<Font>>()
    private val pictureLoaders = PathTreeMap<Picture.Loader>()
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

        // Load the initially present credits files in the executor thread.
        val creditsFiles = try {
            projectDir.listDirectoryEntries()
        } catch (e: IOException) {
            if (e !is NoSuchFileException)
                LOGGER.error("Cannot list files in project dir '{}'.", projectDir, e)
            emptyList()
        }.filter { file -> file.isRegularFile() && hasCreditsFilename(file) }
        executor.submit(throwableAwareTask { reloadCreditsFiles(creditsFiles) })

        // Watch for future changes in the new project dir.
        RecursiveFileWatcher.watch(projectDir) { event: RecursiveFileWatcher.Event, file: Path ->
            if (hasCreditsFilename(file)) {
                // Process changes to the credits file in the executor thread.
                // Also wait a moment so that the file has been fully written.
                executor.schedule(throwableAwareTask { reloadCreditsFiles(listOf(file)) }, 100, TimeUnit.MILLISECONDS)
            } else {
                // Changes to auxiliary files are batched to reduce the number of pushes when, e.g., a long image
                // sequence is copied into the project dir.
                // For this, we first record the event to a map, overriding the previous event for that file if there
                // was any. We also record events for the changed file's parent dir to account for image sequences.
                auxFileEventBatchLock.withLock {
                    val batch = auxFileEventBatch
                    val parent = file.parent
                    batch[file] = event
                    batch[parent] = if (parent.exists()) MODIFY else DELETE
                }
                // We then schedule a task that will later apply the batched changes in one go.
                val newProcessor = executor.schedule(throwableAwareTask {
                    val batch = auxFileEventBatchLock.withLock {
                        auxFileEventBatch.also { auxFileEventBatch = HashMap() }
                    }
                    for ((defFile, defEvent) in batch) {
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
        for (watcher in linkedCreditsWatchers.values)
            watcher.poll()
    }

    fun close() {
        // Don't close the intake twice, as the call to executor.submit() would throw an exception.
        if (executor.isShutdown)
            return

        // Cancel the previous project dir change watching order.
        RecursiveFileWatcher.unwatch(projectDir)

        executor.submit(throwableAwareTask {
            // Close all loaded pictures.
            pictureLoaders.forEachValue(action = Picture.Loader::close)

            // Close all recognized tapes.
            for (tape in tapes.values)
                tape.close()

            // Stop watching for online changes.
            for (watcher in linkedCreditsWatchers.values)
                watcher.cancel()
            linkedCreditsWatchers.clear()
        })

        executor.shutdown()
    }

    private fun reloadCreditsFiles(changedFiles: List<Path>) {
        var push = false
        for (changedFile in changedFiles) if (!changedFile.isRegularFile()) {
            creditsWorkbooks.remove(changedFile)
            creditsLogs.remove(changedFile)
            linkedCreditsWatchers.remove(changedFile)?.cancel()
            push = true
        } else {
            val fileExt = changedFile.extension
            try {
                if (fileExt in SERVICE_LINK_EXTS) {
                    val link = readServiceLink(changedFile)
                    val service = SERVICES.find { it.canWatch(link) }
                    if (service == null) {
                        val msg = l10n("projectIO.credits.unsupportedServiceLink", l10nQuoted(link))
                        val msgObj = ParserMsg(changedFile.name, null, null, null, null, ERROR, msg)
                        creditsWorkbooks.remove(changedFile)
                        creditsLogs[changedFile] = listOf(msgObj)
                        linkedCreditsWatchers.remove(changedFile)?.cancel()
                    } else {
                        linkedCreditsWatchers.put(changedFile, service.watch(link, object : ServiceWatcher.Callbacks {
                            override fun content(spreadsheets: List<Spreadsheet>) {
                                creditsWorkbooks[changedFile] = CreditsWorkbook(changedFile.name, link, spreadsheets)
                                creditsLogs.remove(changedFile)
                                pushCreditsWorkbooks()
                            }

                            override fun problem(problem: ServiceWatcher.Problem) {
                                val key = when (problem) {
                                    ServiceWatcher.Problem.INACCESSIBLE -> "projectIO.credits.noAccountGrantsAccess"
                                    ServiceWatcher.Problem.DOWN -> "projectIO.credits.serviceUnresponsive"
                                }
                                val msg = ParserMsg(changedFile.name, null, null, null, null, ERROR, l10n(key))
                                creditsWorkbooks[changedFile] = CreditsWorkbook(changedFile.name, link, emptyList())
                                creditsLogs[changedFile] = listOf(msg)
                                pushCreditsWorkbooks()
                            }
                        }))?.cancel()
                    }
                } else {
                    val fmt = SPREADSHEET_FORMATS.first { fmt -> fmt.fileExt.equals(fileExt, ignoreCase = true) }
                    val (spreadsheets, loadingLog) = fmt.read(changedFile, l10n("project.template.spreadsheetName"))
                    creditsWorkbooks[changedFile] = CreditsWorkbook(changedFile.name, changedFile.toUri(), spreadsheets)
                    creditsLogs[changedFile] = loadingLog
                    push = true
                }
            } catch (e: Exception) {
                // General exceptions can occur if the credits file is ill-formatted.
                // An IO exception can occur if the credits file has disappeared in the meantime. If that happens,
                // the file watcher should quickly trigger a call to this method again. Still, we push an
                // error message in case something else goes wrong too.
                val e = (e as? ch.rabanti.nanoxlsx4j.exceptions.IOException)?.innerException ?: e
                LOGGER.error("Could not read the credits file '{}'.", changedFile, e)
                val msg = l10n("projectIO.credits.cannotReadCreditsFile", e.userNotification)
                creditsLogs[changedFile] = listOf(ParserMsg(changedFile.name, null, null, null, null, ERROR, msg))
                push = true
            }
        }
        if (push)
            pushCreditsWorkbooks()
    }

    private fun pushCreditsWorkbooks() {
        val creditsWorkbooks = this.creditsWorkbooks.values
            // An ad-hoc solution to ignore CSV tape timeline exports.
            .filterNotTo(mutableListOf()) { wb ->
                wb.fileName.endsWith(".csv") && wb.spreadsheets.size == 1 &&
                        wb.spreadsheets[0].run { numRecords != 0 && numColumns != 0 && get(0, 0) == "Record In" }
            }
        dedupNames(creditsWorkbooks, CreditsWorkbook::fileName) { w, n -> CreditsWorkbook(n, w.uri, w.spreadsheets) }

        val log = creditsLogs.values.flatMapTo(mutableListOf()) { it }
        if (creditsWorkbooks.isEmpty() && creditsLogs.isEmpty() && linkedCreditsWatchers.isEmpty()) {
            val msg = l10n("projectIO.credits.noCreditsFile", "<i>${l10nEnum(CREDITS_EXTS)}</i>")
            log += ParserMsg(null, null, null, null, null, ERROR, msg)
        }

        callbacks.pushCreditsWorkbooks(creditsWorkbooks, log, pollable = linkedCreditsWatchers.isNotEmpty())
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

        Picture.Loader.recognize(fileOrDir)?.let { pictureLoader ->
            pictureLoaders.put(fileOrDir, pictureLoader)?.close()
            pictureLoadersChanged = true
            return
        }

        Tape.recognize(fileOrDir)?.let { tape ->
            tapes.put(fileOrDir, tape)?.close()
            tapesChanged = true
            // If this is a long image sequence tape, disable all picture loaders inside the sequence folder.
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
            pictureLoader.close()
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
            callbacks.pushProjectFonts(buildDedupMap { put ->
                for (fonts in projectFonts.values)
                    for (font in fonts)
                        put(font.name, font)
            })

        if (pictureLoadersChanged) {
            // Exclude all pictures that belong to a file sequence tape with too many frames.
            val exclude = tapes.values.mapNotNullTo(HashSet()) { tape ->
                if (tape.fileSeq && (tape.availableRange.run { endExclusive - start } as Timecode.Frames).frames > 10)
                    tape.fileOrDir else null
            }
            callbacks.pushPictureLoaders(buildDedupMap { put ->
                pictureLoaders.forEachValue(exclude) { pictureLoader ->
                    put(pictureLoader.file.name, pictureLoader)
                }
            })
        }

        if (tapesChanged)
            callbacks.pushTapes(buildDedupMap { put ->
                for (tape in tapes.values)
                    put(tape.fileOrDir.name, tape)
            })

        projectFontsChanged = false
        pictureLoadersChanged = false
        tapesChanged = false
    }

    private fun <V : Any> buildDedupMap(builderAction: ((String, V) -> Unit) -> Unit): Map<String, V> {
        val map = HashMap<String, V>()
        val dupKeys = HashSet<String>()
        builderAction { key, value ->
            if (key !in dupKeys && map.put(key, value) != null) {
                map.remove(key)
                dupKeys.add(key)
            }
        }
        return map
    }


    companion object {

        private val CREDITS_EXTS = SPREADSHEET_FORMATS.map(SpreadsheetFormat::fileExt) + SERVICE_LINK_EXTS
        private val FONT_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "ttf", "ttc", "otf", "otc")

        fun hasCreditsFilename(file: Path): Boolean {
            val fileExt = file.extension
            return CREDITS_EXTS.any { ext -> ext.equals(fileExt, ignoreCase = true) }
        }

        private fun tryReadFonts(file: Path): List<Font> {
            if (file.isRegularFile() && file.extension in FONT_EXTS)
                try {
                    return Font.read(file)
                } catch (e: Exception) {
                    LOGGER.error("Font '{}' cannot be read.", file.name, e)
                }
            return emptyList()
        }

        private inline fun <T> dedupNames(list: MutableList<T>, getName: (T) -> String, changeName: (T, String) -> T) {
            for (idx in list.indices) {
                val elem = list[idx]
                var name = getName(elem)
                var counter = 1
                while (list.subList(0, idx).any { getName(it) == name })
                    name = "${getName(elem)} (${++counter})"
                if (name != getName(elem))
                    list[idx] = changeName(elem, name)
            }
        }

    }


    private class PathTreeMap<V : Any> {

        private val root = Node<V>(Path(""), null)

        operator fun get(path: Path): V? {
            var node = root
            for (component in path)
                node = node.children[component] ?: return null
            return node.value
        }

        fun put(path: Path, value: V): V? {
            var node = root
            for ((i, component) in path.withIndex())
                node = node.children.computeIfAbsent(component) {
                    // We can't use Path.subpath() to compute nodePath as it turns absolute paths into relative ones.
                    var nodePath = path
                    repeat(path.nameCount - i - 1) { nodePath = nodePath.parent }
                    Node(nodePath, null)
                }
            return node.value.also { node.value = value }
        }

        fun remove(path: Path): V? {
            var parentNode = root
            for (component in path.parent)
                parentNode = parentNode.children[component] ?: return null
            val filename = path.fileName
            val node = parentNode.children[filename] ?: return null
            if (node.children.isEmpty())
                parentNode.children.remove(filename)
            return node.value
        }

        fun forEachValue(exclude: Set<Path>? = null, action: (V) -> Unit) {
            fun recursion(node: Node<V>) {
                node.value?.let(action)
                for (child in node.children.values)
                    if (exclude == null || child.path !in exclude)
                        recursion(child)
            }
            recursion(root)
        }

        private class Node<V>(val path: Path, var value: V?) {
            val children = HashMap<Path, Node<V>>()
        }

    }

}
