package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.FOLDER_ICON
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.*
import kotlin.io.path.isDirectory


class OpenPanel(private val ctrl: WelcomeController) : JPanel() {

    // ========== HINT OWNERS ==========
    val browseHintOwner: Component
    val dropHintOwner: Component
    // =================================

    init {
        val browseButton = makeOpenButton(l10n("ui.open.browse")).apply { addActionListener { browse() } }
        browseHintOwner = browseButton

        val memorizedPanel = JPanel(MigLayout("insets 0, wrap", "[grow,fill]")).apply { background = null }
        for (projectDir in PreferencesStorage.memorizedProjectDirs) {
            val btn = makeOpenButton("<html>${projectDir.fileName}<br><font color=#888>$projectDir</font></html>")
            btn.addActionListener { ctrl.tryOpenProject(projectDir) }
            memorizedPanel.add(btn)
        }
        val memorizedScrollPane = JScrollPane(memorizedPanel).apply {
            border = null
            background = null
            viewport.background = null
        }

        val dropLabel = JLabel(l10n("ui.open.drop")).apply {
            dropHintOwner = this
            putClientProperty(STYLE, "font: bold \$h0.font; foreground: #666")
        }

        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
            BorderFactory.createDashedBorder(Color(80, 80, 80), 5f, 4f, 4f, false)
        )
        layout = MigLayout("insets 15lp, wrap")
        add(browseButton, "growx")
        add(JSeparator(), "growx")
        add(memorizedScrollPane, "grow, push")
        add(dropLabel, "center, gaptop 40lp, gapbottom 20lp")

        // Add the drag-and-drop handler.
        transferHandler = OpenTransferHandler()
    }

    private fun makeOpenButton(text: String) = JButton(text, FOLDER_ICON).apply {
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 8
        putClientProperty(STYLE, "arc: 0")
        putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
    }

    private fun browse() {
        val fc = JFileChooser()
        fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        PreferencesStorage.memorizedProjectDirs.firstOrNull()?.let { fc.selectedFile = it.toFile() }
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            ctrl.tryOpenProject(fc.selectedFile.toPath())
    }


    private inner class OpenTransferHandler : TransferHandler() {

        override fun canImport(support: TransferSupport) =
            support.dataFlavors.any { it.isFlavorJavaFileListType }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support))
                return false

            val transferData = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<Any?>
            if (transferData.isEmpty())
                return false

            // Note: As the drag-and-drop thread is not the EDT thread, we use SwingUtilities.invokeLater()
            // to make sure all action happens in the EDT thread.
            val file = (transferData[0] as File).toPath()
            if (file.isDirectory())
                SwingUtilities.invokeLater { ctrl.tryOpenProject(file) }
            else if (file.parent != null)
                SwingUtilities.invokeLater { ctrl.tryOpenProject(file.parent) }

            return true
        }

    }

}
