package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.delivery.getRuntimeFrames
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.l10n
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.toString2
import net.miginfocom.swing.MigLayout
import org.apache.batik.ext.awt.image.GraphicsUtil
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.JOptionPane.*
import javax.swing.JScrollPane.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.roundToInt


object EditPanel : JPanel() {

    private val toggleEditStylingDialogButton = JToggleButton(EDIT_ICON).apply {
        isSelected = true
        toolTipText = l10n("ui.edit.toggleStyling")
        addActionListener {
            Controller.setEditStylingDialogVisible(isSelected)
        }
    }
    private val unsavedStylingLabel = JLabel(l10n("ui.edit.unsavedChanges")).apply {
        isVisible = false
        font = font.deriveFont(font.size * 0.8f)
    }
    private val layoutGuidesToggleButton = JToggleButton(l10n("ui.edit.layoutGuides"), true).apply {
        toolTipText = l10n(
            "ui.edit.layoutGuidesTooltip",
            CTRLINE_GUIDE_COLOR.darker().toString2(), BODY_ELEM_GUIDE_COLOR.toString2(),
            BODY_WIDTH_GUIDE_COLOR.brighter().toString2(), HEAD_TAIL_GUIDE_COLOR.toString2()
        )
        addActionListener {
            for (scrollPane in pageTabs.components)
                ((scrollPane as JScrollPane).viewport.view as PagePreviewPanel).showGuides = isSelected
        }
    }
    private val safeAreasToggleButton = JToggleButton(l10n("ui.edit.safeAreas"), false).apply {
        toolTipText = l10n("ui.edit.safeAreasTooltip")
        addActionListener {
            for (scrollPane in pageTabs.components)
                ((scrollPane as JScrollPane).viewport.view as PagePreviewPanel).showSafeAreas = isSelected
        }
    }
    private val runtimeLabel = JLabel()
    private val pageTabs = JTabbedPane()

    init {
        val saveStylingButton = JButton(SAVE_ICON).apply {
            toolTipText = l10n("ui.edit.saveStyling")
            addActionListener {
                Controller.saveStyling()
                unsavedStylingLabel.isVisible = false
            }
        }
        val reloadStylingButton = JButton(RESET_ICON).apply {
            toolTipText = l10n("ui.edit.resetStyling")
            addActionListener {
                if (unsavedStylingLabel.isVisible) {
                    val option = showConfirmDialog(
                        MainFrame, l10n("ui.edit.resetUnsavedChangesWarning.msg"),
                        l10n("ui.edit.resetUnsavedChangesWarning.title"), YES_NO_OPTION
                    )
                    if (option == NO_OPTION)
                        return@addActionListener
                }
                Controller.reloadStylingFileAndRedraw()
                unsavedStylingLabel.isVisible = false
            }
        }

        val topPanel = JPanel(MigLayout("", "[]push[][][][][]push[][]push[]")).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")))
            add(JLabel(l10n("ui.edit.styling")))
            add(toggleEditStylingDialogButton, "width 4%::")
            add(saveStylingButton, "width 4%::")
            add(reloadStylingButton)
            add(unsavedStylingLabel)
            add(layoutGuidesToggleButton)
            add(safeAreasToggleButton)
            add(runtimeLabel)
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
            columnModel.getColumn(2).cellRenderer = WordWrapCellRenderer()
        }
        val logTablePanel = JPanel(MigLayout()).apply {
            add(JScrollPane(logTable), "grow, push")
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, topPanel, logTablePanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater {
            splitPane.setDividerLocation(0.85)
        }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    fun onSetEditStylingDialogVisible(isVisible: Boolean) {
        toggleEditStylingDialogButton.isSelected = isVisible
    }

    fun onLoadStyling() {
        unsavedStylingLabel.isVisible = false
    }

    fun onEditStyling() {
        unsavedStylingLabel.isVisible = true
    }

    fun onTryOpenProjectDirOrExit(): Boolean =
        if (unsavedStylingLabel.isVisible) {
            val option = showConfirmDialog(
                MainFrame, l10n("ui.edit.openUnsavedChangesWarning.msg"),
                l10n("ui.edit.openUnsavedChangesWarning.title"), YES_NO_CANCEL_OPTION
            )
            when (option) {
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
        project: Project,
        pageDefImages: List<DeferredImage>,
        log: List<ParserMsg>
    ) {
        // Adjust the total runtime label.
        if (project.pages.isEmpty())
            runtimeLabel.apply {
                text = null
                toolTipText = null
            }
        else {
            val fps = project.styling.global.fps.frac
            var durFrames = getRuntimeFrames(project, pageDefImages)
            var durSeconds = (durFrames / fps).toInt()
            val durMinutes = durSeconds / 60
            durFrames -= (durSeconds * fps).toInt()
            durSeconds -= durMinutes * 60
            runtimeLabel.apply {
                text = l10n("ui.edit.runtime", "%02d:%02d+%02d".format(durMinutes, durSeconds, durFrames))
                toolTipText = l10n("ui.edit.runtimeTooltip", durMinutes, durSeconds, durFrames)
            }
        }

        // First adjust the number of tabs to the number of pages.
        while (pageTabs.tabCount > project.pages.size)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < project.pages.size) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            pageTabs.addTab(tabTitle, PAGE_ICON, JScrollPane(VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_NEVER))
        }
        // Then fill each tab with its corresponding page.
        // Make sure that each scroll pane remembers its previous scroll height.
        for ((pageIdx, page) in project.pages.withIndex()) {
            val tabScrollPane = pageTabs.components[pageIdx] as JScrollPane
            val scrollHeight = tabScrollPane.verticalScrollBar.value
            tabScrollPane.setViewportView(
                PagePreviewPanel(
                    project.styling.global.widthPx, project.styling.global.background, page.style.behavior,
                    pageDefImages[pageIdx], layoutGuidesToggleButton.isSelected, safeAreasToggleButton.isSelected
                )
            )
            tabScrollPane.verticalScrollBar.value = scrollHeight
        }

        // Put the new parser log messages into the log table.
        LogTableModel.log = log.sortedWith { a, b ->
            b.severity.compareTo(a.severity).let { if (it != 0) it else a.lineNo.compareTo(b.lineNo) }
        }
    }


