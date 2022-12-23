package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.center
import com.loadingbyte.cinecred.ui.helper.setup
import com.loadingbyte.cinecred.ui.helper.snapToSide
import com.loadingbyte.cinecred.ui.styling.EditStylingPanel
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog


enum class ProjectDialogType { STYLING, VIDEO, DELIVERY }


private fun JDialog.setupProjectDialog(ctrl: ProjectController, type: ProjectDialogType) {
    setup()
    title = "${ctrl.projectName} \u2013 ${l10n("ui.${type.name.lowercase()}.title")} \u2013 Cinecred"

    addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            ctrl.setDialogVisible(type, false)
        }
    })
}


class StylingDialog(ctrl: ProjectController) : JDialog(ctrl.projectFrame) {

    val panel = EditStylingPanel(ctrl)

    init {
        setupProjectDialog(ctrl, ProjectDialogType.STYLING)
        // Make the window fill the right half of the screen.
        snapToSide(ctrl.openOnScreen, rightSide = true)
        contentPane.add(panel)
    }

}


class VideoDialog(ctrl: ProjectController) : JDialog(ctrl.projectFrame) {

    val panel = VideoPanel(ctrl)

    init {
        setupProjectDialog(ctrl, ProjectDialogType.VIDEO)
        center(ctrl.openOnScreen, 0.5, 0.5)
        contentPane.add(panel)
    }

}


class DeliveryDialog(ctrl: ProjectController) : JDialog(ctrl.projectFrame) {

    val panel = DeliveryPanel(ctrl)

    init {
        setupProjectDialog(ctrl, ProjectDialogType.DELIVERY)
        center(ctrl.openOnScreen, 0.45, 0.6)
        contentPane.add(panel)
    }

}
