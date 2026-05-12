package com.loadingbyte.cinecred.common

import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class SizedValue<V : Any>(val value: V, val bytes: Long)


class DisposableReference<V : Any>(sizedValue: SizedValue<V>) : AutoCloseable {

    private val trackerKey = Any()
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKey))

    init {
        DisposableTracker.put(trackerKey, sizedValue)
    }

    constructor(value: V, bytes: Long) : this(SizedValue(value, bytes))

    @Suppress("UNCHECKED_CAST")
    fun get(): V? =
        DisposableTracker.get(trackerKey) as V?

    override fun close() {
        cleanable.clean()
    }

    fun getAndClose(): V? =
        get().also { close() }

    private class CleanerAction(private val trackerKey: Any) : Runnable {
        override fun run() {
            DisposableTracker.remove(trackerKey)
        }
    }

}


class DisposableCache<K : Any, V : Any> : AutoCloseable {

    private val cacheId = Any()
    // The key set is only accessed or modified when the MemoryTracker lock is held, hence we don't need more locking.
    private val trackerKeys = Collections.newSetFromMap(WeakHashMap<TrackerKey, Boolean>())
    private val cleanable = CLEANER.register(this, CleanerAction(trackerKeys))

    inline fun get(key: K, crossinline compute: () -> SizedValue<V>): V =
        getAsync(key) { CompletableFuture.completedFuture(compute()) }.get()

    fun getAsync(key: K, compute: () -> CompletableFuture<SizedValue<V>>): CompletableFuture<V> {
        val trackerKey = TrackerKey(cacheId, key)
        var computeFuture: CompletableFuture<SizedValue<*>>? = null
        val getFuture = DisposableTracker.cache(trackerKey) {
            trackerKeys += trackerKey
            CompletableFuture<SizedValue<*>>().also { computeFuture = it }
        }
        // Run the user-provided compute function outside the cache lambda to not block the lock for too long.
        if (computeFuture != null)
            compute().thenAccept(computeFuture::complete)
        @Suppress("UNCHECKED_CAST")
        return getFuture as CompletableFuture<V>
    }

    fun getAll(): List<V> =
        getAllAsync().map(CompletableFuture<V>::get)

    @Suppress("UNCHECKED_CAST")
    fun getAllAsync(): List<CompletableFuture<V>> =
        DisposableTracker.getAllAsync(trackerKeys) as List<CompletableFuture<V>>

    override fun close() {
        cleanable.clean()
    }

    private data class TrackerKey(private val cacheId: Any, private val cacheKey: Any)

    private class CleanerAction(private val trackerKeys: Iterable<TrackerKey>) : Runnable {
        override fun run() {
            DisposableTracker.removeAll(trackerKeys)
        }
    }

}


private object DisposableTracker {

    private val maxBytes: Long

    init {
        // We want to limit memory-tracked objects to use at most 20% of the available memory. To find out how much
        // system memory is available in total, we find the maximum heap size, then divide by the maximum heap size to
        // RAM ratio specified at VM start up.
        val opt = "-XX:MaxRAMPercentage"
        val arg = ManagementFactory.getRuntimeMXBean().inputArguments.find { it.startsWith(opt) }
        checkNotNull(arg) { "Expected the VM to be run with $opt." }
        val ratio = arg.substring(opt.length + 1).toDouble() / 100.0
        maxBytes = (0.2 * (Runtime.getRuntime().maxMemory() / ratio)).toLong()
    }

    private val lock = ReentrantLock()
    private val map = LinkedHashMap<Any, CompletableFuture<SizedValue<*>>>(16, 0.75f, true)
    private var curBytes = 0L

    fun get(key: Any): Any? =
        lock.withLock {
            map[key]
        }?.let { future -> future.get().value }

    fun getAllAsync(keys: Iterable<Any>): List<CompletableFuture<*>> {
        return lock.withLock {
            keys.mapNotNull { key -> map[key]?.thenApply(SizedValue<*>::value) }
        }
    }

    fun put(key: Any, sv: SizedValue<*>) {
        lock.withLock {
            if (map.put(key, CompletableFuture.completedFuture(sv)) != null)
                throw UnsupportedOperationException("Cannot override previous mappings.")
            curBytes += sv.bytes
            evictIfFull()
        }
    }

    fun cache(key: Any, compute: () -> CompletableFuture<SizedValue<*>>): CompletableFuture<*> {
        val future: CompletableFuture<SizedValue<*>>
        lock.withLock {
            future = map.computeIfAbsent(key) {
                compute().also { future -> future.thenAccept { sv -> lock.withLock { curBytes += sv.bytes } } }
            }
            evictIfFull()
        }
        return future.thenApply(SizedValue<*>::value)
    }

    fun remove(key: Any) {
        lock.withLock {
            map.remove(key)?.thenAccept { sv -> lock.withLock { curBytes -= sv.bytes } }
        }
    }

    fun removeAll(keys: Iterable<Any>) {
        lock.withLock {
            keys.mapNotNull { key ->
                map.remove(key)?.thenAccept { sv -> lock.withLock { curBytes -= sv.bytes } }
            }
        }
    }

    // Note: Must be called while holding the lock.
    private fun evictIfFull() {
        if (curBytes > maxBytes) {
            val iter = map.iterator()
            while (curBytes > maxBytes && iter.hasNext()) {
                val future = iter.next().value
                // Only evict completed futures. If there are actually pending futures to be evicted (highly unlikely),
                // that will be done at a later date once they're completed.
                if (future.isDone)
                    curBytes -= future.get().bytes
                iter.remove()
            }
        }
    }

}
