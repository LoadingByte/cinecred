package com.loadingbyte.cinecred.common

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class JobSlot(slots: Int = 1) {

    private val lock = ReentrantLock()
    private val jobs = arrayOfNulls<Runnable>(slots)
    private val jobStartTimes = LongArray(slots)
    private var isRunning = false
    private var sleepingForSlot = -1
    private val sleepingSemaphore = Semaphore(0)

    fun submit(slot: Int = 0, delay: Int = 0, job: Runnable) {
        val jobStartTime = System.currentTimeMillis() + delay
        lock.withLock {
            jobs[slot] = job
            jobStartTimes[slot] = jobStartTime
            if (!isRunning) {
                isRunning = true
                GLOBAL_THREAD_POOL.submit(throwableAwareTask(::run))
            } else if (sleepingForSlot >= slot)
                sleepingSemaphore.release()
        }
    }

    fun unsubmit(slot: Int = 0) {
        lock.withLock {
            jobs[slot] = null
            if (sleepingForSlot == slot)
                sleepingSemaphore.release()
        }
    }

    private fun run() {
        while (true) {
            val slot: Int
            val job: Runnable
            val sleepTime: Long
            lock.withLock {
                slot = jobs.indexOfFirst { it != null }
                if (slot == -1) {
                    isRunning = false
                    return
                }
                job = jobs[slot]!!
                sleepTime = jobStartTimes[slot] - System.currentTimeMillis()
                if (sleepTime > 0L)
                    sleepingForSlot = slot
            }
            if (sleepTime > 0L) {
                val interrupted = sleepingSemaphore.tryAcquire(sleepTime, TimeUnit.MILLISECONDS)
                lock.withLock {
                    sleepingForSlot = -1
                    // Clear the permits, in case either multiple ones were released, or some were released just after
                    // tryAcquire() returned, but before sleepingForSlot was reset to -1.
                    sleepingSemaphore.drainPermits()
                }
                if (interrupted)
                    continue
            }
            job.run()
            lock.withLock {
                if (jobs[slot] === job)
                    jobs[slot] = null
            }
        }
    }

}
