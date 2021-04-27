package com.loadingbyte.cinecred.ui.styling

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableSet
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*


class EditStylingPanel(private val ctrl: ProjectController) : JPanel() {

    private val globalForm = GlobalForm()
    private val pageStyleForm = PageStyleForm()
    private val contentStyleForm = ContentStyleForm()
    private val letterStyleForm = LetterStyleForm()

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

    init {
        stylingTree.onDeselect = ::openBlank
        stylingTree.addSingletonType(
            PRESET_GLOBAL, l10n("ui.styling.globalStyling"), GLOBE_ICON,
            onSelect = ::openGlobal
        )
        stylingTree.addListType(
            PageStyle::class.java, l10n("ui.styling.pageStyles"), FILMSTRIP_ICON,
            onSelect = ::openPageStyle,
            objToString = { it.name }, copyObj = { it.copy() }
        )
        stylingTree.addListType(
            ContentStyle::class.java, l10n("ui.styling.contentStyles"), LAYOUT_ICON,
            onSelect = ::openContentStyle,
            objToString = { it.name }, copyObj = { it.copy() }
        )
        stylingTree.addListType(
            LetterStyle::class.java, l10n("ui.styling.letterStyles"), LETTERS_ICON,
            onSelect = ::openLetterStyle,
            objToString = { it.name }, copyObj = { it.copy() }
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
            if (stylingTree.duplicateSelectedListElement())
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
        rightPanelCards.show(rightPanel, "Blank")
    }

    private fun openGlobal(global: Global) {
        globalForm.open(global, onChange = { stylingTree.setSingleton(it); onChange() })
        postOpenForm("Global", globalForm)
    }

    private fun openPageStyle(style: PageStyle) {
        pageStyleForm.open(
            style, stylingTree.getList(PageStyle::class.java),
            onChange = { stylingTree.updateSelectedListElement(it); onChange() })
        postOpenForm("PageStyle", pageStyleForm)
    }

    private fun openContentStyle(style: ContentStyle) {
        contentStyleForm.open(
            style, stylingTree.getList(ContentStyle::class.java), stylingTree.getList(LetterStyle::class.java),
            onChange = { stylingTree.updateSelectedListElement(it); onChange() })
        postOpenForm("ContentStyle", contentStyleForm)
    }

    private fun openLetterStyle(style: LetterStyle) {
        var oldName = style.name
        letterStyleForm.open(
            style, stylingTree.getList(LetterStyle::class.java),
            onChange = {
                stylingTree.updateSelectedListElement(it)

                // If the letter style changed its name, update all occurrences of that name in all content styles.
                val newName = it.name
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

    private fun postOpenForm(cardName: String, form: Form) {
        rightPanelCards.show(rightPanel, cardName)
        // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
        // Note that we have to delay this change because for some reason, if we don't, the change has no effect.
        SwingUtilities.invokeLater { (form.parent.parent as JScrollPane).verticalScrollBar.value = 0 }
    }

    fun setStyling(styling: Styling) {
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
            stylingTree.getList(PageStyle::class.java).toImmutableSet(),
            stylingTree.getList(ContentStyle::class.java).toImmutableSet(),
            stylingTree.getList(LetterStyle::class.java).toImmutableSet(),
        )
        ctrl.stylingHistory.editedAndRedraw(styling)
    }

}
