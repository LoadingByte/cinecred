package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.helper.BUNDLED_FAMILIES
import java.awt.*
import java.awt.event.KeyEvent.*
import java.lang.Thread.sleep
import java.text.NumberFormat
import java.util.*
import javax.swing.*
import kotlin.io.path.*


private const val DIR = "screencast"

val SCREENCAST_DEMOS
    get() = listOf(
        ScreencastScreencastDemo
    )


object ScreencastScreencastDemo : ScreencastDemo(
    "$DIR/screencast", Format.MP4, 1920, 1080, hold = 250, captions = true
) {
    @Suppress("DEPRECATION")
    override fun generate() {
        addWelcomeWindow()

        sc.caption("screencast.caption.create.welcome")
        sc.caption("screencast.caption.create.new")
        sc.caption("screencast.caption.create.drop")
        dt.mouseDownAndDragFolder(projectDir)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedStartPanel))
        dt.mouseUp()
        sc.hold()
        sc.caption("screencast.caption.create.config")
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgFormatWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(CsvFormat))
        sc.click()
        sc.caption("screencast.caption.create.exec")
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgDoneButton))
        sc.click(0)

        removeWelcomeWindow()

        sc.hold(4 * hold)

        addProjectWindows(setupVidWin = true, setupDlvWin = true)

        sc.hold(8 * hold)
        sc.caption("screencast.caption.create.done")

        sc.caption("screencast.caption.video.look")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click()
        sc.mouseTo(vidWin.desktopPosOf(vidPnl.leakedPlayButton))
        sc.click { edt { vidPnl.leakedFrameSlider.value += 1 } }
        sc.caption("screencast.caption.video.play") { vidPnl.leakedFrameSlider.value += 1 }
        sc.click { edt { vidPnl.leakedFrameSlider.value += 2 } }
        while (vidPnl.leakedFrameSlider.run { value != maximum })
            sc.frame { edt { vidPnl.leakedFrameSlider.value += 4 } }
        vidPnl.leakedPlayButton.isSelected = false
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click()

        sc.caption("screencast.caption.delivery.look")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvFormats))
        sc.click()
        sc.caption("screencast.caption.delivery.formats")
        sc.mouseTo(dlvWin.desktopPosOfDropdownItem(VideoRenderJob.Format.ALL.first { it.defaultFileExt == "png" }))
        sc.click()
        sc.mouseTo(dlvWin.desktopPosOf(dlvTransparent))
        sc.caption("screencast.caption.delivery.transparent")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton))
        sc.click()

        sc.caption("screencast.caption.pages.explore")
        sc.caption("screencast.caption.pages.composed")
        sc.caption("screencast.caption.pages.each")
        sc.caption("screencast.caption.pages.flick")
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 1))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 2))
        sc.click(2 * hold)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 0))
        sc.click()

        sc.caption("screencast.caption.files.look")
        val fileBrowserWin = FileBrowserVirtualWindow().apply {
            pos = Point(dt.width + 10, dt.height / 3)
            folderPath = projectDir.pathString
            fileNames.addAll(projectDir.listDirectoryEntries().map { it.name })
        }
        dt.add(fileBrowserWin)
        sc.mouseTo(fileBrowserWin.desktopPosOfTitleBar())
        dt.dragWindow(fileBrowserWin)
        sc.mouseTo(Point(dt.width * 3 / 4, (dt.height - fileBrowserWin.size.height) / 2))
        dt.dropWindow()
        sc.caption("screencast.caption.files.list1")
        sc.caption("screencast.caption.files.list2")
        sc.caption("screencast.caption.files.credits")
        sc.mouseTo(fileBrowserWin.desktopPosOfFile("Credits.csv"))
        fileBrowserWin.selectedFileName = "Credits.csv"
        sc.hold()

        val creditsFile = projectDir.resolve("Credits.csv")
        val spreadsheetEditorWin = SpreadsheetEditorVirtualWindow(creditsFile, skipRows = 1).apply {
            size = Dimension(800, 500)
            colWidths = intArrayOf(160, 160, 50, 110, 110, 80, 80, 80, 110)
        }
        dt.add(spreadsheetEditorWin)
        dt.center(spreadsheetEditorWin)
        sc.hold(2 * hold)
        sc.caption("screencast.caption.files.hide")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click()
        sc.caption("screencast.caption.files.snap")
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfTitleBar())
        dt.dragWindow(spreadsheetEditorWin)
        sc.mouseTo(Point(dt.width - 2, dt.height / 4))
        dt.snapToSide(spreadsheetEditorWin, rightSide = true)
        dt.dropWindow()
        dt.toBack(spreadsheetEditorWin)
        dt.remove(fileBrowserWin)
        sc.hold()
        sc.caption("screencast.caption.files.switch")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        repeat(4) { sc.click(2 * hold) }

        sc.caption("screencast.caption.files.edit")
        sc.type(spreadsheetEditorWin, 4, 1, "R\u00E9my de R\u00E9alisateur")
        sc.caption("screencast.caption.files.reload")
        sc.caption("screencast.caption.files.mistake")
        sc.type(spreadsheetEditorWin, 6, 3, "-1")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedCreditsLog))
        sc.caption("screencast.caption.files.warning")
        sc.type(spreadsheetEditorWin, 6, 3, "")

        sc.caption("screencast.caption.assign.columns")
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(0, 4))
        sc.hold()
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(0, 7))
        sc.caption("screencast.caption.assign.page")
        sc.caption("screencast.caption.assign.pages")
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 1))
        sc.click()
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 2))
        sc.click(2 * hold)
        sc.caption("screencast.caption.assign.content")
        sc.caption("screencast.caption.assign.block")
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(32, 1))
        sc.caption("screencast.caption.assign.assist")
        sc.caption("screencast.caption.assign.guides1")
        sc.caption("screencast.caption.assign.guides2")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedGuidesButton))
        sc.click(0)
        sleep(500)
        sc.hold(4 * hold)
        sc.click(0)
        sleep(500)
        sc.hold()
        sc.caption("screencast.caption.assign.change")
        sc.type(spreadsheetEditorWin, 35, 4, l10n("project.template.contentStyleSong"), 4 * hold)
        sc.type(spreadsheetEditorWin, 30, 4, l10n("project.template.contentStyleBullets"), 4 * hold)
        sc.type(spreadsheetEditorWin, 27, 4, l10n("project.template.contentStyleBlurb"), 4 * hold)
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedCreditsLog))
        sc.caption("screencast.caption.assign.heads")
        sc.type(spreadsheetEditorWin, 27, 4, l10n("project.template.contentStyleGutter"))
        sc.type(spreadsheetEditorWin, 30, 4, "")
        sc.type(spreadsheetEditorWin, 35, 4, "")

        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(23, 1))
        sc.caption("screencast.caption.vGap.lines1")
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(26, 1))
        sc.caption("screencast.caption.vGap.lines2")
        sc.caption("screencast.caption.vGap.explicit")
        sc.type(spreadsheetEditorWin, 26, 3, NumberFormat.getInstance().format(8.5), 4 * hold)
        sc.type(spreadsheetEditorWin, 26, 3, "")

        sc.caption("screencast.caption.styling.open")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedStylingDialogButton))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("ui.styling.globalStyling")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styGlobForm, Global::unitVGapPx.st()))
        sc.caption("screencast.caption.styling.vGap")
        sc.mouseTo(styWin.desktopPosOf(styIncUnitVGap))
        repeat(7) { sc.click(10) }
        sc.click()
        sc.caption("screencast.caption.styling.global")
        sc.caption("screencast.caption.styling.runtime")
        sc.mouseTo(styWin.desktopPosOfSetting(styGlobForm, Global::runtimeFrames.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(styDecRuntime))
        repeat(47) { sc.click(10) }
        sc.click()
        styRuntime.transferFocusBackward()  // Avoid that the moving mouse selects text in the spinner text field.
        sc.caption("screencast.caption.styling.reset")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedResetStylingButton))
        sc.click()

        addOptionPaneDialog()
        sc.hold()
        sc.mouseTo(optionPaneWin.desktopPosOf(optionPaneDialog.rootPane.defaultButton))
        sc.click(0)
        removeOptionPaneDialog()
        sc.hold()

        sc.caption("screencast.caption.styling.styles")
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.pageStyleScroll")))
        sc.click()
        sc.caption("screencast.caption.styling.page")
        sc.mouseTo(styWin.desktopPosOfSetting(styPageForm, PageStyle::scrollMeltWithNext.st()))
        sc.caption("screencast.caption.styling.melt")

        sc.caption("screencast.caption.gutter.open")
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.contentStyleGutter")))
        sc.click()
        sc.caption("screencast.caption.gutter.orient")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::blockOrientation.st(), 1, 0)
        sc.caption("screencast.caption.gutter.spine1")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::spineAttachment.st(), 1, 4)
        sc.caption("screencast.caption.gutter.spine2")
        sc.caption("screencast.caption.gutter.letterRef")
        sc.mouseTo(styWin.desktopPosOfSetting(styContForm, ContentStyle::bodyLetterStyleName.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(l10n("project.template.letterStyleSongTitle")))
        sc.click(4 * hold)
        sc.mouseTo(styWin.desktopPosOfSetting(styContForm, ContentStyle::bodyLetterStyleName.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(l10n("project.template.letterStyleName")))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styContForm, ContentStyle::bodyLayout.st()))
        sc.caption("screencast.caption.gutter.layout")
        sc.caption("screencast.caption.gutter.match")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridMatchColWidths.st(), 0, 1)
        sc.caption("screencast.caption.gutter.justify")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridCellHJustifyPerCol.st(), 4, 2)
        sc.caption("screencast.caption.gutter.columns")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridCellHJustifyPerCol.st(), 0, 5)
        sc.caption("screencast.caption.gutter.head")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::hasHead.st(), 0, 0)

        sc.caption("screencast.caption.bullets.open")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y += 80 })
        dt.mouseDown()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y -= 80 })
        dt.mouseUp()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.contentStyleBullets")))
        sc.click()
        sc.caption("screencast.caption.bullets.justify")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::flowLineHJustify.st(), 0, 6, 1)
        sc.caption("screencast.caption.bullets.match")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::flowMatchCellWidth.st(), 1, 2, 0)
        sc.caption("screencast.caption.bullets.sep")
        sc.type(styWin, styFlowSep, "", 2 * hold)
        sc.type(styWin, styFlowSep, "\u2013", 4 * hold)
        sc.type(styWin, styFlowSep, "\u2022")

        sc.caption("screencast.caption.blurb.open")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y += 380 })
        dt.mouseDown()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y -= 380 })
        dt.mouseUp()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.contentStyleBlurb")))
        sc.click()
        sc.caption("screencast.caption.blurb.justify")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::paragraphsLineHJustify.st(), 2, 6, 1)

        sc.caption("screencast.caption.letter.open")
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.letterStyleCardName")))
        sc.click()
        sc.caption("screencast.caption.letter.lots")
        sc.caption("screencast.caption.letter.fonts")
        val fontName = "Raleway Medium"
        val fontFamily = BUNDLED_FAMILIES.getFamily(fontName)!!
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::fontName.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(fontFamily))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::fontName.st(), 1))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(fontFamily.getFont(fontName)!!))
        sc.click(4 * hold)
        sc.caption("screencast.caption.letter.height")
        sc.mouseTo(styWin.desktopPosOf(styIncFontHeight))
        repeat(19) { sc.click(10) }
        sc.click()
        sc.caption("screencast.caption.letter.caps")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::uppercase.st(), 0)
        sc.caption("screencast.caption.letter.except")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::useUppercaseExceptions.st(), 0, 0)
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::uppercase.st()))
        sc.click()
        sc.caption("screencast.caption.letter.sc")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::smallCaps.st(), 1, 2, 0)
        sc.caption("screencast.caption.letter.feats")
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st()))
        sc.click()
        edt { styFeat.maximumRowCount = 10 }
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st(), 2))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem("aalt"))
        sc.click(4 * hold)
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st(), 1))
        sc.click()
        sc.caption("screencast.caption.letter.underline")
        sc.mouseTo(styWin.desktopPosOf(styLayrAddBtn(1)))
        sc.click(0)
        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }
        sc.hold(4 * hold)
        sc.caption("screencast.caption.letter.layers")
        sc.mouseTo(styWin.desktopPosOf(styLayrGrip(1)))
        dt.mouseDownAndDrag()
        sc.hold()
        sc.mouseTo(styWin.desktopPosOf(styLayrAddBtn(0)), 2 * hold)
        dt.mouseUp()
        sc.hold(4 * hold)
        sc.mouseTo(styWin.desktopPosOf(styLayrAdvancedBtn(0)))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(styLetrFormScrollBar))
        dt.mouseDown()
        sc.mouseTo(styWin.desktopPosOf(styLetrFormScrollBar).apply { y += 250 })
        dt.mouseUp()
        sc.hold()
        sc.caption("screencast.caption.letter.advanced")
        sc.hold(4 * hold)

        edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner() }
        sc.mouseTo(Point(dt.width / 3, dt.height / 2))
        sc.hold(7 * hold)
        sc.caption("screencast.caption.outro.congrats")
        sc.caption("screencast.caption.outro.guide")
        sc.caption("screencast.caption.outro.try")
        sc.caption("screencast.caption.outro.fun")
    }
}
