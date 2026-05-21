@file:Suppress("DEPRECATION")

package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.ImageSequenceRenderJob
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.delivery.VideoContainerRenderJob
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.Scan
import com.loadingbyte.cinecred.project.st
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate.Placeholder.*
import com.loadingbyte.cinecred.ui.PresetWindowLayout
import com.loadingbyte.cinecred.ui.comms.DockableId.*
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.helper.BUNDLED_FAMILIES
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.lang.Thread.sleep
import java.lang.foreign.MemorySegment.NULL
import javax.swing.*
import javax.swing.JSpinner.NumberEditor
import kotlin.io.path.name


private const val DIR = "guide/user-interface"

val GUIDE_USER_INTERFACE_DEMOS
    get() = listOf(
        GuideUserInterfaceTogglePanelsDemo,
        GuideUserInterfacePagesDemo,
        GuideUserInterfaceLayoutGuidesDemo,
        GuideUserInterfaceOverlaysStandardDemo,
        GuideUserInterfaceOverlaysCustomDemo,
        GuideUserInterfaceEditDemo,
        GuideUserInterfaceResetDemo,
        GuideUserInterfaceSnapSpreadsheetEditorDemo,
        GuideUserInterfaceVideoPreviewDemo,
        GuideUserInterfaceDeckLinkDemo,
        GuideUserInterfaceDeliveryDemo,
        GuideUserInterfaceDeliveryDestTemplateDemo,
        GuideUserInterfaceWarningsDemo,
        GuideUserInterfaceRearrangePanelsDemo,
        GuideUserInterfaceRetractWindowDemo,
        GuideUserInterfaceWindowLayoutsStandardDemo,
        GuideUserInterfaceWindowLayoutsCustomDemo
    )


object GuideUserInterfaceTogglePanelsDemo : ScreencastDemo("$DIR/toggle-panels", Format.VIDEO_GIF, 1100, 650, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees)

        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            tolDok.leakedGuidesButton.isSelected = false
            styTree.selectedRow = 8
        }
        sleep(500)

        val sequence = listOf(
            tolDok.leakedLogButton,
            tolDok.leakedPreviewButton,
            tolDok.leakedStylingButton,
            tolDok.leakedPlaybackButton,
            tolDok.leakedDeliveryButton
        )

        dt.mouseTo(prjWin.desktopPosOf(sequence[0]), jump = true)
        sc.hold(4 * hold)
        for (button in sequence) {
            sc.mouseTo(prjWin.desktopPosOf(button))
            sc.click(0)
            if (button == tolDok.leakedPlaybackButton)
                sleep(500)
            sc.hold(4 * hold)
        }
        sc.hold(4 * hold)
        for (button in sequence.asReversed()) {
            sc.mouseTo(prjWin.desktopPosOf(button))
            sc.click()
        }
    }
}


object GuideUserInterfacePagesDemo : ScreencastDemo("$DIR/pages", Format.VIDEO_GIF, 850, 650) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, PREVIEW))))

        edt { tolDok.leakedGuidesButton.isSelected = false }
        sleep(500)

        dt.mouseTo(prjWin.desktopPosOfTab(preDok.leakedPageTabs, 0), jump = true)
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(preDok.leakedPageTabs, 1))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(preDok.leakedPageTabs, 2))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(preDok.leakedPageTabs, 0))
    }
}


object GuideUserInterfaceLayoutGuidesDemo : ScreencastDemo("$DIR/layout-guides", Format.VIDEO_GIF, 850, 600) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, PREVIEW))))

        edt {
            preDok.leakedPageTabs.selectedIndex = 2
            tolDok.leakedGuidesButton.isSelected = false
        }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedGuidesButton))
        sc.click(0)
        sleep(500)
        sc.hold(4 * hold)
        sc.click(0)
        sleep(500)
        sc.hold()
    }
}


