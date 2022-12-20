package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.FontFeature
import com.loadingbyte.cinecred.project.Opt
import com.loadingbyte.cinecred.project.TimecodeFormat
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.io.File
import java.nio.file.Path
import java.text.NumberFormat
import java.text.ParseException
import java.util.*
import javax.swing.*
import javax.swing.JScrollPane.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.text.DefaultFormatter
import javax.swing.text.DefaultFormatterFactory
import javax.swing.text.JTextComponent
import javax.swing.text.PlainDocument
import kotlin.io.path.Path
import kotlin.math.max


enum class WidthSpec(val mig: String) {
    FREE(""),
    TINIER("width 50:50:"),
    TINY("width 70:70:"),
    NARROW("width 100:100:"),
    SQUEEZE("width 100::max(200,30%)"),
    FIT("width 100::max(300,40%)"),
    WIDE("width 100:max(300,40%):"),
    WIDER("width 100:max(300,50%):"),
    FILL("width 100:100%:")
}


private const val STD_HEIGHT = 24


abstract class AbstractTextComponentWidget<V : Any>(
    protected val tc: JTextComponent,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    override val components = listOf<JComponent>(tc)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.WIDE).mig)

    protected var text: String
        get() = tc.text
        set(text) {
            // Avoid the field's cursor skipping around when the text is in fact not changed.
            if (this.text == text)
                return
            // Do not change tc.text but instead replace the whole document to prevent document events from being
            // triggered, which would scroll the wrapping scroll pane's viewport.
            tc.document = PlainDocument().apply {
                insertString(0, text, null)
                addDocumentListener { notifyChangeListeners() }
            }
            notifyChangeListeners()
        }

    init {
        // Make sure that the document listener is installed.
        text = ""
    }

}


class TextWidget(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<String>(JTextField(), widthSpec) {
    override var value: String
        get() = text
        set(value) {
            text = value
        }
}


class TextListWidget(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<List<String>>(JTextArea(), widthSpec) {
    override val components = listOf(JScrollPane(tc, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER))
    override val constraints = listOf(super.constraints.single() + ", hmax ${15 * STD_HEIGHT}")
    override var value: List<String>
        get() = text.split("\n").filter(String::isNotBlank)
        set(value) {
            text = value.filter(String::isNotBlank).joinToString("\n")
        }
}


class FileExtAssortment(val choices: List<String>, val default: String) {
    init {
        require(default in choices)
    }
}

abstract class AbstractFilenameWidget<V : Any>(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<V>(JTextField(), widthSpec) {

    init {
        // When the user leaves the text field, ensure that it ends with an admissible file extension.
        tc.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val ass = fileExtAssortment
                if (ass != null && ass.choices.none { text.endsWith(".$it", ignoreCase = true) })
                    text += ".${ass.default}"
            }
        })
    }

    var fileExtAssortment: FileExtAssortment? = null
        set(newAssortment) {
            if (field == newAssortment)
                return
            // When the list of admissible file extensions is changed and the field text doesn't end with an
            // admissible file extension anymore, remove the previous file extension (if there was any) and add
            // the default new one.
            if (newAssortment != null && newAssortment.choices.none { text.endsWith(".$it", ignoreCase = true) }) {
                field?.let { old -> text = text.removeAnySuffix(old.choices.map { ".$it" }, ignoreCase = true) }
                text += ".${newAssortment.default}"
            }
            field = newAssortment
        }

}


class FilenameWidget(
    widthSpec: WidthSpec? = null
) : AbstractFilenameWidget<String>(widthSpec) {
    override var value: String
        get() = text.trim()
        set(value) {
            text = value.trim()
        }
}


enum class FileType { FILE, DIRECTORY }

class FileWidget(
    fileType: FileType,
    widthSpec: WidthSpec? = null
) : AbstractFilenameWidget<Path>() {

    private val browse = JButton(l10n("ui.form.browse"), FOLDER_ICON)

    init {
        browse.addActionListener {
            val ass = fileExtAssortment

            val fc = JFileChooser()
            fc.fileSelectionMode = when (fileType) {
                FileType.FILE -> JFileChooser.FILES_ONLY
                FileType.DIRECTORY -> JFileChooser.DIRECTORIES_ONLY
            }
            fc.selectedFile = File(text)

            if (ass != null) {
                fc.isAcceptAllFileFilterUsed = false
                for (fileExt in ass.choices) {
                    val filter = FileNameExtensionFilter("*.$fileExt", fileExt)
                    fc.addChoosableFileFilter(filter)
                    if (text.endsWith(".$fileExt"))
                        fc.fileFilter = filter
                }
            }

            if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                var newText = fc.selectedFile.absolutePath
                if (ass != null) {
                    val selectedFileExt = (fc.fileFilter as FileNameExtensionFilter).extensions.single()
                    newText = text.removeAnySuffix(ass.choices.map { ".$it" }) + ".$selectedFileExt"
                }
                text = newText
            }
        }
    }

    override val components = listOf<JComponent>(tc, browse)
    override val constraints = listOf((widthSpec ?: WidthSpec.WIDE).mig, "")

    override var value: Path
        get() = Path(text.trim())
        set(value) {
            text = value.toString()
        }

}


open class SpinnerWidget<V : Number>(
    private val valueClass: Class<V>,
    model: SpinnerModel,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    init {
        require(!valueClass.isPrimitive)
    }

    protected val spinner = JSpinner(model).apply {
        addChangeListener { notifyChangeListeners() }
        ((editor as JSpinner.DefaultEditor).textField.formatter as DefaultFormatter).makeSafe()
    }

    override val components = listOf(spinner)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.NARROW).mig)

    override var value: V
        get() = valueClass.cast(spinner.value)
        set(value) {
            spinner.value = value
        }

}


