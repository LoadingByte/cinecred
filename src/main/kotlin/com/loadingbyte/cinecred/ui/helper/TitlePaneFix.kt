package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatRootPaneUI
import com.formdev.flatlaf.ui.FlatTitlePane
import java.awt.Dialog
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import javax.swing.UIManager


/** Adds maximization buttons to all plain dialog windows, i.e., dialogs that are not info/warning/error etc. */
fun fixTitlePane() {
    UIManager.put("RootPaneUI", CcRootPaneUI::class.java.name)
}


class CcRootPaneUI : FlatRootPaneUI() {
    companion object {
        @JvmStatic
        fun createUI(@Suppress("UNUSED_PARAMETER") c: JComponent) = CcRootPaneUI()
    }

    override fun createTitlePane(): FlatTitlePane = CcTitlePane(rootPane)
}


private class CcTitlePane(rootPane: JRootPane) : FlatTitlePane(rootPane) {

    private var restoreBounds: Rectangle? = null
    private var maximizedBounds: Rectangle? = null

    override fun createHandler() = object : Handler() {

        override fun mouseClicked(e: MouseEvent) {
            super.mouseClicked(e)
            // Maximize/restore when the user double-clicks the title bar.
            if (window is Dialog && e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) &&
                SwingUtilities.getDeepestComponentAt(this@CcTitlePane, e.x, e.y) != iconLabel
            )
                if (restoreBounds == null) maximize() else restore()
        }

        override fun mouseDragged(e: MouseEvent) {
            // Restore when the dialog is maximized and the user drags the title bar.
            val restoreBounds = restoreBounds
            if (window is Dialog && restoreBounds != null)
                window.setBounds(
                    restoreBounds.width * (e.x - window.x) / window.width,
                    window.y, restoreBounds.width, restoreBounds.height
                )
            super.mouseDragged(e)
        }

        override fun componentResized(e: ComponentEvent) {
            super.componentResized(e)
            // Mark the window as no longer maximized when the user or another function in this class resizes it.
            if (window is Dialog && restoreBounds != null && window.bounds != maximizedBounds) {
                restoreBounds = null
                maximizedBounds = null
                maximizeButton.isVisible = true
                restoreButton.isVisible = false
            }
        }

    }

    override fun createButtons() {
        super.createButtons()
        if (rootPane.windowDecorationStyle == JRootPane.PLAIN_DIALOG) {
            val gap = FlatTitlePane::class.java.getDeclaredField("maximizeCloseGapComp")
                .apply { isAccessible = true }.get(this) as JComponent
            maximizeButton.isVisible = true
            gap.isVisible = true
            buttonPanel.add(maximizeButton, 0)
            buttonPanel.add(restoreButton, 1)
            buttonPanel.add(gap, 2)
        }
    }

    override fun maximize() {
        super.maximize()
        if (window is Dialog) {
            restoreBounds = window.bounds
            maximizedBounds = window.graphicsConfiguration.usableBounds
            window.bounds = maximizedBounds!!
            maximizeButton.isVisible = false
            restoreButton.isVisible = true
        }
    }

    override fun restore() {
        super.restore()
        if (window is Dialog)
            restoreBounds?.let { window.bounds = it }
    }

}
