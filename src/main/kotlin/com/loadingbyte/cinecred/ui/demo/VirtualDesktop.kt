package com.loadingbyte.cinecred.ui.demo

import com.loadingbyte.cinecred.common.preserveTransform
import com.loadingbyte.cinecred.common.sumBetween
import com.loadingbyte.cinecred.project.Style
import com.loadingbyte.cinecred.project.StyleSetting
import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.styling.StyleForm
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.MouseEvent
import java.awt.event.MouseEvent.*
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.*
import javax.swing.*
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.roundToInt


class VirtualDesktop(val width: Int, val height: Int) {

    private val windows = mutableListOf<VirtualWindow>()

    private var mouse = Point2D.Double()
    private var mouseTarget: Point? = null
    private var mouseSpeed = 0.0  // px per second
    private var mousePressed = false
    private var draggedWindow: VirtualWindow? = null
    private var draggedWindowOffsetFromMouse = Point2D.Double()
    private var draggedFolder: Path? = null

    fun add(window: VirtualWindow) {
        windows.add(window)
    }

    fun remove(window: VirtualWindow) {
        windows.remove(window)
    }

    fun toBack(window: VirtualWindow) {
        windows.remove(window)
        windows.add(0, window)
    }

    fun center(window: VirtualWindow) {
        val size = window.size
        window.pos = Point((width - size.width) / 2, (height - size.height) / 2)
    }

    fun snapToSide(window: VirtualWindow, rightSide: Boolean) {
        window.pos = Point(if (rightSide) width / 2 else 0, 0)
        window.size = Dimension(width / 2, height)
    }

    fun isMouseMoving(): Boolean =
        mouseTarget != null

    fun mouseTo(point: Point, jump: Boolean = false) {
        if (jump) {
            mouse = Point2D.Double(point.x.toDouble(), point.y.toDouble())
            mouseTarget = null
        } else {
            mouseTarget = point
            mouseSpeed = 150.0 + mouse.distance(point)
        }
    }

    fun mouseDown() {
        mousePressed = true
        for (window in windows.asReversed() /* front-to-back */)
            if (window.mouseEvent(mouse, MOUSE_PRESSED))
                break
    }

    fun mouseUp() {
        mousePressed = false
        for (window in windows.asReversed() /* front-to-back */)
            if (window.mouseEvent(mouse, MOUSE_RELEASED) or window.mouseEvent(mouse, MOUSE_CLICKED))
                break
    }

    fun dragWindow(window: VirtualWindow) {
        val pos = window.pos
        draggedWindow = window
        draggedWindowOffsetFromMouse = Point2D.Double(pos.x - mouse.x, pos.y - mouse.y)
    }

    fun dropWindow() {
        draggedWindow = null
    }

    fun dragFolder(folder: Path) {
        draggedFolder = folder
    }

    fun dropFolder(target: JComponent) {
        val file = draggedFolder!!
        draggedFolder = null
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor.isFlavorJavaFileListType
            override fun getTransferData(flavor: DataFlavor) = listOf(file.toFile())
        }
        target.transferHandler.importData(TransferHandler.TransferSupport(target, transferable))
    }

    fun tick(deltaTime: Double /* seconds */) {
        for (window in windows)
            window.elapsedTime += deltaTime

        mouseTarget?.let { target ->
            val tickDist = mouseSpeed * deltaTime
            val remainingDist = mouse.distance(target)
            if (remainingDist <= tickDist) {
                mouse.setLocation(target)
                mouseTarget = null
            } else {
                mouse.setLocation(
                    mouse.x + (target.x - mouse.x) * tickDist / remainingDist,
                    mouse.y + (target.y - mouse.y) * tickDist / remainingDist
                )
            }
            for (window in windows.asReversed() /* front-to-back */)
                if (window.mouseEvent(mouse, MOUSE_MOVED) or mousePressed && window.mouseEvent(mouse, MOUSE_DRAGGED))
                    break
            draggedWindow?.pos = Point(
                (mouse.x + draggedWindowOffsetFromMouse.x).roundToInt(),
                (mouse.y + draggedWindowOffsetFromMouse.y).roundToInt()
            )
        }
    }

    fun paint(g2: Graphics2D) {
        // Paint windows
        for (window in windows)
            g2.preserveTransform {
                g2.translate(window.pos.x, window.pos.y)
                window.paint(g2)
            }
        // Paint dragged folder
        if (draggedFolder != null)
            g2.preserveTransform {
                g2.translate(mouse.x - FOLDER_ICON_WIDTH / 2, mouse.y - FOLDER_ICON_HEIGHT / 2)
                g2.color = PALETTE_BLUE_COLOR
                g2.fill(FOLDER_ICON)
            }
        // Paint mouse cursor
        g2.preserveTransform {
            g2.translate(mouse.x, mouse.y)
            g2.color = Color.WHITE
            g2.fill(CURSOR_ICON)
            g2.color = Color.BLACK
            g2.stroke = BasicStroke(1.5f)
            g2.draw(CURSOR_ICON)
        }
    }

}


