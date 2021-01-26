package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.toFPS
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


    private object GlobalForm : Form() {

        private val SUGGESTED_FPS = floatArrayOf(23.97f, 24f, 25f, 29.97f, 30f, 59.94f, 60f)

        private val fpsComboBox = addComboBox(
            l10n("ui.styling.global.fps"), SUGGESTED_FPS.map { "%.3f".format(it).dropLast(1) }.toTypedArray(),
            verify = {
                try {
                    it?.toFPS() ?: throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                } catch (_: IllegalArgumentException) {
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.global.illFormattedFPS"))
                }
            }
        ).apply { isEditable = true }

        private val widthPxSpinner = addSpinner(l10n("ui.styling.global.widthPx"), SpinnerNumberModel(1, 1, null, 10))
        private val heightPxSpinner = addSpinner(l10n("ui.styling.global.heightPx"), SpinnerNumberModel(1, 1, null, 10))
        private val backgroundColorChooserButton = addColorChooserButton(l10n("ui.styling.global.background"))
        private val unitVGapPxSpinner = addSpinner(
            l10n("ui.styling.global.unitVGapPx"), SpinnerNumberModel(1f, 0.01f, null, 1f)
        )

        init {
            finishInit()
        }

        fun openGlobal(global: Global, changeCallback: (Global) -> Unit) {
            clearChangeListeners()

            withSuspendedChangeEvents {
                fpsComboBox.selectedItem =
                    if (global.fps.denominator == 1) global.fps.numerator.toString()
                    else "%.3f".format(global.fps.frac).dropLast(1)
                widthPxSpinner.value = global.widthPx
                heightPxSpinner.value = global.heightPx
                backgroundColorChooserButton.selectedColor = global.background
                unitVGapPxSpinner.value = global.unitVGapPx
            }

            addChangeListener {
                if (isErrorFree) {
                    val newGlobal = Global(
                        (fpsComboBox.selectedItem as String).toFPS(),
                        widthPxSpinner.value as Int,
                        heightPxSpinner.value as Int,
                        backgroundColorChooserButton.selectedColor,
                        unitVGapPxSpinner.value as Float
                    )
                    changeCallback(newGlobal)
                }
            }
        }

    }


    private object PageStyleForm : Form() {

        private val nameField = addTextField(
            l10n("ui.styling.page.name"),
            verify = {
                val name = it.trim()
                if (name.isEmpty())
                    throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                if (otherPageStyles.any { o -> o.name.equals(name, ignoreCase = true) })
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.duplicateStyleName"))
            }
        )
        private val behaviorComboBox = addComboBox(
            l10n("ui.styling.page.behavior"), PageBehavior.values(), toString = ::l10nEnum
        )
        private val meltWithPrevCheckBox = addCheckBox(
            l10n("ui.styling.page.meltWithPrev"),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.SCROLL }
        )
        private val meltWithNextCheckBox = addCheckBox(
            l10n("ui.styling.page.meltWithNext"),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.SCROLL }
        )
        private val afterwardSlugFramesSpinner = addSpinner(
            l10n("ui.styling.page.afterwardSlugFrames"), SpinnerNumberModel(0, 0, null, 1),
            isVisible = { !(behaviorComboBox.selectedItem == PageBehavior.SCROLL && meltWithNextCheckBox.isSelected) }
        )
        private val cardDurationFramesSpinner = addSpinner(
            l10n("ui.styling.page.cardDurationFrames"), SpinnerNumberModel(0, 0, null, 1),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.CARD }
        )
        private val cardFadeInFramesSpinner = addSpinner(
            l10n("ui.styling.page.cardInFrames"), SpinnerNumberModel(0, 0, null, 1),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.CARD }
        )
        private val cardFadeOutFramesSpinner = addSpinner(
            l10n("ui.styling.page.cardOutFrames"), SpinnerNumberModel(0, 0, null, 1),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.CARD }
        )
        private val scrollPxPerFrameSpinner = addSpinner(
            l10n("ui.styling.page.scrollPxPerFrame"), SpinnerNumberModel(1f, 0.01f, null, 1f),
            isVisible = { behaviorComboBox.selectedItem == PageBehavior.SCROLL },
            verify = {
                val value = it as Float
                if (floor(value) != value)
                    throw VerifyResult(Severity.WARN, l10n("ui.styling.page.scrollPxPerFrameFractional"))
            }
        )

        init {
            finishInit()
        }

        private var otherPageStyles = emptyList<PageStyle>()

        fun openPageStyle(pageStyle: PageStyle) {
            otherPageStyles = EditStylingTree.pageStyles.filter { it !== pageStyle }

            clearChangeListeners()

            withSuspendedChangeEvents {
                nameField.text = pageStyle.name
                behaviorComboBox.selectedItem = pageStyle.behavior
                meltWithPrevCheckBox.isSelected = pageStyle.meltWithPrev
                meltWithNextCheckBox.isSelected = pageStyle.meltWithNext
                afterwardSlugFramesSpinner.value = pageStyle.afterwardSlugFrames
                cardDurationFramesSpinner.value = pageStyle.cardDurationFrames
                cardFadeInFramesSpinner.value = pageStyle.cardFadeInFrames
                cardFadeOutFramesSpinner.value = pageStyle.cardFadeOutFrames
                scrollPxPerFrameSpinner.value = pageStyle.scrollPxPerFrame
            }

            addChangeListener {
                if (isErrorFree) {
                    val newPageStyle = PageStyle(
                        nameField.text.trim(),
                        behaviorComboBox.selectedItem as PageBehavior,
                        meltWithPrevCheckBox.isSelected,
                        meltWithNextCheckBox.isSelected,
                        afterwardSlugFramesSpinner.value as Int,
                        cardDurationFramesSpinner.value as Int,
                        cardFadeInFramesSpinner.value as Int,
                        cardFadeOutFramesSpinner.value as Int,
                        scrollPxPerFrameSpinner.value as Float
                    )
                    EditStylingTree.updateSelectedStyle(newPageStyle)
                    onChange()
                }
            }
        }

    }


    private object ContentStyleForm : Form() {

        private fun isGridOrFlow(layout: Any?) = layout == BodyLayout.GRID || layout == BodyLayout.FLOW
        private fun isFlowOrParagraphs(layout: Any?) = layout == BodyLayout.FLOW || layout == BodyLayout.PARAGRAPHS

        private val nameField = addTextField(
            l10n("ui.styling.content.name"),
            verify = {
                val name = it.trim()
                if (name.isEmpty())
                    throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                if (otherContentStyles.any { o -> o.name.equals(name, ignoreCase = true) })
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.duplicateStyleName"))
            }
        )
        private val alignWithAxisComboBox = addComboBox(
            l10n("ui.styling.content.alignWithAxis"), AlignWithAxis.values(), toString = ::l10nEnum
        )
        private val spineOrientationComboBox = addComboBox(
            l10n("ui.styling.content.spineOrientation"), SpineOrientation.values(), toString = ::l10nEnum,
            isVisible = { hasHeadCheckBox.isSelected || hasTailCheckBox.isSelected }
        )
        private val vMarginPxSpinner = addSpinner(
            l10n("ui.styling.content.vMarginPx"), SpinnerNumberModel(0f, 0f, null, 1f)
        )

        init {
            addSeparator()
        }

        private val bodyLayoutComboBox = addComboBox(
            l10n("ui.styling.content.bodyLayout"), BodyLayout.values(), toString = ::l10nEnum
        )
        private val bodyLayoutLineHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.lineHJustify"), LineHJustify.values(), toString = ::l10nEnum,
            isVisible = { isFlowOrParagraphs(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutColsHJustifyComboBox = addComboBoxList(
            l10n("ui.styling.content.colsHJustify"), HJustify.values(), toString = ::l10nEnum,
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.GRID },
        )
        private val bodyLayoutElemHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.elemHJustify"), HJustify.values(), toString = ::l10nEnum,
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.FLOW }
        )
        private val bodyLayoutElemVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.elemVJustify"), VJustify.values(), toString = ::l10nEnum,
            isVisible = { isGridOrFlow(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutElemConformComboBox = addComboBox(
            l10n("ui.styling.content.elemConform"), BodyElementConform.values(), toString = ::l10nEnum,
            isVisible = { isGridOrFlow(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutSeparatorField = addTextField(
            l10n("ui.styling.content.separator"), grow = false,
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.FLOW }
        )
        private val bodyLayoutBodyWidthPxSpinner = addSpinner(
            l10n("ui.styling.content.bodyWidthPx"), SpinnerNumberModel(1f, 0.01f, null, 10f),
            isVisible = { isFlowOrParagraphs(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutParagraphGapPxSpinner = addSpinner(
            l10n("ui.styling.content.paragraphGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.PARAGRAPHS }
        )
        private val bodyLayoutLineGapPxSpinner = addSpinner(
            l10n("ui.styling.content.lineGapPx"), SpinnerNumberModel(0f, 0f, null, 1f)
        )
        private val bodyLayoutHorizontalGapPxSpinner = addSpinner(
            l10n("ui.styling.content.horizontalGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = {
                bodyLayoutComboBox.selectedItem == BodyLayout.GRID &&
                        bodyLayoutColsHJustifyComboBox.selectedItems.size >= 2 ||
                        bodyLayoutComboBox.selectedItem == BodyLayout.FLOW
            }
        )

        private val bodyFontSpecChooser = addFontSpecChooser(l10n("ui.styling.content.bodyFont"))

        init {
            addSeparator()
        }

        private val hasHeadCheckBox = addCheckBox(l10n("ui.styling.content.hasHead"))
        private val headHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.headHJustify"), HJustify.values(), toString = ::l10nEnum,
            isVisible = { hasHeadCheckBox.isSelected }
        )
        private val headVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.headVJustify"), VJustify.values(), toString = ::l10nEnum,
            isVisible = { hasHeadCheckBox.isSelected && spineOrientationComboBox.selectedItem == SpineOrientation.HORIZONTAL }
        )
        private val headGapPxSpinner = addSpinner(
            l10n("ui.styling.content.headGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = { hasHeadCheckBox.isSelected }
        )
        private val headFontSpecChooser = addFontSpecChooser(
            l10n("ui.styling.content.headFont"),
            isVisible = { hasHeadCheckBox.isSelected }
        )

        init {
            addSeparator()
        }

        private val hasTailCheckBox = addCheckBox(l10n("ui.styling.content.hasTail"))
        private val tailHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.tailHJustify"), HJustify.values(), toString = ::l10nEnum,
            isVisible = { hasTailCheckBox.isSelected }
        )
        private val tailVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.tailVJustify"), VJustify.values(), toString = ::l10nEnum,
            isVisible = { hasTailCheckBox.isSelected && spineOrientationComboBox.selectedItem == SpineOrientation.HORIZONTAL }
        )
        private val tailGapPxSpinner = addSpinner(
            l10n("ui.styling.content.tailGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = { hasTailCheckBox.isSelected }
        )
        private val tailFontSpecChooser = addFontSpecChooser(
            l10n("ui.styling.content.tailFont"),
            isVisible = { hasTailCheckBox.isSelected }
        )

        init {
            finishInit()
        }

        fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
            bodyFontSpecChooser.projectFamilies = projectFamilies
            headFontSpecChooser.projectFamilies = projectFamilies
            tailFontSpecChooser.projectFamilies = projectFamilies
        }

        private var otherContentStyles = emptyList<ContentStyle>()

        fun openContentStyle(contentStyle: ContentStyle) {
            otherContentStyles = EditStylingTree.contentStyles.filter { it !== contentStyle }

            clearChangeListeners()

            withSuspendedChangeEvents {
                nameField.text = contentStyle.name
                spineOrientationComboBox.selectedItem = contentStyle.spineOrientation
                alignWithAxisComboBox.selectedItem = contentStyle.alignWithAxis
                vMarginPxSpinner.value = contentStyle.vMarginPx
                bodyLayoutComboBox.selectedItem = contentStyle.bodyLayout
                bodyLayoutLineGapPxSpinner.value = contentStyle.bodyLayoutLineGapPx
                bodyLayoutElemConformComboBox.selectedItem = contentStyle.bodyLayoutElemConform
                bodyLayoutElemVJustifyComboBox.selectedItem = contentStyle.bodyLayoutElemVJustify
                bodyLayoutHorizontalGapPxSpinner.value = contentStyle.bodyLayoutHorizontalGapPx
                bodyLayoutColsHJustifyComboBox.selectedItems = contentStyle.bodyLayoutColsHJustify
                bodyLayoutLineHJustifyComboBox.selectedItem = contentStyle.bodyLayoutLineHJustify
                bodyLayoutBodyWidthPxSpinner.value = contentStyle.bodyLayoutBodyWidthPx
                bodyLayoutElemHJustifyComboBox.selectedItem = contentStyle.bodyLayoutElemHJustify
                bodyLayoutSeparatorField.text = contentStyle.bodyLayoutSeparator
                bodyLayoutParagraphGapPxSpinner.value = contentStyle.bodyLayoutParagraphGapPx
                bodyFontSpecChooser.selectedFontSpec = contentStyle.bodyFontSpec
                hasHeadCheckBox.isSelected = contentStyle.hasHead
                headHJustifyComboBox.selectedItem = contentStyle.headHJustify
                headVJustifyComboBox.selectedItem = contentStyle.headVJustify
                headGapPxSpinner.value = contentStyle.headGapPx
                headFontSpecChooser.selectedFontSpec = contentStyle.headFontSpec
                hasTailCheckBox.isSelected = contentStyle.hasTail
                tailHJustifyComboBox.selectedItem = contentStyle.tailHJustify
                tailVJustifyComboBox.selectedItem = contentStyle.tailVJustify
                tailGapPxSpinner.value = contentStyle.tailGapPx
                tailFontSpecChooser.selectedFontSpec = contentStyle.tailFontSpec
            }

            addChangeListener {
                if (isErrorFree) {
                    val newContentStyle = ContentStyle(
                        nameField.text.trim(),
                        spineOrientationComboBox.selectedItem as SpineOrientation,
                        alignWithAxisComboBox.selectedItem as AlignWithAxis,
                        vMarginPxSpinner.value as Float,
                        bodyLayoutComboBox.selectedItem as BodyLayout,
                        bodyLayoutLineGapPxSpinner.value as Float,
                        bodyLayoutElemConformComboBox.selectedItem as BodyElementConform,
                        bodyLayoutElemVJustifyComboBox.selectedItem as VJustify,
                        bodyLayoutHorizontalGapPxSpinner.value as Float,
                        bodyLayoutColsHJustifyComboBox.selectedItems.filterNotNull().toImmutableList(),
                        bodyLayoutLineHJustifyComboBox.selectedItem as LineHJustify,
                        bodyLayoutBodyWidthPxSpinner.value as Float,
                        bodyLayoutElemHJustifyComboBox.selectedItem as HJustify,
                        bodyLayoutSeparatorField.text,
                        bodyLayoutParagraphGapPxSpinner.value as Float,
                        bodyFontSpecChooser.selectedFontSpec,
                        hasHeadCheckBox.isSelected,
                        headHJustifyComboBox.selectedItem as HJustify,
                        headVJustifyComboBox.selectedItem as VJustify,
                        headGapPxSpinner.value as Float,
                        headFontSpecChooser.selectedFontSpec,
                        hasTailCheckBox.isSelected,
                        tailHJustifyComboBox.selectedItem as HJustify,
                        tailVJustifyComboBox.selectedItem as VJustify,
                        tailGapPxSpinner.value as Float,
                        tailFontSpecChooser.selectedFontSpec
                    )
                    EditStylingTree.updateSelectedStyle(newContentStyle)
                    onChange()
                }
            }
        }

    }

}
