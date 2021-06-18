package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.OptionallyEffective
import com.loadingbyte.cinecred.projectio.toFPS
import com.loadingbyte.cinecred.projectio.toString2
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.ItemEvent
import java.io.File
import java.nio.file.Path
import java.text.NumberFormat
import java.util.*
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.JTextComponent
import kotlin.math.ceil


abstract class AbstractTextComponentWidget<V>(
    protected val tc: JTextComponent,
    grow: Boolean = true
) : Form.AbstractWidget<V>() {

    init {
        tc.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                notifyChangeListeners()
            }
        })
        if (tc is JTextField)
            tc.addActionListener { notifyChangeListeners() }

        tc.setMinWidth100()
    }

    override val components = listOf<JComponent>(tc)
    override val constraints = listOf(if (grow) "width 40%" else "")

}


class TextWidget(
    grow: Boolean = true
) : AbstractTextComponentWidget<String>(JTextField(), grow) {
    override var value: String
        get() = tc.text
        set(value) {
            tc.text = value
            notifyChangeListeners()
        }
}


class TextListWidget(
    grow: Boolean = true
) : AbstractTextComponentWidget<ImmutableList<String>>(JTextArea(), grow) {
    override var value: ImmutableList<String>
        get() = tc.text.split("\n").filter(String::isNotBlank).toImmutableList()
        set(value) {
            tc.text = value.joinToString("\n")
            notifyChangeListeners()
        }
}


abstract class AbstractFilenameWidget<V>(
    grow: Boolean = true
) : AbstractTextComponentWidget<V>(JTextField(), grow) {

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

    override fun notifyChangeListeners() {
        // Ensure that the filename ends with an admissible file extension.
        tc.text = tc.text.ensureEndsWith(fileExts.map { ".$it" })
        super.notifyChangeListeners()
    }

}


class FilenameWidget(
    grow: Boolean = true
) : AbstractFilenameWidget<String>(grow) {
    override var value: String
        get() = tc.text.trim()
        set(value) {
            tc.text = value.trim()
            notifyChangeListeners()
        }
}


enum class FileType { FILE, DIRECTORY }

class FileWidget(
    fileType: FileType
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
    override val constraints = listOf("split, width 40%", "")

    override var value: Path
        get() = Path.of(tc.text.trim())
        set(value) {
            tc.text = value.toString()
            notifyChangeListeners()
        }

}


open class SpinnerWidget<V>(
    private val valueClass: Class<V>,
    model: SpinnerModel
) : Form.AbstractWidget<V>() {

    init {
        require(!valueClass.isPrimitive)
    }

    protected val spinner = JSpinner(model).apply {
        addChangeListener { notifyChangeListeners() }
        setMinWidth100()
    }

    override val components = listOf(spinner)
    override val constraints = listOf("")

    override var value: V
        get() = valueClass.cast(spinner.value)
        set(value) {
            spinner.value = value
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


open class ComboBoxWidget<E>(
    private val itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String = { it.toString() },
    hFill: Boolean = false,
    private val scrollbar: Boolean = true,
    decorateRenderer: ((ListCellRenderer<E>) -> ListCellRenderer<E>) = { it }
) : Form.AbstractWidget<E>(), Form.ChoiceWidget<E, E> {

    protected val cb = JComboBox<E>().apply {
        addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
        setMinWidth100()
        renderer = decorateRenderer(CustomToStringListCellRenderer(itemClass, toString))
        keySelectionManager = CustomToStringKeySelectionManager(itemClass, toString)
    }

    override val components = listOf(cb)
    override val constraints = listOf(if (hFill) "width 100%" else "wmax 40%")

    final override var items: ImmutableList<E> = persistentListOf()
        set(items) {
            field = items
            val newModel = DefaultComboBoxModel(Vector(items))
            value?.let { newModel.selectedItem = it }
            cb.model = newModel
            if (!scrollbar)
                cb.maximumRowCount = items.size
        }

    override var value: E
        get() = itemClass.cast(cb.selectedItem)
        set(value) {
            cb.selectedItem = value
        }

    init {
        this.items = items.toImmutableList()
    }

}


class InconsistentComboBoxWidget<E : Any>(
    itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String = { it.toString() }
) : ComboBoxWidget<E>(itemClass, items, toString) {

    override var value: E
        get() = super.value
        set(value) {
            cb.isEditable = value !in items
            super.value = value
            cb.isEditable = false
        }

}


open class EditableComboBoxWidget<E>(
    itemClass: Class<E>,
    items: List<E>,
    toString: (E) -> String,
    fromString: ((String) -> E)
) : ComboBoxWidget<E>(itemClass, items, toString) {

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
                wrappedEditor.item = toString(itemClass.cast(item))
            }
        }
    }

}


