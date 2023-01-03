package com.loadingbyte.cinecred.ui.demo

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.delivery.WholePagePDFRenderJob
import com.loadingbyte.cinecred.delivery.WholePageSequenceRenderJob
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.project.Opt
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.helper.usableBounds
import java.awt.*
import java.awt.event.KeyEvent.*
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_INT_RGB
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.imageio.ImageIO
import javax.swing.JTree
import kotlin.io.path.readLines
import kotlin.io.path.writeLines


@Suppress("DEPRECATION")
fun screenshotDemo(masterCtrl: MasterCtrl, locale: Locale) {
    comprehensivelyApplyLocale(locale)

    val projectDir = Files.createTempDirectory("cinecred-demo-project")
    tryCopyTemplate(projectDir, locale, CsvFormat)
    // Inject an error into the credits file.
    val creditsFile = projectDir.resolve("Credits.csv")
    creditsFile.writeLines(creditsFile.readLines().toMutableList().apply { set(6, ",,,-1,,,,,") })

    edt { masterCtrl.openProject(projectDir, openOnScreen = gCfg) }
    val projectCtrl = masterCtrl.leakedProjectCtrls.single()
    val prjFrame = projectCtrl.projectFrame
    val prjPanel = prjFrame.panel
    fun prjImagePanel(pageIdx: Int) = prjPanel.leakedPreviewPanels[pageIdx].leakedImagePanel
    val styDialog = projectCtrl.stylingDialog
    val styPanel = styDialog.panel
    val vidDialog = projectCtrl.videoDialog
    val vidPanel = vidDialog.panel
    val dlvDialog = projectCtrl.deliveryDialog
    val dlvPanel = dlvDialog.panel
    val dlvFormatCB = dlvPanel.configurationForm.leakedFormatWidget.components[0]

    sleep(2000)
    edt { projectCtrl.setDialogVisible(ProjectDialogType.STYLING, false) }
    sleep(500)

    // live-vis
    locate(prjFrame, 0, 920, 570)
    sleep(500)
    edt {
        prjPanel.leakedSplitPane.setDividerLocation(0.88)
        prjPanel.leakedPageTabs.selectedIndex = 2
        prjImagePanel(2).leakedViewportCenterYSetter(975.0)
    }
    sleep(500)
    png("screenshot-live-vis-guides-on", locale, screenshot(prjPanel))
    edt { prjPanel.leakedGuidesButton.doClick() }
    sleep(500)
    png("screenshot-live-vis-guides-off", locale, screenshot(prjPanel))

    // styling
    edt { projectCtrl.setDialogVisible(ProjectDialogType.STYLING, true) }
    sleep(500)
    locate(styDialog, 1, 760, 570)
    sleep(500)
    selectLastRow(styPanel.leakedStylingTree, l10n("project.template.contentStyleGutter", locale))
    sleep(500)
    png("screenshot-styling", locale, screenshot(styPanel))

    // video-preview
    edt { projectCtrl.setDialogVisible(ProjectDialogType.VIDEO, true) }
    sleep(500)
    locate(vidDialog, 2, 900, 455)
    sleep(500)
    edt {
        vidPanel.leakedPlayButton.isSelected = true
        vidPanel.leakedFrameSlider.value = 520
    }
    sleep(500)
    png("screenshot-video-preview", locale, screenshot(vidPanel))

    // delivery
    edt { projectCtrl.setDialogVisible(ProjectDialogType.DELIVERY, true) }
    sleep(500)
    locate(dlvDialog, 3, 820, 555)
    sleep(500)
    edt {
        dlvFormatCB.apply {
            requestFocus()
            isPopupVisible = true
        }
        listOf(
            WholePagePDFRenderJob.FORMAT,
            WholePageSequenceRenderJob.Format.TIFF_PACK_BITS,
            VideoRenderJob.Format.ALL.first { it.defaultFileExt == "mp4" },
            VideoRenderJob.Format.ALL.first { it.defaultFileExt == "dpx" }
        ).forEach { dlvPanel.renderQueuePanel.addRenderJobToQueue(DummyRenderJob(), it.label, "") }
        dlvPanel.renderQueuePanel.apply {
            leakedProgressSetter(0, isFinished = true)
            leakedProgressSetter(1, isFinished = true)
            leakedProgressSetter(2, progress = 80)
        }
    }
    sleep(500)
    png("screenshot-delivery", locale, screenshot(dlvPanel))

    // runtime
    // Take this screenshot at the end because it changes the styling, but only for one locale. Having this screenshot
    // further up would cause the demo styling to diverge between different locales.
    if (locale == FALLBACK_TRANSLATED_LOCALE) {
        png("credits-runtime-natural", locale, materialize(prjImagePanel(2).image!!, 0.45, 0.14, 425))
        edt {
            val old = projectCtrl.stylingHistory.current
            val rf = Opt(true, 1536)
            val new = Styling(old.global.copy(runtimeFrames = rf), old.pageStyles, old.contentStyles, old.letterStyles)
            projectCtrl.stylingHistory.editedAndRedraw(new, null)
        }
        sleep(500)
        png("credits-runtime-adjusted", locale, materialize(prjImagePanel(2).image!!, 0.45, 0.14, 425))
    }

    edt { Window.getWindows().forEach(Window::dispose) }
    sleep(500)
    masterCtrl.leakedProjectCtrls.clear()
    projectDir.toFile().deleteRecursively()
}