class TimecodeWidget(
    model: SpinnerNumberModel,
    fps: FPS,
    timecodeFormat: TimecodeFormat,
    widthSpec: WidthSpec? = null
) : SpinnerWidget<Int>(Int::class.javaObjectType, model, widthSpec ?: WidthSpec.FIT) {

    var fps: FPS = fps
        set(fps) {
            if (field == fps)
                return
            field = fps
            updateFormatter()
        }

    var timecodeFormat: TimecodeFormat = timecodeFormat
        set(timecodeFormat) {
            if (field == timecodeFormat)
                return
            field = timecodeFormat
            updateFormatter()
        }

    private val editor = object : JSpinner.DefaultEditor(spinner) {
        init {
            textField.isEditable = true
        }

        // There is a subtle bug in the JDK which causes JTextField and its subclasses to not utilize a one pixel wide
        // column at the right for drawing; instead, it is always empty. However, this column is needed to draw the
        // caret at the rightmost position when the text field has the preferred size that exactly matches the text
        // width (which happens to this spinner when the width spec is kept as FIT). Hence, when the user positions the
        // caret at the rightmost location, the text scrolls one pixel to the left to accommodate the caret. We did not
        // manage to localize the source of this bug, but as a workaround, we just add one more pixel to the preferred
        // width, thereby providing the required space for the caret when it is at the rightmost position.
        override fun getPreferredSize() = super.getPreferredSize().apply { if (!isPreferredSizeSet) width += 1 }
    }

    init {
        spinner.putClientProperty(STYLE_CLASS, "monospaced")
        spinner.editor = editor
        updateFormatter()
    }

    private fun updateFormatter() {
        editor.textField.formatterFactory = DefaultFormatterFactory(TimecodeFormatter(fps, timecodeFormat))
    }


    private class TimecodeFormatter(
        private val fps: FPS,
        private val timecodeFormat: TimecodeFormat
    ) : DefaultFormatter() {
        init {
            makeSafe()
        }

        // We need to catch exceptions here because formatTimecode() throws some when using non-fractional FPS together
        // with a drop-frame timecode.
        override fun valueToString(value: Any?): String =
            runCatching { formatTimecode(fps, timecodeFormat, value as Int) }.getOrDefault("")

        override fun stringToValue(string: String?): Int =
            runCatching { parseTimecode(fps, timecodeFormat, string!!) }.getOrElse { throw ParseException("", 0) }
    }

}


class CheckBoxWidget : Form.AbstractWidget<Boolean>() {

    private val cb = LargeCheckBox(STD_HEIGHT).apply {
        addItemListener { notifyChangeListeners() }
    }

    override val components = listOf(cb)
    override val constraints = listOf("")

    override var value: Boolean
        get() = cb.isSelected
        set(value) {
            cb.isSelected = value
        }

}


open class ComboBoxWidget<V : Any>(
    protected val valueClass: Class<V>,
    items: List<V>,
    toString: (V) -> String = { it.toString() },
    widthSpec: WidthSpec? = null,
    private val scrollbar: Boolean = true,
    decorateRenderer: ((ListCellRenderer<V>) -> ListCellRenderer<V>) = { it }
) : Form.AbstractWidget<V>(), Form.Choice<V> {

    protected val cb = JComboBox<V>().apply {
        addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
        renderer = decorateRenderer(CustomToStringListCellRenderer(valueClass, toString))
        keySelectionManager = CustomToStringKeySelectionManager(valueClass, toString)
    }

    override val components = listOf(cb)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.FIT).mig)

    final override var items: List<V> = listOf()
        set(items) {
            if (field == items)
                return
            field = items
            val oldSelectedItem = cb.selectedItem?.let(valueClass::cast)
            cb.model = makeModel(Vector(items), oldSelectedItem)
            if (cb.selectedItem != oldSelectedItem)
                notifyChangeListeners()
            if (!scrollbar)
                cb.maximumRowCount = items.size
        }

    protected open fun makeModel(items: Vector<V>, oldSelectedItem: V?) =
        DefaultComboBoxModel(items).apply {
            if (oldSelectedItem in items)
                selectedItem = oldSelectedItem
        }

    override var value: V
        get() = valueClass.cast(cb.selectedItem!!)
        set(value) {
            cb.selectedItem = value
        }

    init {
        this.items = items
    }

}


