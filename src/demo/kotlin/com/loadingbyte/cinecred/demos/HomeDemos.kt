@file:Suppress("DEPRECATION")

package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.delivery.WholePagePDFRenderJob
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Opt
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.ui.ProjectDialogType
import java.awt.KeyboardFocusManager
import java.lang.Thread.sleep
import javax.swing.JTree
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
    override fun generate() {
        // Inject an error into the credits file.
        val creditsFile = projectDir.resolve("Credits.csv")
        creditsFile.writeLines(creditsFile.readLines().toMutableList().apply { set(6, ",,,-1,,,,,") })

        reposition(prjFrame, 920, 570)
        sleep(500)
        edt {
            prjPanel.leakedSplitPane.setDividerLocation(0.88)
            prjPanel.leakedPageTabs.selectedIndex = 2
            prjImagePanel(2).leakedViewportCenterSetter(y = 975.0)
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
        reposition(vidDialog, 900, 430)
        sleep(500)
        edt {
            vidPanel.leakedPlayButton.isSelected = true
            vidPanel.leakedFrameSlider.value = 520
        }
        sleep(500)
        write(printWithPopups(vidPanel))
    }
}


object HomeScreenshotDeliveryDemo : ProjectDemo("$DIR/screenshot-delivery", Format.PNG) {
    override fun generate() {
        edt { projectCtrl.setDialogVisible(ProjectDialogType.DELIVERY, true) }
        sleep(500)
        reposition(dlvDialog, 820, 555)
        sleep(500)
        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            addDummyRenderJob(WholePagePDFRenderJob.FORMAT)
            addDummyRenderJob(VideoRenderJob.Format.ALL.first { it.label == "H.264" })
            addDummyRenderJob(VideoRenderJob.Format.ALL.first { it.label == "ProRes 422" })
            addDummyRenderJob(VideoRenderJob.Format.ALL.first { it.defaultFileExt == "png" })
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
    }

    private fun addDummyRenderJob(format: RenderFormat) {
        var dest = "Render"
        if (format.fileSeq) dest += "/Render.#######"
        dest += "." + format.defaultFileExt
        dlvPanel.renderQueuePanel.addRenderJobToQueue(DummyRenderJob(), format.label, dest)
    }

    private class DummyRenderJob : RenderJob {
        override val prefix get() = throw UnsupportedOperationException()
        override fun render(progressCallback: (Int) -> Unit) = throw UnsupportedOperationException()
    }
}


object HomeCreditsRuntimeDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/credits-runtime", Format.PNG,
    listOf(Global::runtimeFrames.st()), pageScaling = 0.45, pageWidth = 900, pageHeight = 400
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global.copy(runtimeFrames = Opt(false, 1092))
        this += last().copy(runtimeFrames = last().runtimeFrames.copy(isActive = true))
    }

    override val suffixes = listOf("-natural", "-adjusted")
    override fun credits(style: Global) = Pair(style, TEMPLATE_SCROLL_PAGE_FROM_DOP)
}