object GuideUserInterfaceOverlaysStandardDemo : ScreencastDemo("$DIR/overlays-standard", Format.VIDEO_GIF, 850, 600) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, PREVIEW))))

        edt { tolDok.leakedGuidesButton.isSelected = false }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedOverlaysButton))
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
    }
}


object GuideUserInterfaceOverlaysCustomDemo : ScreencastDemo("$DIR/overlays-custom", Format.VIDEO_GIF, 900, 620) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, PREVIEW))))

        edt { tolDok.leakedGuidesButton.isSelected = false }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedOverlaysButton))
        sc.click()
        sleep(500)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 5))
        sleep(1000)
        sc.click(0)
        sleep(1000)

        addWelcomeWindow()
        welcomeWin.size = Dimension(750, 500)
        dt.center(welcomeWin)

        sc.hold(4 * hold)
        for (idx in 2 downTo 1) {
            sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayTypeWidget.components[0].getComponent(idx)))
            sc.click(4 * hold)
        }
        sc.type(welcomeWin, prefsPanel.leakedCfgOverlayNameWidget.components[0] as JTextField, "Demo")
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayLinesHWidget.components[0]))
        sc.click()
        val linesHSpinner = prefsPanel.leakedCfgOverlayLinesHWidget.components[1].getComponent(1) as JSpinner
        sc.type(welcomeWin, (linesHSpinner.editor as NumberEditor).textField, "200")
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgOverlayDoneButton))
        sc.click(0)
        sc.hold(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOfCloseButton())

        removeWelcomeWindow()

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedOverlaysButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 4))
        sleep(500)
        sc.click(0)
        sleep(500)
        sc.hold(4 * hold)
    }
}


object GuideUserInterfaceEditDemo : ScreencastDemo("$DIR/edit", Format.VIDEO_GIF, 1100, 650, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { single().root.ratio = 0.37; leaf(LOG).collapsed = true })

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTreeItem(styTree, l10n("ui.styling.globalStyling")))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfSetting(styGlobForm, Global::resolution.st()))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 2))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfTreeItem(styTree, l10n("project.template.letterStyleCardName")))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfSetting(styLetrForm, LetterStyle::font.st(), 0))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(BUNDLED_FAMILIES.getFamily("Raleway Regular")))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedUndoStylingButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedRedoStylingButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedSaveStylingButton))
        sc.click(4 * hold)
        sc.mouseTo(prjWin.desktopPosOf(styDok.leakedAddContentStyleButton))
        sc.click(4 * hold)
        sc.mouseTo(prjWin.desktopPosOf(styDok.leakedRemoveStyleButton))
        sc.click(4 * hold)
    }
}


object GuideUserInterfaceResetDemo : ScreencastDemo("$DIR/reset", Format.VIDEO_GIF, 1100, 650, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { single().root.ratio = 0.37; leaf(LOG).collapsed = true })

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTreeItem(styTree, l10n("project.template.letterStyleCardName")))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfSetting(styLetrForm, LetterStyle::uppercase.st()))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedResetStylingButton))
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
    "$DIR/snap-spreadsheet-editor", Format.VIDEO_GIF, 1100, 650, 0.85
) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { leaf(LOG).collapsed = true })

        edt { styTree.selectedRow = 8 }
        val creditsFile = projectDir.resolve("${projectDir.name}.csv")
        val spreadsheetEditorWin = SpreadsheetEditorVirtualWindow(creditsFile, CsvFormat, skipRows = 1).apply {
            size = Dimension(600, 350)
            colWidths = intArrayOf(100, 100, 50, 100, 100, 50, 50, 50, 50, 50)
        }
        dt.add(spreadsheetEditorWin)
        dt.center(spreadsheetEditorWin)

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton))
        sc.click(2 * hold)
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfTitleBar())
        dt.dragWindow(spreadsheetEditorWin)
        sc.mouseTo(Point(dt.width - 2, dt.height / 4))
        dt.snapToSide(spreadsheetEditorWin, rightSide = true)
        dt.dropWindow()
        dt.toBack(spreadsheetEditorWin)
        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton))
        repeat(4) { sc.click(2 * hold) }
    }
}