class InconsistentComboBoxWidget<V : Any>(
    valueClass: Class<V>,
    items: List<V>,
    toString: (V) -> String = { it.toString() },
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<V>(valueClass, items, toString, widthSpec) {

    override fun makeModel(items: Vector<V>, oldSelectedItem: V?) =
        DefaultComboBoxModel(items).apply {
            // Also sets selectedItem to null if oldSelectedItem was null before.
            selectedItem = oldSelectedItem
        }

    override var value: V
        get() = super.value
        set(value) {
            cb.isEditable = value !in items
            super.value = value
            cb.isEditable = false
        }

}


open class EditableComboBoxWidget<V : Any>(
    valueClass: Class<V>,
    items: List<V>,
    private val toString: (V) -> String,
    private val fromString: ((String) -> V),
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<V>(valueClass, items, toString, widthSpec) {

    init {
        cb.isEditable = true
        cb.editor = CustomComboBoxEditor()
    }

    override fun makeModel(items: Vector<V>, oldSelectedItem: V?) =
        DefaultComboBoxModel(items).apply {
            if (oldSelectedItem != null)
                selectedItem = oldSelectedItem
        }


    private inner class CustomComboBoxEditor : BasicComboBoxEditor() {
        override fun createEditorComponent(): JTextField =
            JFormattedTextField().apply {
                border = null
                formatterFactory = DefaultFormatterFactory(CustomFormatter())
                addPropertyChangeListener("value") { e -> cb.selectedItem = e.newValue }
            }

        override fun getItem(): Any = (editor as JFormattedTextField).value
        override fun setItem(item: Any) = run { (editor as JFormattedTextField).value = item }
    }


    private inner class CustomFormatter : DefaultFormatter() {
        init {
            makeSafe()
            overwriteMode = false
        }

        override fun valueToString(value: Any?): String =
            value?.let { toString(this@EditableComboBoxWidget.valueClass.cast(it)) } ?: ""

        override fun stringToValue(string: String?): Any =
            runCatching { fromString(string!!) }.getOrElse { throw ParseException("", 0) }
    }

}


class FPSWidget(
    widthSpec: WidthSpec? = null
) : EditableComboBoxWidget<FPS>(FPS::class.java, SUGGESTED_FPS, ::toDisplayString, ::fromDisplayString, widthSpec) {

    companion object {

        private val SUGGESTED_FRAC_FPS = listOf(
            23.98 to FPS(24000, 1001),
            29.97 to FPS(30000, 1001),
            47.95 to FPS(48000, 1001),
            59.94 to FPS(60000, 1001)
        )
        private val SUGGESTED_FPS: List<FPS>

        init {
            val suggestedIntFPS = listOf(24, 25, 30, 48, 50, 60)
                .map { it.toDouble() to FPS(it, 1) }
            SUGGESTED_FPS = (SUGGESTED_FRAC_FPS + suggestedIntFPS)
                .sortedBy { it.first }
                .map { it.second }
        }

        private fun toDisplayString(fps: FPS): String {
            val frac = SUGGESTED_FRAC_FPS.find { it.second == fps }?.first
            return when {
                frac != null -> NumberFormat.getNumberInstance().format(frac)
                fps.denominator == 1 -> fps.numerator.toString()
                else -> fps.toFraction()
            }
        }

        private fun fromDisplayString(str: String): FPS {
            runCatching { NumberFormat.getNumberInstance().parse(str) }.onSuccess { frac ->
                SUGGESTED_FRAC_FPS.find { it.first == frac }?.let { return it.second }
            }
            runCatching(str::toInt).onSuccess { return FPS(it, 1) }
            return fpsFromFraction(str)
        }

    }

}


class MultiComboBoxWidget<E : Any>(
    items: List<E>,
    private val comparator: Comparator<E>,
    toString: (E) -> String = { it.toString() },
    widthSpec: WidthSpec? = null,
    inconsistent: Boolean = false,
    noItemsMessage: String? = null
) : Form.AbstractWidget<List<E>>(), Form.Choice<E> {

    private val mcb = MultiComboBox(toString, inconsistent, noItemsMessage).apply {
        // Notice that this item listener is only triggered when the user manually (de)selects items. That's why we
        // manually notify the change listeners when the user operations implemented below lead to a changed selection.
        addItemListener { notifyChangeListeners() }
    }

    override val components = listOf(mcb)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.FIT).mig)

    override var items: List<E>
        get() = mcb.items
        set(items) {
            val oldSelectedItems = mcb.selectedItems
            mcb.items = items
            if (mcb.selectedItems != oldSelectedItems)
                notifyChangeListeners()
        }

    override var value: List<E>
        get() = mcb.selectedItems.sortedWith(comparator)
        set(value) {
            val valueAsSet = value.toSet()
            if (mcb.selectedItems == valueAsSet)
                return
            mcb.selectedItems = valueAsSet
            notifyChangeListeners()
        }

    override fun applySeverity(index: Int, severity: Severity?) {
        super.applySeverity(index, severity)
        mcb.overflowColor = outline(severity)
    }

    init {
        mcb.items = items
    }

}


