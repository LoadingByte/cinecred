package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_BORDERLESS
import com.formdev.flatlaf.ui.FlatUIUtils
import com.formdev.flatlaf.util.UIScale
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.Opt
import com.loadingbyte.cinecred.project.TimecodeFormat
import com.loadingbyte.cinecred.projectio.formatTimecode
import com.loadingbyte.cinecred.projectio.parseTimecode
import com.loadingbyte.cinecred.projectio.toFPS
import com.loadingbyte.cinecred.projectio.toString2
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.io.File
import java.nio.file.Path
import java.text.NumberFormat
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.DefaultFormatter
import javax.swing.text.DefaultFormatterFactory
import javax.swing.text.JTextComponent
import kotlin.io.path.Path
import kotlin.math.ceil


enum class WidthSpec(val mig: String) {
    TINY("width 70lp!"),
    NARROW("width 100lp!"),
    FIT("width 100lp::40%"),
    WIDE("width 40%!"),
    FILL("width 100%!")
}


abstract class AbstractTextComponentWidget<V>(
    protected val tc: JTextComponent,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    init {
        tc.addChangeListener { notifyChangeListeners() }
    }

    override val components = listOf<JComponent>(tc)
    override val constraints = listOf((widthSpec ?: WidthSpec.WIDE).mig)

}


class TextWidget(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<String>(JTextField(), widthSpec) {
    override var value: String by tc::text
}


class TextListWidget(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<ImmutableList<String>>(JTextArea(), widthSpec) {
    override var value: ImmutableList<String>
        get() = tc.text.split("\n").filter(String::isNotBlank).toImmutableList()
        set(value) {
            tc.text = value.joinToString("\n")
        }
}


abstract class AbstractFilenameWidget<V>(
    widthSpec: WidthSpec? = null
) : AbstractTextComponentWidget<V>(JTextField(), widthSpec) {

    init {
        // When the user leaves the text field, ensure that it ends with an admissible file extension.
        tc.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                tc.text = tc.text.ensureEndsWith(fileExts.map { ".$it" })
            }
        })
    }

    var fileExts: ImmutableList<String> = persistentListOf()
        set(newFileExts) {
            // When the list of admissible file extensions is changed and the field text doesn't end with an
            // admissible file extension anymore, remove the previous file extension (if there was any) and add
            // the default new one.
            if (!newFileExts.any { tc.text.endsWith(".$it") }) {
                tc.text = tc.text.ensureDoesntEndWith(fileExts.map { ".$it" })
                tc.text = tc.text.ensureEndsWith(newFileExts.map { ".$it" })
            }
            field = newFileExts
        }

}


class FilenameWidget(
    widthSpec: WidthSpec? = null
) : AbstractFilenameWidget<String>(widthSpec) {
    override var value: String
        get() = tc.text.trim()
        set(value) {
            tc.text = value.trim()
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
            val fc = JFileChooser()
            fc.fileSelectionMode = when (fileType) {
                FileType.FILE -> JFileChooser.FILES_ONLY
                FileType.DIRECTORY -> JFileChooser.DIRECTORIES_ONLY
            }
            fc.selectedFile = File(tc.text.ensureDoesntEndWith(fileExts.map { ".$it" }))

            if (fileExts.isNotEmpty()) {
                fc.isAcceptAllFileFilterUsed = false
                for (fileExt in fileExts) {
                    val filter = FileNameExtensionFilter("*.$fileExt", fileExt)
                    fc.addChoosableFileFilter(filter)
                    if (tc.text.endsWith(".$fileExt"))
                        fc.fileFilter = filter
                }
            }

            if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                tc.text = fc.selectedFile.absolutePath
                if (fileExts.isNotEmpty()) {
                    val selectedFileExts = (fc.fileFilter as FileNameExtensionFilter).extensions
                    tc.text = tc.text.ensureEndsWith(selectedFileExts.map { ".$it" })
                }
            }
        }
    }

    override val components = listOf<JComponent>(tc, browse)
    override val constraints = listOf("split, ${(widthSpec ?: WidthSpec.WIDE).mig}", "")

    override var value: Path
        get() = Path(tc.text.trim())
        set(value) {
            tc.text = value.toString()
        }

}


