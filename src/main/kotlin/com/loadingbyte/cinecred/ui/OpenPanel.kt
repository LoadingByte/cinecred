package com.loadingbyte.cinecred.ui

import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*


object OpenPanel : JPanel() {

    private var lastProjectDir: Path? = null

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
            BorderFactory.createDashedBorder(Color(100, 100, 100), 10f, 4f, 4f, false)
        )

        val selectButton = JButton("Select Project Folder", FOLDER_ICON).apply {
            alignmentX = CENTER_ALIGNMENT
            font = font.deriveFont(font.size * 1.25f)
        }
        selectButton.addActionListener {
            val fc = JFileChooser()
            fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            lastProjectDir?.let { fc.selectedFile = it.toFile() }
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && fc.selectedFile.isDirectory)
                openProjectDir(fc.selectedFile.toPath())
        }

        val dropLabel = JLabel("Drop Project Folder Here").apply {
            alignmentX = CENTER_ALIGNMENT
            foreground = Color.LIGHT_GRAY
            font = font.deriveFont(font.size * 2.5f)
        }

        // Add the select button and the drag'n'drop hint text.
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(Box.createVerticalGlue())
        add(selectButton)
        add(Box.createVerticalStrut(50))
        add(dropLabel)
        add(Box.createVerticalGlue())

        // Add the drag'n'drop handler.
        transferHandler = object : TransferHandler() {

            override fun canImport(support: TransferSupport) =
                support.dataFlavors.any { it.isFlavorJavaFileListType }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support))
                    return false

                val transferData = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<Any?>
                if (transferData.isEmpty())
                    return false

                val file = (transferData[0] as File).toPath()
                if (Files.isDirectory(file))
                    openProjectDir(file)
                else if (file.parent != null)
                    openProjectDir(file.parent)

                return true
            }

        }
    }

    private fun openProjectDir(projectDir: Path) {
        lastProjectDir = projectDir
        Controller.openProjectDir(projectDir)
    }

}
