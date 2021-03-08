package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.PageStyle
import com.loadingbyte.cinecred.ui.helper.CheckBoxWidget
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.Form
import com.loadingbyte.cinecred.ui.helper.SpinnerWidget
import javax.swing.SpinnerNumberModel
import kotlin.math.floor


object PageStyleForm : Form() {

    private val nameWidget = addWidget(
        l10n("ui.styling.page.name"),
        StyleNameWidget()
    )
    private val behaviorWidget = addWidget(
        l10n("ui.styling.page.behavior"),
        ComboBoxWidget(PageBehavior.values().asList(), toString = ::l10nEnum)
    )
    private val meltWithPrevWidget = addWidget(
        l10n("ui.styling.page.meltWithPrev"),
        CheckBoxWidget(),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.SCROLL }
    )
    private val meltWithNextWidget = addWidget(
        l10n("ui.styling.page.meltWithNext"),
        CheckBoxWidget(),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.SCROLL }
    )
    private val afterwardSlugFramesWidget = addWidget(
        l10n("ui.styling.page.afterwardSlugFrames"),
        SpinnerWidget(SpinnerNumberModel(0, 0, null, 1)),
        isVisible = { !(behaviorWidget.selectedItem == PageBehavior.SCROLL && meltWithNextWidget.isSelected) }
    )
    private val cardDurationFramesWidget = addWidget(
        l10n("ui.styling.page.cardDurationFrames"),
        SpinnerWidget(SpinnerNumberModel(0, 0, null, 1)),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.CARD }
    )
    private val cardFadeInFramesWidget = addWidget(
        l10n("ui.styling.page.cardInFrames"),
        SpinnerWidget(SpinnerNumberModel(0, 0, null, 1)),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.CARD }
    )
    private val cardFadeOutFramesWidget = addWidget(
        l10n("ui.styling.page.cardOutFrames"),
        SpinnerWidget(SpinnerNumberModel(0, 0, null, 1)),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.CARD }
    )
    private val scrollPxPerFrameWidget = addWidget(
        l10n("ui.styling.page.scrollPxPerFrame"),
        SpinnerWidget(
            SpinnerNumberModel(1f, 0.01f, null, 1f),
            verify = {
                val value = it as Float
                if (floor(value) != value)
                    throw VerifyResult(Severity.WARN, l10n("ui.styling.page.scrollPxPerFrameFractional"))
            }),
        isVisible = { behaviorWidget.selectedItem == PageBehavior.SCROLL }
    )

    init {
        finishInit()
    }

    private fun load(style: PageStyle) {
        nameWidget.text = style.name
        behaviorWidget.selectedItem = style.behavior
        meltWithPrevWidget.isSelected = style.meltWithPrev
        meltWithNextWidget.isSelected = style.meltWithNext
        afterwardSlugFramesWidget.value = style.afterwardSlugFrames
        cardDurationFramesWidget.value = style.cardDurationFrames
        cardFadeInFramesWidget.value = style.cardFadeInFrames
        cardFadeOutFramesWidget.value = style.cardFadeOutFrames
        scrollPxPerFrameWidget.value = style.scrollPxPerFrame
    }

    private fun save() = PageStyle(
        nameWidget.text.trim(),
        behaviorWidget.selectedItem,
        meltWithPrevWidget.isSelected,
        meltWithNextWidget.isSelected,
        afterwardSlugFramesWidget.value as Int,
        cardDurationFramesWidget.value as Int,
        cardFadeInFramesWidget.value as Int,
        cardFadeOutFramesWidget.value as Int,
        scrollPxPerFrameWidget.value as Float
    )

    fun open(style: PageStyle, allStyles: List<PageStyle>, onChange: (PageStyle) -> Unit) {
        nameWidget.otherStyleNames = allStyles.filter { it !== style }.map { it.name }

        withSuspendedChangeEvents {
            load(style)
            changeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}