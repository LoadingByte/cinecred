package com.loadingbyte.cinecred.ui.helper

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
            if (!hadJob && !isRunning) {
                // Note: By using execute() instead of submit(), exceptions thrown by the job will propagate upward
                // and eventually kill the program in a controlled fashion with a useful error message. This is the
                // behavior we want.
                executor.execute(::run)
            }
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
            if (this.job != null) {
                // Once again, we're using execute() instead of submit() because of the reasons mentioned above
                // in this class's submit() method.
                executor.execute(::run)
            }
        }
    }

    companion object {
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "JobSlotRunner").apply { isDaemon = true }
        }
    }

}
