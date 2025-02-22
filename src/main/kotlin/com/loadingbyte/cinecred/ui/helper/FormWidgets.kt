package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.ui.FlatBorder
import com.formdev.flatlaf.ui.FlatButtonUI
import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.project.FontFeature
import com.loadingbyte.cinecred.project.FontRef
import com.loadingbyte.cinecred.project.Opt
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import java.awt.event.*
import java.nio.file.Path
import java.text.NumberFormat
import java.text.ParseException
import java.util.*
import javax.swing.*
import javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.Timer
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.text.*
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.max


enum class WidthSpec(val mig: String) {
    NONE(""),
    TINIER("width 50:50:"),
    TINY("width 60:60:"),
    LITTLE("width 70:70:"),
    NARROW("width 100:100:"),
    SQUEEZE("width 100::200"),
    FIT("width 100::300"),
    WIDE("width 100:300:"),
    WIDER("width 100:500:"),
    FILL("width 100:100%:")
}


private const val STD_HEIGHT = 24


abstract class AbstractTextComponentWidget<V : Any>(
    protected val tc: JTextComponent,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    init {
        if (tc is JTextArea) {
            // Use tab key for focus traversal instead of inserting a tab character.
            tc.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
            tc.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
        }
    }

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
            installDocument(text)
            notifyChangeListeners()
        }

    private fun installDocument(string: String) {
        tc.document = PlainDocument().apply {
            insertString(0, string, null)
            addDocumentListener { notifyChangeListeners() }
            configureDocument(this)
        }
    }

    protected open fun configureDocument(document: Document) {}

    init {
        // Make sure that the document listener is installed.
        installDocument("")
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


class TextModulesWidget(
    modules: List<String>,
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<String>(JTextArea(), widthSpec) {

    private val insertModuleBtn = JButton(ADD_ICON)
    private val popup = DropdownPopupMenu(insertModuleBtn)

    override val components = listOf<JComponent>(JScrollPane(tc), insertModuleBtn)
    override val constraints = listOf(super.constraints.single() + ", hmax ${10 * STD_HEIGHT}", "growy")

    init {
        tc as JTextArea
        tc.lineWrap = true
        tc.wrapStyleWord = true

        popup.addMouseListenerTo(insertModuleBtn)
        popup.addKeyListenerTo(insertModuleBtn)
        for (module in modules) {
            val menuItem = DropdownPopupMenuItem(module)
            menuItem.addActionListener {
                tc.document.insertString(tc.caretPosition, module, null)
                tc.requestFocusInWindow()
            }
            popup.add(menuItem)
        }
        popup.pack()
    }

    private var prevPrefHeight = -1

    override fun configureDocument(document: Document) {
        document.putProperty("filterNewlines", true)
        // When the text area changes preferred height because lines are added/removed, the wrapping scroll pane does
        // not follow until either the user moves the mouse or we call revalidate() on the Form container.
        // Note that the text area doesn't reliably change its actual height upon edits, so we cannot rely on a
        // ComponentListener and really need to manually pull the preferred height like this.
        document.addDocumentListener {
            val prefHeight = tc.preferredSize.height
            if (prevPrefHeight != prefHeight) {
                prevPrefHeight = prefHeight
                components[0].parent.revalidate()
            }
        }
    }

    override var value: String
        get() = text
        set(value) {
            text = value
        }

}


class FileExtAssortment(val name: String, val choices: List<String>, val default: String? = null) {
    init {
        require(default == null || default in choices)
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
                if (ass?.default != null && ass.choices.none { text.endsWith(".$it", ignoreCase = true) })
                    text += ".${ass.default}"
            }
        })
    }

    var fileExtAssortment: FileExtAssortment? = null
        set(ass) {
            if (field == ass)
                return
            // When the list of admissible file extensions is changed and the field text doesn't end with the new
            // default file extension, remove the previous file extension (if there was any) and add the new default.
            if (ass?.default != null && !text.endsWith(".${ass.default}", ignoreCase = true)) {
                field?.let { old -> text = text.removeAnySuffix(old.choices.map { ".$it" }, ignoreCase = true) }
                text += ".${ass.default}"
            }
            field = ass
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
enum class FileAction { OPEN, SAVE }

class FileWidget(
    fileType: FileType,
    fileAction: FileAction,
    widthSpec: WidthSpec? = null
) : AbstractFilenameWidget<Path>() {

    private val browse = JButton(l10n("ui.form.browse"), FOLDER_ICON)

    init {
        browse.addActionListener {
            val win = SwingUtilities.getWindowAncestor(browse)
            var initial = text.toPathSafely()?.normalize()
            val selected = when (fileType) {
                FileType.FILE -> {
                    if (fileAction == FileAction.OPEN)
                        initial = initial?.parent
                    val ass = checkNotNull(fileExtAssortment) { "File ext assortment is required." }
                    showFileDialog(win, fileAction == FileAction.OPEN, ass.name, ass.choices, initial)
                }
                FileType.DIRECTORY ->
                    showFolderDialog(win, initial)
            }
            if (selected != null) {
                var newText = selected.absolutePathString()
                // When saving, ensure that the path ends with an admissible file extension.
                if (fileAction == FileAction.SAVE) {
                    val ass = fileExtAssortment
                    if (ass?.default != null && ass.choices.none { newText.endsWith(".$it", ignoreCase = true) })
                        newText += ".${ass.default}"
                }
                text = newText
            }
        }
    }

    override val components = listOf<JComponent>(tc, browse)
    override val constraints = listOf((widthSpec ?: WidthSpec.WIDE).mig, "")

    override var value: Path
        get() = text.trim().toPathSafely() ?: Path("")
        set(value) {
            text = value.pathString
        }

}


open class SpinnerWidget<V : Number>(
    private val valueClass: Class<V>,
    model: SpinnerNumberModel,
    decimalFormatPattern: String? = null,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    protected val spinner = JSpinner(model)

    init {
        require(valueClass == Int::class.javaObjectType || valueClass == Double::class.javaObjectType)

        spinner.addChangeListener {
            val newValue = valueClass.cast(spinner.value)
            // Only update the widget's value if the new value diverges sufficiently from the old one. We need this
            // check because the spinner uses its formatter's stringToValue() function once BEFORE the value is actually
            // increased or decreased, and due to floating point inaccuracy, that can change the value ever so slightly.
            if (valueClass != Double::class.javaObjectType || abs(newValue.toDouble() - value.toDouble()) > 0.00001) {
                value = newValue
                notifyChangeListeners()
            }
        }

        spinner.editor = when (decimalFormatPattern) {
            null -> JSpinner.NumberEditor(spinner)
            else -> JSpinner.NumberEditor(spinner, decimalFormatPattern)
        }.apply { (textField.formatter as DefaultFormatter).makeSafe() }
    }

    override val components = listOf(spinner)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.NARROW).mig)

    override var value: V = valueClass.cast(spinner.value)
        set(value) {
            field = value
            spinner.value = value
        }

}


