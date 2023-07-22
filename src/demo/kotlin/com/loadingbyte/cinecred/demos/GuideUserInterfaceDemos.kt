@file:Suppress("DEPRECATION")

package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.demo.ScreencastDemo
import com.loadingbyte.cinecred.demo.SpreadsheetEditorVirtualWindow
import com.loadingbyte.cinecred.demo.edt
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.ui.LocaleWish
import com.loadingbyte.cinecred.ui.OVERLAYS_PREFERENCE
import com.loadingbyte.cinecred.ui.helper.BUNDLED_FAMILIES
import java.awt.Dimension
import java.awt.Point
import java.lang.Thread.sleep
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSpinner.NumberEditor
import javax.swing.JTextField


private const val DIR = "guide/user-interface"

val GUIDE_USER_INTERFACE_DEMOS
    get() = listOf(
        GuideUserInterfaceToggleDialogsDemo,
        GuideUserInterfacePagesDemo,
        GuideUserInterfaceLayoutGuidesDemo,
        GuideUserInterfaceOverlaysStandardDemo,
        GuideUserInterfaceOverlaysCustomDemo,
        GuideUserInterfaceEditDemo,
        GuideUserInterfaceResetDemo,
        GuideUserInterfaceSnapSpreadsheetEditorDemo,
        GuideUserInterfaceVideoPreviewDemo,
        GuideUserInterfaceDeliveryDemo,
        GuideUserInterfaceWarningsDemo
    )


object GuideUserInterfaceToggleDialogsDemo : ScreencastDemo("$DIR/toggle-dialogs", Format.VIDEO_GIF, 1100, 600) {
    override fun generate() {
        addProjectWindows(hideStyWin = true, setupVidWin = true, setupDlvWin = true)

        edt {
            prjPnl.leakedGuidesButton.isSelected = false
            styTree.selectedRow = 8
        }
        sleep(500)

        dt.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton), jump = true)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click(8 * hold)
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click(8 * hold)
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton))
        sc.click(8 * hold)
        sc.click()
    }
}


object GuideUserInterfacePagesDemo : ScreencastDemo("$DIR/pages", Format.VIDEO_GIF, 600, 450) {
    override fun generate() {
        addProjectWindows(fullscreenPrjWin = true)

        edt { prjPnl.leakedGuidesButton.isSelected = false }
        sleep(500)

        dt.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 0), jump = true)
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 1))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 2))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 0))
    }
}


object GuideUserInterfaceLayoutGuidesDemo : ScreencastDemo("$DIR/layout-guides", Format.VIDEO_GIF, 600, 450) {
    override fun generate() {
        addProjectWindows(fullscreenPrjWin = true)

        edt {
            prjPnl.leakedPageTabs.selectedIndex = 2
            prjPnl.leakedGuidesButton.isSelected = false
        }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedGuidesButton))
        sc.click(0)
        sleep(500)
        sc.hold(4 * hold)
        sc.click(0)
        sleep(500)
        sc.hold()
    }
}


object GuideUserInterfaceOverlaysStandardDemo : ScreencastDemo("$DIR/overlays-standard", Format.VIDEO_GIF, 600, 450) {
    override fun generate() {
        val backedUpOverlays = OVERLAYS_PREFERENCE.get()
        OVERLAYS_PREFERENCE.set(emptyList())
        try {
            generate2()
        } finally {
            OVERLAYS_PREFERENCE.set(backedUpOverlays)
        }
    }

    private fun generate2() {
        addProjectWindows(fullscreenPrjWin = true)
        val backedUpOverlays = OVERLAYS_PREFERENCE.get()
        OVERLAYS_PREFERENCE.set(emptyList())

        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedOverlaysButton))
        sc.click()
        sleep(500)
        for (idx in 0..3) {
            sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = idx))
            sleep(500)
            sc.click(0)
            sleep(1000)
            sc.hold(2 * hold)
            sc.click(0)
            sleep(1000)
            sc.hold(hold)
        }

        OVERLAYS_PREFERENCE.set(backedUpOverlays)
    }
}


object GuideUserInterfaceOverlaysCustomDemo : ScreencastDemo("$DIR/overlays-custom", Format.VIDEO_GIF, 900, 600) {
    override fun generate() {
        val backedUpOverlays = OVERLAYS_PREFERENCE.get()
        OVERLAYS_PREFERENCE.set(emptyList())
        try {
            generate2()
        } finally {
            OVERLAYS_PREFERENCE.set(backedUpOverlays)
        }
    }

    private fun generate2() {
        addProjectWindows(fullscreenPrjWin = true)

        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedOverlaysButton))
        sc.click()
        sleep(500)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 5))
        sleep(1000)
        sc.click(0)
        sleep(1000)

        addWelcomeWindow()
        welcomeWin.size = Dimension(750, 500)
        dt.center(welcomeWin)
        welcomeFrame.preferences_start_setUILocaleWish(LocaleWish.System)
        welcomeFrame.preferences_start_setCheckForUpdates(true)
        welcomeFrame.preferences_start_setAccounts(emptyList())

        sc.hold(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedStartAddOverlayButton), 2 * hold)
        sc.click(4 * hold)
        for (idx in 2 downTo 1) {
            sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayTypeWidget.components[0].getComponent(idx)))
            sc.click(4 * hold)
        }
        val name = "Demo"
        sc.type(welcomeWin, prefsPanel.leakedCfgOverlayNameWidget.components[0] as JTextField, name)
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayLinesHWidget.components[0]))
        sc.click()
        val linesHSpinner = prefsPanel.leakedCfgOverlayLinesHWidget.components[1].getComponent(1) as JSpinner
        sc.type(welcomeWin, (linesHSpinner.editor as NumberEditor).textField, "200")
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayDoneButton))
        sc.click(0)
        welcomeFrame.preferences_start_setAccounts(emptyList())
        sc.hold(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOfCloseButton())

        removeWelcomeWindow()

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedOverlaysButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 4))
        sleep(500)
        sc.click(0)
        sleep(500)
        sc.hold(4 * hold)
    }
}


