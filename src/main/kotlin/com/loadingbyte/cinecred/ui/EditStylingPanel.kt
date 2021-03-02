package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.toFPS
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.math.floor


object EditStylingDialog : JDialog(MainFrame, "Cinecred \u2013 " + l10n("ui.styling.title")) {

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                Controller.setEditStylingDialogVisible(false)
            }
        })

        // Make the window fill the left half of the screen.
        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width / 2, maxWinBounds.height)
        setLocation(maxWinBounds.x + maxWinBounds.width / 2, maxWinBounds.y)

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(EditStylingPanel)
    }

}


object EditStylingPanel : JPanel() {

    private var global: Global? = null

    // Create a panel with the three style editing forms.
    private val rightPanelCards = CardLayout()
    private val rightPanel = JPanel(rightPanelCards).apply {
        add(JPanel(), "Blank")
        add(JScrollPane(GlobalForm), "Global")
        add(JScrollPane(PageStyleForm), "PageStyle")
        add(JScrollPane(ContentStyleForm), "ContentStyle")
    }

    init {
        // Add buttons for adding and removing page and content style nodes.
        val addPageStyleButton = JButton(DualSVGIcon(ADD_ICON, FILMSTRIP_ICON))
            .apply { toolTipText = l10n("ui.styling.addPageStyleTooltip") }
        val addContentStyleButton = JButton(DualSVGIcon(ADD_ICON, LAYOUT_ICON))
            .apply { toolTipText = l10n("ui.styling.addContentStyleTooltip") }
        val duplicateStyleButton = JButton(DUPLICATE_ICON)
            .apply { toolTipText = l10n("ui.styling.duplicateStyleTooltip") }
        val removeStyleButton = JButton(TRASH_ICON)
            .apply { toolTipText = l10n("ui.styling.removeStyleTooltip") }
        addPageStyleButton.addActionListener {
            EditStylingTree.addPageStyle(STANDARD_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName")))
            onChange()
        }
        addContentStyleButton.addActionListener {
            EditStylingTree.addContentStyle(STANDARD_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName")))
            onChange()
        }
        duplicateStyleButton.addActionListener {
            if (EditStylingTree.duplicateSelectedStyle())
                onChange()
        }
        removeStyleButton.addActionListener {
            if (EditStylingTree.removeSelectedStyle())
                onChange()
        }

        // Layout the tree and the buttons.
        val leftPanel = JPanel(MigLayout()).apply {
            add(addPageStyleButton, "split, grow")
            add(addContentStyleButton, "grow")
            add(duplicateStyleButton, "grow")
            add(removeStyleButton, "grow")
            add(JScrollPane(EditStylingTree), "newline, grow, push")
        }

        // Put everything together in a split pane.
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftPanel, rightPanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater { splitPane.setDividerLocation(0.25) }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    fun openGlobal() {
        GlobalForm.openGlobal(global!!, changeCallback = { global = it; onChange() })
        rightPanelCards.show(rightPanel, "Global")
        // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
        // Note that we have to delay this change because for some reason, if we don't, the change has no effect.
        SwingUtilities.invokeLater { (GlobalForm.parent.parent as JScrollPane).verticalScrollBar.value = 0 }
    }

    fun openPageStyle(pageStyle: PageStyle) {
        PageStyleForm.openPageStyle(pageStyle)
        rightPanelCards.show(rightPanel, "PageStyle")
        SwingUtilities.invokeLater { (PageStyleForm.parent.parent as JScrollPane).verticalScrollBar.value = 0 }
    }

    fun openContentStyle(contentStyle: ContentStyle) {
        ContentStyleForm.openContentStyle(contentStyle)
        rightPanelCards.show(rightPanel, "ContentStyle")
        SwingUtilities.invokeLater { (ContentStyleForm.parent.parent as JScrollPane).verticalScrollBar.value = 0 }
    }

    fun openBlank() {
        rightPanelCards.show(rightPanel, "Blank")
    }

    fun setStyling(styling: Styling) {
        global = styling.global
        EditStylingTree.setStyling(styling)
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        ContentStyleForm.updateProjectFontFamilies(projectFamilies)
    }

    private fun onChange() {
        val styling = Styling(
            global!!,
            EditStylingTree.pageStyles.toImmutableList(),
            EditStylingTree.contentStyles.toImmutableList()
        )
        Controller.StylingHistory.editedAndRedraw(styling)
    }

    private fun l10nEnum(enumElem: Enum<*>) =
        l10n("project.${enumElem.javaClass.simpleName}.${enumElem.name}")


    class StyleNameWidget : TextWidget() {

        override val verify = {
            val name = text.trim()
            if (name.isEmpty())
                throw Form.VerifyResult(Severity.ERROR, l10n("general.blank"))
            if (otherStyleNames.any { o -> o.equals(name, ignoreCase = true) })
                throw Form.VerifyResult(Severity.ERROR, l10n("ui.styling.duplicateStyleName"))
        }

        var otherStyleNames: List<String> = emptyList()

    }


    private object GlobalForm : Form() {

        private val SUGGESTED_FPS = floatArrayOf(23.97f, 24f, 25f, 29.97f, 30f, 59.94f, 60f)

        private val fpsWidget = addWidget(
            l10n("ui.styling.global.fps"),
            ComboBoxWidget(
                SUGGESTED_FPS.map { "%.3f".format(it).dropLast(1) },
                isEditable = true,
                verify = {
                    if (it.isBlank())
                        throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                    try {
                        it.toFPS()
                    } catch (_: IllegalArgumentException) {
                        throw VerifyResult(Severity.ERROR, l10n("ui.styling.global.illFormattedFPS"))
                    }
                })
        )
        private val widthPxWidget = addWidget(
            l10n("ui.styling.global.widthPx"),
            SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
        )
        private val heightPxWidget = addWidget(
            l10n("ui.styling.global.heightPx"),
            SpinnerWidget(SpinnerNumberModel(1, 1, null, 10))
        )
        private val backgroundWidget = addWidget(
            l10n("ui.styling.global.background"),
            ColorChooserButtonWidget()
        )
        private val unitVGapPxWidget = addWidget(
            l10n("ui.styling.global.unitVGapPx"),
            SpinnerWidget(SpinnerNumberModel(1f, 0.01f, null, 1f))
        )

        init {
            finishInit()
        }

        fun openGlobal(global: Global, changeCallback: (Global) -> Unit) {
            withSuspendedChangeEvents {
                fpsWidget.selectedItem =
                    if (global.fps.denominator == 1) global.fps.numerator.toString()
                    else "%.3f".format(global.fps.frac).dropLast(1)
                widthPxWidget.value = global.widthPx
                heightPxWidget.value = global.heightPx
                backgroundWidget.selectedColor = global.background
                unitVGapPxWidget.value = global.unitVGapPx

                changeListener = {
                    if (isErrorFree) {
                        val newGlobal = Global(
                            fpsWidget.selectedItem.toFPS(),
                            widthPxWidget.value as Int,
                            heightPxWidget.value as Int,
                            backgroundWidget.selectedColor,
                            unitVGapPxWidget.value as Float
                        )
                        changeCallback(newGlobal)
                    }
                }
            }
        }

    }


    private object PageStyleForm : Form() {

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

        fun openPageStyle(pageStyle: PageStyle) {
            nameWidget.otherStyleNames = EditStylingTree.pageStyles.filter { it !== pageStyle }.map { it.name }

            withSuspendedChangeEvents {
                nameWidget.text = style.name
                behaviorWidget.selectedItem = style.behavior
                meltWithPrevWidget.isSelected = style.meltWithPrev
                meltWithNextWidget.isSelected = style.meltWithNext
                afterwardSlugFramesWidget.value = style.afterwardSlugFrames
                cardDurationFramesWidget.value = style.cardDurationFrames
                cardFadeInFramesWidget.value = style.cardFadeInFrames
                cardFadeOutFramesWidget.value = style.cardFadeOutFrames
                scrollPxPerFrameWidget.value = style.scrollPxPerFrame

                changeListener = {
                    if (isErrorFree) {
                        val newPageStyle = PageStyle(
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
                        EditStylingTree.updateSelectedStyle(newPageStyle)
                        onChange()
                    }
                }
            }
        }

    }


    private object ContentStyleForm : Form() {

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

        fun openContentStyle(contentStyle: ContentStyle) {
            nameWidget.otherStyleNames = EditStylingTree.contentStyles.filter { it !== contentStyle }.map { it.name }

            withSuspendedChangeEvents {
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

                changeListener = {
                    if (isErrorFree) {
                        val newContentStyle = ContentStyle(
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
                        EditStylingTree.updateSelectedStyle(newContentStyle)
                        onChange()
                    }
                }
            }
        }

    }

}
