@file:Suppress("DEPRECATION")

package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Override
import com.loadingbyte.cinecred.project.PRESET_GLOBAL
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.ui.comms.CreditsId
import com.loadingbyte.cinecred.ui.comms.DockableId.*
import com.loadingbyte.cinecred.ui.comms.RenderFormatCategory
import com.loadingbyte.cinecred.ui.comms.RenderJobInfo
import com.loadingbyte.cinecred.ui.comms.RenderJobStatus
import com.loadingbyte.cinecred.ui.helper.DropdownPopupMenuCheckBoxItem
import com.loadingbyte.cinecred.ui.styling.OverrideWidgetSpec
import com.loadingbyte.cinecred.ui.view.delivery.DeliveryDockable
import java.awt.KeyboardFocusManager
import java.lang.Thread.sleep
import java.time.Duration
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import kotlin.io.path.Path


private const val DIR = "home"

val HOME_DEMOS
    get() = listOf(
        HomeScreenshotDemo,
        HomeScreenshotLiveVisDemo,
        HomeScreenshotStylingDemo,
        HomeScreenshotVideoPreviewDemo,
        HomeScreenshotDeliveryDemo,
        HomeCreditsRuntimeDemo
    )


object HomeScreenshotDemo : ScreencastDemo("$DIR/screenshot", Format.PNG, 1450, 816, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply {
            parent(LOG).ratio = 0.89
            parent(PLAYBACK).ratio = 0.75
            parent(DELIVERY).ratio = 0.487
            leaf(PLAYBACK).collapsed = false
            leaf(DELIVERY).collapsed = false
        })

        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            preDok.leakedPageTabs.selectedIndex = 2
            prjImagePnl(2).leakedViewportCenterSetter(y = 1055.0)
            plyCtl.leakedFrameSlider.valueIsAdjusting = true
            plyCtl.leakedFrameSlider.value = 520
            plyCtl.setPlaybackDirection(1)
            dlvDok.leakedConfigForm.leakedDestinationWidgetTemplateMenu.components
                .filterIsInstance<DropdownPopupMenuCheckBoxItem<*>>().single { it.item == null }.doClick()
            (dlvDok.leakedConfigForm.leakedDestinationWidget.components[1] as JTextField).text = "/Render.mov"
            (dlvDok.leakedRenderQueuePanel.viewport.view as JTable).columnModel.apply {
                getColumn(0).preferredWidth = 220
                getColumn(5).preferredWidth = 180
            }
            dlvDok.addDummyRenderJob(true, VideoContainerRenderJob.FORMATS.first { it.label == "ProRes" })
            dlvDok.leakedRenderQueuePanel.leakedProgressSetter(0, progress = 800, time = Duration.ofSeconds(83))
        }
        selectLastRowWithLabel(styDok.leakedStylingTree, l10n("project.template.contentStyleGutter", locale))
        sleep(1000)

        sc.frame()
    }
}


object HomeScreenshotLiveVisDemo : ProjectDemo("$DIR/screenshot-live-vis", Format.PNG) {
    override fun trees() = trees(tree(920, 630, vSplit(0.0, TOOLBAR, PREVIEW)))

    override fun generate() {
        sleep(500)
        edt {
            preDok.leakedPageTabs.selectedIndex = 2
            prjImagePanel(2).leakedViewportCenterSetter(y = 982.0)
        }
        sleep(500)
        write(printWithPopups(prjFrame.contentPane), "-guides-on")
        edt { tolDok.leakedGuidesButton.doClick() }
        sleep(500)
        write(printWithPopups(prjFrame.contentPane), "-guides-off")
    }
}


object HomeScreenshotStylingDemo : ProjectDemo("$DIR/screenshot-styling", Format.PNG) {
    override fun trees() = trees(tree(760, 636, STYLING))

    override fun generate() {
        edt { styDok.leakedSplitPane.setDividerLocation(0.26) }
        sleep(500)
        selectLastRowWithLabel(styDok.leakedStylingTree, l10n("project.template.contentStyleGutter", locale))
        sleep(500)
        write(printWithPopups(prjFrame.contentPane))
    }
}


