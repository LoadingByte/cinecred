package com.loadingbyte.cinecred.ui.view.welcome

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.ui.comms.License
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Insets
import java.awt.event.ItemEvent
import java.net.URI
import java.util.*
import javax.swing.*
import javax.swing.SwingConstants.LEFT
import javax.swing.SwingConstants.TOP
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants


class WelcomePanel(welcomeCtrl: WelcomeCtrlComms) : JPanel() {

    val projectsPanel = ProjectsPanel(welcomeCtrl)
    val preferencesPanel = PreferencesPanel(welcomeCtrl)

    private val tabPane: JTabbedPane
    private val changelogScrollPane: JScrollPane
    private val changelogEditorPane: JEditorPane
    private val licenseComboBox: JComboBox<License>

    private val updatePanel: JPanel
    private val updateVersionLabel = JLabel()
    private val updateMessageTextPane: JTextPane

    init {
        val brandPanel = JPanel(MigLayout("center, wrap", "[center]", "15[][]-3[]20")).apply {
            add(JLabel(SVGIcon.load("/logo.svg").getScaledIcon(0.25f)))
            add(JLabel("Cinecred").apply {
                font = TITILLIUM_SEMI.deriveFont(H2)
                putClientProperty(STYLE, "foreground: #FFF")
            })
            add(JLabel(VERSION).apply {
                font = TITILLIUM_REGU.deriveFont(font.size2D)
                putClientProperty(STYLE, "foreground: #FFF")
            })
        }

        projectsPanel.putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
        preferencesPanel.putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")

        changelogEditorPane = newLabelEditorPane("text/html")
        changelogScrollPane = JScrollPane(changelogEditorPane).apply {
            border = null
            background = null
            viewport.background = null
        }
        val changelogPanel = JPanel(MigLayout("insets 20")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(changelogScrollPane)
        }

        val licenseTextArea = newLabelTextArea().apply {
            putClientProperty(STYLE_CLASS, "monospaced")
        }
        val licenseScrollPane = JScrollPane(licenseTextArea).apply {
            border = null
            background = null
            viewport.background = null
        }
        licenseComboBox = JComboBox<License>().apply {
            renderer = CustomToStringListCellRenderer(License::class.java, License::name)
        }
        licenseComboBox.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                licenseTextArea.text = (licenseComboBox.selectedItem as License).body
                licenseScrollPane.verticalScrollBar.value = 0
            }
        }
        val licensesPanel = JPanel(MigLayout("insets 20")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(licenseComboBox, "growx")
            add(licenseScrollPane, "newline, grow, push, gaptop 10")
        }

        updateMessageTextPane = newLabelTextPane()
        val updateBrowseButton = JButton(l10n("ui.update.browse"), BEARING_BOTTOM_ICON.getScaledIcon(2f)).apply {
            iconTextGap = 10
            putClientProperty(STYLE_CLASS, "h2")
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
            addActionListener { tryBrowse(URI("https://loadingbyte.com/cinecred/")) }
        }
        updatePanel = JPanel(MigLayout("insets 60", "[grow 1]15[grow 1]", "[]30[]25[]")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(JLabel(SVGIcon.load("/logo.svg").getScaledIcon(0.4f)), "right")
            add(JLabel("Cinecred").apply {
                font = TITILLIUM_SEMI.deriveFont(H1)
                putClientProperty(STYLE, "foreground: #FFF")
            }, "split 2, flowy, left, gaptop 10")
            add(updateVersionLabel.apply {
                font = TITILLIUM_BOLD.deriveFont(H0)
                putClientProperty(STYLE, "foreground: #FFF")
            })
            add(updateMessageTextPane, "newline, span 2, center")
            add(updateBrowseButton, "newline, span 2, center")
        }

        tabPane = JTabbedPane(LEFT).apply {
            putClientProperty(TABBED_PANE_SHOW_CONTENT_SEPARATOR, false)
            putClientProperty(TABBED_PANE_TAB_ICON_PLACEMENT, TOP)
            putClientProperty(TABBED_PANE_TAB_INSETS, Insets(10, 14, 10, 14))
            putClientProperty(TABBED_PANE_LEADING_COMPONENT, brandPanel)
            addTab(l10n("ui.welcome.projects"), FOLDER_ICON, projectsPanel)
            addTab(l10n("ui.welcome.preferences"), PREFERENCES_ICON, preferencesPanel)
            addTab(l10n("ui.welcome.changelog"), GIFT_ICON, changelogPanel)
            addTab(l10n("ui.welcome.licenses"), PAGE_ICON, licensesPanel)
            addChangeListener { welcomeCtrl.onChangeTab(WelcomeTab.values()[selectedIndex]) }
        }
        layout = BorderLayout()
        add(tabPane, BorderLayout.CENTER)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun getTab() =
        WelcomeTab.values()[tabPane.selectedIndex]

    fun setTab(tab: WelcomeTab) {
        tabPane.selectedIndex = tab.ordinal
    }

    fun setTabsLocked(locked: Boolean) {
        for (index in 0 until tabPane.tabCount)
            if (!locked || index != tabPane.selectedIndex)
                tabPane.setEnabledAt(index, !locked)
    }

    fun setChangelog(changelog: String) {
        changelogEditorPane.text = changelog
    }

    fun setLicenses(licenses: List<License>) {
        (licenseComboBox.model as DefaultComboBoxModel<License>).apply { removeAllElements(); addAll(licenses) }
        licenseComboBox.selectedIndex = 0
    }

    fun setUpdate(version: String) {
        if (tabPane.indexOfComponent(updatePanel) == -1)
            tabPane.addTab(l10n("ui.welcome.update"), UPDATE_ICON, updatePanel)
        updateVersionLabel.text = version
        updateMessageTextPane.text = l10n("ui.update.msg", version)
        val style = SimpleAttributeSet().also { StyleConstants.setAlignment(it, StyleConstants.ALIGN_CENTER) }
        updateMessageTextPane.styledDocument.setParagraphAttributes(0, updateMessageTextPane.text.length, style, false)
    }


    companion object {

        private const val CONTENT_BG_COLOR = "\$TabbedPane.hoverColor"

        private val TITILLIUM_REGU = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Regular Upright" }
        private val TITILLIUM_SEMI = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Semibold Upright" }
        private val TITILLIUM_BOLD = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Bold Upright" }

        private val H0 = UIManager.getFont("h0.font").size2D
        private val H1 = UIManager.getFont("h1.font").size2D
        private val H2 = UIManager.getFont("h2.font").size2D

    }


    class PreferencesPanel(welcomeCtrl: WelcomeCtrlComms) : JPanel(MigLayout("insets 20, wrap")) {

        val form = PreferencesForm(welcomeCtrl)

        init {
            form.background = null
            for (line in l10n("ui.preferences.msg").split("\n"))
                add(newLabelTextArea(line), "width 100%")
            add(form, "gaptop para")
        }

    }

}
