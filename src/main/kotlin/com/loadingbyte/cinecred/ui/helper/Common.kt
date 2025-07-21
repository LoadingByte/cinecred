package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.icons.FlatCheckBoxIcon
import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.SystemInfo
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Color4f
import java.awt.*
import java.awt.RenderingHints.*
import java.awt.event.*
import java.awt.event.KeyEvent.*
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import javax.swing.*
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.TableCellRenderer
import javax.swing.text.Document
import kotlin.math.roundToInt


const val PALETTE_RED: String = "#DB5C5C"
const val PALETTE_YELLOW: String = "#F0A732"
const val PALETTE_GREEN: String = "#499C54"
const val PALETTE_BLUE: String = "#548AF7"
const val PALETTE_GRAY: String = "#CED0D6"
val PALETTE_RED_COLOR = Color(Integer.decode(PALETTE_RED))
val PALETTE_YELLOW_COLOR = Color(Integer.decode(PALETTE_YELLOW))
val PALETTE_GREEN_COLOR = Color(Integer.decode(PALETTE_GREEN))
val PALETTE_BLUE_COLOR = Color(Integer.decode(PALETTE_BLUE))
val PALETTE_GRAY_COLOR = Color(Integer.decode(PALETTE_GRAY))

val OVERLAY_COLOR = Color4f.GRAY


inline fun Graphics.withNewG2(block: (Graphics2D) -> Unit) {
    val g2 = create() as Graphics2D
    try {
        block(g2)
    } finally {
        g2.dispose()
    }
}

inline fun Graphics2D.preserveTransform(block: () -> Unit) {
    val prevTransform = transform  // creates a defensive copy
    try {
        block()
    } finally {
        transform = prevTransform
    }
}

inline fun BufferedImage.withG2(block: (Graphics2D) -> Unit): BufferedImage {
    val g2 = createGraphics()
    try {
        block(g2)
    } finally {
        g2.dispose()
    }
    return this
}

fun Graphics2D.setHighQuality() {
    setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON)
    setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY)
    setRenderingHint(KEY_DITHERING, VALUE_DITHER_DISABLE)
    setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC)
    setRenderingHint(KEY_ALPHA_INTERPOLATION, VALUE_ALPHA_INTERPOLATION_QUALITY)
    setRenderingHint(KEY_COLOR_RENDERING, VALUE_COLOR_RENDER_QUALITY)
    setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(KEY_FRACTIONALMETRICS, VALUE_FRACTIONALMETRICS_ON)
}

fun Graphics2D.scale(s: Double) = scale(s, s)


/**
 * By enabling HTML in the JLabel, a branch that doesn't add ellipsis but instead clips the string is taken in
 * SwingUtilities.layoutCompoundLabelImpl().
 */
fun noEllipsisLabel(text: String) = "<html><nobr>$text</nobr></html>"


fun newLabelTextArea(text: String? = null, insets: Boolean = false) = object : JTextArea(text) {
    init {
        background = null
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        // If requested, set insets to 0, as JLabels also have insets of 0 and the text area should behave like a label.
        if (!insets)
            margin = null
        // Without setting an explicit minimum width, the component would never ever again shrink once it has grown.
        // This would of course lead to trouble when first enlarging and then shrinking a container which contains
        // a label text area. By setting an explicit minimum width, we turn off this undesired behavior.
        minimumSize = Dimension(0, 0)
    }

    // Disable the capability of the text area to scroll any ancestor scroll pane. Text areas usually scroll to
    // themselves whenever their text changes. We do not want this behavior for label text areas.
    override fun scrollRectToVisible(aRect: Rectangle) {}
}


fun newLabelTextPane() = object : JTextPane() {
    init {
        background = null
        isEditable = false
        isFocusable = false
    }

    // Disable the ability to scroll for the same reason as explained above.
    override fun scrollRectToVisible(aRect: Rectangle) {}
}


