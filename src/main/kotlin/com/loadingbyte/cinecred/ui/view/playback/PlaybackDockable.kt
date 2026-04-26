package com.loadingbyte.cinecred.ui.view.playback

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.Shortcut.PLAYBACK_TOGGLE_ACTUAL_SIZE
import com.loadingbyte.cinecred.ui.Shortcut.PLAYBACK_TOGGLE_FULL_SCREEN
import com.loadingbyte.cinecred.ui.comms.DockableId
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import kotlin.math.roundToInt


class PlaybackDockable(playbackCtrl: PlaybackCtrlComms) :
    DockingFrame.Dockable(
        dockableId = DockableId.PLAYBACK.name,
        title = l10n("ui.video.title"),
        icon = DockableId.PLAYBACK.icon
    ),
    PlaybackViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedControlsPanel get() = controlsPanel
    @Deprecated("ENCAPSULATION LEAK") val leakedVideoCanvas: Canvas get() = videoCanvas
    // =========================================

    private val playbackPanel = JPanel()
    private val fullScreenDialog = JDialog().apply { isUndecorated = true }

    private val controlsPanel = PlaybackControlsPanel(playbackCtrl)
    private val videoCanvas = VideoCanvas()

    private val actualSizeButton = newToolbarToggleButton(
        X_1_TO_1_ICON, l10n("ui.video.actualSize"), PLAYBACK_TOGGLE_ACTUAL_SIZE, listener = playbackCtrl::setActualSize
    )

    // Do not add the full-screen button on macOS, as full screen is supported though native window buttons there.
    // Additionally, our shortcut F11 is already occupied by "show desktop", and when using Java's full-screen API,
    // the dock (presumably) blocks mouse click events in the lower part of the window, where our buttons are.
    private val fullScreenButton = if (SystemInfo.isMacOS) null else
        newToolbarToggleButton(
            SCREEN_ICON, l10n("ui.video.fullScreen"), PLAYBACK_TOGGLE_FULL_SCREEN
        ) { isSelected ->
            if (isSelected) {
                remove(playbackPanel)
                fullScreenDialog.contentPane.add(playbackPanel)
                fullScreenDialog.isVisible = true
                graphicsConfiguration.device.fullScreenWindow = fullScreenDialog
            } else {
                fullScreenDialog.graphicsConfiguration.device.fullScreenWindow = null
                fullScreenDialog.isVisible = false
                fullScreenDialog.contentPane.remove(playbackPanel)
                add(playbackPanel, BorderLayout.CENTER)
                revalidate()
            }
        }

    init {
        playbackCtrl.registerView(this)

        val stutterLabel = JLabel(l10n("ui.video.stutter"), WARN_ICON, JLabel.LEADING).apply { toolTipText = text }

        playbackPanel.apply {
            layout = MigLayout(
                "insets 0",
                "[]" + (if (fullScreenButton != null) "0[]" else "") + "rel[]unrel[]11[]10[]",
                "[]0[]8[]8"
            )
            add(videoCanvas, "span, grow, pushy")
            add(JSeparator(), "newline, span, growx, hmin pref")
            add(actualSizeButton, "newline, gapleft 8")
            fullScreenButton?.let(::add)
            add(JSeparator(JSeparator.VERTICAL), "growy, wmin pref")
            add(controlsPanel, "growx, pushx")
            add(JSeparator(JSeparator.VERTICAL), "growy, wmin pref")
            add(stutterLabel, "wmin 0, gapright 14")
        }

        playbackPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                playbackCtrl.setVideoCanvasSize(videoCanvas.size, videoCanvas.graphicsConfiguration)
                // Without this explicit revalidation, the stutter label's width oscillates when shrinking the window.
                // The reason seems to be the heavyweight Canvas.
                stutterLabel.revalidate()
            }
        })

        layout = BorderLayout()
        add(playbackPanel, BorderLayout.CENTER)
    }

    override fun getMinimumSize(): Dimension =
        if (isMinimumSizeSet) super.getMinimumSize() else Dimension(super.getMinimumSize().width, 200)


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun isFullScreenWindow(window: Window) = window == fullScreenDialog
    override fun disposeResources() = fullScreenDialog.dispose()

    override fun setActualSize(actualSize: Boolean) {
        actualSizeButton.isSelected = actualSize
    }

    override fun setFullScreen(fullScreen: Boolean): Boolean {
        val changing = fullScreenButton != null && fullScreenButton.isSelected != fullScreen
        fullScreenButton?.isSelected = fullScreen
        return changing
    }

    override fun toggleFullScreen() {
        fullScreenButton?.let { it.isSelected = !it.isSelected }
    }

    override fun setVideoFrame(videoFrame: BufferedImage?, scaling: Double) =
        videoCanvas.setVideoFrame(videoFrame, scaling)


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
        @Volatile private var prevPaintedArea = Rectangle()

        init {
            // Without an explicitly set minimum size, a Canvas' default minimum size its current size, which means that
            // once it has grown, a Canvas could never shrink again.
            minimumSize = Dimension(0, 0)
        }

        fun setVideoFrame(videoFrame: BufferedImage?, scaling: Double) {
            this.videoFrame = videoFrame
            this.scaling = scaling
            val g = graphics ?: return
            paint(g)
            g.dispose()
            Toolkit.getDefaultToolkit().sync()
        }

        override fun paint(g: Graphics) {
            val videoFrame = this.videoFrame
            if (videoFrame == null) {
                g.clearRect(0, 0, width, height)
                prevPaintedArea = Rectangle()
            } else {
                val x = ((width - videoFrame.width * scaling) / 2.0).roundToInt()
                val y = ((height - videoFrame.height * scaling) / 2.0).roundToInt()
                val w = (videoFrame.width * scaling).roundToInt()
                val h = (videoFrame.height * scaling).roundToInt()
                val paintedArea = Rectangle(x, y, w, h)
                if (!paintedArea.contains(prevPaintedArea))
                    g.clearRect(0, 0, width, height)
                prevPaintedArea = paintedArea
                g.drawImage(videoFrame, x, y, w, h, null)
            }
        }

    }

}
