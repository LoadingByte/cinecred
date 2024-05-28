package com.loadingbyte.cinecred.ui.view.playback

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.helper.center
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog


class PlaybackDialog(ctrl: ProjectController, private val playbackCtrl: PlaybackCtrlComms) :
    JDialog(ctrl.projectFrame, "${ctrl.projectName} \u2013 ${l10n("ui.video.title")} \u2013 Cinecred") {

    val panel = PlaybackPanel(playbackCtrl, this)

    init {
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                playbackCtrl.setDialogVisibility(true)
            }

            override fun windowClosing(e: WindowEvent) {
                playbackCtrl.setDialogVisibility(false)
            }
        })

        center(ctrl.openOnScreen, 0.5, 0.5)
        contentPane.add(panel)
    }

}
