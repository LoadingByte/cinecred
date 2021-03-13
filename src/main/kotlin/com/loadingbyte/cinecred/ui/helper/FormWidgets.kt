package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatUIUtils
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withNewG2
import com.loadingbyte.cinecred.drawer.getSystemFont
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.ceil


open class TextWidget(
    grow: Boolean = true,
    verify: ((String) -> Unit)? = null
) : Form.Widget() {

    protected val tf = JTextField().apply {
        addChangeListener { notifyChangeListeners() }
        setMinWidth100()
    }

    override val components = listOf<JComponent>(tf)
    override val constraints = listOf(if (grow) "width 40%" else "")
    override val verify = verify?.let { { it(text) } }

    var text: String by tf::text

}


open class FilenameWidget(
    grow: Boolean = true,
    verify: ((String) -> Unit)? = null
) : TextWidget(grow, verify) {

    init {
        // When the user leaves the text field, ensure that it ends with an admissible file extension.
        tf.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                tf.text = tf.text.ensureEndsWith(fileExts.map { ".$it" })
            }
        })
    }

    var fileExts: List<String>
        get() = _fileExts
        set(newFileExts) {
            // When the list of admissible file extensions is changed and the field text doesn't end with an
            // admissible file extension anymore, remove the previous file extension (if there was any) and add
            // the default new one.
            if (!newFileExts.any { tf.text.endsWith(".$it") }) {
                tf.text = tf.text.ensureDoesntEndWith(fileExts.map { ".$it" })
                tf.text = tf.text.ensureEndsWith(newFileExts.map { ".$it" })
            }
            _fileExts.clear()
            _fileExts.addAll(newFileExts)
        }

    private var _fileExts = mutableListOf<String>()

}


enum class FileType { FILE, DIRECTORY }

class FileWidget(
    fileType: FileType,
    verify: ((Path) -> Unit)? = null
) : FilenameWidget() {

    private val browse = JButton(l10n("ui.form.browse"), FOLDER_ICON)

    init {
        browse.addActionListener {
            val fc = JFileChooser()
            fc.fileSelectionMode = when (fileType) {
                FileType.FILE -> JFileChooser.FILES_ONLY
                FileType.DIRECTORY -> JFileChooser.DIRECTORIES_ONLY
            }
            fc.selectedFile = File(text.ensureDoesntEndWith(fileExts.map { ".$it" }))

            if (fileExts.isNotEmpty()) {
                fc.isAcceptAllFileFilterUsed = false
                for (fileExt in fileExts) {
                    val filter = FileNameExtensionFilter("*.$fileExt", fileExt)
                    fc.addChoosableFileFilter(filter)
                    if (text.endsWith(".$fileExt"))
                        fc.fileFilter = filter
                }
            }

            if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                text = fc.selectedFile.absolutePath
                if (fileExts.isNotEmpty()) {
                    val selectedFileExts = (fc.fileFilter as FileNameExtensionFilter).extensions
                    text = text.ensureEndsWith(selectedFileExts.map { ".$it" })
                }
            }
        }
    }

    override val components = listOf<JComponent>(tf, browse)
    override val constraints = listOf("split, width 40%", "")

    override val verify = {
        val fileStr = text.trim()
        if (fileStr.isEmpty())
            throw Form.VerifyResult(Severity.ERROR, l10n("general.blank"))
        val file = Path.of(fileStr)
        if (fileType == FileType.FILE && Files.isDirectory(file))
            throw Form.VerifyResult(Severity.ERROR, l10n("ui.form.pathIsFolder"))
        if (fileType == FileType.DIRECTORY && Files.isRegularFile(file))
            throw Form.VerifyResult(Severity.ERROR, l10n("ui.form.pathIsFile"))
        if (verify != null)
            verify(file)
    }

    var file: Path
        get() = Path.of(text.trim())
        set(value) {
            text = value.toString()
        }

}