abstract class VirtualWindow {

    companion object {
        private const val BORDER = 1
        private const val TITLE_BAR_HEIGHT = 28
        const val INSET_T = BORDER + TITLE_BAR_HEIGHT
        const val INSET_L = BORDER
        const val INSET_B = BORDER
        const val INSET_R = BORDER
        private val BORDER_COLOR = UIManager.getColor("Panel.background").darker()
        private val DUMMY_COMPONENT = JPanel()
    }

    var elapsedTime = 0.0  // seconds

    abstract var pos: Point
    abstract var size: Dimension
    abstract fun paint(g2: Graphics2D)
    open fun mouseEvent(mouse: Point2D.Double, id: Int): Boolean = false

    protected fun paintWindowDecorations(g2: Graphics2D, title: String, icon: BufferedImage?, bg: Color, fg: Color) {
        val (width, height) = size.run { Pair(width, height) }
        // Border
        g2.color = BORDER_COLOR
        g2.fillRect(0, 0, width, BORDER)  // top
        g2.fillRect(0, height - BORDER, width, BORDER)  // bottom
        g2.fillRect(0, BORDER, BORDER, height - 2 * BORDER) // left
        g2.fillRect(width - BORDER, BORDER, BORDER, height - 2 * BORDER) // right
        // Bar
        g2.color = bg
        g2.fillRect(BORDER, BORDER, width - 2 * BORDER, TITLE_BAR_HEIGHT)
        // Title
        g2.color = fg
        g2.font = UI_FONT
        val fm = g2.fontMetrics
        g2.drawString(
            title,
            (width - 2 * BORDER - fm.stringWidth(title)) / 2,
            BORDER + (TITLE_BAR_HEIGHT + fm.ascent - fm.descent) / 2
        )
        // Icon
        if (icon != null) {
            val iconGap = (TITLE_BAR_HEIGHT - icon.height) / 2
            g2.drawImage(icon, BORDER + iconGap, BORDER + iconGap, null)
        }
        // Close button
        val closeGap = (TITLE_BAR_HEIGHT - CANCEL_ICON.iconHeight) / 2
        CANCEL_ICON.paintIcon(DUMMY_COMPONENT, g2, width - BORDER - closeGap - CANCEL_ICON.iconWidth, BORDER + closeGap)
    }

    /** Obtains virtual desktop coordinates for the title bar. */
    fun desktopPosOfTitleBar(center: Boolean = true) = Point(
        pos.x + if (center) size.width / 2 else 0,
        pos.y + if (center) TITLE_BAR_HEIGHT / 2 else 0
    )

}


class BackedVirtualWindow(val backingWin: Window) : VirtualWindow() {

    companion object {
        private val TITLE_BAR_ICON = WINDOW_ICON_IMAGES.first { it.height == 16 }
        private val TITLE_BAR_BACKGROUND = UIManager.getColor("Panel.background")
        private val TITLE_BAR_FOREGROUND = UIManager.getColor("Panel.foreground")
    }

    override var pos: Point = backingWin.location
        set(pos) {
            field = pos
            edt { backingWin.location = pos }
        }

