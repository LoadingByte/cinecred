package com.loadingbyte.cinecred.ui.view.welcome

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.PRESET_GLOBAL
import com.loadingbyte.cinecred.project.label
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.ui.comms.CreditsLocation
import com.loadingbyte.cinecred.ui.comms.ProjectsCard
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.nio.file.Path
import java.util.*
import javax.swing.*
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull


class ProjectsPanel(private val welcomeCtrl: WelcomeCtrlComms) : JPanel() {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedStartPanel: JPanel
    @Deprecated("ENCAPSULATION LEAK") val leakedStartCreateButton: JButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStartOpenButton: JButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStartDropLabel: JLabel
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgResWidget get() = createConfigureForm.resolutionWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgLocWidget get() = createConfigureForm.creditsLocationWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgFormatWidget get() = createConfigureForm.creditsFormatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgAccWidget get() = createConfigureForm.creditsAccountWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgDoneButton get() = createConfigureDoneButton
    // =========================================

    private val cards = CardLayout().also { layout = it }

    private val startMemorizedPanel: JPanel
    private val createConfigureProjectDirLabel = JLabel()
    private val createConfigureForm: CreateConfigureForm
    private val createConfigureDoneButton: JButton
    private val createWaitErrorTextArea: JTextArea
    private val createWaitResponseTextArea: JTextArea

    init {
        val startCreateButton = makeOpenButton(l10n("ui.projects.create"), ADD_ICON).apply {
            addActionListener { welcomeCtrl.projects_start_onClickCreate() }
        }
        val startOpenButton = makeOpenButton(l10n("ui.projects.open"), FOLDER_ICON).apply {
            addActionListener { welcomeCtrl.projects_start_onClickOpen() }
        }

        startMemorizedPanel = JPanel(MigLayout("insets 0, wrap", "[grow,fill]")).apply { background = null }
        val startMemorizedScrollPane = JScrollPane(startMemorizedPanel).apply {
            border = null
            background = null
            viewport.background = null
            verticalScrollBar.unitIncrement = 10
            verticalScrollBar.blockIncrement = 100
        }

        val startDropLabel = JLabel(l10n("ui.projects.drop")).apply {
            putClientProperty(STYLE, "font: bold \$h0.font; foreground: #666")
        }

        val startPanel = JPanel(MigLayout("insets 15, wrap")).apply {
            fun setHover(hover: Boolean) {
                val color = if (hover) Color(120, 120, 120) else Color(80, 80, 80)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(10, 10, 10, 10),
                    BorderFactory.createDashedBorder(color, 5f, 4f, 4f, false)
                )
            }
            setHover(false)
            background = null
            add(startCreateButton, "split 2, center")
            add(startOpenButton)
            add(JSeparator(), "growx, shrink 0 0")
            add(startMemorizedScrollPane, "grow, push")
            add(startDropLabel, "center, gaptop 20, gapbottom 20")
            // Add the drag-and-drop handler.
            transferHandler = OpenTransferHandler()
            // Highlight the area when the user hovers over it during drag-and-drop.
            dropTarget.addDropTargetListener(object : DropTargetAdapter() {
                override fun dragEnter(dtde: DropTargetDragEvent) = setHover(true)
                override fun dragExit(dte: DropTargetEvent) = setHover(false)
                override fun drop(dtde: DropTargetDropEvent) = setHover(false)
            })
        }

        val createConfigureBackButton = JButton(l10n("back"), ARROW_LEFT_ICON).apply {
            addActionListener { welcomeCtrl.projects_createConfigure_onClickBack() }
        }
        createConfigureDoneButton = JButton(l10n("ui.projects.create"), ADD_ICON)
        createConfigureForm = CreateConfigureForm().apply { background = null }
        createConfigureDoneButton
            .addActionListener {
                welcomeCtrl.projects_createConfigure_onClickDone(
                    createConfigureForm.localeWidget.value,
                    createConfigureForm.resolutionWidget.value,
                    createConfigureForm.fpsWidget.value,
                    createConfigureForm.timecodeFormatWidget.value,
                    createConfigureForm.contentWidget.value,
                    createConfigureForm.creditsLocationWidget.value,
                    createConfigureForm.creditsFormatWidget.value,
                    createConfigureForm.creditsAccountWidget.value.getOrNull(),
                    createConfigureForm.creditsFilenameWidget.value
                )
            }

        val createConfigurePanel = JPanel(MigLayout("insets 20, gapy 10, wrap")).apply {
            background = null
            add(JLabel(l10n("ui.projects.create.projectDir"), FOLDER_ICON, JLabel.LEADING), "split 2, center")
            add(createConfigureProjectDirLabel)
            add(newLabelTextArea(l10n("ui.projects.create.prompt")), "growx")
            add(createConfigureForm, "grow, push")
            add(createConfigureBackButton, "split 2, right")
            add(createConfigureDoneButton)
        }

