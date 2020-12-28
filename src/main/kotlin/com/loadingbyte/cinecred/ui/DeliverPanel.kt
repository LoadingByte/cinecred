package com.loadingbyte.cinecred.ui

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities


object DeliverPanel : JPanel() {

    init {
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, DeliverConfigurationForm, DeliverRenderQueuePanel)
        // Slightly postpone moving the divider so that the pane knows its height when the divider is moved.
        SwingUtilities.invokeLater { splitPane.setDividerLocation(0.5) }

        // Use BorderLayout to maximize the size of the tabbed pane.
        layout = BorderLayout()
        add(splitPane, BorderLayout.CENTER)
    }

}
