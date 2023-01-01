package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.ui.helper.JobSlot
import com.loadingbyte.cinecred.ui.helper.PAUSE_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import com.loadingbyte.cinecred.ui.helper.WARN_ICON
import net.miginfocom.swing.MigLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.*


class VideoPanel(private val ctrl: ProjectController) : JPanel() {

    private val canvas: JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val video = scaledVideo
            val videoBackend = scaledVideoBackend
            if (video != null && videoBackend != null) {
                g as Graphics2D
                g.translate((width - video.width / systemScaling) / 2.0, 0.0)
                g.scale(1.0 / systemScaling)
                g.clipRect(0, 0, video.width, video.height)
                videoBackend.materializeFrame(g, frameSlider.value)
            }
        }
    }

    private val rewindButton: JToggleButton
    private val pauseButton: JToggleButton
    private val playButton: JToggleButton

    private val frameSlider = JSlider().also { frameSlider ->
        frameSlider.value = 0
        frameSlider.isFocusable = false
        frameSlider.addChangeListener {
            // When the slider is moved, either automatically or by hand, draw the selected frame.
            // Use paintImmediately() because repaint() might postpone the painting, which we do not want.
            canvas.paintImmediately(0, 0, canvas.width, canvas.height)
            // Also adjust the timecode label.
            adjustTimecodeLabel()
        }
    }

    private val timecodeLabel = JLabel().apply {
        putClientProperty(STYLE_CLASS, "monospaced")
    }

    private var drawnProject: DrawnProject? = null

    private val makeVideoBackendJobSlot = JobSlot()
    private var scaledVideo: DeferredVideo? = null
    private var scaledVideoBackend: DeferredVideo.Graphics2DBackend? = null
    private var systemScaling = 1.0

    private var playTimer: Timer? = null
    private var playRate = 0
        set(value) {
            val project = drawnProject?.project
            val playRate = if (project == null) 0 else value
            if (field == playRate)
                return
            field = playRate
            when {
                playRate < 0 -> rewindButton.isSelected = true
                playRate == 0 -> pauseButton.isSelected = true
                playRate > 0 -> playButton.isSelected = true
            }
            playTimer?.stop()
            playTimer = if (playRate == 0) null else {
                val frameSpacing /* ms */ = 1000.0 / (abs(playRate) * project!!.styling.global.fps.frac)
                // We presume that we cannot draw faster than roughly 80 frames per second, and hence increase the step
                // size once that framerate is exceeded.
                val stepSize = ceil(12.0 / frameSpacing).roundToInt()
                Timer((frameSpacing * stepSize).roundToInt()) {
                    if (!ctrl.projectFrame.isVisible) {
                        // If the main frame has been disposed, also stop the timer, because would otherwise keep
                        // generating action events, which keep AWT's EDT thread from stopping and in turn keep the
                        // whole application from closing.
                        playTimer?.stop()
                    } else {
                        frameSlider.value += playRate.sign * stepSize
                        if (frameSlider.value.let { it == 0 || it == frameSlider.maximum })
                            this.playRate = 0
                    }
                }.apply { initialDelay = 0; start() }
            }
        }

    init {
        val rewindIcon = PLAY_ICON.getScaledIcon(-1.0, 1.0)
        val buttonGroup = ButtonGroup()
        fun makeBtn(icon: Icon, tooltip: String, keyCode: Int, listener: () -> Unit, isSelected: Boolean = false) =
            JToggleButton(icon, isSelected).apply {
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
                isFocusable = false
                toolTipText = "$tooltip (${getKeyText(keyCode)})"
                addActionListener { listener() }
                buttonGroup.add(this)
            }
        rewindButton = makeBtn(rewindIcon, l10n("ui.video.rewind"), VK_J, ::rewind)
        pauseButton = makeBtn(PAUSE_ICON, l10n("ui.video.pause"), VK_K, ::pause, isSelected = true)
        playButton = makeBtn(PLAY_ICON, l10n("ui.video.play"), VK_L, ::play)

        layout = MigLayout("insets 12 n n n, gapy 10")
        add(JLabel(l10n("ui.video.warning"), WARN_ICON, JLabel.LEADING), "center")
        add(rewindButton, "newline, split 5, gapx 2")
        add(pauseButton, "gapx 2")
        add(playButton, "gapx 2")
        // If we use % or growx instead of "sp", the slider becomes laggy due to the quickly changing timecode label.
        add(frameSlider, "width 100sp")
        add(timecodeLabel, "gapright 8")
        add(canvas, "newline, grow, push")

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                restartDrawing()
            }
        })
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KEY_PRESSED)
            return false

        val project = drawnProject?.project
        when (event.modifiersEx) {
            0 -> when (event.keyCode) {
                VK_J -> rewind()
                VK_K -> pause()
                VK_L -> play()
                VK_SPACE -> playRate = if (playRate == 0) 1 else 0
                VK_LEFT, VK_KP_LEFT -> frameSlider.value--
                VK_RIGHT, VK_KP_RIGHT -> frameSlider.value++
                VK_HOME -> frameSlider.value = 0
                VK_END -> frameSlider.value = frameSlider.maximum
                else -> return false
            }
            SHIFT_DOWN_MASK -> when (event.keyCode) {
                VK_LEFT, VK_KP_LEFT -> project?.let { frameSlider.value -= it.styling.global.fps.frac.roundToInt() }
                VK_RIGHT, VK_KP_RIGHT -> project?.let { frameSlider.value += it.styling.global.fps.frac.roundToInt() }
                else -> return false
            }
            else -> return false
        }
        return true
    }

    // @formatter:off
    private fun rewind() { if (playRate > 0) playRate = -1 else playRate-- }
    private fun pause() { playRate = 0 }
    private fun play() { if (playRate < 0) playRate = 1 else playRate++ }
    // @formatter:on

    fun onHide() {
        playRate = 0
    }

    fun updateProject(drawnProject: DrawnProject?) {
        this.drawnProject = drawnProject

        playRate = 0
        if (drawnProject == null) {
            scaledVideo = null
            scaledVideoBackend = null
            canvas.repaint()
            timecodeLabel.text = "\u2014 / \u2014"
        } else {
            restartDrawing()
            adjustTimecodeLabel()
        }
    }

    private fun restartDrawing() {
        val (project, _, video) = drawnProject ?: return

        // Abort if the canvas has never been shown on the screen yet, which would have it in a pre-initialized state
        // that this method can't cope with. As soon as it is shown for the first time, the resize listener will be
        // notified and call this method again.
        val graphics = canvas.graphics as Graphics2D? ?: return

        systemScaling = UIScale.getSystemScaleFactor(graphics)
        val scaling = min(
            canvas.width.toDouble() / project.styling.global.widthPx,
            canvas.height.toDouble() / project.styling.global.heightPx
        ) * systemScaling
        // Protect against too small canvas sizes.
        if (scaling < 0.001)
            return

        // Adjust the frame slider's range, then capture its current position.
        frameSlider.maximum = video.numFrames - 1
        val currentFrameIdx = frameSlider.value

        makeVideoBackendJobSlot.submit {
            val scaledVideo = video.copy(scaling)
            val scaledVideoBackend = object : DeferredVideo.Graphics2DBackend(
                scaledVideo, project.styling.global.grounding, draft = true, preloading = true
            ) {
                override fun createIntermediateImage(width: Int, height: Int) =
                    canvas.createImage(width, height) as BufferedImage
            }
            // Simulate materializing the currently selected frame in the background thread. As expensive operations
            // are cached, the subsequent materialization of that frame in the EDT thread will be very fast.
            scaledVideoBackend.preloadFrame(currentFrameIdx)
            SwingUtilities.invokeLater {
                this.scaledVideo = scaledVideo
                this.scaledVideoBackend = scaledVideoBackend
                canvas.repaint()
            }
        }
    }

    private fun adjustTimecodeLabel() {
        val (project, _, video) = drawnProject ?: return
        val fps = project.styling.global.fps
        val timecodeFormat = project.styling.global.timecodeFormat
        val curTc = formatTimecode(fps, timecodeFormat, frameSlider.value)
        val runtimeTc = formatTimecode(fps, timecodeFormat, video.numFrames)
        timecodeLabel.text = "$curTc / $runtimeTc"
    }

}