private val gCfg = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration


private fun selectLastRow(tree: JTree, label: String) {
    edt {
        val rowIdx = (0 until tree.rowCount).last { tree.getPathForRow(it).lastPathComponent.toString() == label }
        tree.selectionRows = intArrayOf(rowIdx)
    }
}

private fun locate(window: Window, quadrant: Int, width: Int, height: Int) {
    edt {
        window.minimumSize = Dimension(0, 0)

        // Add the window decorations to the size.
        val insets = window.insets
        val winWidth = width + insets.left + insets.right
        val winHeight = height + insets.top + insets.bottom

        val screenBounds = gCfg.usableBounds
        window.setBounds(
            screenBounds.x + (quadrant % 2) * screenBounds.width / 2 + (screenBounds.width / 2 - winWidth) / 2,
            screenBounds.y + (quadrant / 2) * screenBounds.height / 2 + (screenBounds.height / 2 - winHeight) / 2,
            winWidth, winHeight
        )
    }
}


private fun screenshot(component: Component) =
    buildImage(component.width, component.height, TYPE_INT_RGB) { g2 ->
        printWithPopups(component, g2)
    }

private fun materialize(defImage: DeferredImage, scaling: Double, cropTop: Double, height: Int): BufferedImage {
    val scaled = defImage.copy(universeScaling = scaling)
    val width = scaled.width.toInt()
    return buildImage(width, height, TYPE_INT_RGB) { g2 ->
        g2.setHighQuality()
        g2.color = Color.BLACK
        g2.fillRect(0, 0, width, height)
        g2.translate(0.0, -scaled.height.resolve() * cropTop)
        scaled.materialize(g2, listOf(DeferredImage.BACKGROUND, DeferredImage.FOREGROUND))
    }
}


private fun png(name: String, locale: Locale, image: BufferedImage) {
    val suffix = if (locale == FALLBACK_TRANSLATED_LOCALE) "" else "_$locale"
    val file = OUTPUT_DIR.resolve("screenshots/$name$suffix.png")
    file.parent.createDirectoriesSafely()
    ImageIO.write(image, "png", file.toFile())
}


private class DummyRenderJob : RenderJob {
    override fun generatesFile(file: Path) = throw UnsupportedOperationException()
    override fun render(progressCallback: (Int) -> Unit) = throw UnsupportedOperationException()
}