open class SpinnerWidget<V>(
    private val valueClass: Class<V>,
    model: SpinnerModel,
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<V>() {

    init {
        require(!valueClass.isPrimitive)
    }

    protected val spinner = JSpinner(model).apply {
        addChangeListener { notifyChangeListeners() }
    }

    override val components = listOf(spinner)
    override val constraints = listOf((widthSpec ?: WidthSpec.NARROW).mig)

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
            field = fps
            updateFormatter()
        }

    var timecodeFormat: TimecodeFormat = timecodeFormat
        set(timecodeFormat) {
            field = timecodeFormat
            updateFormatter()
        }

    private val editor = JSpinner.DefaultEditor(spinner).apply {
        textField.isEditable = true
    }

    init {
        spinner.font = Font(Font.MONOSPACED, Font.PLAIN, spinner.font.size)
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

        override fun valueToString(value: Any?): String? =
            runCatching { formatTimecode(fps, timecodeFormat, value as Int) }.getOrNull()

        override fun stringToValue(string: String?): Int? =
            runCatching { parseTimecode(fps, timecodeFormat, string!!) }.getOrNull()

    }

}


class CheckBoxWidget : Form.AbstractWidget<Boolean>() {

    private val cb = JCheckBox().apply {
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


open class ComboBoxWidget<E : Any /* non-null */>(
    private val itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String = { it.toString() },
    widthSpec: WidthSpec? = null,
    private val scrollbar: Boolean = true,
    decorateRenderer: ((ListCellRenderer<E>) -> ListCellRenderer<E>) = { it }
) : Form.AbstractWidget<E>(), Form.ChoiceWidget<E, E> {

    protected val cb = JComboBox<E>().apply {
        addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
        renderer = decorateRenderer(CustomToStringListCellRenderer(itemClass, toString))
        keySelectionManager = CustomToStringKeySelectionManager(itemClass, toString)
    }

    override val components = listOf(cb)
    override val constraints = listOf((widthSpec ?: WidthSpec.FIT).mig)

    final override var items: ImmutableList<E> = persistentListOf()
        set(items) {
            field = items
            val oldSelectedItem = cb.selectedItem?.let(itemClass::cast)
            cb.model = makeModel(Vector(items), oldSelectedItem)
            if (cb.selectedItem != oldSelectedItem)
                notifyChangeListeners()
            if (!scrollbar)
                cb.maximumRowCount = items.size
        }

    protected open fun makeModel(items: Vector<E>, oldSelectedItem: E?) =
        DefaultComboBoxModel(items).apply {
            if (oldSelectedItem in items)
                selectedItem = oldSelectedItem
        }

    override var value: E
        get() = itemClass.cast(cb.selectedItem!!)
        set(value) {
            cb.selectedItem = value
        }

    init {
        this.items = items.toImmutableList()
    }

}


class InconsistentComboBoxWidget<E : Any /* non-null */>(
    itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String = { it.toString() },
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<E>(itemClass, items, toString, widthSpec) {

    override fun makeModel(items: Vector<E>, oldSelectedItem: E?) =
        DefaultComboBoxModel(items).apply { selectedItem = oldSelectedItem }

    override var value: E
        get() = super.value
        set(value) {
            cb.isEditable = value !in items
            super.value = value
            cb.isEditable = false
        }

}


open class EditableComboBoxWidget<E : Any /* non-null */>(
    itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String,
    fromString: ((String) -> E),
    widthSpec: WidthSpec? = null
) : ComboBoxWidget<E>(itemClass, items, toString, widthSpec) {

    init {
        cb.isEditable = true

        val wrappedEditor = cb.editor
        cb.editor = object : ComboBoxEditor by wrappedEditor {
            private var prevItem: Any? = null

            override fun getItem(): Any? =
                try {
                    fromString(wrappedEditor.item as String)
                } catch (_: IllegalArgumentException) {
                    item = prevItem
                    prevItem
                }

            override fun setItem(item: Any?) {
                prevItem = item
                wrappedEditor.item = item?.let { toString(itemClass.cast(it)) }
            }
        }
    }

    override fun makeModel(items: Vector<E>, oldSelectedItem: E?) =
        DefaultComboBoxModel(items).apply { selectedItem = oldSelectedItem }

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
                else -> fps.toString2()
            }
        }

        private fun fromDisplayString(str: String): FPS {
            runCatching { NumberFormat.getNumberInstance().parse(str) }.onSuccess { frac ->
                SUGGESTED_FRAC_FPS.find { it.first == frac }?.let { return it.second }
            }
            runCatching(str::toInt).onSuccess { return FPS(it, 1) }
            return str.toFPS()
        }

    }

}