class SpinnerWidget(
    model: SpinnerModel,
    verify: ((Any) -> Unit)? = null
) : Form.Widget() {

    private val spinner = JSpinner(model).apply {
        addChangeListener { notifyChangeListeners() }
        setMinWidth100()
    }

    override val components = listOf(spinner)
    override val constraints = listOf("")
    override val verify = verify?.let { { it(value) } }

    var value: Any by spinner::value

}


class CheckBoxWidget(
    verify: ((Boolean) -> Unit)? = null
) : Form.Widget() {

    private val cb = JCheckBox().apply {
        addActionListener { notifyChangeListeners() }
    }

    override val components = listOf(cb)
    override val constraints = listOf("")
    override val verify = verify?.let { { it(isSelected) } }

    var isSelected: Boolean
        get() = cb.isSelected
        set(value) {
            cb.isSelected = value
        }

}


open class ComboBoxWidget<E>(
    items: List<E>,
    toString: (E) -> String = { it.toString() },
    isEditable: Boolean = false,
    private val scrollbar: Boolean = true,
    decorateRenderer: ((ListCellRenderer<E>) -> ListCellRenderer<E>)? = null,
    verify: ((E) -> Unit)? = null
) : Form.Widget() {

    protected val cb = JComboBox<E>().apply {
        addActionListener { notifyChangeListeners() }
        this.isEditable = isEditable
        setMinWidth100()
        renderer = CustomToStringListCellRenderer(toString)
        keySelectionManager = CustomToStringKeySelectionManager(toString)
    }

    override val components = listOf(cb)
    override val constraints = listOf("wmax 40%")
    override val verify = verify?.let { { it(selectedItem) } }

    var items: List<E> = emptyList()
        set(value) {
            field = value
            cb.model = DefaultComboBoxModel(Vector(value))
            if (!scrollbar)
                cb.maximumRowCount = value.size
        }

    open var selectedItem: E
        @Suppress("UNCHECKED_CAST")
        get() = cb.selectedItem as E
        set(value) {
            cb.selectedItem = value
        }

    init {
        this.items = items
    }

}


class InconsistentComboBoxWidget(
    items: List<String>,
    inconsistencyWarning: String? = null,
    verify: ((String) -> Unit)? = null
) : ComboBoxWidget<String>(items, verify = verify) {

    init {
        cb.addActionListener {
            cb.isEditable = false
        }
    }

    override val verify: () -> Unit = {
        if (selectedItem !in this.items && inconsistencyWarning != null)
            throw Form.VerifyResult(Severity.WARN, inconsistencyWarning)
        super.verify?.invoke()
    }

    override var selectedItem: String
        get() = super.selectedItem
        set(value) {
            cb.isEditable = value !in items
            super.selectedItem = value
        }

}


class ComboBoxListWidget<E>(
    items: List<E>,
    private val toString: (E) -> String = { it.toString() },
    verify: ((List<E>) -> Unit)? = null
) : Form.Widget() {

    private val panel = JPanel(MigLayout("insets 0"))
    private val addBtn = JButton(ADD_ICON)
    private val delBtn = JButton(REMOVE_ICON).apply { isEnabled = false }
    private val cbs = mutableListOf<JComboBox<E>>()

    init {
        addBtn.addActionListener { selectedItems = selectedItems + selectedItems.last() }
        delBtn.addActionListener { selectedItems = selectedItems.dropLast(1) }
    }

    override val components = listOf<JComponent>(panel, addBtn, delBtn)

    // Note: We have to manually specify "aligny top" for the ComboBoxList for some reason. If we don't and a
    // multiline verification message occurs, the ComboBoxList does for some reason align itself in the middle.
    override val constraints = listOf("split, aligny top", "", "")
    override val verify = verify?.let { { it(selectedItems) } }

    var items: List<E> = items
        set(value) {
            field = value
            for (cb in cbs)
                cb.model = DefaultComboBoxModel(Vector(value))
        }

    var selectedItems: List<E>
        @Suppress("UNCHECKED_CAST")
        get() = cbs.map { it.selectedItem as E }
        set(sel) {
            while (cbs.size < sel.size) {
                val comboBox = JComboBox(DefaultComboBoxModel(Vector(items))).apply {
                    addActionListener { notifyChangeListeners() }
                    renderer = CustomToStringListCellRenderer(toString)
                    keySelectionManager = CustomToStringKeySelectionManager(toString)
                }
                cbs.add(comboBox)
                panel.add(comboBox)
            }
            while (cbs.size > sel.size)
                panel.remove(cbs.removeLast())
            for ((comboBox, item) in cbs.zip(sel))
                comboBox.selectedItem = item
            panel.revalidate()
            delBtn.isEnabled = cbs.size != 1
            notifyChangeListeners()
        }

}


