package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.ui.FlatArrowButton
import com.formdev.flatlaf.ui.FlatRoundBorder
import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.ceilDiv
import com.loadingbyte.cinecred.common.withNewG2
import java.awt.Dimension
import java.awt.Graphics
import java.awt.ItemSelectable
import java.awt.event.*
import java.awt.event.KeyListener
import javax.swing.*


class MultiComboBox<E : Any>(
    toString: (E) -> String = { it.toString() },
    private val inconsistent: Boolean = false,
    private val noItemsMessage: String? = null
) : JComponent(), ItemSelectable, FocusListener, MouseListener, KeyListener {

    private val arc = (UIManager.get("Component.arc") as Number).toFloat()
    private val focusWidth = (UIManager.get("Component.focusWidth") as Number).toFloat()
    private val padding = UIManager.getInsets("ComboBox.padding")
    private val arrowType = UIManager.getString("Component.arrowType")
    private val normalBackground = UIManager.getColor("ComboBox.background")
    private val focusedBackground = UIManager.getColor("ComboBox.focusedBackground")
    private val disabledBackground = UIManager.getColor("ComboBox.disabledBackground")
    private val buttonArrowColor = UIManager.getColor("ComboBox.buttonArrowColor")
    private val buttonDisabledArrowColor = UIManager.getColor("ComboBox.buttonDisabledArrowColor")
    private val buttonHoverArrowColor = UIManager.getColor("ComboBox.buttonHoverArrowColor")
    private val buttonPressedArrowColor = UIManager.getColor("ComboBox.buttonPressedArrowColor")

    private val selectionLabel = JLabel(" ")
    private val arrowButton = CustomArrowButton()
    private val popup = DropdownPopupMenu(this)
    private val overflowSeparator = JSeparator()

    private var hover = false
    private var pressed = false

    init {
        val arrowButtonPad = 3

        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createCompoundBorder(
            FlatRoundBorder(),
            BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, arrowButtonPad)
        )
        isFocusable = true

        // The label is allowed to shrink (it automatically adds ellipsis when there is not enough space).
        selectionLabel.minimumSize = Dimension(0, selectionLabel.minimumSize.height)

        if (noItemsMessage != null)
            popup.add(makeMessageMenuItem(noItemsMessage))

        add(selectionLabel)
        add(Box.createHorizontalGlue())
        add(Box.createHorizontalStrut(padding.right + arrowButtonPad))
        add(arrowButton)

        addPropertyChangeListener("enabled") { arrowButton.isEnabled = isEnabled }
        addPropertyChangeListener("focusable") { arrowButton.isFocusable = isFocusable }
        addPropertyChangeListener(OUTLINE) { repaint() }
        addFocusListener(this)
        addMouseListener(this)
        addKeyListener(this)

        val toggleMouseListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isEnabled && SwingUtilities.isLeftMouseButton(e)) {
                    if (isRequestFocusEnabled)
                        requestFocusInWindow()
                    popup.toggle()
                }
            }
        }
        addMouseListener(toggleMouseListener)
        arrowButton.addMouseListener(toggleMouseListener)
    }

    var toString: (E) -> String = toString
        set(toString) {
            if (field == toString)
                return
            field = toString
            updateSelectionLabel()
            for (idx in 0..<popup.componentCount) {
                val menuItem = popup.getComponent(idx)
                if (menuItem is MultiComboBox<*>.CustomMenuItem)
                    @Suppress("UNCHECKED_CAST")
                    menuItem.text = toString((menuItem as MultiComboBox<E>.CustomMenuItem).item)
            }
        }

    var items: List<E> = emptyList()
        set(items) {
            if (field == items)
                return
            field = items

            val sel = selectedItems
            // First remove all old items, then add the new regular items. If the combo box is inconsistent, also record
            // all previously selected items which do not appear in the new regular items ("overflow").
            val over = if (inconsistent) HashSet(sel) else null
            popup.removeAll()
            if (items.isNotEmpty())
                for (item in items)
                    popup.add(CustomMenuItem(item, isSelected = over?.remove(item) ?: (item in sel)))
            else if (noItemsMessage != null)
                popup.add(makeMessageMenuItem(noItemsMessage))
            // Add the recorded overflow items if there are any.
            if (!over.isNullOrEmpty()) {
                popup.add(overflowSeparator)
                for (item in over)
                    popup.add(CustomMenuItem(item, isSelected = true, isOverflow = true))
            }

            popup.pack()
            updateSelectionLabel()
        }

    var selectedItems: Set<E>
        get() = buildSet {
            for (idx in 0..<popup.componentCount) {
                val menuItem = popup.getComponent(idx)
                if (menuItem is MultiComboBox<*>.CustomMenuItem && menuItem.isSelected)
                    @Suppress("UNCHECKED_CAST")
                    add((menuItem as MultiComboBox<E>.CustomMenuItem).item)
            }
        }
        set(selectedItems) {
            val numFixedMenuItems = getNumFixedMenuItems()
            // Mark all regular items as selected or deselected. If the combo box is inconsistent, also record all
            // selected items which do not appear in the regular items ("overflow").
            val over = if (inconsistent) HashSet(selectedItems) else null
            for (idx in 0..<numFixedMenuItems) {
                val menuItem = popup.getComponent(idx)
                if (menuItem is MultiComboBox<*>.CustomMenuItem)
                    menuItem.isSelected = over?.remove(menuItem.item) ?: (menuItem.item in selectedItems)
            }
            // First remove all previous overflow items, then add the now selected overflow items.
            val wasOverflown = popup.componentCount > numFixedMenuItems
            while (popup.componentCount > numFixedMenuItems)
                popup.remove(numFixedMenuItems)
            if (!over.isNullOrEmpty()) {
                popup.add(overflowSeparator)
                for (item in over)
                    popup.add(CustomMenuItem(item, isSelected = true, isOverflow = true))
            }
            val isOverflown = popup.componentCount > numFixedMenuItems

            if (wasOverflown || isOverflown)
                popup.pack()
            updateSelectionLabel()
        }

    /** Possible values are [OUTLINE_ERROR] and [OUTLINE_WARNING]. */
    var overflowColor: String? = null
        set(overflowColor) {
            if (field == overflowColor)
                return
            field = overflowColor

            val sepStyle = when (overflowColor) {
                null -> ""
                OUTLINE_ERROR -> "foreground: \$Component.error.borderColor"
                OUTLINE_WARNING -> "foreground: \$Component.warning.borderColor"
                else -> throw IllegalArgumentException("Unknown overflow color: $overflowColor")
            }
            overflowSeparator.putClientProperty(STYLE, sepStyle)

            for (idx in getNumFixedMenuItems() + 1 /* skip separator */..<popup.componentCount)
                (popup.getComponent(idx) as MultiComboBox<*>.CustomMenuItem).updateStyle()
        }

    private fun getNumFixedMenuItems() =
        if (noItemsMessage != null && items.isEmpty()) 1 else items.size

    private fun updateSelectionLabel() {
        selectionLabel.text = selectedItems.joinToString(transform = toString).ifEmpty { " " }
    }

    // @formatter:off
    override fun focusGained(e: FocusEvent) { repaint() }
    override fun focusLost(e: FocusEvent) { repaint() }
    override fun mouseClicked(e: MouseEvent) {}
    override fun mouseEntered(e: MouseEvent) { hover = true; arrowButton.repaint() }
    override fun mouseExited(e: MouseEvent) { hover = false; arrowButton.repaint() }
    override fun mousePressed(e: MouseEvent) { pressed = true; arrowButton.repaint() }
    override fun mouseReleased(e: MouseEvent) { pressed = false; arrowButton.repaint() }
    override fun keyTyped(e: KeyEvent) {}
    override fun keyPressed(e: KeyEvent) { popup.reactToOwnerKeyPressed(e) }
    override fun keyReleased(e: KeyEvent) {}

    override fun getSelectedObjects(): Array<Any> = (selectedItems as Set<Any>).toTypedArray()
    override fun addItemListener(listener: ItemListener) { listenerList.add(ItemListener::class.java, listener) }
    override fun removeItemListener(listener: ItemListener) { listenerList.remove(ItemListener::class.java, listener) }
    // @formatter:on

    private fun fireItemStateChanged(item: E, isSelected: Boolean) {
        val stateChange = if (isSelected) ItemEvent.SELECTED else ItemEvent.DESELECTED
        val e = ItemEvent(this, ItemEvent.ITEM_STATE_CHANGED, item, stateChange)
        val listeners = listenerList.listenerList
        var idx = listeners.size - 2
        while (idx >= 0) {
            if (listeners[idx] == ItemListener::class.java)
                (listeners[idx + 1] as ItemListener).itemStateChanged(e)
            idx -= 2
        }
    }

    override fun getBaseline(width: Int, height: Int): Int =
        ceilDiv(height - selectionLabel.height, 2) +
                selectionLabel.getBaseline(selectionLabel.width, selectionLabel.height)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        g.withNewG2 { g2 ->
            FlatUIUtils.setRenderingHints(g2)
            g2.color = when {
                !isEnabled -> disabledBackground
                focusedBackground != null && FlatUIUtils.isPermanentFocusOwner(this) -> focusedBackground
                else -> normalBackground
            }
            FlatUIUtils.paintComponentBackground(g2, 0, 0, width, height, focusWidth, arc)
        }
    }


    private inner class CustomArrowButton : FlatArrowButton(
        SwingConstants.SOUTH, arrowType, buttonArrowColor, buttonDisabledArrowColor, buttonHoverArrowColor, null,
        buttonPressedArrowColor, null
    ) {
        override fun getMinimumSize(): Dimension = preferredSize  // Would otherwise permit shrinking.
        override fun getMaximumSize(): Dimension = preferredSize  // Would otherwise return humongous values.
        override fun isHover(): Boolean = super.isHover() || hover
        override fun isPressed(): Boolean = super.isPressed() || pressed
    }


    private fun makeMessageMenuItem(message: String) = JMenuItem(message, INFO_ICON).apply {
        isEnabled = false
        putClientProperty(STYLE, "margin: \$ComboBox.padding")
    }

    private inner class CustomMenuItem(item: E, isSelected: Boolean, private val isOverflow: Boolean = false) :
        DropdownPopupMenuCheckBoxItem<E>(popup, item, toString(item), isSelected = isSelected) {

        init {
            updateStyle()
        }

        fun updateStyle() {
            val overflowStyling = if (!isOverflow || overflowColor == null) "" else {
                val colorRef = when (overflowColor) {
                    OUTLINE_ERROR -> "\$Component.error.focusedBorderColor"
                    OUTLINE_WARNING -> "\$Component.warning.focusedBorderColor"
                    else -> throw IllegalArgumentException("Unknown overflow color: $overflowColor")
                }
                "icon.checkmarkColor: $colorRef; icon.selectionForeground: $colorRef"
            }
            putClientProperty(STYLE, "margin: \$ComboBox.padding; $overflowStyling")
        }

        override fun onToggle() {
            if (isOverflow && !isSelected) {
                popup.remove(this)
                if (popup.componentCount == getNumFixedMenuItems() + 1 /* +1 is the separator */)
                    popup.remove(overflowSeparator)
                popup.pack()
            }

            updateSelectionLabel()
            fireItemStateChanged(item, isSelected)
        }

    }

}
