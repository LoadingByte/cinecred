package com.loadingbyte.cinecred.ui.styling

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.*


class EditStylingPanel(private val ctrl: ProjectController) : JPanel() {

    // ========== HINT OWNERS ==========
    val stylingTreeHintOwner: Component
    // =================================

    private val globalForm = StyleForm(Global::class.java)
    private val pageStyleForm = StyleForm(PageStyle::class.java)
    private val contentStyleForm = StyleForm(ContentStyle::class.java)
    private val letterStyleForm = StyleForm(LetterStyle::class.java)

    // Create a panel with the four style editing forms.
    private val rightPanelCards = CardLayout()
    private val rightPanel = JPanel(rightPanelCards).apply {
        add(JScrollPane() /* use a full-blown JScrollPane to match the look of the non-blank cards */, "Blank")
        add(JScrollPane(globalForm), "Global")
        add(JScrollPane(pageStyleForm), "PageStyle")
        add(JScrollPane(contentStyleForm), "ContentStyle")
        add(JScrollPane(letterStyleForm), "LetterStyle")
    }

    private val stylingTree = StylingTree()

    // Cache the styling which is currently stored in the styling tree as well as its constraint violations,
    // so that we don't have to repeatedly regenerate both.
    private var styling: Styling? = null
    private var constraintViolations: List<ConstraintViolation> = emptyList()

    // Keep track of the form which is currently open.
    private var openedForm: StyleForm<*>? = null

    init {
        stylingTreeHintOwner = stylingTree
        stylingTree.onDeselect = ::openBlank
        stylingTree.addSingletonType(
            PRESET_GLOBAL, l10n("ui.styling.globalStyling"), GLOBE_ICON,
            onSelect = ::openGlobal
        )
        stylingTree.addListType(
            PageStyle::class.java, l10n("ui.styling.pageStyles"), FILMSTRIP_ICON,
            onSelect = ::openPageStyle,
            objToString = PageStyle::name,
            copyObj = { it.copy(name = l10n("ui.styling.copiedStyleName", it.name)) }
        )
        stylingTree.addListType(
            ContentStyle::class.java, l10n("ui.styling.contentStyles"), LAYOUT_ICON,
            onSelect = ::openContentStyle,
            objToString = ContentStyle::name,
            copyObj = { it.copy(name = l10n("ui.styling.copiedStyleName", it.name)) }
        )
        stylingTree.addListType(
            LetterStyle::class.java, l10n("ui.styling.letterStyles"), LETTERS_ICON,
            onSelect = ::openLetterStyle,
            objToString = LetterStyle::name,
            copyObj = { it.copy(name = l10n("ui.styling.copiedStyleName", it.name)) }
        )

        fun JButton.makeToolbarButton() = apply { putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON) }