class ToggleButtonGroupWidget<V : Any>(
    items: List<V>,
    toIcon: ((V) -> Icon)? = null,
    private val toLabel: ((V) -> String)? = null,
    private val toTooltip: ((V) -> String)? = null,
    private val inconsistent: Boolean = false
) : Form.AbstractWidget<V>(), Form.Choice<V> {

    private val panel = GroupPanel()
    private val btnGroup = ButtonGroup()

    private var overflowItem: V? = null

    override val components = listOf<JComponent>(panel)
    override val constraints = listOf("")

    override var items: List<V> = listOf()
        set(items) {
            if (field == items)
                return
            val oldSelItem = if (field.isNotEmpty() || overflowItem != null) value else null
            field = items
            // Remove the previous buttons.
            panel.removeAll()
            for (elem in btnGroup.elements.toList() /* copy for concurrent modification */)
                btnGroup.remove(elem)
            // Add buttons for the new items.
            for (item in items)
                addButton(item)
            // If the overflow item is now available in the actual items, discard it.
            if (overflowItem != null && overflowItem in items)
                overflowItem = null
            // In inconsistent mode, if the previously selected item is no longer available, make it the overflow item.
            if (inconsistent && oldSelItem != null && oldSelItem !in items)
                overflowItem = oldSelItem
            // Select the adequate button or add the overflow item if that's set.
            if (overflowItem != null) {
                val overflowBtn = addButton(overflowItem!!)
                withoutChangeListeners { overflowBtn.isSelected = true }
            } else {
                val oldSelIdx = if (oldSelItem == null) -1 else items.indexOf(oldSelItem)
                // We default to selecting the first button because the absence of a selection is an invalid state and
                // the value getter wouldn't know what to return.
                val selBtn = btnGroup.elements.asSequence().drop(oldSelIdx.coerceAtLeast(0)).first()
                withoutChangeListeners { selBtn.isSelected = true }
                if (oldSelIdx == -1)
                    notifyChangeListeners()
            }
        }

    var toIcon: ((V) -> Icon)? = toIcon
        set(toIcon) {
            field = toIcon
            btnGroup.elements.asSequence().forEachIndexed { idx, btn ->
                btn.icon = toIcon?.invoke(items.getOrElse(idx) { overflowItem!! })
            }
        }

    override var value: V
        get() = overflowItem ?: items[btnGroup.elements.asSequence().indexOfFirst { it.isSelected }]
        set(value) {
            if ((items.isNotEmpty() || overflowItem != null) && this.value == value)
                return
            if (overflowItem != null)
                removeOverflow()
            if (value !in items) {
                require(inconsistent)
                overflowItem = value
                btnGroup.setSelected(addButton(value).model, true)
            } else
                btnGroup.setSelected(btnGroup.elements.asSequence().drop(items.indexOf(value)).first().model, true)
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            for (btn in btnGroup.elements)
                btn.isEnabled = isEnabled
        }

    init {
        this.items = items
    }

    private fun addButton(item: V) = JToggleButton().also { btn ->
        toIcon?.let { btn.icon = it(item) }
        toLabel?.let { btn.text = it(item) }
        toTooltip?.let { btn.toolTipText = it(item) }
        if (toLabel != null) {
            // Make room for GroupPanel's border. This way, the panel will have the same height as other components.
            val panelInsets = panel.insets
            btn.margin = btn.margin.run { Insets(top - panelInsets.top, left, bottom - panelInsets.bottom, right) }
        }
        btn.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
        btn.putClientProperty(STYLE, "arc: 0")
        btn.model.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                if (overflowItem != null && !btnGroup.elements.asSequence().last().isSelected)
                    removeOverflow()
                notifyChangeListeners()
            }
        }
        panel.add(btn)
        btnGroup.add(btn)
    }

    private fun removeOverflow() {
        overflowItem = null
        val overflowBtn = btnGroup.elements.asSequence().last()
        panel.remove(overflowBtn)
        btnGroup.remove(overflowBtn)
    }

    private class GroupPanel : JPanel(GroupPanelLayout()) {

        val arc = (UIManager.get("Component.arc") as Number).toFloat()
        val focusWidth = (UIManager.get("Component.focusWidth") as Number).toFloat()
        val borderWidth = (UIManager.get("Component.borderWidth") as Number).toFloat()
        val innerOutlineWidth = (UIManager.get("Component.innerOutlineWidth") as Number).toFloat()
        val normalBorderColor: Color = UIManager.getColor("Component.borderColor")
        val disabledBorderColor: Color = UIManager.getColor("Component.disabledBorderColor")
        val errorBorderColor: Color = UIManager.getColor("Component.error.borderColor")
        val errorFocusedBorderColor: Color = UIManager.getColor("Component.error.focusedBorderColor")
        val warningBorderColor: Color = UIManager.getColor("Component.warning.borderColor")
        val warningFocusedBorderColor: Color = UIManager.getColor("Component.warning.focusedBorderColor")
        val normalBackground: Color = UIManager.getColor("ComboBox.background")
        val disabledBackground: Color = UIManager.getColor("ComboBox.disabledBackground")

        init {
            border = BorderFactory.createEmptyBorder(1, 1, 1, 1)
            addPropertyChangeListener(OUTLINE) { repaint() }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.withNewG2 { g2 ->
                FlatUIUtils.setRenderingHints(g2)
                g2.color = if (isEnabled) normalBackground else disabledBackground
                FlatUIUtils.paintComponentBackground(g2, 0, 0, width, height, 0f, arc)
            }
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)

            g.withNewG2 { g2 ->
                FlatUIUtils.setRenderingHints(g2)

                // Draw the outline around the selected button manually because the buttons are borderless and hence do
                // not support outlines.
                val outline = getClientProperty(OUTLINE)
                if (outline != null)
                    for (idx in 0 until componentCount) {
                        val btn = getComponent(idx) as JToggleButton
                        if (btn.isSelected) {
                            val focused = FlatUIUtils.isPermanentFocusOwner(btn)
                            val outlineColor = when (outline) {
                                OUTLINE_ERROR -> if (focused) errorFocusedBorderColor else errorBorderColor
                                OUTLINE_WARNING -> if (focused) warningFocusedBorderColor else warningBorderColor
                                else -> throw IllegalStateException()
                            }
                            FlatUIUtils.paintOutlinedComponent(
                                g2, btn.x, btn.y, btn.width, btn.height, focusWidth, 1f,
                                borderWidth + innerOutlineWidth, 0f, 0f, outlineColor, null, null
                            )
                        }
                    }

                val borderColor = if (isEnabled) normalBorderColor else disabledBorderColor
                FlatUIUtils.paintOutlinedComponent(
                    g2, 0, 0, width, height, 0f, 0f, 0f, borderWidth, arc, null, borderColor, null
                )
            }
        }

        override fun getBaseline(width: Int, height: Int): Int {
            // As all toggle buttons have the same baseline, just ask the first one.
            return if (components.isEmpty()) -1 else components[0].let { c ->
                val insets = this.insets
                // We can use "c.preferredSize.height" here because our custom layout manager always assigns each
                // component exactly its preferred height (bounded by STD_HEIGHT from below). This alleviates some
                // re-layouts after the first painting that looks quite ugly.
                val cHeight = max(c.preferredSize.height, STD_HEIGHT - insets.top - insets.bottom)
                var baseline = c.getBaseline(c.width, cHeight)
                if (baseline == -1) {
                    // If the toggle button doesn't have a baseline because it has no label, manually compute where the
                    // baseline would be if it had one. This makes all toggle button groups along with their form labels
                    // look more consistent. It also allows to place the form label of a toggle button group list widget
                    // with unlabeled toggle buttons such that it matches the y position of the first row.
                    // The baseline turns out to be the baseline position of a vertically centered string.
                    val fm = c.getFontMetrics(c.font)
                    baseline = (cHeight + fm.ascent - fm.descent) / 2
                }
                // Adjust for the panel's border.
                insets.top + baseline
            }
        }

    }

    // A very simple layout manager that just lays out the components from left to right and enforces the panel and all
    // its components to have a height of at least STD_HEIGHT. We cannot use FlowLayout or BoxLayout because those do
    // not permit enforcing a minimum height, and setting the minimum height of the buttons individually only affects
    // the panel's but not the buttons' final height.
    private class GroupPanelLayout : LayoutManager {

        override fun addLayoutComponent(name: String, comp: Component) {}
        override fun removeLayoutComponent(comp: Component) {}
        override fun minimumLayoutSize(parent: Container) = preferredLayoutSize(parent)

        override fun preferredLayoutSize(parent: Container): Dimension {
            var width = 0
            var height = 0
            for (idx in 0 until parent.componentCount) {
                val pref = parent.getComponent(idx).preferredSize
                width += pref.width
                height = max(height, pref.height)
            }
            val insets = parent.insets
            return Dimension(width + insets.left + insets.right, max(STD_HEIGHT, height + insets.top + insets.bottom))
        }

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            var x = insets.left
            for (idx in 0 until parent.componentCount) {
                val comp = parent.getComponent(idx)
                val pref = comp.preferredSize
                comp.setBounds(x, insets.top, pref.width, max(pref.height, STD_HEIGHT - insets.top - insets.bottom))
                x += pref.width
            }
        }

    }

}