    private class PagePreviewPanel(
        private val globalWidth: Int,
        private val backgroundColor: Color,
        private val behavior: PageBehavior,
        private val defImage: DeferredImage,
        showGuides: Boolean,
        showSafeAreas: Boolean
    ) : JPanel() {

        var showGuides = showGuides
            set(value) {
                field = value
                repaint()
            }
        var showSafeAreas = showSafeAreas
            set(value) {
                field = value
                repaint()
            }

        // The scroll pane uses this information to decide on the length of the scrollbar.
        override fun getPreferredSize() =
            Dimension(parent.width, (parent.width * defImage.height / globalWidth).roundToInt())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val viewWidth = width
            val viewHeight = preferredSize.height

            // Create a temporary raster image two times the size of the panel. We will first draw the deferred image
            // on the raster image and then draw a scaled-down version of the raster image to the panel.
            // This way, we avoid directly drawing a very scaled-down version of the deferred image, which could
            // lead to text kerning issues etc.
            val rasterImage = BufferedImage(viewWidth * 2, viewHeight * 2, BufferedImage.TYPE_INT_RGB)
            // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
            val g2r = GraphicsUtil.createGraphics(rasterImage)
            try {
                g2r.setHighQuality()
                // Fill the background.
                g2r.color = backgroundColor
                g2r.fillRect(0, 0, viewWidth, viewHeight)
                // Draw a scaled version of the deferred image to the raster image.
                val scaledDefImage = DeferredImage()
                scaledDefImage.drawDeferredImage(defImage, 0f, 0f, 2 * viewWidth / globalWidth.toFloat())
                scaledDefImage.materialize(g2r, showGuides)
            } finally {
                g2r.dispose()
            }

            // Copy the Graphics2D so that we can change rendering hints without affecting stuff outside this method.
            val g2c = g.create() as Graphics2D
            try {
                g2c.setHighQuality()
                // Draw the a scaled-down version of the raster image to the panel.
                g2c.drawImage(rasterImage, AffineTransform.getScaleInstance(0.5, 0.5), null)
                // If requested, draw the action safe and title safe areas.
                if (showSafeAreas)
                    drawSafeAreas(g2c)
            } finally {
                g2c.dispose()
            }
        }

        private fun drawSafeAreas(g2: Graphics2D) {
            g2.color = Color.GRAY

            val viewWidth = width.toFloat()
            val viewHeight = preferredSize.height.toFloat()
            val actionSafeWidth = viewWidth * 0.93f
            val titleSafeWidth = viewWidth * 0.9f
            val actionSafeX1 = (viewWidth - actionSafeWidth) / 2f
            val titleSafeX1 = (viewWidth - titleSafeWidth) / 2f
            val actionSafeX2 = actionSafeX1 + actionSafeWidth
            val titleSafeX2 = titleSafeX1 + titleSafeWidth
            when (behavior) {
                PageBehavior.CARD -> {
                    val actionSafeHeight = viewHeight * 0.93f
                    val titleSafeHeight = viewHeight * 0.9f
                    val actionSafeY1 = (viewHeight - actionSafeHeight) / 2f
                    val titleSafeY1 = (viewHeight - titleSafeHeight) / 2f
                    g2.draw(Rectangle2D.Float(actionSafeX1, actionSafeY1, actionSafeWidth, actionSafeHeight))
                    g2.draw(Rectangle2D.Float(titleSafeX1, titleSafeY1, titleSafeWidth, titleSafeHeight))
                    val titleSafeY2 = titleSafeY1 + titleSafeHeight
                    val d = viewWidth / 200f
                    g2.draw(Line2D.Float(titleSafeX1 - d, viewHeight / 2f, titleSafeX1 + d, viewHeight / 2f))
                    g2.draw(Line2D.Float(titleSafeX2 - d, viewHeight / 2f, titleSafeX2 + d, viewHeight / 2f))
                    g2.draw(Line2D.Float(viewWidth / 2f, titleSafeY1 - d, viewWidth / 2f, titleSafeY1 + d))
                    g2.draw(Line2D.Float(viewWidth / 2f, titleSafeY2 - d, viewWidth / 2f, titleSafeY2 + d))
                }
                PageBehavior.SCROLL -> {
                    g2.draw(Line2D.Float(actionSafeX1, 0f, actionSafeX1, viewHeight))
                    g2.draw(Line2D.Float(actionSafeX2, 0f, actionSafeX2, viewHeight))
                    g2.draw(Line2D.Float(titleSafeX1, 0f, titleSafeX1, viewHeight))
                    g2.draw(Line2D.Float(titleSafeX2, 0f, titleSafeX2, viewHeight))
                }
            }
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
            0 -> l10n("ui.edit.severity")
            1 -> l10n("ui.edit.line")
            2 -> l10n("ui.edit.message")
            else -> throw IllegalArgumentException()
        }

        override fun getColumnClass(colIdx: Int) =
            if (colIdx == 0) Icon::class.java else String::class.java

        override fun getValueAt(rowIdx: Int, colIdx: Int): Any = when (colIdx) {
            0 -> SEVERITY_ICON[log[rowIdx].severity]!!
            1 -> log[rowIdx].lineNo
            2 -> log[rowIdx].msg
            else -> throw IllegalArgumentException()
        }

    }

}
