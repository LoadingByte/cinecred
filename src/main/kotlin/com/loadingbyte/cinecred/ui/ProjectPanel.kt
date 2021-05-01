package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.DELIVER_ICON
import com.loadingbyte.cinecred.ui.helper.EYE_ICON
import com.loadingbyte.cinecred.ui.helper.FOLDER_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane


class ProjectPanel(ctrl: ProjectController) : JPanel() {

    val editPanel = EditPanel(ctrl)
    val videoPanel = VideoPanel(ctrl)
    val deliverPanel = DeliverPanel(ctrl)

    init {
        val tabs = JTabbedPane().apply {
            putClientProperty(TABBED_PANE_SHOW_TAB_SEPARATORS, true)
            addTab(l10n("ui.project.style"), EYE_ICON, editPanel)
            addTab(l10n("ui.project.video"), PLAY_ICON, videoPanel)
            addTab(l10n("ui.project.deliver"), DELIVER_ICON, deliverPanel)
            addChangeListener {
                ctrl.onChangeTab(changedToEdit = selectedComponent == editPanel)
            }
        }

        val openButton = JButton(l10n("ui.project.openAnother"), FOLDER_ICON).apply {
            background = null
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_SQUARE)
            putClientProperty(OUTLINE, Color(0, 0, 0, 0))
            addActionListener { OpenController.showOpenFrame() }
        }
        val openButtonPanel = JPanel(BorderLayout()).apply { add(openButton, BorderLayout.LINE_END) }
        tabs.putClientProperty(TABBED_PANE_TRAILING_COMPONENT, openButtonPanel)

        layout = BorderLayout()
        add(tabs, BorderLayout.CENTER)
    }

}