class ColorWellWidget(
    allowAlpha: Boolean = true,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<Color>() {

    private val btn = object : JButton(" ") {
        init {
            toolTipText = l10n("ui.form.colorChooserTooltip")
        }

        override fun paintComponent(g: Graphics) {
            g.withNewG2 { g2 ->
                // Draw a checkerboard pattern which will be visible if the color is transparent.
                // Clip the drawing to the rounded shape of the button (minus some slack).
                val arc = FlatUIUtils.getBorderArc(this)
                g2.clip(FlatUIUtils.createComponentRectangle(0.5f, 0.5f, width - 1f, height - 1f, arc))
                g2.drawCheckerboard(width, height, 6)
            }

            super.paintComponent(g)
        }
    }

    init {
        btn.addActionListener {
            val oldColor = value

            val chooser = JColorChooser(oldColor)
            // Disable the horrible preview panel.
            chooser.previewPanel = JPanel()
            // Disable the ability to choose transparent colors if that is desired.
            for (chooserPanel in chooser.chooserPanels)
                chooserPanel.isColorTransparencySelectionEnabled = allowAlpha
            // Whenever the user changes the color, call the change listener.
            chooser.selectionModel.addChangeListener { value = chooser.color }
            // Show the color chooser and wait for the user to close it.
            // If he cancels the dialog, revert to the old color.
            val cancelListener = { _: Any -> value = oldColor }
            JColorChooser.createDialog(btn, l10n("ui.form.colorChooserTitle"), true, chooser, null, cancelListener)
                .isVisible = true
        }
    }

    override val components = listOf<JComponent>(btn)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.NARROW).mig)

    override var value: Color = Color.BLACK
        set(value) {
            field = value
            btn.background = value
            notifyChangeListeners()
        }

    private fun Graphics.drawCheckerboard(width: Int, height: Int, n: Int) {
        val checkerSize = ceilDiv(height, n)
        for (x in 0 until width / checkerSize)
            for (y in 0 until n) {
                color = if ((x + y) % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
                fillRect(x * checkerSize, y * checkerSize, checkerSize, checkerSize)
            }
    }

}


