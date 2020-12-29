package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.delivery.getDurationFrames
import com.loadingbyte.cinecred.delivery.setHighQuality
import com.loadingbyte.cinecred.drawer.BODY_GUIDE_COLOR
import com.loadingbyte.cinecred.drawer.CTRLINE_GUIDE_COLOR
import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.drawer.HEAD_TAIL_GUIDE_COLOR
import com.loadingbyte.cinecred.project.Page
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.project.Styling
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.toString2
import net.miginfocom.swing.MigLayout
import java.awt.*
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.ceil


object EditPanel : JPanel() {

    private val unsavedStylingLabel = JLabel("(Unsaved Changes)").apply {
        isVisible = false
        font = font.deriveFont(font.size * 0.85f)
    }
    private val showGuidesCheckBox = JCheckBox("Show Guides", true).apply {
        toolTipText = "<html>Legend:<br>" +
                "<font color=\"${CTRLINE_GUIDE_COLOR.darker().toString2()}\">Center lines</font><br>" +
                "<font color=\"${BODY_GUIDE_COLOR.darker().toString2()}\">Body column and body flow bounds</font><br>" +
                "<font color=\"${HEAD_TAIL_GUIDE_COLOR.darker().toString2()}\">Head and tail bounds</font></html>"
        addActionListener {
            for (scrollPane in pageTabs.components)
                ((scrollPane as JScrollPane).viewport.view as DeferredImagePanel).showGuides = isSelected
        }
    }
    private val durationLabel = JLabel()
    private val pageTabs = JTabbedPane()