fun newLabelEditorPane(type: String, text: String? = null, insets: Boolean = false) = object : JEditorPane(type, text) {
    init {
        background = null
        isEditable = false
        isFocusable = false
        // If requested, set insets to 0, as JLabels also have insets of 0 and the pane should behave like a label.
        if (!insets)
            margin = null
    }

    // Disable the ability to scroll for the same reason as explained above.
    override fun scrollRectToVisible(aRect: Rectangle) {}
}


fun newToolbarButton(
    icon: Icon,
    tooltip: String,
    shortcutKeyCode: Int,
    shortcutModifiers: Int,
    listener: (() -> Unit)? = null
) = JButton(icon).also { btn ->
    cfgForToolbar(btn, tooltip, shortcutKeyCode, shortcutModifiers)
    if (listener != null)
        btn.addActionListener { listener() }
}

fun newToolbarToggleButton(
    icon: Icon,
    tooltip: String,
    shortcutKeyCode: Int,
    shortcutModifiers: Int,
    isSelected: Boolean = false,
    listener: ((Boolean) -> Unit)? = null
) = JToggleButton(icon, isSelected).also { btn ->
    cfgForToolbar(btn, tooltip, shortcutKeyCode, shortcutModifiers)
    if (listener != null)
        btn.addItemListener { listener(it.stateChange == ItemEvent.SELECTED) }
}

private fun cfgForToolbar(btn: AbstractButton, tooltip: String, shortcutKeyCode: Int, shortcutModifiers: Int) {
    btn.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
    btn.isFocusable = false

    val shortcutHint = shortcutHint(shortcutKeyCode, shortcutModifiers)
    if (shortcutHint == null) {
        btn.toolTipText = tooltip
        return
    }
    btn.toolTipText = if ("<br>" !in tooltip) "$tooltip ($shortcutHint)" else {
        val idx = tooltip.indexOf("<br>")
        tooltip.substring(0, idx) + " ($shortcutHint)" + tooltip.substring(idx)
    }
}


fun JComponent.setTableCellBackground(table: JTable, rowIdx: Int) {
    background = if (rowIdx % 2 == 0) table.background else UIManager.getColor("Table.alternateRowColor")
}


fun Document.addDocumentListener(listener: (DocumentEvent) -> Unit) {
    addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener(e)
        override fun removeUpdate(e: DocumentEvent) = listener(e)
        override fun changedUpdate(e: DocumentEvent) = listener(e)
    })
}


/** Should be implemented by wrapper objects that are put into combo boxes. It's expected by the demo code. */
interface ComboBoxWrapper {
    val item: Any
}


class LargeCheckBox(size: Int) : JCheckBox() {

    init {
        icon = LargeCheckBoxIcon(size - 2)
        putClientProperty(STYLE, "margin: 1,0,1,0")
    }

    private class LargeCheckBoxIcon(private val size: Int) : FlatCheckBoxIcon() {

        private val innerFocusWidth = (UIManager.get("Component.innerFocusWidth") as Number).toFloat()

        init {
            focusWidth = 0f
            borderColor = UIManager.getColor("Component.borderColor")
            selectedBorderColor = borderColor
            focusedBackground = background
        }

        // scale() is actually necessary here because otherwise, the icon is too small, at least when the UI font size
        // is explicitly increased.
        override fun getIconWidth() = UIScale.scale(size)
        override fun getIconHeight() = UIScale.scale(size)

        override fun paintFocusBorder(c: Component, g: Graphics2D) {
            throw UnsupportedOperationException()
        }

        override fun paintBorder(c: Component, g: Graphics2D, borderWidth: Float) {
            if (borderWidth != 0f)
                g.fillRoundRect(0, 0, size, size, arc, arc)
        }

        override fun paintBackground(c: Component, g: Graphics2D, borderWidth: Float) {
            val bw = if (FlatUIUtils.isPermanentFocusOwner(c)) borderWidth + innerFocusWidth else borderWidth
            g.fill(RoundRectangle2D.Float(bw, bw, size - 2 * bw, size - 2 * bw, arc - bw, arc - bw))
        }