class FontChooserWidget(
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<String>() {

    private val familyComboBox = JComboBox<FontFamily>().apply {
        maximumRowCount = 20
        keySelectionManager = CustomToStringKeySelectionManager(FontFamily::class.java) { family ->
            family.getFamily(Locale.getDefault())
        }
    }

    private val fontComboBox = JComboBox<Any>(emptyArray()).apply {
        maximumRowCount = 20
        fun toString(value: Any) = when (value) {
            // Retrieve the subfamily name from the font's family object. The fallback should never be needed.
            is Font -> getFamilyOf(value)?.getSubfamilyOf(value, Locale.getDefault()) ?: value.fontName
            else -> value as String
        }
        renderer = FontSampleListCellRenderer<Any>(::toString) { if (it is Font) it else null }
        keySelectionManager = CustomToStringKeySelectionManager(Any::class.java, ::toString)
    }

    override val components = listOf(familyComboBox, fontComboBox)
    override val constraints = ("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.WIDE).mig)
        .let { listOf(it, "newline, $it") }

    var projectFamilies: FontFamilies = FontFamilies(emptyList())
        set(value) {
            if (field == value)
                return
            field = value
            populateFamilyComboBox()
        }

    override var value: String
        get() {
            val selectedFont = fontComboBox.selectedItem
            return if (selectedFont is Font) selectedFont.getFontName(Locale.ROOT) else selectedFont as String? ?: ""
        }
        set(value) {
            val family = projectFamilies.getFamily(value)
                ?: BUNDLED_FAMILIES.getFamily(value)
                ?: SYSTEM_FAMILIES.getFamily(value)
            if (family != null) {
                familyComboBox.selectedItem = family
                fontComboBox.selectedItem = family.getFont(value)
                fontComboBox.isEnabled = true
            } else {
                familyComboBox.selectedItem = null
                fontComboBox.isEditable = true
                fontComboBox.selectedItem = value
                fontComboBox.isEditable = false
                fontComboBox.isEnabled = false
            }
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            if (isEnabled && fontComboBox.selectedItem is String)
                fontComboBox.isEnabled = false
        }

    init {
        // Equip the family combo box with a custom renderer that shows category headers.
        val baseRenderer = FontSampleListCellRenderer({ it.getFamily(Locale.getDefault()) }, FontFamily::canonicalFont)
        familyComboBox.renderer = LabeledListCellRenderer(baseRenderer, groupSpacing = 10) { index: Int ->
            mutableListOf<String>().apply {
                val projectHeaderIdx = 0
                val bundledHeaderIdx = projectHeaderIdx + projectFamilies.list.size
                val systemHeaderIdx = bundledHeaderIdx + BUNDLED_FAMILIES.list.size
                if (index == projectHeaderIdx) {
                    add("\u2605 " + l10n("ui.form.fontProjectFamilies") + " \u2605")
                    if (projectHeaderIdx == bundledHeaderIdx)
                        add(l10n("ui.form.fontNoProjectFamilies"))
                }
                if (index == bundledHeaderIdx)
                    add("\u2605 " + l10n("ui.form.fontBundledFamilies") + " \u2605")
                if (index == systemHeaderIdx)
                    add(l10n("ui.form.fontSystemFamilies"))
            }
        }

        familyComboBox.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                val selectedFamily = familyComboBox.selectedItem as FontFamily?
                fontComboBox.model = when (selectedFamily) {
                    null -> DefaultComboBoxModel()
                    else -> DefaultComboBoxModel<Any>(selectedFamily.fonts.toTypedArray()).apply {
                        selectedItem = selectedFamily.canonicalFont
                    }
                }
                notifyChangeListeners()
            }
        }
        fontComboBox.addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }

        populateFamilyComboBox()
    }

    private fun populateFamilyComboBox() {
        // We temporarily disable the change listeners because otherwise, they would be notified multiple times and,
        // even worse, with intermediate states. However, since the font widget keeps its value even if the chosen font
        // is no longer available, we do not want any notifications to trigger.
        withoutChangeListeners {
            val selected = value
            val families = projectFamilies.list + BUNDLED_FAMILIES.list + SYSTEM_FAMILIES.list
            // It is important that the family combo box initially has no selection, so that the value setter we call
            // one line later always triggers the family combo box's selection listener (even if when the value setter
            // sets the first family as selected!) and hence correctly populates the font combo box.
            familyComboBox.model = DefaultComboBoxModel(families.toTypedArray()).apply { selectedItem = null }
            value = selected
        }
    }

    private fun getFamilyOf(font: Font) =
        projectFamilies.getFamily(font) ?: BUNDLED_FAMILIES.getFamily(font) ?: SYSTEM_FAMILIES.getFamily(font)


    private inner class FontSampleListCellRenderer<E>(
        private val toString: (E) -> String,
        private val toFont: (E) -> Font?
    ) : ListCellRenderer<E> {

        private val label1 = JLabel()
        private val label2 = JLabel()
        // Note: filly ensures that label1 is vertically centered also in an enlarged combo box.
        private val panel = JPanel(MigLayout("insets 0, filly", "[]40:::push[]")).apply {
            add(label1)
            add(label2, "width 100!, height ::22")
        }

        override fun getListCellRendererComponent(
            list: JList<out E>, value: E?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            // An upstream cell renderer might adjust the border. As such, we must reset it each time.
            panel.border = null

            val bg = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground
            label1.background = bg
            label1.foreground = fg
            label1.font = list.font
            label2.background = bg
            label2.foreground = fg
            panel.background = bg
            panel.foreground = fg

            label1.text = value?.let(toString) ?: ""

            // Show the sample text only in the popup menu, but not in the combo box. Make the label invisible
            // in the combo box to ensure that fonts with large ascent don't stretch out the combo box.
            label1.border = null
            label2.isVisible = false
            label2.font = list.font
            if (index != -1) {
                label1.border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
                value?.let(toFont)?.let { sampleFont ->
                    val effSampleFont = sampleFont.deriveFont(list.font.size2D * 1.25f)
                    // Try to get the sample text from the font, and if none is specified, use a standard one.
                    val effSampleText = getFamilyOf(sampleFont)?.getSampleTextOf(sampleFont, Locale.getDefault())
                        ?: l10n("ui.form.fontSample")
                    // Only display the sample when there is at least one glyph is not the missing glyph placeholder.
                    val gv = effSampleFont.createGlyphVector(REF_FRC, effSampleText)
                    val missGlyph = effSampleFont.missingGlyphCode
                    if (gv.getGlyphCodes(0, gv.numGlyphs, null).any { it != missGlyph }) {
                        label2.isVisible = true
                        label2.font = effSampleFont
                        label2.text = effSampleText
                    }
                }
            }

            return panel
        }

    }

}


class FontFeatureWidget : Form.AbstractWidget<FontFeature>() {

    private val tagWidget = InconsistentComboBoxWidget(String::class.java, emptyList(), widthSpec = WidthSpec.TINY)
    private val valWidget = SpinnerWidget(
        Int::class.javaObjectType, SpinnerNumberModel(1, 0, null, 1), WidthSpec.TINIER
    )

    init {
        // When a wrapped widget changes, notify this widget's change listeners that that widget has changed.
        tagWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
        valWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components: List<JComponent> = tagWidget.components + listOf(JLabel("=")) + valWidget.components
    override val constraints = tagWidget.constraints + listOf("") + valWidget.constraints

    override var value: FontFeature
        get() = FontFeature(tagWidget.value, valWidget.value)
        set(value) {
            tagWidget.value = value.tag
            valWidget.value = value.value
        }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        tagWidget.applyConfigurator(configurator)
        valWidget.applyConfigurator(configurator)
    }

}


