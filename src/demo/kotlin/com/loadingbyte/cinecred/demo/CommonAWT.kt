package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.ui.helper.preserveTransform
import com.loadingbyte.cinecred.ui.helper.usableBounds
import com.loadingbyte.cinecred.ui.helper.withG2
import com.loadingbyte.cinecred.ui.view.playback.PlaybackDialog
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import java.awt.*
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock


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
        if (window.isVisible)
            component.print(g2)
        if (window is PlaybackDialog) {
            @Suppress("DEPRECATION")
            val canvas = window.panel.leakedVideoCanvas
            if (SwingUtilities.isDescendingFrom(canvas, component))
                g2.preserveTransform {
                    val canvasLocInComp = SwingUtilities.convertPoint(canvas, 0, 0, component)
                    g2.translate(canvasLocInComp.x, canvasLocInComp.y)
                    canvas.print(g2)
                }
        }
        forEachVisiblePopupOf(window) { popup ->
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
        if (popup.isVisible && popup.type == Window.Type.POPUP) {
            var owner = popup
            while ((owner ?: continue) != window)
                owner = owner.owner
            action(popup)
        }
}


val Window.insetsInclTitlePane: Insets
    get() {
        val insets = this.insets
        if (this is RootPaneContainer) {
            val rootInsets = this.rootPane.insets
            insets.left += rootInsets.left
            insets.right += rootInsets.right
            insets.top += rootInsets.top + this.contentPane.y
            insets.bottom += rootInsets.bottom
        }
        return insets
    }


fun reposition(window: Window, width: Int, height: Int) {
    edt {
        window.minimumSize = Dimension(0, 0)

        // Add the window decorations to the size.
        val insets = window.insetsInclTitlePane
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


val BGR24_REPRESENTATION = Bitmap.Representation(
    Bitmap.PixelFormat.of(AV_PIX_FMT_BGR24), ColorSpace.SRGB, Bitmap.Alpha.OPAQUE
)