object GuideUserInterfaceEditDemo : ScreencastDemo(
    "$DIR/edit", Format.VIDEO_GIF, 1100, 600
) {
    override fun generate() {
        addProjectWindows(hideStyWin = true)

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click(2 * hold)
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("ui.styling.globalStyling")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styGlobForm, Global::resolution.st()))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(idx = 2))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.letterStyleCardName")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::fontName.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(BUNDLED_FAMILIES.getFamily("Raleway Regular")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(prjPnl.leakedUndoStylingButton))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(prjPnl.leakedRedoStylingButton))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(prjPnl.leakedSaveStylingButton))
        sc.click(4 * hold)
        sc.mouseTo(styWin.desktopPosOf(styPnl.leakedAddContentStyleButton))
        sc.click(4 * hold)
        sc.mouseTo(styWin.desktopPosOf(styPnl.leakedRemoveStyleButton))
        sc.click(4 * hold)
    }
}


object GuideUserInterfaceResetDemo : ScreencastDemo(
    "$DIR/reset", Format.VIDEO_GIF, 1100, 600
) {
    override fun generate() {
        addProjectWindows()

        sc.hold(2 * hold)
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.letterStyleCardName")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::uppercase.st()))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedResetStylingButton))
        sc.click()
        addOptionPaneDialog()
        sc.hold(2 * hold)
        sc.mouseTo(optionPaneWin.desktopPosOf(optionPaneDialog.rootPane.defaultButton))
        sc.click(0)
        removeOptionPaneDialog()
        sc.hold(4 * hold)
    }
}


object GuideUserInterfaceSnapSpreadsheetEditorDemo : ScreencastDemo(
    "$DIR/snap-spreadsheet-editor", Format.VIDEO_GIF, 1100, 600
) {
    override fun generate() {
        addProjectWindows(hideStyWin = true)

        edt { styTree.selectedRow = 8 }
        val creditsFile = projectDir.resolve("Credits.csv")
        val spreadsheetEditorWin = SpreadsheetEditorVirtualWindow(creditsFile, skipRows = 1).apply {
            size = Dimension(600, 350)
            colWidths = intArrayOf(100, 100, 50, 100, 100, 50, 50, 50, 50, 50)
        }
        dt.add(spreadsheetEditorWin)
        dt.center(spreadsheetEditorWin)

        sc.hold(2 * hold)
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfTitleBar())
        dt.dragWindow(spreadsheetEditorWin)
        sc.mouseTo(Point(dt.width - 2, dt.height / 4))
        dt.snapToSide(spreadsheetEditorWin, rightSide = true)
        dt.dropWindow()
        dt.toBack(spreadsheetEditorWin)
        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        repeat(4) { sc.click(2 * hold) }
    }
}


object GuideUserInterfaceVideoPreviewDemo : ScreencastDemo("$DIR/video-preview", Format.VIDEO_GIF, 700, 440) {
    override fun generate() {
        addProjectWindows(fullscreenPrjWin = true, setupVidWin = true, vidWinSize = Dimension(600, 330))

        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click(2 * hold)
        sc.mouseTo(vidWin.desktopPosOf(vidPnl.leakedPlayButton))
        sc.click { edt { vidPnl.leakedFrameSlider.value += 1 } }
        while (vidPnl.leakedFrameSlider.run { value < maximum / 3 })
            sc.frame { edt { vidPnl.leakedFrameSlider.value += 4 } }
    }
}


object GuideUserInterfaceDeliveryDemo : ScreencastDemo("$DIR/delivery", Format.VIDEO_GIF, 800, 695) {
    override fun generate() {
        addProjectWindows(fullscreenPrjWin = true, setupDlvWin = true, dlvWinSize = Dimension(700, 570))

        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton))
        sc.click(12 * hold)
        sc.mouseTo(dlvWin.desktopPosOf(dlvFormats))
        sc.click(8 * hold)
        sc.mouseTo(dlvWin.desktopPosOfDropdownItem(VideoRenderJob.Format.ALL.first { it.label == "ProRes 4444" }))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvTransparent))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvResMult))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOfDropdownItem(1))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvScan))
        sc.click(2 * hold)
        sc.mouseTo(dlvWin.desktopPosOfDropdownItem(Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvPnl.addButton))
        sc.click(8 * hold)

        RenderQueue.cancelAllJobs()
    }
}


object GuideUserInterfaceWarningsDemo : ScreencastDemo("$DIR/warnings", Format.PNG, 1100, 600) {
    override fun generate() {
        addProjectWindows(prjWinSplitRatio = 0.8)

        edt { prjPnl.leakedGuidesButton.isSelected = false }
        val oldStyling = projectCtrl.stylingHistory.current
        val oldCardStyle = oldStyling.contentStyles.first { it.name == l10n("project.PageBehavior.CARD") }
        val newCardStyle = oldCardStyle.copy(hasTail = false)
        val newStyling = oldStyling.copy(
            contentStyles = oldStyling.contentStyles.remove(oldCardStyle).add(newCardStyle),
            letterStyles = oldStyling.letterStyles
                .removeAll { it.name == l10n("project.template.letterStyleCardSmall") }
        )
        edt {
            projectCtrl.stylingHistory.loadAndRedraw(newStyling)
            styTree.selected = newCardStyle
            (styContForm.parent.parent as JScrollPane).verticalScrollBar.apply { value = maximum }
        }
        sleep(500)

        sc.frame()
    }
}