object GuideUserInterfaceVideoPreviewDemo : ScreencastDemo("$DIR/video-preview", Format.VIDEO_GIF, 850, 540) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, collapsed(PLAYBACK)))))

        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedPlaybackButton))
        sc.click(0)
        sleep(500)
        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(plyCtl.leakedPlayButton))
        edt { plyCtl.leakedFrameSlider.valueIsAdjusting = true }
        sc.click { edt { plyCtl.leakedFrameSlider.value += 1; plyCtl.setPlaybackDirection(1) } }
        while (plyCtl.leakedFrameSlider.run { value < maximum / 3 })
            sc.frame { edt { plyCtl.leakedFrameSlider.value += 4; plyCtl.setPlaybackDirection(1) } }
    }
}


object GuideUserInterfaceDeckLinkDemo : ScreencastDemo("$DIR/decklink", Format.VIDEO_GIF, 850, 380) {
    override fun generate() {
        addProjectWindows(trees(tree(TOOLBAR)))

        edt {
            prjCtl.setDeckLinks(DECK_LINKS)
            prjCtl.setDeckLinkModes(MODES)
            prjCtl.setDeckLinkDepths(DeckLink.Depth.entries)
            prjCtl.setSelectedDeckLink(DECK_LINKS[0])
            prjCtl.setSelectedDeckLinkMode(MODES.first { it.name == "1080p24" })
            prjCtl.setSelectedDeckLinkDepth(DeckLink.Depth.D8)
            prjCtl.isVisible = true
        }
        sleep(500)

        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(prjCtl.leakedDeckLinkConfigButton))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 1), 0)
        sleep(500)
        sc.hold(8 * hold)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 3), 0)
        sleep(500)
        sc.hold(4 * hold)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 4), 0)
        sleep(500)
        sc.hold(4 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjCtl.leakedDeckLinkConfigButton), 0)
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(prjCtl.leakedDeckLinkConnectedButton))
        sc.hold(4 * hold)
    }

    private val MODES = listOf(
        "720p50", "720p59.94", "720p60",
        "1080p23.98", "1080p24", "1080p25", "1080p29.97", "1080p30", "1080p50", "1080p59.94", "1080p60",
        "1080i50", "1080i59.94", "1080i60",
        "2Kp23.98 DCI", "2Kp24 DCI", "2Kp25 DCI",
        "2160p23.98", "2160p24", "2160p25", "2160p29.97", "2160p30",
        "4Kp23.98 DCI", "4Kp24 DCI", "4Kp25 DCI", "4Kp29.97 DCI", "4Kp30 DCI"
    ).map { DeckLink.Mode(it, 0, Resolution(1, 1), FPS(1, 1), Bitmap.Scan.PROGRESSIVE, DeckLink.Depth.entries) }
    private val DECK_LINKS = listOf(DeckLink(NULL, NULL, null, "DeckLink Mini Monitor 4K", MODES))
}


object GuideUserInterfaceDeliveryDemo : ScreencastDemo("$DIR/delivery", Format.VIDEO_GIF, 850, 710) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, collapsed(DELIVERY)))))

        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedDeliveryButton))
        sc.click(12 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dlvFormats))
        sc.click(8 * hold)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(VideoContainerRenderJob.FORMATS.first { it.label == "ProRes" }))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvProfiles))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(RenderFormat.ProResProfile.PRORES_4444))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvTranspar))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(RenderFormat.Transparency.TRANSPARENT))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvSpaceScal))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(1))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvScan))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(Scan.INTERLACED_TOP_SHOWN_FIRST_AND_TOP_CODED_FIRST))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvPrimaries))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(ColorSpace.Primaries.BT2020))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvTransfer))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(ColorSpace.Transfer.PQ))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dlvDok.leakedAddButton))
        sc.click(8 * hold)

        RenderQueue.cancelAllJobs()
    }
}


