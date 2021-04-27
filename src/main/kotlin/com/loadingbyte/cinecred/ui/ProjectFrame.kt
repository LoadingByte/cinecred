package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.DELIVER_ICON
import com.loadingbyte.cinecred.ui.helper.EYE_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JTabbedPane


class ProjectFrame(ctrl: ProjectController) : JFrame("${ctrl.projectName} \u2013 Cinecred") {

    val editPanel = EditPanel(ctrl)
    val videoPanel = VideoPanel(ctrl)
    val deliverPanel = DeliverPanel(ctrl)

    private val tabs = JTabbedPane()

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                ctrl.tryCloseProject()
            }
        })

        // Make the window fill the right half of the screen.
        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width / 2, maxWinBounds.height)
        setLocation(maxWinBounds.x, maxWinBounds.y)

        iconImages = WINDOW_ICON_IMAGES

        tabs.apply {
            addTab(l10n("ui.main.style"), EYE_ICON, editPanel)
            addTab(l10n("ui.main.video"), PLAY_ICON, videoPanel)
            addTab(l10n("ui.main.deliver"), DELIVER_ICON, deliverPanel)
            addChangeListener {
                ctrl.onChangeTab(changedToEdit = selectedComponent == editPanel)
            }
        }
        contentPane.add(tabs)
    }

}
