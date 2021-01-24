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
            if (!hadJob && !isRunning) {
                // Note: By using execute() instead of submit(), exceptions thrown by the job will propagate upward
                // and eventually kill the program in a controlled fashion with a useful error message. This is the
                // behavior we want.
                executor.execute(::run)
            }
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
            if (this.job != null) {
                // Once again, we're using execute() instead of submit() because of the reasons mentioned above
                // in this class's submit() method.
                executor.execute(::run)
            }
        }
    }

    companion object {
        private var threadCount = 0
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "JobSlotRunner-${threadCount++}").apply { isDaemon = true }
        }
    }

}
