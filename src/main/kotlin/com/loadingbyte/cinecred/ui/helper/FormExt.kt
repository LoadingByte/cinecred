package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
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


enum class FileType { FILE, DIRECTORY }


fun Form.addTextField(
    label: String,
    grow: Boolean = true,
    isVisible: (() -> Boolean)? = null,
    verify: ((String) -> Unit)? = null
): JTextField {
    val field = JTextField().apply { setMinWidth120() }
    val constraints = listOf(if (grow) "width 40%" else "")
    addFormRow(label, listOf(field), constraints, isVisible, verify?.let { { it(field.text) } })
    field.addChangeListener { onChange(field) }
    return field
}


fun Form.addFilenameField(
    label: String,
    isVisible: (() -> Boolean)? = null,
    verify: ((String) -> Unit)? = null
): TextFieldWithFileExts {
    val field = TextFieldWithFileExts()
    addFormRow(label, listOf(field), listOf("width 40%"), isVisible, verify?.let { { it(field.text) } })
    field.addChangeListener { onChange(field) }
    return field
}


fun Form.addFileField(
    label: String,
    fileType: FileType,
    isVisible: (() -> Boolean)? = null,
    verify: ((Path) -> Unit)? = null
): TextFieldWithFileExts {
    val field = TextFieldWithFileExts()

    val browse = JButton(l10n("ui.form.browse"), FOLDER_ICON)
    browse.addActionListener {
        val fc = JFileChooser()
        fc.fileSelectionMode = when (fileType) {
            FileType.FILE -> JFileChooser.FILES_ONLY
            FileType.DIRECTORY -> JFileChooser.DIRECTORIES_ONLY
        }
        fc.selectedFile = File(field.text)

        if (field.fileExts.isNotEmpty()) {
            fc.isAcceptAllFileFilterUsed = false
            for (fileExt in field.fileExts)
                fc.addChoosableFileFilter(FileNameExtensionFilter("*.$fileExt", fileExt))
        }

        if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            field.text = fc.selectedFile.absolutePath
            if (field.fileExts.isNotEmpty()) {
                val selectedFileExts = (fc.fileFilter as FileNameExtensionFilter).extensions
                field.text = field.text.ensureEndsWith(selectedFileExts.map { ".$it" })
            }
        }
    }

    val extendedVerify = {
        val fileStr = field.text.trim()
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

    addFormRow(label, listOf(field, browse), listOf("split, width 40%", ""), isVisible, extendedVerify)
    field.addChangeListener { onChange(field) }

    return field
}


fun Form.addSpinner(
    label: String,
    model: SpinnerModel,
    isVisible: (() -> Boolean)? = null,
    verify: ((Any) -> Unit)? = null
): JSpinner {
    val field = JSpinner(model).apply { setMinWidth120() }
    addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.value) } })
    field.addChangeListener { onChange(field) }
    return field
}


fun Form.addCheckBox(
    label: String,
    isVisible: (() -> Boolean)? = null,
    verify: ((Boolean) -> Unit)? = null
): JCheckBox {
    val field = JCheckBox()
    addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.isSelected) } })
    field.addActionListener { onChange(field) }
    return field
}


@Suppress("UNCHECKED_CAST")
fun <E> Form.addComboBox(
    label: String,
    items: Array<E>,
    toString: (E) -> String = { it.toString() },
    isVisible: (() -> Boolean)? = null,
    verify: ((E?) -> Unit)? = null
): JComboBox<E> {
    val field = JComboBox(DefaultComboBoxModel(items)).apply {
        setMinWidth120()
        renderer = CustomToStringListCellRenderer(toString)
        keySelectionManager = CustomToStringKeySelectionManager(toString)
    }
    addFormRow(
        label, listOf(field), listOf("wmax 40%"), isVisible, verify?.let { { it(field.selectedItem as E?) } }
    )
    field.addActionListener { onChange(field) }
    return field
}


