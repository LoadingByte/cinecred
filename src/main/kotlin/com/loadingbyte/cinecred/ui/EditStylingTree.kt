package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.FILMSTRIP_ICON
import com.loadingbyte.cinecred.ui.helper.GLOBE_ICON
import com.loadingbyte.cinecred.ui.helper.LAYOUT_ICON
import java.awt.Component
import javax.swing.*
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.*


/**
 * A tree that contains a node for global settings, as well as nodes for page & content styles.
 */
object EditStylingTree : JTree(DefaultTreeModel(DefaultMutableTreeNode(), true)) {

    private val model = treeModel as DefaultTreeModel

    private val globalNode = DefaultMutableTreeNode(l10n("ui.styling.globalStyling"), false)
    private val pageStylesNode = DefaultMutableTreeNode(l10n("ui.styling.pageStyles"), true)
    private val contentStylesNode = DefaultMutableTreeNode(l10n("ui.styling.contentStyles"), true)

    private var disableSelectionListener = false

    init {
        // Create the base tree that contains a node for the global settings,
        // as well as nodes for the page & content styles.
        val rootNode = model.root as DefaultMutableTreeNode
        model.insertNodeInto(globalNode, rootNode, 0)
        model.insertNodeInto(pageStylesNode, rootNode, 1)
        model.insertNodeInto(contentStylesNode, rootNode, 2)

        isRootVisible = false
        setShowsRootHandles(false)
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        // The page and content style "folders" must not be collapsed by the user.
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {}
            override fun treeWillCollapse(event: TreeExpansionEvent) {
                throw ExpandVetoException(event)
            }
        })

        // Each node gets an icon depending on whether it stores the global settings, a page style, or a content style.
        setCellRenderer(object : DefaultTreeCellRenderer() {
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
        })

        // When the user selects the global settings node or a page or content style node, open the corresponding form.
        addTreeSelectionListener {
            if (disableSelectionListener)
                return@addTreeSelectionListener
            val selectedNode = lastSelectedPathComponent as DefaultMutableTreeNode?
            val selectedNodeUserObject = selectedNode?.userObject
            when {
                selectedNode == globalNode -> EditStylingPanel.openGlobal()
                selectedNodeUserObject is PageStyle -> EditStylingPanel.openPageStyle(selectedNodeUserObject)
                selectedNodeUserObject is ContentStyle -> EditStylingPanel.openContentStyle(selectedNodeUserObject)
                else -> EditStylingPanel.openBlank()
            }
        }
    }

    val pageStyles: List<PageStyle>
        get() = pageStylesNode.children().asSequence()
            .map { (it as DefaultMutableTreeNode).userObject as PageStyle }
            .toList()

    val contentStyles: List<ContentStyle>
        get() = contentStylesNode.children().asSequence()
            .map { (it as DefaultMutableTreeNode).userObject as ContentStyle }
            .toList()

    fun addPageStyle(pageStyle: PageStyle) {
        insertAndSelectSortedLeaf(pageStylesNode, pageStyle)
    }

    fun addContentStyle(contentStyle: ContentStyle) {
        insertAndSelectSortedLeaf(contentStylesNode, contentStyle)
    }

    fun duplicateSelectedStyle(): Boolean {
        when (val selectedObject = ((lastSelectedPathComponent ?: return false) as DefaultMutableTreeNode).userObject) {
            is PageStyle -> {
                insertAndSelectSortedLeaf(pageStylesNode, selectedObject.copy())
                return true
            }
            is ContentStyle -> {
                insertAndSelectSortedLeaf(contentStylesNode, selectedObject.copy())
                return true
            }
        }
        return false
    }

    fun removeSelectedStyle(): Boolean {
        val selectedNode = (lastSelectedPathComponent ?: return false) as DefaultMutableTreeNode
        if (selectedNode.userObject is PageStyle || selectedNode.userObject is ContentStyle) {
            model.removeNodeFromParent(selectedNode)
            return true
        }
        return false
    }

    fun updateSelectedStyle(newStyle: Any) {
        val selectedNode = (lastSelectedPathComponent ?: throw IllegalStateException()) as DefaultMutableTreeNode
        if (selectedNode.userObject is PageStyle || selectedNode.userObject is ContentStyle) {
            selectedNode.userObject = newStyle
            sortNode(selectedNode)
        } else
            throw IllegalStateException()
    }

    fun setStyling(styling: Styling) {
        val selectedObject = (lastSelectedPathComponent as DefaultMutableTreeNode?)?.userObject

        for (node in pageStylesNode.children().toList() + contentStylesNode.children().toList())
            model.removeNodeFromParent(node as MutableTreeNode)

        for (pageStyle in styling.pageStyles)
            insertSortedLeaf(pageStylesNode, pageStyle)
        for (contentStyle in styling.contentStyles)
            insertSortedLeaf(contentStylesNode, contentStyle)

        expandPath(TreePath(pageStylesNode.path))
        expandPath(TreePath(contentStylesNode.path))

        // First clear the current selection, which leads to the blank card showing in the right panel. This way, we
        // make sure that no stale data is shown in the right panel.
        selectionRows = intArrayOf()
        // The select the best matching node. If no node is matching, select the global settings.
        setSelectionRow(0)
        when (selectedObject) {
            is PageStyle -> selectBestMatchingNode(selectedObject, pageStylesNode) { it.name }
            is ContentStyle -> selectBestMatchingNode(selectedObject, contentStylesNode) { it.name }
        }
    }

    private inline fun <reified S> selectBestMatchingNode(obj: S, parent: TreeNode, getName: (S) -> String) {
        parent.children().asSequence().find { (it as DefaultMutableTreeNode).userObject == obj }?.let {
            selectionPath = TreePath((it as DefaultMutableTreeNode).path)
            return
        }
        parent.children().asSequence().find {
            getName((it as DefaultMutableTreeNode).userObject as S) == getName(obj)
        }?.let {
            selectionPath = TreePath((it as DefaultMutableTreeNode).path)
        }
    }

    private fun sortNode(node: DefaultMutableTreeNode) {
        // Note: We temporarily disable the tree selection listener because the node removal and subsequent insertion
        // at another place should not close and re-open (and thus reset) the right-hand editing panel.
        disableSelectionListener = true
        try {
            // We also remember the current selection path so that we can restore it later.
            val selectionPath = this.selectionPath

            val parent = node.parent as DefaultMutableTreeNode
            model.removeNodeFromParent(node)
            var idx = parent.children().asSequence().indexOfFirst {
                (it as DefaultMutableTreeNode).userObject.toString() > node.userObject.toString()
            }
            if (idx == -1)
                idx = parent.childCount
            model.insertNodeInto(node, parent, idx)

            this.selectionPath = selectionPath
        } finally {
            disableSelectionListener = false
        }
    }

    private fun insertSortedLeaf(parent: MutableTreeNode, userObject: Any): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(userObject, false)
        model.insertNodeInto(node, parent, 0)
        sortNode(node)
        return node
    }

    private fun insertAndSelectSortedLeaf(parent: MutableTreeNode, userObject: Any) {
        val leaf = insertSortedLeaf(parent, userObject)
        val leafPath = TreePath(leaf.path)
        scrollPathToVisible(TreePath(leaf.path))
        selectionPath = leafPath
    }

    override fun convertValueToText(
        value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) = when (val userObject = (value as DefaultMutableTreeNode?)?.userObject) {
        is PageStyle -> userObject.name
        is ContentStyle -> userObject.name
        else -> userObject?.toString() ?: ""
    }

}