class MultipliedSpinnerWidget(
    model: SpinnerNumberModel,
    decimalFormatPattern: String? = null,
    widthSpec: WidthSpec? = null
) : SpinnerWidget<Double>(Double::class.javaObjectType, model, decimalFormatPattern, widthSpec) {

    private val step = model.stepSize as Double

    var multiplier: Double = 1.0
        set(multiplier) {
            if (field == multiplier)
                return
            field = multiplier
            // Note: We have verified that changing the formatter only causes valueToString(), but not stringToValue()
            // calls. Hence, the value in the spinner model remains unmodified each time the multiplier is changed.
            // As such, no floating point drift occurs.
            (spinner.editor as JSpinner.DefaultEditor).textField.formatterFactory =
                DefaultFormatterFactory(MultipliedFormatter(spinner.model as SpinnerNumberModel, multiplier))
            // Also adapt the step size to the multiplier. Notice that this triggers ChangeEvents, which we discard.
            withoutChangeListeners { (spinner.model as SpinnerNumberModel).stepSize = step / multiplier }
        }


    private class MultipliedFormatter(
        private val model: SpinnerNumberModel,
        private val multiplier: Double
    ) : NumberFormatter() {
        init {
            valueClass = model.value.javaClass
            makeSafe()
        }

        // Necessary to have limits on the text input. If the multiplier is negative, the bounds need to be flipped.
        // Otherwise, the user would only be allowed to input out-of-bounds numbers.
        override fun getMinimum(): Comparable<*>? = if (multiplier < 0) model.maximum else model.minimum
        override fun getMaximum(): Comparable<*>? = if (multiplier < 0) model.minimum else model.maximum
        override fun setMinimum(minimum: Comparable<*>?) = throw UnsupportedOperationException()
        override fun setMaximum(maximum: Comparable<*>?) = throw UnsupportedOperationException()

        override fun valueToString(value: Any?): String =
            super.valueToString(value as Double * multiplier)

        override fun stringToValue(string: String?): Double =
            (super.stringToValue(string) as Number).toDouble() / multiplier
    }

}


class TimecodeWidget(
    model: SpinnerNumberModel,
    fps: FPS,
    timecodeFormat: TimecodeFormat,
    widthSpec: WidthSpec? = null
) : SpinnerWidget<Int>(Int::class.javaObjectType, model, widthSpec = widthSpec ?: WidthSpec.FIT) {

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

    private val signed = model.minimum.let { it == null || (it as Int) < 0 }

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
        editor.textField.formatterFactory = DefaultFormatterFactory(TimecodeFormatter(fps, timecodeFormat, signed))
    }


    private class TimecodeFormatter(
        private val fps: FPS,
        private val timecodeFormat: TimecodeFormat,
        private val signed: Boolean
    ) : DefaultFormatter() {
        init {
            valueClass = Int::class.javaObjectType
            makeSafe()
        }

        // We need to catch exceptions here because formatTimecode() throws some when using non-fractional FPS together
        // with a drop-frame timecode.
        override fun valueToString(value: Any?): String = try {
            val tc = formatTimecode(fps, timecodeFormat, abs(value as Int))
            if (value < 0) "-$tc" else if (signed) "+$tc" else tc
        } catch (_: IllegalArgumentException) {
            ""
        }

        override fun stringToValue(string: String?): Int = try {
            val c0 = string!![0]
            val n = parseTimecode(fps, timecodeFormat, if (c0 == '+' || c0 == '-') string.substring(1) else string)
            if (signed && c0 == '-') -n else n
        } catch (_: Exception) {
            throw ParseException("", 0)
        }
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


abstract class AbstractComboBoxWidget<V : Any, E : Any>(
    private val itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String,
    widthSpec: WidthSpec?,
    private val scrollbar: Boolean = true,
    rendererDecorator: RendererDecorator? = null
) : Form.AbstractWidget<V>(), Form.Choice<E> {

    protected val cb = JComboBox<Wrapper>().apply {
        addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
        if (rendererDecorator != null)
            renderer = rendererDecorator.decorate(renderer)
    }

    override val components = listOf<JComponent>(cb)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.FIT).mig)

    final override var items: List<E> = listOf()
        set(items) {
            if (field == items)
                return
            field = items
            val oldSelected = unwrap(cb.selectedItem)
            val keepSelected = shouldKeepSelected(oldSelected)
            cb.model = DefaultComboBoxModel(items.mapTo(Vector(), ::Wrapper)).apply {
                if (keepSelected)
                    selectedItem = oldSelected?.let(::Wrapper)
            }
            if (!keepSelected)
                notifyChangeListeners()
            if (!scrollbar)
                cb.maximumRowCount = items.size
        }

    var toString: (E) -> String = toString
        set(toString) {
            if (field == toString)
                return
            field = toString
            cb.invalidate()
        }

    protected fun unwrap(wrapper: Any?): E? =
        (wrapper as AbstractComboBoxWidget<*, *>.Wrapper?)?.item?.let(itemClass::cast)

    protected open fun shouldKeepSelected(selected: E?): Boolean =
        selected in items

    init {
        this.items = items
    }


    interface RendererDecorator {
        fun <I> decorate(renderer: ListCellRenderer<I>): ListCellRenderer<I>
    }


    protected inner class Wrapper(override val item: E) : ComboBoxWrapper {
        override fun toString() = toString(item).ifEmpty { " " }
        override fun hashCode() = item.hashCode()
        override fun equals(other: Any?) =
            this === other || other is AbstractComboBoxWidget<*, *>.Wrapper && item == other.item
    }

}


open class ComboBoxWidget<V : Any>(
    valueClass: Class<V>,
    items: List<V>,
    toString: (V) -> String = { it.toString() },
    widthSpec: WidthSpec? = null,
    scrollbar: Boolean = true,
    rendererDecorator: RendererDecorator? = null
) : AbstractComboBoxWidget<V, V>(valueClass, items, toString, widthSpec, scrollbar, rendererDecorator) {

    override var value: V
        get() = unwrap(cb.selectedItem)!!
        set(value) {
            cb.selectedItem = Wrapper(value)
        }

}


