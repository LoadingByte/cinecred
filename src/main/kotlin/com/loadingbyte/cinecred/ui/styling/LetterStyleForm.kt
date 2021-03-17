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
    private val smallCapsWidget = addWidget(
        l10n("ui.styling.letter.smallCaps"),
        CheckBoxWidget()
    )
    private val uppercaseWidget = addWidget(
        l10n("ui.styling.letter.uppercase"),
        CheckBoxWidget()
    )
    private val useUppercaseExceptionsWidget = addWidget(
        l10n("ui.styling.letter.useUppercaseExceptions"),
        CheckBoxWidget(),
        isEnabled = { uppercaseWidget.isSelected },
    )
    private val superscriptWidget = addWidget(
        l10n("ui.styling.letter.superscript"),
        ComboBoxWidget(Superscript.values().asList(), toString = ::l10nEnum)
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
        foregroundWidget.selectedColor = style.foreground
        backgroundWidget.selectedColor = style.background
        underlineWidget.isSelected = style.underline
        strikethroughWidget.isSelected = style.strikethrough
        smallCapsWidget.isSelected = style.smallCaps
        uppercaseWidget.isSelected = style.uppercase
        useUppercaseExceptionsWidget.isSelected = style.useUppercaseExceptions
        superscriptWidget.selectedItem = style.superscript
    }

    private fun save() = LetterStyle(
        nameWidget.text.trim(),
        fontWidget.selectedFontName,
        heightPxWidget.value as Int,
        trackingWidget.value as Float,
        foregroundWidget.selectedColor,
        backgroundWidget.selectedColor,
        underlineWidget.isSelected,
        strikethroughWidget.isSelected,
        smallCapsWidget.isSelected,
        uppercaseWidget.isSelected,
        useUppercaseExceptionsWidget.isSelected,
        superscriptWidget.selectedItem
    )

    fun open(style: LetterStyle, allStyles: List<LetterStyle>, onChange: (LetterStyle) -> Unit) {
        nameWidget.otherStyleNames = allStyles.filter { it !== style }.map { it.name }

        withoutChangeListeners {
            load(style)
            postChangeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
