package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.gCfg
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.scale
import com.loadingbyte.cinecred.common.translate
import com.loadingbyte.cinecred.drawer.VideoDrawer
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.projectio.formatTimecode
import com.loadingbyte.cinecred.ui.helper.JobSlot
import com.loadingbyte.cinecred.ui.helper.PAUSE_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import com.loadingbyte.cinecred.ui.helper.WARN_ICON
import net.miginfocom.swing.MigLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*
import kotlin.math.min
import kotlin.math.roundToInt


class VideoPanel(ctrl: ProjectController) : JPanel() {

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

    private val playButton = JToggleButton(PLAY_ICON).also { playButton ->
        var timer: Timer? = null

        playButton.addActionListener {
            playButton.icon = if (playButton.isSelected) PAUSE_ICON else PLAY_ICON

            val project = this.project
            if (!playButton.isSelected)
                timer?.stop()
            else if (project != null)
                timer = Timer((1000f / project.styling.global.fps.frac).roundToInt()) {
                    if (!ctrl.projectFrame.isVisible) {
                        // If the main frame has been disposed, also stop the timer, because would otherwise keep
                        // generating action events, which keep AWT's EDT thread from stopping and in turn keep the
                        // whole application from closing.
                        timer?.stop()
                    } else {
                        frameSlider.value++
                        if (frameSlider.value == frameSlider.maximum && playButton.isSelected)
                            playButton.doClick()
                    }
                }.apply { initialDelay = 0; start() }
        }
    }

    private val frameSlider = JSlider().also { frameSlider ->
        frameSlider.value = 0
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

    init {
        layout = MigLayout("insets 12lp n n n, gapy 10lp", "[]push[][][]push[]")
        add(JLabel(l10n("ui.video.warning"), WARN_ICON, JLabel.CENTER), "span, growx")
        add(playButton, "skip 1, width 2*pref!")
        add(frameSlider, "width :50sp:50sp")
        add(timecodeLabel)
        add(canvas, "newline, span, grow, push")

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                restartDrawing()
            }
        })
    }

    fun onLeaveTab() {
        if (playButton.isSelected)
            playButton.doClick()
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>) {
        this.project = project
        this.drawnPages = drawnPages

        if (playButton.isSelected)
            playButton.doClick()
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

        systemScaling = UIScale.getSystemScaleFactor(canvas.graphics as Graphics2D).toFloat()
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