        override fun paintCheckmark(c: Component, g: Graphics2D) {
            g.preserveTransform {
                val offset = (size - 14) / 2
                g.translate(offset - 1, offset)
                g.stroke = BasicStroke(2f)
                g.draw(Path2D.Float().apply {
                    // Taken from FlatCheckBoxIcon:
                    moveTo(4.5f, 7.5f)
                    lineTo(6.6f, 10f)
                    lineTo(11.25f, 3.5f)
                })
            }
        }

    }

}


// borderInsets: Based on Component.borderWidth, which is 1 by default.
// background: Should be ComboBox.popupBackground, but that's not set by default, and the fallback is List.
private const val STYLE_LIKE_COMBO_BOX_POPUP = "borderInsets: 1,1,1,1; background: \$List.background"

class DropdownPopupMenu(
    private val owner: Component,
    private val preShow: (() -> Unit)? = null,
    private val postShow: (() -> Unit)? = null
) : JPopupMenu(), PopupMenuListener {

    var lastOpenTime = 0L
        private set
    private var lastCloseTime = 0L

    init {
        putClientProperty(STYLE, STYLE_LIKE_COMBO_BOX_POPUP)
        addPopupMenuListener(this)
    }

    // @formatter:off
    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) { lastOpenTime = System.currentTimeMillis() }
    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { lastCloseTime = System.currentTimeMillis() }
    override fun popupMenuCanceled(e: PopupMenuEvent) {}
    // @formatter:on

    fun toggle() {
        // When the user clicks on the box button while the popup is open, it first closes because the user clicked
        // outside the popup, and then the button is informed, triggering this method. This would immediately re-open
        // the popup. We avoid that via this hack.
        if (System.currentTimeMillis() - lastCloseTime < 100)
            return

        if (isVisible)
            isVisible = false
        else if (componentCount != 0) {
            preShow?.invoke()
            val ownerY = owner.locationOnScreen.y
            val ownerHeight = owner.height
            val popupHeight = preferredSize.height
            val screenBounds = owner.graphicsConfiguration.usableBounds
            val popupYRelToOwnerY = when {
                ownerY + ownerHeight + popupHeight <= screenBounds.y + screenBounds.height -> ownerHeight
                ownerY - popupHeight >= screenBounds.y -> -popupHeight
                else -> screenBounds.y + (screenBounds.height - popupHeight) / 2 - ownerY
            }
            show(owner, 0, popupYRelToOwnerY)
            postShow?.invoke()
        }
    }

    fun reactToOwnerKeyPressed(e: KeyEvent) {
        val m = e.modifiersEx
        val k = e.keyCode
        if (m == 0 && k == VK_SPACE ||
            m == ALT_DOWN_MASK && (k == VK_DOWN || k == VK_KP_DOWN || k == VK_UP || k == VK_KP_UP)
        )
            toggle()
    }

    fun addMouseListenerTo(btn: JComponent) {
        btn.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (btn.isEnabled && SwingUtilities.isLeftMouseButton(e))
                    SwingUtilities.invokeLater { toggle() }
            }
        })
    }

    fun addKeyListenerTo(btn: JComponent) {
        btn.addKeyListener(object : KeyAdapter() {
            // Note: We need invokeLater() here because otherwise, btn loses focus.
            override fun keyPressed(e: KeyEvent) = SwingUtilities.invokeLater { reactToOwnerKeyPressed(e) }
        })
    }

}

open class DropdownPopupMenuSubmenu(label: String) : JMenu(label) {
    init {
        iconTextGap = 0
        putClientProperty(STYLE, "minimumIconSize: 0, 0")
        popupMenu.putClientProperty(STYLE, STYLE_LIKE_COMBO_BOX_POPUP)
    }
}

open class DropdownPopupMenuItem(label: String, icon: Icon? = null) : JMenuItem(label, icon) {
    init {
        iconTextGap = 0
        putClientProperty(STYLE, "minimumIconSize: 0, 0")
    }
}