object GuideUserInterfaceDeliveryDestTemplateDemo : ScreencastDemo(
    "$DIR/delivery-dest-template", Format.VIDEO_GIF, 850, 620
) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, DELIVERY))))

        for (idx in intArrayOf(3, 5)) {
            sc.mouseTo(prjWin.desktopPosOf(dlvDestTempl))
            sc.click(0)
            sleep(500)
            sc.hold()
            sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = idx))
            sc.click(0)
            sleep(500)
            sc.hold(4 * hold)
        }
        sc.mouseTo(prjWin.desktopPosOf(dlvDestTempl))
        sc.click(0)
        sleep(500)
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 7))
        sc.click(0)
        sleep(1000)

        addWelcomeWindow()
        welcomeWin.size = Dimension(750, 490)
        dt.center(welcomeWin)

        sc.hold(4 * hold)
        sc.type(welcomeWin, prefsPanel.leakedCfgTemplateNameWidget.components[0] as JTextField, "Demo")
        for (elem in listOf(PROJECT, " ", FORMAT, " ", FRAME_RATE, SCAN)) when (elem) {
            is String -> edt {
                val ta = (prefsPanel.leakedCfgTemplateStrWidget.components[0] as JScrollPane).viewport.view as JTextArea
                ta.append(elem)
            }
            is DeliveryDestTemplate.Placeholder -> {
                sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgTemplateStrWidget.components[1]))
                sc.click()
                sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(idx = elem.ordinal))
                sc.click()
            }
        }
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgTemplateDoneButton))
        sc.click(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOfCloseButton())

        removeWelcomeWindow()
        edt { welcomeFrame.close() }

        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dlvDestTempl))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 7))
        sleep(500)
        sc.click(8 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dlvFormats))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(VideoContainerRenderJob.FORMATS.first { it.label == "ProRes" }))
        sc.click(8 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dlvFormats))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(ImageSequenceRenderJob.FORMATS.find { it.label == "PNG" }))
        sc.click(8 * hold)
        sc.hold(8 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dlvDestTempl))
        sc.click(0)
        sleep(500)
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = 0))
        sc.click(0)
        sleep(500)
        sc.hold(8 * hold)
    }
}


object GuideUserInterfaceWarningsDemo : ScreencastDemo("$DIR/warnings", Format.PNG, 1100, 650, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { parent(LOG).ratio = 0.83 })

        edt {
            tolDok.leakedGuidesButton.isSelected = false
            logDok.leakedLogTable.columnModel.apply {
                getColumn(4).apply { minWidth = 64; width = 64 }
                getColumn(5).apply { minWidth = 64; width = 64 }
            }
        }
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


object GuideUserInterfaceRearrangePanelsDemo : ScreencastDemo(
    "$DIR/rearrange-panels", Format.VIDEO_GIF, 1100, 700, 0.85
) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { leaf(LOG).collapsed = true; leaf(DELIVERY).collapsed = false })
        prjWin.size = prjWin.size.apply { height -= 100 }

        edt { tolDok.leakedGuidesButton.isSelected = false }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutLockedButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(dok.leakedHeader(DELIVERY.name)))
        dt.mouseDownAndDrag()
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(preDok).apply { y += 200 })
        dt.mouseUp()
        sleep(500)
        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }
        sc.hold(4 * hold)

        sc.mouseTo(prjWin.desktopPosOf(dok.leakedHeader(DELIVERY.name)))
        dt.mouseDownAndDrag()
        sc.hold()
        sc.mouseTo(Point(dt.width / 2, dt.height - 20))
        dt.mouseUp()
        updateUndockedDialogs()
        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }
        sc.hold(4 * hold)

        sc.mouseTo(undockedWins[0].desktopPosOf(dok.leakedHeader(DELIVERY.name)))
        dt.mouseDownAndDrag()
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(styDok).apply { x += 100; y += 200 })
        dt.mouseUp()
        updateUndockedDialogs()
        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }
        sc.hold(4 * hold)
    }
}


