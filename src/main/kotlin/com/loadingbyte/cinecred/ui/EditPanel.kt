package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.delivery.getRuntimeFrames
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.projectio.toString2
import net.miginfocom.swing.MigLayout
import org.apache.batik.ext.awt.image.GraphicsUtil
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
            CARD_GUIDE_COLOR.brighter().toString2(), AXIS_GUIDE_COLOR.toString2(),
            BODY_ELEM_GUIDE_COLOR.brighter().toString2(), BODY_WIDTH_GUIDE_COLOR.brighter().brighter().toString2(),
            HEAD_TAIL_GUIDE_COLOR.brighter().toString2()
        )
        addActionListener { previewPanels.forEach { it.showGuides = isSelected } }
    }
    private val safeAreasToggleButton = JToggleButton(l10n("ui.edit.safeAreas"), false).apply {
        toolTipText = l10n("ui.edit.safeAreasTooltip")
        addActionListener { previewPanels.forEach { it.showSafeAreas = isSelected } }
    }
    private val runtimeLabel = JLabel()
    private val pageTabs = JTabbedPane()

    // Utility to quickly get all PagePreviewPanels from the tabbed pane.
    private val previewPanels get() = pageTabs.components.map { (it as JScrollPane).viewport.view as PagePreviewPanel }

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
                if (Controller.tryReloadStylingFileAndRedraw())
                    unsavedStylingLabel.isVisible = false
            }
        }

        val topPanel = JPanel(MigLayout("", "[]push[][][][][]push[][]push[]")).apply {
            add(JLabel(l10n("ui.edit.autoReloadActive")))
            add(JLabel(l10n("ui.edit.styling")))
            add(toggleEditStylingDialogButton, "width 5%::")
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
                    unsavedStylingLabel.isVisible = false
                    true
                }
                NO_OPTION -> {
                    unsavedStylingLabel.isVisible = false
                    true
                }
                else /* Cancel option */ -> false
            }
        } else
            true

    fun updateProjectAndLog(
        project: Project,
        drawnPages: List<DrawnPage>,
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
            val durOnlyFrames = getRuntimeFrames(project, drawnPages)
            var durSeconds = (durOnlyFrames / fps).toInt()
            val durMinutes = durSeconds / 60
            val durFrames = durOnlyFrames - (durSeconds * fps).toInt()
            durSeconds -= durMinutes * 60
            runtimeLabel.apply {
                text = l10n("ui.edit.runtime", "%02d:%02d+%02d".format(durMinutes, durSeconds, durFrames))
                toolTipText = l10n("ui.edit.runtimeTooltip", durMinutes, durSeconds, durFrames, durOnlyFrames)
            }
        }

        // First adjust the number of tabs to the number of pages.
        while (pageTabs.tabCount > project.pages.size) {
            val idx = pageTabs.tabCount - 1
            ((pageTabs.components[idx] as JScrollPane).viewport.view as PagePreviewPanel).onRemoval()
            pageTabs.removeTabAt(idx)
        }
        while (pageTabs.tabCount < project.pages.size) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val previewPanel = PagePreviewPanel(layoutGuidesToggleButton.isSelected, safeAreasToggleButton.isSelected)
            val scrollPane = JScrollPane(previewPanel, VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_NEVER)
            pageTabs.addTab(tabTitle, PAGE_ICON, scrollPane)
        }
        // Then fill each tab with its corresponding page.
        // Make sure that each scroll pane remembers its previous scroll height.
        for ((drawnPage, tabScrollPane) in drawnPages.zip(pageTabs.components)) {
            val scrollHeight = (tabScrollPane as JScrollPane).verticalScrollBar.value
            (tabScrollPane.viewport.view as PagePreviewPanel).setContent(project.styling.global, drawnPage)
            tabScrollPane.verticalScrollBar.value = scrollHeight
        }

        // Put the new parser log messages into the log table.
        LogTableModel.log = log.sortedWith { a, b ->
            b.severity.compareTo(a.severity).let { if (it != 0) it else a.lineNo.compareTo(b.lineNo) }
        }
    }


    private class PagePreviewPanel(showGuides: Boolean, showSafeAreas: Boolean) : JPanel() {

        private var dirty = true
        private var cachedImage: BufferedImage? = null
        private val paintingExecutor = LatestJobExecutor("PagePreviewPaintingThread-${System.identityHashCode(this)}")

        private var globalWidth = 0
        private var globalHeight = 0
        private var backgroundColor = Color.BLACK
        private var defImage = DeferredImage()
        private var stageInfo = emptyList<DrawnStageInfo>()

        fun setContent(global: Global, drawnPage: DrawnPage) {
            globalWidth = global.widthPx
            globalHeight = global.heightPx
            backgroundColor = global.background
            defImage = drawnPage.defImage
            stageInfo = drawnPage.stageInfo
            dirty = true
            revalidate()  // This changes the scrollbar length when the page height changes.
            repaint()
        }

        var showGuides = showGuides
            set(value) {
                field = value
                dirty = true
                repaint()
            }
        var showSafeAreas = showSafeAreas
            set(value) {
                field = value
                repaint()
            }

        init {
            // Listen for changes to the panel's width.
            var prevWidth = width
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (prevWidth != width) {
                        prevWidth = width
                        dirty = true
                        repaint()
                    }
                }
            })
        }

        fun onRemoval() {
            paintingExecutor.destroy()
        }

        // The parent scroll pane uses this information to decide on the length of the scrollbar.
        override fun getPreferredSize() =
            Dimension(parent.width, (parent.width * defImage.height / globalWidth).roundToInt())

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val currCachedImage = cachedImage

            // Use an intermediate raster image two times the size of the panel. We first paint a scaled version
            // of the deferred image onto the raster image and then paint the raster image onto the panel.
            // The raster image is cached.
            // This way, we avoid having to materialize the deferred image over and over again whenever the user
            // scrolls or resizes something, which can be very expensive when the deferred image contains, e.g., PDFs.
            // Also, we avoid directly drawing a very scaled-down version of the deferred image, which could lead to
            // text kerning issues etc.
            if (dirty) {
                dirty = false
                paintingExecutor.submit {
                    val newImage = paintRasterImage()
                    // Use invokeLater() to make sure that when the following code is executed, we are definitely
                    // no longer in the outer paintComponent() method. This way, we can go without explicit locking.
                    // Also, we set the cachedImage variable in the AWT thread, which alleviates the need to declare
                    // it volatile.
                    SwingUtilities.invokeLater {
                        cachedImage = newImage
                        repaint()
                    }
                }
                // This method will be called once again once the background thread has finished painting the
                // intermediate image. We will now go on painting the old version of the cached image.
            }

            // Copy the Graphics so that we can change rendering hints without affecting stuff outside this method.
            val g2 = g.create() as Graphics2D
            try {
                g2.setHighQuality()
                if (currCachedImage == null) {
                    // If the preview panel has just been created, it doesn't have a cached image yet.
                    // In that case, we just don't draw an empty background.
                    g2.color = backgroundColor
                    g2.fillRect(0, 0, width, preferredSize.height)
                } else {
                    // Otherwise, paint the cached image on the panel.
                    g2.drawImage(currCachedImage, AffineTransform.getScaleInstance(0.5, 0.5), null)
                }
                // If requested, paint the action safe and title safe areas.
                if (showSafeAreas)
                    paintSafeAreas(g2)
            } finally {
                g2.dispose()
            }
        }

        private fun paintRasterImage(): BufferedImage {
            val rasterImage = BufferedImage(2 * width, 2 * preferredSize.height, BufferedImage.TYPE_3BYTE_BGR)

            // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
            val g2 = GraphicsUtil.createGraphics(rasterImage)
            try {
                g2.setHighQuality()
                // Fill the background.
                g2.color = backgroundColor
                g2.fillRect(0, 0, rasterImage.width, rasterImage.height)
                // Paint a scaled version of the deferred image on the raster image.
                val scaledDefImage = DeferredImage()
                scaledDefImage.drawDeferredImage(defImage, 0f, 0f, rasterImage.width / globalWidth.toFloat())
                // Note: We compute the guide stroke width such that they have a nice thin width irrespective of the
                // configured film dimensions.
                scaledDefImage.materialize(g2, showGuides, guideStrokeWidth = globalWidth / 1024f)
            } finally {
                g2.dispose()
            }

            return rasterImage
        }

        private fun paintSafeAreas(g2Orig: Graphics2D) {
            val g2 = g2Orig.create() as Graphics2D
            try {
                g2.color = Color.GRAY

                // By asking the graphics object itself to scale such that we accommodate for the
                // smaller preview size, we can work in regular page units in the rest of this method.
                val previewScaling = width / globalWidth.toDouble()
                g2.scale(previewScaling, previewScaling)

                val actionSafeWidth = globalWidth * 0.93f
                val titleSafeWidth = globalWidth * 0.9f
                val actionSafeHeight = globalHeight * 0.93f
                val titleSafeHeight = globalHeight * 0.9f
                val actionSafeX1 = (globalWidth - actionSafeWidth) / 2f
                val titleSafeX1 = (globalWidth - titleSafeWidth) / 2f
                val actionSafeX2 = actionSafeX1 + actionSafeWidth
                val titleSafeX2 = titleSafeX1 + titleSafeWidth

                // Draw full safe area hints for each card stage.
                for (middleY in stageInfo.filterIsInstance<DrawnStageInfo.Card>().map { it.middleY }) {
                    val actionSafeY1 = middleY - actionSafeHeight / 2f
                    val titleSafeY1 = middleY - titleSafeHeight / 2f
                    g2.draw(Rectangle2D.Float(actionSafeX1, actionSafeY1, actionSafeWidth, actionSafeHeight))
                    g2.draw(Rectangle2D.Float(titleSafeX1, titleSafeY1, titleSafeWidth, titleSafeHeight))
                    val titleSafeY2 = titleSafeY1 + titleSafeHeight
                    val d = globalWidth / 200f
                    g2.draw(Line2D.Float(titleSafeX1 - d, middleY, titleSafeX1 + d, middleY))
                    g2.draw(Line2D.Float(titleSafeX2 - d, middleY, titleSafeX2 + d, middleY))
                    g2.draw(Line2D.Float(globalWidth / 2f, titleSafeY1 - d, globalWidth / 2f, titleSafeY1 + d))
                    g2.draw(Line2D.Float(globalWidth / 2f, titleSafeY2 - d, globalWidth / 2f, titleSafeY2 + d))
                }

                // Draw left and right safe area strips if there are scroll stages on the page. If the page starts
                // resp. ends with a card stage, make sure to NOT draw the strips to the page's very top resp. bottom.
                if (stageInfo.any { it is DrawnStageInfo.Scroll }) {
                    val firstStageInfo = stageInfo.first()
                    val lastStageInfo = stageInfo.last()
                    val firstCardMiddleY = if (firstStageInfo is DrawnStageInfo.Card) firstStageInfo.middleY else null
                    val lastCardMiddleY = if (lastStageInfo is DrawnStageInfo.Card) lastStageInfo.middleY else null
                    val actionSafeY1 = firstCardMiddleY?.let { it - actionSafeHeight / 2f } ?: 0f
                    val titleSafeY1 = firstCardMiddleY?.let { it - titleSafeHeight / 2f } ?: 0f
                    val actionSafeY2 = lastCardMiddleY?.let { it + actionSafeHeight / 2f } ?: 0f
                    val titleSafeY2 = lastCardMiddleY?.let { it + titleSafeHeight / 2f } ?: 0f
                    g2.draw(Line2D.Float(actionSafeX1, actionSafeY1, actionSafeX1, actionSafeY2))
                    g2.draw(Line2D.Float(actionSafeX2, actionSafeY1, actionSafeX2, actionSafeY2))
                    g2.draw(Line2D.Float(titleSafeX1, titleSafeY1, titleSafeX1, titleSafeY2))
                    g2.draw(Line2D.Float(titleSafeX2, titleSafeY1, titleSafeX2, titleSafeY2))
                }
            } finally {
                g2.dispose()
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
