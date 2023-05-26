package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.REF_FRC
import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.ProjectDialogType
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.ctrl.WelcomeCtrl
import com.loadingbyte.cinecred.ui.styling.StyleForm
import com.loadingbyte.cinecred.ui.view.welcome.WelcomeFrame
import java.awt.*
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.lang.Thread.sleep
import java.nio.file.Path
import java.text.AttributedString
import java.util.*
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.math.roundToInt


@Suppress("DEPRECATION")
abstract class ScreencastDemo(
    filename: String,
    format: Format,
    private val desktopWidth: Int = 1280,
    private val desktopHeight: Int = 720,
    protected val hold: Int = 500,
    private val captions: Boolean = false
) : Demo(filename, format) {

    protected abstract fun generate()

    override fun doGenerate() {
        withDemoProjectDir { projectDir ->
            this.projectDir = projectDir
            dt = VirtualDesktop(desktopWidth, desktopHeight - if (captions) 200 else 0)
            sc = Screencast(::write, format.fps, dt, hold, captionHeight = if (captions) 200 else 0, captionGap = 40)
            masterCtrl = UIFactory().master() as MasterCtrl
            generate()
            edt { Window.getWindows().forEach(Window::dispose) }
            sleep(100)
        }
        masterCtrl = null
    }

    protected fun addWelcomeWindow(fullscreen: Boolean = false) {
        edt { masterCtrl!!.showWelcomeFrame() }
        welcomeWin = BackedVirtualWindow(welcomeFrame)
        dt.add(welcomeWin)
        sleep(500)
        edt { welcomeFrame.projects_start_setMemorized(emptyList()) }
        if (fullscreen)
            dt.fullscreen(welcomeWin)
        else
            dt.center(welcomeWin)
        sleep(500)
    }

    protected fun removeWelcomeWindow() {
        dt.remove(welcomeWin)
    }

    protected fun addProjectWindows(
        hideStyWin: Boolean = false,
        setupVidWin: Boolean = false,
        setupDlvWin: Boolean = false,
        fullscreenPrjWin: Boolean = false,
        snapRatio: Double = 0.5,
        prjWinSplitRatio: Double = if (desktopHeight < 800) 0.85 else 0.9,
        styWinSplitRatio: Double = 0.25,
        vidWinSize: Dimension? = null,
        dlvWinSize: Dimension? = null
    ) {
        projectCtrl = masterCtrl!!.leakedProjectCtrls.lastOrNull() ?: run {
            tryCopyTemplate(projectDir, locale, 1, CsvFormat)
            edt { masterCtrl!!.openProject(projectDir, openOnScreen = gCfg) }
            sleep(1500)
            masterCtrl!!.leakedProjectCtrls.last()
        }
        prjWin = BackedVirtualWindow(projectCtrl.projectFrame)
        styWin = BackedVirtualWindow(projectCtrl.stylingDialog)
        vidWin = BackedVirtualWindow(projectCtrl.videoDialog)
        dlvWin = BackedVirtualWindow(projectCtrl.deliveryDialog)
        dt.add(prjWin)
        dt.add(styWin)
        dt.add(vidWin)
        dt.add(dlvWin)

        sleep(500)
        if (fullscreenPrjWin)
            dt.fullscreen(prjWin)
        else {
            dt.snapToSide(prjWin, rightSide = false, snapRatio)
            dt.snapToSide(styWin, rightSide = true, 1.0 - snapRatio)
        }
        if (setupVidWin) {
            edt {
                projectCtrl.setDialogVisible(ProjectDialogType.VIDEO, true)
                vidPnl.leakedPlayButton.actionListeners.forEach(vidPnl.leakedPlayButton::removeActionListener)
            }
            sleep(500)
            if (vidWinSize != null || desktopWidth < 1500)
                vidWin.size = vidWinSize ?: Dimension(desktopWidth * 2 / 3, desktopHeight * 2 / 3)
            dt.center(vidWin)
        }
        if (setupDlvWin) {
            edt { projectCtrl.setDialogVisible(ProjectDialogType.DELIVERY, true) }
            sleep(500)
            if (dlvWinSize != null || desktopWidth < 1500)
                dlvWin.size = dlvWinSize ?: Dimension(desktopWidth * 2 / 3, desktopHeight * 3 / 4)
            dt.center(dlvWin)
        }
        sleep(500)
        edt {
            prjPnl.leakedSplitPane.setDividerLocation(prjWinSplitRatio)
            styPnl.leakedSplitPane.setDividerLocation(styWinSplitRatio)
        }
        sleep(500)
        if (hideStyWin || fullscreenPrjWin)
            edt { projectCtrl.setDialogVisible(ProjectDialogType.STYLING, false) }
        if (setupVidWin)
            edt { projectCtrl.setDialogVisible(ProjectDialogType.VIDEO, false) }
        if (setupDlvWin)
            edt { projectCtrl.setDialogVisible(ProjectDialogType.DELIVERY, false) }
        sleep(500)
    }

    fun addOptionPaneDialog() {
        sleep(2000)
        optionPaneDialog = Window.getWindows()
            .filterIsInstance<JDialog>()
            .first { it.contentPane.run { componentCount == 1 && getComponent(0) is JOptionPane } }
        optionPaneWin = BackedVirtualWindow(optionPaneDialog)
        dt.add(optionPaneWin)
        dt.center(optionPaneWin)
    }

    fun removeOptionPaneDialog() {
        dt.remove(optionPaneWin)
    }

    private var masterCtrl: MasterCtrl? = null

    protected lateinit var projectDir: Path; private set
    protected lateinit var dt: VirtualDesktop; private set
    protected lateinit var sc: Screencast; private set

    protected lateinit var welcomeWin: BackedVirtualWindow; private set
    protected val welcomeFrame get() = (masterCtrl!!.leakedWelcomeCtrl as WelcomeCtrl).leakedWelcomeView as WelcomeFrame
    protected val projectsPanel get() = welcomeFrame.panel.projectsPanel

    protected lateinit var projectCtrl: ProjectController; private set
    protected lateinit var prjWin: BackedVirtualWindow; private set
    protected lateinit var styWin: BackedVirtualWindow; private set
    protected lateinit var vidWin: BackedVirtualWindow; private set
    protected lateinit var dlvWin: BackedVirtualWindow; private set

    protected val prjPnl get() = projectCtrl.projectFrame.panel
    protected val styPnl get() = projectCtrl.stylingDialog.panel
    protected val vidPnl get() = projectCtrl.videoDialog.panel
    protected val dlvPnl get() = projectCtrl.deliveryDialog.panel
    protected fun prjImagePnl(pageIdx: Int) = prjPnl.leakedPreviewPanels[pageIdx].leakedImagePanel
    protected val styTree get() = styPnl.leakedStylingTree
    protected val styGlobForm get() = styPnl.leakedGlobalForm
    protected val styPageForm get() = styPnl.leakedPageStyleForm
    protected val styContForm get() = styPnl.leakedContentStyleForm
    protected val styLetrForm get() = styPnl.leakedLetterStyleForm
    protected fun styLayrForm(i: Int): StyleForm<Layer> =
        ((styLayrPnl(i).getComponent(6) as JPanel).getComponent(0) as StyleForm<*>).castToStyle(Layer::class.java)

    protected val styIncUnitVGap
        get() = styGlobForm.getWidgetFor(Global::unitVGapPx.st()).components[0].getComponent(0)!!
    protected val styRuntime get() = styGlobForm.getWidgetFor(Global::runtimeFrames.st()).components[1] as JSpinner
    protected val styDecRuntime get() = styRuntime.getComponent(1)!!
    protected val styFlowSep
        get() = styContForm.getWidgetFor(ContentStyle::flowSeparator.st()).components[0] as JTextComponent
    protected val styLetrFormScrollBar get() = (styLetrForm.parent.parent as JScrollPane).verticalScrollBar
    protected val styIncFontHeight
        get() = styLetrForm.getWidgetFor(LetterStyle::heightPx.st()).components[0].getComponent(0)!!
    protected val styFeat
        get() = styLetrForm.getWidgetFor(LetterStyle::features.st()).components[1].getComponent(1) as JComboBox<*>
    protected val styLayrList
        get() = styLetrForm.getWidgetFor(LetterStyle::layers.st()).components[0] as JPanel

    protected fun styLayrAddBtn(i: Int) = styLayrList.let { it.getComponent(it.componentCount - 1 - 2 * i) } as JButton
    protected fun styLayrPnl(i: Int) = styLayrList.let { it.getComponent(it.componentCount - 2 - 2 * i) } as JPanel
    protected fun styLayrGrip(i: Int) = styLayrPnl(i).getComponent(0)
    protected fun styLayrNameField(i: Int) = styLayrPnl(i).getComponent(2) as JTextField
    protected fun styLayrAdvancedBtn(i: Int) = styLayrPnl(i).getComponent(3) as JToggleButton
    protected fun styLayrDelBtn(i: Int) = styLayrPnl(i).getComponent(4) as JButton

    protected fun styIncClearing() = styLayrForm(0).getWidgetFor(Layer::clearingRfh.st()).components[1].getComponent(0)

    protected val dlvFormats get() = dlvPnl.configurationForm.leakedFormatWidget.components[0] as JComboBox<*>
    protected val dlvTransparent
        get() = dlvPnl.configurationForm.leakedTransparentGroundingWidget.components[0] as JCheckBox
    protected val dlvResMult get() = dlvPnl.configurationForm.leakedResolutionMultWidget.components[0] as JComboBox<*>
    protected val dlvScan get() = dlvPnl.configurationForm.leakedScanWidget.components[0] as JComboBox<*>

    protected lateinit var optionPaneWin: BackedVirtualWindow; private set
    protected lateinit var optionPaneDialog: JDialog; private set

}


