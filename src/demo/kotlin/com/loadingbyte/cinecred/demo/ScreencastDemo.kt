package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.useResourceStream
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.ctrl.WelcomeCtrl
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import com.loadingbyte.cinecred.ui.helper.setExtraSystemScaleFactor
import com.loadingbyte.cinecred.ui.helper.withG2
import com.loadingbyte.cinecred.ui.styling.StyleForm
import com.loadingbyte.cinecred.ui.view.welcome.WelcomeFrame
import sun.font.FontUtilities
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.lang.Thread.sleep
import java.nio.file.Path
import java.text.AttributedString
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.math.ceil
import kotlin.math.roundToInt


@Suppress("DEPRECATION")
abstract class ScreencastDemo(
    filename: String,
    format: Format,
    private val imageWidth: Int = 1280,
    private val imageHeight: Int = 720,
    private val desktopScale: Double = 1.0,
    protected val hold: Int = 500,
    private val captions: Boolean = false
) : Demo(filename, format) {

    protected abstract fun generate()

    override fun doGenerate() {
        withDemoProjectDir { projectDir ->
            this.projectDir = projectDir
            val captionHeight = if (captions) 200 else 0
            dt = VirtualDesktop(
                (imageWidth / desktopScale).roundToInt(),
                ((imageHeight - captionHeight) / desktopScale).roundToInt()
            )
            sc = Screencast(::write, format.fps, imageWidth, imageHeight, dt, desktopScale, hold, captionHeight, 40)
            masterCtrl = UIFactory().master() as MasterCtrl

            setExtraSystemScaleFactor(desktopScale)
            edt { ToolTipManager.sharedInstance().isEnabled = false }
            val backedUpProjectDirs = PROJECT_DIRS_PREFERENCE.get()
            val backedUpDefaultWindowLayout = DEFAULT_WINDOW_LAYOUT_PREFERENCE.get()
            val backedUpWindowLayouts = WINDOW_LAYOUTS_PREFERENCE.get()
            val backedUpOverlays = OVERLAYS_PREFERENCE.get()
            val backedUpDeliveryDestTemplates = DELIVERY_DEST_TEMPLATES_PREFERENCE.get()
            PROJECT_DIRS_PREFERENCE.set(emptyList())
            DEFAULT_WINDOW_LAYOUT_PREFERENCE.set("null")
            WINDOW_LAYOUTS_PREFERENCE.set(emptyList())
            OVERLAYS_PREFERENCE.set(emptyList())
            DELIVERY_DEST_TEMPLATES_PREFERENCE.set(emptyList())
            try {
                generate()
                edt { Window.getWindows().forEach(Window::dispose) }
                sleep(100)
            } finally {
                setExtraSystemScaleFactor(1.0)
                edt { ToolTipManager.sharedInstance().isEnabled = true }
                PROJECT_DIRS_PREFERENCE.set(backedUpProjectDirs)
                DEFAULT_WINDOW_LAYOUT_PREFERENCE.set(backedUpDefaultWindowLayout)
                WINDOW_LAYOUTS_PREFERENCE.set(backedUpWindowLayouts)
                OVERLAYS_PREFERENCE.set(backedUpOverlays)
                DELIVERY_DEST_TEMPLATES_PREFERENCE.set(backedUpDeliveryDestTemplates)
            }
        }
        masterCtrl = null
    }

    protected fun addWelcomeWindow(fullscreen: Boolean = false) {
        edt { masterCtrl!!.showWelcomeFrame() }
        welcomeWin = BackedVirtualWindow(welcomeFrame)
        dt.add(welcomeWin)
        sleep(500)
        edt {
            welcomeFrame.preferences_start_setUILocaleWish(LocaleWish.System)
            welcomeFrame.preferences_start_setCheckForUpdates(true)
            welcomeFrame.preferences_start_setAccounts(emptyList())
        }
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
        trees: List<DockingFrame.Tree> = dockedTrees,
        fullscreenPrjWin: Boolean = true,
        styWinSplitRatio: Double = 0.25,
        prepareProjectDir: (() -> Unit)? = null
    ) {
        val existingProjectCtrl = masterCtrl!!.leakedProjectCtrls.lastOrNull()
        projectCtrl = if (existingProjectCtrl != null) existingProjectCtrl else {
            tryCopyTemplate(projectDir, template(locale), CsvFormat)
            prepareProjectDir?.invoke()
            if (fullscreenPrjWin)
                trees[0].bounds = dt.fullscreen
            edt { masterCtrl!!.openProject(projectDir, null, trees) }
            sleep(1500)
            masterCtrl!!.leakedProjectCtrls.last()
        }
        prjWin = BackedVirtualWindow(dok)
        dt.add(prjWin)

        sleep(500)
        if (fullscreenPrjWin && existingProjectCtrl != null) {
            dt.fullscreen(prjWin)
            sleep(500)
        }
        edt {
            plyCtl.leakedPlayButton.actionListeners.forEach(plyCtl.leakedPlayButton::removeActionListener)
            styDok.leakedSplitPane.setDividerLocation(styWinSplitRatio)
        }
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
    protected val prefsPanel get() = welcomeFrame.panel.preferencesPanel

    protected lateinit var projectCtrl: ProjectController; private set
    protected lateinit var prjWin: BackedVirtualWindow; private set

    protected val dok get() = projectCtrl.projectFrame
    protected val tolDok get() = projectCtrl.leakedToolbarDockable
    protected val preDok get() = projectCtrl.leakedPreviewDockable
    protected val logDok get() = projectCtrl.leakedLogDockable
    protected val styDok get() = projectCtrl.stylingDockable
    protected val plyDok get() = projectCtrl.leakedPlaybackDockable
    protected val dlvDok get() = projectCtrl.leakedDeliveryDockable
    protected fun prjImagePnl(pageIdx: Int) = preDok.leakedImagePanels[pageIdx]
    protected val prjCtl get() = tolDok.leakedPlaybackControls
    protected val plyCtl get() = plyDok.leakedControlsPanel
    protected val styTree get() = styDok.leakedStylingTree
    protected val styGlobForm get() = styDok.leakedGlobalForm
    protected val styPageForm get() = styDok.leakedPageStyleForm
    protected val styContForm get() = styDok.leakedContentStyleForm
    protected val styLetrForm get() = styDok.leakedLetterStyleForm
    protected val styPictForm get() = styDok.leakedPictureStyleForm
    protected fun styLayrForm(i: Int): StyleForm<Layer> =
        ((styLayrPnl(i).getComponent(6) as JPanel).getComponent(0) as StyleForm<*>).castToStyle(Layer::class.java)

    protected val styIncUnitVGap
        get() = styGlobForm.getWidgetFor(Global::unitVGapPx.st()).components[0].getComponent(0)!!
    protected val styRuntime get() = styGlobForm.getWidgetFor(Global::runtimeFrames.st()).components[1] as JSpinner
    protected val styDecRuntime get() = styRuntime.getComponent(1)!!
    protected val styGridCols get() = styContForm.getWidgetFor(ContentStyle::gridCols.st()).components[0] as JSpinner
    protected val styIncGridCols get() = styGridCols.getComponent(0)!!
    protected val styDecGridCols get() = styGridCols.getComponent(1)!!
    protected val styFlowSep
        get() = styContForm.getWidgetFor(ContentStyle::flowSeparator.st()).components[0] as JTextComponent
    protected val styLetrFormScrollBar get() = (styLetrForm.parent.parent as JScrollPane).verticalScrollBar
    protected val styIncFontHeight
        get() = styLetrForm.getWidgetFor(LetterStyle::heightPx.st()).components[0].getComponent(0)!!
    protected val styLayrList
        get() = styLetrForm.getWidgetFor(LetterStyle::layers.st()).components[0] as JPanel
    protected val styPicHeight
        get() = ((styPictForm.getWidgetFor(PictureStyle::heightPx.st()).components[1] as JSpinner).editor
                as JSpinner.NumberEditor).textField

    protected fun styLayrAddBtn(i: Int) = styLayrList.let { it.getComponent(it.componentCount - 1 - 2 * i) } as JButton
    protected fun styLayrPnl(i: Int) = styLayrList.let { it.getComponent(it.componentCount - 2 - 2 * i) } as JPanel
    protected fun styLayrGrip(i: Int) = styLayrPnl(i).getComponent(0)
    protected fun styLayrNameField(i: Int) = styLayrPnl(i).getComponent(2) as JTextField
    protected fun styLayrAdvancedBtn(i: Int) = styLayrPnl(i).getComponent(3) as JToggleButton
    protected fun styLayrDelBtn(i: Int) = styLayrPnl(i).getComponent(4) as JButton

    protected val dlvFormats get() = dlvDok.leakedConfigForm.leakedFormatWidget.components[0] as JComboBox<*>
    protected val dlvProfiles get() = dlvDok.leakedConfigForm.leakedProfileWidget.components[0] as JComboBox<*>
    protected val dlvDestTempl get() = dlvDok.leakedConfigForm.leakedDestinationWidget.components[0] as JButton
    protected val dlvTranspar get() = dlvDok.leakedConfigForm.leakedTransparencyWidget.components[0] as JComboBox<*>
    protected val dlvSpaceScal get() = dlvDok.leakedConfigForm.leakedSpatialScalingWidget.components[0] as JComboBox<*>
    protected val dlvScan get() = dlvDok.leakedConfigForm.leakedScanWidget.components[0] as JComboBox<*>
    protected val dlvPrimaries get() = dlvDok.leakedConfigForm.leakedPrimariesWidget.components[0] as JComboBox<*>
    protected val dlvTransfer get() = dlvDok.leakedConfigForm.leakedTransferWidget.components[0] as JComboBox<*>

    protected lateinit var optionPaneWin: BackedVirtualWindow; private set
    protected lateinit var optionPaneDialog: JDialog; private set

}


class Screencast(
    private val writeFrame: (BufferedImage) -> Unit,
    private val fps: FPS,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val desktop: VirtualDesktop,
    private val desktopScale: Double,
    private val hold: Int,
    private val captionHeight: Int,
    private val captionGap: Int
) {

    companion object {
        @Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
        private val CAPTION_FONT = useResourceStream("/fonts/Titillium-RegularUpright.otf", Font::createFonts)[0]
            .deriveFont(48f)
            .let(FontUtilities::getCompositeFontUIResource)
    }

    private var caption = mutableListOf<TextLayout>()

    fun frame(action: (() -> Unit)? = null) {
        action?.invoke()
        desktop.tick(1.0 / fps.frac)
        writeFrame(BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Paint wallpaper
            g2.color = Color(24, 24, 24)
            g2.fillRect(0, 0, imageWidth, imageHeight - captionHeight)
            // Paint desktop
            desktop.paint(ScaledGraphics2D(g2, desktopScale))
            // Paint caption
            g2.color = Color.WHITE
            var captionY = (imageHeight - captionHeight + captionGap).toFloat()
            for (line in caption) {
                captionY += line.ascent
                line.draw(g2, (imageWidth - line.advance) / 2f, captionY)
                captionY += line.descent + line.leading
            }
        })
    }

    fun hold(millis: Int = hold, action: (() -> Unit)? = null) {
        repeat(ceil(fps.frac * millis / 1000.0).toInt()) { frame(action) }
    }

    fun mouseTo(point: Point, holdMillis: Int = hold) {
        desktop.mouseTo(point)
        while (desktop.isMouseMoving())
            frame()
        hold(holdMillis)
    }

    fun click(holdAfterMillis: Int = hold, holdDuringMillis: Int = 100, holdAction: (() -> Unit)? = null) {
        desktop.mouseDown()
        hold(holdDuringMillis, holdAction)
        desktop.mouseUp()
        hold(holdAfterMillis, holdAction)
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
        val prefix = cur.commonPrefixWith(text).length
        for (n in cur.length - 1 downTo prefix) {
            set(cur.substring(0, n))
            if (n != prefix)
                hold(50)
        }
        hold()
        for (n in prefix + 1..text.length) {
            set(text.substring(0, n))
            if (n != text.length)
                hold(70)
        }
    }

    fun caption(l10nKey: String, holdAction: (() -> Unit)? = null) {
        val text = l10nDemo(l10nKey)
        val attrs = mapOf(
            TextAttribute.FONT to CAPTION_FONT,
            TextAttribute.KERNING to TextAttribute.KERNING_ON,
            TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON
        )
        for (ratio in floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f)) {
            caption.clear()
            val lbm = LineBreakMeasurer(AttributedString(text, attrs).iterator, FontRenderContext(null, true, true))
            while (lbm.position != text.length)
                caption.add(lbm.nextLayout(imageWidth * ratio))
            if (caption.size <= 2)
                break
        }
        check(caption.size <= 2) { "Caption '$l10nKey' has ${caption.size} lines (only 2 are allowed)." }
        hold(1000 + 35 * text.codePoints().map { c -> if (Character.isIdeographic(c)) 4 else 1 }.sum(), holdAction)
        caption.clear()
    }

}
