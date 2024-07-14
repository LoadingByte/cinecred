package com.loadingbyte.cinecred.ui.view.playback

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.helper.SCREEN_ICON
import com.loadingbyte.cinecred.ui.helper.X_1_TO_1_ICON
import com.loadingbyte.cinecred.ui.helper.newToolbarToggleButton
import net.miginfocom.swing.MigLayout
import java.awt.Canvas
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent.*
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JSeparator
import kotlin.math.roundToInt


class PlaybackPanel(playbackCtrl: PlaybackCtrlComms, playbackDialog: JDialog) : JPanel(), PlaybackViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedControlsPanel get() = controlsPanel
    @Deprecated("ENCAPSULATION LEAK") val leakedVideoCanvas: Canvas get() = videoCanvas
    // =========================================

    private val controlsPanel = PlaybackControlsPanel(playbackCtrl)
    private val videoCanvas = VideoCanvas()

    private val actualSizeButton = newToolbarToggleButton(
        X_1_TO_1_ICON, l10n("ui.video.actualSize"), VK_1, CTRL_DOWN_MASK, listener = playbackCtrl::setActualSize
    )

    // Do not add the full-screen button on macOS, as full screen is supported though native window buttons there.
    // Additionally, our shortcut F11 is already occupied by "show desktop", and when using Java's full-screen API,
    // the dock (presumably) blocks mouse click events in the lower part of the window, where our buttons are.
    private val fullScreenButton = if (SystemInfo.isMacOS) null else
        newToolbarToggleButton(
            SCREEN_ICON, l10n("ui.video.fullScreen"), VK_F11, 0
        ) { isSelected -> graphicsConfiguration.device.fullScreenWindow = if (isSelected) playbackDialog else null }

    init {
        playbackCtrl.registerView(this)

        layout = MigLayout(
            "insets 0",
            "[]" + (if (fullScreenButton != null) "0[]" else "") + "rel[]unrel[]",
            "[]0[]8[]8"
        )
        add(videoCanvas, "span, grow, pushy")
        add(JSeparator(), "newline, span, growx, shrink 0 0")
        add(actualSizeButton, "newline, gapleft 8")
        fullScreenButton?.let(::add)
        add(JSeparator(JSeparator.VERTICAL), "growy, shrink 0 0")
        add(controlsPanel, "growx, pushx, gapright 14")

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                playbackCtrl.setVideoCanvasSize(videoCanvas.size, videoCanvas.graphicsConfiguration)
            }
        })
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setActualSize(actualSize: Boolean) {
        actualSizeButton.isSelected = actualSize
    }

    override fun setFullScreen(fullScreen: Boolean) {
        fullScreenButton?.isSelected = fullScreen
    }

    override fun toggleFullScreen() {
        fullScreenButton?.let { it.isSelected = !it.isSelected }
    }

    override fun setVideoFrame(videoFrame: BufferedImage?, scaling: Double, clear: Boolean) =
        videoCanvas.setVideoFrame(videoFrame, scaling, clear)


    /**
     * We chose a heavyweight AWT [Canvas] instead of a lightweight Swing JPanel or similar for one reason:
     * A heavyweight component has its own drawing surface, and when calling [getGraphics] to immediately get a drawing
     * handle from any thread (which is needed for real-time video playback), we get a handle for only that drawing
     * surface, instead of the whole window's drawing surface. In the latter case, we would interfere with Swing's
     * drawing system, and judging from their code, it seems like the call to [getGraphics] could even inadvertently
     * disable double-buffering for the main Swing GUI.
     */
    private class VideoCanvas : Canvas() {

        @Volatile private var videoFrame: BufferedImage? = null
        @Volatile private var scaling: Double = 1.0

        init {
            // Without an explicitly set minimum size, a Canvas' default minimum size its current size, which means that
            // once it has grown, a Canvas could never shrink again.
            minimumSize = Dimension(0, 0)
        }

        fun setVideoFrame(videoFrame: BufferedImage?, scaling: Double, clear: Boolean) {
            this.videoFrame = videoFrame
            this.scaling = scaling
            val g = graphics ?: return
            if (clear && videoFrame != null)
                g.clearRect(0, 0, width, height)
            paint(g)
            g.dispose()
            Toolkit.getDefaultToolkit().sync()
        }

        override fun paint(g: Graphics) {
            val videoFrame = this.videoFrame
            if (videoFrame == null)
                g.clearRect(0, 0, width, height)
            else {
                val x = ((width - videoFrame.width * scaling) / 2.0).roundToInt()
                val y = ((height - videoFrame.height * scaling) / 2.0).roundToInt()
                val w = (videoFrame.width * scaling).roundToInt()
                val h = (videoFrame.height * scaling).roundToInt()
                g.drawImage(videoFrame, x, y, w, h, null)
            }
        }

    }

}