object GuideUserInterfaceRetractWindowDemo : ScreencastDemo("$DIR/retract-window", Format.VIDEO_GIF, 1100, 500, 0.85) {
    override fun generate() {
        addProjectWindows(dockedTrees.apply { leaf(LOG).collapsed = true })

        edt {
            tolDok.leakedGuidesButton.isSelected = false
            tolDok.leakedWindowLayoutLockedButton.isSelected = false
        }
        sleep(500)

        dt.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton), jump = true)
        sc.click(2 * hold)
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dok.leakedRetractableButtons()[1]))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(dok.leakedRetractableButtons()[1]))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedStylingButton))
    }
}


object GuideUserInterfaceWindowLayoutsStandardDemo : ScreencastDemo(
    "$DIR/window-layouts-standard", Format.VIDEO_GIF, 1100, 550, 0.85
) {
    override fun generate() {
        addProjectWindows(dockedTrees)

        edt {
            tolDok.leakedGuidesButton.isSelected = false
        }
        sleep(500)

        dt.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutsButton), jump = true)
        for (idx in 1 downTo 0) {
            sc.click()
            sleep(500)
            sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = idx))
            sleep(500)
            sc.click(0)
            edt { projectCtrl.windowLayoutTrees = PresetWindowLayout.ALL[idx].trees(dt.fullscreen) }
            updateUndockedDialogs()
            sc.hold(4 * hold)
            sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutsButton))
        }
    }
}


object GuideUserInterfaceWindowLayoutsCustomDemo : ScreencastDemo(
    "$DIR/window-layouts-custom", Format.VIDEO_GIF, 1100, 550, 0.85
) {
    override fun generate() {
        addProjectWindows(trees(tree(vSplit(TOOLBAR, hSplit(DELIVERY, PREVIEW)))))

        edt {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner()
            tolDok.leakedGuidesButton.isSelected = false
            tolDok.leakedWindowLayoutLockedButton.isSelected = false
        }
        sleep(500)

        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutsButton))
        sc.click()
        sleep(500)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = PresetWindowLayout.ALL.size + 1))
        sleep(500)
        sc.click(0)
        addOptionPaneDialog()
        sc.hold(2 * hold)
        val textField = findComboBox(optionPaneDialog.contentPane)!!.editor.editorComponent as JTextField
        sc.type(optionPaneWin, textField, "Demo")
        sc.mouseTo(optionPaneWin.desktopPosOf(optionPaneDialog.rootPane.defaultButton))
        sc.click(0)
        removeOptionPaneDialog()
        sc.hold(2 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutsButton))
        sc.click()
        sleep(500)
        sc.mouseTo(prjWin.desktopPosOfDropdownItem(idx = PresetWindowLayout.ALL.size), 4 * hold)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedWindowLayoutsButton))
        sc.click()
        sleep(500)
        sc.mouseTo(prjWin.desktopPosOf(tolDok.leakedHomeButton))
        sc.click(0)
        sleep(1000)

        addWelcomeWindow()
        welcomeWin.size = Dimension(dt.width - 500, dt.height - 100)
        dt.center(welcomeWin)

        sc.hold(4 * hold)
        sc.mouseTo(welcomeWin.desktopPosOfTab(welcomeFrame.panel.leakedTabs, WelcomeTab.PREFERENCES.ordinal))
        sc.click()
        val btn = welcomeFrame.panel.preferencesPanel.leakedStartWindowLayoutDefaultButton(PresetWindowLayout.ALL.size)
        sc.mouseTo(welcomeWin.desktopPosOf(btn))
        sc.click(8 * hold)
    }

    private fun findComboBox(container: Container): JComboBox<*>? {
        for (idx in 0..<container.componentCount)
            when (val component = container.getComponent(idx)) {
                is JComboBox<*> -> return component
                is Container -> findComboBox(component)?.let { return it }
            }
        return null
    }
}
