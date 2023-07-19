package com.loadingbyte.cinecred.ui.view.welcome

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.toPathSafely
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.ui.comms.CreditsLocation
import com.loadingbyte.cinecred.ui.comms.ProjectsCard
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.filechooser.FileView
import javax.swing.plaf.basic.BasicFileChooserUI
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull


class ProjectsPanel(private val welcomeCtrl: WelcomeCtrlComms) : JPanel() {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedStartPanel: JPanel
    @Deprecated("ENCAPSULATION LEAK") val leakedStartCreateButton: JButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStartOpenButton: JButton
    @Deprecated("ENCAPSULATION LEAK") val leakedStartDropLabel: JLabel
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgFormatWidget get() = createConfigureForm.creditsFormatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedCreCfgDoneButton get() = createConfigureDoneButton
    // =========================================

    private val cards = CardLayout().also { layout = it }

    private val startMemorizedPanel: JPanel
    private val openBrowseFileChooser: JFileChooser
    private val openBrowseDoneButton: JButton
    private val createBrowseFileChooser: JFileChooser
    private val createBrowseNextButton: JButton
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

        openBrowseFileChooser = makeProjectDirChooser()
        val openBrowseCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.projects_openBrowse_onClickCancel() }
        }
        openBrowseDoneButton = JButton(l10n("ui.projects.open"), FOLDER_ICON).apply {
            addActionListener { welcomeCtrl.projects_openBrowse_onClickDone() }
        }

        // Show the Cinecred icon for project dirs. On Windows, some shortcuts are virtual directories like "PC" and
        // throw an exception when converting them to Path. As such, we need to filter them out.
        openBrowseFileChooser.fileView = object : FileView() {
            override fun getIcon(f: File) =
                if (openBrowseFileChooser.fileSystemView.isFileSystem(f) &&
                    welcomeCtrl.projects_openBrowse_shouldShowAppIcon(f.toPath())
                ) CINECRED_ICON else null
        }

        openBrowseFileChooser.addRealSelectedFileListener {
            welcomeCtrl.projects_openBrowse_onChangeSelection(openBrowseFileChooser.realSelectedPath)
        }

        // Notify the controller when the user double-clicks (which causes the file chooser to navigate).
        openBrowseFileChooser.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY) { e ->
            val dir = e.newValue as File
            // Once again, watch out for virtual directories like "PC", which we cannot convert to Path.
            if (!openBrowseFileChooser.fileSystemView.isFileSystem(dir))
                return@addPropertyChangeListener
            if (welcomeCtrl.projects_openBrowse_onDoubleClickDir(dir.toPath())) {
                // If requested, cancel navigating into the dir.
                openBrowseFileChooser.changeToParentDirectory()
                openBrowseFileChooser.selectedFile = dir
            }
        }

        val openBrowsePanel = JPanel(MigLayout("insets 20, gapy 15, wrap")).apply {
            background = null
            add(openBrowseFileChooser, "grow, push")
            add(openBrowseCancelButton, "split 2, right")
            add(openBrowseDoneButton)
        }

        createBrowseFileChooser = makeProjectDirChooser()
        val createBrowseCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.projects_createBrowse_onClickCancel() }
        }
        createBrowseNextButton = JButton(l10n("next"), ARROW_RIGHT_ICON).apply {
            addActionListener { welcomeCtrl.projects_createBrowse_onClickNext() }
        }

        createBrowseFileChooser.addRealSelectedFileListener {
            welcomeCtrl.projects_createBrowse_onChangeSelection(createBrowseFileChooser.realSelectedPath)
        }

        val createBrowsePanel = JPanel(MigLayout("insets 20, gapy 15, wrap")).apply {
            background = null
            add(createBrowseFileChooser, "grow, push")
            add(createBrowseCancelButton, "split 2, right")
            add(createBrowseNextButton)
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
                    createConfigureForm.scaleWidget.value,
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
        add(openBrowsePanel, ProjectsCard.OPEN_BROWSE.name)
        add(createBrowsePanel, ProjectsCard.CREATE_BROWSE.name)
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

    private fun makeProjectDirChooser() = JFileChooser().also { fc ->
        fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        fc.border = null
        fc.controlButtonsAreShown = false

        fc.minimumSize = Dimension(0, 0)

        // Remove the file type selection.
        val bottomPanel = (fc.getComponent(0) as JPanel).getComponent(3) as JPanel
        bottomPanel.remove(2)  // Remove the file type panel.
        bottomPanel.remove(1)  // Remove the vertical strut.

        fun nullBackground(comp: Component) {
            if (comp !is JButton && comp !is JToggleButton && comp !is JComboBox<*> && comp !is JTextField)
                comp.background = null
            if (comp is Container)
                comp.components.forEach(::nullBackground)
        }
        nullBackground(fc)
    }

    /**
     * Gets around various quirks of the file chooser to determine the file really selected by the user instead of
     * relying on the selectedFile property. Essentially, we determine the file from the filename currently visible in
     * the corresponding text field. To do this, we follow the same steps as used by the official Swing code.
     *
     * We also make sure that the file determined this way is real and not virtual (checked by isFileSystem()) and lies
     * on a ready device (checked by the try-catch).
     *
     * There is still one case not properly handled by this method, namely if the user is currently in a real directory
     * and first selects a real file and then a virtual file. In that case, we return the current directory instead of
     * null. Luckily, this actually mirrors the behavior of the filename text field. Also, handling this rare case
     * appears to be difficult without exploiting internal members.
     */
    private val JFileChooser.realSelectedPath: Path?
        get() {
            // Adapted from BasicFileChooserUI.ApproveSelectionAction.actionPerformed().
            val filename = (ui as BasicFileChooserUI).fileName
            if (filename.isNullOrBlank())
                return null
            var selF = fileSystemView.createFileObject(filename)
            if (!selF.isAbsolute)
                selF = fileSystemView.getChild(currentDirectory, filename)
            if (!fileSystemView.isFileSystem(selF))
                return null
            // Note: Some disallowed paths only now throw an exception when converting them to Path objects. This safe
            // conversion method catches that exception.
            return selF.toPathSafely()
        }

    /**
     * This method should be used instead of directly adding a property change listener to the selected file because one
     * also needs to listen for changes to the current directory property as the selected file can actually change
     * without triggering the listener when the user ascends the directory hierarchy.
     */
    private fun JFileChooser.addRealSelectedFileListener(listener: () -> Unit) {
        val eventHandler = PropertyChangeListener { listener() }
        addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY, eventHandler)
        addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, eventHandler)

        val bottomPanel = (getComponent(0) as JPanel).getComponent(3) as JPanel
        val fileNameTextField = (bottomPanel.getComponent(0) as JPanel).getComponent(1) as JTextField
        fileNameTextField.document.addDocumentListener { listener() }
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun projects_setCard(card: ProjectsCard) {
        // Notify the controller about the initially selected directory and refresh the directory listing.
        if (card == ProjectsCard.OPEN_BROWSE) {
            welcomeCtrl.projects_openBrowse_onChangeSelection(openBrowseFileChooser.realSelectedPath)
            openBrowseFileChooser.rescanCurrentDirectory()
        } else if (card == ProjectsCard.CREATE_BROWSE) {
            welcomeCtrl.projects_createBrowse_onChangeSelection(createBrowseFileChooser.realSelectedPath)
            createBrowseFileChooser.rescanCurrentDirectory()
        }
        // Show the card.
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

    // @formatter:off
    fun projects_openBrowse_setCurrentDir(dir: Path) { openBrowseFileChooser.currentDirectory = dir.toFile() }
    fun projects_openBrowse_setDoneEnabled(enabled: Boolean) { openBrowseDoneButton.isEnabled = enabled }
    fun projects_createBrowse_setCurrentDir(dir: Path) { createBrowseFileChooser.currentDirectory = dir.toFile() }
    fun projects_createBrowse_setSelection(dir: Path) {
        createBrowseFileChooser.fullySetSelectedFile(dir.toFile())
    }
    fun projects_createBrowse_setNextEnabled(enabled: Boolean) { createBrowseNextButton.isEnabled = enabled }
    fun projects_createConfigure_setProjectDir(prjDir: Path) { createConfigureProjectDirLabel.text = prjDir.pathString }
    fun projects_createConfigure_setAccounts(accounts: List<Account>) {
        createConfigureForm.creditsAccountWidget.items = accounts
    }
    fun projects_createConfigure_setCreditsFilename(filename: String) {
        createConfigureForm.creditsFilenameWidget.value = filename
    }
    // @formatter:on

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


    private inner class CreateConfigureForm : EasyForm(insets = false, noticeArea = true, constLabelWidth = false) {

        val localeWidget = addWidget(
            l10n("ui.styling.global.locale"),
            ComboBoxWidget(
                Locale::class.java, TRANSLATED_LOCALES, widthSpec = WidthSpec.WIDE,
                toString = { it.displayName }
            )
        )

        val scaleWidget = addWidget(
            l10n("ui.styling.global.resolution"),
            ToggleButtonGroupWidget(
                listOf(1, 2),
                toLabel = { "${it * 2}K" }
            )
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
        }

        override fun onChange(widget: Widget<*>) {
            super.onChange(widget)
            createConfigureDoneButton.isEnabled = isErrorFree
        }

    }

}
