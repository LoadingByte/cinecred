package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import com.loadingbyte.cinecred.ui.helper.setup
import com.loadingbyte.cinecred.ui.helper.snapToSide
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame


class ProjectFrame(ctrl: ProjectController) : JFrame("${ctrl.projectName} \u2013 Cinecred") {

    val panel = EditPanel(ctrl)

    init {
        setup()
        iconImages = WINDOW_ICON_IMAGES

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                ctrl.tryCloseProject()
            }
        })

        // Make the window fill the left half of the screen.
        snapToSide(ctrl.openOnScreen, rightSide = false)

        // On macOS, show the opened project folder in the window title bar.
        rootPane.putClientProperty("Window.documentFile", ctrl.projectDir.toFile())

        contentPane.add(panel)
    }

    fun onStylingChange(isUnsaved: Boolean) {
        // On macOS, show an unsaved indicator inside the "close window" button.
        rootPane.putClientProperty("Window.documentModified", isUnsaved)
    }

}
