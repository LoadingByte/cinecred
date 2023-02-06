package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toPersistentList
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.util.*
import javax.swing.*
import kotlin.math.max


class EditStylingPanel(private val ctrl: ProjectController) : JPanel() {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedSplitPane: JSplitPane
    @Deprecated("ENCAPSULATION LEAK") val leakedStylingTree get() = stylingTree
    @Deprecated("ENCAPSULATION LEAK") val leakedGlobalForm get() = globalForm
    @Deprecated("ENCAPSULATION LEAK") val leakedPageStyleForm get() = pageStyleForm
    @Deprecated("ENCAPSULATION LEAK") val leakedContentStyleForm get() = contentStyleForm
    @Deprecated("ENCAPSULATION LEAK") val leakedLetterStyleForm get() = letterStyleForm
    // =========================================

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

    // Cache the styling which is currently stored in the styling tree as well as its constraint violations,
    // so that we don't have to repeatedly regenerate these three things.
    private var styling: Styling? = null
    private var constraintViolations: List<ConstraintViolation> = emptyList()

    // Keep track of the form which is currently open.
    private var openedForm: StyleForm<*>? = null

    // We increase this counter each time a new form is opened. It is used to tell apart multiple edits
    // of the same widget but in different styles.
    private var openCounter = 0

    // Remember the runtime of the latest render of the project.
    private var mostRecentRuntime = 0

    init {
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

        // Add buttons for adding and removing style nodes.
        val addPageStyleButton = newToolbarButtonWithKeyListener(
            SVGIcon.Dual(ADD_ICON, FILMSTRIP_ICON), l10n("ui.styling.addPageStyleTooltip"),
            VK_P, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName")), true)
            onChange()
        }
        val addContentStyleButton = newToolbarButtonWithKeyListener(
            SVGIcon.Dual(ADD_ICON, LAYOUT_ICON), l10n("ui.styling.addContentStyleTooltip"),
            VK_C, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName")), true)
            onChange()
        }
        val addLetterStyleButton = newToolbarButtonWithKeyListener(
            SVGIcon.Dual(ADD_ICON, LETTERS_ICON), l10n("ui.styling.addLetterStyleTooltip"),
            VK_L, CTRL_DOWN_MASK or SHIFT_DOWN_MASK
        ) {
            stylingTree.addListElement(PRESET_LETTER_STYLE.copy(name = l10n("ui.styling.newLetterStyleName")), true)
            onChange()
        }
        val duplicateStyleButton = newToolbarButton(
            DUPLICATE_ICON, l10n("ui.styling.duplicateStyleTooltip"), VK_D, CTRL_DOWN_MASK, ::duplicateStyle
        )
        val removeStyleButton = newToolbarButton(
            TRASH_ICON, l10n("ui.styling.removeStyleTooltip"), VK_DELETE, 0, ::removeStyle
        )

        // Add contextual keyboard shortcuts for the style duplication and removal buttons.
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

        // Layout the buttons.
        val buttonPanel = JPanel(WrappingToolbarLayout(gap = 6)).apply {
            add(addPageStyleButton)
            add(addContentStyleButton)
            add(addLetterStyleButton)
            add(duplicateStyleButton)
            add(removeStyleButton)
        }

        // Layout the tree and the button panel.
        val leftPanel = JPanel(BorderLayout(0, 6)).apply {
            add(buttonPanel, BorderLayout.NORTH)
            add(JScrollPane(stylingTree), BorderLayout.CENTER)
            minimumSize = Dimension(100, 0)
        }

        // Put everything together in a split pane.
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftPanel, rightPanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater { splitPane.setDividerLocation(0.25) }

        layout = MigLayout()
        add(splitPane, "grow, push")

        @Suppress("DEPRECATION")
        leakedSplitPane = splitPane
    }

    private fun newToolbarButtonWithKeyListener(
        icon: Icon,
        tooltip: String,
        shortcutKeyCode: Int,
        shortcutModifiers: Int,
        listener: () -> Unit
    ): JButton {
        keyListeners.add(KeyListener(shortcutKeyCode, shortcutModifiers, listener))
        return newToolbarButton(icon, tooltip, shortcutKeyCode, shortcutModifiers, listener)
    }

