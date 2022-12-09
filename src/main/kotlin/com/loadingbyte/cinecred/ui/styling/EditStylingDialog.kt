package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.helper.setup
import com.loadingbyte.cinecred.ui.helper.snapToSide
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog


class EditStylingDialog(ctrl: ProjectController) :
    JDialog(ctrl.projectFrame, "${ctrl.projectName} \u2013 Cinecred \u2013 ${l10n("ui.styling.title")}") {

    val panel = EditStylingPanel(ctrl)

    init {
        setup()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                ctrl.setEditStylingDialogVisible(false)
            }
        })

        // Make the window fill the right half of the screen.
        snapToSide(ctrl.openOnScreen, rightSide = true)

        contentPane.add(panel)
    }

}
