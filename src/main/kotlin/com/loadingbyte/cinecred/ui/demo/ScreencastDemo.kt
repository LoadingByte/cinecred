package com.loadingbyte.cinecred.ui.demo

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.imaging.VideoWriter
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.ctrl.WelcomeCtrl
import com.loadingbyte.cinecred.ui.helper.BUNDLED_FAMILIES
import com.loadingbyte.cinecred.ui.styling.StyleForm
import com.loadingbyte.cinecred.ui.view.welcome.WelcomeFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.awt.*
import java.awt.event.KeyEvent.*
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.io.Closeable
import java.lang.Thread.sleep
import java.nio.file.Path
import java.text.AttributedString
import java.text.NumberFormat
import java.util.*
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.io.path.*


@Suppress("DEPRECATION")
fun screencastDemo(masterCtrl: MasterCtrl, locale: Locale) {
    comprehensivelyApplyLocale(locale)

    val projectDir = Path(System.getProperty("java.io.tmpdir") + "/Demo")
    projectDir.toFile().deleteRecursively()
    projectDir.createDirectoriesSafely()

    val dt = VirtualDesktop(1920, 880)

    val suffix = if (locale == FALLBACK_TRANSLATED_LOCALE) "" else "_$locale"
    val file = OUTPUT_DIR.resolve("screencasts/screencast$suffix.mp4")
    file.parent.createDirectoriesSafely()
    Screencast(file, fps = 30, dt, captionHeight = 200, captionGap = 40).use { sc ->
        edt { masterCtrl.showWelcomeFrame() }
        val welcomeFrame = (masterCtrl.leakedWelcomeCtrl as WelcomeCtrl).leakedWelcomeView as WelcomeFrame
        val welcomeWin = BackedVirtualWindow(welcomeFrame)
        dt.add(welcomeWin)
        val projectsPanel = welcomeFrame.panel.projectsPanel

        sleep(500)
        edt { welcomeFrame.projects_start_setMemorized(emptyList()) }
        dt.center(welcomeWin)
        sleep(500)

        dt.mouseTo(Point(dt.width + 10, dt.height / 2), jump = true)
        sc.caption("screencast.caption.create.welcome")
        sc.caption("screencast.caption.create.new")
        sc.caption("screencast.caption.create.drop")
        dt.dragFolder(projectDir)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedStartPanel))
        dt.dropFolder(projectsPanel.leakedStartPanel)
        sc.hold()
        sc.caption("screencast.caption.create.config")
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgFormatWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(CsvFormat))
        sc.click()
        sc.caption("screencast.caption.create.exec")
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgDoneButton))
        sc.click(0)

        dt.remove(welcomeWin)
        sc.hold(4 * HOLD)

        val projectCtrl = masterCtrl.leakedProjectCtrls.last()
        val prjWin = BackedVirtualWindow(projectCtrl.projectFrame)
        val styWin = BackedVirtualWindow(projectCtrl.stylingDialog)
        val vidWin = BackedVirtualWindow(projectCtrl.videoDialog)
        val dlvWin = BackedVirtualWindow(projectCtrl.deliveryDialog)
        dt.add(prjWin)
        dt.add(styWin)
        dt.add(vidWin)
        dt.add(dlvWin)
        val prjPnl = projectCtrl.projectFrame.panel
        val styPnl = projectCtrl.stylingDialog.panel
        val vidPnl = projectCtrl.videoDialog.panel
        val dlvPnl = projectCtrl.deliveryDialog.panel
        val styTree = styPnl.leakedStylingTree
        val styGlobForm = styPnl.leakedGlobalForm
        val styPageForm = styPnl.leakedPageStyleForm
        val styContForm = styPnl.leakedContentStyleForm
        val styLetrForm = styPnl.leakedLetterStyleForm
        fun styDecoForm() =
            (styLetrForm.getWidgetFor(LetterStyle::decorations.st()).components[1].getComponent(3) as StyleForm<*>)
                .castToStyle(TextDecoration::class.java)

        val styIncUnitVGap = styGlobForm.getWidgetFor(Global::unitVGapPx.st()).components[0].getComponent(0)
        val styRuntime = styGlobForm.getWidgetFor(Global::runtimeFrames.st()).components[1] as JSpinner
        val styDecRuntime = styRuntime.getComponent(1)
        val styFlowSep = styContForm.getWidgetFor(ContentStyle::flowSeparator.st()).components[0] as JTextComponent
        val styIncFontHeight = styLetrForm.getWidgetFor(LetterStyle::heightPx.st()).components[0].getComponent(0)
        val styFeat = styLetrForm.getWidgetFor(LetterStyle::features.st()).components[1].getComponent(1) as JComboBox<*>
        fun styIncClearing() = styDecoForm().getWidgetFor(TextDecoration::clearingPx.st()).components[1].getComponent(0)
        val dlvFormats = dlvPnl.configurationForm.leakedFormatWidget.components[0] as JComboBox<*>
        val dlvTransparent = dlvPnl.configurationForm.leakedTransparentGroundingWidget.components[0] as JCheckBox

        sleep(1000)
        dt.snapToSide(prjWin, rightSide = false)
        dt.snapToSide(styWin, rightSide = true)
        sleep(500)
        edt {
            prjPnl.leakedSplitPane.setDividerLocation(0.9)
            styPnl.leakedSplitPane.setDividerLocation(0.25)
        }
        sleep(500)
        sc.hold(8 * HOLD)
        sc.caption("screencast.caption.create.done")

        sc.caption("screencast.caption.video.look")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click(0)
        sleep(500)
        dt.center(vidWin)
        sc.hold()
        edt { vidPnl.leakedPlayButton.actionListeners.forEach(vidPnl.leakedPlayButton::removeActionListener) }
        sc.mouseTo(vidWin.desktopPosOf(vidPnl.leakedPlayButton))
        sc.click { edt { vidPnl.leakedFrameSlider.value += 1 } }
        sc.caption("screencast.caption.video.play") { vidPnl.leakedFrameSlider.value += 1 }
        for (speed in 2..3)
            sc.click { edt { vidPnl.leakedFrameSlider.value += speed } }
        while (vidPnl.leakedFrameSlider.run { value != maximum })
            sc.frame { edt { vidPnl.leakedFrameSlider.value += 4 } }
        vidPnl.leakedPlayButton.isSelected = false
        sc.hold()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedVideoDialogButton))
        sc.click()

        sc.caption("screencast.caption.delivery.look")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedDeliveryDialogButton))
        sc.click(0)
        sleep(500)
        dt.center(vidWin)
        sc.hold()
        sc.mouseTo(dlvWin.desktopPosOf(dlvFormats))
        sc.click()
        sc.caption("screencast.caption.delivery.formats")
        sc.mouseTo(dlvWin.desktopPosOfDropdownItem(VideoRenderJob.Format.ALL.first { it.defaultFileExt == "dpx" }))
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
        sc.click(2 * HOLD)
        sc.mouseTo(prjWin.desktopPosOfTab(prjPnl.leakedPageTabs, 2))
        sc.click(2 * HOLD)
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
        sc.hold(2 * HOLD)
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
        repeat(4) { sc.click(2 * HOLD) }

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
        sc.click(2 * HOLD)
        sc.caption("screencast.caption.assign.content")
        sc.caption("screencast.caption.assign.block")
        sc.mouseTo(spreadsheetEditorWin.desktopPosOfCell(32, 1))
        sc.caption("screencast.caption.assign.assist")
        sc.caption("screencast.caption.assign.guides1")
        sc.caption("screencast.caption.assign.guides2")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedGuidesButton))
        sc.click(4 * HOLD)
        sc.click()
        sc.caption("screencast.caption.assign.change")
        sc.type(spreadsheetEditorWin, 35, 4, l10n("project.template.contentStyleSong"), 4 * HOLD)
        sc.type(spreadsheetEditorWin, 30, 4, l10n("project.template.contentStyleBullets"), 4 * HOLD)
        sc.type(spreadsheetEditorWin, 27, 4, l10n("project.template.contentStyleBlurb"), 4 * HOLD)
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
        sc.type(spreadsheetEditorWin, 26, 3, NumberFormat.getInstance().format(8.5), 4 * HOLD)
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

        sleep(1000)
        val resetDia = Window.getWindows()
            .filterIsInstance<JDialog>().first { it.contentPane.getComponent(0) is JOptionPane }
        val resetWin = BackedVirtualWindow(resetDia)
        dt.add(resetWin)
        dt.center(resetWin)
        sc.hold()
        sc.mouseTo(resetWin.desktopPosOf(resetDia.rootPane.defaultButton))
        sc.click(0)
        dt.remove(resetWin)
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
        sc.mouseTo(styWin.desktopPosOfSetting(styContForm, ContentStyle::bodyLetterStyleName.st()))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem(l10n("project.template.letterStyleSongTitle")))
        sc.click(4 * HOLD)
        sc.mouseTo(styWin.desktopPosOfSetting(styContForm, ContentStyle::bodyLetterStyleName.st()))
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

        sc.caption("screencast.caption.tabular.open")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y += 160 })
        dt.mouseDown()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y -= 160 })
        dt.mouseUp()
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.contentStyleTabular")))
        sc.click()
        sc.caption("screencast.caption.tabular.order")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridFillingOrder.st(), 1, 2, 0)
        sc.caption("screencast.caption.tabular.balance")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridFillingBalanced.st(), 0, 1)
        sc.caption("screencast.caption.tabular.struct")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::gridStructure.st(), 0, 2, 1)

        sc.caption("screencast.caption.bullets.open")
        sc.mouseTo(styWin.desktopPosOfTreeItem(styTree, l10n("project.template.contentStyleBullets")))
        sc.click()
        sc.caption("screencast.caption.bullets.dir")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::flowDirection.st(), 1, 0)
        sc.caption("screencast.caption.bullets.justify")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::flowLineHJustify.st(), 0, 6, 1)
        sc.caption("screencast.caption.bullets.match")
        sc.demonstrateSetting(styWin, styContForm, ContentStyle::flowMatchCellWidth.st(), 1, 2, 0)
        sc.caption("screencast.caption.bullets.sep")
        sc.type(styWin, styFlowSep, "", 2 * HOLD)
        sc.type(styWin, styFlowSep, "\u2013", 4 * HOLD)
        sc.type(styWin, styFlowSep, "\u2022")

        sc.caption("screencast.caption.blurb.open")
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y += 300 })
        dt.mouseDown()
        sc.mouseTo(prjWin.desktopPosOf(prjPnl.leakedPageTabs).apply { y -= 300 })
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
        sc.click(4 * HOLD)
        sc.caption("screencast.caption.letter.height")
        sc.mouseTo(styWin.desktopPosOf(styIncFontHeight))
        repeat(19) { sc.click(10) }
        sc.click()
        sc.caption("screencast.caption.letter.caps")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::uppercase.st(), 0)
        sc.caption("screencast.caption.letter.except")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::useUppercaseExceptions.st(), 0, 0)
        sc.caption("screencast.caption.letter.sc")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::smallCaps.st(), 1, 2, 0)
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::uppercase.st()))
        sc.click()
        sc.caption("screencast.caption.letter.feats")
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st()))
        sc.click()
        edt { styFeat.maximumRowCount = 10 }
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st(), 2))
        sc.click()
        sc.mouseTo(styWin.desktopPosOfDropdownItem("aalt"))
        sc.click(4 * HOLD)
        sc.mouseTo(styWin.desktopPosOfSetting(styLetrForm, LetterStyle::features.st(), 1))
        sc.click()
        sc.caption("screencast.caption.letter.deco")
        sc.demonstrateSetting(styWin, styLetrForm, LetterStyle::decorations.st(), 0)
        sc.caption("screencast.caption.letter.clear")
        sc.mouseTo(styWin.desktopPosOfSetting(styDecoForm(), TextDecoration::clearingPx.st(), 0))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(styIncClearing()))
        repeat(69) { sc.click(10) }
        sc.click()

        styTree.requestFocusInWindow()
        sc.mouseTo(Point(dt.width / 3, dt.height / 2))
        sc.hold(7 * HOLD)
        sc.caption("screencast.caption.outro.congrats")
        sc.caption("screencast.caption.outro.try")
        sc.caption("screencast.caption.outro.fun")
    }

    edt { Window.getWindows().forEach(Window::dispose) }
    sleep(500)
    masterCtrl.leakedProjectCtrls.clear()
    projectDir.toFile().deleteRecursively()
}


