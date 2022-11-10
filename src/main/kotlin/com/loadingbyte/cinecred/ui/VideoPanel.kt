package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.drawer.VideoDrawer
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
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
import javax.swing.*
import kotlin.math.*


class VideoPanel(private val ctrl: ProjectController) : JPanel() {

    private val canvas: JPanel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            videoDrawer?.let { videoDrawer ->
                g as Graphics2D
                g.translate((width - videoDrawer.width / systemScaling) / 2f, 0f)
                g.scale(1f / systemScaling)
                g.clipRect(0, 0, videoDrawer.width, videoDrawer.height)
                videoDrawer.drawFrame(g, frameSlider.value)
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

    private var project: Project? = null
    private var drawnPages: List<DrawnPage> = emptyList()

    private val makeVideoDrawerJobSlot = JobSlot()
    private var videoDrawer: VideoDrawer? = null
    private var systemScaling = 1f

    private var playTimer: Timer? = null
    private var playRate = 0
        set(value) {
            val project = this.project
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
                val frameSpacing /* ms */ = 1000f / (abs(playRate) * project!!.styling.global.fps.frac)
                // We presume that we cannot draw faster than roughly 80 frames per second, and hence increase the step
                // size once that framerate is exceeded.
                val stepSize = ceil(12f / frameSpacing).roundToInt()
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
        val rewindIcon = PLAY_ICON.getScaledIcon(-1f, 1f)
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

        layout = MigLayout("insets 12lp n n n, gapy 10lp")
        add(JLabel(l10n("ui.video.warning"), WARN_ICON, JLabel.LEADING), "center")
        add(rewindButton, "newline, split 5, gapx 2lp")
        add(pauseButton, "gapx 2lp")
        add(playButton, "gapx 2lp")
        // If we use % or growx instead of "sp", the slider becomes laggy due to the quickly changing timecode label.
        add(frameSlider, "width 100sp")
        add(timecodeLabel, "gapright 8lp")
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

    fun onLeaveTab() {
        playRate = 0
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>) {
        this.project = project
        this.drawnPages = drawnPages

        playRate = 0
        if (project == null) {
            videoDrawer = null
            canvas.repaint()
            timecodeLabel.text = null
        } else {
            restartDrawing()
            adjustTimecodeLabel()
        }
    }

    private fun restartDrawing() {
        val project = this.project ?: return

        // Only access the graphics object if it's actually non-null. It can be null when the user closes a window
        // immediately after it has been opened.
        canvas.graphics?.let {
            systemScaling = UIScale.getSystemScaleFactor(it as Graphics2D).toFloat()
        }
        val scaling = min(
            canvas.width.toFloat() / project.styling.global.widthPx,
            canvas.height.toFloat() / project.styling.global.heightPx
        ) * systemScaling

        makeVideoDrawerJobSlot.submit {
            val videoDrawer = object : VideoDrawer(project, drawnPages, scaling, previewMode = true) {
                override fun createIntermediateImage(width: Int, height: Int) =
                    gCfg.createCompatibleImage(width, height)
            }
            SwingUtilities.invokeLater {
                this.videoDrawer = videoDrawer
                frameSlider.maximum = videoDrawer.numFrames - 1
                canvas.repaint()
            }
        }
    }

    private fun adjustTimecodeLabel() {
        val project = this.project ?: return
        val videoDrawer = this.videoDrawer ?: return
        val fps = project.styling.global.fps
        val timecodeFormat = project.styling.global.timecodeFormat
        val curTc = formatTimecode(fps, timecodeFormat, frameSlider.value)
        val runtimeTc = formatTimecode(fps, timecodeFormat, videoDrawer.numFrames)
        timecodeLabel.text = "$curTc / $runtimeTc"
    }

}
