package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.throwableAwareTask
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class JobSlot {

    private val lock = ReentrantLock()
    private var job: Runnable? = null
    private var isRunning = false

    fun submit(job: Runnable) {
        lock.withLock {
            val hadJob = this.job != null
            this.job = job
            if (!hadJob && !isRunning)
                executor.submit(throwableAwareTask(::run))
        }
    }

    private fun run() {
        val job = lock.withLock {
            val job = this.job!!
            this.job = null
            isRunning = true
            job
        }
        job.run()
        lock.withLock {
            isRunning = false
            if (this.job != null)
                executor.submit(throwableAwareTask(::run))
        }
    }

    companion object {
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "JobSlotRunner").apply { isDaemon = true }
        }
    }

}