open class DropdownPopupMenuCheckBoxItem<E>(
    private val popup: DropdownPopupMenu,
    val item: E,
    label: String,
    icon: Icon? = null,
    isSelected: Boolean = false,
    private val closeOnMouseClick: Boolean = false
) : JCheckBoxMenuItem(label, icon, isSelected), ActionListener {

    init {
        if (!closeOnMouseClick)
            putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", true)
        addActionListener(this)
    }

    final override fun actionPerformed(e: ActionEvent) {
        // If we don't do this, the menu item loses the hover/navigation effect when it's toggled.
        if (!closeOnMouseClick)
            SwingUtilities.invokeLater { isArmed = true }

        // When the user opens a popup that is so long it overlaps the box button, him releasing the mouse
        // immediately afterward actually selects the item he's hovering over if he moved the mouse ever so
        // slightly. To avoid this undesired behavior, we cancel any item change that comes in too soon after the
        // popup has been opened.
        if (System.currentTimeMillis() - popup.lastOpenTime < 300) {
            removeActionListener(this)
            isSelected = !isSelected
            addActionListener(this)
            return
        }

        onToggle()
    }

    open fun onToggle() {}

}


class WordWrapCellRenderer(allowHtml: Boolean = false, private val shrink: Boolean = false) : TableCellRenderer {

    private val comp = when (allowHtml) {
        false -> newLabelTextArea(insets = true)
        true -> newLabelEditorPane("text/html")
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, rowIdx: Int, colIdx: Int
    ) = comp.apply {
        text = value as String
        setSize(table.columnModel.getColumn(colIdx).width, preferredSize.height)
        val newHeight = preferredSize.height
        val oldHeight = table.getRowHeight(rowIdx)
        if (if (shrink) newHeight != oldHeight else newHeight > oldHeight)
            table.setRowHeight(rowIdx, newHeight)
        setTableCellBackground(table, rowIdx)
    }

}


class LabeledListCellRenderer<E>(
    private val wrapped: ListCellRenderer<in E>,
    private val groupSpacing: Int = 0,
    private val getLabelLines: (Int) -> List<String>
) : ListCellRenderer<E> {

    override fun getListCellRendererComponent(
        list: JList<out E>, value: E?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val cell = wrapped.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        // Show the label lines only in the popup menu, but not in the combo box.
        if (index != -1) {
            val labelLines = getLabelLines(index)
            var b = (cell as JComponent).border
            if (b is CompoundBorder && b.outsideBorder is LabeledListCellRenderer<*>.LabelListCellBorder)
                b = b.insideBorder
            cell.border = if (labelLines.isEmpty()) b else CompoundBorder(LabelListCellBorder(list, labelLines), b)
        }
        return cell
    }

    /**
     * To be used in list cell components. Displays a separating label above the component.
     * Alternatively displays multiple separating labels above the component, interspersed by
     * non-separating labels. This can be used to show that some list category is empty.
     *
     * Inspired from here:
     * https://github.com/JFormDesigner/FlatLaf/blob/master/flatlaf-demo/src/main/java/com/formdev/flatlaf/demo/intellijthemes/ListCellTitledBorder.java
     */
    private inner class LabelListCellBorder(private val list: JList<*>, val lines: List<String>) : Border {

        override fun isBorderOpaque() = true
        override fun getBorderInsets(c: Component) =
            Insets(lines.size * c.getFontMetrics(list.font).height + groupSpacing * (lines.size - 1) / 2, 0, 0, 0)

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g as Graphics2D
            val fontMetrics = c.getFontMetrics(list.font)

            // Draw the list background.
            g2.color = list.background
            g2.fillRect(x, y, width, getBorderInsets(c).top)

            FlatUIUtils.setRenderingHints(g2)
            g2.color = UIManager.getColor("Label.disabledForeground")
            g2.font = list.font

            for ((line, text) in lines.withIndex()) {
                val lineY = y + line * fontMetrics.height + line / 2 * groupSpacing
                val textWidth = fontMetrics.stringWidth(text)
                // Draw the centered string.
                FlatUIUtils.drawString(list, g2, text, x + (width - textWidth) / 2, lineY + fontMetrics.ascent)
                // On even lines, draw additional separator lines.
                if (line % 2 == 0) {
                    val sepGap = 4
                    val sepWidth = (width - textWidth) / 2 - 2 * sepGap
                    if (sepWidth > 0) {
                        val sepY = lineY + fontMetrics.height / 2
                        val sepHeight = 1
                        g2.fillRect(x + sepGap, sepY, sepWidth, sepHeight)
                        g2.fillRect((x + width - sepGap - sepWidth), sepY, sepWidth, sepHeight)
                    }
                }
            }
        }

    }

}