class ToggleButtonGroupWidget<E : Any /* non-null */>(
    items: List<E>,
    private val toIcon: ((E) -> Icon)? = null,
    private val toLabel: ((E) -> String)? = null,
    private val toTooltip: ((E) -> String)? = null
) : Form.AbstractWidget<E>(), Form.ChoiceWidget<E, E> {

    private val panel = GroupPanel()
    private val btnGroup = ButtonGroup()

    override val components = listOf<JComponent>(panel)
    override val constraints = listOf("")

    override var items: ImmutableList<E> = persistentListOf()
        set(items) {
            field = items
            panel.removeAll()
            btnGroup.elements.toList().forEach(btnGroup::remove)
            for (item in items) {
                val btn = JToggleButton()
                toIcon?.let { btn.icon = it(item) }
                toLabel?.let { btn.text = it(item) }
                toTooltip?.let { btn.toolTipText = it(item) }
                btn.putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
                btn.model.addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
                panel.add(btn)
                btnGroup.add(btn)
            }
        }

    override var value: E
        get() = items[btnGroup.elements.asSequence().indexOfFirst { it.model == btnGroup.selection }]
        set(value) {
            val idx = items.indexOf(value)
            if (idx == -1)
                throw IllegalArgumentException()
            btnGroup.setSelected(btnGroup.elements.asSequence().drop(idx).first().model, true)
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            for (btn in btnGroup.elements)
                btn.isEnabled = isEnabled
        }

    init {
        this.items = items.toImmutableList()
    }


    private class GroupPanel : JPanel(MigLayout("insets 0 1lp 0 1lp, gap 0")) {

        val arc = UIManager.getInt("Button.arc").toFloat()
        val backgroundColor: Color = UIManager.getColor("ComboBox.background")
        val disabledBackgroundColor: Color = UIManager.getColor("ComboBox.disabledBackground")
        val borderColor: Color = UIManager.getColor("Component.borderColor")
        val disabledBorderColor: Color = UIManager.getColor("Component.disabledBorderColor")

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.withNewG2 { g2 ->
                FlatUIUtils.setRenderingHints(g2)
                g2.color = if (isEnabled) backgroundColor else disabledBackgroundColor
                FlatUIUtils.paintComponentBackground(g2, 0, 0, width, height, 0f, UIScale.scale(arc))
            }
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            g.withNewG2 { g2 ->
                FlatUIUtils.setRenderingHints(g2)
                g2.color = if (isEnabled) borderColor else disabledBorderColor
                FlatUIUtils.paintComponentBorder(g2, 0, 0, width, height, 0f, UIScale.scale(1f), UIScale.scale(arc))
            }
        }

        override fun getBaseline(width: Int, height: Int): Int {
            // Since the vertical insets of this panel are 0, its baseline is equivalent to that of its components.
            // Now, as all toggle buttons have the same baseline, just ask the first one.
            return if (components.isEmpty()) -1 else components[0].let { c ->
                // It turns out that using "c.minimumSize.height" here and in the manual calculation a couple of lines
                // below alleviates some re-layouting after the first painting that looks quite ugly.
                var baseline = c.getBaseline(c.width, c.minimumSize.height)
                if (baseline == -1) {
                    // If the toggle button doesn't have a baseline because it has no label, manually compute where the
                    // baseline would be if it had one. This makes all toggle button groups along with their form labels
                    // look more consistent. It also allows to place the form label of a toggle button group list widget
                    // with unlabeled toggle buttons such that it matches the y position of the first row.
                    // The baseline turns out to be the baseline position of a vertically centered string.
                    val fm = c.getFontMetrics(c.font)
                    baseline = (c.minimumSize.height + fm.ascent - fm.descent) / 2
                }
                baseline
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
    override val constraints = listOf((widthSpec ?: WidthSpec.NARROW).mig)

    override var value: Color = Color.BLACK
        set(value) {
            field = value
            btn.background = value
            notifyChangeListeners()
        }

    private fun Graphics.drawCheckerboard(width: Int, height: Int, n: Int) {
        val checkerSize = ceil(height / n.toFloat()).toInt()
        for (x in 0 until width / checkerSize)
            for (y in 0 until n) {
                color = if ((x + y) % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
                fillRect(x * checkerSize, y * checkerSize, checkerSize, checkerSize)
            }
    }

}


class FontChooserWidget(
    widthSpec: WidthSpec? = null
) : Form.AbstractWidget<String>(), Form.FontRelatedWidget<String> {

    private val familyComboBox = JComboBox<FontFamily>().apply {
        maximumRowCount = 20
        // Note: We do not localize font family names since obtaining family names requires some manual processing
        // (see the UI helper Fonts file) and for now it would be too much work adapting this logic to each
        // supported language.
        keySelectionManager = CustomToStringKeySelectionManager(FontFamily::class.java) { it.familyName }
    }

    private val fontComboBox = JComboBox<Any>(emptyArray()).apply {
        maximumRowCount = 20
        fun toString(value: Any) = if (value is Font) value.fontName /* localized */ else value as String
        renderer = FontSampleListCellRenderer<Any>(::toString) { if (it is Font) it else null }
        keySelectionManager = CustomToStringKeySelectionManager(Any::class.java, ::toString)
    }

    override val components = listOf(familyComboBox, fontComboBox)
    override val constraints = (widthSpec ?: WidthSpec.WIDE).let { listOf(it.mig, "newline, ${it.mig}") }

    override var projectFamilies: FontFamilies = FontFamilies(emptyList())
        set(value) {
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
                fontComboBox.isEditable = false
                fontComboBox.selectedItem = family.getFont(value)
            } else {
                familyComboBox.selectedItem = null
                fontComboBox.isEditable = true
                fontComboBox.selectedItem = value
                fontComboBox.isEditable = false
            }
        }

    private var disableFamilyListener = false

    init {
        // Equip the family combo box with a custom renderer that shows category headers.
        val baseRenderer = FontSampleListCellRenderer(FontFamily::familyName, FontFamily::canonicalFont)
        familyComboBox.renderer = LabeledListCellRenderer(baseRenderer, groupSpacing = 8) { index: Int ->
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
            if (!disableFamilyListener && e.stateChange == ItemEvent.SELECTED) {
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
        // Note: We temporarily disable the family change listener because otherwise,
        // it would trigger multiple times and, even worse, with intermediate states.
        disableFamilyListener = true
        try {
            val selected = value
            val families = projectFamilies.list + BUNDLED_FAMILIES.list + SYSTEM_FAMILIES.list
            familyComboBox.model = DefaultComboBoxModel(families.toTypedArray())
            value = selected
        } finally {
            disableFamilyListener = false
        }
    }


    private class FontSampleListCellRenderer<E>(
        private val toString: (E) -> String,
        private val toFont: (E) -> Font?
    ) : ListCellRenderer<E> {

        private val label1 = JLabel()
        private val label2 = JLabel(l10n("ui.form.fontSample"))
        private val panel = JPanel(MigLayout("insets 0", "[]40lp:::push[]")).apply {
            add(label1)
            add(label2, "width 100lp!")
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
                label1.border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
                value?.let(toFont)?.let { sampleFont ->
                    label2.isVisible = true
                    label2.font = sampleFont.deriveFont(list.font.size2D * 1.25f)
                }
            }

            return panel
        }

    }

}


class OptWidget<V>(
    private val wrapped: Form.Widget<V>
) : Form.AbstractWidget<Opt<V>>() {

    init {
        // When the wrapped widget changes, notify this widget's change listeners that __this__ widget has changed.
        wrapped.changeListeners.add { notifyChangeListeners() }
    }

    private val cb = JCheckBox().apply {
        addItemListener {
            wrapped.isEnabled = isSelected
            notifyChangeListeners()
        }
    }

    override val components = listOf(cb) + wrapped.components
    override val constraints = listOf("split") + wrapped.constraints

    override var value: Opt<V>
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

}


class ListWidget<V>(
    private val groupsPerRow: Int = 1,
    private val minSize: Int = 0,
    private val newWidget: () -> Form.Widget<V>
) : Form.AbstractWidget<ImmutableList<V>>() {

    private val addBtn = JButton(ADD_ICON)

    private val panel = object : JPanel(MigLayout("insets 0")) {
        override fun getBaseline(width: Int, height: Int): Int {
            // Since the vertical insets of this panel are 0, we can directly forward the baseline query to a component.
            // By selecting the first one which has a valid baseline, we usually let the panel's baseline be the one
            // of the first row of wrapped widgets.
            for (comp in components) {
                val baseline = comp.getBaseline(0, 0 /* not used */)
                if (baseline >= 0)
                    return baseline
            }
            return -1
        }

        private var isInitialized = false
        fun ensureInitialized(numComponentsPerWidget: Int) {
            if (!isInitialized) {
                isInitialized = true
                val l = layout as MigLayout
                l.layoutConstraints = "${l.layoutConstraints}, wrap ${groupsPerRow * (1 + numComponentsPerWidget)}"
                revalidate()
            }
        }
    }

    private val elemWidgets = mutableListOf<Form.Widget<V>>()
    private val elemDelBtns = mutableListOf<JButton>()

    init {
        addBtn.addActionListener {
            addElemWidget(copyLastValue = true)
            notifyChangeListeners()
        }
    }

    override val components = listOf<JComponent>(addBtn, panel)
    override val constraints = listOf("split, aligny top", "")

    override var value: ImmutableList<V>
        get() = elemWidgets.map { it.value }.toImmutableList()
        set(value) {
            while (elemWidgets.size < value.size)
                addElemWidget()
            while (elemWidgets.size > value.size)
                removeElemWidget(elemWidgets.lastIndex)
            for ((widget, item) in elemWidgets.zip(value))
                widget.value = item
            notifyChangeListeners()
        }

    private fun addElemWidget(copyLastValue: Boolean = false) {
        val delBtn = JButton(REMOVE_ICON)
        delBtn.addActionListener {
            removeElemWidget(elemDelBtns.indexOfFirst { it === delBtn })
            notifyChangeListeners()
        }
        elemDelBtns.add(delBtn)
        panel.add(delBtn, "gapx 6lp 0lp")

        val widget = newWidget()
        // If this is the first widget we have created, we now have enough information to complete the
        // initialization of the panel.
        panel.ensureInitialized(widget.components.size)
        // If requested,the new widget should start out with the same value as the current last one.
        if (copyLastValue && elemWidgets.size != 0)
            widget.value = elemWidgets.last().value
        // When the wrapped widget changes, notify this widget's change listeners that __this__ widget has changed.
        widget.changeListeners.add { notifyChangeListeners() }
        elemWidgets.add(widget)
        for ((comp, constr) in widget.components.zip(widget.constraints))
            panel.add(comp, constr)

        panel.revalidate()
        enableOrDisableDelBtns()
    }

    private fun removeElemWidget(idx: Int) {
        elemWidgets.removeAt(idx).components.forEach(panel::remove)
        panel.remove(elemDelBtns.removeAt(idx))
        panel.revalidate()
        enableOrDisableDelBtns()
    }

    private fun enableOrDisableDelBtns() {
        val enabled = elemWidgets.size > minSize
        for (delBtn in elemDelBtns)
            delBtn.isEnabled = enabled
    }

}


class UnionWidget(
    private val wrapped: List<Form.Widget<*>>,
    icons: List<Icon>
) : Form.AbstractWidget<List<*>>() {

    init {
        require(wrapped.size == icons.size)

        // When a wrapped widget changes, notify this widget's change listeners that __the wrapped__ widget has changed.
        for (widget in wrapped)
            widget.changeListeners.add(::notifyChangeListenersAboutOtherWidgetChange)
    }

    override val components: List<JComponent> = mutableListOf<JComponent>().apply {
        for ((widget, icon) in wrapped.zip(icons)) {
            add(JLabel(icon))
            addAll(widget.components)
        }
    }

    override val constraints = mutableListOf<String>().apply {
        add("split, gapx 0 3lp")
        addAll(wrapped.first().constraints)
        for (widget in wrapped.drop(1)) {
            add("gapx 10lp 3lp")
            addAll(widget.constraints)
        }
    }

    override var value: List<*>
        get() = wrapped.map { it.value }
        set(value) {
            require(wrapped.size == value.size)
            for (idx in wrapped.indices)
                @Suppress("UNCHECKED_CAST")
                (wrapped[idx] as Form.Widget<Any?>).value = value[idx]
        }

}


private fun String.ensureEndsWith(suffixes: List<String>, ignoreCase: Boolean = true) =
    if (suffixes.isEmpty() || suffixes.any { endsWith(it, ignoreCase) }) this else this + suffixes[0]


private fun String.ensureDoesntEndWith(suffixes: List<String>, ignoreCase: Boolean = true): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
}


private inline fun JTextComponent.addChangeListener(crossinline listener: () -> Unit) {
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener()
        override fun removeUpdate(e: DocumentEvent) = listener()
        override fun changedUpdate(e: DocumentEvent) = listener()
    })
}
