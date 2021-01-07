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
                Controller.tryExit()
            }
        })

        // Make the window fill the right half of the screen.
        val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(maxWinBounds.width / 2, maxWinBounds.height)
        setLocation(maxWinBounds.x, maxWinBounds.y)

        iconImages = WINDOW_ICON_IMAGES

        tabs.apply {
            addTab("1. Create/Open", FOLDER_ICON, OpenPanel)
            addTab("2. Style/Review", EYE_ICON, EditPanel)
            addTab("3. Deliver", DELIVER_ICON, DeliverPanel)
            setEnabledAt(1, false)
            setEnabledAt(2, false)
            addChangeListener {
                Controller.onChangeTab(changedToEdit = selectedComponent == EditPanel)
            }
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
