package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.toFPS
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.*
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

    private val globalNode = DefaultMutableTreeNode(l10n("ui.styling.globalStyling"), false)
    private val pageStylesNode = DefaultMutableTreeNode(l10n("ui.styling.pageStyles"), true)
    private val contentStylesNode = DefaultMutableTreeNode(l10n("ui.styling.contentStyles"), true)
    private val treeModel: DefaultTreeModel
    private val tree: JTree
    private var disableTreeSelectionListener = false

    init {
        // Create a panel with the three style editing forms.
        val rightPanelCards = CardLayout()
        val rightPanel = JPanel(rightPanelCards).apply {
            add(JPanel(), "Blank")
            add(JScrollPane(GlobalForm), "Global")
            add(JScrollPane(PageStyleForm), "PageStyle")
            add(JScrollPane(ContentStyleForm), "ContentStyle")
        }

        // Create the tree that contains a node for the global settings, as well as nodes for the page & content styles.
        val rootNode = DefaultMutableTreeNode().apply {
            add(globalNode)
            add(pageStylesNode)
            add(contentStylesNode)
        }
        treeModel = DefaultTreeModel(rootNode, true)
        tree = object : JTree(treeModel) {
            override fun convertValueToText(
                value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) = when (val userObject = (value as DefaultMutableTreeNode?)?.userObject) {
                is PageStyle -> userObject.name
                is ContentStyle -> userObject.name
                else -> userObject?.toString() ?: ""
            }
        }.apply {
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }
        // The page and content style "folders" must not be collapsed by the user.
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {}
            override fun treeWillCollapse(event: TreeExpansionEvent) {
                throw ExpandVetoException(event)
            }
        })
        // Each node gets an icon depending on whether it stores the global settings, a page style, or a content style.
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as DefaultMutableTreeNode
                when {
                    node == globalNode -> icon = GLOBE_ICON
                    node.userObject is PageStyle -> icon = FILMSTRIP_ICON
                    node.userObject is ContentStyle -> icon = LAYOUT_ICON
                }
                return this
            }
        }
        // When the user selects the global settings node or a page or content style node, open the corresponding form.
        tree.addTreeSelectionListener {
            if (disableTreeSelectionListener)
                return@addTreeSelectionListener
            val selectedNode = tree.lastSelectedPathComponent as DefaultMutableTreeNode?
            val selectedNodeUserObject = selectedNode?.userObject
            // Otherwise, open form corresponding to the selected node.
            var scrollPane: JScrollPane? = null
            when {
                selectedNode == globalNode -> {
                    scrollPane = GlobalForm.parent.parent as JScrollPane
                    GlobalForm.openGlobal(global!!, changeCallback = { global = it; onChange() })
                    rightPanelCards.show(rightPanel, "Global")
                }
                selectedNodeUserObject is PageStyle -> {
                    scrollPane = PageStyleForm.parent.parent as JScrollPane
                    PageStyleForm.openPageStyle(
                        selectedNodeUserObject,
                        changeCallback = { selectedNode.userObject = it; onChange(); sortNode(selectedNode) }
                    )
                    rightPanelCards.show(rightPanel, "PageStyle")
                }
                selectedNodeUserObject is ContentStyle -> {
                    scrollPane = ContentStyleForm.parent.parent as JScrollPane
                    ContentStyleForm.openContentStyle(
                        selectedNodeUserObject,
                        changeCallback = { selectedNode.userObject = it; onChange(); sortNode(selectedNode) }
                    )
                    rightPanelCards.show(rightPanel, "ContentStyle")
                }
                else ->
                    rightPanelCards.show(rightPanel, "Blank")
            }
            // When the user selected a non-blank card, reset the vertical scrollbar position to the top.
            // Note that we have to delay this change because for some reason, if we don't, the change has no effect.
            SwingUtilities.invokeLater { scrollPane?.verticalScrollBar?.value = 0 }
        }

        // Add buttons for adding and removing page and content style nodes.
        val addPageStyleButton = JButton(DualSVGIcon(ADD_ICON, FILMSTRIP_ICON))
            .apply { toolTipText = l10n("ui.styling.addPageStyleTooltip") }
        val addContentStyleButton = JButton(DualSVGIcon(ADD_ICON, LAYOUT_ICON))
            .apply { toolTipText = l10n("ui.styling.addContentStyleTooltip") }
        val removeButton = JButton(REMOVE_ICON)
            .apply { toolTipText = l10n("ui.styling.removeStyleTooltip") }
        addPageStyleButton.addActionListener {
            insertAndSelectSortedLeaf(
                pageStylesNode,
                STANDARD_PAGE_STYLE.copy(name = l10n("ui.styling.newPageStyleName"))
            )
            onChange()
        }
        addContentStyleButton.addActionListener {
            insertAndSelectSortedLeaf(
                contentStylesNode,
                STANDARD_CONTENT_STYLE.copy(name = l10n("ui.styling.newContentStyleName"))
            )
            onChange()
        }
        removeButton.addActionListener {
            val selectedNode = (tree.lastSelectedPathComponent ?: return@addActionListener) as DefaultMutableTreeNode
            if (selectedNode.userObject is PageStyle || selectedNode.userObject is ContentStyle) {
                treeModel.removeNodeFromParent(selectedNode)
                onChange()
            }
        }

        // Layout the tree and the buttons.
        val leftPanel = JPanel(MigLayout()).apply {
            add(addPageStyleButton, "split, grow")
            add(addContentStyleButton, "grow")
            add(removeButton, "grow")
            add(JScrollPane(tree), "newline, grow, push")
        }

        // Put everything together in a split pane.
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftPanel, rightPanel)
        // Slightly postpone moving the dividers so that the panes know their height when the dividers are moved.
        SwingUtilities.invokeLater { splitPane.setDividerLocation(0.25) }

        // Use BorderLayout to maximize the size of the split pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

    private fun sortNode(node: DefaultMutableTreeNode) {
        // Note: We temporarily disable the tree selection listener because the node removal and subsequent insertion
        // at another place should not close and re-open (and thus reset) the right-hand editing panel.
        disableTreeSelectionListener = true
        try {
            // We also remember the current selection path so that we can restore it later.
            val selectionPath = tree.selectionPath

            val parent = node.parent as DefaultMutableTreeNode
            treeModel.removeNodeFromParent(node)
            var idx = parent.children().asSequence().indexOfFirst {
                (it as DefaultMutableTreeNode).userObject.toString() > node.userObject.toString()
            }
            if (idx == -1)
                idx = parent.childCount
            treeModel.insertNodeInto(node, parent, idx)

            tree.selectionPath = selectionPath
        } finally {
            disableTreeSelectionListener = false
        }
    }

    private fun insertSortedLeaf(parent: MutableTreeNode, userObject: Any): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(userObject, false)
        treeModel.insertNodeInto(node, parent, 0)
        sortNode(node)
        return node
    }

    private fun insertAndSelectSortedLeaf(parent: MutableTreeNode, userObject: Any) {
        val leaf = insertSortedLeaf(parent, userObject)
        val leafPath = TreePath(leaf.path)
        tree.scrollPathToVisible(TreePath(leaf.path))
        tree.selectionPath = leafPath
    }

    fun onLoadStyling(initialStyling: Styling) {
        global = initialStyling.global

        for (node in pageStylesNode.children().toList() + contentStylesNode.children().toList())
            treeModel.removeNodeFromParent(node as MutableTreeNode)

        for (pageStyle in initialStyling.pageStyles)
            insertSortedLeaf(pageStylesNode, pageStyle)
        for (contentStyle in initialStyling.contentStyles)
            insertSortedLeaf(contentStylesNode, contentStyle)

        tree.expandPath(TreePath(pageStylesNode.path))
        tree.expandPath(TreePath(contentStylesNode.path))

        // First clear the current selection, which leads to the blank card showing in the right panel. This way, we
        // make sure that no stale data is shown in the right panel. Then initially select the global settings.
        tree.selectionRows = intArrayOf()
        tree.setSelectionRow(0)
    }

    fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
        ContentStyleForm.updateProjectFontFamilies(projectFamilies)
    }

    private fun onChange() {
        Controller.editStylingAndRedraw(Styling(
            global!!,
            pageStylesNode.children().toList().map { (it as DefaultMutableTreeNode).userObject as PageStyle },
            contentStylesNode.children().toList().map { (it as DefaultMutableTreeNode).userObject as ContentStyle }
        ))
    }

    private fun toDisplayString(enum: Enum<*>) =
        enum.name.replace('_', ' ').split(' ').joinToString(" ") { it.toLowerCase().capitalize() }


    private object GlobalForm : Form() {

        private val fpsComboBox = addComboBox(
            l10n("ui.styling.global.fps"), arrayOf("23.97", "24", "25", "29.97", "30", "59.94", "60"),
            verify = {
                try {
                    it?.toFPS() ?: throw VerifyResult(Severity.ERROR, l10n("general.blank"))
                } catch (_: IllegalArgumentException) {
                    throw VerifyResult(Severity.ERROR, l10n("ui.styling.global.fpsIllFormatted"))
                }
            }
        ).apply { isEditable = true }

        private val widthPxSpinner = addSpinner(l10n("ui.styling.global.widthPx"), SpinnerNumberModel(1, 1, null, 10))
        private val heightPxSpinner = addSpinner(l10n("ui.styling.global.heightPx"), SpinnerNumberModel(1, 1, null, 10))
        private val backgroundColorChooserButton = addColorChooserButton(l10n("ui.styling.global.background"))
        private val unitVGapPxSpinner = addSpinner(
            l10n("ui.styling.global.unitVGapPx"), SpinnerNumberModel(1f, 0.01f, null, 1f)
        )

        fun openGlobal(global: Global, changeCallback: (Global) -> Unit) {
            clearChangeListeners()

            fpsComboBox.selectedItem =
                if (global.fps.denominator == 1) global.fps.numerator.toString()
                else "%.2f".format(global.fps.frac)
            widthPxSpinner.value = global.widthPx
            heightPxSpinner.value = global.heightPx
            backgroundColorChooserButton.selectedColor = global.background
            unitVGapPxSpinner.value = global.unitVGapPx

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
            verify = { if (it.trim().isEmpty()) throw VerifyResult(Severity.ERROR, l10n("general.blank")) }
        )
        private val behaviorComboBox = addComboBox(
            l10n("ui.styling.page.behavior"), PageBehavior.values(),
            toString = ::toDisplayString
        )
        private val afterwardSlugFramesSpinner = addSpinner(
            l10n("ui.styling.page.afterwardSlugFrames"), SpinnerNumberModel(0, 0, null, 1)
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

        fun openPageStyle(pageStyle: PageStyle, changeCallback: (PageStyle) -> Unit) {
            clearChangeListeners()

            nameField.text = pageStyle.name
            afterwardSlugFramesSpinner.value = pageStyle.afterwardSlugFrames
            behaviorComboBox.selectedItem = pageStyle.behavior
            cardDurationFramesSpinner.value = pageStyle.cardDurationFrames
            cardFadeInFramesSpinner.value = pageStyle.cardFadeInFrames
            cardFadeOutFramesSpinner.value = pageStyle.cardFadeOutFrames
            scrollPxPerFrameSpinner.value = pageStyle.scrollPxPerFrame

            addChangeListener {
                if (isErrorFree) {
                    val newPageStyle = PageStyle(
                        nameField.text.trim(),
                        behaviorComboBox.selectedItem as PageBehavior,
                        afterwardSlugFramesSpinner.value as Int,
                        cardDurationFramesSpinner.value as Int,
                        cardFadeInFramesSpinner.value as Int,
                        cardFadeOutFramesSpinner.value as Int,
                        scrollPxPerFrameSpinner.value as Float
                    )
                    changeCallback(newPageStyle)
                }
            }
        }

    }


    private object ContentStyleForm : Form() {

        private fun isGridOrFlow(layout: Any?) = layout == BodyLayout.GRID || layout == BodyLayout.FLOW
        private fun isFlowOrParagraphs(layout: Any?) = layout == BodyLayout.FLOW || layout == BodyLayout.PARAGRAPHS

        private val nameField = addTextField(
            l10n("ui.styling.content.name"),
            verify = { if (it.trim().isEmpty()) throw VerifyResult(Severity.ERROR, l10n("general.blank")) }
        )
        private val vMarginPxSpinner = addSpinner(
            l10n("ui.styling.content.vMarginPx"), SpinnerNumberModel(0f, 0f, null, 1f)
        )
        private val centerOnComboBox = addComboBox(
            l10n("ui.styling.content.centerOn"), CenterOn.values(), toString = ::toDisplayString
        )
        private val spineDirComboBox = addComboBox(
            l10n("ui.styling.content.spineDir"), SpineDir.values(), toString = ::toDisplayString
        )

        private val bodyLayoutComboBox = addComboBox(
            l10n("ui.styling.content.bodyLayout"), BodyLayout.values(), toString = ::toDisplayString
        )
        private val bodyLayoutLineHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.lineHJustify"), LineHJustify.values(), toString = ::toDisplayString,
            isVisible = { isFlowOrParagraphs(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutColsHJustifyComboBox = addComboBoxList(
            l10n("ui.styling.content.colsHJustify"), HJustify.values(), toString = ::toDisplayString,
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.GRID },
        )
        private val bodyLayoutElemHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.elemHJustify"), HJustify.values(), toString = ::toDisplayString,
            isVisible = { bodyLayoutComboBox.selectedItem == BodyLayout.FLOW }
        )
        private val bodyLayoutElemVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.elemVJustify"), VJustify.values(), toString = ::toDisplayString,
            isVisible = { isGridOrFlow(bodyLayoutComboBox.selectedItem) }
        )
        private val bodyLayoutElemConformComboBox = addComboBox(
            l10n("ui.styling.content.elemConform"), BodyElementConform.values(), toString = ::toDisplayString,
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

        private val hasHeadCheckBox = addCheckBox(l10n("ui.styling.content.hasHead"))
        private val headHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.headHJustify"), HJustify.values(), toString = ::toDisplayString,
            isVisible = { hasHeadCheckBox.isSelected }
        )
        private val headVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.headVJustify"), VJustify.values(), toString = ::toDisplayString,
            isVisible = { hasHeadCheckBox.isSelected && spineDirComboBox.selectedItem == SpineDir.HORIZONTAL }
        )
        private val headGapPxSpinner = addSpinner(
            l10n("ui.styling.content.headGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = { hasHeadCheckBox.isSelected }
        )
        private val headFontSpecChooser = addFontSpecChooser(
            l10n("ui.styling.content.headFont"),
            isVisible = { hasHeadCheckBox.isSelected }
        )

        private val hasTailCheckBox = addCheckBox(l10n("ui.styling.content.hasTail"))
        private val tailHJustifyComboBox = addComboBox(
            l10n("ui.styling.content.tailHJustify"), HJustify.values(), toString = ::toDisplayString,
            isVisible = { hasTailCheckBox.isSelected }
        )
        private val tailVJustifyComboBox = addComboBox(
            l10n("ui.styling.content.tailVJustify"), VJustify.values(), toString = ::toDisplayString,
            isVisible = { hasTailCheckBox.isSelected && spineDirComboBox.selectedItem == SpineDir.HORIZONTAL }
        )
        private val tailGapPxSpinner = addSpinner(
            l10n("ui.styling.content.tailGapPx"), SpinnerNumberModel(0f, 0f, null, 1f),
            isVisible = { hasTailCheckBox.isSelected }
        )
        private val tailFontSpecChooser = addFontSpecChooser(
            l10n("ui.styling.content.tailFont"),
            isVisible = { hasTailCheckBox.isSelected }
        )

        fun updateProjectFontFamilies(projectFamilies: FontFamilies) {
            bodyFontSpecChooser.projectFamilies = projectFamilies
            headFontSpecChooser.projectFamilies = projectFamilies
            tailFontSpecChooser.projectFamilies = projectFamilies
        }

        fun openContentStyle(contentStyle: ContentStyle, changeCallback: (ContentStyle) -> Unit) {
            clearChangeListeners()

            nameField.text = contentStyle.name
            vMarginPxSpinner.value = contentStyle.vMarginPx
            spineDirComboBox.selectedItem = contentStyle.spineDir
            centerOnComboBox.selectedItem = contentStyle.centerOn
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

            addChangeListener {
                if (isErrorFree) {
                    val newContentStyle = ContentStyle(
                        nameField.text.trim(),
                        vMarginPxSpinner.value as Float,
                        centerOnComboBox.selectedItem as CenterOn,
                        spineDirComboBox.selectedItem as SpineDir,
                        bodyLayoutComboBox.selectedItem as BodyLayout,
                        bodyLayoutLineGapPxSpinner.value as Float,
                        bodyLayoutElemConformComboBox.selectedItem as BodyElementConform,
                        bodyLayoutElemVJustifyComboBox.selectedItem as VJustify,
                        bodyLayoutHorizontalGapPxSpinner.value as Float,
                        bodyLayoutColsHJustifyComboBox.selectedItems.filterNotNull(),
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
                    changeCallback(newContentStyle)
                }
            }
        }

    }

}
