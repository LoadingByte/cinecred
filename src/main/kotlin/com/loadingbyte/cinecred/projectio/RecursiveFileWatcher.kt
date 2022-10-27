package com.loadingbyte.cinecred.projectio

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Stream
import kotlin.concurrent.withLock
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile


object RecursiveFileWatcher {

    enum class Event { MODIFY, DELETE }

    private class Order(val listener: (Event, Path) -> Unit) {
        val watchKeys = HashSet<WatchKey>()  // Use a set to speed up the "in" operation.
        val memory = HashMap<Path, MemoryEntry>()
        var continuousPolling = true
        var onetimePolling = false
    }

    private class MemoryEntry(var lastModMillis: Long, var lastSeenTick: Long)


    private val watcher = FileSystems.getDefault().newWatchService()
    private val orders = HashMap<Path, Order>()
    private val lock = ReentrantLock()
    private var pollingTick: Long = 0

    init {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "FileWatcher-Poll").apply { isDaemon = true } }
            .scheduleAtFixedRate(::poll, 1, 1, TimeUnit.SECONDS)

        Thread({
            while (true) {
                val watchKey = watcher.take()
                receiveWatchKey(watchKey)
                watchKey.reset()
            }
        }, "FileWatcher-Native").apply { isDaemon = true }.start()
    }

    fun watch(rootDir: Path, listener: (Event, Path) -> Unit) {
        lock.withLock {
            val order = Order(listener)
            orders[rootDir] = order
            // Recursively memorize all files below the root directory, notify the listener about them, and register
            // watching instructions for the root directory and all subdirectories.
            setupFileTree(order, rootDir, notifyListener = false)
        }
    }

    fun unwatch(rootDir: Path) {
        lock.withLock {
            orders.remove(rootDir)?.run { watchKeys.forEach(WatchKey::cancel) }
        }
    }

    private fun poll() {
        lock.withLock {
            pollingTick++
            for ((rootDir, order) in orders)
                if (order.continuousPolling || order.onetimePolling) {
                    order.onetimePolling = false
                    // Check whether the modification time of any file in the file tree has changed (including new
                    // files!), and if so, notify the listener. Also refresh the last seen tick of every existing file.
                    for (file in safeWalk(rootDir))
                        if (file.isRegularFile())
                            potentialModification(order, file, setLastSeenTick = true)
                    // De-memorize all files which have not been seen this tick, and notify the listener about them.
                    order.memory.entries.remAndDoIf(
                        { (_, memoryEntry) -> memoryEntry.lastSeenTick != pollingTick },
                        { (file, _) -> order.listener(Event.DELETE, file) })
                }
        }
    }

    private fun receiveWatchKey(watchKey: WatchKey) {
        lock.withLock {
            val order = orders.values.first { order -> watchKey in order.watchKeys }

            // We have just seen that the native file watcher is working, so stop polling this root directory.
            order.continuousPolling = false

            for (event in watchKey.pollEvents())
                if (event.kind() == OVERFLOW) {
                    // In the rare case of too many events overflowing the native file watcher, go back to polling for
                    // just one cycle to catch up.
                    order.onetimePolling = true
                } else {
                    val file = (watchKey.watchable() as Path).resolve(event.context() as Path)

                    // When a directory is created, memorize all files below it (recursively), notify the listener about
                    // them, and register watching instructions for the new directory and all its subdirectories.
                    if (event.kind() == ENTRY_CREATE && file.isDirectory())
                        setupFileTree(order, file, notifyListener = true)

                    // When a directory is deleted, deregister the instructions for it and all its subdirectories.
                    if (event.kind() == ENTRY_DELETE) {
                        val wasDir = order.watchKeys.remAndDoIf(
                            { (it.watchable() as Path).startsWith(file) },
                            { it.cancel() })
                        // Also de-memorize all files somewhere below the directory and notify the listener about them.
                        if (wasDir)
                            order.memory.keys.remAndDoIf({ it.startsWith(file) }, { order.listener(Event.DELETE, it) })
                    }

                    // When a regular file is created or modified, check the modification time and notify the listener.
                    if ((event.kind() == ENTRY_CREATE || event.kind() == ENTRY_MODIFY) && file.isRegularFile())
                        potentialModification(order, file, setLastSeenTick = false)

                    // When a regular file is deleted, de-memorize it and notify the listener.
                    if (event.kind() == ENTRY_DELETE && order.memory.remove(file) != null)
                        order.listener(Event.DELETE, file)
                }
        }
    }

    private fun setupFileTree(order: Order, dir: Path, notifyListener: Boolean) {
        // Memorize all regular files and their current modification times.
        for (file in safeWalk(dir))
            if (file.isRegularFile()) {
                try {
                    order.memory[file] = MemoryEntry(file.getLastModifiedTime().toMillis(), -1)
                } catch (_: NoSuchFileException) {
                    // The file was deleted right after isRegularFile() returned true. Unlucky timing, but do
                    // not add the memory entry then.
                    continue
                }
                // If the file was instead successfully memorized, notify the listener about it if requested.
                if (notifyListener)
                    order.listener(Event.MODIFY, file)
            }

        // Only after the memorization is complete, register a file watcher in each directory of the file tree.
        for (file in safeWalk(dir))
            if (file.isDirectory())
                try {
                    order.watchKeys.add(file.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY))
                } catch (_: NoSuchFileException) {
                    // Once again, unlucky timing.
                }
    }

    private fun safeWalk(dir: Path) = try {
        Files.walk(dir, FileVisitOption.FOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        Stream.empty()
    }


    private fun potentialModification(order: Order, file: Path, setLastSeenTick: Boolean) {
        val memoryEntry = order.memory.computeIfAbsent(file) { MemoryEntry(-1, -1) }
        val lastModMillis = try {
            file.getLastModifiedTime().toMillis()
        } catch (_: NoSuchFileException) {
            // The file was deleted between it being detected and this code being reached. Abort this method and let the
            // deletion detector notify the listener in a moment.
            return
        }
        if (memoryEntry.lastModMillis != lastModMillis) {
            memoryEntry.lastModMillis = lastModMillis
            order.listener(Event.MODIFY, file)
        }
        if (setLastSeenTick)
            memoryEntry.lastSeenTick = pollingTick
    }

    private inline fun <E> MutableCollection<E>.remAndDoIf(filter: (E) -> Boolean, action: (E) -> Unit): Boolean {
        var removed = false
        val iter = iterator()
        for (elem in iter)
            if (filter(elem)) {
                removed = true
                iter.remove()
                action(elem)
            }
        return removed
    }

}