var minimumWindowSize = Dimension(700, 500)

fun JFrame.setup() {
    minimumSize = minimumWindowSize
    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
}

fun JDialog.setup() {
    minimumSize = minimumWindowSize
    defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    // On macOS, enable the system-native full-screen buttons also for dialogs.
    if (SystemInfo.isMacOS)
        setWindowCanFullScreenMacOS(this, true)
}

fun Window.center(onScreen: GraphicsConfiguration, widthFrac: Double, heightFrac: Double) {
    val winBounds = onScreen.usableBounds
    val width = (winBounds.width * widthFrac).roundToInt().coerceAtLeast(minimumSize.width)
    val height = (winBounds.height * heightFrac).roundToInt().coerceAtLeast(minimumSize.height)
    setBounds(
        winBounds.x + (winBounds.width - width) / 2,
        winBounds.y + (winBounds.height - height) / 2,
        width, height
    )
}

var disableSnapToSide = false

fun Window.snapToSide(onScreen: GraphicsConfiguration, rightSide: Boolean) {
    if (disableSnapToSide)
        return

    val winBounds = onScreen.usableBounds
    winBounds.width /= 2

    // If the window should snap to the right side, move its x coordinate.
    if (rightSide)
        winBounds.x += winBounds.width

    // Apply the computed window bounds.
    bounds = winBounds

    // On Windows 10, windows have a thick invisible border which resides inside the window bounds.
    // If we do nothing about it, the window is not flush with the sides of the screen, but there's a thick strip
    // of empty space between the window and the left, right, and bottom sides of the screen.
    // To fix this, we find the exact thickness of the border by querying the window's insets (which we can only do
    // after the window has been opened, hence the listener) and add those insets to the window bounds on the left,
    // right, and bottom sides.
    if (SystemInfo.isWindows_10_orLater)
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                val winInsets = insets
                setBounds(
                    winBounds.x - winInsets.left,
                    winBounds.y,
                    winBounds.width + winInsets.left + winInsets.right,
                    winBounds.height + winInsets.bottom
                )
            }
        })

    // On Linux, when a window that vertically covers the entire screen is made visible for the first time, AWT sets its
    // extendedState property to MAXIMIZED_VERT. Unfortunately, that state has a couple of problems:
    //   - When the user drags the window, it will get "minimized", i.e. it shrinks vertically. This is different from
    //     the behavior on other OS, and quite inconvenient as it could mess up the window's vertical layout.
    //   - FlatLaf's custom window decorations disallow resizing the window by dragging its boundaries.
    //   - When clicking on the "maximize" button in the window's title bar, a special Linux condition in FlatLaf's
    //     FlatTitlePane.maximize() method actually minimizes the window vertically, but maximizes it horizontally,
    //     i.e. it shrinks vertically and expands horizontally.
    // Our fix is to avoid the extendedState ever being set to MAXIMIZED_VERT. To trick AWT into this, we shrink the
    // window by 1 pixel vertically, then make it visible, and finally add the pixel back.
    if (SystemInfo.isLinux) {
        setSize(winBounds.width, winBounds.height - 1)
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                size = winBounds.size
            }
        })
    }
}

val GraphicsConfiguration.usableBounds: Rectangle
    get() {
        val bounds = bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(this)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom
        )
    }


