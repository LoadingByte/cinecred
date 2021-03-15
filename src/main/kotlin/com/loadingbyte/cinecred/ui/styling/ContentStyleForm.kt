package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import javax.swing.SpinnerNumberModel


object ContentStyleForm : Form() {

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
        isEnabled = { hasHeadWidget.isSelected || hasTailWidget.isSelected }
    )
    private val vMarginPxWidget = addWidget(
        l10n("ui.styling.content.vMarginPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f))
    )
    private val bodyLetterStyleWidget = addWidget(
        l10n("ui.styling.content.bodyLetterStyle"),
        InconsistentComboBoxWidget(emptyList(), l10n("ui.styling.content.letterStyleUnavailable"))
    )

    init {
        addSeparator()
    }

    private val bodyLayoutWidget = addWidget(
        l10n("ui.styling.content.bodyLayout"),
        ComboBoxWidget(BodyLayout.values().asList(), toString = ::l10nEnum)
    )

    private val gridFillingOrderWidget = addWidget(
        l10n("ui.styling.content.gridFillingOrder"),
        ComboBoxWidget(GridFillingOrder.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID }
    )
    private val gridElemBoxConformWidget = addWidget(
        l10n("ui.styling.content.gridElemBoxConform"),
        ComboBoxWidget(BodyElementBoxConform.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID }
    )
    private val gridElemHJustifyPerColWidget = addWidget(
        l10n("ui.styling.content.gridElemHJustifyPerCol"),
        ComboBoxListWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID },
    )
    private val gridElemVJustifyWidget = addWidget(
        l10n("ui.styling.content.gridElemVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID },
        isEnabled = {
            gridElemHJustifyPerColWidget.selectedItems.size >= 2 || gridElemBoxConformWidget.selectedItem.let {
                it == BodyElementBoxConform.HEIGHT || it == BodyElementBoxConform.WIDTH_AND_HEIGHT ||
                        it == BodyElementBoxConform.SQUARE
            }
        }
    )
    private val gridRowGapPxWidget = addWidget(
        l10n("ui.styling.content.gridRowGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID }
    )
    private val gridColGapPxWidget = addWidget(
        l10n("ui.styling.content.gridColGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.GRID },
        isEnabled = { gridElemHJustifyPerColWidget.selectedItems.size >= 2 }
    )

    private val flowDirectionWidget = addWidget(
        l10n("ui.styling.content.flowDirection"),
        ComboBoxWidget(
            FlowDirection.values().asList(),
            toString = {
                when (it) {
                    FlowDirection.L2R -> "\u2192"
                    FlowDirection.R2L -> "\u2190"
                }
            }),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowLineHJustifyWidget = addWidget(
        l10n("ui.styling.content.flowLineHJustify"),
        ComboBoxWidget(LineHJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowElemBoxConformWidget = addWidget(
        l10n("ui.styling.content.flowElemBoxConform"),
        ComboBoxWidget(BodyElementBoxConform.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowElemHJustifyWidget = addWidget(
        l10n("ui.styling.content.flowElemHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowElemVJustifyWidget = addWidget(
        l10n("ui.styling.content.flowElemVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowLineWidthPxWidget = addWidget(
        l10n("ui.styling.content.flowLineWidthPx"),
        SpinnerWidget(SpinnerNumberModel(1f, 0.01f, null, 10f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowLineGapPxWidget = addWidget(
        l10n("ui.styling.content.flowLineGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowHGapPxWidget = addWidget(
        l10n("ui.styling.content.flowHGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )
    private val flowSeparatorWidget = addWidget(
        l10n("ui.styling.content.flowSeparator"),
        TextWidget(grow = false),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.FLOW }
    )

    private val paragraphsLineHJustifyWidget = addWidget(
        l10n("ui.styling.content.paragraphsLineHJustify"),
        ComboBoxWidget(LineHJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.PARAGRAPHS }
    )
    private val paragraphsLineWidthPxWidget = addWidget(
        l10n("ui.styling.content.paragraphsLineWidthPx"),
        SpinnerWidget(SpinnerNumberModel(1f, 0.01f, null, 10f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.PARAGRAPHS }
    )
    private val paragraphsParaGapPxWidget = addWidget(
        l10n("ui.styling.content.paragraphsParaGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.PARAGRAPHS }
    )
    private val paragraphsLineGapPxWidget = addWidget(
        l10n("ui.styling.content.paragraphsLineGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { bodyLayoutWidget.selectedItem == BodyLayout.PARAGRAPHS }
    )

    init {
        addSeparator()
    }

    private val hasHeadWidget = addWidget(
        l10n("ui.styling.content.hasHead"),
        CheckBoxWidget()
    )
    private val headLetterStyleWidget = addWidget(
        l10n("ui.styling.content.headLetterStyle"),
        InconsistentComboBoxWidget(emptyList(), l10n("ui.styling.content.letterStyleUnavailable")),
        isVisible = { hasHeadWidget.isSelected }
    )
    private val headHJustifyWidget = addWidget(
        l10n("ui.styling.content.headHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasHeadWidget.isSelected }
    )
    private val headVJustifyWidget = addWidget(
        l10n("ui.styling.content.headVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasHeadWidget.isSelected },
        isEnabled = { spineOrientationWidget.selectedItem == SpineOrientation.HORIZONTAL }
    )
    private val headGapPxWidget = addWidget(
        l10n("ui.styling.content.headGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { hasHeadWidget.isSelected }
    )

    init {
        addSeparator()
    }

    private val hasTailWidget = addWidget(
        l10n("ui.styling.content.hasTail"),
        CheckBoxWidget()
    )
    private val tailLetterStyleWidget = addWidget(
        l10n("ui.styling.content.tailLetterStyle"),
        InconsistentComboBoxWidget(emptyList(), l10n("ui.styling.content.letterStyleUnavailable")),
        isVisible = { hasTailWidget.isSelected }
    )
    private val tailHJustifyWidget = addWidget(
        l10n("ui.styling.content.tailHJustify"),
        ComboBoxWidget(HJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasTailWidget.isSelected }
    )
    private val tailVJustifyWidget = addWidget(
        l10n("ui.styling.content.tailVJustify"),
        ComboBoxWidget(VJustify.values().asList(), toString = ::l10nEnum),
        isVisible = { hasTailWidget.isSelected },
        isEnabled = { spineOrientationWidget.selectedItem == SpineOrientation.HORIZONTAL }
    )
    private val tailGapPxWidget = addWidget(
        l10n("ui.styling.content.tailGapPx"),
        SpinnerWidget(SpinnerNumberModel(0f, 0f, null, 1f)),
        isVisible = { hasTailWidget.isSelected }
    )

    init {
        finishInit()
    }

    private fun load(style: ContentStyle) {
        nameWidget.text = style.name
        spineOrientationWidget.selectedItem = style.spineOrientation
        alignWithAxisWidget.selectedItem = style.alignWithAxis
        vMarginPxWidget.value = style.vMarginPx
        bodyLetterStyleWidget.selectedItem = style.bodyLetterStyleName
        bodyLayoutWidget.selectedItem = style.bodyLayout
        gridFillingOrderWidget.selectedItem = style.gridFillingOrder
        gridElemBoxConformWidget.selectedItem = style.gridElemBoxConform
        gridElemHJustifyPerColWidget.selectedItems = style.gridElemHJustifyPerCol
        gridElemVJustifyWidget.selectedItem = style.gridElemVJustify
        gridRowGapPxWidget.value = style.gridRowGapPx
        gridColGapPxWidget.value = style.gridColGapPx
        flowDirectionWidget.selectedItem = style.flowDirection
        flowLineHJustifyWidget.selectedItem = style.flowLineHJustify
        flowElemBoxConformWidget.selectedItem = style.flowElemBoxConform
        flowElemHJustifyWidget.selectedItem = style.flowElemHJustify
        flowElemVJustifyWidget.selectedItem = style.flowElemVJustify
        flowLineWidthPxWidget.value = style.flowLineWidthPx
        flowLineGapPxWidget.value = style.flowLineGapPx
        flowHGapPxWidget.value = style.flowHGapPx
        flowSeparatorWidget.text = style.flowSeparator
        paragraphsLineHJustifyWidget.selectedItem = style.paragraphsLineHJustify
        paragraphsLineWidthPxWidget.value = style.paragraphsLineWidthPx
        paragraphsParaGapPxWidget.value = style.paragraphsParaGapPx
        paragraphsLineGapPxWidget.value = style.paragraphsLineGapPx
        hasHeadWidget.isSelected = style.hasHead
        headLetterStyleWidget.selectedItem = style.headLetterStyleName
        headHJustifyWidget.selectedItem = style.headHJustify
        headVJustifyWidget.selectedItem = style.headVJustify
        headGapPxWidget.value = style.headGapPx
        hasTailWidget.isSelected = style.hasTail
        tailLetterStyleWidget.selectedItem = style.tailLetterStyleName
        tailHJustifyWidget.selectedItem = style.tailHJustify
        tailVJustifyWidget.selectedItem = style.tailVJustify
        tailGapPxWidget.value = style.tailGapPx
    }

    private fun save() = ContentStyle(
        nameWidget.text.trim(),
        spineOrientationWidget.selectedItem,
        alignWithAxisWidget.selectedItem,
        vMarginPxWidget.value as Float,
        bodyLetterStyleWidget.selectedItem,
        bodyLayoutWidget.selectedItem,
        gridFillingOrderWidget.selectedItem,
        gridElemBoxConformWidget.selectedItem,
        gridElemHJustifyPerColWidget.selectedItems.toImmutableList(),
        gridElemVJustifyWidget.selectedItem,
        gridRowGapPxWidget.value as Float,
        gridColGapPxWidget.value as Float,
        flowDirectionWidget.selectedItem,
        flowLineHJustifyWidget.selectedItem,
        flowElemBoxConformWidget.selectedItem,
        flowElemHJustifyWidget.selectedItem,
        flowElemVJustifyWidget.selectedItem,
        flowLineWidthPxWidget.value as Float,
        flowLineGapPxWidget.value as Float,
        flowHGapPxWidget.value as Float,
        flowSeparatorWidget.text,
        paragraphsLineHJustifyWidget.selectedItem,
        paragraphsLineWidthPxWidget.value as Float,
        paragraphsParaGapPxWidget.value as Float,
        paragraphsLineGapPxWidget.value as Float,
        hasHeadWidget.isSelected,
        headLetterStyleWidget.selectedItem,
        headHJustifyWidget.selectedItem,
        headVJustifyWidget.selectedItem,
        headGapPxWidget.value as Float,
        hasTailWidget.isSelected,
        tailLetterStyleWidget.selectedItem,
        tailHJustifyWidget.selectedItem,
        tailVJustifyWidget.selectedItem,
        tailGapPxWidget.value as Float
    )

    fun open(
        style: ContentStyle, allStyles: List<ContentStyle>, letterStyles: List<LetterStyle>,
        onChange: (ContentStyle) -> Unit
    ) {
        nameWidget.otherStyleNames = allStyles.filter { it !== style }.map { it.name }

        withSuspendedChangeEvents {
            val letterStyleNames = letterStyles.map { it.name }
            bodyLetterStyleWidget.items = letterStyleNames
            headLetterStyleWidget.items = letterStyleNames
            tailLetterStyleWidget.items = letterStyleNames

            load(style)
            changeListener = { if (isErrorFree) onChange(save()) }
        }
    }

}
