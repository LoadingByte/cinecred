package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.ui.PresetWindowLayout
import com.loadingbyte.cinecred.ui.comms.DockableId
import com.loadingbyte.cinecred.ui.helper.*
import com.loadingbyte.cinecred.ui.view.playback.PlaybackDockable
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import java.awt.*
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock


fun l10nDemo(key: String): String =
    ResourceBundle.getBundle("l10n.demo").getString(key)


val gCfg: GraphicsConfiguration =
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration

fun edt(block: () -> Unit) {
    val lock = ReentrantLock()
    val condition = lock.newCondition()
    lock.withLock {
        SwingUtilities.invokeLater {
            block()
            lock.withLock { condition.signal() }
        }
        // Some AWT actions block the EDT thread (e.g., opening a modal dialog). To avoid deadlocking the demo,
        // stop waiting for the action to return after some time has passed.
        condition.await(5, TimeUnit.SECONDS)
    }
}


fun printWithPopups(component: Component): BufferedImage =
    BufferedImage(component.width, component.height, BufferedImage.TYPE_3BYTE_BGR).withG2 { g2 ->
        printWithPopups(component, g2)
    }

fun printWithPopups(component: Component, g2: Graphics2D) {
    edt {
        val window = SwingUtilities.getWindowAncestor(component)
        val compLocOnScreen = component.locationOnScreen

        if (window.isVisible)
            component.print(g2)
        if (window is DockingFrame) {
            val playbackDockable =
                window.dockables.first { it.dockableId == DockableId.PLAYBACK.name } as PlaybackDockable
            @Suppress("DEPRECATION")
            val canvas = playbackDockable.leakedVideoCanvas
            if (playbackDockable.parent.isVisible && SwingUtilities.isDescendingFrom(canvas, component)) {
                g2.preserveTransform {
                    val canvasLocInComp = SwingUtilities.convertPoint(canvas, 0, 0, component)
                    g2.translate(canvasLocInComp.x, canvasLocInComp.y)
                    canvas.print(g2)
                }
            }
        }
        (g2 as? ScaledGraphics2D)?.settleDeferred()

        if (window is DockingFrame) {
            for (c in @Suppress("DEPRECATION") window.leakedRetractableButtons())
                if (c.isVisible)
                    printOverlay(c, compLocOnScreen, g2)
        }

        forEachVisiblePopupOf(window) { popup ->
            printOverlay(popup, compLocOnScreen, g2)
        }

        if (window is RootPaneContainer && component == window.contentPane) {
            val glassPane = window.glassPane
            if (glassPane.isVisible) {
                glassPane.print(g2)
                (g2 as? ScaledGraphics2D)?.settleDeferred()
            }
        }
    }
}

private fun printOverlay(overlay: Component, compLocOnScreen: Point, g2: Graphics2D) {
    g2.preserveTransform {
        g2.translate(overlay.x - compLocOnScreen.x, overlay.y - compLocOnScreen.y)
        overlay.print(g2)
    }
    (g2 as? ScaledGraphics2D)?.settleDeferred()
}

inline fun forEachVisiblePopupOf(window: Window, action: (Window) -> Unit) {
    for (popup in Window.getWindows())
        if (popup.isVisible && popup.type == Window.Type.POPUP) {
            var owner = popup
            while ((owner ?: continue) != window)
                owner = owner.owner
            action(popup)
        }
}


val BGR24_REPRESENTATION = Bitmap.Representation(
    Bitmap.PixelFormat.of(AV_PIX_FMT_BGR24), ColorSpace.SRGB, Bitmap.Alpha.OPAQUE
)


val dockedTrees get() = PresetWindowLayout.PRESET_DOCKED.trees(gCfg.usableBounds)

fun trees(vararg trees: DockingFrame.Tree) = buildList {
    addAll(trees)
    for (id in HashSet(DockableId.entries).also { set -> for (tree in trees) removeDockableIds(tree.root, set) })
        add(tree(-50, 0, 10, 10, if (id == DockableId.TOOLBAR) mkLeaf(id) else collapsed(id)))
}

