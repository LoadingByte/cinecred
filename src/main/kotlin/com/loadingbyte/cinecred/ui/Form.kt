package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.getSystemFont
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
        val components = mutableListOf<JComponent>()
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
        val constraints = listOf(if (grow) "width 40%" else "")
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
        addFormRow(label, listOf(field), listOf("width 40%"), isVisible, verify?.let { { it(field.text) } })
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
                throw VerifyResult(Severity.ERROR, l10n("general.blank"))
            val file = Path.of(fileStr)
            if (fileType == FileType.FILE && Files.isDirectory(file))
                throw VerifyResult(Severity.ERROR, l10n("ui.form.pathIsFolder"))
            if (fileType == FileType.DIRECTORY && Files.isRegularFile(file))
                throw VerifyResult(Severity.ERROR, l10n("ui.form.pathIsFile"))
            if (verify != null)
                verify(file)
        }

        addFormRow(label, listOf(field, browse), listOf("split, width 40%", ""), isVisible, extendedVerify)
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
            keySelectionManager = CustomToStringKeySelectionManager(toString)
        }
        addFormRow(
            label, listOf(field), listOf("wmax 40%"), isVisible, verify?.let { { it(field.selectedItem as E?) } }
        )
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
    ): FontSpecChooserComponents {
        val field = FontSpecChooserComponents(changeListener = ::onChange)

        val extendedVerify = {
            val fontSpec = field.selectedFontSpec
            if (field.projectFamilies.getFamily(fontSpec.name) == null &&
                BUNDLED_FAMILIES.getFamily(fontSpec.name) == null &&
                SYSTEM_FAMILIES.getFamily(fontSpec.name) == null
            ) {
                val substituteFontName = getSystemFont(fontSpec.name).getFontName(Locale.US)
                throw VerifyResult(Severity.WARN, l10n("ui.form.fontUnavailable", fontSpec.name, substituteFontName))
            }
            if (verify != null)
                verify(fontSpec)
        }

        addFormRow(
            label,
            listOf(
                field.familyComboBox, field.fontComboBox,
                JLabel(l10n("ui.form.fontHeightPx")), field.heightPxSpinner,
                JLabel("    " + l10n("ui.form.fontColor")), field.colorChooserButton
            ),
            listOf("width 40%!", "newline, width 40%!", "newline, split", "", "", ""),
            isVisible, extendedVerify
        )

        return field
    }

    private fun addFormRow(
        label: String,
        fields: List<JComponent>,
        constraints: List<String>,
        isVisible: (() -> Boolean)? = null,
        verify: (() -> Unit)? = null
    ) {
        require(fields.size == constraints.size)

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
            if (fieldIdx == fields.lastIndex || "newline" in constraints.getOrElse(fieldIdx + 1) { "" }) {
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
            val verifyMsgArea = newLabelTextArea()
            formRow.components.addAll(arrayOf(verifyIconLabel, verifyMsgArea))

            // Position the verification components using coordinates relative to the fields that are at the line ends.
            val iconLabelId = "c${System.identityHashCode(verifyIconLabel)}"
            val startYExpr = "${endlineFieldIds[0]}.y"
            add(verifyIconLabel, "id $iconLabelId, pos ($endlineGroupId.x2 + 3*rel) ($startYExpr + 3)")
            add(verifyMsgArea, "pos $iconLabelId.x2 $startYExpr visual.x2 null")

            formRow.doVerify = {
                formRow.isErroneous = false
                // Remove FlatLaf outlines.
                for (comp in formRow.components)
                    comp.putClientProperty("JComponent.outline", null)
                try {
                    verify()
                    verifyIconLabel.icon = null
                    verifyMsgArea.text = null
                } catch (e: VerifyResult) {
                    verifyIconLabel.icon = SEVERITY_ICON[e.severity]
                    verifyMsgArea.text = e.message
                    if (e.severity == Severity.WARN || e.severity == Severity.ERROR) {
                        // Add FlatLaf outlines.
                        val outline = if (e.severity == Severity.WARN) "warning" else "error"
                        for (comp in formRow.components)
                            comp.putClientProperty("JComponent.outline", outline)
                    }
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

    fun addSeparator() {
        add(JSeparator(), "newline, span, growx")
    }

    fun addSubmitButton(label: String) = JButton(label).also { button ->
        submitButton = button
        add(button, "newline, skip 1, span, align left")
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
        componentToFormRow[component]?.doVerify?.invoke()

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


    class FontSpecChooserComponents(changeListener: (Component) -> Unit) {

        var selectedFontSpec: FontSpec
            get() {
                val selectedFont = fontComboBox.selectedItem
                return FontSpec(
                    if (selectedFont is Font) selectedFont.getFontName(Locale.US) else selectedFont as String? ?: "",
                    heightPxSpinner.value as Int, colorChooserButton.selectedColor
                )
            }
            set(value) {
                val family = projectFamilies.getFamily(value.name)
                    ?: BUNDLED_FAMILIES.getFamily(value.name)
                    ?: SYSTEM_FAMILIES.getFamily(value.name)
                if (family != null) {
                    familyComboBox.selectedItem = family
                    fontComboBox.isEditable = false
                    fontComboBox.selectedItem = family.getFont(value.name)
                } else {
                    familyComboBox.selectedItem = null
                    fontComboBox.isEditable = true
                    fontComboBox.selectedItem = value.name
                }
                heightPxSpinner.value = value.heightPx
                colorChooserButton.selectedColor = value.color
            }

        var projectFamilies: FontFamilies = FontFamilies(emptyList())
            set(value) {
                field = value
                populateFamilyComboBox()
            }

        val familyComboBox = JComboBox<FontFamily>().apply {
            maximumRowCount = 20
            // Equip the family combo box with a custom renderer that shows category headers.
            val baseRenderer = FontSampleListCellRenderer(FontFamily::familyName, FontFamily::canonicalFont)
            renderer = LabeledListCellRenderer(baseRenderer, groupSpacing = 8) { index: Int ->
                mutableListOf<String>().apply {
                    val projectHeaderIdx = 0
                    val bundledHeaderIdx = projectHeaderIdx + projectFamilies.list.size
                    val systemHeaderIdx = bundledHeaderIdx + BUNDLED_FAMILIES.list.size
                    if (index == projectHeaderIdx) {
                        add("\u2605 " + l10n("ui.form.fontProjectFamilies") + " \u2605")
                        if (projectHeaderIdx == bundledHeaderIdx) add(l10n("ui.form.fontNoProjectFamilies"))
                    }
                    if (index == bundledHeaderIdx) add("\u2605 " + l10n("ui.form.fontBundledFamilies") + " \u2605")
                    if (index == systemHeaderIdx) add(l10n("ui.form.fontSystemFamilies"))
                }
            }
            keySelectionManager = CustomToStringKeySelectionManager<FontFamily> { it.familyName }
        }

        val fontComboBox = JComboBox<Any>(emptyArray()).apply {
            maximumRowCount = 20
            fun toString(value: Any) = if (value is Font) value.getFontName(Locale.US) else value as String
            renderer = FontSampleListCellRenderer<Any>(::toString) { if (it is Font) it else null }
            keySelectionManager = CustomToStringKeySelectionManager(::toString)
        }

        val heightPxSpinner = JSpinner(SpinnerNumberModel(1, 1, null, 1))
            .apply { minimumSize = Dimension(50, minimumSize.height) }

        val colorChooserButton = ColorChooserButton(changeListener)

        private var disableFamilyListener = false

        init {
            familyComboBox.addActionListener {
                if (!disableFamilyListener) {
                    val selectedFamily = familyComboBox.selectedItem as FontFamily?
                    fontComboBox.model = DefaultComboBoxModel(selectedFamily?.fonts?.toTypedArray() ?: emptyArray())
                    if (selectedFamily != null)
                        fontComboBox.selectedItem = selectedFamily.canonicalFont
                    fontComboBox.isEditable = false
                    changeListener(familyComboBox)
                }
            }
            fontComboBox.addActionListener { changeListener(fontComboBox) }
            heightPxSpinner.addChangeListener { changeListener(heightPxSpinner) }

            populateFamilyComboBox()
        }

        private fun populateFamilyComboBox() {
            // Note: We temporarily disable the family change listener because otherwise,
            // it would trigger multiple times and, even worse, with intermediate states.
            disableFamilyListener = true
            try {
                val selected = selectedFontSpec
                val families = projectFamilies.list + BUNDLED_FAMILIES.list + SYSTEM_FAMILIES.list
                familyComboBox.model = DefaultComboBoxModel(families.toTypedArray())
                selectedFontSpec = selected
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
            private val panel = JPanel(MigLayout("insets 0", "[]30lp:::push[]")).apply {
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

}