    override var size: Dimension
        get() {
            val insets = backingWin.insets
            return Dimension(
                backingWin.width - insets.left - insets.right + INSET_L + INSET_R,
                backingWin.height - insets.top - insets.bottom + INSET_T + INSET_B
            )
        }
        set(size) {
            val insets = backingWin.insets
            edt {
                backingWin.setSize(
                    size.width + insets.left + insets.right - INSET_L - INSET_R,
                    size.height + insets.top + insets.bottom - INSET_T - INSET_B
                )
            }
        }

    override fun paint(g2: Graphics2D) {
        if (backingWin.isVisible) {
            val (title, contentPane) = when (backingWin) {
                is JFrame -> Pair(backingWin.title, backingWin.contentPane)
                is JDialog -> Pair(backingWin.title, backingWin.contentPane)
                else -> throw IllegalStateException()
            }
            paintWindowDecorations(g2, title, TITLE_BAR_ICON, TITLE_BAR_BACKGROUND, TITLE_BAR_FOREGROUND)
            g2.preserveTransform {
                g2.translate(INSET_L, INSET_T)
                printWithPopups(contentPane, g2)
            }
        }
    }

    override fun mouseEvent(mouse: Point2D.Double, id: Int): Boolean {
        fun dispatch(win: Window, vWinPos: Point, vWinInsetL: Int, vWinInsetT: Int, winInsets: Insets): Boolean {
            if (mouse.x.toInt() - vWinPos.x - vWinInsetL in 0..win.width - winInsets.left - winInsets.right &&
                mouse.y.toInt() - vWinPos.y - vWinInsetT in 0..win.height - winInsets.top - winInsets.bottom
            ) {
                val x = mouse.x.toInt() - vWinPos.x - vWinInsetL + winInsets.left
                val y = mouse.y.toInt() - vWinPos.y - vWinInsetT + winInsets.top
                val event = when (id) {
                    MOUSE_MOVED -> MouseEvent(win, id, 0, 0, x, y, 0, false)
                    MOUSE_DRAGGED -> MouseEvent(win, id, 0, BUTTON1_DOWN_MASK, x, y, 0, false)
                    else -> MouseEvent(win, id, 0, 0, x, y, 1, false, BUTTON1)
                }
                edt { win.dispatchEvent(event) }
                return event.isConsumed
            }
            return false
        }

        forEachVisiblePopupOf(backingWin) { popup ->
            if (dispatch(popup, desktopPosOf(popup, center = false), 0, 0, Insets(0, 0, 0, 0)))
                return true
        }
        if (backingWin.isVisible)
            return dispatch(backingWin, pos, INSET_L, INSET_T, backingWin.insets)
        return false
    }

    /** Obtains virtual desktop coordinates for the given component, assuming that it is inside this window. */
    fun desktopPosOf(comp: Component, center: Boolean = true): Point {
        val insets = backingWin.insets
        val rel = SwingUtilities.convertPoint(comp, 0, 0, backingWin)
        return Point(
            pos.x + (rel.x - insets.left) + INSET_L + if (center) comp.width / 2 else 0,
            pos.y + (rel.y - insets.top) + INSET_T + if (center) comp.height / 2 else 0
        )
    }

    /**
     * Obtains virtual desktop coordinates for the item with the given element in the currently open dropdown,
     * assuming that it stems from some component inside this window.
     */
    fun desktopPosOfDropdownItem(elem: Any, center: Boolean = true): Point {
        val menu = MenuSelectionManager.defaultManager().selectedPath.single() as JPopupMenu
        val list = (menu.getComponent(0) as JScrollPane).viewport.view as JList<*>
        val listDesktopPos = desktopPosOf(list, center = false)
        val idx = (0 until list.model.size).first { list.model.getElementAt(it) == elem }
        val cellBounds = list.getCellBounds(idx, idx)
        return Point(
            listDesktopPos.x + cellBounds.x + if (center) cellBounds.width / 2 else 0,
            listDesktopPos.y + cellBounds.y + if (center) cellBounds.height / 2 else 0
        )
    }

