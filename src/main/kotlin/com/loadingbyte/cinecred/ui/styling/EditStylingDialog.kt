package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog


class EditStylingDialog(ctrl: ProjectController) :
    JDialog(ctrl.projectFrame, "${ctrl.projectName} \u2013 Cinecred \u2013 ${l10n("ui.styling.title")}") {

    val panel = EditStylingPanel(ctrl)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                ctrl.setEditStylingDialogVisible(false)
            }
        })

        // Make the window fill the left half of the screen.
        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width / 2, maxWinBounds.height)
        setLocation(maxWinBounds.x + maxWinBounds.width / 2, maxWinBounds.y)

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(panel)
    }

}
