package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame


object OpenFrame : JFrame("Cinecred \u2013 ${l10n("ui.open.title")}") {

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                OpenController.onCloseOpenFrame()
            }
        })

        iconImages = WINDOW_ICON_IMAGES

        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width * 9 / 20, maxWinBounds.height / 2)
        setLocationRelativeTo(null)  // Center

        contentPane.add(OpenPanel)
    }

}