class InconsistentComboBoxWidget<V : Any>(
    valueClass: Class<V>,
    items: List<V>,
    toString: (V) -> String = { it.toString() },
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<V>(valueClass, items, toString, widthSpec) {

    // Also sets selectedItem to null if it was null before.
    override fun shouldKeepSelected(selected: V?) = true

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
    toString: (V) -> String,
    private val fromString: ((String) -> V),
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<V>(valueClass, items, toString, widthSpec) {

    init {
        cb.isEditable = true
        cb.editor = CustomComboBoxEditor()
    }

    override fun shouldKeepSelected(selected: V?) =
        selected != null


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
            unwrap(value)?.let(toString) ?: ""

        override fun stringToValue(string: String?): Any =
            try {
                Wrapper(fromString(string!!))
            } catch (_: Exception) {
                throw ParseException("", 0)
            }
    }

}


class OptionalComboBoxWidget<E : Any>(
    itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String = { it.toString() },
    widthSpec: WidthSpec? = null
) : AbstractComboBoxWidget<Optional<E>, E>(itemClass, items, toString, widthSpec) {

    override var value: Optional<E>
        get() = Optional.ofNullable(unwrap(cb.selectedItem))
        set(value) {
            cb.selectedItem = value.getOrNull()?.let(::Wrapper)
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
            return if (frac != null) NumberFormat.getNumberInstance().format(frac) else fps.toString()
        }

        private fun fromDisplayString(str: String): FPS {
            try {
                val frac = NumberFormat.getNumberInstance().parse(str)
                SUGGESTED_FRAC_FPS.find { it.first == frac }?.let { return it.second }
            } catch (_: ParseException) {
                // Try the next parser.
            }
            return FPS.fromString(str)
        }

    }

}


