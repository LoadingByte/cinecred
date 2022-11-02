package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.LOGGER
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
                        job.progressCallback(0f)
                        job.job.render(job.progressCallback)
                        pollJobLock.withLock { runningJob = null }
                        job.progressCallback(1f)
                        job.finishCallback(null)
                    } catch (e: Exception) {
                        // Note that this catch also catches InterruptedExceptions,
                        // which occurs when a job is cancelled while it is running.
                        pollJobLock.withLock { runningJob = null }
                        if (e !is InterruptedException)
                            LOGGER.error("Error while rendering", e)
                        job.finishCallback(e)
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

    fun getRemainingJobs(): List<RenderJob> =
        pollJobLock.withLock {
            val remJobs = ArrayList<RenderJob>()
            runningJob?.let { remJobs.add(it.job) }
            for (queue in queuedJobs.values)
                for (job in queue)
                    remJobs.add(job.job)
            remJobs
        }

    fun submitJob(
        category: Any,
        job: RenderJob,
        progressCallback: (Float) -> Unit,
        finishCallback: (Exception?) -> Unit
    ) {
        val queue = queuedJobs.computeIfAbsent(category) { ConcurrentLinkedQueue() }
        queue.add(SubmittedJob(category, job, progressCallback, finishCallback))
    }

    fun cancelJob(category: Any, job: RenderJob) {
        pollJobLock.withLock {
            // If the job hasn't started yet, remove it from the queue and call its finish callback.
            queuedJobs[category]?.let { queue ->
                queue.find { it.job == job }?.let { subJob ->
                    queue.remove(subJob)
                    subJob.finishCallback(null)
                }
            }
            // If the job is currently running, immediately interrupt the rendering thread.
            if (runningJob?.job == job)
                thread.interrupt()
        }
    }

    fun cancelAllJobs(category: Any) {
        pollJobLock.withLock {
            queuedJobs[category]?.let { queue ->
                for (subJob in queue)
                    subJob.finishCallback(null)
                queue.clear()
            }
            if (runningJob?.category == category)
                thread.interrupt()
        }
    }

}
