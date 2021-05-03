package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import com.loadingbyte.cinecred.ui.helper.setupToSnapToSide
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

        // Make the window fill the left half of the screen.
        setupToSnapToSide(ctrl.openOnScreen, rightSide = false)

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(panel)
    }

}