class Screencast(
    private val writeFrame: (BufferedImage) -> Unit,
    private val fps: FPS,
    private val desktop: VirtualDesktop,
    private val hold: Int,
    captionHeight: Int,
    private val captionGap: Int
) {

    companion object {
        private val CAPTION_FONT = BUNDLED_FONTS
            .first { it.getFontName(Locale.ROOT) == "Titillium Regular Upright" }
            .deriveFont(48f)
    }

    private val width = desktop.width
    private val height = desktop.height + captionHeight

    private var caption = mutableListOf<TextLayout>()

    fun frame(action: (() -> Unit)? = null) {
        action?.invoke()
        desktop.tick(1.0 / fps.frac)
        writeFrame(buildImage(width, height, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
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

    fun hold(millis: Int = hold, action: (() -> Unit)? = null) {
        repeat((fps.frac * millis / 1000.0).roundToInt()) { frame(action) }
    }

    fun mouseTo(point: Point, holdMillis: Int = hold) {
        desktop.mouseTo(point)
        while (desktop.isMouseMoving())
            frame()
        hold(holdMillis)
    }

    fun click(holdMillis: Int = hold, holdAction: (() -> Unit)? = null) {
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
            click(4 * hold)
        }
    }

    fun type(window: BackedVirtualWindow, textComp: JTextComponent, text: String, holdMillis: Int = hold) {
        mouseTo(window.desktopPosOf(textComp))
        edt { textComp.requestFocusInWindow() }
        hold()
        type(text, get = textComp::getText, set = { edt { textComp.text = it } })
        hold(holdMillis)
    }

    fun type(window: SpreadsheetEditorVirtualWindow, rowIdx: Int, colIdx: Int, text: String, holdMillis: Int = hold) {
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
        val lbm = LineBreakMeasurer(AttributedString(text, attrs).iterator, REF_FRC)
        while (lbm.position != text.length)
            caption.add(lbm.nextLayout(width / 2f))
        check(caption.size <= 2) { "Caption '$l10nKey' has ${caption.size} lines (only 2 are allowed)." }
        hold(1000 + 35 * text.length, holdAction)
        caption.clear()
    }

}