    /**
     * Obtains virtual desktop coordinates for the given tab index of the given tabbed pane,
     * assuming that the tabbed pane is inside this window.
     */
    fun desktopPosOfTab(tabs: JTabbedPane, idx: Int, center: Boolean = true): Point {
        val tabsDesktopPos = desktopPosOf(tabs, center = false)
        val tabBounds = tabs.getBoundsAt(idx)
        return Point(
            tabsDesktopPos.x + tabBounds.x + if (center) tabBounds.width / 2 else 0,
            tabsDesktopPos.y + tabBounds.y + if (center) tabBounds.height / 2 else 0
        )
    }

    /**
     * Obtains virtual desktop coordinates for the given tree element of the given tree with the given label,
     * assuming that the tree is inside this window.
     */
    fun desktopPosOfTreeItem(tree: JTree, label: String, center: Boolean = true): Point {
        val treeDesktopPos = desktopPosOf(tree, center = false)
        for (rowIdx in 0 until tree.rowCount) {
            val path = tree.getPathForRow(rowIdx)
            if (path.lastPathComponent.toString() == label) {
                val pathBounds = tree.getPathBounds(path)!!
                return Point(
                    treeDesktopPos.x + pathBounds.x + if (center) pathBounds.width / 2 else 0,
                    treeDesktopPos.y + pathBounds.y + if (center) pathBounds.height / 2 else 0
                )
            }
        }
        throw NoSuchElementException()
    }

    /**
     * Obtains virtual desktop coordinates for the given part of the widget for the given setting in the given form,
     * assuming that the form is inside this window.
     */
    fun <S : Style> desktopPosOfSetting(
        form: StyleForm<S>, setting: StyleSetting<S, *>, idx: Int = 0, center: Boolean = true
    ): Point {
        fun inListWidget(widget: ListWidget<*>): Component {
            if (idx == 0)
                return widget.components[0]
            var ctr = 1
            for (c in widget.components[1].components)
                if (c is JPanel /* ToggleButtonGroupWidget's GroupPanel */) {
                    for (button in c.components)
                        if (ctr++ == idx) return button
                } else if (ctr++ == idx) return c
            throw IllegalArgumentException()
        }

        val comp = when (val widget = form.getWidgetFor(setting)) {
            is ToggleButtonGroupWidget -> widget.components[0].getComponent(idx)
            is ListWidget<*> -> inListWidget(widget)
            else -> widget.components[idx]
        }
        return desktopPosOf(comp, center)
    }

}


abstract class FakeVirtualWindow : VirtualWindow() {

    companion object {
        private const val BORDER = 4
        const val INSET_T = VirtualWindow.INSET_T
        const val INSET_L = VirtualWindow.INSET_L + BORDER
        const val INSET_B = VirtualWindow.INSET_B + BORDER
        const val INSET_R = VirtualWindow.INSET_R + BORDER
    }

    override var pos = Point()
    override var size = Dimension(500, 300)

    abstract val title: String
    abstract fun paintContent(g2: Graphics2D)

    override fun paint(g2: Graphics2D) {
        // Paint title bar
        paintWindowDecorations(g2, title, icon = null, PALETTE_BLUE_COLOR.darker(), Color.LIGHT_GRAY)
        // Paint window background
        g2.color = PALETTE_BLUE_COLOR.darker()
        g2.fillRect(
            VirtualWindow.INSET_L,
            VirtualWindow.INSET_T,
            size.width - VirtualWindow.INSET_L - VirtualWindow.INSET_R,
            size.height - VirtualWindow.INSET_T - VirtualWindow.INSET_B
        )
        // Paint content background
        g2.color = Color.LIGHT_GRAY
        g2.fillRect(INSET_L, INSET_T, size.width - INSET_L - INSET_R, size.height - INSET_T - INSET_B)
        // Paint content
        paintContent(g2)
    }

}


class FileBrowserVirtualWindow : FakeVirtualWindow() {

    companion object {
        private const val GAP = 40
    }

    var folderPath = ""
    val fileNames = TreeSet(Comparator.comparing(::isRegularFile).thenComparing<String> { it })
    var selectedFileName: String? = null