private const val HOLD = 250

private class Screencast(
    file: Path,
    private val fps: Int,
    private val desktop: VirtualDesktop,
    captionHeight: Int,
    private val captionGap: Int
) : Closeable {

    companion object {
        private val CAPTION_FONT = BUNDLED_FONTS
            .first { it.getFontName(Locale.ROOT) == "Titillium Regular Upright" }
            .deriveFont(48f)
        private val CAPTION_QUOTES_FONT = BUNDLED_FONTS
            .first { it.getFontName(Locale.ROOT) == "Roboto Condensed" }
            .deriveFont(48f)
    }

    private val width = desktop.width
    private val height = desktop.height + captionHeight

    private val videoWriter = VideoWriter(
        file, Resolution(width, height), FPS(fps, 1), avcodec.AV_CODEC_ID_H264, avutil.AV_PIX_FMT_YUV420P, emptyMap(),
        codecOptions = mapOf("crf" to "17")
    )

    private var caption = mutableListOf<TextLayout>()

    fun frame(action: (() -> Unit)? = null) {
        action?.invoke()
        desktop.tick(1.0 / fps)
        videoWriter.writeFrame(buildImage(width, height, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Paint wallpaper
            g2.color = Color(24, 24, 24)
            g2.fillRect(0, 0, width, desktop.height)
            // Paint desktop
            desktop.paint(g2)
            // Paint caption
            g2.color = Color.WHITE
            var captionY = (desktop.height + captionGap).toFloat()
            for (line in caption) {
                captionY += line.ascent
                line.draw(g2, (width - line.advance) / 2f, captionY)
                captionY += line.descent + line.leading
            }
        })
    }

    fun hold(millis: Int = HOLD, action: (() -> Unit)? = null) {
        repeat(fps * millis / 1000) { frame(action) }
    }

    fun mouseTo(point: Point) {
        desktop.mouseTo(point)
        while (desktop.isMouseMoving())
            frame()
        hold()
    }

    fun click(holdMillis: Int = HOLD, holdAction: (() -> Unit)? = null) {
        desktop.mouseDown()
        hold(100, holdAction)
        desktop.mouseUp()
        hold(holdMillis, holdAction)
    }

    fun <S : Style> demonstrateSetting(
        window: BackedVirtualWindow, form: StyleForm<S>, setting: StyleSetting<S, *>, vararg indices: Int
    ) {
        for (idx in indices) {
            mouseTo(window.desktopPosOfSetting(form, setting, idx))
            click(4 * HOLD)
        }
    }

    fun type(window: BackedVirtualWindow, textComp: JTextComponent, text: String, holdMillis: Int = HOLD) {
        mouseTo(window.desktopPosOf(textComp))
        click()
        type(text, get = textComp::getText, set = { edt { textComp.text = it } })
        hold(holdMillis)
    }

    fun type(window: SpreadsheetEditorVirtualWindow, rowIdx: Int, colIdx: Int, text: String, holdMillis: Int = HOLD) {
        mouseTo(window.desktopPosOfCell(rowIdx, colIdx))
        window.selectCell(rowIdx, colIdx)
        hold()
        type(text, get = { window.matrix[rowIdx][colIdx] }, set = { window.matrix[rowIdx][colIdx] = it })
        window.deselectCell()
        hold()
        window.save()
        sleep(500)
        hold(holdMillis)
    }

    private inline fun type(text: String, get: () -> String, set: (String) -> Unit) {
        val cur = get()
        for (n in 1..cur.length) {
            set(cur.dropLast(n))
            if (n != cur.length)
                hold(50)
        }
        hold()
        for (n in text.length - 1 downTo 0) {
            set(text.dropLast(n))
            if (n != 0)
                hold(70)
        }
    }

    fun caption(l10nKey: String, holdAction: (() -> Unit)? = null) {
        val text = l10nDemo(l10nKey)
        caption.clear()
        val attrs = mapOf(
            TextAttribute.FONT to CAPTION_FONT,
            TextAttribute.KERNING to TextAttribute.KERNING_ON,
            TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON
        )
        val attrStr = AttributedString(text, attrs)
        // Sadly, the lower quotes of "Titillium Upright" are bugged. As a fix, we just use quote glyphs from another
        // font which closely resembles the one used by "Titillium Upright".
        if ('\u201E' in text)
            for (idx in text.indices)
                if (text[idx].let { it == '\u201C' || it == '\u201D' || it == '\u201E' })
                    attrStr.addAttribute(TextAttribute.FONT, CAPTION_QUOTES_FONT, idx, idx + 1)
        val lbm = LineBreakMeasurer(attrStr.iterator, REF_FRC)
        while (lbm.position != text.length)
            caption.add(lbm.nextLayout(width / 2f))
        check(caption.size <= 2) { "Caption '$l10nKey' has ${caption.size} lines (only 2 are allowed)." }
        hold(1000 + 35 * text.length, holdAction)
        caption.clear()
    }

    override fun close() {
        videoWriter.close()
    }

}
