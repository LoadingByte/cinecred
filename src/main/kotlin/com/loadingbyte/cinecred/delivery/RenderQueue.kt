package com.loadingbyte.cinecred.delivery

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


interface RenderJob {
    fun generatesFile(file: Path): Boolean
    fun render(progressCallback: (Float) -> Unit)
}


object RenderQueue {

    private class SubmittedJob(
        val category: Any,
        val job: RenderJob,
        val invokeLater: (() -> Unit) -> Unit,
        val progressCallback: (Float) -> Unit,
        val finishCallback: (Exception?) -> Unit
    )

    private val thread: Thread

    private val willPauseCategories = ConcurrentHashMap.newKeySet<Any>()
    private val willUnpauseCategories = LinkedBlockingQueue<Any>()
    private val queuedJobs = ConcurrentHashMap<Any, ConcurrentLinkedQueue<SubmittedJob>>()

    private val pollJobLock = ReentrantLock()
    private var runningJob: SubmittedJob? = null

    init {
        thread = Thread({
            while (true) {
                val category = try {
                    willUnpauseCategories.take()
                } catch (_: InterruptedException) {
                    continue
                }

                val queue = queuedJobs[category] ?: continue

                while (true) {
                    // If the category should be paused now, continue with the next unpaused category.
                    if (category in willPauseCategories)
                        break

                    // Get the next job from the current category's queue.
                    val job: SubmittedJob?
                    pollJobLock.withLock {
                        job = queue.poll()
                        runningJob = job
                    }
                    // If the queue is empty, continue with the next unpaused category.
                    if (job == null)
                        break

                    try {
                        // Start rendering.
                        job.invokeLater { job.progressCallback(0f) }
                        job.job.render { progress -> job.invokeLater { job.progressCallback(progress) } }
                        pollJobLock.withLock { runningJob = null }
                        job.invokeLater { job.progressCallback(1f) }
                        job.invokeLater { job.finishCallback(null) }
                    } catch (e: Exception) {
                        // Note that this catch also catches InterruptedExceptions,
                        // which occurs when a job is cancelled while it is running.
                        pollJobLock.withLock { runningJob = null }
                        job.invokeLater { job.finishCallback(e) }
                    }
                }
            }
        }, "RenderQueueThread").apply { isDaemon = true; start() }
    }

    fun setPaused(category: Any, paused: Boolean) {
        if (paused) {
            willPauseCategories.add(category)
            willUnpauseCategories.remove(category)
        } else {
            // Important that we first remove the category from willPause and then add it to willUnpause.
            // If we did it the other way around, the category could be polled from willUnpause, but then
            // immediately discarded again as it's still in willPause.
            willPauseCategories.remove(category)
            willUnpauseCategories.add(category)
        }
    }

    fun submitJob(
        category: Any,
        job: RenderJob,
        invokeLater: (() -> Unit) -> Unit,
        progressCallback: (Float) -> Unit,
        finishCallback: (Exception?) -> Unit
    ) {
        val queue = queuedJobs.getOrPut(category) { ConcurrentLinkedQueue() }
        queue.add(SubmittedJob(category, job, invokeLater, progressCallback, finishCallback))
    }

    fun cancelJob(category: Any, job: RenderJob) {
        pollJobLock.withLock {
            // If the job hasn't started yet, remove it from the queue.
            queuedJobs[category]?.removeIf { it.job == job }
            // If the job is currently running, immediately interrupt the rendering thread.
            if (runningJob?.job == job)
                thread.interrupt()
        }
    }

    fun cancelAllJobs(category: Any) {
        pollJobLock.withLock {
            queuedJobs[category]?.clear()
            if (runningJob?.category == category)
                thread.interrupt()
        }
    }

}