    override val title
        get() = "$folderPath \u2013 " + l10nDemo("screencast.fileBrowser.title")

    override fun paintContent(g2: Graphics2D) {
        for ((fileName, bounds) in fileNames.zip(fileBounds())) {
            val regFile = isRegularFile(fileName)
            val selected = fileName == selectedFileName
            // Paint file or folder icon
            g2.color = if (selected) PALETTE_BLUE_COLOR.darker() else PALETTE_BLUE_COLOR
            g2.preserveTransform {
                g2.translate(bounds.x, bounds.y)
                g2.fill(if (regFile) FILE_ICON else FOLDER_ICON)
            }
            // Paint file type onto icon
            if (regFile) {
                g2.color = Color.LIGHT_GRAY
                g2.font = UI_FONT.deriveFont(Font.BOLD).deriveFont(14f)
                val ext = fileName.substringAfter('.').uppercase()
                val strWidth = g2.fontMetrics.stringWidth(ext)
                g2.drawString(ext, bounds.x + (bounds.width - strWidth) / 2, INSET_T + GAP + 42)
            }
            // Paint filename
            g2.color = if (selected) Color.DARK_GRAY.darker() else Color.DARK_GRAY
            g2.font = UI_FONT
            val strWidth = g2.fontMetrics.stringWidth(fileName)
            g2.drawString(fileName, bounds.x + (bounds.width - strWidth) / 2, INSET_T + GAP + 80)
        }
    }

    /** Obtains virtual desktop coordinates for the given file. */
    fun desktopPosOfFile(fileName: String, center: Boolean = true): Point {
        val bounds = fileBounds()[fileNames.indexOf(fileName)]
        return Point(
            pos.x + bounds.x + if (center) bounds.width / 2 else 0,
            pos.y + bounds.y + if (center) bounds.height / 2 else 0
        )
    }

    private fun fileBounds(): List<Rectangle> {
        val bounds = mutableListOf<Rectangle>()
        val maxIconHeight = max(FILE_ICON_HEIGHT, FOLDER_ICON_HEIGHT)
        var x = INSET_L + GAP
        for (fileName in fileNames) {
            val iconWidth: Int
            val iconHeight: Int
            if (isRegularFile(fileName)) {
                iconWidth = FILE_ICON_WIDTH
                iconHeight = FILE_ICON_HEIGHT
            } else {
                iconWidth = FOLDER_ICON_WIDTH
                iconHeight = FOLDER_ICON_HEIGHT
            }
            bounds.add(Rectangle(x, INSET_T + GAP + (maxIconHeight - iconHeight) / 2, iconWidth, iconHeight))
            x += iconWidth + GAP
        }
        return bounds
    }

    private fun isRegularFile(fileName: String) = "." in fileName

}


class SpreadsheetEditorVirtualWindow(private val file: Path, skipRows: Int) : FakeVirtualWindow() {

    companion object {
        private const val ROW_HEIGHT = 20
        private const val SEP_THICKNESS = 2
        private const val CELL_INSET = 4
    }

    override val title = "${file.name} \u2013 " + l10nDemo("screencast.spreadsheetEditor.title")

    val matrix = CsvFormat.read(file).first.drop(skipRows).map { it.cells.toMutableList() }
    var colWidths = intArrayOf()

    private var selectedCell: Point? = null

    fun selectCell(rowIdx: Int, colIdx: Int) {
        selectedCell = Point(rowIdx, colIdx)
    }

    fun deselectCell() {
        selectedCell = null
    }

