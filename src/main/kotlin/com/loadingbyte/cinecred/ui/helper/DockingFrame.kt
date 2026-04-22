package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatClientProperties.*
import com.formdev.flatlaf.ui.FlatBorder
import com.formdev.flatlaf.ui.FlatSplitPaneUI
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.Shortcut
import java.awt.*
import java.awt.event.*
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.plaf.SplitPaneUI
import javax.swing.plaf.basic.BasicSplitPaneUI
import kotlin.math.max
import kotlin.math.roundToInt


class DockingFrame(
    val dockables: List<Dockable>,
    private val configureWindow: (Window) -> Unit,
    private val onChangeCollapsed: (String, Boolean) -> Unit,
    private val onBlockedDrag: () -> Unit
) : CcFrame() {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK")
    fun leakedRetractableButtons() = layeredPane.getComponentsInLayer(RETRACTABLE_BUTTONS_LAYER)!!
    // =========================================


    private val bricks: Map<String, Brick> = dockables.associate { dockable -> dockable.dockableId to Brick(dockable) }
    private val gap = UIManager.getInt("SplitPane.dividerSize")

    private val keyEventDispatcher = object : KeyEventDispatcher {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.id == KEY_PRESSED && Shortcut.ESCAPE.matches(e))
                dragTracker?.let { it.cancel(); dragTracker = null; return true }
            return false
        }
    }

    private val windows = mutableListOf<Window>(this)
    private val collapsedCache = HashMap<String, Boolean>()
    private var dragTracker: DragTracker? = null

    init {
        if (bricks.size != dockables.size) throw IllegalArgumentException("An dockable ID occurs more than once.")

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)

        setupWindow(this)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
                for (window in windows)
                    window.dispose()
            }
        })
    }

    fun isDockingWindow(window: Window): Boolean =
        window in windows

    fun setMacOSModifiedIndicator(modified: Boolean) {
        for (window in windows)
            (window as RootPaneContainer).rootPane.putClientProperty("Window.documentModified", modified)
    }

    var isLocked: Boolean = false
        set(isLocked) {
            field = isLocked
            for (window in windows)
                updateRetractableButtons(window, null)
        }

    fun isCollapsed(dockableId: String): Boolean =
        collapsedCache.getValue(dockableId)

    fun setCollapsed(dockableId: String, collapsed: Boolean) {
        if (isCollapsed(dockableId) != collapsed) {
            val trees = getTrees()
            findLeaf(trees, dockableId).collapsed = collapsed
            setTrees(trees, user = false)
        }
    }

    fun getTrees(): List<Tree> {
        val trees = mutableListOf<Tree>()
        for (window in windows) {
            ((window as RootPaneContainer).rootPane.getClientProperty("__fullyCollapsedTree") as Tree?)?.let { tree ->
                trees += tree
                continue
            }

            if (window.contentPane.componentCount != 0) {
                val bounds = window.boundsExclMargin
                val leftRetractable = window.rootPane.getClientProperty("__leftRetractable") as Boolean
                val rightRetractable = window.rootPane.getClientProperty("__rightRetractable") as Boolean
                val rootComp = window.contentPane.getComponent(0)
                val root = extractTree(rootComp)

                // If the window is retracted, make sure to store non-retracted bounds in the tree, and update the
                // top-level split's ratio based on the size of the retracted window.
                if (rootComp is SplitPane &&
                    (rootComp.leftCollapsedNode != null || rootComp.rightCollapsedNode != null) &&
                    if (rootComp.leftCollapsedNode != null) leftRetractable else rightRetractable
                ) {
                    val p = windowPadding
                    val hor = rootComp.orient == Orientation.HORIZONTAL
                    val sBeforeRet = window.rootPane.getClientProperty("__sizeBeforeRetraction") as Int
                    val sAfterRet = if (hor) bounds.width - p.left - p.right else bounds.height - p.top - p.bottom
                    (root as Node.Split).ratio = (sAfterRet - gap * 2) / (sBeforeRet - gap * 3).toDouble()
                    if (rootComp.leftCollapsedNode != null) {
                        root.ratio = 1 - root.ratio
                        val ret = sBeforeRet - sAfterRet
                        if (hor) bounds.x -= ret else bounds.y -= ret
                    }
                    if (hor) bounds.width = sBeforeRet + p.left + p.right else
                        bounds.height = sBeforeRet + p.top + p.bottom
                }

                trees += Tree(bounds, leftRetractable, rightRetractable, root)
            }
        }
        return trees
    }

    fun setTrees(trees: List<Tree>) {
        setTrees(trees, user = true)
    }

    private fun setTrees(trees: List<Tree>, user: Boolean) {
        val treeIdList = mutableListOf<String>().also { for (tree in trees) collectIds(tree.root, it) }
        val treeIdSet = HashSet(treeIdList)
        if (treeIdList.size != treeIdSet.size) throw IllegalArgumentException("A leaf node ID occurs more than once.")
        if (bricks.keys != treeIdSet) throw IllegalArgumentException("The dockable IDs don't match the leaf node IDs.")

        // Add windows if there are more trees than existing windows.
        while (windows.size < trees.size)
            windows += CcDialog(this).also { window ->
                setupWindow(window)
                window.defaultCloseOperation = DO_NOTHING_ON_CLOSE
                window.addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) {
                        // Upon window closure, if it contains only collapsible dockables, collapse them, thereby
                        // making the window disappear. If there's a non-collapsible dockable, close the project.
                        val trees = getTrees()
                        val tree = trees[windows.indexOf(window)]
                        if (!isTreeCollapsible(tree.root))
                            dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                        else {
                            collapseTree(tree.root)
                            setTrees(trees, user = false)
                        }
                    }
                })
                rootPane.getClientProperty("Window.documentModified")
                    ?.let { window.rootPane.putClientProperty("Window.documentModified", it) }
            }
        // Discard windows if there are fewer trees than existing windows.
        while (windows.size > trees.size.coerceAtLeast(1))
            windows.removeLast().dispose()

        for ((tree, window) in trees.zip(windows)) {
            val contentPane = (window as RootPaneContainer).contentPane
            val prevComp = if (contentPane.componentCount == 0) null else contentPane.getComponent(0)

            // Rearrange the Swing components according to the tree. This yields one root replComp, which, depending on
            // the rearrangement, might be the same as the window's current root component.
            val ratios = mutableListOf<SplitPaneRatio>()
            val (replComp, _, collapsed) = applyTree(tree.root, prevComp, ratios)

            // If the whole tree is collapsed, we'll just keep the components untouched, but store the tree itself.
            window.rootPane.putClientProperty("__fullyCollapsedTree", if (collapsed) deepCopy(tree) else null)
            if (collapsed) {
                window.isVisible = false
                continue
            }

            val bounds = Rectangle(tree.bounds)

            // Retract the window if it's top-level split is half-collapsed and the collapsed side is retractable.
            window.rootPane.putClientProperty("__leftRetractable", tree.leftRetractable)
            window.rootPane.putClientProperty("__rightRetractable", tree.rightRetractable)
            if (replComp is SplitPane &&
                (replComp.leftCollapsedNode != null || replComp.rightCollapsedNode != null) &&
                if (replComp.leftCollapsedNode != null) tree.leftRetractable else tree.rightRetractable
            ) {
                val p = windowPadding
                val hor = replComp.orient == Orientation.HORIZONTAL
                val ratio = replComp.backedUpRatio!!
                val sBeforeRet = if (hor) bounds.width - p.left - p.right else bounds.height - p.top - p.bottom
                val sAfterRet = ((sBeforeRet - gap * 3) * if (replComp.leftCollapsedNode != null) 1 - ratio else ratio)
                    .roundToInt() + gap * 2
                window.rootPane.putClientProperty("__sizeBeforeRetraction", sBeforeRet)
                if (replComp.leftCollapsedNode != null) {
                    val ret = sBeforeRet - sAfterRet
                    if (hor) bounds.x += ret else bounds.y += ret
                }
                if (hor) bounds.width = sAfterRet + p.left + p.right else bounds.height = sAfterRet + p.top + p.bottom
                // If this is the main frame, ensure it's not maximized.
                // On macOS, we stay away from even touching maximization because their system doesn't fit well with us.
                if (window is Frame && !SystemInfo.isMacOS)
                    window.maximized = false
            }

            // Reset the window's minimum size so it doesn't interfere with the following changes.
            window.minimumSize = Dimension()

            // Update the window's bounds if they changed.
            if (window.boundsExclMargin != bounds)
                window.boundsExclMargin = bounds

            // If this is the main frame and the user supplied a new window layout, do the following:
            //   - On Windows, auto-maximize it if it fills the entire screen.
            //   - On macOS, we stay away from even touching maximization because their system doesn't fit well with us.
            //   - On Linux (tested on KDE), un-maximizing windows heavily bugs out, but would be required for
            //     retraction, so we always want our windows to be non-maximized. In case the user maximized one of the
            //     windows by themselves before, un-maximize it again. Note that automatic maximization on Linux is
            //     further prevent by code in our custom CcFrame.
            if (window is Frame && user)
                if (SystemInfo.isWindows && autoMaximize)
                    window.maximized = bounds == window.mostOccupiedGraphicsConfiguration.usableBounds
                else if (SystemInfo.isLinux)
                    window.maximized = false

            // Switch out the window's content if that changed.
            if (replComp != prevComp) {
                contentPane.removeAll()
                contentPane.add(replComp)
            }

            // Update the window's minimum size based on its content.
            updateMinimumSize(window)

            // Rearrange the retractable buttons and update their visibility and activation state.
            updateRetractableButtons(window, tree)

            // Apply split pane ratios, perform layout, and make the window (in)visible.
            // Note: Technically, the post-validation application of split pane ratios a couple of lines below should be
            // sufficient, but we've observed cased (especially when many split panes are stacked) where repeatedly
            // uncollapsing a dockable doesn't restore the split pane's original ratio. For some reason, adding this
            // earlier application of ratios fixes the problem.
            for (ratio in ratios) ratio.splitPane.setDividerLocation(ratio.ratio)
            window.revalidate()
            window.isVisible = true
            for (ratio in ratios) ratio.splitPane.setDividerLocation(ratio.ratio)
            // Also, in some rare cases with many stacked split panes, the original ratios are not always adhered to.
            // An ad-hoc fix is to just apply the divider ratios again a little bit later.
            Timer(100) { for (ratio in ratios) ratio.splitPane.setDividerLocation(ratio.ratio) }
                .apply { isRepeats = false }.start()
        }
    }

    private fun extractTree(comp: Component): Node = when (comp) {
        is Brick -> Node.Leaf(comp.dockable.dockableId, collapsed = false)
        is SplitPane -> Node.Split(
            orientation = comp.orient,
            ratio = comp.backedUpRatio
                ?: if (comp.orient == Orientation.HORIZONTAL)
                    comp.dividerLocation / (comp.width - comp.dividerSize).toDouble()
                else
                    comp.dividerLocation / (comp.height - comp.dividerSize).toDouble(),
            left = comp.leftCollapsedNode?.let(::deepCopy) ?: extractTree(comp.leftComponent),
            right = comp.rightCollapsedNode?.let(::deepCopy) ?: extractTree(comp.rightComponent)
        )
        else -> throw IllegalStateException()
    }

    private fun applyTree(node: Node, comp: Component?, ratios: MutableList<SplitPaneRatio>): ApplyRet {
        when (node) {
            is Node.Leaf -> {
                val brick = bricks.getValue(node.id)
                if (brick != comp)
                    brick.parent?.remove(brick)
                val collapsed = node.collapsed && brick.dockable.collapsible
                brick.isVisible = !collapsed
                brick.dockable.minimumSize = if (collapsed || !applyMinimumSizes) Dimension() else null
                if (collapsed != collapsedCache.put(node.id, collapsed))
                    onChangeCollapsed(node.id, collapsed)
                return ApplyRet(brick, brick.dockable.vResizable, collapsed)
            }
            is Node.Split -> {
                val curSplitPane = comp as? SplitPane
                val leftPrevComp = curSplitPane?.leftComponent
                val rightPrevComp = curSplitPane?.rightComponent
                val (leftReplComp, leftVResizable, leftCollapsed) = applyTree(node.left, leftPrevComp, ratios)
                val (rightReplComp, rightVResizable, rightCollapsed) = applyTree(node.right, rightPrevComp, ratios)

                // If both sides are collapsed, keep the components untouched. A higher-up split or the window itself
                // will turn the subtree invisible anyway.
                if (leftCollapsed && rightCollapsed) {
                    comp?.isVisible = false
                    comp?.minimumSize = Dimension()
                    return ApplyRet(comp ?: JPanel(), leftVResizable || rightVResizable, collapsed = true)
                }

                val splitPane =
                    if (curSplitPane != null && curSplitPane.orient == node.orientation)
                        curSplitPane.apply {
                            if (leftPrevComp != leftReplComp) leftComponent = leftReplComp
                            if (rightPrevComp != rightReplComp) rightComponent = rightReplComp
                        }
                    else
                        SplitPane(node.orientation, leftReplComp, rightReplComp)
                val fixed = (!leftVResizable || !rightVResizable) && node.orientation == Orientation.VERTICAL ||
                        leftCollapsed || rightCollapsed
                if (fixed) {
                    if (splitPane.ui.let {
                            it !is FixedSplitPaneUI ||
                                    it.leftVResizable != leftVResizable || it.rightVResizable != rightVResizable
                        })
                        splitPane.ui = FixedSplitPaneUI(leftVResizable, rightVResizable)
                } else {
                    if (splitPane.ui is FixedSplitPaneUI)
                        splitPane.ui = FlatSplitPaneUI()
                }
                splitPane.isVisible = true
                splitPane.minimumSize = null
                splitPane.backedUpRatio = if (fixed) node.ratio else null
                splitPane.leftCollapsedNode = if (leftCollapsed) deepCopy(node.left) else null
                splitPane.rightCollapsedNode = if (rightCollapsed) deepCopy(node.right) else null
                if (!fixed)
                    ratios += SplitPaneRatio(splitPane, node.ratio.coerceIn(0.0, 1.0))  // coerce for setDividerLocation

                val vResizable = leftVResizable && !leftCollapsed || rightVResizable && !rightCollapsed
                return ApplyRet(splitPane, vResizable, collapsed = false)
            }
        }
    }

    private fun setupWindow(window: Window) {
        configureWindow(window)
        window.layout = WindowLayout()

        // Add buttons for configuring whether either side of the window is retractable.
        for (b in 0..1) {
            val button = JButton().apply {
                setSize(16, 16)
                toolTipText = l10n("ui.docking.toggleRetractable")
                putClientProperty(BUTTON_TYPE, BUTTON_TYPE_TOOLBAR_BUTTON)
                isFocusable = false
            }
            button.addActionListener {
                val trees = getTrees()
                val tree = trees[windows.indexOf(window)]
                if (b == 0) tree.leftRetractable = !tree.leftRetractable else
                    tree.rightRetractable = !tree.rightRetractable
                setTrees(trees, user = false)
            }
            (window as RootPaneContainer).layeredPane.add(button, RETRACTABLE_BUTTONS_LAYER, -1)
        }

        // When the window is resized, update its minimum size (think about wrapping components like the toolbar) and
        // the placement of its retractable buttons.
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updateMinimumSize(window)
                updateRetractableButtons(window, null)
            }
        })
    }

    private fun updateMinimumSize(window: Window) {
        val s = (window as RootPaneContainer).contentPane.minimumSize
        val i = windowMarginPlusPadding
        val minSize = Dimension(s.width + i.left + i.right, s.height + i.top + i.bottom)
        if (window.minimumSize != minSize && applyMinimumSizes)
            window.minimumSize = minSize
    }

    private fun updateRetractableButtons(window: Window, tree: Tree?) {
        val cPane = (window as RootPaneContainer).contentPane
        val orientation = ((if (cPane.componentCount == 0) null else cPane.getComponent(0)) as? SplitPane)?.orient
        val buttons = window.layeredPane.getComponentsInLayer(RETRACTABLE_BUTTONS_LAYER)
        for (button in buttons)
            button.isVisible = !isLocked && orientation != null
        if (orientation == null)
            return
        val fringe = SwingUtilities.convertRectangle(cPane, Rectangle(Point(), cPane.size), window.layeredPane)
        val x = fringe.x + fringe.width - buttons[0].width
        val y = fringe.y + fringe.height - buttons[0].height
        if (orientation == Orientation.HORIZONTAL) {
            buttons[0].setLocation(fringe.x, y)
            buttons[1].setLocation(x, y)
            if (tree != null) {
                setRetractableButtonIcon(buttons[0], ARROW_RIGHT_ICON, tree.leftRetractable)
                setRetractableButtonIcon(buttons[1], ARROW_LEFT_ICON, tree.rightRetractable)
            }
        } else {
            buttons[0].setLocation(x, fringe.y)
            buttons[1].setLocation(x, y)
            if (tree != null) {
                setRetractableButtonIcon(buttons[0], ARROW_DOWN_ICON, tree.leftRetractable)
                setRetractableButtonIcon(buttons[1], ARROW_UP_ICON, tree.rightRetractable)
            }
        }
    }

    private fun setRetractableButtonIcon(button: Component, icon: SVGIcon, retractable: Boolean) {
        (button as JButton).icon = icon.getRecoloredIcon(if (retractable) PALETTE_BLUE_COLOR else PALETTE_GRAY_COLOR)
    }

    private data class ApplyRet(val repl: Component, val vResizable: Boolean, val collapsed: Boolean)
    private class SplitPaneRatio(val splitPane: SplitPane, val ratio: Double)

    private fun droppedBrick(
        draggedBrick: Brick,
        droppedOnto: Component?,
        insertionCardinal: Cardinal?,
        draggedBrickDropLocOnScreen: Point
    ) {
        // Abort if a brick has been dropped onto itself.
        if (droppedOnto is Brick && droppedOnto == draggedBrick ||
            droppedOnto is Window && (draggedBrick.parent.name ?: "").endsWith(".contentPane") &&
            droppedOnto == SwingUtilities.getWindowAncestor(draggedBrick)
        ) return

        val trees = getTrees().toMutableList()

        // Remove the dragged leaf from its parent, and dispose of the tree if it was the last node in it.
        val draggedLeaf = findLeaf(trees, draggedBrick.dockable.dockableId)
        when (val parent = findParent(trees, draggedLeaf)) {
            is Node.Split -> {
                val otherSide = if (draggedLeaf == parent.left) parent.right else parent.left
                replaceInTree(parent, otherSide, findParent(trees, parent))
            }
            is Tree -> trees.remove(parent)
        }

        // Replace the drop target node with a new split.
        val droppedOntoTree = (droppedOnto as? Window)?.let { trees[windows.indexOf(it)] }
        val droppedOntoLeaf = (droppedOnto as? Brick)?.let { findLeaf(trees, it.dockable.dockableId) }
        val droppedOntoNode = droppedOntoTree?.root ?: droppedOntoLeaf
        if (droppedOntoNode == null) {
            val p = windowPadding
            val bounds = Rectangle(
                draggedBrickDropLocOnScreen.x - p.left - gap,
                draggedBrickDropLocOnScreen.y - p.top - gap,
                draggedBrick.width + p.left + p.right + 2 * gap,
                draggedBrick.height + p.top + p.bottom + 2 * gap
            )
            trees += Tree(bounds, leftRetractable = false, rightRetractable = false, draggedLeaf)
        } else {
            val orientation =
                if (insertionCardinal == Cardinal.WEST || insertionCardinal == Cardinal.EAST) Orientation.HORIZONTAL
                else Orientation.VERTICAL
            val newSplit =
                if (insertionCardinal == Cardinal.NORTH || insertionCardinal == Cardinal.WEST)
                    Node.Split(orientation, ratio = 0.5, left = draggedLeaf, right = droppedOntoNode)
                else
                    Node.Split(orientation, ratio = 0.5, left = droppedOntoNode, right = draggedLeaf)
            when {
                droppedOntoTree != null -> droppedOntoTree.root = newSplit
                droppedOntoLeaf != null -> replaceInTree(droppedOntoLeaf, newSplit, findParent(trees, droppedOntoLeaf))
            }
        }

        setTrees(trees, user = false)
    }

    private fun collectIds(node: Node, ids: MutableCollection<String>) {
        when (node) {
            is Node.Leaf -> ids += node.id
            is Node.Split -> {
                collectIds(node.left, ids)
                collectIds(node.right, ids)
            }
        }
    }

    private fun findLeaf(trees: List<Tree>, id: String): Node.Leaf =
        trees.firstNotNullOfOrNull { tree -> findLeaf(tree.root, id) }
            ?: throw IllegalArgumentException("Dockable ID $id not found.")

    private fun findLeaf(node: Node, id: String): Node.Leaf? =
        when (node) {
            is Node.Leaf -> if (node.id == id) node else null
            is Node.Split -> findLeaf(node.left, id) ?: findLeaf(node.right, id)
        }

    private fun findParent(trees: List<Tree>, of: Node): Any =
        trees.firstNotNullOf { tree -> if (tree.root == of) tree else findParent(tree.root, of) }

    private fun findParent(node: Node, of: Node): Node? =
        when (node) {
            is Node.Leaf -> null
            is Node.Split -> if (node.left == of || node.right == of) node else
                findParent(node.left, of) ?: findParent(node.right, of)
        }

    private fun isTreeCollapsible(node: Node): Boolean =
        when (node) {
            is Node.Leaf -> bricks.getValue(node.id).dockable.collapsible
            is Node.Split -> isTreeCollapsible(node.left) && isTreeCollapsible(node.right)
        }

    private fun collapseTree(node: Node) {
        when (node) {
            is Node.Leaf -> node.collapsed = true
            is Node.Split -> {
                collapseTree(node.left)
                collapseTree(node.right)
            }
        }
    }

    private fun replaceInTree(oldNode: Node, newNode: Node, parent: Any) {
        when (parent) {
            is Node.Split -> if (oldNode == parent.left) parent.left = newNode else parent.right = newNode
            is Tree -> parent.root = newNode
        }
    }

    private fun deepCopy(tree: Tree): Tree =
        Tree(Rectangle(tree.bounds), tree.leftRetractable, tree.rightRetractable, deepCopy(tree.root))

    private fun deepCopy(node: Node): Node =
        when (node) {
            is Node.Leaf -> Node.Leaf(node.id, node.collapsed)
            is Node.Split -> Node.Split(node.orientation, node.ratio, deepCopy(node.left), deepCopy(node.right))
        }


    companion object {
        var applyMinimumSizes = true
        var autoMaximize = true
        private const val RETRACTABLE_BUTTONS_LAYER = 50
        private operator fun Point.plus(o: Point) = Point(x + o.x, y + o.y)
        private operator fun Point.minus(o: Point) = Point(x - o.x, y - o.y)
    }


    open class Dockable(
        val dockableId: String,
        val title: String,
        val icon: Icon,
        val vResizable: Boolean = true,
        val collapsible: Boolean = true
    ) : JPanel()


    class Tree(var bounds: Rectangle, var leftRetractable: Boolean, var rightRetractable: Boolean, var root: Node)

    sealed interface Node {
        class Leaf(var id: String, var collapsed: Boolean) : Node
        class Split(var orientation: Orientation, var ratio: Double, var left: Node, var right: Node) : Node
    }

    enum class Orientation { HORIZONTAL, VERTICAL }


    private enum class Cardinal { WEST, EAST, NORTH, SOUTH }


    private class WindowLayout : LayoutManager {

        private val gap = UIManager.getInt("SplitPane.dividerSize")

        override fun addLayoutComponent(name: String, comp: Component) {}
        override fun removeLayoutComponent(comp: Component) {}

        override fun preferredLayoutSize(parent: Container): Dimension =
            (if (parent.componentCount == 0) Dimension() else parent.getComponent(0).preferredSize)
                .apply { setSize(width + gap * 2, height + gap * 2) }

        override fun minimumLayoutSize(parent: Container): Dimension =
            if (parent.componentCount == 0) Dimension() else parent.getComponent(0).minimumSize
                .apply { setSize(width + gap * 2, height + gap * 2) }

        override fun layoutContainer(parent: Container) {
            if (parent.componentCount != 0)
                parent.getComponent(0).setBounds(gap, gap, parent.width - gap * 2, parent.height - gap * 2)
        }

    }


    private inner class Brick(val dockable: Dockable) : JPanel(BorderLayout()) {

        init {
            border = FlatBorder()
            add(Header(), BorderLayout.NORTH)
            add(dockable, BorderLayout.CENTER)
        }

        private inner class Header : JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)) {

            init {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                putClientProperty(STYLE, "background: @componentBackground")

                add(JLabel(dockable.title, dockable.icon, JLabel.LEADING).apply { iconTextGap = 6 })

                addThresholdedStartDragListener { e ->
                    if (isLocked) onBlockedDrag() else dragTracker = DragTracker(this@Brick, e.locationOnScreen)
                }

                val mouseListener = object : MouseAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        dragTracker?.mouseDragged(e)
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        dragTracker?.mouseReleased(e)
                        dragTracker = null
                    }
                }
                addMouseListener(mouseListener)
                addMouseMotionListener(mouseListener)
            }

        }

    }


    private class SplitPane(orient: Orientation, leftComponent: Component, rightComponent: Component) : JSplitPane(
        if (orient == Orientation.HORIZONTAL) HORIZONTAL_SPLIT else VERTICAL_SPLIT,
        true, leftComponent, rightComponent
    ) {

        var backedUpRatio: Double? = null
        var leftCollapsedNode: Node? = null
        var rightCollapsedNode: Node? = null

        init {
            resizeWeight = 0.5
        }

        val orient: Orientation
            get() = if (orientation == HORIZONTAL_SPLIT) Orientation.HORIZONTAL else Orientation.VERTICAL

        override fun setUI(ui: SplitPaneUI?) {
            super.setUI(ui)
            // Allow the user to reset the divider to the middle by double-clicking on it.
            (ui as? BasicSplitPaneUI)?.divider?.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2)
                        setDividerLocation(0.5)
                }
            })
        }

    }


    // Notice that the only time this UI is put on a split pane with two visible components, the split pane is vertical.
    // As such, we don't need a dedicated orientation parameter here.
    private class FixedSplitPaneUI(val leftVResizable: Boolean, val rightVResizable: Boolean) : SplitPaneUI() {

        override fun installUI(c: JComponent) {
            c.layout = Layout()
        }

        override fun resetToPreferredSizes(jc: JSplitPane) {}
        override fun setDividerLocation(jc: JSplitPane, location: Int) {}
        override fun getDividerLocation(jc: JSplitPane) = 0
        override fun getMinimumDividerLocation(jc: JSplitPane) = 0
        override fun getMaximumDividerLocation(jc: JSplitPane) = 0
        override fun finishedPaintingChildren(jc: JSplitPane, g: Graphics) {}

        private inner class Layout : LayoutManager {

            private val gap = UIManager.getInt("SplitPane.dividerSize")

            override fun addLayoutComponent(name: String, comp: Component) {}
            override fun removeLayoutComponent(comp: Component) {}

            override fun preferredLayoutSize(parent: Container) = layoutSize(parent, Component::getPreferredSize)
            override fun minimumLayoutSize(parent: Container) = layoutSize(parent, Component::getMinimumSize)

            private fun layoutSize(parent: Container, getSize: (Component) -> Dimension): Dimension {
                val leftComp = (parent as SplitPane).leftComponent
                val rightComp = parent.rightComponent
                return when {
                    leftComp.isVisible && rightComp.isVisible -> {
                        val leftSize = getSize(leftComp)
                        val rightSize = getSize(rightComp)
                        Dimension(max(leftSize.width, rightSize.width), leftSize.height + rightSize.height + gap)
                    }
                    leftComp.isVisible -> getSize(leftComp)
                    rightComp.isVisible -> getSize(rightComp)
                    else -> Dimension()
                }
            }

            override fun layoutContainer(parent: Container) {
                val leftComp = (parent as SplitPane).leftComponent
                val rightComp = parent.rightComponent
                when {
                    leftComp.isVisible && rightComp.isVisible -> {
                        var leftHeight = if (!leftVResizable) leftComp.preferredSize.height else -1
                        var rightHeight = if (!rightVResizable) rightComp.preferredSize.height else -1
                        if (leftVResizable) leftHeight = parent.height - gap - rightHeight
                        if (rightVResizable) rightHeight = parent.height - gap - leftHeight
                        leftComp.setBounds(0, 0, parent.width, leftHeight)
                        rightComp.setBounds(0, leftHeight + gap, parent.width, rightHeight)
                    }
                    leftComp.isVisible -> leftComp.setBounds(0, 0, parent.width, parent.height)
                    rightComp.isVisible -> rightComp.setBounds(0, 0, parent.width, parent.height)
                }
            }

        }

    }


    private inner class DragTracker(private val draggingBrick: Brick, dragStartLocOnScreen: Point) {

        private val ghost: JWindow
        private val draggingBrickOffsetFromMouse: Point

        private var draggingOver: Pair<Window, Brick?>? = null
        private var highlightCardinal: Cardinal? = null

        init {
            val snapshot = BufferedImage(draggingBrick.width, draggingBrick.height, BufferedImage.TYPE_3BYTE_BGR)
                .withG2(draggingBrick::print)
            val locOnScreen = draggingBrick.locationOnScreen
            ghost = JWindow(this@DockingFrame).apply {
                opacity = 0.5f
                background = Color(0, 0, 0, 0)
                contentPane.add(JLabel(ImageIcon(snapshot)))
                pack()
                location = locOnScreen
                focusableWindowState = false
                isAlwaysOnTop = true
                isVisible = true
            }
            draggingBrickOffsetFromMouse = locOnScreen - dragStartLocOnScreen

            for (window in windows) {
                val glassPane = GlassPane(window)
                (window as RootPaneContainer).glassPane = glassPane
                glassPane.isVisible = true
            }
        }

        fun mouseDragged(e: MouseEvent) {
            val locOnScreen = e.locationOnScreen

            ghost.location = locOnScreen + draggingBrickOffsetFromMouse

            var draggingOverWindow: Window? = null
            var draggingOverBrick: Brick? = null
            var highlightCardinal: Cardinal? = null

            // Iterate through the windows front-to-back, and find the first window which contains the mouse
            // location. If there's no such window or the window doesn't belong to our docking system, stop.
            for (window in getWindows().asList().asReversed())
                if (window != ghost && window.isVisible && window.bounds.contains(locOnScreen)) {
                    if (window in windows)
                        draggingOverWindow = window
                    break
                }

            if (draggingOverWindow != null) {
                val cp = (draggingOverWindow as RootPaneContainer).contentPane
                val locOnCp = Point(locOnScreen)
                SwingUtilities.convertPointFromScreen(locOnCp, cp)
                val m = 50
                if (!Rectangle(m, m, cp.width - 2 * m, cp.height - 2 * m).contains(locOnCp)) {
                    highlightCardinal = cardinalForDropPoint(cp, locOnCp)
                } else {
                    val locOnBrick = Point()
                    for (brick in bricks.values) {
                        locOnBrick.location = locOnScreen
                        SwingUtilities.convertPointFromScreen(locOnBrick, brick)
                        if (brick.contains(locOnBrick) && SwingUtilities.getWindowAncestor(brick) == draggingOverWindow) {
                            draggingOverBrick = brick
                            break
                        }
                    }

                    if (draggingOverBrick != null)
                        highlightCardinal = cardinalForDropPoint(draggingOverBrick, locOnBrick)
                }
            }

            val draggingOver = if (draggingOverWindow == null) null else Pair(draggingOverWindow, draggingOverBrick)
            val repaintGlassPanes = draggingOver != this.draggingOver || highlightCardinal != this.highlightCardinal
            this.draggingOver = draggingOver
            this.highlightCardinal = highlightCardinal
            if (repaintGlassPanes)
                for (window in windows)
                    (window as RootPaneContainer).glassPane.repaint()
        }

        fun mouseReleased(e: MouseEvent) {
            cancel()
            droppedBrick(
                draggedBrick = draggingBrick,
                droppedOnto = draggingOver?.second ?: draggingOver?.first,
                insertionCardinal = highlightCardinal,
                draggedBrickDropLocOnScreen = e.locationOnScreen + draggingBrickOffsetFromMouse
            )
        }

        fun cancel() {
            ghost.dispose()
            for (window in windows)
                (window as RootPaneContainer).glassPane.isVisible = false
        }

        private fun cardinalForDropPoint(comp: Component, locOnComp: Point): Cardinal {
            val x = locOnComp.x / comp.width.toDouble()
            val y = locOnComp.y / comp.height.toDouble()
            val botLeftTri = x < y
            val topLeftTri = x < 1 - y
            return when {
                botLeftTri && topLeftTri -> Cardinal.WEST
                !botLeftTri && !topLeftTri -> Cardinal.EAST
                !botLeftTri && topLeftTri -> Cardinal.NORTH
                else -> Cardinal.SOUTH
            }
        }

        private inner class GlassPane(val window: Window) : JComponent() {

            private val c = Color(UIManager.getColor("List.selectionBackground").rgb and 0xFFFFFF or (100 shl 24), true)

            override fun paintComponent(g: Graphics) {
                val (draggingOverWindow, draggingOverBrick) = draggingOver ?: return
                if (window == draggingOverWindow) {
                    val comp = draggingOverBrick ?: (draggingOverWindow as RootPaneContainer).contentPane
                    // Highlight a cardinal direction when the user hovers over it during dragging another brick.
                    val bounds = when (highlightCardinal) {
                        Cardinal.WEST -> Rectangle(0, 0, comp.width / 2, comp.height)
                        Cardinal.EAST -> Rectangle(comp.width / 2, 0, comp.width / 2, comp.height)
                        Cardinal.NORTH -> Rectangle(0, 0, comp.width, comp.height / 2)
                        Cardinal.SOUTH -> Rectangle(0, comp.height / 2, comp.width, comp.height / 2)
                        null -> return
                    }
                    bounds.location = SwingUtilities.convertPoint(comp, bounds.location, this)
                    g.color = c
                    (g as Graphics2D).fill(bounds)
                }
            }

        }

    }

}
