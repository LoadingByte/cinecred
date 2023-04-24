package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.common.preserveTransform
import com.loadingbyte.cinecred.ui.helper.usableBounds
import java.awt.*
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock


inline fun buildImage(width: Int, height: Int, type: Int, draw: (Graphics2D) -> Unit): BufferedImage {
    val image = BufferedImage(width, height, type)
    val g2 = image.createGraphics()
    draw(g2)
    g2.dispose()
    return image
}


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
    buildImage(component.width, component.height, BufferedImage.TYPE_3BYTE_BGR) { g2 ->
        printWithPopups(component, g2)
    }

fun printWithPopups(component: Component, g2: Graphics2D) {
    edt {
        if (SwingUtilities.getWindowAncestor(component).isVisible)
            component.print(g2)
        forEachVisiblePopupOf(SwingUtilities.getWindowAncestor(component)) { popup ->
            g2.preserveTransform {
                val compLocOnScreen = component.locationOnScreen
                g2.translate(popup.x - compLocOnScreen.x, popup.y - compLocOnScreen.y)
                popup.print(g2)
            }
        }
    }
}

inline fun forEachVisiblePopupOf(window: Window, action: (Window) -> Unit) {
    for (popup in Window.getWindows())
        if (popup.isVisible && popup.type == Window.Type.POPUP && popup.owner == window)
            action(popup)
}


fun reposition(window: Window, width: Int, height: Int) {
    edt {
        window.minimumSize = Dimension(0, 0)

        // Add the window decorations to the size.
        val insets = window.insets
        val winWidth = width + insets.left + insets.right
        val winHeight = height + insets.top + insets.bottom

        val screenBounds = gCfg.usableBounds
        window.setBounds(
            screenBounds.x + (screenBounds.width - winWidth) / 2,
            screenBounds.y + (screenBounds.height - winHeight) / 2,
            winWidth, winHeight
        )
    }
}