    private fun duplicateStyle() {
        val style = stylingTree.getSelected()
        if (style !is NamedStyle)
            return
        val newName = "${style.name} (${l10n("ui.styling.copiedStyleNameSuffix")})"
        var copiedStyle = style.copy(NamedStyle::name.st().notarize(newName))
        if (copiedStyle is LetterStyle && !copiedStyle.inheritLayersFromStyle.isActive)
            copiedStyle = copiedStyle.copy(inheritLayersFromStyle = Opt(true, style.name))
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
            var newStyle = form.save()
            // If the global runtime setting is 0 and has now been activated, initialize the corresponding timecode
            // spinner with the project's current runtime. This is to provide a good starting value for the user.
            if (newStyle.runtimeFrames.run { isActive && value == 0 }) {
                form.openSingleSetting(Global::runtimeFrames.st(), Opt(true, mostRecentRuntime))
                newStyle = form.save()
            }
            stylingTree.setSingleton(newStyle)
            onChange(widget)
        }
        openStyle(style, form, cardName)
    }

    private fun <S : NamedStyle> openNamedStyle(style: S, form: StyleForm<S>, cardName: String) {
        val consistencyRetainer = StylingConsistencyRetainer(ctrl.stylingCtx, styling!!, style)
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

    fun updateProject(drawnProject: DrawnProject?) {
        val project = drawnProject?.project
        val usedStyles = if (project == null) emptySet() else findUsedStyles(project)
        stylingTree.adjustAppearance(isGrayedOut = { project != null && it is NamedStyle && it !in usedStyles })
        mostRecentRuntime = drawnProject?.video?.numFrames ?: 0
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
            is SiblingOrdinalConstr<*, *> -> {
                fun <S : NestedStyle<P>, P : Style> withNestedTypeVars(curStyle: S) {
                    @Suppress("UNCHECKED_CAST")
                    constr as SiblingOrdinalConstr<S, P>
                    val choices = constr.siblings.get(styling.getParentStyle(curStyle))
                        .mapIndexedNotNull { idx, sibling -> if (sibling === curStyle) null else idx + 1 }
                    for (setting in constr.settings)
                        curForm.setChoices(setting, choices)
                }
                withNestedTypeVars(curStyle as NestedStyle<*>)
            }
            else -> {}
        }

        for (spec in getStyleWidgetSpecs(curStyle.javaClass)) when (spec) {
            is ToggleButtonGroupWidgetSpec<S, *> -> {
                fun <SUBJ : Any> makeToIcon(spec: ToggleButtonGroupWidgetSpec<S, SUBJ>): ((SUBJ) -> Icon)? =
                    spec.getDynIcon?.let { return fun(item: SUBJ) = it(ctrl.stylingCtx, styling, curStyle, item) }

                val toIcon = makeToIcon(spec)
                if (toIcon != null)
                    for (setting in spec.settings)
                        curForm.setToIconFun(setting, toIcon)
            }
            is MultiplierWidgetSpec -> {
                val multiplier = spec.getMultiplier(ctrl.stylingCtx, styling, curStyle)
                for (setting in spec.settings)
                    curForm.setMultiplier(setting, multiplier)
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


    /**
     * A wrapping LayoutManager similar to FlowLayout. In contrast to FlowLayout, this implementation increases the
     * minimum and preferred height of the container when wrapping occurs.
     */
    private class WrappingToolbarLayout(private val gap: Int) : LayoutManager {

        override fun addLayoutComponent(name: String, comp: Component) {}
        override fun removeLayoutComponent(comp: Component) {}
        override fun minimumLayoutSize(parent: Container) = preferredLayoutSize(parent)

        override fun preferredLayoutSize(parent: Container): Dimension {
            val rows = flowIntoRows(parent)
            val height = rows.sumOf(Row::height) + gap * (rows.size - 1)
            val insets = parent.insets
            return Dimension(parent.width + insets.left + insets.right, height + insets.top + insets.bottom)
        }

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            var y = insets.top
            for (row in flowIntoRows(parent)) {
                var x = insets.left + (parent.width - row.width) / 2
                for (comp in row.comps) {
                    val pref = comp.preferredSize
                    comp.setBounds(x, y, pref.width, pref.height)
                    x += pref.width + gap
                }
                y += row.height + gap
            }
        }

        private fun flowIntoRows(parent: Container): List<Row> {
            val rows = mutableListOf<Row>()

            var rowComps = mutableListOf<Component>()
            var rowWidth = 0
            var rowHeight = 0
            for (idx in 0 until parent.componentCount) {
                val comp = parent.getComponent(idx)
                val pref = comp.preferredSize
                if (rowWidth == 0 || rowWidth + gap + pref.width <= parent.width) {
                    rowComps.add(comp)
                    if (rowWidth != 0) rowWidth += gap
                    rowWidth += pref.width
                    rowHeight = max(rowHeight, pref.height)
                } else {
                    rows.add(Row(rowComps, rowWidth, rowHeight))
                    rowComps = mutableListOf(comp)
                    rowWidth = pref.width
                    rowHeight = pref.height
                }
            }
            if (rowComps.isNotEmpty())
                rows.add(Row(rowComps, rowWidth, rowHeight))

            return rows
        }

        private class Row(val comps: List<Component>, val width: Int, val height: Int)

    }

}
