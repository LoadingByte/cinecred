package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.DELIVER_ICON
import com.loadingbyte.cinecred.ui.helper.EYE_ICON
import com.loadingbyte.cinecred.ui.helper.HOME_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane


class ProjectPanel(ctrl: ProjectController) : JPanel() {

    val editPanel = EditPanel(ctrl)
    val videoPanel = VideoPanel(ctrl)
    val deliverPanel = DeliverPanel(ctrl)

    private val tabPane = JTabbedPane()

    private var prevSelectedTab: JPanel = editPanel

    init {
        val tabPaneTrailingPanel = JPanel(MigLayout("insets 0, align right"))
        val homeButton = JButton(l10n("ui.project.home"), HOME_ICON).apply {
            isFocusable = false
            background = null
            putClientProperty(STYLE, "arc: 0")
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
            addActionListener { ctrl.masterCtrl.showWelcomeFrame() }
        }
        tabPaneTrailingPanel.add(homeButton, "growy, pushy")

        tabPane.apply {
            isFocusable = false
            putClientProperty(TABBED_PANE_TAB_TYPE, TABBED_PANE_TAB_TYPE_CARD)
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

        layout = BorderLayout()
        add(tabPane, BorderLayout.CENTER)
    }

    var selectedTab: JPanel
        get() = tabPane.selectedComponent as JPanel
        set(selectedTab) {
            tabPane.selectedComponent = selectedTab
        }

}