    override fun paintContent(g2: Graphics2D) {
        val oldClip = g2.clip
        val area = Rectangle(INSET_L, INSET_T, size.width - INSET_L - INSET_R, size.height - INSET_T - INSET_B)
        g2.font = UI_FONT
        val fm = g2.fontMetrics
        // Paint cell separators
        g2.clip(area)
        g2.color = Color.GRAY.brighter()
        for (rowIdx in 1 until matrix.size) {
            val y = cellY(rowIdx) - SEP_THICKNESS
            if (y > area.y + area.height) break
            g2.fillRect(area.x, y, area.width, SEP_THICKNESS)
        }
        for (colIdx in 1 until colWidths.size) {
            val x = cellX(colIdx) - SEP_THICKNESS
            if (x > area.x + area.width) break
            g2.fillRect(x, area.y, SEP_THICKNESS, area.height)
        }
        // Paint cell selection
        selectedCell?.let { selectedCell ->
            val rowIdx = selectedCell.x
            val colIdx = selectedCell.y
            val x = cellX(colIdx)
            val y = cellY(rowIdx)
            val w = colWidths[colIdx]
            val h = ROW_HEIGHT
            val thickness = SEP_THICKNESS + 1
            g2.color = Color.DARK_GRAY
            g2.fillRect(x - thickness, y - thickness, w + 2 * thickness, thickness)  // top
            g2.fillRect(x - thickness, y + h, w + 2 * thickness, thickness)  // bottom
            g2.fillRect(x - thickness, y, thickness, h)  // left
            g2.fillRect(x + w, y, thickness, h)  // right
            // Paint caret
            if (elapsedTime % 1.0 < 0.5) {
                g2.color = Color.DARK_GRAY
                g2.clip(Rectangle(x, y, w, h))
                g2.fillRect(x + CELL_INSET + fm.stringWidth(matrix[rowIdx][colIdx]), y + 2, 2, ROW_HEIGHT - 4)
            }
        }
        // Paint cell contents
        g2.color = Color.DARK_GRAY
        for (rowIdx in matrix.indices) {
            val y = cellY(rowIdx)
            if (y > area.y + area.height) break
            check(matrix[rowIdx].size == colWidths.size)
            for (colIdx in colWidths.indices) {
                val x = cellX(colIdx)
                if (x > area.x + area.width) break
                g2.clip = oldClip
                g2.clip(area)
                g2.clip(Rectangle(x, y, colWidths[colIdx], ROW_HEIGHT))
                g2.drawString(matrix[rowIdx][colIdx], x + CELL_INSET, y + (ROW_HEIGHT + fm.ascent - fm.descent) / 2)
            }
        }
        // Reset clip
        g2.clip = oldClip
    }

    /** Obtains virtual desktop coordinates for the given cell. */
    fun desktopPosOfCell(rowIdx: Int, colIdx: Int, center: Boolean = true) = Point(
        pos.x + cellX(colIdx) + if (center) colWidths[colIdx] / 2 else 0,
        pos.y + cellY(rowIdx) + if (center) ROW_HEIGHT / 2 else 0
    )

    fun save() {
        CsvFormat.write(file, Spreadsheet(matrix), emptyMap(), emptyList())
    }

    private fun cellX(colIdx: Int) = INSET_L + colIdx * SEP_THICKNESS + colWidths.sumBetween(0, colIdx)
    private fun cellY(rowIdx: Int) = INSET_T + rowIdx * (ROW_HEIGHT + SEP_THICKNESS)

}


private val UI_FONT = UIManager.getFont("Panel.font")

private val CURSOR_ICON = Path2D.Double().apply {
    moveTo(0.0, 0.0)
    lineTo(1.0, 23.0)
    lineTo(6.0, 18.0)
    lineTo(12.0, 29.0)
    lineTo(18.0, 26.0)
    lineTo(12.0, 15.0)
    lineTo(18.9, 13.5)
    closePath()
}

private const val FILE_ICON_WIDTH = 52
private const val FILE_ICON_HEIGHT = 60
private val FILE_ICON = Path2D.Double().apply {
    moveTo(0.0, 16.0)
    lineTo(0.0, 60.0)
    lineTo(52.0, 60.0)
    lineTo(52.0, 0.0)
    lineTo(16.0, 0.0)
    closePath()
}

private const val FOLDER_ICON_WIDTH = 64
private const val FOLDER_ICON_HEIGHT = 48
private val FOLDER_ICON = Path2D.Double().apply {
    moveTo(0.0, 0.0)
    lineTo(0.0, 48.0)
    lineTo(64.0, 48.0)
    lineTo(64.0, 8.0)
    lineTo(28.0, 8.0)
    lineTo(20.0, 0.0)
    closePath()
}
