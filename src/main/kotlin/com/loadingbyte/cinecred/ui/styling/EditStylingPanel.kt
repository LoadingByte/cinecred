package com.loadingbyte.cinecred.ui.styling

import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE
import com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toPersistentList
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.util.*
import javax.swing.*


class EditStylingPanel(private val ctrl: ProjectController) : JPanel() {

    // ========== HINT OWNERS ==========
    val stylingTreeHintOwner: Component
    // =================================

    private val keyListeners = mutableListOf<KeyListener>()

    fun onKeyEvent(event: KeyEvent): Boolean =
        keyListeners.any { it.onKeyEvent(event) }

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

    // Cache the styling which is currently stored in the styling tree as well as its constraint violations and unused
    // styles, so that we don't have to repeatedly regenerate these three things.
    private var styling: Styling? = null
    private var constraintViolations: List<ConstraintViolation> = emptyList()
    private var unusedStyles: Set<NamedStyle> = emptySet()

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
            onSelect = { openGlobal(it, globalForm, "Global") }
        )
        stylingTree.addListType(
            PageStyle::class.java, l10n("ui.styling.pageStyles"), FILMSTRIP_ICON,
            onSelect = { openNamedStyle(it, pageStyleForm, "PageStyle") },
            objToString = PageStyle::name
        )
        stylingTree.addListType(
            ContentStyle::class.java, l10n("ui.styling.contentStyles"), LAYOUT_ICON,
            onSelect = { openNamedStyle(it, contentStyleForm, "ContentStyle") },
            objToString = ContentStyle::name
        )
        stylingTree.addListType(
            LetterStyle::class.java, l10n("ui.styling.letterStyles"), LETTERS_ICON,
            onSelect = { openNamedStyle(it, letterStyleForm, "LetterStyle") },
            objToString = LetterStyle::name
        )

        fun makeToolbarBtn(
            name: String, icon: Icon, shortcutKeyCode: Int = -1, shortcutModifiers: Int = 0, listener: () -> Unit
        ): JButton {
            var ttip = l10n("ui.styling.$name")
            if (shortcutKeyCode != -1) {
                ttip += " (${getModifiersExText(shortcutModifiers)}+${getKeyText(shortcutKeyCode)})"
                keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers, listener))
            }
            return JButton(icon).apply {
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
                isFocusable = false
                toolTipText = ttip
                addActionListener { listener() }
            }
        }

        // Add buttons for adding and removing style nodes.
        val addPageStyleButton = makeToolbarBtn(
            "addPageStyleTooltip", SVGIcon.Dual(ADD_ICON, FILMSTRIP_ICON), VK_P, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName")), true)
            onChange()
        }
        val addContentStyleButton = makeToolbarBtn(
            "addContentStyleTooltip", SVGIcon.Dual(ADD_ICON, LAYOUT_ICON), VK_C, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName")), true)
            onChange()
        }
        val addLetterStyleButton = makeToolbarBtn(
            "addLetterStyleTooltip", SVGIcon.Dual(ADD_ICON, LETTERS_ICON), VK_L, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_LETTER_STYLE.copy(name = l10n("ui.styling.newLetterStyleName")), true)
            onChange()
        }
        val duplicateStyleButton = makeToolbarBtn("duplicateStyleTooltip", DUPLICATE_ICON, listener = ::duplicateStyle)
        val removeStyleButton = makeToolbarBtn("removeStyleTooltip", TRASH_ICON, listener = ::removeStyle)

        // Add contextual keyboard shortcuts for the style duplication and removal buttons.
        duplicateStyleButton.toolTipText += " (${getModifiersExText(CTRL_DOWN_MASK)}+${getKeyText(VK_D)})"
        removeStyleButton.toolTipText += " (${getKeyText(VK_DELETE)})"
        stylingTree.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).apply {
            put(KeyStroke.getKeyStroke(VK_D, CTRL_DOWN_MASK), "duplicate")
            put(KeyStroke.getKeyStroke(VK_DELETE, 0), "remove")
        }
        stylingTree.actionMap.put("duplicate", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                duplicateStyle()
            }
        })
        stylingTree.actionMap.put("remove", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                removeStyle()
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

    private fun duplicateStyle() {
        val style = stylingTree.getSelected()
        if (style !is NamedStyle)
            return
        val newName = l10n("ui.styling.copiedStyleName", style.name)
        var copiedStyle = style.copy(NamedStyle::name.st().notarize(newName))
        val updates = ensureConsistency(ctrl.stylingCtx, stylingTree.getList(style.javaClass) + copiedStyle)
        for ((oldStyle, newStyle) in updates)
            if (oldStyle === copiedStyle) copiedStyle = newStyle else stylingTree.updateListElement(oldStyle, newStyle)
        stylingTree.addListElement(copiedStyle, select = true)
        onChange()
    }

    private fun removeStyle() {
        val removedStyle = stylingTree.removeSelectedListElement(selectNext = true)
        if (removedStyle != null) {
            removedStyle as NamedStyle
            val updates = ensureConsistencyAfterRemoval(stylingTree.getList(removedStyle.javaClass), removedStyle)
            updates.forEach(stylingTree::updateListElement)
            onChange()
        }
    }

    private fun openBlank() {
        openedForm = null
        rightPanelCards.show(rightPanel, "Blank")
    }

    private fun openGlobal(style: Global, form: StyleForm<Global>, cardName: String) {
        // Strictly speaking, we wouldn't need to recreate the change listener each time the form is opened, but we do
        // it here nevertheless for symmetry with the openNamedStyle() method.
        form.changeListeners.clear()
        form.changeListeners.add { widget ->
            val newStyle = form.save()
            stylingTree.setSingleton(newStyle)
            onChange(widget)
        }
        openStyle(style, form, cardName)
    }

    private fun <S : NamedStyle> openNamedStyle(style: S, form: StyleForm<S>, cardName: String) {
        val consistencyRetainer = StylingConsistencyRetainer(ctrl.stylingCtx, styling!!, unusedStyles, style)
        form.changeListeners.clear()
        form.changeListeners.add { widget ->
            val newStyle = form.save()
            stylingTree.updateSelectedListElement(newStyle)
            val newStyling = buildStyling()
            val updates = consistencyRetainer.ensureConsistencyAfterEdit(ctrl.stylingCtx, newStyling, newStyle)
            if (updates.isEmpty()) {
                // Take a shortcut to avoid generating the Styling object a second time.
                onChange(widget, newStyling)
                return@add
            }
            updates.forEach(stylingTree::updateListElement)
            updates[newStyle]?.let { updatedNewStyle -> form.open(newStyle.javaClass.cast(updatedNewStyle)) }
            onChange(widget)
        }
        openStyle(style, form, cardName)
    }

    private fun <S : Style> openStyle(style: S, form: StyleForm<S>, cardName: String) {
        form.open(style)
        openedForm = form
        openCounter++
        adjustOpenedForm()
        rightPanelCards.show(rightPanel, cardName)
        // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
        (form.parent.parent as JScrollPane).verticalScrollBar.value = 0
    }

    private fun buildStyling() = Styling(
        stylingTree.getSingleton(Global::class.java),
        stylingTree.getList(PageStyle::class.java).toPersistentList(),
        stylingTree.getList(ContentStyle::class.java).toPersistentList(),
        stylingTree.getList(LetterStyle::class.java).toPersistentList(),
    )

    fun setStyling(styling: Styling) {
        this.styling = styling

        stylingTree.setSingleton(styling.global)
        stylingTree.replaceAllListElements(NamedStyle.CLASSES.flatMap { styling.getNamedStyles(it) })
        refreshConstraintViolations()

        // Simulate the user selecting the node which is already selected currently. This triggers a callback
        // which then updates the right panel. If the node is a style node, that callback will also in turn call
        // postOpenForm(), which will in turn call adjustOpenedForm().
        stylingTree.triggerOnSelectOrOnDeselect()
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        letterStyleForm.setProjectFontFamilies(projectFamilies)
        if (openedForm === letterStyleForm) {
            refreshConstraintViolations()
            adjustOpenedForm()
        }
    }

    private fun onChange(widget: Form.Widget<*>? = null, styling: Styling = buildStyling()) {
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

        val severityPerStyle = IdentityHashMap<Style, Severity>()
        for (violation in constraintViolations)
            severityPerStyle[violation.rootStyle] =
                maxOf(violation.severity, severityPerStyle.getOrDefault(violation.rootStyle, Severity.values()[0]))

        stylingTree.adjustAppearance(getExtraIcons = { style ->
            val severity = severityPerStyle[style]
            if (severity == null || severity == Severity.INFO) emptyList() else listOf(severity.icon)
        })
    }

    private fun adjustOpenedForm() {
        val curStyle = (stylingTree.getSelected() ?: return) as Style
        adjustForm((openedForm ?: return).castToStyle(curStyle.javaClass), curStyle)
    }

    private fun <S : Style> adjustForm(curForm: StyleForm<S>, curStyle: S) {
        val styling = this.styling ?: return

        curForm.setIneffectiveSettings(findIneffectiveSettings(ctrl.stylingCtx, styling, curStyle))

        curForm.clearIssues()
        for (violation in constraintViolations)
            if (violation.leafStyle == curStyle) {
                val issue = Form.Notice(violation.severity, violation.msg)
                curForm.showIssueIfMoreSevere(violation.leafSetting, violation.leafSubjectIndex, issue)
            }

        for (constr in getStyleConstraints(curStyle.javaClass)) when (constr) {
            is DynChoiceConstr<S, *> -> {
                val choices = constr.choices(ctrl.stylingCtx, styling, curStyle).toList()
                for (setting in constr.settings)
                    curForm.setChoices(setting, choices)
            }
            is StyleNameConstr<S, *> -> {
                val choiceSet = constr.choices(ctrl.stylingCtx, styling, curStyle).mapTo(TreeSet(), NamedStyle::name)
                if (constr.clustering)
                    choiceSet.remove((curStyle as NamedStyle).name)
                val choices = choiceSet.toList()
                for (setting in constr.settings)
                    curForm.setChoices(setting, choices)
            }
            is FontFeatureConstr -> {
                val availableTags = constr.getAvailableTags(ctrl.stylingCtx, styling, curStyle).toList()
                for (setting in constr.settings)
                    curForm.setChoices(setting, availableTags, unique = true)
            }
            else -> {}
        }

        for (spec in getStyleWidgetSpecs(curStyle.javaClass)) when (spec) {
            is ToggleButtonGroupWidgetSpec<S, *> -> {
                fun <SUBJ : Enum<*>> makeToIcon(spec: ToggleButtonGroupWidgetSpec<S, SUBJ>): ((SUBJ) -> Icon)? =
                    spec.getIcon?.let { return fun(item: SUBJ) = it(ctrl.stylingCtx, styling, curStyle, item) }

                val toIcon = makeToIcon(spec)
                if (toIcon != null)
                    for (setting in spec.settings)
                        curForm.setToIconFun(setting, toIcon)
            }
            is TimecodeWidgetSpec -> {
                val fps = spec.getFPS(ctrl.stylingCtx, styling, curStyle)
                val timecodeFormat = spec.getTimecodeFormat(ctrl.stylingCtx, styling, curStyle)
                for (setting in spec.settings)
                    curForm.setTimecodeFPSAndFormat(setting, fps, timecodeFormat)
            }
            else -> {}
        }

        for ((nestedForm, nestedStyle) in curForm.getNestedFormsAndStyles(curStyle))
            adjustForm(nestedForm.castToStyle(nestedStyle.javaClass), nestedStyle)
    }

    private fun updateUnusedStyles(project: Project?) {
        val unusedStyles = Collections.newSetFromMap(IdentityHashMap<NamedStyle, Boolean>())

        if (project != null) {
            val styling = project.styling

            // Mark all styles as unused. Next, we will gradually remove all styles which are actually used.
            for (styleClass in NamedStyle.CLASSES)
                unusedStyles.addAll(styling.getNamedStyles(styleClass))

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
                                    when (bodyElem) {
                                        is BodyElement.Nil -> unusedStyles -= bodyElem.sty
                                        is BodyElement.Str -> for ((_, letSty) in bodyElem.str) unusedStyles -= letSty
                                        is BodyElement.Pic -> {}
                                    }
                            }
                }
        }

        stylingTree.adjustAppearance(isGrayedOut = unusedStyles::contains)
        this.unusedStyles = unusedStyles
    }

}
