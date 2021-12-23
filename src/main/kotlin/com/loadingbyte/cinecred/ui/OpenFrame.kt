package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame


class OpenFrame : JFrame("Cinecred \u2013 ${l10n("ui.open.title")}") {

    val panel = OpenPanel(this)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                OpenController.onCloseOpenFrame()
            }
        })

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(panel)
    }

    fun findMostOccupiedScreen(): GraphicsConfiguration {
        val bounds = bounds
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { dev -> dev.defaultConfiguration }
            .maxByOrNull { cfg -> cfg.bounds.intersection(bounds).run { width * height } }!!
    }

}