private fun removeDockableIds(node: DockingFrame.Node, ids: MutableCollection<DockableId>) {
    when (node) {
        is DockingFrame.Node.Leaf -> ids.remove(DockableId.valueOf(node.id))
        is DockingFrame.Node.Split -> {
            removeDockableIds(node.left, ids)
            removeDockableIds(node.right, ids)
        }
    }
}

fun tree(root: DockableId) = tree(mkLeaf(root))
fun tree(root: DockingFrame.Node) = tree(500, 500, root)
fun tree(w: Int, h: Int, root: DockableId) = tree(w, h, mkLeaf(root))
fun tree(w: Int, h: Int, root: DockingFrame.Node) = tree(0, 0, w, h, root)
fun tree(x: Int, y: Int, w: Int, h: Int, root: DockingFrame.Node): DockingFrame.Tree {
    // Add the window decorations to the size.
    val insets = windowMarginPlusPadding
    val w = w + insets.left + insets.right
    val h = h + insets.top + insets.bottom
    return DockingFrame.Tree(Rectangle(x, y, w, h), leftRetractable = false, rightRetractable = false, root)
}

private fun mkLeaf(id: DockableId) = DockingFrame.Node.Leaf(id.name, collapsed = false)
fun collapsed(id: DockableId) = DockingFrame.Node.Leaf(id.name, collapsed = true)

fun hSplit(left: DockableId, right: DockableId) = hSplit(0.5, left, right)
fun hSplit(ratio: Double, left: DockableId, right: DockableId) = hSplit(ratio, mkLeaf(left), mkLeaf(right))
fun hSplit(ratio: Double, left: DockableId, right: DockingFrame.Node) = hSplit(ratio, mkLeaf(left), right)
fun hSplit(ratio: Double, left: DockingFrame.Node, right: DockableId) = hSplit(ratio, left, mkLeaf(right))
fun hSplit(ratio: Double, left: DockingFrame.Node, right: DockingFrame.Node) =
    DockingFrame.Node.Split(DockingFrame.Orientation.HORIZONTAL, ratio, left, right)

fun vSplit(left: DockableId, right: DockableId) = vSplit(0.5, left, right)
fun vSplit(left: DockableId, right: DockingFrame.Node) = vSplit(0.5, left, right)
fun vSplit(ratio: Double, left: DockableId, right: DockableId) = vSplit(ratio, mkLeaf(left), mkLeaf(right))
fun vSplit(ratio: Double, left: DockableId, right: DockingFrame.Node) = vSplit(ratio, mkLeaf(left), right)
fun vSplit(ratio: Double, left: DockingFrame.Node, right: DockableId) = vSplit(ratio, left, mkLeaf(right))
fun vSplit(ratio: Double, left: DockingFrame.Node, right: DockingFrame.Node) =
    DockingFrame.Node.Split(DockingFrame.Orientation.VERTICAL, ratio, left, right)

fun List<DockingFrame.Tree>.leaf(id: DockableId): DockingFrame.Node.Leaf = firstNotNullOf { it.leaf(id) }
fun DockingFrame.Tree.leaf(id: DockableId): DockingFrame.Node.Leaf? = root.leaf(id)
fun DockingFrame.Node.leaf(id: DockableId): DockingFrame.Node.Leaf? = when (this) {
    is DockingFrame.Node.Leaf -> if (this.id == id.name) this else null
    is DockingFrame.Node.Split -> left.leaf(id) ?: right.leaf(id)
}

fun List<DockingFrame.Tree>.parent(id: DockableId): DockingFrame.Node = firstNotNullOf { it.parent(id) }
fun DockingFrame.Tree.parent(id: DockableId): DockingFrame.Node? = root.parent(id)
fun DockingFrame.Node.parent(id: DockableId): DockingFrame.Node? = when (this) {
    is DockingFrame.Node.Leaf -> null
    is DockingFrame.Node.Split ->
        if (left.let { it is DockingFrame.Node.Leaf && it.id == id.name } ||
            right.let { it is DockingFrame.Node.Leaf && it.id == id.name })
            this
        else left.parent(id) ?: right.parent(id)
}

var DockingFrame.Node.ratio
    get() = (this as DockingFrame.Node.Split).ratio
    set(ratio) {
        (this as DockingFrame.Node.Split).ratio = ratio
    }