class ColorWellWidget(
    allowAlpha: Boolean = true,
    verify: ((Color) -> Unit)? = null
) : Form.Widget() {

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

            val chooser = JColorChooser(selectedColor)
            // Disable the horrible preview panel.
            chooser.previewPanel = JPanel()
            // Disable the ability to choose transparent colors if that is desired.
            for (chooserPanel in chooser.chooserPanels)
                chooserPanel.isColorTransparencySelectionEnabled = allowAlpha
            // Show the color chooser and wait for the user to close it.
            JColorChooser.createDialog(
                btn, l10n("ui.form.colorChooserTitle"), true, chooser, { newColor = chooser.color }, null
            ).isVisible = true

            newColor?.let {
                selectedColor = it
                notifyChangeListeners()
            }
        }
    }

    override val components = listOf<JComponent>(btn)
    override val constraints = listOf("")
    override val verify = verify?.let { { it(selectedColor) } }

    var selectedColor: Color = Color.BLACK
        set(value) {
            field = value
            btn.background = value
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
    verify: ((String) -> Unit)? = null
) : Form.Widget() {

    private val familyComboBox = JComboBox<FontFamily>().apply {
        maximumRowCount = 20
        keySelectionManager = CustomToStringKeySelectionManager<FontFamily> { it.familyName }
    }

    private val fontComboBox = JComboBox<Any>(emptyArray()).apply {
        maximumRowCount = 20
        fun toString(value: Any) = if (value is Font) value.getFontName(Locale.US) else value as String
        renderer = FontSampleListCellRenderer<Any>(::toString) { if (it is Font) it else null }
        keySelectionManager = CustomToStringKeySelectionManager(::toString)
    }

    override val components = listOf(familyComboBox, fontComboBox)
    override val constraints = listOf("width 40%!", "newline, width 40%!")

    override val verify = {
        val fontName = selectedFontName
        if (projectFamilies.getFamily(fontName) == null &&
            BUNDLED_FAMILIES.getFamily(fontName) == null &&
            SYSTEM_FAMILIES.getFamily(fontName) == null
        ) {
            val replFontName = getSystemFont(fontName).getFontName(Locale.US)
            throw Form.VerifyResult(Severity.WARN, l10n("ui.form.fontUnavailable", fontName, replFontName))
        }
        if (verify != null)
            verify(fontName)
    }

    var projectFamilies: FontFamilies = FontFamilies(emptyList())
        set(value) {
            field = value
            populateFamilyComboBox()
        }

    var selectedFontName: String
        get() {
            val selectedFont = fontComboBox.selectedItem
            return if (selectedFont is Font) selectedFont.getFontName(Locale.US) else selectedFont as String? ?: ""
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
                notifyChangeListeners()
            }
        }
        fontComboBox.addActionListener { notifyChangeListeners() }

        populateFamilyComboBox()
    }

    private fun populateFamilyComboBox() {
        // Note: We temporarily disable the family change listener because otherwise,
        // it would trigger multiple times and, even worse, with intermediate states.
        disableFamilyListener = true
        try {
            val selected = selectedFontName
            val families = projectFamilies.list + BUNDLED_FAMILIES.list + SYSTEM_FAMILIES.list
            familyComboBox.model = DefaultComboBoxModel(families.toTypedArray())
            selectedFontName = selected
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

private inline fun JTextField.addChangeListener(crossinline listener: () -> Unit) {
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener()
        override fun removeUpdate(e: DocumentEvent) = listener()
        override fun changedUpdate(e: DocumentEvent) = listener()
    })
}
