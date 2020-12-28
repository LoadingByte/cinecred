package com.loadingbyte.cinecred.delivery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock


interface RenderJob {
    val destination: String
    fun render(progressCallback: (Float) -> Unit)
}


object RenderQueue {

    private class SubmittedJob(
        val job: RenderJob,
        val invokeLater: (() -> Unit) -> Unit,
        val progressCallback: (Float) -> Unit,
        val finishCallback: (Exception?) -> Unit
    )

    private val thread: Thread

    private val pauseLock = ReentrantLock()

    private val queuedJobs = LinkedBlockingQueue<SubmittedJob>()
    private val cancelledJobs = ConcurrentHashMap.newKeySet<RenderJob>()
    private var runningJob = AtomicReference<RenderJob?>(null)

    init {
        thread = Thread({
            while (true) {
                val job = try {
                    queuedJobs.take()
                } catch (_: InterruptedException) {
                    continue
                }

                // Only start with a new rendering task if paused=false.
                pauseLock.lock()
                pauseLock.unlock()

                try {
                    runningJob.set(job.job)
                    // If the job has been cancelled, skip it.
                    if (cancelledJobs.remove(job.job))
                        continue
                    // Otherwise, start rendering.
                    job.invokeLater { job.progressCallback(0f) }
                    job.job.render { progress -> job.invokeLater { job.progressCallback(progress) } }
                    runningJob.set(null)
                } catch (e: Exception) {
                    // Note that this catch also catches InterruptedExceptions,
                    // which occur when a job is cancelled mid-run.
                    job.invokeLater { job.finishCallback(e) }
                    continue
                }
                job.invokeLater { job.progressCallback(1f) }
                job.invokeLater { job.finishCallback(null) }
            }
        }, "RenderQueueThread").apply { isDaemon = true; start() }
    }

    var isPaused: Boolean
        get() = pauseLock.isLocked
        set(value) {
            if (isPaused != value)
                if (value) pauseLock.lock()
                else pauseLock.unlock()
        }

    init {
        // Initially, the render queue should be paused.
        isPaused = true
    }

    fun submitJob(
        job: RenderJob,
        invokeLater: (() -> Unit) -> Unit,
        progressCallback: (Float) -> Unit,
        finishCallback: (Exception?) -> Unit
    ) {
        queuedJobs.add(SubmittedJob(job, invokeLater, progressCallback, finishCallback))
    }

    fun cancelJob(job: RenderJob) {
        // Mark the job for future cancellation so that before .
        cancelledJobs.add(job)
        if (job == runningJob.get())
            thread.interrupt()
    }

}