class FPSWidget : EditableComboBoxWidget<FPS>(FPS::class.java, SUGGESTED_FPS, ::toDisplayString, ::fromDisplayString) {

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


class ComboBoxListWidget<E>(
    private val itemClass: Class<E>,
    items: List<E>,
    private val toString: (E) -> String = { it.toString() }
) : Form.AbstractWidget<ImmutableList<E>>(), Form.ChoiceWidget<ImmutableList<E>, E> {

    private val panel = JPanel(MigLayout("insets 0"))
    private val addBtn = JButton(ADD_ICON)
    private val delBtn = JButton(REMOVE_ICON).apply { isEnabled = false }
    private val cbs = mutableListOf<JComboBox<E>>()

    init {
        addBtn.addActionListener { value = (value + value.last()).toImmutableList() }
        delBtn.addActionListener { value = value.dropLast(1).toImmutableList() }
    }

    override val components = listOf<JComponent>(panel, addBtn, delBtn)

    // Note: We have to manually specify "aligny top" for the ComboBoxList for some reason. If we don't and a
    // multiline verification message occurs, the ComboBoxList does for some reason align itself in the middle.
    override val constraints = listOf("split, aligny top", "", "")

    override var items: ImmutableList<E> = items.toImmutableList()
        set(items) {
            field = items
            for (cb in cbs)
                cb.model = DefaultComboBoxModel(Vector(items))
        }

    override var value: ImmutableList<E>
        get() = cbs.map { itemClass.cast(it.selectedItem) }.toImmutableList()
        set(value) {
            while (cbs.size < value.size) {
                val comboBox = JComboBox(DefaultComboBoxModel(Vector(items))).apply {
                    addItemListener { e -> if (e.stateChange == ItemEvent.SELECTED) notifyChangeListeners() }
                    renderer = CustomToStringListCellRenderer(itemClass, toString)
                    keySelectionManager = CustomToStringKeySelectionManager(itemClass, toString)
                }
                cbs.add(comboBox)
                panel.add(comboBox)
            }
            while (cbs.size > value.size)
                panel.remove(cbs.removeLast())
            for ((comboBox, item) in cbs.zip(value))
                comboBox.selectedItem = item
            panel.revalidate()
            delBtn.isEnabled = cbs.size != 1
            notifyChangeListeners()
        }

}


class ColorWellWidget(
    allowAlpha: Boolean = true
) : Form.AbstractWidget<Color>() {

    private val btn = object : JButton(" ") {
        init {
            setMinWidth100()
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
            var newColor: Color? = null

            val chooser = JColorChooser(value)
            // Disable the horrible preview panel.
            chooser.previewPanel = JPanel()
            // Disable the ability to choose transparent colors if that is desired.
            for (chooserPanel in chooser.chooserPanels)
                chooserPanel.isColorTransparencySelectionEnabled = allowAlpha
            // Show the color chooser and wait for the user to close it.
            JColorChooser.createDialog(
                btn, l10n("ui.form.colorChooserTitle"), true, chooser, { newColor = chooser.color }, null
            ).isVisible = true

            newColor?.let { value = it }
        }
    }

    override val components = listOf<JComponent>(btn)
    override val constraints = listOf("")

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


class FontChooserWidget : Form.AbstractWidget<String>(), Form.FontRelatedWidget<String> {

    private val familyComboBox = JComboBox<FontFamily>().apply {
        maximumRowCount = 20
        keySelectionManager = CustomToStringKeySelectionManager(FontFamily::class.java) { it.familyName }
    }

    private val fontComboBox = JComboBox<Any>(emptyArray()).apply {
        maximumRowCount = 20
        fun toString(value: Any) = if (value is Font) value.getFontName(Locale.ROOT) else value as String
        renderer = FontSampleListCellRenderer<Any>(::toString) { if (it is Font) it else null }
        keySelectionManager = CustomToStringKeySelectionManager(Any::class.java, ::toString)
    }

    override val components = listOf(familyComboBox, fontComboBox)
    override val constraints = listOf("width 40%!", "newline, width 40%!")

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

        familyComboBox.addActionListener {
            if (!disableFamilyListener) {
                val selectedFamily = familyComboBox.selectedItem as FontFamily?
                fontComboBox.model = DefaultComboBoxModel(selectedFamily?.fonts?.toTypedArray() ?: emptyArray())
                if (selectedFamily != null)
                    fontComboBox.selectedItem = selectedFamily.canonicalFont
                fontComboBox.isEditable = false
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


class OptionallyEffectiveWidget<V>(
    override val wrapped: Form.Widget<V>
) : Form.AbstractWidget<OptionallyEffective<V>>(), Form.WrapperWidget<OptionallyEffective<V>, Form.Widget<V>> {

    init {
        wrapped.changeListeners.add(::notifyChangeListeners)
    }

    private val cb = JCheckBox().apply {
        addItemListener {
            wrapped.isEnabled = isSelected
            notifyChangeListeners()
        }
    }

    override val components = listOf(cb) + wrapped.components
    override val constraints = listOf("split") + wrapped.constraints

    override var value: OptionallyEffective<V>
        get() = OptionallyEffective(cb.isSelected, wrapped.value)
        set(value) {
            cb.isSelected = value.isEffective
            wrapped.value = value.value
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(isEnabled) {
            super.isEnabled = isEnabled
            wrapped.isEnabled = cb.isSelected
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

private fun <C : Component> C.setMinWidth100() {
    minimumSize = Dimension(100, minimumSize.height)
}
