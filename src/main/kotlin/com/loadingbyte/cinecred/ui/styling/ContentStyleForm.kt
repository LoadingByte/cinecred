package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import javax.swing.SpinnerNumberModel


object ContentStyleForm : Form() {

    private fun isGridOrFlow(layout: Any?) = layout == BodyLayout.GRID || layout == BodyLayout.FLOW
    private fun isFlowOrParagraphs(layout: Any?) = layout == BodyLayout.FLOW || layout == BodyLayout.PARAGRAPHS

    private val nameWidget = addWidget(
        l10n("ui.styling.content.name"),
        StyleNameWidget()
    )
    private val alignWithAxisWidget = addWidget(
        l10n("ui.styling.content.alignWithAxis"),
        ComboBoxWidget(AlignWithAxis.values().asList(), toString = ::l10nEnum)
    )
    private val spineOrientationWidget = addWidget(
        l10n("ui.styling.content.spineOrientation"),
        ComboBoxWidget(SpineOrientation.values().asList(), toString = ::l10nEnum),
        isVisible = { hasHeadWidget.isSelected || hasTailWidget.isSelected }
    )
    private val vMarginPxWidget = addWidget(
        l10n("ui.styling.content.vMarginPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f))
    )

    init {
        addSeparator()
    }

    private val bodyLayoutWidget = addWidget(
        l10n("ui.styling.content.bodyLayout"),
        ComboBoxWidget(BodyLayout.values().asList(), toString = ::l10nEnum)
    )
    private val bodyLayoutLineHJustifyWidget = addWidget(
        l10n("ui.styling.content.lineHJustify"),
        ComboBoxWidget(LineHJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { isFlowOrParagraphs(bodyLayoutWidget.selectedItem) }
    )
    private val bodyLayoutColsHJustifyWidget = addWidget(
        l10n("ui.styling.content.colsHJustify"),
        ComboBoxListWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID },
    )
    private val bodyLayoutElemHJustifyWidget = addWidget(
        l10n("ui.styling.content.elemHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val bodyLayoutElemVJustifyWidget = addWidget(
        l10n("ui.styling.content.elemVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { isGridOrFlow(bodyLayoutWidget.selectedItem) }
    )
    private val bodyLayoutElemConfWidget = addWidget(
        l10n("ui.styling.content.elemConform"),
        ComboBoxWidget(BodyElementConform.values().asList(), toString = ::l10nEnum),
        isVisible = { isGridOrFlow(bodyLayoutWidget.selectedItem) }
    )
    private val bodyLayoutSeparatorWidget = addWidget(
        l10n("ui.styling.content.separator"),
        TextWidget(grow = false),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val bodyLayoutBodyWidthPxWidget = addWidget(
        l10n("ui.styling.content.bodyWidthPx"),
        SpinnerWidget(SpinnerNumberModel(1f, 0.01f, null, 10f)),
        isVisible = { isFlowOrParagraphs(bodyLayoutWidget.selectedItem) }
    )
    private val bodyLayoutParagraphGapPxWidget = addWidget(
        l10n("ui.styling.content.paragraphGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.PARAGRAPHS }
    )
    private val bodyLayoutLineGapPxWidget = addWidget(
        l10n("ui.styling.content.lineGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f))
    )
    private val bodyLayoutHorizontalGapPxWidget = addWidget(
        l10n("ui.styling.content.horizontalGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = {
            bodyLayoutWidget.selectedItem == BodyLayout.GRID &&
                    bodyLayoutColsHJustifyWidget.selectedItems.size >= 2 ||
                    bodyLayoutWidget.selectedItem == BodyLayout.FLOW
        }
    )

    private val bodyFontSpecWidget = addWidget(
        l10n("ui.styling.content.bodyFont"),
        FontSpecChooserWidget()
    )

    init {
        addSeparator()
    }

    private val hasHeadWidget = addWidget(
        l10n("ui.styling.content.hasHead"),
        CheckBoxWidget()
    )
    private val headHJustifyWidget = addWidget(
        l10n("ui.styling.content.headHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasHeadWidget.isSelected }
    )
    private val headVJustifyWidget = addWidget(
        l10n("ui.styling.content.headVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasHeadWidget.isSelected && spineOrientationWidget.selectedItem == SpineOrientation.HORIZONTAL }
    )
    private val headGapPxWidget = addWidget(
        l10n("ui.styling.content.headGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { hasHeadWidget.isSelected }
    )
    private val headFontSpecWidget = addWidget(
        l10n("ui.styling.content.headFont"),
        FontSpecChooserWidget(),
        isVisible = { hasHeadWidget.isSelected }
    )

    init {
        addSeparator()
    }

    private val hasTailWidget = addWidget(
        l10n("ui.styling.content.hasTail"),
        CheckBoxWidget()
    )
    private val tailHJustifyWidget = addWidget(
        l10n("ui.styling.content.tailHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasTailWidget.isSelected }
    )
    private val tailVJustifyWidget = addWidget(
        l10n("ui.styling.content.tailVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasTailWidget.isSelected && spineOrientationWidget.selectedItem == SpineOrientation.HORIZONTAL }
    )
    private val tailGapPxWidget = addWidget(
        l10n("ui.styling.content.tailGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { hasTailWidget.isSelected }
    )
    private val tailFontSpecWidget = addWidget(
        l10n("ui.styling.content.tailFont"),
        FontSpecChooserWidget(),
        isVisible = { hasTailWidget.isSelected }
    )

    init {
        finishInit()
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        bodyFontSpecWidget.projectFamilies = projectFamilies
        headFontSpecWidget.projectFamilies = projectFamilies
        tailFontSpecWidget.projectFamilies = projectFamilies
    }

    private fun load(style: ContentStyle) {
        nameWidget.text = style.name
        spineOrientationWidget.selectedItem = style.spineOrientation
        alignWithAxisWidget.selectedItem = style.alignWithAxis
        vMarginPxWidget.value = style.vMarginPx
        bodyLayoutWidget.selectedItem = style.bodyLayout
        bodyLayoutLineGapPxWidget.value = style.bodyLayoutLineGapPx
        bodyLayoutElemConfWidget.selectedItem = style.bodyLayoutElemConform
        bodyLayoutElemVJustifyWidget.selectedItem = style.bodyLayoutElemVJustify
        bodyLayoutHorizontalGapPxWidget.value = style.bodyLayoutHorizontalGapPx
        bodyLayoutColsHJustifyWidget.selectedItems = style.bodyLayoutColsHJustify
        bodyLayoutLineHJustifyWidget.selectedItem = style.bodyLayoutLineHJustify
        bodyLayoutBodyWidthPxWidget.value = style.bodyLayoutBodyWidthPx
        bodyLayoutElemHJustifyWidget.selectedItem = style.bodyLayoutElemHJustify
        bodyLayoutSeparatorWidget.text = style.bodyLayoutSeparator
        bodyLayoutParagraphGapPxWidget.value = style.bodyLayoutParagraphGapPx
        bodyFontSpecWidget.selectedFontSpec = style.bodyFontSpecName
        hasHeadWidget.isSelected = style.hasHead
        headHJustifyWidget.selectedItem = style.headHJustify
        headVJustifyWidget.selectedItem = style.headVJustify
        headGapPxWidget.value = style.headGapPx
        headFontSpecWidget.selectedFontSpec = style.headFontSpecName
        hasTailWidget.isSelected = style.hasTail
        tailHJustifyWidget.selectedItem = style.tailHJustify
        tailVJustifyWidget.selectedItem = style.tailVJustify
        tailGapPxWidget.value = style.tailGapPx
        tailFontSpecWidget.selectedFontSpec = style.tailFontSpecName
    }

    private fun save() = ContentStyle(
        nameWidget.text.trim(),
        spineOrientationWidget.selectedItem,
        alignWithAxisWidget.selectedItem,
        vMarginPxWidget.value as Float,
        bodyLayoutWidget.selectedItem,
        bodyLayoutLineGapPxWidget.value as Float,
        bodyLayoutElemConfWidget.selectedItem,
        bodyLayoutElemVJustifyWidget.selectedItem,
        bodyLayoutHorizontalGapPxWidget.value as Float,
        bodyLayoutColsHJustifyWidget.selectedItems.toImmutableList(),
        bodyLayoutLineHJustifyWidget.selectedItem,
        bodyLayoutBodyWidthPxWidget.value as Float,
        bodyLayoutElemHJustifyWidget.selectedItem,
        bodyLayoutSeparatorWidget.text,
        bodyLayoutParagraphGapPxWidget.value as Float,
        bodyFontSpecWidget.selectedFontSpec,
        hasHeadWidget.isSelected,
        headHJustifyWidget.selectedItem,
        headVJustifyWidget.selectedItem,
        headGapPxWidget.value as Float,
        headFontSpecWidget.selectedFontSpec,
        hasTailWidget.isSelected,
        tailHJustifyWidget.selectedItem,
        tailVJustifyWidget.selectedItem,
        tailGapPxWidget.value as Float,
        tailFontSpecWidget.selectedFontSpec
    )

    fun open(style: ContentStyle, allStyles: List<ContentStyle>, onChange: (ContentStyle) -> Unit) {
        nameWidget.otherStyleNames = allStyles.filter { it !== style }.map { it.name }

        withSuspendedChangeEvents {
            load(style)
            changeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
