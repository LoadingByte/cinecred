package com.loadingbyte.cinecred.ui

import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JTabbedPane


object MainFrame : JFrame("Cinecred") {

    private val tabs = JTabbedPane()

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                Controller.tryClose()
            }
        })

        // Make the window fill the right half of the screen.
        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width / 2, maxWinBounds.height)
        setLocation(maxWinBounds.x + maxWinBounds.width / 2, 0)

        tabs.apply {
            addTab("1. Create/Open", DIR_ICON, OpenPanel)
            addTab("2. Style/Review", COMPUTER_ICON, EditPanel)
            addTab("3. Deliver", FLOPPY_ICON, DeliverPanel)
            setEnabledAt(1, false)
            setEnabledAt(2, false)
        }
        contentPane.add(tabs)
    }

    fun onOpenProjectDir() {
        tabs.apply {
            setEnabledAt(1, true)
            setEnabledAt(2, true)
            selectedIndex = 1
        }
    }

}
