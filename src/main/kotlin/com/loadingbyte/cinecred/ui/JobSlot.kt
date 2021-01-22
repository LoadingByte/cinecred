package com.loadingbyte.cinecred.ui

import java.util.concurrent.Executors


class JobSlot {

    private val lock = Any()
    private var job: Runnable? = null
    private var isRunning = false

    fun submit(job: Runnable) {
        synchronized(lock) {
            val hadJob = this.job != null
            this.job = job
            if (!hadJob && !isRunning)
                executor.submit(::run)
        }
    }

    private fun run() {
        val job = synchronized(lock) {
            val job = this.job!!
            this.job = null
            isRunning = true
            job
        }
        job.run()
        synchronized(lock) {
            isRunning = false
            if (this.job != null)
                executor.submit(::run)
        }
    }

    companion object {
        private var threadCount = 0
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "JobSlotRunner-${threadCount++}").apply { isDaemon = true }
        }
    }

}
