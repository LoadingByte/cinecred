package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.ui.helper.FOLDER_ICON
import com.loadingbyte.cinecred.ui.helper.ICON_ICON_GAP
import com.loadingbyte.cinecred.ui.helper.SVGIcon
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.*


/**
 * A tree used for managing the global settings and various style lists. Note that this class is pretty generic and
 * as such doesn't directly know about any of that stuff.
 */
class StylingTree : JTree(DefaultTreeModel(DefaultMutableTreeNode(), true)) {

    var onDeselect: (() -> Unit)? = null

    private val model = treeModel as DefaultTreeModel
    private val rootNode = model.root as DefaultMutableTreeNode

    private val singletonTypeInfos = HashMap<Class<*>, TypeInfo.Singleton>()
    private val listTypeInfos = HashMap<Class<*>, TypeInfo.List>()

    private var disableSelectionListener = false

    init {
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

        // Each node gets an icon depending on which type it is part of.
        // In addition, optional extra icons are rendered on the right side of each node.
        setCellRenderer(StylingTreeCellRenderer())

        // When the user selects a node that stores an object, notify the callback.
        addTreeSelectionListener {
            if (disableSelectionListener)
                return@addTreeSelectionListener
            triggerOnSelectOrOnDeselect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> addSingletonType(initial: T, label: String, icon: Icon, onSelect: (T) -> Unit) {
        val node = DefaultMutableTreeNode(null, false)
        model.insertNodeInto(node, rootNode, rootNode.childCount)
        val typeInfo = TypeInfo.Singleton(icon, node, onSelect as (Any) -> Unit, label)
        singletonTypeInfos[initial.javaClass] = typeInfo
        node.userObject = StoredObj(typeInfo, initial)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> addListType(
        type: Class<T>, label: String, icon: Icon,
        onSelect: (T) -> Unit, objToString: (T) -> String, isVolatile: ((T) -> Boolean)? = null
    ) {
        val node = DefaultMutableTreeNode(label, true)
        model.insertNodeInto(node, rootNode, rootNode.childCount)
        listTypeInfos[type] = TypeInfo.List(
            icon, node, onSelect as (Any) -> Unit, objToString as (Any) -> String, isVolatile as ((Any) -> Boolean)?
        )
    }

    var selected: Any?
        get() {
            val selectedNodeUserObj = (selectedNode ?: return null).userObject
            return if (selectedNodeUserObj is StoredObj) return selectedNodeUserObj.obj else null
        }
        set(selected) {
            fun getSelNode(): DefaultMutableTreeNode? {
                if (selected == null)
                    return null
                val singletonTypeInfo = singletonTypeInfos[selected.javaClass]
                if (singletonTypeInfo != null && (singletonTypeInfo.node.userObject as StoredObj).obj === selected)
                    return singletonTypeInfo.node
                val listTypeInfo = listTypeInfos[selected.javaClass]
                if (listTypeInfo != null)
                    for (leaf in listTypeInfo.node.children()) {
                        leaf as DefaultMutableTreeNode
                        if ((leaf.userObject as StoredObj).obj === selected)
                            return leaf
                    }
                throw IllegalArgumentException("Object for selection not found in StylingTree.")
            }

            val selPath = getSelNode()?.path?.let(::TreePath)
            selPath?.let(::scrollPathToVisible)
            selectionPath = selPath
        }

    var selectedRow: Int
        get() = minSelectionRow
        set(selectedRow) {
            scrollRowToVisible(selectedRow)
            setSelectionRow(selectedRow)
        }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getSingleton(type: Class<T>): T =
        (singletonTypeInfos.getValue(type).node.userObject as StoredObj).obj as T

    fun setSingleton(singleton: Any) {
        val typeInfo = singletonTypeInfos.getValue(singleton.javaClass)
        (typeInfo.node.userObject as StoredObj).obj = singleton
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getList(type: Class<T>): List<T> =
        listTypeInfos.getValue(type).node.children().asSequence()
            .map { leaf -> ((leaf as DefaultMutableTreeNode).userObject as StoredObj).obj as T }
            .toList()

    fun addListElement(element: Any) {
        val typeInfo = listTypeInfos.getValue(element.javaClass)
        insertSortedLeaf(typeInfo.node, StoredObj(typeInfo, element))
    }

    fun removeListElement(element: Any) {
        val typeInfo = listTypeInfos.getValue(element.javaClass)
        for (leaf in typeInfo.node.children()) {
            leaf as DefaultMutableTreeNode
            if ((leaf.userObject as StoredObj).obj === element) {
                withoutSelectionListener { model.removeNodeFromParent(leaf) }
                return
            }
        }
        throw IllegalArgumentException("Element for removal not found.")
    }

    /**
     * This method uses reference equality to locate the old element. This behavior is expected by the client of this
     * method and should not be changed.
     */
    fun <T : Any> updateListElement(oldElement: T, newElement: T) {
        val oldTypeInfo = listTypeInfos.getValue(oldElement.javaClass)
        val newTypeInfo = listTypeInfos.getValue(newElement.javaClass)
        if (oldTypeInfo != newTypeInfo)
            throw IllegalArgumentException("Both elements must belong to the same list type.")
        for (leaf in oldTypeInfo.node.children()) {
            val leafUserObj = (leaf as DefaultMutableTreeNode).userObject as StoredObj
            if (leafUserObj.obj === oldElement) {
                leafUserObj.obj = newElement
                // If the node's name has not changed, there is no need to change the current ordering. Having this explicit
                // exception also ensures that nodes with duplicate names do not jump around when editing them.
                if (!oldTypeInfo.objToString(oldElement).equals(oldTypeInfo.objToString(newElement), ignoreCase = true))
                    sortNode(leaf)
                return
            }
        }
        throw IllegalArgumentException("Old element not found.")
    }

    fun replaceAllListElements(newElements: Iterable<Any>) {
        val selectedNodeUserObj = selectedNode?.userObject.let { it as? StoredObj }

        // If a list node is currently selected, deselect it for now. For this, the onDeselect() function is disabled
        // because a call to a tree method should by contract never trigger such callbacks. Note that we if we wouldn't
        // explicitly deselect the node here, it would be deselected automatically when it is removed in a couple of
        // lines, however, then onDeselect() would be called, which we do not want.
        if (selectedNodeUserObj?.typeInfo is TypeInfo.List)
            withoutSelectionListener { selectionRows = intArrayOf() }

        for (typeInfo in listTypeInfos.values)
            for (node in typeInfo.node.children().toList())  // Using .toList() avoids concurrent modification.
                model.removeNodeFromParent(node as MutableTreeNode)

        for (element in newElements)
            addListElement(element)

        for (typeInfo in listTypeInfos.values)
            expandPath(TreePath(typeInfo.node.path))

        val typeInfo = selectedNodeUserObj?.typeInfo
        if (typeInfo is TypeInfo.List) {
            // If a list node has previously been select, select the best matching node now...
            val oldElem = selectedNodeUserObj.obj
            val oldElemName = typeInfo.objToString(oldElem)
            val candidateNodes = typeInfo.node.children().asSequence().map { it as DefaultMutableTreeNode }.toList()

            val bestMatchingNode =
                candidateNodes.find { (it.userObject as StoredObj).obj == oldElem }
                    ?: candidateNodes.find { typeInfo.objToString((it.userObject as StoredObj).obj) == oldElemName }

            // If some matching node has been found, select it. Once again, don't notify onSelect().
            if (bestMatchingNode != null)
                withoutSelectionListener { selectionPath = TreePath(bestMatchingNode.path) }
        }
    }

    fun adjustAppearance(isGrayedOut: ((Any) -> Boolean)? = null, getExtraIcons: ((Any) -> List<SVGIcon>)? = null) {
        for (typeInfo in singletonTypeInfos.values) {
            val nodeUserObj = typeInfo.node.userObject as StoredObj
            isGrayedOut?.let { nodeUserObj.isGrayedOut = it(nodeUserObj.obj) }
            getExtraIcons?.let { nodeUserObj.extraIcons = it(nodeUserObj.obj) }
        }
        model.nodesChanged(rootNode, IntArray(rootNode.childCount) { it })

        for (typeInfo in listTypeInfos.values) {
            for (leaf in typeInfo.node.children()) {
                val leafUserObj = (leaf as DefaultMutableTreeNode).userObject as StoredObj
                isGrayedOut?.let { leafUserObj.isGrayedOut = it(leafUserObj.obj) }
                getExtraIcons?.let { leafUserObj.extraIcons = it(leafUserObj.obj) }
            }
            model.nodesChanged(typeInfo.node, IntArray(typeInfo.node.childCount) { it })
        }
    }

    fun triggerOnSelectOrOnDeselect() {
        val selectedNodeUserObj = selectedNode?.userObject
        if (selectedNodeUserObj is StoredObj)
            selectedNodeUserObj.typeInfo.onSelect(selectedNodeUserObj.obj)
        else
            onDeselect?.invoke()
    }

    private fun sortNode(node: DefaultMutableTreeNode) {
        val parent = node.parent as DefaultMutableTreeNode
        val nodeUserObj = node.userObject as StoredObj
        val isVolatile = (nodeUserObj.typeInfo as TypeInfo.List).isVolatile
        val nodeStr = nodeUserObj.toString()
        val isGrayedOut = nodeUserObj.isGrayedOut
        val curIdx = parent.getIndex(node)

        // By default, if the node's name is used by multiple styles, sort the node below all duplicates. Only sort it
        // above if the node is currently in use (not grayed out) and all other nodes with the same name are not in use
        // (grayed out). This ensures that:
        //   - When renaming a style to the name of another which is in use, the other styles remains the preferred one.
        //   - When renaming a used style and the name is already taken by a bunch of unused (and hence irrelevant)
        //     styles, the renamed style remains the preferred one.
        // When styles can be volatile, also sort above if the style is not volatile, but the currently used (not grayed
        // out) style of the same name is volatile. This will leave the other style unused, so it will disappear.
        val sortAboveDups = !isGrayedOut && parent.children().asSequence().none { sibling ->
            val sibUserObj = (sibling as DefaultMutableTreeNode).userObject as StoredObj
            sibling !== node && !sibUserObj.isGrayedOut && sibUserObj.toString().equals(nodeStr, ignoreCase = true)
        } || isVolatile != null && !isVolatile(nodeUserObj.obj) && parent.children().asSequence().any { sibling ->
            val sibUserObj = (sibling as DefaultMutableTreeNode).userObject as StoredObj
            sibling !== node && !sibUserObj.isGrayedOut && sibUserObj.toString().equals(nodeStr, ignoreCase = true) &&
                    isVolatile(sibUserObj.obj)
        }
        var newIdx = parent.children().asSequence().indexOfFirst {
            if (it === node) return@indexOfFirst false
            val c = String.CASE_INSENSITIVE_ORDER.compare((it as DefaultMutableTreeNode).userObject.toString(), nodeStr)
            if (sortAboveDups) c >= 0 else c > 0
        }
        if (newIdx == -1)
            newIdx = parent.childCount
        // Account for the fact that the node we wish to re-insert is still in the list.
        if (newIdx > curIdx)
            newIdx--

        // Only re-insert the node if its position will actually change.
        if (newIdx != curIdx) {
            // Temporarily disable the tree selection listener because the node removal and subsequent insertion at
            // another place should not close and re-open (and thus reset) the right-hand editing panel.
            withoutSelectionListener {
                // Also remember the current selection path so that we can restore it in a moment.
                val selectionPath = this.selectionPath
                model.removeNodeFromParent(node)
                model.insertNodeInto(node, parent, newIdx)
                this.selectionPath = selectionPath
            }
        }
    }

    private fun insertSortedLeaf(parent: MutableTreeNode, userObject: Any) {
        val node = DefaultMutableTreeNode(userObject, false)
        model.insertNodeInto(node, parent, 0)
        sortNode(node)
    }

    private val selectedNode
        get() = lastSelectedPathComponent as DefaultMutableTreeNode?

    private inline fun withoutSelectionListener(block: () -> Unit) {
        disableSelectionListener = true
        try {
            block()
        } finally {
            disableSelectionListener = false
        }
    }


    private sealed interface TypeInfo {

        val icon: Icon
        val node: DefaultMutableTreeNode
        val onSelect: (Any) -> Unit

        class Singleton(
            override val icon: Icon,
            override val node: DefaultMutableTreeNode,
            override val onSelect: (Any) -> Unit,
            val label: String
        ) : TypeInfo

        class List(
            override val icon: Icon,
            override val node: DefaultMutableTreeNode,
            override val onSelect: (Any) -> Unit,
            val objToString: (Any) -> String,
            val isVolatile: ((Any) -> Boolean)?
        ) : TypeInfo

    }


    private class StoredObj(
        val typeInfo: TypeInfo,
        var obj: Any,
        var isGrayedOut: Boolean = false,
        var extraIcons: List<SVGIcon> = emptyList()
    ) {
        override fun toString() = when (typeInfo) {
            is TypeInfo.Singleton -> typeInfo.label
            is TypeInfo.List -> typeInfo.objToString(obj)
        }
    }


    private class StylingTreeCellRenderer : DefaultTreeCellRenderer() {

        private var extraIcons: List<Icon> = emptyList()

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val userObj = (value as DefaultMutableTreeNode).userObject
            if (userObj is StoredObj) {
                icon = userObj.typeInfo.icon
                isEnabled = !userObj.isGrayedOut
                extraIcons = userObj.extraIcons
            } else {
                icon = FOLDER_ICON
                isEnabled = true
                extraIcons = emptyList()
            }
            return this
        }

        override fun getPreferredSize(): Dimension? = super.getPreferredSize()?.let { pref ->
            var newPrefWidth = pref.width
            if (extraIcons.isNotEmpty())
                newPrefWidth += 2 * iconTextGap +  // Multiply by 2 to increase the spacing between text & icons.
                        ICON_ICON_GAP * (extraIcons.size - 1) +
                        extraIcons.sumOf(Icon::getIconWidth)
            Dimension(newPrefWidth, pref.height)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            var x = width - 3  // Subtract 3 because super.getPreferredSize() adds 3 to the preferred width.
            for (icon in extraIcons.asReversed()) {
                x -= icon.iconWidth
                icon.paintIcon(this, g, x, (height - icon.iconHeight) / 2)
                x -= ICON_ICON_GAP
            }
        }

    }

}
