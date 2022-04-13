package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame


class WelcomeFrame(ctrl: WelcomeController) : JFrame(l10n("ui.welcome.title")) {

    val panel = WelcomePanel(ctrl)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                ctrl.close()
            }
        })

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(panel)
    }

}