    init {
        val reloadCreditsButton = JButton("credits.csv", UP_FOLDER_ICON).apply {
            toolTipText = "Manually reload credits.csv from disk"
            addActionListener { Controller.reloadCreditsFile() }
        }
        val reloadStylingButton = JButton("Styling", UP_FOLDER_ICON).apply {
            toolTipText = "Reload styling from disk and discard changes"
            addActionListener {
                if (unsavedStylingLabel.isVisible) {
                    val msg = "There are unsaved changes to the styling. Discard changes and reload styling from disk?"
                    if (showConfirmDialog(MainFrame, msg, "Unsaved Changes", YES_NO_OPTION) == NO_OPTION)
                        return@addActionListener
                }
                Controller.reloadStylingFile()
                unsavedStylingLabel.isVisible = false
            }
        }
        val saveStylingButton = JButton("Styling", FLOPPY_ICON).apply {
            toolTipText = "Save styling to disk"
            addActionListener {
                Controller.saveStyling()
                unsavedStylingLabel.isVisible = false
            }
        }

        val topPanel = JPanel(MigLayout("", "[][]push[][][]push[]push[]")).apply {
            add(reloadCreditsButton)
            add(JLabel("(Auto-Reload Active)").apply { font = font.deriveFont(font.size * 0.85f) })
            add(reloadStylingButton)
            add(saveStylingButton)
            add(unsavedStylingLabel)
            add(showGuidesCheckBox)
            add(durationLabel)
            add(pageTabs, "newline, span, grow, push")
        }

        val logTable = JTable(LogTableModel).apply {
            // Disable cell selection because it looks weird with the custom WordWrapCellRenderer.
            cellSelectionEnabled = false
            // When manually the window, only the message column should be resized to accommodate
            // for the change. All free space should be absorbed by the message column.
            // Also prevent the user from individually resizing the columns and dragging them around.
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.apply {
                resizingAllowed = false
                reorderingAllowed = false
                resizingColumn = columnModel.getColumn(2)
            }
            // Center the line column.
            columnModel.getColumn(1).cellRenderer =
                DefaultTableCellRenderer().apply { horizontalAlignment = JLabel.CENTER }
            // Allow for word wrapping in the message column.
            columnModel.getColumn(2).cellRenderer = WordWrapCellRenderer
        }
        val logTablePanel = JPanel(MigLayout()).apply {
            add(JScrollPane(logTable), "grow, push")
        }

        val bottomSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, logTablePanel, EditStylingPanel)
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, topPanel, bottomSplitPane)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater {
            splitPane.setDividerLocation(0.6)
            bottomSplitPane.setDividerLocation(0.1)
        }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    fun onLoadStyling() {
        unsavedStylingLabel.isVisible = false
    }

    fun onEditStyling() {
        unsavedStylingLabel.isVisible = true
    }

    fun onTryOpenProjectDirOrExit(): Boolean =
        if (unsavedStylingLabel.isVisible) {
            val msg = "There are unsaved changes to the styling. Save changes now before closing the project?"
            when (showConfirmDialog(MainFrame, msg, "Unsaved Changes", YES_NO_CANCEL_OPTION)) {
                YES_OPTION -> {
                    Controller.saveStyling()
                    true
                }
                NO_OPTION -> true
                else /* Cancel option */ -> false
            }
        } else
            true

    fun updateProjectAndLog(
        styling: Styling,
        pages: List<Page>?,
        pageDefImages: List<DeferredImage>,
        log: List<ParserMsg>
    ) {
        if (pages == null)
            durationLabel.apply {
                text = null
                toolTipText = null
            }
        else {
            val fps = styling.global.fps.frac
            var durFrames = getDurationFrames(Project(styling, pages), pageDefImages)
            var durSeconds = (durFrames / fps).toInt()
            val durMinutes = durSeconds / 60
            durFrames -= (durSeconds * fps).toInt()
            durSeconds -= durMinutes * 60
            durationLabel.apply {
                text = "Duration: %02d:%02d+%02d".format(durMinutes, durSeconds, durFrames)
                toolTipText = "Total duration is $durMinutes minutes, $durSeconds seconds, and $durFrames frames."
            }
        }

        // Remember that tab which is currently selected so that we can reselect that one later.
        val selectedTabIdx = pageTabs.selectedIndex

        while (pageTabs.tabCount != 0)
            pageTabs.removeTabAt(0)

        for ((idx, pageDefImage) in pageDefImages.withIndex())
            pageTabs.addTab(
                (if (idx == 0) "Page " else "") + (idx + 1).toString(),
                PAGE_ICON,
                JScrollPane(
                    DeferredImagePanel(
                        pageDefImage, styling.global.widthPx.toFloat(),
                        styling.global.background, showGuidesCheckBox.isSelected
                    ),
                    JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                )
            )

        if (pageTabs.tabCount != 0)
            pageTabs.selectedIndex = selectedTabIdx.coerceIn(0, pageTabs.tabCount)

        LogTableModel.log = log.sortedWith { a, b ->
            b.severity.compareTo(a.severity).let { if (it != 0) it else a.lineNo.compareTo(b.lineNo) }
        }
    }


    private class DeferredImagePanel(
        val defImage: DeferredImage,
        val imageWidth: Float,
        val backgroundColor: Color,
        showGuides: Boolean
    ) : JPanel() {

        var showGuides = showGuides
            set(value) {
                field = value
                repaint()
            }

        // The scroll pane uses this information to decide on the length of the scrollbar.
        override fun getPreferredSize() =
            Dimension(parent.width, ceil(parent.width * defImage.height / imageWidth).toInt())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            // Copy the Graphics2D so that we can change rendering hints without affecting stuff outside this method.
            val g2 = g.create() as Graphics2D
            g2.setHighQuality()
            // Fill the background.
            g2.color = backgroundColor
            g2.fillRect(0, 0, width, preferredSize.height)
            // Draw a scaled-down version of the image to the panel.
            val scaledDefImage = DeferredImage()
            scaledDefImage.drawDeferredImage(defImage, 0f, 0f, width / imageWidth)
            // Don't used "pathified" text because that's slower and we don't need the best accuracy for the preview.
            scaledDefImage.materialize(g2, showGuides, DeferredImage.TextMode.TEXT)
        }

    }


    private object LogTableModel : AbstractTableModel() {

        var log: List<ParserMsg> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = log.size
        override fun getColumnCount() = 3

        override fun getColumnName(colIdx: Int) = when (colIdx) {
            0 -> "Severity"
            1 -> "Line"
            2 -> "Message"
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> SEVERITY_ICON[log[rowIdx].severity]!!
            1 -> log[rowIdx].lineNo
            // Replace ' with " in messages for better readability.
            2 -> log[rowIdx].msg.replace("'", "\"")
            else -> throw IllegalArgumentException()
        }

    }

}