class OptWidget<E : Any>(
    val wrapped: Form.Widget<E>
) : Form.AbstractWidget<Opt<E>>() {

    init {
        // When the wrapped widget changes, notify this widget's change listeners that that widget has changed.
        wrapped.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    private val cb = LargeCheckBox(STD_HEIGHT).apply {
        addItemListener {
            wrapped.isEnabled = isSelected
            notifyChangeListeners()
        }
    }

    override val components = listOf(cb) + wrapped.components
    override val constraints = listOf("") + wrapped.constraints

    override var value: Opt<E>
        get() = Opt(cb.isSelected, wrapped.value)
        set(value) {
            cb.isSelected = value.isActive
            wrapped.value = value.value
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            if (isEnabled && !cb.isSelected)
                wrapped.isEnabled = false
        }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        wrapped.applyConfigurator(configurator)
    }

}


class ListWidget<E : Any>(
    private val newElemWidget: () -> Form.Widget<E>,
    private val newElem: E? = null,
    private val newElemIsLastElem: Boolean = false,
    private val elemsPerRow: Int = 1,
    private val rowSeparators: Boolean = false,
    private val movButtons: Boolean = false,
    private val minSize: Int = 0
) : Form.AbstractWidget<List<E>>() {

    private val addBtn = JButton(ADD_ICON)

    private val panel = object : JPanel(MigLayout("hidemode 3, insets 0")) {
        override fun getBaseline(width: Int, height: Int): Int {
            // Since the vertical insets of this panel are 0, we can directly forward the baseline query to a component
            // in the first row. We choose the first one which has a valid baseline.
            for (idx in 0 until elemsPerRow.coerceAtMost(listSize))
                for (comp in allElemWidgets[idx].components) {
                    // If the component layout hasn't been done yet, approximate the component's height using its
                    // preferred height (bounded by STD_HEIGHT from below). This alleviates "jumping" when adding
                    // certain components like JComboBoxes.
                    val cHeight = if (comp.height != 0) comp.height else max(comp.preferredSize.height, STD_HEIGHT)
                    val baseline = comp.getBaseline(comp.width, cHeight)
                    if (baseline >= 0)
                        return baseline
                }
            return -1
        }
    }

    private var listSize = 0
    private val allElemWidgets = mutableListOf<Form.Widget<E>>()
    private val allElemCtlBtns = mutableListOf<ControlButtons>()
    private val allSeparators = mutableListOf<JSeparator>()

    val elementWidgets: List<Form.Widget<E>>
        get() = allElemWidgets.subList(0, listSize)

    init {
        // Create and add some widgets and buttons during initialization so that user interaction will appear quicker.
        repeat(4) { addElemWidgetAndCtlBtns(isVisible = false) }

        addBtn.addActionListener {
            userPlus(setValue = true)
            notifyChangeListeners()
        }
    }

    override val components = listOf<JComponent>(addBtn, panel)
    override val constraints = listOf(
        "aligny top, gapy 1 1",
        if (allElemWidgets[0].constraints.any { WidthSpec.FILL.mig in it }) WidthSpec.FILL.mig else ""
    )

    override var value: List<E>
        get() = elementWidgets.map { it.value }
        set(value) {
            while (listSize < value.size)
                userPlus()
            while (listSize > value.size)
                userMinus(listSize - 1)
            for ((widget, item) in elementWidgets.zip(value))
                widget.value = item
            notifyChangeListeners()
        }

    private fun userPlus(setValue: Boolean = false) {
        listSize++
        if (listSize <= allElemWidgets.size) {
            // If there are still unused components at the back of the list, just reactivate them.
            allElemWidgets[listSize - 1].isVisible = true
            allElemCtlBtns[listSize - 1].setVisible(true)
            if (rowSeparators && listSize != 1 && (listSize - 1) % elemsPerRow == 0)
                allSeparators[(listSize - 1) / elemsPerRow - 1].isVisible = true
        } else {
            // Otherwise, really create and add totally new components.
            addElemWidgetAndCtlBtns(isVisible = true)
        }
        // If requested, the new widget should start out with a reasonable value.
        if (setValue)
            withoutChangeListeners {
                if (newElemIsLastElem && listSize > 1)
                    allElemWidgets[listSize - 1].value = allElemWidgets[listSize - 2].value
                else if (newElem != null)
                    allElemWidgets[listSize - 1].value = newElem
                else
                    throw IllegalStateException("No way to choose value of new ListWidget element.")
            }
        enableOrDisableCtlBtns()
    }

    private fun userMinus(idx: Int) {
        withoutChangeListeners {
            for (i in idx + 1 until listSize)
                allElemWidgets[i - 1].value = allElemWidgets[i].value
        }
        allElemWidgets[listSize - 1].isVisible = false
        allElemCtlBtns[listSize - 1].setVisible(false)
        if (rowSeparators && listSize != 1 && (listSize - 1) % elemsPerRow == 0)
            allSeparators[(listSize - 1) / elemsPerRow - 1].isVisible = false
        listSize--
        enableOrDisableCtlBtns()
    }

    private fun userSwap(idx1: Int, idx2: Int) {
        withoutChangeListeners {
            val value1 = allElemWidgets[idx1].value
            allElemWidgets[idx1].value = allElemWidgets[idx2].value
            allElemWidgets[idx2].value = value1
        }
        enableOrDisableCtlBtns()
    }

    private fun addElemWidgetAndCtlBtns(isVisible: Boolean) {
        val newline = allElemWidgets.size != 0 && allElemWidgets.size % elemsPerRow == 0

        if (rowSeparators && newline) {
            val sep = JSeparator()
            sep.isVisible = isVisible
            allSeparators.add(sep)
            panel.add(sep, "newline, span, growx")
        }

        val delBtn = JButton(REMOVE_ICON)
        delBtn.isVisible = isVisible
        delBtn.addActionListener {
            userMinus(allElemCtlBtns.indexOfFirst { it.delBtn == delBtn })
            notifyChangeListeners()
        }
        var delBtnConstraint = "aligny top, gapleft 6, gaptop 1"
        if (movButtons) delBtnConstraint += ", split 3, flowy"
        if (newline) delBtnConstraint += ", newline"
        panel.add(delBtn, delBtnConstraint)

        var uppBtn: JButton? = null
        var dwnBtn: JButton? = null
        if (movButtons) {
            uppBtn = JButton(ARROW_UP_ICON)
            dwnBtn = JButton(ARROW_DOWN_ICON)
            uppBtn.isVisible = isVisible
            dwnBtn.isVisible = isVisible
            uppBtn.addActionListener {
                val idx = allElemCtlBtns.indexOfFirst { it.uppBtn == uppBtn }
                userSwap(idx, idx - 1)
                notifyChangeListeners()
            }
            dwnBtn.addActionListener {
                val idx = allElemCtlBtns.indexOfFirst { it.dwnBtn == dwnBtn }
                userSwap(idx, idx + 1)
                notifyChangeListeners()
            }
            panel.add(uppBtn, "gapleft 6")
            panel.add(dwnBtn, "gapleft 6")
        }

        allElemCtlBtns.add(ControlButtons(delBtn, uppBtn, dwnBtn))

        val widget = newElemWidget()
        widget.isVisible = isVisible
        // When the new widget changes, notify this widget's change listeners that that widget has changed.
        widget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
        allElemWidgets.add(widget)
        for ((comp, constr) in widget.components.zip(widget.constraints))
            panel.add(comp, constr)
    }

    private fun enableOrDisableCtlBtns() {
        val delEnabled = listSize > minSize
        for (idx in 0 until listSize) {
            val ctlBtns = allElemCtlBtns[idx]
            ctlBtns.delBtn.isEnabled = delEnabled
            ctlBtns.uppBtn?.isEnabled = idx != 0
            ctlBtns.dwnBtn?.isEnabled = idx != listSize - 1
        }
    }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        for (idx in 0 until listSize)
            allElemWidgets[idx].applyConfigurator(configurator)
    }

    override fun applySeverity(index: Int, severity: Severity?) {
        if (index == -1) {
            super.applySeverity(-1, severity)
            for (widget in allElemWidgets)
                widget.applySeverity(-1, severity)
        } else if (index in 0 until listSize)
            allElemWidgets[index].applySeverity(-1, severity)
    }


    private class ControlButtons(val delBtn: JButton, val uppBtn: JButton?, val dwnBtn: JButton?) {
        fun setVisible(isVisible: Boolean) {
            delBtn.isVisible = isVisible
            uppBtn?.isVisible = isVisible
            dwnBtn?.isVisible = isVisible
        }
    }

}


