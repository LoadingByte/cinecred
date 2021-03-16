package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.LetterStyle
import com.loadingbyte.cinecred.project.Superscript
import com.loadingbyte.cinecred.ui.helper.*
import javax.swing.SpinnerNumberModel


object LetterStyleForm : Form() {

    private val nameWidget = addWidget(
        l10n("ui.styling.letter.name"),
        StyleNameWidget()
    )
    private val fontWidget = addWidget(
        l10n("ui.styling.letter.font"),
        FontChooserWidget()
    )
    private val heightPxWidget = addWidget(
        l10n("ui.styling.letter.heightPx"),
        SpinnerWidget(SpinnerNumberModel(1, 1, null, 1))
    )
    private val trackingWidget = addWidget(
        l10n("ui.styling.letter.tracking"),
        SpinnerWidget(SpinnerNumberModel(0f, null, null, 0.01f))
    )
    private val superscriptWidget = addWidget(
        l10n("ui.styling.letter.superscript"),
        ComboBoxWidget(Superscript.values().asList(), toString = ::l10nEnum)
    )
    private val foregroundWidget = addWidget(
        l10n("ui.styling.letter.foreground"),
        ColorWellWidget()
    )
    private val backgroundWidget = addWidget(
        l10n("ui.styling.letter.background"),
        ColorWellWidget()
    )
    private val underlineWidget = addWidget(
        l10n("ui.styling.letter.underline"),
        CheckBoxWidget()
    )
    private val strikethroughWidget = addWidget(
        l10n("ui.styling.letter.strikethrough"),
        CheckBoxWidget()
    )

    init {
        finishInit()
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        fontWidget.projectFamilies = projectFamilies
    }

    private fun load(style: LetterStyle) {
        nameWidget.text = style.name
        fontWidget.selectedFontName = style.fontName
        heightPxWidget.value = style.heightPx
        trackingWidget.value = style.tracking
        superscriptWidget.selectedItem = style.superscript
        foregroundWidget.selectedColor = style.foreground
        backgroundWidget.selectedColor = style.background
        underlineWidget.isSelected = style.underline
        strikethroughWidget.isSelected = style.strikethrough
    }

    private fun save() = LetterStyle(
        nameWidget.text.trim(),
        fontWidget.selectedFontName,
        heightPxWidget.value as Int,
        trackingWidget.value as Float,
        superscriptWidget.selectedItem,
        foregroundWidget.selectedColor,
        backgroundWidget.selectedColor,
        underlineWidget.isSelected,
        strikethroughWidget.isSelected
    )

    fun open(style: LetterStyle, allStyles: List<LetterStyle>, onChange: (LetterStyle) -> Unit) {
        nameWidget.otherStyleNames = allStyles.filter { it !== style }.map { it.name }

        withoutChangeEvents {
            load(style)
            postChangeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