        // Add buttons for adding and removing page and content style nodes.
        val addPageStyleButton = JButton(DualSVGIcon(ADD_ICON, FILMSTRIP_ICON))
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.addPageStyleTooltip") }
        val addContentStyleButton = JButton(DualSVGIcon(ADD_ICON, LAYOUT_ICON))
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.addContentStyleTooltip") }
        val addLetterStyleButton = JButton(DualSVGIcon(ADD_ICON, LETTERS_ICON))
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.addLetterStyleTooltip") }
        val duplicateStyleButton = JButton(DUPLICATE_ICON)
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.duplicateStyleTooltip") }
        val removeStyleButton = JButton(TRASH_ICON)
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.removeStyleTooltip") }
        addPageStyleButton.addActionListener {
            stylingTree.addListElement(PRESET_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName")), true)
            onChange()
        }
        addContentStyleButton.addActionListener {
            stylingTree.addListElement(PRESET_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName")), true)
            onChange()
        }
        addLetterStyleButton.addActionListener {
            stylingTree.addListElement(PRESET_LETTER_STYLE.copy(name = l10n("ui.styling.newLetterStyleName")), true)
            onChange()
        }
        duplicateStyleButton.addActionListener {
            if (stylingTree.duplicateSelectedListElement(selectDuplicate = true))
                onChange()
        }
        removeStyleButton.addActionListener {
            if (stylingTree.removeSelectedListElement())
                onChange()
        }

        // Layout the tree and the buttons.
        val leftPanel = JPanel(MigLayout()).apply {
            add(addPageStyleButton, "split, grow")
            add(addContentStyleButton, "grow")
            add(addLetterStyleButton, "grow")
            add(duplicateStyleButton, "grow")
            add(removeStyleButton, "grow")
            add(JScrollPane(stylingTree), "newline, grow, push")
        }

        // Put everything together in a split pane.
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftPanel, rightPanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater { splitPane.setDividerLocation(0.25) }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    private fun openBlank() {
        openedForm = null
        rightPanelCards.show(rightPanel, "Blank")
    }

    private fun openGlobal(global: Global) {
        globalForm.open(global, onChange = { stylingTree.setSingleton(globalForm.save()); onChange() })
        postOpenForm("Global", globalForm)
    }

    private fun openPageStyle(style: PageStyle) {
        pageStyleForm.open(
            style,
            onChange = { stylingTree.updateSelectedListElement(pageStyleForm.save()); onChange() })
        postOpenForm("PageStyle", pageStyleForm)
    }

    private fun openContentStyle(style: ContentStyle) {
        contentStyleForm.open(
            style,
            onChange = { stylingTree.updateSelectedListElement(contentStyleForm.save()); onChange() })
        postOpenForm("ContentStyle", contentStyleForm)
    }

    private fun openLetterStyle(style: LetterStyle) {
        var oldName = style.name
        letterStyleForm.open(
            style,
            onChange = {
                val newStyle = letterStyleForm.save()
                stylingTree.updateSelectedListElement(newStyle)

                // If the letter style changed its name, update all occurrences of that name in all content styles.
                val newName = newStyle.name
                if (oldName != newName)
                    for (oldContentStyle in stylingTree.getList(ContentStyle::class.java)) {
                        var newContentStyle = oldContentStyle
                        if (newContentStyle.bodyLetterStyleName == oldName)
                            newContentStyle = newContentStyle.copy(bodyLetterStyleName = newName)
                        if (newContentStyle.headLetterStyleName == oldName)
                            newContentStyle = newContentStyle.copy(headLetterStyleName = newName)
                        if (newContentStyle.tailLetterStyleName == oldName)
                            newContentStyle = newContentStyle.copy(tailLetterStyleName = newName)
                        // Can use identity equals here to make the check quicker.
                        if (oldContentStyle !== newContentStyle)
                            stylingTree.updateListElement(oldContentStyle, newContentStyle)
                    }
                oldName = newName

                onChange()
            })
        postOpenForm("LetterStyle", letterStyleForm)
    }

    private fun postOpenForm(cardName: String, form: StyleForm<*>) {
        openedForm = form
        adjustOpenedForm()
        rightPanelCards.show(rightPanel, cardName)
        // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
        // Note that we have to delay this change because for some reason, if we don't, the change has no effect.
        SwingUtilities.invokeLater { (form.parent.parent as JScrollPane).verticalScrollBar.value = 0 }
    }

    fun setStyling(styling: Styling) {
        this.styling = styling
        constraintViolations = ctrl.verifyStylingConstraints(styling)

        stylingTree.setSingleton(styling.global)
        stylingTree.replaceAllListElements(styling.pageStyles + styling.contentStyles + styling.letterStyles)
        // Simulate the user selecting the node which is already selected currently. This triggers a callback
        // which then updates the right panel.
        stylingTree.triggerOnSelectOrOnDeselect()
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        letterStyleForm.updateProjectFontFamilies(projectFamilies)
    }

    private fun onChange() {
        val styling = Styling(
            stylingTree.getSingleton(Global::class.java),
            stylingTree.getList(PageStyle::class.java).toImmutableList(),
            stylingTree.getList(ContentStyle::class.java).toImmutableList(),
            stylingTree.getList(LetterStyle::class.java).toImmutableList(),
        )
        this.styling = styling
        constraintViolations = ctrl.verifyStylingConstraints(styling)

        adjustOpenedForm()
        ctrl.stylingHistory.editedAndRedraw(styling)
    }

    private fun adjustOpenedForm() {
        val styling = this.styling ?: return
        val openedForm = this.openedForm ?: return

        val openedStyle = openedForm.save()

        openedForm.clearNoticeOverrides()
        for (violation in constraintViolations)
            if (violation.style == openedStyle)
                openedForm.setNoticeOverride(violation.setting, Form.Notice(violation.severity, violation.msg))

        for (meta in getStyleMeta(openedStyle))
            if (meta is DynChoiceConstr<Style, *>) {
                val choices = meta.choices(styling, openedStyle).toImmutableList()
                for (setting in meta.settings)
                    openedForm.setDynChoices(setting, choices)
            }
    }

}