class MultiComboBoxWidget<E : Any>(
    items: List<E>,
    private val comparator: Comparator<E>,
    toString: (E) -> String = { it.toString() },
    widthSpec: WidthSpec? = null,
    inconsistent: Boolean = false,
    placeholder: String? = null,
    noItemsMessage: String? = null
) : Form.AbstractWidget<List<E>>(), Form.Choice<E> {

    private val mcb = MultiComboBox(toString, inconsistent, placeholder, noItemsMessage).apply {
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

    var toString: (E) -> String
        get() = mcb.toString
        set(toString) {
            mcb.toString = toString
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

    override fun setSeverity(index: Int, severity: Severity?) {
        super.setSeverity(index, severity)
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
            panel.revalidate()
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

    override fun applyEnabled(isEnabled: Boolean) {
        super.applyEnabled(isEnabled)
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
        panel.revalidate()
    }

    private fun removeOverflow() {
        overflowItem = null
        val overflowBtn = btnGroup.elements.asSequence().last()
        panel.remove(overflowBtn)
        btnGroup.remove(overflowBtn)
        panel.revalidate()
    }

    private class GroupPanel : JPanel(GroupPanelLayout()) {

        private val arc = (UIManager.get("Component.arc") as Number).toFloat()
        private val focusWidth = (UIManager.get("Component.focusWidth") as Number).toFloat()
        private val borderWidth = (UIManager.get("Component.borderWidth") as Number).toFloat()
        private val innerOutlineWidth = (UIManager.get("Component.innerOutlineWidth") as Number).toFloat()
        private val normalBorderColor = UIManager.getColor("Component.borderColor")
        private val disabledBorderColor = UIManager.getColor("Component.disabledBorderColor")
        private val errorBorderColor = UIManager.getColor("Component.error.borderColor")
        private val errorFocusedBorderColor = UIManager.getColor("Component.error.focusedBorderColor")
        private val warningBorderColor = UIManager.getColor("Component.warning.borderColor")
        private val warningFocusedBorderColor = UIManager.getColor("Component.warning.focusedBorderColor")
        private val normalBackground = UIManager.getColor("ComboBox.background")
        private val disabledBackground = UIManager.getColor("ComboBox.disabledBackground")

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
                    for (idx in 0..<componentCount) {
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
                    baseline = ceilDiv(cHeight + fm.ascent - fm.descent, 2)
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
            for (idx in 0..<parent.componentCount) {
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
            for (idx in 0..<parent.componentCount) {
                val comp = parent.getComponent(idx)
                val pref = comp.preferredSize
                comp.setBounds(x, insets.top, pref.width, max(pref.height, STD_HEIGHT - insets.top - insets.bottom))
                x += pref.width
            }
        }

    }

}


class ColorWellWidget(
    allowNonSRGB: Boolean = true,
    allowAlpha: Boolean = true,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<Color4f>() {

    private val btn = object : JButton(" ") {
        init {
            toolTipText = l10n("ui.form.colorTooltip")
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

    private val picker = ColorPicker(allowNonSRGB, allowAlpha)
    private val popup = DropdownPopupMenu(
        btn,
        preShow = { picker.resetUI(); picker.swatchColors = swatchColors },
        // Note: Without invokeLater(), the focus is not transferred.
        postShow = { SwingUtilities.invokeLater { picker.requestFocusInWindow() } }
    )

    init {
        picker.addChangeListener { value = picker.value }
        popup.add(picker)
        popup.addMouseListenerTo(btn)
        popup.addKeyListenerTo(btn)
    }

    override val components = listOf<JComponent>(btn)
    override val constraints = listOf("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.NARROW).mig)

    var swatchColors: List<Color4f> = emptyList()

    override var value: Color4f = Color4f.BLACK
        set(value) {
            field = value
            btn.background = value.toSRGBAWT()
            withoutChangeListeners { picker.value = value }
            notifyChangeListeners()
        }

    private fun Graphics.drawCheckerboard(width: Int, height: Int, n: Int) {
        val checkerSize = ceilDiv(height, n)
        for (x in 0..<width / checkerSize)
            for (y in 0..<n) {
                color = if ((x + y) % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
                fillRect(x * checkerSize, y * checkerSize, checkerSize, checkerSize)
            }
    }

}


class ResolutionWidget : Form.AbstractWidget<Resolution>() {

    private sealed interface Preset {
        class Choice(val label: String, val resolution: Resolution) : Preset
        object Custom : Preset
    }

    companion object {
        private val CHOICE_PRESETS = listOf(
            Preset.Choice("HD", Resolution(1280, 720)),
            Preset.Choice("Full HD", Resolution(1920, 1080)),
            Preset.Choice("Ultra HD", Resolution(3840, 2160)),
            Preset.Choice("DCI 2K Flat", Resolution(1998, 1080)),
            Preset.Choice("DCI 2K Scope", Resolution(2048, 858)),
            Preset.Choice("DCI 4K Flat", Resolution(3996, 2160)),
            Preset.Choice("DCI 4K Scope", Resolution(4096, 1716))
        )
    }

    private val presetWidget = ComboBoxWidget(
        Preset::class.java, listOf(Preset.Custom) + CHOICE_PRESETS,
        toString = { p ->
            when (p) {
                is Preset.Choice -> "${p.label} (${p.resolution.widthPx} \u00D7 ${p.resolution.heightPx})"
                is Preset.Custom -> l10n("custom")
            }
        }
    ).apply { value = CHOICE_PRESETS[1] /* start out with Full HD */ }

    private val widthWidget = makeDimensionSpinner()
    private val heightWidget = makeDimensionSpinner()
    private val timesLabel = JLabel("\u00D7")

    private var initializedCustom = false
    private var prevPreset: Preset = presetWidget.value

    private fun makeDimensionSpinner() =
        SpinnerWidget(Int::class.javaObjectType, SpinnerNumberModel(1, 1, null, 10), "#", WidthSpec.LITTLE)

    init {
        // When a wrapped widget changes, notify this widget's change listeners that that widget has changed.
        // If necessary, also update the custom resolution visibility.
        presetWidget.changeListeners.add { widget ->
            val isCustom = presetWidget.value == Preset.Custom
            widthWidget.isVisible = isCustom
            heightWidget.isVisible = isCustom
            timesLabel.isVisible = isCustom
            // When the custom option is selected for the first time, initialize it with the previously selected preset.
            if (!initializedCustom)
                if (isCustom) {
                    initializedCustom = true
                    val initRes = (prevPreset as Preset.Choice).resolution
                    withoutChangeListeners {
                        widthWidget.value = initRes.widthPx
                        heightWidget.value = initRes.heightPx
                    }
                } else
                    prevPreset = presetWidget.value
            notifyChangeListenersAboutOtherWidgetChange(widget)
        }
        widthWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
        heightWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components: List<JComponent> = presetWidget.components +
            widthWidget.components + timesLabel + heightWidget.components
    override val constraints =
        presetWidget.constraints + widthWidget.constraints + listOf("") + heightWidget.constraints

    override var value: Resolution
        get() = when (val preset = presetWidget.value) {
            is Preset.Custom -> Resolution(widthWidget.value, heightWidget.value)
            is Preset.Choice -> preset.resolution
        }
        set(value) {
            val preset = CHOICE_PRESETS.find { it.resolution == value } ?: Preset.Custom
            presetWidget.value = preset
            if (preset == Preset.Custom) {
                widthWidget.value = value.widthPx
                heightWidget.value = value.heightPx
            }
        }

    override fun applyVisible(isVisible: Boolean) {
        presetWidget.isVisible = isVisible
        val isCustomVisible = isVisible && presetWidget.value == Preset.Custom
        widthWidget.isVisible = isCustomVisible
        heightWidget.isVisible = isCustomVisible
        timesLabel.isVisible = isCustomVisible
    }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        presetWidget.applyConfigurator(configurator)
        widthWidget.applyConfigurator(configurator)
        heightWidget.applyConfigurator(configurator)
    }

}


class FontChooserWidget(
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<FontRef>() {

    private val familyComboBox = JComboBox<FamilyWrapper>().apply {
        maximumRowCount = 20
    }

    private val fontComboBox = JComboBox<FontWrapper>(Vector()).apply {
        maximumRowCount = 20
        renderer = FontSampleListCellRenderer()
    }

    override val components = listOf<JComponent>(familyComboBox, fontComboBox)
    override val constraints = ("hmin $STD_HEIGHT, " + (widthSpec ?: WidthSpec.WIDE).mig)
        .let { listOf(it, "newline, $it") }

    var projectFamilies: FontFamilies = FontFamilies(emptyList())
        set(value) {
            if (field == value)
                return
            field = value
            populateFamilyComboBox()
        }

    override var value: FontRef
        get() = when (val selectedFontWrapper = fontComboBox.selectedItem as FontWrapper?) {
            is FontWrapper.ForFont -> FontRef(selectedFontWrapper.font)
            is FontWrapper.ForName -> FontRef(selectedFontWrapper.name)
            null -> FontRef("")
        }
        set(value) {
            val family = value.font?.let { font ->
                projectFamilies.getFamily(font) ?: BUNDLED_FAMILIES.getFamily(font) ?: SYSTEM_FAMILIES.getFamily(font)
            } ?: value.name.let { name ->
                projectFamilies.getFamily(name) ?: BUNDLED_FAMILIES.getFamily(name) ?: SYSTEM_FAMILIES.getFamily(name)
            }
            if (family != null) {
                familyComboBox.selectedItem = FamilyWrapper(family)
                fontComboBox.selectedItem = FontWrapper.ForFont(value.font ?: family.getFont(value.name)!!, family)
                fontComboBox.isEnabled = true
            } else {
                familyComboBox.selectedItem = null
                fontComboBox.isEditable = true
                fontComboBox.selectedItem = FontWrapper.ForName(value.name)
                fontComboBox.isEditable = false
                fontComboBox.isEnabled = false
            }
        }

    override fun applyEnabled(isEnabled: Boolean) {
        super.applyEnabled(isEnabled)
        if (isEnabled && fontComboBox.selectedItem is FontWrapper.ForName)
            fontComboBox.isEnabled = false
    }

    init {
        // Equip the family combo box with a custom renderer that shows category headers.
        familyComboBox.renderer = LabeledListCellRenderer(FontSampleListCellRenderer(), groupSpacing = 10) { index ->
            buildList {
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
                val selectedFamily = (familyComboBox.selectedItem as FamilyWrapper?)?.family
                fontComboBox.model = when (selectedFamily) {
                    null -> DefaultComboBoxModel()
                    else -> DefaultComboBoxModel<FontWrapper>(
                        selectedFamily.fonts.mapTo(Vector()) { FontWrapper.ForFont(it, selectedFamily) }
                    ).apply { selectedItem = FontWrapper.ForFont(selectedFamily.canonicalFont, selectedFamily) }
                }
                fontComboBox.isEnabled = isEnabled
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
            familyComboBox.model = DefaultComboBoxModel(families.mapTo(Vector(), ::FamilyWrapper)).apply {
                selectedItem = null
            }
            value = selected
        }
    }

    private fun getFamilyOf(font: Font) =
        projectFamilies.getFamily(font) ?: BUNDLED_FAMILIES.getFamily(font) ?: SYSTEM_FAMILIES.getFamily(font)


    private interface FontProvider {
        val font: Font?
    }


    // We also use this wrapper so that a user can type a family name while the combo box is in focus to select it.
    private data class FamilyWrapper(val family: FontFamily) : FontProvider, ComboBoxWrapper {
        override fun toString() = family.getFamily(Locale.getDefault())
        override val font get() = family.canonicalFont
        override val item get() = family
    }


    private sealed interface FontWrapper : FontProvider, ComboBoxWrapper {

        data class ForFont(override val font: Font, private val family: FontFamily?) : FontWrapper {
            // Retrieve the subfamily name from the font's family object. The fallback should never be needed.
            override fun toString(): String = family?.getSubfamilyOf(font, Locale.getDefault()) ?: font.fontName
            override val item get() = font
        }

        data class ForName(val name: String) : FontWrapper {
            override fun toString() = name
            override val font get() = null
            override val item get() = name
        }

    }


    private inner class FontSampleListCellRenderer<E : FontProvider> : ListCellRenderer<E> {

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

            label1.text = value?.toString() ?: ""

            // Show the sample text only in the popup menu, but not in the combo box. Make the label invisible
            // in the combo box to ensure that fonts with large ascent don't stretch out the combo box.
            label1.border = null
            label2.isVisible = false
            label2.font = list.font
            if (index != -1) {
                label1.border = BorderFactory.createEmptyBorder(5, 0, 5, 0)
                value?.font?.let { sampleFont ->
                    val effSampleFont = sampleFont.deriveFont(list.font.size2D * 1.25f)
                    val family = getFamilyOf(sampleFont)
                    when {
                        // Try to get the sample text from the font.
                        trySample(effSampleFont, family?.getSampleTextOf(sampleFont, Locale.getDefault())) -> {}
                        // If none is specified, or it has only unknown glyphs, use a standard one in the UI language.
                        trySample(effSampleFont, l10n("ui.form.fontSample")) -> {}
                        // If the standard sample text only has unknown glyphs, use a Latin one.
                        trySample(effSampleFont, l10n("ui.form.fontSample", FALLBACK_TRANSLATED_LOCALE)) -> {}
                        // If the Latin one also doesn't work, try all other sample texts we have available.
                        else -> for (locale in TRANSLATED_LOCALES)
                            if (trySample(effSampleFont, l10n("ui.form.fontSample", locale))) break
                    }
                }
            }

            return panel
        }

        private fun trySample(sampleFont: Font, sampleText: String?): Boolean {
            val missingGlyph = sampleFont.missingGlyphCode
            val gv = sampleFont.createGlyphVector(REF_FRC, sampleText ?: return false)
            // Only display the sample when at least one glyph is not the missing glyph placeholder.
            for (glyphIdx in 0..<gv.numGlyphs) if (gv.getGlyphCode(glyphIdx) != missingGlyph) {
                label2.isVisible = true
                label2.font = sampleFont
                label2.text = sampleText
                return true
            }
            return false
        }

    }

}


class FontFeatureWidget : Form.AbstractWidget<FontFeature>() {

    private val tagWidget = InconsistentComboBoxWidget(
        String::class.java, emptyList(),
        // Clip the string because ellipsis would take up the majority of the space in a label as narrow as this one.
        toString = ::noEllipsisLabel,
        widthSpec = WidthSpec.LITTLE
    )
    private val valWidget = SpinnerWidget(
        Int::class.javaObjectType, SpinnerNumberModel(1, 0, null, 1), widthSpec = WidthSpec.TINIER
    )

    init {
        // When a wrapped widget changes, notify this widget's change listeners that that widget has changed.
        tagWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
        valWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components: List<JComponent> = tagWidget.components + JLabel("=") + valWidget.components
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
        // By default, the checkbox is deselected, so the wrapped widget needs to be disabled.
        wrapped.isEnabled = false
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

    override fun applyVisible(isVisible: Boolean) {
        cb.isVisible = isVisible
        wrapped.isVisible = isVisible
    }

    override fun applyEnabled(isEnabled: Boolean) {
        cb.isEnabled = isEnabled
        wrapped.isEnabled = isEnabled && cb.isSelected
    }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        wrapped.applyConfigurator(configurator)
    }

}


abstract class AbstractListWidget<E : Any, W : Form.Widget<E>>(
    private val makeElementWidget: () -> W,
    private val newElement: E?,
    private val newElementIsLastElement: Boolean
) : Form.AbstractWidget<List<E>>() {

    private val partWidgets = mutableListOf<W>()

    var elementCount = 0
        set(elementCount) {
            if (field == elementCount)
                return
            while (partWidgets.size < elementCount)
                appendNewPart()
            for (idx in elementCount..<field)
                setPartVisible(idx, partWidgets[idx], false)
            if (field < elementCount) {
                val fillElement = getFillElement()
                for (idx in field..<elementCount) {
                    val widget = partWidgets[idx]
                    setPartVisible(idx, widget, true)
                    withoutChangeListeners { setPartElement(idx, widget, fillElement) }
                }
            }
            field = elementCount
            notifyChangeListeners()
        }

    val elementWidgets: List<W>
        get() = partWidgets.subList(0, elementCount)

    override var value: List<E>
        get() = elementWidgets.mapIndexed(::getPartElement)
        set(value) {
            withoutChangeListeners { elementCount = value.size }
            for ((idx, widget) in partWidgets.withIndex()) {
                val vis = idx < value.size
                setPartVisible(idx, widget, vis)
                if (vis)
                    withoutChangeListeners { setPartElement(idx, widget, value[idx]) }
            }
            notifyChangeListeners()
        }

    private fun getFillElement(): E = when {
        newElementIsLastElement && elementCount >= 1 ->
            getPartElement(elementCount - 1, partWidgets[elementCount - 1])
        newElement != null -> newElement
        else -> throw IllegalStateException("No way to choose value of new ListWidget element.")
    }

    /** Already prepares some parts so that user interaction will appear quicker later on. */
    protected fun addInitialParts() {
        repeat(4) { idx ->
            appendNewPart()
            setPartVisible(idx, partWidgets[idx], false)
        }
    }

    protected fun userAdd(idx: Int, element: E? = null) {
        require(idx in 0..elementCount)
        // If there is still an unused part at the back of the list, just reactivate it. Otherwise, create a new one.
        if (elementCount + 1 <= partWidgets.size)
            setPartVisible(elementCount, partWidgets[elementCount], true)
        else
            appendNewPart()
        // This reorder mapping shifts list elements to the right to make room for the added one.
        val mapping = IntArray(elementCount + 1) { it }
        mapping[elementCount /* exceeds current list size, hence filled with fillElement */] = idx
        for (i in idx..<elementCount)
            mapping[i] = i + 1
        withoutChangeListeners { reorder(mapping, element ?: getFillElement()) }
        elementCount++
    }

    protected fun userDel(idx: Int) {
        require(idx in 0..<elementCount)
        // This reorder mapping shifts list elements to the left to overwrite the removed one.
        val mapping = IntArray(elementCount) { it }
        mapping[idx] = -1
        for (i in idx + 1..<elementCount)
            mapping[i] = i - 1
        withoutChangeListeners { reorder(mapping) }
        elementCount--
    }

    /**
     * Removes the element at fromIdx, shifts all elements until toIdx into the now empty space,
     * and then reinserts the removed element at toIdx.
     */
    protected fun userMov(fromIdx: Int, toIdx: Int) {
        require(fromIdx in 0..<elementCount)
        require(toIdx in 0..<elementCount)
        if (fromIdx == toIdx)
            return
        // This reorder mapping applies the rotation described in the function comment.
        val mapping = IntArray(elementCount) { it }
        mapping[fromIdx] = toIdx
        if (fromIdx < toIdx)
            for (i in fromIdx + 1..toIdx)
                mapping[i] = i - 1
        else
            for (i in toIdx..<fromIdx)
                mapping[i] = i + 1
        withoutChangeListeners { reorder(mapping) }
        notifyChangeListeners()
    }

    /**
     * Reorders the list based on the given mapping. Querying the mapping array at `fromIdx` yields `toIdx`. Values of
     * `fromIdx` exceeding the current list size are assumed to refer to the given [fillElement]. Values of `toIdx` that
     * are -1 prevent the element from being copied anywhere.
     */
    private fun reorder(mapping: IntArray, fillElement: E? = null) {
        val oldList = value
        for ((fromIdx, toIdx) in mapping.withIndex())
            if (toIdx != -1) {
                val element = if (fromIdx >= oldList.size) fillElement!! else
                    adjustElementOnReorder(oldList[fromIdx], mapping)
                if (toIdx >= oldList.size || oldList[toIdx] != element)
                    setPartElement(toIdx, partWidgets[toIdx], element)
            }
    }

    private fun appendNewPart() {
        val widget = makeElementWidget()
        // When the new widget changes, notify this widget's change listeners that that widget has changed.
        widget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
        partWidgets.add(widget)
        addPart(partWidgets.size - 1, widget)
    }

    protected abstract fun addPart(idx: Int, widget: W)
    protected abstract fun setPartVisible(idx: Int, widget: W, isVisible: Boolean)
    protected open fun getPartElement(idx: Int, widget: W): E = partWidgets[idx].value
    protected open fun setPartElement(idx: Int, widget: W, element: E) = run { partWidgets[idx].value = element }
    protected open fun adjustElementOnReorder(element: E, mapping: IntArray): E = element

    override fun getSeverity(index: Int): Severity? =
        if (index == -1) super.getSeverity(-1) else partWidgets[index].getSeverity(-1)

    override fun setSeverity(index: Int, severity: Severity?) {
        if (index == -1) {
            super.setSeverity(-1, severity)
            for (widget in partWidgets)
                widget.setSeverity(-1, severity)
        } else if (index in 0..<elementCount)
            partWidgets[index].setSeverity(-1, severity)
    }

    override fun applyConfigurator(configurator: (Form.Widget<*>) -> Unit) {
        configurator(this)
        for (idx in 0..<elementCount)
            partWidgets[idx].applyConfigurator(configurator)
    }

}


class SimpleListWidget<E : Any>(
    makeElementWidget: () -> Form.Widget<E>,
    newElement: E? = null,
    newElementIsLastElement: Boolean = false,
    private val elementsPerRow: Int = 1,
    private val fixedSize: Boolean = false,
    private val minSize: Int = 0
) : AbstractListWidget<E, Form.Widget<E>>(makeElementWidget, newElement, newElementIsLastElement) {

    private val panel = object : JPanel(MigLayout("hidemode 3, insets 0")) {
        init {
            background = null
        }

        override fun getBaseline(width: Int, height: Int): Int {
            // Since the vertical insets of this panel are 0, we can directly forward the baseline query to a component
            // in the first row. We choose the first one which has a valid baseline.
            for (idx in 0..<elementsPerRow.coerceAtMost(elementCount))
                for (comp in elementWidgets[idx].components) {
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

    private val delBtns = mutableListOf<JButton>()

    init {
        require(newElement != null || minSize > 0)

        addInitialParts()
        if (minSize > 0)
            changeListeners.add { for (delBtn in delBtns) delBtn.isEnabled = elementCount > minSize }
    }

    override val components = buildList<JComponent> {
        if (!fixedSize)
            add(JButton(ADD_ICON).also { it.addActionListener { userAdd(elementCount) } })
        add(panel)
    }

    override val constraints = buildList {
        if (!fixedSize)
            add("aligny top, gapy 1 1")
        add("")
    }

    override fun addPart(idx: Int, widget: Form.Widget<E>) {
        var firstConstr = if (idx != 0 && idx % elementsPerRow == 0) ", newline" else ""

        if (!fixedSize) {
            val delBtn = JButton(TRASH_ICON)
            delBtn.addActionListener { userDel(delBtns.indexOfFirst { it === delBtn }) }
            panel.add(delBtn, "aligny top, gapleft 6, gaptop 1" + firstConstr.also { firstConstr = "" })
            delBtns.add(delBtn)
        }

        for ((comp, constr) in widget.components.zip(widget.constraints))
            panel.add(comp, constr + firstConstr.also { firstConstr = "" })

        panel.revalidate()
    }

    override fun setPartVisible(idx: Int, widget: Form.Widget<E>, isVisible: Boolean) {
        if (!fixedSize)
            delBtns[idx].isVisible = isVisible
        widget.isVisible = isVisible
    }

}


class LayerListWidget<E : Any, W : Form.Widget<E>>(
    makeElementWidget: () -> W,
    newElement: E,
    private val getElementName: (E) -> String,
    private val getElementAdvanced: (E) -> Boolean,
    private val setElementNameAndAdvanced: (E, String, Boolean) -> E,
    private val mapOrdinalsInElement: (E, mapping: (Int) -> Int?) -> E,
    private val toggleAdvanced: (W, Boolean) -> Unit
) : AbstractListWidget<E, W>(makeElementWidget, newElement, newElementIsLastElement = false) {

    private val panel = object : JPanel(MigLayout("hidemode 3, insets 0, gapy 0, fillx, wrap", "[fill]")) {
        override fun getBaseline(width: Int, height: Int): Int {
            // Manually compute where the topmost baseline would be, to ensure that the row label stays up top.
            val fm = getFontMetrics(font)
            return ceilDiv(STD_HEIGHT + fm.ascent - fm.descent, 2)
        }
    }

    private val layerPanels = mutableListOf<LayerPanel>()
    private val addButtons = mutableListOf<AddButton>()

    init {
        val addBtn = AddButton(addAtIdx = 0)
        panel.add(addBtn)
        addButtons.add(addBtn)
        addInitialParts()
    }

    override val components = listOf<JComponent>(panel)
    override val constraints = listOf("growx, pushx")

    override fun addPart(idx: Int, widget: W) {
        val layerPanel = LayerPanel(idx, widget)
        panel.add(layerPanel, 0)
        layerPanels.add(layerPanel)
        val addBtn = AddButton(addAtIdx = idx + 1)
        panel.add(addBtn, 0)
        addButtons.add(addBtn)
    }

    override fun setPartVisible(idx: Int, widget: W, isVisible: Boolean) {
        layerPanels[idx].isVisible = isVisible
        addButtons[idx + 1].isVisible = isVisible
    }

    override fun getPartElement(idx: Int, widget: W): E {
        val element = super.getPartElement(idx, widget)
        val lp = layerPanels[idx]
        return setElementNameAndAdvanced(element, lp.nameWidget.value, lp.advancedBtn.isSelected)
    }

    override fun setPartElement(idx: Int, widget: W, element: E) {
        super.setPartElement(idx, widget, element)
        val lp = layerPanels[idx]
        withoutChangeListeners {
            lp.nameWidget.value = getElementName(element)
            lp.advancedBtn.isSelected = getElementAdvanced(element)
        }
    }

    override fun adjustElementOnReorder(element: E, mapping: IntArray): E =
        mapOrdinalsInElement(element) { ordinal ->
            val fromIdx = ordinal - 1
            if (fromIdx !in mapping.indices) return@mapOrdinalsInElement null
            val toIdx = mapping[fromIdx]
            if (toIdx == -1) null else toIdx + 1
        }


    private inner class LayerPanel(idx: Int, widget: W) : JPanel() {

        val nameWidget: TextWidget
        val advancedBtn: JToggleButton

        init {
            putClientProperty(STYLE, "background: @componentBackground")
            border = FlatBorder()

            nameWidget = TextWidget()
            // When the name widget changes, notify the LayerListWidget's change listeners.
            nameWidget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)

            advancedBtn = JToggleButton(l10n("ui.form.layerAdvanced"), ADVANCED_ICON)
            advancedBtn.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            advancedBtn.addItemListener {
                toggleAdvanced(widget, it.stateChange == ItemEvent.SELECTED)
                // When the advanced button is clicked, notify the LayerListWidget's change listener.
                notifyChangeListeners()
            }
            // By default, advancedBtn is not selected, so inform the wrapped widget about that.
            toggleAdvanced(widget, false)

            val delBtn = JButton(l10n("ui.form.layerDelete"), TRASH_ICON)
            delBtn.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            delBtn.addActionListener { userDel(idx) }

            val widgetPanel = JPanel(MigLayout()).apply { border = FlatBorder() }
            for ((comp, constr) in widget.components.zip(widget.constraints))
                widgetPanel.add(comp, constr)

            layout = MigLayout()
            add(Grip(), "split 6, center, gap 20:40: 30")
            add(JLabel((idx + 1).toString()).apply { putClientProperty(STYLE, "font: bold") })
            add(nameWidget.components.single(), "width 100, growx, shrinkprio 50, gap 12 10")
            add(advancedBtn)
            add(delBtn)
            add(Grip(), "gap 30 20:40:")
            add(widgetPanel, "newline, growx, pushx")

            // Add a transfer handler for dragging.
            transferHandler = LayerPanelTransferHandler(idx)
            // Add a mouse listener that notifies the transfer handler when dragging should start.
            val mouseListener = object : MouseAdapter() {
                private val thresh = DragSource.getDragThreshold()
                private var startPoint: Point? = null

                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1)
                        startPoint = e.point
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1)
                        startPoint = null
                }

                override fun mouseDragged(e: MouseEvent) {
                    startPoint?.let { s ->
                        if (abs(e.x - s.x) > thresh || abs(e.y - s.y) > thresh) {
                            startPoint = null
                            startDrag(e)
                        }
                    }
                }
            }
            addMouseListener(mouseListener)
            addMouseMotionListener(mouseListener)
            // Prevent dragging when the user clicks inside the widget panel.
            widgetPanel.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    e.consume()
                }
            })
            // Highlight the AddButton above/below when the user hovers over the top/bottom half during drag-and-drop.
            dropTarget.addDropTargetListener(object : DropTargetAdapter() {
                override fun dragOver(dtde: DropTargetDragEvent) {
                    val above = dtde.location.y < height / 2
                    addButtons[idx + if (above) 1 else 0].dndHoverExternal = true
                    addButtons[idx + if (above) 0 else 1].dndHoverExternal = false
                }

                override fun dragExit(dte: DropTargetEvent) = unsetAddBtnDnDHoverExt()
                override fun drop(dtde: DropTargetDropEvent) = unsetAddBtnDnDHoverExt()
                private fun unsetAddBtnDnDHoverExt() {
                    addButtons[idx].dndHoverExternal = false
                    addButtons[idx + 1].dndHoverExternal = false
                }
            })
        }

        // Note: The autoscroll functionality inside this function is adapted from TransferHandler.DropHandler.
        private fun startDrag(e: MouseEvent) {
            // Find the form this widget is part of. We'll use it in a moment.
            val form = SwingUtilities.getAncestorOfClass(Form::class.java, this) as Form

            // This variable will constantly be updated to represent the current mouse position on the screen.
            var mousePos: Point? = null

            // Add a timer that periodically scrolls the form when the mouse is outside its visible portion.
            val toolkit = Toolkit.getDefaultToolkit()
            val interval = toolkit.getDesktopProperty("DnD.Autoscroll.interval") as Int? ?: 100
            val initialDelay = toolkit.getDesktopProperty("DnD.Autoscroll.initialDelay") as Int? ?: 100
            val timer = Timer(interval) {
                mousePos?.let { mousePos ->
                    val formOnScreen = form.locationOnScreen
                    val formVis = form.visibleRect
                    if (mousePos.y < formOnScreen.y + formVis.y) {
                        val dy = form.getScrollableUnitIncrement(formVis, SwingConstants.VERTICAL, -1)
                        form.scrollRectToVisible(Rectangle(formVis.x, formVis.y - dy, 0, 0))
                    } else if (mousePos.y > formOnScreen.y + formVis.y + formVis.height) {
                        val dy = form.getScrollableUnitIncrement(formVis, SwingConstants.VERTICAL, 1)
                        form.scrollRectToVisible(Rectangle(formVis.x, formVis.y + formVis.height + dy, 0, 0))
                    }
                }
            }
            timer.initialDelay = initialDelay
            timer.start()

            // Update the mousePos variable whenever the mouse moves. Also stop the timer and remove the listener when
            // the drag stops.
            val dragSource = DragSource.getDefaultDragSource()
            val dragListener = object : DragSourceAdapter() {
                override fun dragMouseMoved(dsde: DragSourceDragEvent) {
                    mousePos = dsde.location
                }

                override fun dragDropEnd(dsde: DragSourceDropEvent) {
                    dragSource.removeDragSourceListener(this)
                    dragSource.removeDragSourceMotionListener(this)
                    timer.stop()
                }
            }
            dragSource.addDragSourceListener(dragListener)
            dragSource.addDragSourceMotionListener(dragListener)

            // Actually start dragging.
            transferHandler.exportAsDrag(this, e, TransferHandler.MOVE)
        }

    }


    private class Grip : JComponent() {

        private val gripColor = UIManager.getColor("ToolBar.gripColor")

        init {
            preferredSize = Dimension(96 + 1, 12 + 1)
        }

        override fun paintComponent(g: Graphics) {
            g.color = gripColor
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    g.fillRect(x, y, 1, 1)
                    x += 3
                }
                y += 3
            }
        }

    }


    private inner class AddButton(addAtIdx: Int) : JButton(ADD_ICON) {

        private val background = UIManager.getColor("Button.background")
        private val disabledBackground = UIManager.getColor("Button.disabledBackground")
        private val focusedBackground = UIManager.getColor("Button.focusedBackground")
        private val hoverBackground = UIManager.getColor("Button.hoverBackground")
        private val pressedBackground = UIManager.getColor("Button.pressedBackground")
        private val selectedBackground = UIManager.getColor("Button.selectedBackground")
        private val focusedBorderColor = UIManager.getColor("Button.focusedBorderColor")

        private var dndHover = false
        var dndHoverExternal = false
            set(dndHover) {
                if (field == dndHover)
                    return
                field = dndHover
                repaint()
            }

        init {
            preferredSize = Dimension(0, 32)
            border = null
            addActionListener { userAdd(addAtIdx) }

            // Add a transfer handler for dropping.
            transferHandler = AddButtonTransferHandler(addAtIdx)
            // Highlight this button when the user hovers over it during drag-and-drop.
            dropTarget.addDropTargetListener(object : DropTargetAdapter() {
                override fun dragEnter(dtde: DropTargetDragEvent) = run { dndHover = true; repaint() }
                override fun dragExit(dte: DropTargetEvent) = run { dndHover = false; repaint() }
                override fun drop(dtde: DropTargetDropEvent) = run { dndHover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            icon.paintIcon(this, g, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)

            val w = (width - icon.iconWidth) / 2 - 8
            val h = 2
            val x1 = 0
            val x2 = width - w
            val y = (height - h) / 2
            g.color = when {
                dndHover || dndHoverExternal -> selectedBackground
                FlatUIUtils.isPermanentFocusOwner(this) -> focusedBorderColor
                else -> FlatButtonUI.buttonStateColor(
                    this, background, disabledBackground, focusedBackground, hoverBackground, pressedBackground
                )
            }
            g.fillRect(x1, y, w, h)
            g.fillRect(x2, y, w, h)
        }

    }


    private inner class LayerPanelTransferHandler(private val idx: Int) : TransferHandler() {
        override fun getSourceActions(c: JComponent) = COPY_OR_MOVE
        override fun createTransferable(c: JComponent) = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(LayerTransferData.FLAVOR)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == LayerTransferData.FLAVOR
            override fun getTransferData(flavor: DataFlavor) = LayerTransferData(this@LayerListWidget, idx)
        }

        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(LayerTransferData.FLAVOR)
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support))
                return false
            val addAtIdx = idx + if (support.dropLocation.dropPoint.y < support.component.height / 2) 1 else 0
            return importLayerTransferData(addAtIdx, support)
        }
    }


    private inner class AddButtonTransferHandler(private val addAtIdx: Int) : TransferHandler() {
        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(LayerTransferData.FLAVOR)
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support))
                return false
            return importLayerTransferData(addAtIdx, support)
        }
    }


    private fun importLayerTransferData(addAtIdx: Int, support: TransferHandler.TransferSupport): Boolean {
        val transferData = support.transferable.getTransferData(LayerTransferData.FLAVOR) as LayerTransferData
        // Only permit drag-and-drop within the same widget.
        if (transferData.widget !== this@LayerListWidget)
            return false
        val fromIdx = transferData.idx
        when (support.dropAction) {
            TransferHandler.COPY -> userAdd(addAtIdx, value[fromIdx])
            TransferHandler.MOVE -> userMov(fromIdx, if (addAtIdx <= fromIdx) addAtIdx else addAtIdx - 1)
            else -> return false
        }
        return true
    }


    private class LayerTransferData(val widget: LayerListWidget<*, *>, val idx: Int) {
        companion object {
            val FLAVOR = DataFlavor(
                DataFlavor.javaJVMLocalObjectMimeType + ";class=${LayerTransferData::class.java.name}"
            )
        }
    }

}


