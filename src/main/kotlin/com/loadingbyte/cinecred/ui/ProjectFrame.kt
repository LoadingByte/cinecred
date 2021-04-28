package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame


class ProjectFrame(ctrl: ProjectController) : JFrame("${ctrl.projectName} \u2013 Cinecred") {

    val panel = ProjectPanel(ctrl)

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

        contentPane.add(panel)
    }

}
