package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.FOLDER_ICON
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import javax.swing.*


object OpenPanel : JPanel() {

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
            BorderFactory.createDashedBorder(Color(100, 100, 100), 10f, 4f, 4f, false)
        )

        val selectButton = JButton(l10n("ui.open.browse"), FOLDER_ICON).apply {
            alignmentX = CENTER_ALIGNMENT
            font = font.deriveFont(font.size * 1.25f)
        }
        selectButton.addActionListener {
            val fc = JFileChooser()
            fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            // TODO: lastProjectDir?.let { fc.selectedFile = it.toFile() }
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && fc.selectedFile.isDirectory)
                OpenController.tryOpenProject(fc.selectedFile.toPath())
        }

        val dropLabel = JLabel(l10n("ui.open.drop")).apply {
            alignmentX = CENTER_ALIGNMENT
            foreground = Color.LIGHT_GRAY
            font = font.deriveFont(font.size * 2.5f)
        }

        // Add the select button and the drag-and-drop hint text.
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(Box.createVerticalGlue())
        add(selectButton)
        add(Box.createVerticalStrut(50))
        add(dropLabel)
        add(Box.createVerticalGlue())

        // Add the drag-and-drop handler.
        transferHandler = object : TransferHandler() {

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

}
