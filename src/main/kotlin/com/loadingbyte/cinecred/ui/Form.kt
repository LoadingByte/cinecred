package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.project.FontSpec
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


open class Form : JPanel(MigLayout("hidemode 3", "[align right][grow]")) {

    enum class FileType { FILE, DIRECTORY }
    class VerifyResult(val severity: Severity, msg: String) : Exception(msg)

    private class FormRow(val isVisibleFunc: (() -> Boolean)?) {
        val components = mutableListOf<Component>()
        var doVerify: (() -> Unit)? = null

        // We keep track of the form rows which are visible and have a verification error (not a warning).
        // One can use this information to determine whether the form is error-free.
        var isVisible = true
            set(value) {
                field = value
                for (comp in components)
                    comp.isVisible = value
            }
        var isErroneous = false
    }

    private val formRows = mutableListOf<FormRow>()
    private val componentToFormRow = mutableMapOf<Component, FormRow>()
    private var submitButton: JButton? = null
    private val changeListeners = mutableListOf<(Component) -> Unit>()

    private fun JTextField.addChangeListener(listener: () -> Unit) {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = listener()
            override fun removeUpdate(e: DocumentEvent) = listener()
            override fun changedUpdate(e: DocumentEvent) = listener()
        })
    }

    fun addTextField(
        label: String,
        grow: Boolean = true,
        isVisible: (() -> Boolean)? = null,
        verify: ((String) -> Unit)? = null
    ): JTextField {
        val field = JTextField().apply { setMinWidth() }
        val constraints = listOf(if (grow) "width 50%" else "")
        addFormRow(label, listOf(field), constraints, isVisible, verify?.let { { it(field.text) } })
        field.addChangeListener { onChange(field) }
        return field
    }

    fun addFilenameField(
        label: String,
        isVisible: (() -> Boolean)? = null,
        verify: ((String) -> Unit)? = null
    ): TextFieldWithFileExts {
        val field = TextFieldWithFileExts()
        addFormRow(label, listOf(field), listOf("width 50%"), isVisible, verify?.let { { it(field.text) } })
        field.addChangeListener { onChange(field) }
        return field
    }

    fun addFileField(
        label: String,
        fileType: FileType,
        isVisible: (() -> Boolean)? = null,
        verify: ((Path) -> Unit)? = null
    ): TextFieldWithFileExts {
        val field = TextFieldWithFileExts()

        val browse = JButton("Browse")
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
                throw VerifyResult(Severity.ERROR, "Path is empty.")
            val file = Path.of(fileStr)
            if (fileType == FileType.FILE && Files.isDirectory(file))
                throw VerifyResult(Severity.ERROR, "Path points to a directory.")
            if (fileType == FileType.DIRECTORY && Files.isRegularFile(file))
                throw VerifyResult(Severity.ERROR, "Path points to a non-directory file.")
            if (verify != null)
                verify(file)
            if (fileType == FileType.FILE && Files.exists(file))
                throw VerifyResult(Severity.WARN, "File already exists and will be overwritten.")
        }

        addFormRow(label, listOf(field, browse), listOf("split, width 50%", ""), isVisible, extendedVerify)
        field.addChangeListener { onChange(field) }

        return field
    }

    fun addSpinner(
        label: String,
        model: SpinnerModel,
        isVisible: (() -> Boolean)? = null,
        verify: ((Any) -> Unit)? = null
    ): JSpinner {
        val field = JSpinner(model).apply { setMinWidth() }
        addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.value) } })
        field.addChangeListener { onChange(field) }
        return field
    }

    fun addCheckBox(
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
    private class CustomToStringListCellRenderer<E>(val toString: (E) -> String) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
            text = toString(value as E)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <E> addComboBox(
        label: String,
        items: Array<E>,
        toString: (E) -> String = { it.toString() },
        isVisible: (() -> Boolean)? = null,
        verify: ((E?) -> Unit)? = null
    ): JComboBox<E> {
        val field = JComboBox(DefaultComboBoxModel(items)).apply {
            setMinWidth()
            renderer = CustomToStringListCellRenderer(toString)
        }
        addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.selectedItem as E?) } })
        field.addActionListener { onChange(field) }
        return field
    }

    fun <E> addComboBoxList(
        label: String,
        items: Array<E>,
        toString: (E) -> String = { it.toString() },
        isVisible: (() -> Boolean)? = null,
        verify: ((List<E?>) -> Unit)? = null
    ): ComboBoxList<E> {
        val addButton = JButton("+")
        val removeButton = JButton("\u2212").apply { isEnabled = false }
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

    fun addColorChooserButton(
        label: String,
        isVisible: (() -> Boolean)? = null,
        verify: ((Color?) -> Unit)? = null
    ): ColorChooserButton {
        val field = ColorChooserButton(changeListener = ::onChange).apply { setMinWidth() }
        addFormRow(label, listOf(field), listOf(""), isVisible, verify?.let { { it(field.selectedColor) } })
        return field
    }

    fun addFontSpecChooser(
        label: String,
        isVisible: (() -> Boolean)? = null,
        verify: ((FontSpec?) -> Unit)? = null
    ): FontSpecChooserComps {
        val fontFamilies = FontSpecChooserComps.FONT_NAMES_BY_FAMILY.keys.toTypedArray()

        val familyComboBox = JComboBox(DefaultComboBoxModel(fontFamilies))
        val nameComboBox = JComboBox<String>(emptyArray())
        val colorChooserButton = ColorChooserButton(changeListener = ::onChange)
        val heightPxSpinner = JSpinner(SpinnerNumberModel(1, 1, null, 1))
            .apply { minimumSize = Dimension(50, minimumSize.height) }
        val extraLineSpacingPxSpinner = JSpinner(SpinnerNumberModel(0f, 0f, null, 1f))
            .apply { minimumSize = Dimension(50, minimumSize.height) }

        val field = FontSpecChooserComps(
            familyComboBox, nameComboBox, colorChooserButton, heightPxSpinner, extraLineSpacingPxSpinner
        )

        val extendedVerify = {
            val fontSpec = field.selectedFontSpec
            if (fontSpec.name !in FontSpecChooserComps.FONT_FAMILY_BY_NAME) {
                val substituteFontName = Font(fontSpec.name, 0, 1).getFontName(Locale.US)
                val msg = "The font \"${fontSpec.name}\" is not available and will be substituted " +
                        "by \"$substituteFontName\"."
                throw VerifyResult(Severity.WARN, msg)
            }
            if (verify != null)
                verify(fontSpec)
        }

        addFormRow(
            label,
            listOf(
                familyComboBox, nameComboBox, colorChooserButton, JLabel("Height (Px)"), heightPxSpinner,
                JLabel("+ Line Spacing (Px)"), extraLineSpacingPxSpinner
            ),
            listOf("", "newline, split", "", "newline, split", "", "", ""),
            isVisible, extendedVerify
        )

        familyComboBox.addActionListener {
            val family = familyComboBox.selectedItem as String?
            val fontNames = family?.let { FontSpecChooserComps.FONT_NAMES_BY_FAMILY[it] } ?: emptyList()
            nameComboBox.model = DefaultComboBoxModel(fontNames.toTypedArray())
            nameComboBox.isEditable = false
            onChange(familyComboBox)
        }
        nameComboBox.addActionListener { onChange(nameComboBox) }
        heightPxSpinner.addChangeListener { onChange(heightPxSpinner) }
        extraLineSpacingPxSpinner.addChangeListener { onChange(extraLineSpacingPxSpinner) }

        return field
    }

    private fun addFormRow(
        label: String,
        fields: List<Component>,
        constraints: List<String>,
        isVisible: (() -> Boolean)? = null,
        verify: (() -> Unit)? = null
    ) {
        if (fields.size != constraints.size)
            throw IllegalArgumentException()

        val formRow = FormRow(isVisible)

        val jLabel = JLabel(label)
        formRow.components.add(jLabel)
        add(jLabel, "newline")

        formRow.components.addAll(fields)
        val endlineGroupId = "g" + System.identityHashCode(jLabel)
        val endlineFieldIds = mutableListOf<String>()
        for ((fieldIdx, field) in fields.withIndex()) {
            val fieldConstraints = mutableListOf(constraints[fieldIdx])
            // If the field ends a line, assign it a unique ID. For this, we just use its location in memory. Also add
            // it to the "endlineGroup". These IDs will be used later when positioning the verification components.
            if (fieldIdx == fields.size - 1 || "newline" in constraints.getOrElse(fieldIdx + 1) { "" }) {
                val id = "f" + System.identityHashCode(field).toString()
                fieldConstraints.add("id $endlineGroupId.$id")
                endlineFieldIds.add(id)
            }
            // If this field starts a new line, add a skip constraint to skip the label column.
            if ("newline" in fieldConstraints[0])
                fieldConstraints.add("skip 1")
            add(field, fieldConstraints.joinToString())
        }

        if (verify != null) {
            val verifyIconLabel = JLabel()
            val verifyMsgArea = newLabelTextArea("")
            formRow.components.addAll(arrayOf(verifyIconLabel, verifyMsgArea))

            // Position the verification components using coordinates relative to the fields that are at the line ends.
            val iconLabelId = "c${System.identityHashCode(verifyIconLabel)}"
            val startYExpr = "${endlineFieldIds[0]}.y"
            add(verifyIconLabel, "id $iconLabelId, pos ($endlineGroupId.x2 + 3*rel) $startYExpr")
            add(verifyMsgArea, "pos ($iconLabelId.x2 + rel) $startYExpr visual.x2 null")

            formRow.doVerify = {
                formRow.isErroneous = false
                try {
                    verify()
                    verifyIconLabel.icon = null
                    verifyMsgArea.text = null
                } catch (e: VerifyResult) {
                    verifyIconLabel.icon = SEVERITY_ICON[e.severity]
                    verifyMsgArea.text = e.message
                    if (e.severity == Severity.ERROR)
                        formRow.isErroneous = true
                }
            }
        }

        formRows.add(formRow)
        for (comp in formRow.components)
            componentToFormRow[comp] = formRow

        // Verify the initial field contents.
        formRow.doVerify?.invoke()
        // If the form row should initially be invisible, hide it.
        if (formRow.isVisibleFunc?.invoke() == false)
            formRow.isVisible = false
    }

    fun addSubmitButton(label: String) = JButton(label).also { button ->
        submitButton = button
        add(button, "newline, span, align left")
    }

    private fun <C : Component> C.setMinWidth() {
        minimumSize = Dimension(120, minimumSize.height)
    }

    fun addChangeListener(changeListener: (Component) -> Unit) {
        changeListeners.add(changeListener)
    }

    fun clearChangeListeners() {
        changeListeners.clear()
    }

    fun onChange(component: Component) {
        componentToFormRow[component]!!.doVerify?.invoke()

        for (formRow in formRows)
            formRow.isVisible = formRow.isVisibleFunc?.invoke() ?: true

        // The submit button (if there is one) should only be clickable if there are no verification errors in the form.
        submitButton?.isEnabled = isErrorFree

        // Notify all change listeners.
        for (changeListener in changeListeners)
            changeListener(component)
    }

    val isErrorFree: Boolean
        get() = formRows.all { !it.isVisible || !it.isErroneous }


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
            toolTipText = "Click to choose color"

            // Default color
            selectedColor = Color.WHITE

            addActionListener {
                val newColor = JColorChooser.showDialog(null, "Choose Color", selectedColor)
                if (newColor != null) {
                    selectedColor = newColor
                    changeListener(this)
                }
            }
        }

    }


    class FontSpecChooserComps(
        private val familyComboBox: JComboBox<String>,
        private val nameComboBox: JComboBox<String>,
        private val colorChooserButton: ColorChooserButton,
        private val heightPxSpinner: JSpinner,
        private val extraLineSpacingPxSpinner: JSpinner
    ) {

        var selectedFontSpec: FontSpec
            get() = FontSpec(
                nameComboBox.selectedItem as String? ?: "",
                heightPxSpinner.value as Int,
                extraLineSpacingPxSpinner.value as Float,
                colorChooserButton.selectedColor
            )
            set(value) {
                val family = FONT_FAMILY_BY_NAME[value.name]
                if (family != null) {
                    familyComboBox.selectedItem = family
                    nameComboBox.selectedItem = value.name
                } else {
                    familyComboBox.selectedItem = null
                    nameComboBox.isEditable = true
                    nameComboBox.selectedItem = value.name
                }
                heightPxSpinner.value = value.heightPx
                extraLineSpacingPxSpinner.value = value.extraLineSpacingPx
                colorChooserButton.selectedColor = value.color
            }


        companion object {

            val FONT_NAMES_BY_FAMILY: Map<String, Set<String>>
            val FONT_FAMILY_BY_NAME: Map<String, String>

            init {
                val familyEndingsForRemoval = listOf(
                    "Bd", "BdIta", "Bk", "Black", "Blk", "Bold", "BoldItalic", "Extra", "ExtraBold", "ExtraLight",
                    "Hairline", "Italic", "Light", "Lt", "Md", "Med", "Medium", "Normal", "Oblique", "Regular", "Semi",
                    "SemiBold", "Th", "Thin", "Ultra", "UltraBold", "UltraLight"
                ).flatMap { listOf(" $it", "-$it", ".$it") }

                fun cleanFamily(name: String): String {
                    var family = name.trim()
                    while (true) {
                        for (ending in familyEndingsForRemoval)
                            if (family.endsWith(ending, ignoreCase = true)) {
                                family = family.dropLast(ending.length)
                                break
                            }
                        return family
                    }
                }

                val fontNamesByFamily = TreeMap<String, TreeSet<String>>()
                val fontFamilyByName = mutableMapOf<String, String>()
                for (font in GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts) {
                    val family = cleanFamily(font.family)
                    fontNamesByFamily.getOrPut(family) { TreeSet() }.add(font.getFontName(Locale.US))
                    fontFamilyByName[font.getFontName(Locale.US)] = family
                }
                FONT_NAMES_BY_FAMILY = fontNamesByFamily
                FONT_FAMILY_BY_NAME = fontFamilyByName
            }

        }

    }

}