class UnionWidget(
    private val wrapped: List<Form.Widget<*>>,
    labels: List<String?>? = null,
    icons: List<Icon?>? = null,
    gaps: List<String?>? = null,
    newlines: List<Int> = emptyList()
) : Form.AbstractWidget<List<Any>>() {

    init {
        if (labels != null)
            require(wrapped.size == labels.size)
        if (icons != null)
            require(wrapped.size == icons.size)
        if (gaps != null)
            require(wrapped.size == gaps.size + 1)

        // When a wrapped widget changes, notify this widget's change listeners that that widget has changed.
        // When a wrapped widget becomes invisible or disabled, also modify its corresponding label or icon.
        for (widget in wrapped) {
            widget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
            widget.visibleListeners.add { isVisible -> widgetLabels[widget]?.isVisible = isVisible }
            widget.enabledListeners.add { isEnabled -> widgetLabels[widget]?.isEnabled = isEnabled }
        }
    }

    private val widgetLabels = HashMap<Form.Widget<*>, JLabel>()

    override val components = buildList {
        for ((idx, widget) in wrapped.withIndex()) {
            val label = labels?.get(idx)
            val icon = icons?.get(idx)
            if (label != null || icon != null) {
                val lbl = JLabel(label, icon, JLabel.LEADING)
                add(lbl)
                widgetLabels[widget] = lbl
            }
            addAll(widget.components)
        }
    }

    override val constraints = buildList {
        for ((idx, widget) in wrapped.withIndex()) {
            val hasLabel = labels?.get(idx) != null
            val hasIcon = icons?.get(idx) != null
            val hasNewline = idx in newlines
            var gapLeft = if (idx == 0 || hasNewline) "0" else gaps?.get(idx - 1)
            if (hasLabel || hasIcon) {
                if (gapLeft == null)
                    gapLeft = if (hasLabel) "unrel" else "10"
                val gapRight = if (hasLabel) "rel" else "3"
                add("gapx $gapLeft $gapRight" + if (hasNewline) ", newline" else "")
                addAll(widget.constraints)
            } else {
                val constr = (if (gapLeft != null) ", gapleft $gapLeft" else "") + if (hasNewline) ", newline" else ""
                add(widget.constraints[0] + constr)
                addAll(widget.constraints.subList(1, widget.constraints.size))
            }
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

    override fun applyVisible(isVisible: Boolean) {
        super.applyVisible(isVisible)
        for (widget in wrapped)
            widget.isVisible = isVisible
    }

    override fun applyEnabled(isEnabled: Boolean) {
        super.applyEnabled(isEnabled)
        for (widget in wrapped)
            widget.isEnabled = isEnabled
    }

    override fun getSeverity(index: Int): Severity? =
        if (index == -1) throw UnsupportedOperationException() else wrapped[index].getSeverity(-1)

    override fun setSeverity(index: Int, severity: Severity?) {
        if (index == -1) throw UnsupportedOperationException() else wrapped[index].setSeverity(-1, severity)
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

    override fun getSeverity(index: Int) = null
    override fun setSeverity(index: Int, severity: Severity?) {}

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
