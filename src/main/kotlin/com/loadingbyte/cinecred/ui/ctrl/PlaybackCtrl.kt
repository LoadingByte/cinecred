package com.loadingbyte.cinecred.ui.ctrl

import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.GLOBAL_THREAD_POOL
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.formatTimecode
import com.loadingbyte.cinecred.common.throwableAwareTask
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.helper.JobSlot
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.lang.invoke.MethodHandles
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock
import kotlin.math.*


class PlaybackCtrl(private val projectCtrl: ProjectController) : PlaybackCtrlComms {

    private val views = mutableListOf<PlaybackViewComms>()

    private val materializationCacheAWT = DeferredImage.CanvasMaterializationCache()
    private val materializationCacheDeckLink = DeferredImage.CanvasMaterializationCache()
    private val setupAWTJobSlot = JobSlot()
    private val setupDeckLinkJobSlot = JobSlot()
    private val displayNowAWTJobSlot = JobSlot()
    private val displayNowDeckLinkJobSlot = JobSlot()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Playback").apply { isDaemon = true }
    }

    private val deckLinkListener = { deckLinks: List<DeckLink> ->
        SwingUtilities.invokeLater {
            if (selectedDeckLink !in deckLinks) {
                val savedId = DECK_LINK_ID_PREFERENCE.get()
                val savedModeName = DECK_LINK_MODE_PREFERENCE.get()
                val savedDepthBits = DECK_LINK_DEPTH_PREFERENCE.get()
                val savedDeckLink = deckLinks.find { it.id == savedId }
                val savedMode = savedDeckLink?.modes?.find { it.name == savedModeName }
                val savedDepth = DeckLink.Depth.entries.find { it.bits == savedDepthBits }
                selectedDeckLink = savedDeckLink ?: deckLinks.firstOrNull()
                selectedDeckLinkMode = savedMode ?: selectedDeckLink?.modes?.first()
                selectedDeckLinkDepth = savedDepth ?: selectedDeckLinkMode?.depths?.first() ?: DeckLink.Depth.D8
                deckLinkConnected = savedDeckLink != null && savedMode != null && savedDepth != null &&
                        DECK_LINK_CONNECTED_PREFERENCE.get()
                setupDeckLink(false)
                stopPlayingIfNecessary()
                for (view in views) {
                    view.setDeckLinks(deckLinks)
                    selectedDeckLink?.modes?.let(view::setDeckLinkModes)
                    selectedDeckLinkMode?.depths?.let(view::setDeckLinkDepths)
                    selectedDeckLink?.let(view::setSelectedDeckLink)
                    selectedDeckLinkMode?.let(view::setSelectedDeckLinkMode)
                    view.setSelectedDeckLinkDepth(selectedDeckLinkDepth)
                    view.setDeckLinkConnected(deckLinkConnected)
                }
            } else
                for (view in views) {
                    view.setDeckLinks(deckLinks)
                    selectedDeckLink?.let(view::setSelectedDeckLink)
                }
        }
    }

    // Supplied by other controllers or the UI.
    private var drawnProject: DrawnProject? = null
    private var dialogVisible = false
    private var selectedDeckLink: DeckLink? = null
    private var selectedDeckLinkMode: DeckLink.Mode? = null
    private var selectedDeckLinkDepth = DeckLink.Depth.D8
    private var deckLinkConnected = false
    private var spreadsheetName: String? = null
    private var videoCanvasSize: Dimension? = null
    private var videoCanvasGCfg: GraphicsConfiguration? = null
    private var actualSize = false

    // Set during setupX().
    private var awtFrameSource: FrameSource<BufferedImage>? = null
    private var viewScaling = 1.0
    private var deckLinkFrameSource: FrameSource<Bitmap>? = null
    private var activeDeckLink: DeckLink? = null
    private var activeDeckLinkMode: DeckLink.Mode? = null
    private var activeDeckLinkDepth: DeckLink.Depth? = null

    // Set during refreshPlayback().
    private var playTask: Future<*>? = null
    @Volatile private var awtFrameBuffer: FrameBuffer<BufferedImage>? = null
    @Volatile private var deckLinkFrameBuffer: FrameBuffer<Bitmap>? = null

    private var numFrames = 1
        set(value) {
            val timecodeString = formatTimecode(value)
            for (view in views) view.setNumFrames(value, timecodeString)
            field = value
            frameIdx = frameIdx  // coerce
        }

    private var frameIdx = 0
        set(value) {
            val frameIdx = value.coerceIn(0, numFrames - 1)
            val timecodeString = formatTimecode(frameIdx)
            for (view in views) view.setPlayheadPosition(frameIdx, timecodeString)
            field = frameIdx
        }

    private var playRate = 0
        set(value) {
            val playRate =
                if (drawnProject == null || !dialogVisible && activeDeckLink == null) 0 else value.coerceIn(-6, 6)
            for (view in views) view.setPlaybackDirection(playRate.sign)
            if (field != playRate) {
                field = playRate
                refreshPlayback(true)
            }
        }

    init {
        DeckLink.addListener(deckLinkListener)
    }

    private fun stopPlayingIfNecessary() {
        if (!dialogVisible && activeDeckLink == null)
            playRate = 0
    }

    private fun setupAWT(restartSchedulers: Boolean) {
        val (global, video) = globalAndVideo ?: return

        // Abort if the dialog has never been shown on the screen yet, which would have it in a pre-initialized state
        // that this method can't cope with. As soon as it is shown for the first time, the resize listener will be
        // notified and call this method again.
        val gCfg = videoCanvasGCfg ?: return
        val nativeCM = gCfg.getColorModel(Transparency.OPAQUE) ?: return
        val frameSize = this.videoCanvasSize ?: return

        val systemScaling = UIScale.getSystemScaleFactor(gCfg)
        val awtFrameSourceScaling = if (actualSize) 1.0 else
            systemScaling * min(
                frameSize.width / global.resolution.widthPx.toDouble(),
                frameSize.height / global.resolution.heightPx.toDouble()
            )
        viewScaling = 1.0 / systemScaling

        val frameIdx = this.frameIdx
        val dialogVisible = this.dialogVisible
        setupAWTJobSlot.submit {
            // Protect against too small canvas sizes.
            val newAWTFrameSource = if (dialogVisible && awtFrameSourceScaling < 0.001) null else {
                val scaledVideo = video.copy(resolutionScaling = awtFrameSourceScaling)
                val bitmapJ2DBridge = BitmapJ2DBridge(nativeCM)
                FrameSource(
                    materializationCacheAWT, scaledVideo, global.grounding, scaledVideo.resolution,
                    bitmapJ2DBridge.nativeRepresentation, Bitmap.Scan.PROGRESSIVE, frameIdx,
                    bitmapJ2DBridge::toNativeImage
                )
            }
            SwingUtilities.invokeLater {
                awtFrameSource?.close()
                awtFrameSource = newAWTFrameSource
                displayFrameNowAWT(true)
                refreshPlayback(restartSchedulers)
            }
        }
    }

    private fun setupDeckLink(restartSchedulers: Boolean) {
        val (global, video) = globalAndVideo ?: return

        val deckLink = selectedDeckLink
        val mode = selectedDeckLinkMode
        val depth = selectedDeckLinkDepth
        var doRestartSchedulers = restartSchedulers
        if (activeDeckLink != deckLink || activeDeckLinkMode != mode || activeDeckLinkDepth != depth ||
            !deckLinkConnected
        ) {
            activeDeckLink?.release()
            activeDeckLink = null
            activeDeckLinkMode = null
            activeDeckLinkDepth = null
            if (deckLink != null && deckLinkConnected)
                if (mode == null || !deckLink.supports(mode) || depth !in mode.depths || !deckLink.acquire(mode))
                    setDeckLinkConnected(false)
                else {
                    activeDeckLink = deckLink
                    activeDeckLinkMode = mode
                    activeDeckLinkDepth = depth
                    doRestartSchedulers = true
                }
        }

        val frameIdx = this.frameIdx
        val activeMode = this.activeDeckLinkMode
        setupDeckLinkJobSlot.submit {
            val newDeckLinkFrameSource = if (activeMode == null) null else {
                val (mw, mh) = activeMode.resolution
                val (vw, vh) = video.resolution
                val scaledVideo = video.copy(resolutionScaling = min(mw / vw.toDouble(), mh / vh.toDouble()))
                FrameSource(
                    materializationCacheDeckLink, scaledVideo, global.grounding, activeMode.resolution,
                    DeckLink.compatibleRepresentation(depth, ColorSpace.BT709), activeMode.scan, frameIdx
                ) { it }
            }
            SwingUtilities.invokeLater {
                deckLinkFrameSource?.close()
                deckLinkFrameSource = newDeckLinkFrameSource
                displayFrameNowDeckLink()
                refreshPlayback(doRestartSchedulers)
            }
        }
    }

    private fun refreshPlayback(restartSchedulers: Boolean) {
        if (restartSchedulers) {
            // Cancel the play task. We would like to keep the AWT frame buffer open while we are still playing,
            // thus wait for the play task to terminate if it's currently running.
            playTask?.cancel(false)
            try {
                playTask?.get()
            } catch (_: CancellationException) {
                // This is the expected outcome.
            }
        }
        val fps = drawnProject?.run { project.styling.global.fps }
        if (playRate == 0 || fps == null) {
            playTask = null
            activeDeckLink?.stopScheduledPlayback()
            // It is a bit unpredictable where DeckLink playback actually stops, and even when telling it to stop at a
            // certain frame, we've observed that to not always be reliable. So instead, we immediately display the
            // current frame once playback has stopped, which might sometimes cause a little jump, but at least the
            // displayed final frame is consistent with frameIdx.
            displayFrameNowDeckLink()
            awtFrameBuffer?.close()
            awtFrameBuffer = null
            deckLinkFrameBuffer?.close()
            deckLinkFrameBuffer = null
        } else {
            val frameStep = playRate.sign * (1 shl min(6, abs(playRate) - 1))
            val firstFrameIdx = frameIdx + frameStep
            val oldAWTFrameBuffer = awtFrameBuffer
            val oldDeckLinkFrameBuffer = deckLinkFrameBuffer
            awtFrameBuffer = awtFrameSource?.let { FrameBuffer(it, firstFrameIdx, frameStep, 1) }
            deckLinkFrameBuffer = deckLinkFrameSource?.let {
                val deckLinkFPS = (activeDeckLinkMode ?: return@let null).fps
                val frameStepNum = frameStep * fps.numerator * deckLinkFPS.denominator
                val frameStepDen = fps.denominator * deckLinkFPS.numerator
                FrameBuffer(it, firstFrameIdx, frameStepNum, frameStepDen)
            }
            oldAWTFrameBuffer?.close()
            oldDeckLinkFrameBuffer?.close()
            if (restartSchedulers) {
                val viewScaling = this.viewScaling
                playTask = executor.scheduleAtFixedRate(throwableAwareTask {
                    awtFrameBuffer?.nextOrSkip()?.let { for (view in views) view.setVideoFrame(it, viewScaling, false) }
                    SwingUtilities.invokeLater {
                        val unboundedFrameIdx = frameIdx + frameStep
                        frameIdx = unboundedFrameIdx
                        if (frameIdx == 0 || frameIdx == numFrames - 1) {
                            this.playRate = 0
                            // If the play rate is > 1 or < -1, the last valid frameIdx is likely not 0 or numFrames-1,
                            // and thus the last displayed frame is not the first or last one.
                            // However, the frameIdx variable and thus also the UI playhead run all the way to the end.
                            // To resolve this, we explicitly display the first or last frame of the video.
                            if (frameIdx != unboundedFrameIdx) {
                                displayFrameNowAWT(false)
                                displayFrameNowDeckLink()
                            }
                        }
                    }
                }, 0L, 1_000_000_000L * fps.denominator / fps.numerator, TimeUnit.NANOSECONDS)
                if (deckLinkFrameBuffer != null)
                    activeDeckLink?.startScheduledPlayback { deckLinkFrameBuffer?.nextOrSkip() }
            }
        }
    }

    private fun displayFrameNowAWT(clear: Boolean) {
        if (playRate != 0)
            return
        val frameIdx = this.frameIdx
        val awtFrameSource = this.awtFrameSource ?: return
        val viewScaling = this.viewScaling
        displayNowAWTJobSlot.submit {
            awtFrameSource.materializeFrame(frameIdx)?.let {
                for (view in views) view.setVideoFrame(it, viewScaling, clear)
            }
        }
    }

    private fun displayFrameNowDeckLink() {
        val frameIdx = this.frameIdx
        val deckLinkFrameSource = this.deckLinkFrameSource ?: return
        val deckLink = this.activeDeckLink ?: return
        displayNowDeckLinkJobSlot.submit { deckLinkFrameSource.materializeFrame(frameIdx)?.let(deckLink::displayNow) }
    }

    private fun formatTimecode(frames: Int): String {
        val global = (drawnProject ?: return "").project.styling.global
        return formatTimecode(global.fps, global.timecodeFormat, frames)
    }

    private val globalAndVideo: Pair<Global, DeferredVideo>?
        get() {
            val drawnProject = this.drawnProject ?: return null
            return Pair(
                drawnProject.project.styling.global,
                drawnProject.drawnCredits.find { it.credits.spreadsheetName == spreadsheetName }?.video ?: return null
            )
        }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun registerView(view: PlaybackViewComms) {
        views += view
    }

    override fun updateProject(drawnProject: DrawnProject) {
        val restartSchedulers =
            drawnProject.project.styling.global.fps != this.drawnProject?.run { project.styling.global.fps }
        this.drawnProject = drawnProject
        // Just to be sure, filter out spreadsheets which have 0 runtime.
        val avail = drawnProject.drawnCredits.filter { it.video.numFrames > 0 }.map { it.credits.spreadsheetName }
        if (spreadsheetName !in avail)
            spreadsheetName = avail.firstOrNull()
        for (view in views) {
            view.setSpreadsheetNames(avail)
            spreadsheetName?.let(view::setSelectedSpreadsheetName)
        }
        if (avail.isEmpty()) {
            playRate = 0
            numFrames = 1
            frameIdx = 0
            for (view in views) {
                view.setNumFrames(1, "\u2014")
                view.setPlayheadPosition(0, "\u2014")
                view.setVideoFrame(null, 1.0, true)
            }
        } else
            numFrames = globalAndVideo!!.second.numFrames
        setupAWT(restartSchedulers)
        setupDeckLink(restartSchedulers)
    }

    override fun closeProject() {
        playRate = 0
        awtFrameSource?.close()
        deckLinkFrameSource?.close()
        DeckLink.removeListener(deckLinkListener)
        activeDeckLink?.displayBlack()
        activeDeckLink?.release()
        executor.shutdown()
        DECK_LINK_ID_PREFERENCE.set(selectedDeckLink?.id.toString())
        DECK_LINK_MODE_PREFERENCE.set(selectedDeckLinkMode?.name.toString())
        DECK_LINK_DEPTH_PREFERENCE.set(selectedDeckLinkDepth.bits)
        DECK_LINK_CONNECTED_PREFERENCE.set(deckLinkConnected)
    }

    override fun setDialogVisibility(visible: Boolean) {
        if (dialogVisible == visible)
            return
        dialogVisible = visible
        projectCtrl.setDialogVisible(ProjectDialogType.VIDEO, visible)
        setupAWT(false)
        stopPlayingIfNecessary()
    }

    override fun setSelectedDeckLink(deckLink: DeckLink) {
        if (selectedDeckLink == deckLink)
            return
        selectedDeckLink = deckLink
        for (view in views) {
            view.setSelectedDeckLink(deckLink)
            view.setDeckLinkModes(deckLink.modes)
        }
        setupDeckLink(false)
    }

    override fun setSelectedDeckLinkMode(mode: DeckLink.Mode) {
        if (selectedDeckLinkMode == mode)
            return
        selectedDeckLinkMode = mode
        for (view in views) {
            view.setSelectedDeckLinkMode(mode)
            view.setDeckLinkDepths(mode.depths)
        }
        setupDeckLink(false)
    }

    override fun setSelectedDeckLinkDepth(depth: DeckLink.Depth) {
        if (selectedDeckLinkDepth == depth)
            return
        selectedDeckLinkDepth = depth
        for (view in views) view.setSelectedDeckLinkDepth(depth)
        setupDeckLink(false)
    }

    override fun setDeckLinkConnected(connected: Boolean) {
        if (deckLinkConnected == connected)
            return
        deckLinkConnected = connected
        for (view in views) view.setDeckLinkConnected(connected)
        setupDeckLink(false)
        stopPlayingIfNecessary()
    }

    override fun toggleDeckLinkConnected() = setDeckLinkConnected(!deckLinkConnected)

    override fun setSelectedSpreadsheetName(spreadsheetName: String) {
        if (this.spreadsheetName == spreadsheetName)
            return
        playRate = 0
        this.spreadsheetName = spreadsheetName
        for (view in views) view.setSelectedSpreadsheetName(spreadsheetName)
        setupAWT(false)
        setupDeckLink(false)
    }

    override fun scrub(frameIdx: Int) {
        if (!dialogVisible && selectedDeckLink == null)
            return
        playRate = 0
        this.frameIdx = frameIdx
        displayFrameNowAWT(false)
        displayFrameNowDeckLink()
    }

    override fun scrubRelativeFrames(frames: Int) {
        scrub(frameIdx + frames)
    }

    override fun scrubRelativeSeconds(seconds: Int) {
        scrub(frameIdx + seconds * (drawnProject ?: return).project.styling.global.fps.frac.roundToInt())
    }

    override fun rewind() {
        if (playRate > 0) playRate = -1 else playRate--
    }

    override fun pause() {
        playRate = 0
    }

    override fun play() {
        if (playRate < 0) playRate = 1 else playRate++
    }

    override fun togglePlay() {
        playRate = if (playRate == 0) 1 else 0
    }

    override fun setVideoCanvasSize(size: Dimension, gCfg: GraphicsConfiguration) {
        videoCanvasSize = size
        videoCanvasGCfg = gCfg
        if (!actualSize)
            setupAWT(false)
    }

    override fun setActualSize(actualSize: Boolean) {
        if (!dialogVisible)
            return
        this.actualSize = actualSize
        setupAWT(false)
    }

    override fun toggleActualSize() = setActualSize(!actualSize)

    override fun setFullScreen(fullScreen: Boolean) {
        for (view in views) view.setFullScreen(fullScreen)
    }

    override fun toggleFullScreen() {
        for (view in views) view.toggleFullScreen()
    }


    /** This class is thread-safe. */
    private class FrameSource<F : Any>(
        materializationCache: DeferredImage.CanvasMaterializationCache,
        video: DeferredVideo,
        grounding: Color4f,
        private val resolution: Resolution,
        representation: Bitmap.Representation,
        scan: Bitmap.Scan,
        preloadFrameIdx: Int,
        private val frameConverter: (Bitmap) -> F
    ) {

        private val videoBackendLock = ReentrantLock()
        private var videoBackend: DeferredVideo.BitmapBackend?

        init {
            val content = if (scan == Bitmap.Scan.PROGRESSIVE) Bitmap.Content.PROGRESSIVE_FRAME else
                Bitmap.Content.INTERLEAVED_FIELDS
            val spec = Bitmap.Spec(video.resolution, representation, scan, content)
            videoBackend = DeferredVideo.BitmapBackend(
                video, listOf(STATIC), listOf(TAPES), grounding, spec,
                cache = materializationCache, randomAccessDraftMode = true
            )
            // Simulate materializing the currently selected frame while the FrameBuffer is being constructed in a
            // background thread. As expensive operations are cached, the subsequent materialization of that frame in
            // another thread will be very fast.
            videoBackend?.preloadFrame(preloadFrameIdx)
        }

        fun materializeFrame(frameIdx: Int): F? {
            val baseBitmap = videoBackendLock.withLock { videoBackend?.materializeFrame(frameIdx) } ?: return null
            if (baseBitmap.spec.resolution == resolution)
                return frameConverter(baseBitmap)
            val paddedBitmap = Bitmap.allocate(Bitmap.Spec(resolution, baseBitmap.spec.representation)).zero()
            val (w, h) = resolution
            val (bw, bh) = baseBitmap.spec.resolution
            paddedBitmap.blitLeniently(baseBitmap, 0, 0, bw, bh, (w - bw) / 2, (h - bh) / 2)
            baseBitmap.close()
            return frameConverter(paddedBitmap)
        }

        fun close() {
            videoBackendLock.withLock {
                videoBackend?.close()
                videoBackend = null
            }
        }

    }


    private class FrameBuffer<F : Any>(
        source: FrameSource<F>,
        firstFrameIdx: Int,
        frameStepNum: Int,
        frameStepDen: Int
    ) {

        private val queue = arrayOfNulls<Any>(QUEUE_SIZE)
        @Volatile private var stockQueueThread: Thread? = null
        @Volatile private var nextTakePos = 0
        @Volatile private var lastPutPos = -1

        private val stockQueueTask = GLOBAL_THREAD_POOL.submit(throwableAwareTask {
            stockQueueThread = Thread.currentThread()
            while (!Thread.interrupted()) {
                // If rendering has fallen behind playback, skip the frames that are no longer needed.
                val putPos = max(lastPutPos + 1, nextTakePos)
                // If rendering has run too far ahead and the queue is full, wait for playback to consume one frame.
                while (putPos - nextTakePos >= QUEUE_SIZE) {
                    LockSupport.park(this)
                    if (Thread.interrupted())
                        break
                }
                try {
                    val newFrame = source.materializeFrame(firstFrameIdx + putPos * frameStepNum / frameStepDen)
                    // If obtainFrame() returns null, we have reached the video's boundary, so we can stop.
                    // We immediately exit the task instead of just breaking the loop as that would call stop().
                        ?: return@throwableAwareTask
                    // If rendering took too long and playback has already moved beyond the new frame, discard it.
                    if (putPos < nextTakePos)
                        closeFrame(newFrame)
                    else {
                        val oldFrame = swapInQueue(putPos, newFrame)
                        lastPutPos = putPos
                        closeFrame(oldFrame)
                    }
                } catch (_: InterruptedException) {
                    // Catch this just in case something in obtainFrame() or closeFrame() triggers it.
                    break
                }
            }
            // If the task has been interrupted, discard all queued frames.
            close()
        })

        fun close() {
            stockQueueTask.cancel(true)
            for (i in queue.indices)
                closeFrame(swapInQueue(i, null))
        }

        /** This method returns extremely quickly, because it's used in the real-time playback loop. */
        fun nextOrSkip(): F? {
            val takePos = nextTakePos
            val frame = if (takePos > lastPutPos) null else swapInQueue(takePos, null)
            nextTakePos++
            LockSupport.unpark(stockQueueThread)
            return frame
        }

        @Suppress("UNCHECKED_CAST")
        private fun swapInQueue(pos: Int, newFrame: F?): F? =
            AA.getAndSet(queue, pos and (QUEUE_SIZE - 1), newFrame) as F?

        private fun closeFrame(frame: F?) {
            if (frame is AutoCloseable) frame.close()
        }

        companion object {
            private const val QUEUE_SIZE = 16  // Must be a power of 2!
            private val AA = MethodHandles.arrayElementVarHandle(Array::class.java).withInvokeExactBehavior()
        }

    }

}