        createWaitErrorTextArea = newLabelTextArea(l10n("ui.projects.create.error")).apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        createWaitResponseTextArea = newLabelTextArea().apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        val createWaitCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.projects_createWait_onClickCancel() }
        }
        val createWaitPanel = JPanel(MigLayout("insets 20, wrap", "", "[][][]push[]")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.projects.create.wait")), "growx, pushx")
            add(createWaitErrorTextArea, "growx")
            add(createWaitResponseTextArea, "growx")
            add(createWaitCancelButton, "right")
        }

        add(startPanel, ProjectsCard.START.name)
        add(createConfigurePanel, ProjectsCard.CREATE_CONFIGURE.name)
        add(createWaitPanel, ProjectsCard.CREATE_WAIT.name)

        @Suppress("DEPRECATION")
        leakedStartPanel = startPanel
        @Suppress("DEPRECATION")
        leakedStartCreateButton = startCreateButton
        @Suppress("DEPRECATION")
        leakedStartOpenButton = startOpenButton
        @Suppress("DEPRECATION")
        leakedStartDropLabel = startDropLabel
    }

    private fun makeOpenButton(text: String, icon: Icon) = JButton(text, icon).apply {
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 8
        putClientProperty(STYLE, "arc: 0")
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun projects_setCard(card: ProjectsCard) {
        cards.show(this, card.name)
    }

    fun projects_start_setMemorized(projectDirs: List<Path>) {
        startMemorizedPanel.removeAll()
        for (projectDir in projectDirs) {
            val btn = makeOpenButton(
                "<html>${projectDir.fileName}<br><font color=#888>$projectDir</font></html>",
                FOLDER_ICON
            )
            btn.addActionListener { welcomeCtrl.projects_start_onClickOpenMemorized(projectDir) }
            startMemorizedPanel.add(btn)
        }
    }

    fun projects_createConfigure_setProjectDir(prjDir: Path) {
        createConfigureProjectDirLabel.text = prjDir.pathString
    }

    fun projects_createConfigure_setAccounts(accounts: List<Account>) {
        createConfigureForm.creditsAccountWidget.items = accounts
    }

    fun projects_createConfigure_setCreditsFilename(filename: String) {
        createConfigureForm.creditsFilenameWidget.value = filename
    }

    fun projects_createWait_setError(error: String?) {
        val hasError = error != null
        createWaitErrorTextArea.isVisible = hasError
        createWaitResponseTextArea.isVisible = hasError
        createWaitResponseTextArea.text = error ?: ""
    }


    private inner class OpenTransferHandler : TransferHandler() {

        override fun canImport(support: TransferSupport) =
            support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support))
                return false

            val transferData = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<Any?>
            if (transferData.isEmpty())
                return false

            val path = (transferData[0] as File).toPathSafely() ?: return false
            // Note: As the drag-and-drop thread is not the EDT thread, we use SwingUtilities.invokeLater()
            // to make sure all action happens in the EDT thread.
            SwingUtilities.invokeLater { welcomeCtrl.projects_start_onDrop(path) }
            return true
        }

    }


    private inner class CreateConfigureForm : EasyForm(insets = false, noticeArea = false, constLabelWidth = true) {

        val localeWidget = addWidget(
            l10n("ui.styling.global.locale"),
            ComboBoxWidget(Locale::class.java, TRANSLATED_LOCALES, toString = { it.displayName })
        )

        val resolutionWidget = addWidget(
            l10n("ui.styling.global.resolution"),
            ResolutionWidget()
        )

        val fpsWidget = addWidget(
            l10n("ui.styling.global.fps"),
            FPSWidget()
        )

        val timecodeFormatWidget = addWidget(
            l10n("ui.styling.global.timecodeFormat"),
            ComboBoxWidget(TimecodeFormat::class.java, emptyList(), TimecodeFormat::label)
        )

        val contentWidget = addWidget(
            l10n("ui.projects.create.content"),
            ToggleButtonGroupWidget(
                listOf(true, false),
                toLabel = {
                    when (it) {
                        true -> l10n("ui.projects.create.content.sample")
                        false -> l10n("ui.projects.create.content.blank")
                    }
                }
            ),
            isEnabled = { creditsLocationWidget.value != CreditsLocation.SKIP }
        )

        val creditsLocationWidget = addWidget(
            l10n("ui.projects.create.creditsLocation"),
            ToggleButtonGroupWidget(
                CreditsLocation.entries,
                toLabel = { l10n("ui.projects.create.creditsLocation.${it.name.lowercase()}") }
            )
        )

        val creditsFormatWidget = addWidget(
            l10n("ui.projects.create.creditsFormat"),
            ComboBoxWidget(
                SpreadsheetFormat::class.java, SPREADSHEET_FORMATS, widthSpec = WidthSpec.WIDE,
                toString = { "${it.label} (Credits.${it.fileExt})" }
            ),
            isVisible = { creditsLocationWidget.value == CreditsLocation.LOCAL }
        )

        val creditsAccountWidget = addWidget(
            l10n("ui.projects.create.creditsAccount"),
            OptionalComboBoxWidget(
                Account::class.java, emptyList(), widthSpec = WidthSpec.WIDE,
                toString = { "${it.service.product} \u2013 ${it.id}" }
            ),
            isVisible = { creditsLocationWidget.value == CreditsLocation.SERVICE },
            verify = { if (it.isEmpty) Notice(Severity.ERROR, l10n("ui.projects.create.noAccount")) else null }
        )

        val creditsFilenameWidget = addWidget(
            l10n("ui.projects.create.creditsFilename"),
            TextWidget(),
            isVisible = { creditsLocationWidget.value == CreditsLocation.SERVICE }
        )

        init {
            localeWidget.value = Locale.getDefault()
            resolutionWidget.value = PRESET_GLOBAL.resolution
            fpsWidget.value = PRESET_GLOBAL.fps
            timecodeFormatWidget.value = PRESET_GLOBAL.timecodeFormat
            // Populate the timecode format combo box.
            onChange(fpsWidget)
        }

        override fun onChange(widget: Widget<*>) {
            super.onChange(widget)
            if (widget == fpsWidget)
                timecodeFormatWidget.items = fpsWidget.value.canonicalTimecodeFormats.toList()
            createConfigureDoneButton.isEnabled = isErrorFree
        }

    }

}
