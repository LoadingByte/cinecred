package com.loadingbyte.cinecred.ui.styling

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toImmutableList
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent.VK_DELETE
import java.awt.event.KeyEvent.getKeyText
import javax.swing.*


class EditStylingPanel(private val ctrl: ProjectController) : JPanel() {

    // ========== HINT OWNERS ==========
    val stylingTreeHintOwner: Component
    // =================================

    private val globalForm = StyleForm(ctrl.stylingCtx, Global::class.java)
    private val pageStyleForm = StyleForm(ctrl.stylingCtx, PageStyle::class.java)
    private val contentStyleForm = StyleForm(ctrl.stylingCtx, ContentStyle::class.java)
    private val letterStyleForm = StyleForm(ctrl.stylingCtx, LetterStyle::class.java)

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

    // We increase this counter each time a new form is opened. It is used to tell apart multiple edits
    // of the same widget but in different styles.
    private var openCounter = 0

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
            objToString = PageStyle::name
        )
        stylingTree.addListType(
            ContentStyle::class.java, l10n("ui.styling.contentStyles"), LAYOUT_ICON,
            onSelect = ::openContentStyle,
            objToString = ContentStyle::name
        )
        stylingTree.addListType(
            LetterStyle::class.java, l10n("ui.styling.letterStyles"), LETTERS_ICON,
            onSelect = ::openLetterStyle,
            objToString = LetterStyle::name
        )

        fun JButton.makeToolbarButton() = apply {
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
            isFocusable = false
        }

        // Add buttons for adding and removing page and content style nodes.
        val addPageStyleButton = JButton(SVGIcon.Dual(ADD_ICON, FILMSTRIP_ICON))
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.addPageStyleTooltip") }
        val addContentStyleButton = JButton(SVGIcon.Dual(ADD_ICON, LAYOUT_ICON))
            .makeToolbarButton().apply { toolTipText = l10n("ui.styling.addContentStyleTooltip") }
        val addLetterStyleButton = JButton(SVGIcon.Dual(ADD_ICON, LETTERS_ICON))
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
            val copiedStyle = when (val style = stylingTree.getSelectedListElement() as Style?) {
                null -> return@addActionListener
                is Global, is TextDecoration -> throw IllegalStateException()  // can never happen
                is PageStyle -> style.copy(name = l10n("ui.styling.copiedStyleName", style.name))
                is ContentStyle -> style.copy(name = l10n("ui.styling.copiedStyleName", style.name))
                is LetterStyle -> style.copy(name = l10n("ui.styling.copiedStyleName", style.name))
            }
            stylingTree.addListElement(copiedStyle, select = true)
            onChange()
        }
        removeStyleButton.addActionListener {
            if (stylingTree.removeSelectedListElement())
                onChange()
        }

        // Add a keyboard shortcut for the style removal button.
        removeStyleButton.toolTipText += " (${getKeyText(VK_DELETE)})"
        stylingTree.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(VK_DELETE, 0), "remove")
        stylingTree.actionMap.put("remove", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                removeStyleButton.actionListeners[0].actionPerformed(null)
            }
        })

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

    init {
        globalForm.changeListeners += { widget ->
            stylingTree.setSingleton(globalForm.save())
            onChange(widget)
        }
        pageStyleForm.changeListeners += { widget ->
            stylingTree.updateSelectedListElement(pageStyleForm.save())
            onChange(widget)
        }
        contentStyleForm.changeListeners += { widget ->
            stylingTree.updateSelectedListElement(contentStyleForm.save())
            onChange(widget)
        }
    }

    private fun openBlank() {
        openedForm = null
        rightPanelCards.show(rightPanel, "Blank")
    }

    private fun openGlobal(global: Global) {
        globalForm.open(global)
        postOpenForm("Global", globalForm)
    }

    private fun openPageStyle(style: PageStyle) {
        pageStyleForm.open(style)
        postOpenForm("PageStyle", pageStyleForm)
    }

    private fun openContentStyle(style: ContentStyle) {
        contentStyleForm.open(style)
        postOpenForm("ContentStyle", contentStyleForm)
    }

    private fun openLetterStyle(style: LetterStyle) {
        var oldName = style.name
        letterStyleForm.open(style)
        letterStyleForm.changeListeners.clear()
        letterStyleForm.changeListeners += { widget ->
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

            onChange(widget)
        }
        postOpenForm("LetterStyle", letterStyleForm)
    }

    private fun postOpenForm(cardName: String, form: StyleForm<*>) {
        openedForm = form
        openCounter++
        adjustOpenedForm()
        rightPanelCards.show(rightPanel, cardName)
        // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
        (form.parent.parent as JScrollPane).verticalScrollBar.value = 0
    }

    fun setStyling(styling: Styling) {
        this.styling = styling

        stylingTree.setSingleton(styling.global)
        stylingTree.replaceAllListElements(styling.pageStyles + styling.contentStyles + styling.letterStyles)
        refreshConstraintViolations()

        // Simulate the user selecting the node which is already selected currently. This triggers a callback
        // which then updates the right panel. If the node is a style node, that callback will also in turn call
        // postOpenForm(), which will in turn call adjustOpenedForm().
        stylingTree.triggerOnSelectOrOnDeselect()
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        letterStyleForm.setProjectFontFamilies(projectFamilies)
    }

    private fun onChange(widget: Form.Widget<*>? = null) {
        val styling = Styling(
            stylingTree.getSingleton(Global::class.java),
            stylingTree.getList(PageStyle::class.java).toImmutableList(),
            stylingTree.getList(ContentStyle::class.java).toImmutableList(),
            stylingTree.getList(LetterStyle::class.java).toImmutableList(),
        )
        this.styling = styling

        refreshConstraintViolations()
        adjustOpenedForm()
        ctrl.stylingHistory.editedAndRedraw(styling, Pair(widget, openCounter))
    }

    fun updateProject(project: Project?) {
        updateUnusedStyles(project)
    }

    private fun refreshConstraintViolations() {
        constraintViolations = verifyConstraints(ctrl.stylingCtx, styling ?: return)

        val severityPerStyle = HashMap<Style, Severity>()
        for (violation in constraintViolations)
            severityPerStyle[violation.rootStyle] =
                maxOf(violation.severity, severityPerStyle.getOrDefault(violation.rootStyle, Severity.values()[0]))

        stylingTree.adjustAppearance(getExtraIcons = { style ->
            val severity = severityPerStyle[style]
            if (severity == null || severity == Severity.INFO) emptyList() else listOf(severity.icon)
        })
    }

    private fun adjustOpenedForm() {
        adjustForm(openedForm ?: return)
    }

    private fun adjustForm(curForm: StyleForm<*>) {
        val styling = this.styling ?: return

        val curStyle = curForm.save()

        curForm.clearIssues()
        for (violation in constraintViolations)
            if (violation.leafStyle == curStyle) {
                val issue = Form.Notice(violation.severity, violation.msg)
                curForm.showIssueIfMoreSevere(violation.leafSetting, violation.leafIndex, issue)
            }

        for (constr in getStyleConstraints(curStyle.javaClass))
            if (constr is DynChoiceConstr<Style, *>) {
                val choices = constr.choices(ctrl.stylingCtx, styling, curStyle)
                for (setting in constr.settings)
                    curForm.setChoices(setting, choices)
            } else if (constr is FontFeatureConstr) {
                val availableTags = constr.getAvailableTags(ctrl.stylingCtx, styling, curStyle)
                for (setting in constr.settings)
                    curForm.setChoices(setting, availableTags.toList())
            }

        for (spec in getStyleWidgetSpecs(curStyle.javaClass))
            if (spec is ToggleButtonGroupWidgetSpec<Style, *>) {
                fun <V : Enum<*>> makeToIcon(spec: ToggleButtonGroupWidgetSpec<Style, V>): ((V) -> Icon)? =
                    spec.getIcon?.let { return fun(item: V) = it(ctrl.stylingCtx, styling, curStyle, item) }

                val toIcon = makeToIcon(spec)
                if (toIcon != null)
                    for (setting in spec.settings)
                        curForm.setToIconFun(setting, toIcon)
            } else if (spec is TimecodeWidgetSpec) {
                val fps = spec.getFPS(ctrl.stylingCtx, styling, curStyle)
                val timecodeFormat = spec.getTimecodeFormat(ctrl.stylingCtx, styling, curStyle)
                for (setting in spec.settings)
                    curForm.setTimecodeFPSAndFormat(setting, fps, timecodeFormat)
            }

        for (nestedForm in curForm.getNestedForms())
            adjustForm(nestedForm)
    }

    private fun updateUnusedStyles(project: Project?) {
        val unusedStyles = HashSet<Style>()

        if (project != null) {
            val styling = project.styling

            // Mark all styles as unused. Next, we will gradually remove all styles which are actually used.
            unusedStyles.addAll(styling.pageStyles)
            unusedStyles.addAll(styling.contentStyles)
            unusedStyles.addAll(styling.letterStyles)

            for (contentStyle in styling.contentStyles) {
                // Remove the content style's body letter style.
                styling.letterStyles.find { it.name == contentStyle.bodyLetterStyleName }?.let(unusedStyles::remove)
                // If the content style supports heads, remove its head letter style.
                if (contentStyle.hasHead)
                    styling.letterStyles.find { it.name == contentStyle.headLetterStyleName }?.let(unusedStyles::remove)
                // If the content style supports heads, remove its tail letter style.
                if (contentStyle.hasTail)
                    styling.letterStyles.find { it.name == contentStyle.tailLetterStyleName }?.let(unusedStyles::remove)
            }

            for (page in project.pages)
                for (stage in page.stages) {
                    // Remove the stage's page style.
                    unusedStyles -= stage.style
                    for (segment in stage.segments)
                        for (spine in segment.spines)
                            for (block in spine.blocks) {
                                // Remove the block's content style.
                                unusedStyles -= block.style
                                // Remove the head's letter styles.
                                for ((_, letterStyle) in block.head.orEmpty())
                                    unusedStyles -= letterStyle
                                // Remove the tail's letter styles.
                                for ((_, letterStyle) in block.tail.orEmpty())
                                    unusedStyles -= letterStyle
                                // Remove the body's letter styles.
                                for (bodyElem in block.body)
                                    if (bodyElem is BodyElement.Str)
                                        for ((_, letterStyle) in bodyElem.str)
                                            unusedStyles -= letterStyle
                            }
                }
        }

        stylingTree.adjustAppearance(isGrayedOut = unusedStyles::contains)
    }

}
