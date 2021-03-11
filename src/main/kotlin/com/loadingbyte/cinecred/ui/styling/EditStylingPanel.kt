package com.loadingbyte.cinecred.ui.styling

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.Controller
import com.loadingbyte.cinecred.ui.MainFrame
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*


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

    // Create a panel with the three style editing forms.
    private val rightPanelCards = CardLayout()
    private val rightPanel = JPanel(rightPanelCards).apply {
        add(JScrollPane() /* use a full-blown JScrollPane to match the look of the non-blank cards */, "Blank")
        add(JScrollPane(GlobalForm), "Global")
        add(JScrollPane(PageStyleForm), "PageStyle")
        add(JScrollPane(ContentStyleForm), "ContentStyle")
        add(JScrollPane(LetterStyleForm), "LetterStyle")
    }

    private val stylingTree = StylingTree()

    init {
        stylingTree.onDeselect = ::openBlank
        stylingTree.addSingletonType(
            STANDARD_GLOBAL, l10n("ui.styling.globalStyling"), GLOBE_ICON,
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
            stylingTree.addListElement(STANDARD_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName")), true)
            onChange()
        }
        addContentStyleButton.addActionListener {
            stylingTree.addListElement(STANDARD_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName")), true)
            onChange()
        }
        addLetterStyleButton.addActionListener {
            stylingTree.addListElement(STANDARD_LETTER_STYLE.copy(name = l10n("ui.styling.newLetterStyleName")), true)
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
        GlobalForm.open(global, onChange = { stylingTree.setSingleton(it); onChange() })
        postOpenForm("Global", GlobalForm)
    }

    private fun openPageStyle(style: PageStyle) {
        PageStyleForm.open(
            style, stylingTree.getList(PageStyle::class.java),
            onChange = { stylingTree.updateSelectedListElement(it); onChange() })
        postOpenForm("PageStyle", PageStyleForm)
    }

    private fun openContentStyle(style: ContentStyle) {
        ContentStyleForm.open(
            style, stylingTree.getList(ContentStyle::class.java), stylingTree.getList(LetterStyle::class.java),
            onChange = { stylingTree.updateSelectedListElement(it); onChange() })
        postOpenForm("ContentStyle", ContentStyleForm)
    }

    private fun openLetterStyle(style: LetterStyle) {
        var oldName = style.name
        LetterStyleForm.open(
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
        postOpenForm("LetterStyle", LetterStyleForm)
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
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        LetterStyleForm.updateProjectFontFamilies(projectFamilies)
    }

    private fun onChange() {
        val styling = Styling(
            stylingTree.getSingleton(Global::class.java),
            stylingTree.getList(PageStyle::class.java).toImmutableList(),
            stylingTree.getList(ContentStyle::class.java).toImmutableList(),
            stylingTree.getList(LetterStyle::class.java).toImmutableList(),
        )
        Controller.StylingHistory.editedAndRedraw(styling)
    }

}
