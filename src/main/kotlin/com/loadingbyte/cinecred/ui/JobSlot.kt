package com.loadingbyte.cinecred.ui

import java.util.concurrent.Executors


class JobSlot {

    private val lock = Any()
    private var job: Runnable? = null

    fun submit(job: Runnable) {
        synchronized(lock) {
            val hadJob = this.job != null
            this.job = job
            if (!hadJob)
                executor.submit(::run)
        }
    }

    private fun run() {
        val job = synchronized(lock) {
            val job = this.job!!
            this.job = null
            job
        }
        job.run()
    }

    companion object {
        private var threadCount = 0
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "JobSlotRunner-${threadCount++}").apply { isDaemon = true }
        }
    }

}
