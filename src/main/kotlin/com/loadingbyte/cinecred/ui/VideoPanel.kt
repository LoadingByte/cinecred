package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.VideoDrawer
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*
import kotlin.math.min
import kotlin.math.roundToInt


object VideoPanel : JPanel() {

    private val G_CFG = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

    private val playButton = JToggleButton(PLAY_ICON).also { playButton ->
        var timer: Timer? = null

        playButton.addActionListener {
            playButton.icon = if (playButton.isSelected) PAUSE_ICON else PLAY_ICON

            val project = this.project
            if (!playButton.isSelected)
                timer?.stop()
            else if (project != null)
                timer = Timer((1000f / project.styling.global.fps.frac).roundToInt()) {
                    if (!MainFrame.isVisible) {
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
        frameSlider.addChangeListener {
            // When the slider is moved, either automatically or by hand, draw the selected frame.
            // Use paintImmediately() because repaint() might postpone the painting, which we do not want.
            Canvas.paintImmediately(0, 0, Canvas.width, Canvas.height)
            // Also adjust the time label.
            val project = project
            val videoDrawer = videoDrawer
            if (project != null && videoDrawer != null) {
                val fps = project.styling.global.fps.frac
                val curTc = Timecode(fps, frameSlider.value)
                val runtimeTc = Timecode(fps, videoDrawer.numFrames)
                timecodeLabel.text = "$curTc / $runtimeTc"
            }
        }
    }

    private val timecodeLabel = JLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    private var project: Project? = null
    private var drawnPages: List<DrawnPage> = emptyList()

    private val makeVideoDrawerJobSlot = JobSlot()
    private var videoDrawer: VideoDrawer? = null

    init {
        layout = MigLayout("insets 12lp n n n, gapy 10lp", "[]push[][][]push[]")
        add(JLabel(l10n("ui.video.warning"), SEVERITY_ICON[Severity.WARN], JLabel.CENTER), "span, growx")
        add(playButton, "skip 1, width 2*pref!")
        add(frameSlider, "width :50sp:50sp")
        add(timecodeLabel)
        add(Canvas, "newline, span, grow, push")

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                restartDrawing()
            }
        })
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>) {
        this.project = project
        this.drawnPages = drawnPages

        if (playButton.isSelected)
            playButton.doClick()
        if (project == null) {
            videoDrawer = null
            frameSlider.value = 0
            timecodeLabel.text = null
            Canvas.repaint()
        } else
            restartDrawing()
    }

    private fun restartDrawing() {
        val project = this.project ?: return
        val scaling = min(
            Canvas.width.toFloat() / project.styling.global.widthPx,
            Canvas.height.toFloat() / project.styling.global.heightPx
        )

        makeVideoDrawerJobSlot.submit {
            val videoDrawer = object : VideoDrawer(project, drawnPages, scaling, previewMode = true) {
                override fun createIntermediateImage(width: Int, height: Int) =
                    G_CFG.createCompatibleImage(width, height)
            }
            SwingUtilities.invokeLater {
                this.videoDrawer = videoDrawer
                frameSlider.maximum = videoDrawer.numFrames - 1
                Canvas.repaint()
            }
        }
    }


    private object Canvas : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            videoDrawer?.let { videoDrawer ->
                g.translate((width - videoDrawer.width) / 2, 0)
                g.clipRect(0, 0, videoDrawer.width, videoDrawer.height)
                videoDrawer.drawFrame(g as Graphics2D, frameSlider.value)
            }
        }
    }

}