fun <E> Form.addComboBoxList(
    label: String,
    items: Array<E>,
    toString: (E) -> String = { it.toString() },
    isVisible: (() -> Boolean)? = null,
    verify: ((List<E?>) -> Unit)? = null
): ComboBoxList<E> {
    val addButton = JButton(ADD_ICON)
    val removeButton = JButton(REMOVE_ICON).apply { isEnabled = false }
    val field = ComboBoxList(items, toString, changeListener = { field ->
        removeButton.isEnabled = field.selectedItems.size != 1
        onChange(field)
    })
    addButton.addActionListener { field.selectedItems += items[0] }
    removeButton.addActionListener { field.selectedItems = field.selectedItems.dropLast(1) }
    // Note: We have to manually specify "aligny top" for the ComboBoxList for some reason. If we don't and a
    // multiline verification message occurs, the ComboBoxList does for some reason align itself in the middle.
    addFormRow(
        label, listOf(field, addButton, removeButton), listOf("split, aligny top", "", ""), isVisible,
        verify?.let { { it(field.selectedItems) } }
    )
    return field
}


fun Form.addColorChooserButton(
    label: String,
    isVisible: (() -> Boolean)? = null,
    verify: ((Color?) -> Unit)? = null
): ColorChooserButton {
    val field = ColorChooserButton(changeListener = ::onChange).apply { setMinWidth120() }
    addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.selectedColor) } })
    return field
}


private fun String.ensureEndsWith(suffixes: List<String>, ignoreCase: Boolean = true) =
    if (suffixes.isEmpty() || suffixes.any { endsWith(it, ignoreCase) }) this else this + suffixes[0]

private fun String.ensureDoesntEndWith(suffixes: List<String>, ignoreCase: Boolean = true): String {
    for (suffix in suffixes)
        if (endsWith(suffix, ignoreCase))
            return dropLast(suffix.length)
    return this
}

private fun <C : Component> C.setMinWidth120() {
    minimumSize = Dimension(120, minimumSize.height)
}

private fun JTextField.addChangeListener(listener: () -> Unit) {
    document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = listener()
        override fun removeUpdate(e: DocumentEvent) = listener()
        override fun changedUpdate(e: DocumentEvent) = listener()
    })
}


class TextFieldWithFileExts : JTextField() {

    private var _fileExts = mutableListOf<String>()

    var fileExts: List<String>
        get() = _fileExts
        set(newFileExts) {
            // When the list of admissible file extensions is changed and the field text doesn't end with an
            // admissible file extension anymore, remove the previous file extension (if there was any) and add
            // the default new one.
            if (!newFileExts.any { text.endsWith(".$it") }) {
                text = text.ensureDoesntEndWith(fileExts.map { ".$it" })
                text = text.ensureEndsWith(newFileExts.map { ".$it" })
            }
            _fileExts.clear()
            _fileExts.addAll(newFileExts)
        }

    init {
        // When the user leaves the text field, ensure that it ends with an admissible file extension.
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                text = text.ensureEndsWith(fileExts.map { ".$it" })
            }
        })
    }

}


class ComboBoxList<E>(
    private val items: Array<E>,
    private val toString: (E) -> String,
    private val changeListener: (ComboBoxList<E>) -> Unit
) : JPanel(MigLayout("insets 0")) {

    private val comboBoxes = mutableListOf<JComboBox<E>>()

    var selectedItems: List<E?>
        @Suppress("UNCHECKED_CAST")
        get() = comboBoxes.map { it.selectedItem as E? }
        set(sel) {
            while (comboBoxes.size < sel.size) {
                val comboBox = JComboBox(DefaultComboBoxModel(items)).apply {
                    renderer = CustomToStringListCellRenderer(toString)
                    keySelectionManager = CustomToStringKeySelectionManager(toString)
                    addActionListener { changeListener(this@ComboBoxList) }
                }
                comboBoxes.add(comboBox)
                add(comboBox)
            }
            while (comboBoxes.size > sel.size)
                remove(comboBoxes.removeLast())
            for ((comboBox, item) in comboBoxes.zip(sel))
                comboBox.selectedItem = item
            revalidate()
            changeListener(this)
        }

}


class ColorChooserButton(private val changeListener: (ColorChooserButton) -> Unit) : JButton(" ") {

    var selectedColor: Color
        get() = background
        set(value) {
            background = value
        }

    init {
        toolTipText = l10n("ui.form.colorChooserTooltip")

        // Default color
        selectedColor = Color.WHITE

        addActionListener {
            val newColor = JColorChooser.showDialog(null, l10n("ui.form.colorChooserTitle"), selectedColor)
            if (newColor != null) {
                selectedColor = newColor
                changeListener(this)
            }
        }
    }

}
