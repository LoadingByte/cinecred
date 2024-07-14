@file:Suppress("DEPRECATION")

package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Opt
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.ui.ProjectDialogType
import java.awt.KeyboardFocusManager
import java.lang.Thread.sleep
import java.nio.file.Path
import javax.swing.JTree
import kotlin.io.path.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeLines


private const val DIR = "home"

val HOME_DEMOS
    get() = listOf(
        HomeScreenshotLiveVisDemo,
        HomeScreenshotStylingDemo,
        HomeScreenshotVideoPreviewDemo,
        HomeScreenshotDeliveryDemo,
        HomeCreditsRuntimeDemo
    )


object HomeScreenshotLiveVisDemo : ProjectDemo("$DIR/screenshot-live-vis", Format.PNG) {
    override fun prepare(projectDir: Path) {
        // Inject an error into the credits file.
        val creditsFile = projectDir.resolve("Credits.csv")
        val lines = creditsFile.readLines().toMutableList()
        lines[lines.indexOfFirst { "Dirc Director" in it } + 1] = ",,,-1,,,,,,"
        creditsFile.writeLines(lines)
    }

    override fun generate() {
        reposition(prjFrame, 920, 610)
        sleep(500)
        edt {
            prjPanel.leakedSplitPane.setDividerLocation(0.87)
            prjPanel.leakedPageTabs.selectedIndex = 2
            prjImagePanel(2).leakedViewportCenterSetter(y = 980.0)
        }
        sleep(500)
        write(printWithPopups(prjPanel), "-guides-on")
        edt { prjPanel.leakedGuidesButton.doClick() }
        sleep(500)
        write(printWithPopups(prjPanel), "-guides-off")
    }
}


object HomeScreenshotStylingDemo : ProjectDemo("$DIR/screenshot-styling", Format.PNG) {
    override fun generate() {
        edt { projectCtrl.setDialogVisible(ProjectDialogType.STYLING, true) }
        sleep(500)
        reposition(styDialog, 760, 570)
        sleep(500)
        edt { styPanel.leakedSplitPane.setDividerLocation(0.3) }
        sleep(500)
        selectLastRowWithLabel(styPanel.leakedStylingTree, l10n("project.template.contentStyleGutter", locale))
        sleep(500)
        write(printWithPopups(styPanel))
    }

    private fun selectLastRowWithLabel(tree: JTree, label: String) {
        edt {
            val rowIdx = (0..<tree.rowCount).last { tree.getPathForRow(it).lastPathComponent.toString() == label }
            tree.selectionRows = intArrayOf(rowIdx)
        }
    }
}


object HomeScreenshotVideoPreviewDemo : ProjectDemo("$DIR/screenshot-video-preview", Format.PNG) {
    override fun generate() {
        edt { projectCtrl.setDialogVisible(ProjectDialogType.VIDEO, true) }
        sleep(500)
        reposition(plyDialog, 900, 430)
        sleep(500)
        edt {
            plyControls.leakedPlayButton.isSelected = true
            plyControls.leakedFrameSlider.value = 520
        }
        sleep(500)
        write(printWithPopups(plyPanel))
    }
}


object HomeScreenshotDeliveryDemo : ProjectDemo("$DIR/screenshot-delivery", Format.PNG) {
    override fun generate() {
        edt { projectCtrl.setDialogVisible(ProjectDialogType.DELIVERY, true) }
        sleep(500)
        reposition(dlvDialog, 820, 550)
        sleep(500)
        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            addDummyRenderJob(WholePagePDFRenderJob.FORMATS[0])
            addDummyRenderJob(VideoContainerRenderJob.H264)
            addDummyRenderJob(VideoContainerRenderJob.FORMATS.first { it.label == "ProRes" })
            addDummyRenderJob(ImageSequenceRenderJob.FORMATS.first { it.defaultFileExt == "png" })
            dlvPanel.renderQueuePanel.apply {
                leakedProgressSetter(0, isFinished = true)
                leakedProgressSetter(1, isFinished = true)
                leakedProgressSetter(2, progress = 80)
            }
        }
        sleep(500)
        write(printWithPopups(dlvPanel), "-options")
        edt { dlvFormatCB.apply { isPopupVisible = true } }
        sleep(500)
        write(printWithPopups(dlvPanel), "-formats")

        RenderQueue.cancelAllJobs()
    }

    private fun addDummyRenderJob(format: RenderFormat) {
        var dest = "Render"
        if (format.fileSeq) dest += "/Render.#######"
        dest += "." + format.defaultFileExt
        dlvPanel.renderQueuePanel.addRenderJobToQueue(DummyRenderJob(), format.label, dest)
    }

    private class DummyRenderJob : RenderJob {
        override val prefix get() = Path("")
        override fun render(progressCallback: (Int) -> Unit) = throw UnsupportedOperationException()
    }
}


object HomeCreditsRuntimeDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/credits-runtime", Format.PNG,
    listOf(Global::runtimeFrames.st()), pageScaling = 0.45, pageWidth = 900, pageHeight = 400
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global.copy(runtimeFrames = Opt(false, 1056))
        this += last().copy(runtimeFrames = last().runtimeFrames.copy(isActive = true))
    }

    override val suffixes = listOf("-natural", "-adjusted")
    override fun credits(style: Global) = Pair(style, TEMPLATE_SCROLL_PAGE_FROM_DOP)
}
