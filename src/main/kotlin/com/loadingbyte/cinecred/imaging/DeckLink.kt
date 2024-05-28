package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.natives.decklinkcapi.decklinkcapi_h.*
import com.loadingbyte.cinecred.natives.decklinkcapi.deviceNotificationCallback_t
import com.loadingbyte.cinecred.natives.decklinkcapi.scheduledFrameCompletionCallback_t
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_X2RGB10BE
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class DeckLink(
    private val deviceHandle: MemorySegment,
    private val outputHandle: MemorySegment,
    val id: String?,
    val name: String,
    val modes: List<Mode>
) {

    private var mode: Mode? = null
    private var lastBitmap: Bitmap? = null
    private var schedId = 0
    private var schedNextFn: (() -> Bitmap?)? = null
    private var schedFrameCtr = 0

    fun supports(mode: Mode): Boolean =
        modes.any { it === mode }

    fun acquire(mode: Mode): Boolean {
        require(supports(mode)) { "Mode object stems from another device." }
        return EXECUTOR.submit(throwableAwareValuedTask {
            val success = this.mode == null && IDeckLinkOutput_EnableVideoOutput(outputHandle, mode.code)
            if (success)
                this.mode = mode
            success
        }).get()
    }

    fun release() {
        EXECUTOR.submit(throwableAwareTask {
            if (mode != null) {
                mode = null
                stopScheduledPlaybackInThisThread()
                if (!IDeckLinkOutput_DisableVideoOutput(outputHandle))
                    error("Failed to disable video output.")
                lastBitmap?.close()
                lastBitmap = null
            }
        })
    }

    /**
     * The [bitmap] will be closed by this class, and must not be closed by anyone else.
     * If playback is currently running, this method has no effect.
     */
    fun displayNow(bitmap: Bitmap) {
        EXECUTOR.submit(throwableAwareTask {
            displayNowInThisThread(bitmap)
        })
    }

    /** If playback is currently running, this method has no effect. */
    fun displayBlack() {
        EXECUTOR.submit(throwableAwareTask {
            createBlackBitmap()?.let(::displayNowInThisThread)
        })
    }

    private fun displayNowInThisThread(bitmap: Bitmap) {
        if (mode == null || schedNextFn != null)
            return
        lastBitmap?.close()
        lastBitmap = bitmap.view()
        bitmap.requireNotClosed {
            // If the mode has changed in the meantime, silently ignore the call.
            val frameHandle = createFrame(bitmap) ?: return@requireNotClosed
            if (!IDeckLinkOutput_DisplayVideoFrameSync(outputHandle, frameHandle))
                error("Failed to synchronously display a frame.")
            IUnknown_Release(frameHandle)
        }
        bitmap.close()
    }

    /**
     * The [next] function supplies either the next frame (which will be later closed by this class) or null to not
     * display a frame, but instead keep the previously displayed frame visible. The function must return quickly!
     */
    fun startScheduledPlayback(next: () -> Bitmap?) {
        EXECUTOR.submit(throwableAwareTask {
            if (mode == null)
                return@throwableAwareTask
            stopScheduledPlaybackInThisThread()
            if (!IDeckLinkOutput_SetScheduledFrameCompletionCallback(outputHandle, VO_CB_HANDLE)) {
                error("Failed to set scheduled frame completion callback.")
                return@throwableAwareTask
            }
            val timeScale = mode!!.fps.numerator.toLong()
            if (!IDeckLinkOutput_StartScheduledPlayback(outputHandle, 0, timeScale, 1.0)) {
                error("Failed to start scheduled playback.")
                return@throwableAwareTask
            }
            schedNextFn = next
            schedFrameCtr = 0
            // According to a DeckLink presentation, old cards require 3 frames of preroll while newer ones only require
            // 2, so by scheduling 3, we're on the safe side.
            repeat(3) { scheduleNextFrame() }
        })
    }

    private fun scheduleNextFrame() {
        val bitmap = schedNextFn!!()?.also { lastBitmap?.close() } ?: lastBitmap ?: createBlackBitmap() ?: return
        lastBitmap = bitmap.view()
        // If the mode has changed in the meantime, silently ignore the call.
        val frameHandle = createFrame(bitmap) ?: return
        val timeScale = mode!!.fps.numerator.toLong()
        val dur = mode!!.fps.denominator.toLong()
        if (!IDeckLinkOutput_ScheduleVideoFrame(outputHandle, frameHandle, schedFrameCtr++ * dur, dur, timeScale)) {
            error("Failed to schedule next frame.")
            IUnknown_Release(frameHandle)
            bitmap.close()
        } else
            SCHEDULED_FRAME_LOOKUP[frameHandle] = Triple(this, schedId, bitmap)
    }

    fun stopScheduledPlayback() {
        EXECUTOR.submit(throwableAwareTask { stopScheduledPlaybackInThisThread() })
    }

    private fun stopScheduledPlaybackInThisThread() {
        schedNextFn ?: return
        schedId++
        schedNextFn = null
        if (!IDeckLinkOutput_StopScheduledPlayback(outputHandle, 0, 0))
            error("Failed to stop scheduled playback.")
    }

    private fun createFrame(bitmap: Bitmap): MemorySegment? {
        val (res, rep) = bitmap.spec
        if (res != mode?.resolution || rep.colorSpace == null)
            return null
        val depth = Depth.entries.find { rep == compatibleRepresentation(it, rep.colorSpace) } ?: return null
        val (w, h) = res
        val eotf = 0
        val cs = Colorspace_Rec709()
        val chr = ColorSpace.Primaries.BT709.chromaticities!!
        return IDeckLinkVideoFrame_Create(
            w, h, bitmap.linesize(0), depth.code, eotf,
            chr.rx.toDouble(), chr.ry.toDouble(), chr.gx.toDouble(), chr.gy.toDouble(),
            chr.bx.toDouble(), chr.by.toDouble(), chr.wx.toDouble(), chr.wy.toDouble(),
            1000.0, 0.0001, 1000.0, 50.0, cs,
            bitmap.memorySegment(0)
        )
    }

    private fun createBlackBitmap() = mode?.let { mode ->
        Bitmap.allocate(Bitmap.Spec(mode.resolution, compatibleRepresentation(mode.depths[0], ColorSpace.BT709))).zero()
    }

    private fun error(msg: String) = LOGGER.error("DeckLink '$name': $msg")


    companion object {

        fun compatibleRepresentation(depth: Depth, colorSpace: ColorSpace) = Bitmap.Representation(
            depth.pixelFormat, depth.range, colorSpace,
            if (depth.pixelFormat.hasAlpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
        )

        // Causes the static initializer to be executed, which registers the notification callback.
        fun preload() {}

        fun addListener(listener: (List<DeckLink>) -> Unit) {
            notificationLock.withLock {
                listeners += listener
                listener(deckLinks.toMutableList())
            }
        }

        fun removeListener(listener: (List<DeckLink>) -> Unit) {
            notificationLock.withLock { listeners -= listener }
        }

        // Some DeckLink documentation mentioned issues when calling the DeckLink API from multiple threads. So to be on
        // the safe side, we always call it from a dedicated DeckLink thread. This also simplifies the bookkeeping done
        // by instances of this class.
        private val EXECUTOR = Executors.newSingleThreadExecutor { Thread(it, "DeckLink").apply { isDaemon = true } }

        private val SCHEDULED_FRAME_LOOKUP = ConcurrentHashMap<MemorySegment, Triple<DeckLink, Int, Bitmap>>()
        private val VO_CB_HANDLE = IDeckLinkVideoOutputCallback_Create(
            scheduledFrameCompletionCallback_t.allocate({ frameHandle, _ ->
                try {
                    IUnknown_Release(frameHandle)
                    val (dekLink, schedId, bitmap) = SCHEDULED_FRAME_LOOKUP.remove(frameHandle)!!
                    bitmap.close()
                    EXECUTOR.submit(throwableAwareTask { if (schedId == dekLink.schedId) dekLink.scheduleNextFrame() })
                } catch (t: Throwable) {
                    // We have to catch all exceptions because if one escapes, a segfault happens.
                    runCatching { LOGGER.error("Unexpected exception in DeckLink frame completion callback.", t) }
                }
            }, Arena.global())
        )

        private val deckLinks = mutableListOf<DeckLink>()
        private val listeners = mutableListOf<(List<DeckLink>) -> Unit>()
        private val notificationLock = ReentrantLock()

        init {
            initDeckLinkAPI()

            val discovery = IDeckLinkDiscovery_Create()
            if (discovery != NULL)
                IDeckLinkDiscovery_InstallDeviceNotifications(
                    discovery,
                    IDeckLinkDeviceNotificationCallback_Create(
                        deviceNotificationCallback_t.allocate({ deviceHandle ->
                            try {
                                deviceArrived(deviceHandle)
                            } catch (t: Throwable) {
                                // We have to catch all exceptions because if one escapes, a segfault happens.
                                runCatching { LOGGER.error("Failed to handle the arrival of a DeckLink device.", t) }
                            }
                        }, Arena.global()),
                        deviceNotificationCallback_t.allocate({ deviceHandle ->
                            try {
                                deviceRemoved(deviceHandle)
                            } catch (t: Throwable) {
                                // We have to catch all exceptions because if one escapes, a segfault happens.
                                runCatching { LOGGER.error("Failed to handle the removal of a DeckLink device.", t) }
                            }
                        }, Arena.global())
                    )
                )
        }

        private fun deviceArrived(deviceHandle: MemorySegment) {
            val attributesHandle = IDeckLink_QueryIDeckLinkProfileAttributes(deviceHandle)
            if (attributesHandle == NULL)
                return
            if (!IDeckLinkProfileAttributes_IsActive(attributesHandle) ||
                !IDeckLinkProfileAttributes_SupportsPlayback(attributesHandle)
            ) return
            val id = getDeviceId(attributesHandle)
            IUnknown_Release(attributesHandle)
            val outputHandle = IDeckLink_QueryIDeckLinkOutput(deviceHandle)
            if (outputHandle == NULL)
                return
            val name = getDeviceName(deviceHandle)
            val modes = getDeviceModes(outputHandle)
            if (modes.isEmpty())
                return
            IUnknown_AddRef(deviceHandle)
            notificationLock.withLock {
                deckLinks += DeckLink(deviceHandle, outputHandle, id, name, modes)
                val deckLinksCopy = deckLinks.toMutableList()
                for (listener in listeners)
                    listener(deckLinksCopy)
            }
        }

        private fun deviceRemoved(deviceHandle: MemorySegment) {
            notificationLock.withLock {
                if (deckLinks.removeIf { it.deviceHandle == deviceHandle }) {
                    IUnknown_Release(deviceHandle)
                    val deckLinksCopy = deckLinks.toMutableList()
                    for (listener in listeners)
                        listener(deckLinksCopy)
                }
            }
        }

        private fun getDeviceId(attributesHandle: MemorySegment): String? = Arena.ofConfined().use { arena ->
            val cStr = arena.allocate(1024)
            if (IDeckLinkProfileAttributes_GetDeviceHandle(attributesHandle, cStr, cStr.byteSize()))
                cStr.getUtf8String(0) else null
        }

        private fun getDeviceName(deviceHandle: MemorySegment): String = Arena.ofConfined().use { arena ->
            val cStr = arena.allocate(1024)
            if (IDeckLink_GetDisplayName(deviceHandle, cStr, cStr.byteSize())) cStr.getUtf8String(0) else "???"
        }

        private fun getDeviceModes(outputHandle: MemorySegment): List<Mode> {
            val modeIterHandle = IDeckLinkOutput_GetDisplayModeIterator(outputHandle)
            if (modeIterHandle == NULL)
                return emptyList()
            val modes = mutableListOf<Mode>()
            while (true) {
                val modeHandle = IDeckLinkDisplayModeIterator_Next(modeIterHandle)
                if (modeHandle == NULL)
                    break
                val name = getModeName(modeHandle)
                val code = IDeckLinkDisplayMode_GetDisplayMode(modeHandle)
                val width = IDeckLinkDisplayMode_GetWidth(modeHandle)
                val height = IDeckLinkDisplayMode_GetHeight(modeHandle)
                val fpsFused = IDeckLinkDisplayMode_GetFrameRate(modeHandle)
                val fpsNumerator = fpsFused.toInt()
                val fpsDenominator = (fpsFused ushr 32).toInt()
                val scan = when (IDeckLinkDisplayMode_GetFieldDominance(modeHandle)) {
                    FieldDominance_ProgressiveFrame(), FieldDominance_ProgressiveSegmentedFrame() ->
                        Bitmap.Scan.PROGRESSIVE
                    FieldDominance_UpperFieldFirst() -> Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                    FieldDominance_LowerFieldFirst() -> Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST
                    else -> null
                }
                // The legacy BT.601 modes (PAL and NTSC) expect the Colorspace_Rec601 tag to be set, or else they raise
                // an error. We don't support the various corresponding BT.601 primaries and probably never will, so to
                // prevent confused users, just remove the BT.601 modes from the list.
                val bt601 = IDeckLinkDisplayMode_GetFlags(modeHandle) and DisplayModeFlag_ColorspaceRec601() != 0
                val depths = Depth.entries.filter { IDeckLinkOutput_DoesSupportVideoMode(outputHandle, code, it.code) }
                if (name != null && width > 0 && height > 0 && fpsNumerator > 0 && fpsDenominator > 0 && scan != null &&
                    !bt601 && depths.isNotEmpty()
                ) modes += Mode(name, code, Resolution(width, height), FPS(fpsNumerator, fpsDenominator), scan, depths)
                IUnknown_Release(modeHandle)
            }
            modes.sortWith(Comparator.comparingInt<Mode> { it.resolution.widthPx }
                .thenComparing(Mode::scan)
                .thenComparingDouble { it.fps.frac })
            return modes
        }

        private fun getModeName(modeHandle: MemorySegment): String? = Arena.ofConfined().use { arena ->
            val cStr = arena.allocate(1024)
            if (IDeckLinkDisplayMode_GetName(modeHandle, cStr, cStr.byteSize())) cStr.getUtf8String(0) else null
        }

    }


    data class Mode(
        val name: String,
        val code: Int,
        val resolution: Resolution,
        val fps: FPS,
        val scan: Bitmap.Scan,
        val depths: List<Depth>
    )


    enum class Depth(val bits: Int, val code: Int, val pixelFormat: Bitmap.PixelFormat, val range: Bitmap.Range) {
        D8(8, PixelFormat_8BitBGRA(), Bitmap.PixelFormat.of(AV_PIX_FMT_BGRA), Bitmap.Range.FULL),
        D10(10, PixelFormat_10BitRGB(), Bitmap.PixelFormat.of(AV_PIX_FMT_X2RGB10BE), Bitmap.Range.LIMITED)
    }

}
