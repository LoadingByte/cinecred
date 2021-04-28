package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.FOLDER_ICON
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import javax.swing.*


object OpenPanel : JPanel() {

    private val memorizedPanel = JPanel(MigLayout("center, wrap 3")).apply {
        alignmentX = CENTER_ALIGNMENT
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
            BorderFactory.createDashedBorder(Color(100, 100, 100), 10f, 4f, 4f, false)
        )

        val selectButton = JButton(l10n("ui.open.browse"), FOLDER_ICON).apply {
            font = font.deriveFont(font.size * 1.15f)
            addActionListener { onSelectButton() }
        }

        val dropLabel = JLabel(l10n("ui.open.drop")).apply {
            foreground = Color(150, 150, 150)
            font = font.deriveFont(font.size * 2.5f).deriveFont(Font.BOLD)
        }

        // Add the memorized project panel, select button, and the drag-and-drop hint text.
        layout = MigLayout("center, center, wrap 1, gapy 60lp, hidemode 3", "center")
        add(memorizedPanel)
        add(selectButton)
        add(dropLabel)

        // Add the drag-and-drop handler.
        transferHandler = OpenTransferHandler
    }

    private fun onSelectButton() {
        val fc = JFileChooser()
        fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        OpenController.getMemorizedProjectDirs().firstOrNull()?.let { fc.selectedFile = it.toFile() }
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && fc.selectedFile.isDirectory)
            OpenController.tryOpenProject(fc.selectedFile.toPath())
    }

    fun onShow() {
        memorizedPanel.removeAll()
        for (projectDir in OpenController.getMemorizedProjectDirs()) {
            val btn = JButton(projectDir.fileName.toString(), FOLDER_ICON).apply {
                toolTipText = projectDir.toString()
                font = font.deriveFont(font.size * 1.15f)
                addActionListener { OpenController.tryOpenProject(projectDir) }
            }
            memorizedPanel.add(btn, "sizegroup")
        }
        memorizedPanel.isVisible = memorizedPanel.components.isNotEmpty()
    }


    private object OpenTransferHandler : TransferHandler() {

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
            if (Files.isDirectory(file))
                SwingUtilities.invokeLater { OpenController.tryOpenProject(file) }
            else if (file.parent != null)
                SwingUtilities.invokeLater { OpenController.tryOpenProject(file.parent) }

            return true
        }

    }

}
