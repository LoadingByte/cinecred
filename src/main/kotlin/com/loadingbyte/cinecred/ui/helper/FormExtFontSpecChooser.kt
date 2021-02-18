package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.getSystemFont
import com.loadingbyte.cinecred.project.FontSpec
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.util.*
import javax.swing.*


fun Form.addFontSpecChooser(
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
            throw Form.VerifyResult(Severity.WARN, l10n("ui.form.fontUnavailable", fontSpec.name, substituteFontName))
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
