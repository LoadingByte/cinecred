package com.loadingbyte.cinecred.projectio

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import kotlin.io.path.isDirectory


object RecursiveFileWatcher {

    private class Order(val listener: (Path, WatchEvent.Kind<*>) -> Unit, val watchKeys: MutableSet<WatchKey>)

    private val watcher = FileSystems.getDefault().newWatchService()
    private val orders = HashMap<Path, Order>()
    private val lock = Any()

    fun watch(rootDir: Path, listener: (Path, WatchEvent.Kind<*>) -> Unit) {
        synchronized(lock) {
            val watchKeys = HashSet<WatchKey>()
            for (file in Files.walk(rootDir))
                if (file.isDirectory())
                    watchKeys.add(file.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY))
            orders[rootDir] = Order(listener, watchKeys)
        }
    }

    fun unwatch(rootDir: Path) {
        synchronized(lock) {
            orders.remove(rootDir)?.watchKeys?.forEach(WatchKey::cancel)
        }
    }

    init {
        Thread({
            while (true) {
                val watchKey = watcher.take()
                val order = orders.values.first { order -> watchKey in order.watchKeys }
                synchronized(lock) {
                    for (event in watchKey.pollEvents())
                        if (event.kind() != OVERFLOW) {
                            val file = (watchKey.watchable() as Path).resolve(event.context() as Path)

                            // If the file is a directory, we have to (de)register a watching instruction for it.
                            if (file.isDirectory())
                                if (event.kind() == ENTRY_CREATE) {
                                    val newWatchKey = file.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                                    order.watchKeys.add(newWatchKey)
                                } else if (event.kind() == ENTRY_DELETE)
                                    order.watchKeys.find { it.watchable() == file }?.cancel()

                            order.listener(file, event.kind())
                        }
                    watchKey.reset()
                }
            }
        }, "DirectoryWatcherThread").apply { isDaemon = true }.start()
    }

}