object HomeScreenshotVideoPreviewDemo : ProjectDemo("$DIR/screenshot-video-preview", Format.PNG) {
    override fun trees() = trees(tree(900, 460, PLAYBACK))

    override fun generate() {
        edt {
            plyControls.leakedFrameSlider.valueIsAdjusting = true
            plyControls.leakedFrameSlider.value = 520
            plyControls.setPlaybackDirection(1)
        }
        sleep(500)
        write(printWithPopups(prjFrame.contentPane))
    }
}


object HomeScreenshotDeliveryDemo : ProjectDemo("$DIR/screenshot-delivery", Format.PNG) {
    override fun trees() = trees(tree(820, 610, DELIVERY))

    override fun generate() {
        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            dlvDok.leakedConfigForm.leakedDestinationWidgetTemplateMenu.components
                .filterIsInstance<DropdownPopupMenuCheckBoxItem<*>>().single { it.item == null }.doClick()
            (dlvDok.leakedConfigForm.leakedDestinationWidget.components[1] as JTextField).text = "/Render.mp4"
            dlvDok.addDummyRenderJob(false, WholePagePDFRenderJob.FORMATS[0])
            dlvDok.addDummyRenderJob(true, VideoContainerRenderJob.H264)
            dlvDok.addDummyRenderJob(true, VideoContainerRenderJob.FORMATS.first { it.label == "ProRes" })
            dlvDok.addDummyRenderJob(true, ImageSequenceRenderJob.FORMATS.first { it.defaultFileExt == "png" })
            dlvDok.leakedRenderQueuePanel.apply {
                leakedProgressSetter(0, isFinished = true)
                leakedProgressSetter(1, isFinished = true)
                leakedProgressSetter(2, progress = 800, time = Duration.ofSeconds(83))
            }
        }
        sleep(500)
        write(printWithPopups(prjFrame.contentPane), "-options")
        edt { dlvFormatCB.apply { isPopupVisible = true } }
        sleep(500)
        write(printWithPopups(prjFrame.contentPane), "-formats")

        RenderQueue.cancelAllJobs()
    }

}


object HomeCreditsRuntimeDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/credits-runtime", Format.PNG,
    listOf(Global::runtimeFrames.st()), pageScaling = 0.45, pageWidth = 900, pageHeight = 400
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global
        this += last().copy(runtimeFrames = Override(1080))
    }

    override val overrideCtx = OverrideWidgetSpec.Context(1247, emptyMap())
    override val suffixes = listOf("-natural", "-adjusted")
    override fun credits(style: Global) = Pair(style, listOf(TEMPLATE_SCROLL_PAGE_FROM_DOP))
}


private fun selectLastRowWithLabel(tree: JTree, label: String) {
    edt {
        val rowIdx = (0..<tree.rowCount).last { tree.getPathForRow(it).lastPathComponent.toString() == label }
        tree.selectionRows = intArrayOf(rowIdx)
    }
}

private fun DeliveryDockable.addDummyRenderJob(videoOrStills: Boolean, format: RenderFormat) {
    var dest = "Render"
    if (format.fileSeq) dest += "/Render.#######"
    dest += "." + format.defaultFileExt
    val jobInfo = RenderJobInfo(
        object : RenderJob {
            override val prefix get() = Path("")
            override fun render(progressCallback: (Int) -> Unit) = throw UnsupportedOperationException()
        },
        CreditsId("${l10nDemo("projectDir")}.xlsx", l10n("project.template.spreadsheetName")),
        l10n("ui.deliverConfig.pagesAll"),
        if (videoOrStills) RenderFormatCategory.VIDEO else RenderFormatCategory.WHOLE_PAGE,
        format.label,
        PRESET_GLOBAL.resolution.run { "$widthPx \u00D7 $heightPx" },
        "${PRESET_GLOBAL.fps}p",
        "${RenderFormat.Property.PRIMARIES.standardDefault} / ${RenderFormat.Property.TRANSFER.standardDefault}",
        dest
    )
    leakedRenderQueuePanel.addRenderJobToQueue(jobInfo, RenderJobStatus.Queued)
}