class UnionWidget(
    private val wrapped: List<Form.Widget<*>>,
    icons: List<Icon>? = null
) : Form.AbstractWidget<List<Any>>() {

    init {
        if (icons != null)
            require(wrapped.size == icons.size)

        // When a wrapped widget changes, notify this widget's change listeners that that widget has changed.
        for (widget in wrapped)
            widget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components: List<JComponent> = buildList {
        for ((idx, widget) in wrapped.withIndex()) {
            if (icons != null) add(JLabel(icons[idx]))
            addAll(widget.components)
        }
    }

    override val constraints = mutableListOf<String>().apply {
        if (icons != null)
            add("gapx 0 3")
        addAll(wrapped.first().constraints)
        for (widget in wrapped.drop(1)) {
            if (icons != null)
                add("gapx 10 3")
            addAll(widget.constraints)
        }
    }

    override var value: List<Any>
        get() = wrapped.map { it.value }
        set(value) {
            require(wrapped.size == value.size)
            for (idx in wrapped.indices)
                @Suppress("UNCHECKED_CAST")
                (wrapped[idx] as Form.Widget<Any>).value = value[idx]
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            for (widget in wrapped)
                widget.isEnabled = isEnabled
        }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        for (widget in wrapped)
            widget.applyConfigurator(configurator)
    }

}


class NestedFormWidget<V : Any>(
    val form: Form.Storable<V>
) : Form.AbstractWidget<V>() {

    init {
        // When the wrapped form changes, notify this widget's change listeners that a widget in that form has changed.
        form.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components = listOf(form)
    override val constraints = listOf(WidthSpec.FILL.mig)

    override var value: V
        get() = form.save()
        set(value) {
            form.open(value)
        }

}


private fun String.removeAnySuffix(suffixes: List<String>, ignoreCase: Boolean = false): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
}


/**
 * When editing a [JFormattedTextField] field in a StyleForm and leaving it with a valid value, then clicking in the
 * style tree to open another style, that style will first be opened, and only after that's done the change to the
 * [JFormattedTextField] will be committed. Hence, the change will be applied to the wrong style or, even worse, the
 * application might crash if the user opened another style type. Fixing this directly seems difficult, and there's a
 * better solution: we let the text field commit whenever its text is a valid value. Thereby, the situation described
 * above can't ever occur. It is of course vital that the formatter of each [JFormattedTextField] is made safe this way.
 */
private fun DefaultFormatter.makeSafe() {
    commitsOnValidEdit = true
}