class KeyListener(
    private val shortcutKeyCode: Int,
    private val shortcutModifiers: Int,
    private val listener: () -> Unit
) {
    fun onKeyEvent(e: KeyEvent): Boolean {
        val match = e.keyCode == shortcutKeyCode && e.modifiersEx == shortcutModifiers
        if (match)
            listener()
        return match
    }
}

fun shortcutHint(shortcutKeyCode: Int, shortcutModifiers: Int): String? =
    if (shortcutKeyCode == 0) null else buildString {
        if (shortcutModifiers != 0)
            append(getModifiersExText(shortcutModifiers)).append("+")
        append(getKeyText(shortcutKeyCode))
    }


fun tryOpen(file: Path) = openCascade(Desktop.Action.OPEN, Desktop::open, file.toFile(), file.toUri())
fun tryEdit(file: Path) = openCascade(Desktop.Action.EDIT, Desktop::edit, file.toFile(), file.toUri())
fun tryBrowse(uri: URI) = openCascade(Desktop.Action.BROWSE, Desktop::browse, uri, uri)
fun tryMail(uri: URI) = openCascade(Desktop.Action.MAIL, Desktop::mail, uri, uri)

private fun <T> openCascade(desktopAction: Desktop.Action, desktopFun: Desktop.(T) -> Unit, desktopArg: T, uri: URI) {
    fun tryDesktopAction() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(desktopAction))
            try {
                Desktop.getDesktop().desktopFun(desktopArg)
            } catch (e: Exception) {
                LOGGER.error("Failed to perform {} for '{}'.", desktopAction, desktopArg, e)
            }
    }

    fun tryExec(cmd: String, env: Map<String, String>? = null) = try {
        execProcess(listOf(cmd, uri.toString()), env)
        true
    } catch (e: IOException) {
        LOGGER.error(e.message)
        false
    }

    // This cascade is required because:
    //   - Desktop.open() doesn't always open the actually configured file browser on KDE.
    //   - KDE is not supported by Desktop.browse()/mail().
    if (SystemInfo.isLinux)
        GLOBAL_THREAD_POOL.submit(throwableAwareTask {
            if (!SystemInfo.isKDE || !tryExec("kde-open", env = mapOf("QT_XCB_GL_INTEGRATION" to "none")))
                if (!tryExec("xdg-open"))
                    tryDesktopAction()
        })
    else
        tryDesktopAction()
}


/**
 * On macOS, one is unable to show a progress bar on top of the app's icon in the dock as soon as the app is launched
 * via a jpackage-built binary. Fortunately, updating the icon once on startup with this function resolves the issue.
 *
 * This bug will hopefully be fixed in a future version of the JDK. It is tracked here:
 * https://bugs.openjdk.org/browse/JDK-8300037
 */
fun fixTaskbarProgressBarOnMacOS() {
    if (SystemInfo.isMacOS && Taskbar.Feature.ICON_IMAGE.isSupported)
        taskbar!!.iconImage = taskbar.iconImage
}

fun trySetTaskbarIconBadge(badge: Int) {
    if (Taskbar.Feature.ICON_BADGE_NUMBER.isSupported)
        taskbar!!.setIconBadge(if (badge == 0) null else badge.toString())
}

fun trySetTaskbarProgress(window: Window, percent: Int) {
    if (Taskbar.Feature.PROGRESS_VALUE.isSupported)
        taskbar!!.setProgressValue(percent)
    if (Taskbar.Feature.PROGRESS_VALUE_WINDOW.isSupported)
        taskbar!!.setWindowProgressValue(window, percent)
}

fun tryRequestUserAttentionInTaskbar(window: Window) {
    if (Taskbar.Feature.USER_ATTENTION.isSupported)
        taskbar!!.requestUserAttention(true, false)
    if (Taskbar.Feature.USER_ATTENTION_WINDOW.isSupported)
        taskbar!!.requestWindowUserAttention(window)
}

private val taskbar = if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else null

private val Taskbar.Feature.isSupported
    get() = taskbar != null && taskbar.isSupported(this)
