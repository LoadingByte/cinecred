package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane


class ProjectPanel(ctrl: ProjectController) : JPanel() {

    val editPanel = EditPanel(ctrl)
    val videoPanel = VideoPanel(ctrl)
    val deliverPanel = DeliverPanel(ctrl)

    private val tabPane = JTabbedPane()
    private val tabPaneTrailingPanel = JPanel(MigLayout("insets 0, gap 0, align right"))

    private var prevSelectedTab: JPanel = editPanel

    init {
        tabPane.apply {
            putClientProperty(TABBED_PANE_SHOW_TAB_SEPARATORS, true)
            putClientProperty(TABBED_PANE_TRAILING_COMPONENT, tabPaneTrailingPanel)
            addTab(l10n("ui.project.edit"), EYE_ICON, editPanel)
            addTab(l10n("ui.project.video"), PLAY_ICON, videoPanel)
            addTab(l10n("ui.project.deliver"), DELIVER_ICON, deliverPanel)
            addChangeListener {
                ctrl.onChangeTab(leftPanel = prevSelectedTab, enteredPanel = selectedTab)
                prevSelectedTab = selectedTab
            }
        }

        addTabPaneTrailingComponent(
            JButton(l10n("ui.project.openAnother"), FOLDER_ICON).apply {
                addActionListener { OpenController.showOpenFrame() }
            })
        addTabPaneTrailingComponent(
            JButton(l10n("ui.preferences.open"), PREFERENCES_ICON).apply {
                addActionListener {
                    PreferencesController.showPreferencesDialog(ctrl.projectFrame.graphicsConfiguration)
                }
            })

        layout = BorderLayout()
        add(tabPane, BorderLayout.CENTER)
    }

    private fun addTabPaneTrailingComponent(component: JComponent) {
        component.apply {
            alignmentX = RIGHT_ALIGNMENT
            background = null
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_SQUARE)
            putClientProperty(OUTLINE, Color(0, 0, 0, 0))
        }
        tabPaneTrailingPanel.add(component, "growy, pushy")
    }

    var selectedTab: JPanel
        get() = tabPane.selectedComponent as JPanel
        set(selectedTab) {
            tabPane.selectedComponent = selectedTab
        }

}
