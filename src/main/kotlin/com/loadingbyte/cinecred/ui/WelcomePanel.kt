package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.useResourcePath
import com.loadingbyte.cinecred.common.useResourceStream
import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BorderLayout
import java.awt.Insets
import java.awt.event.ItemEvent
import java.net.URI
import java.nio.file.Files
import java.util.*
import javax.swing.*
import javax.swing.SwingConstants.LEFT
import javax.swing.SwingConstants.TOP
import kotlin.io.path.readText


class WelcomePanel(ctrl: WelcomeController) : JPanel() {

    val openPanel = OpenPanel(ctrl)
    val preferencesPanel = PreferencesPanel(ctrl)

    private val tabPane: JTabbedPane

    init {
        val brandPanel = JPanel(MigLayout("center, wrap", "[center]", "15lp[][]-3lp[]20lp")).apply {
            add(JLabel(SVGIcon.load("/logo.svg").getScaledIcon(0.25f)))
            add(JLabel("Cinecred").apply { font = TITILLIUM_SEMI.deriveFont(H2) })
            add(JLabel(VERSION).apply { font = TITILLIUM_REGU.deriveFont(font.size2D) })
        }

        openPanel.putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
        preferencesPanel.putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")

        val changelogEditorPane = JEditorPane("text/html", CHANGELOG_HTML).apply {
            isEditable = false
            background = null
        }
        val changelogScrollPane = JScrollPane(changelogEditorPane).apply {
            border = null
            background = null
            viewport.background = null
        }
        // For some reason, we have to explicitly reset the scroll bar, and we have to wait before we do that.
        SwingUtilities.invokeLater { changelogScrollPane.verticalScrollBar.value = 0 }
        val changelogPanel = JPanel(MigLayout("insets 20lp")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(changelogScrollPane)
        }

        val licenseTextArea = JTextArea(LICENSES[0].second).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = null
            putClientProperty(STYLE, "font: monospaced")
        }
        val licenseScrollPane = JScrollPane(licenseTextArea).apply {
            border = null
            background = null
            viewport.background = null
        }
        val licenseComboBox = JComboBox(LICENSES.map { it.first }.toTypedArray())
        licenseComboBox.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                licenseTextArea.text = LICENSES[licenseComboBox.selectedIndex].second
                // We have to delay this change because for some reason; if we don't, the change has no effect.
                SwingUtilities.invokeLater { licenseScrollPane.verticalScrollBar.value = 0 }
            }
        }
        val licensesPanel = JPanel(MigLayout("insets 20lp")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(licenseComboBox, "growx")
            add(licenseScrollPane, "newline, grow, push")
        }

        tabPane = JTabbedPane(LEFT).apply {
            putClientProperty(TABBED_PANE_SHOW_CONTENT_SEPARATOR, false)
            putClientProperty(TABBED_PANE_TAB_ICON_PLACEMENT, TOP)
            putClientProperty(TABBED_PANE_TAB_INSETS, Insets(10, 14, 10, 14))
            putClientProperty(TABBED_PANE_LEADING_COMPONENT, brandPanel)
            addTab(l10n("ui.welcome.open"), FOLDER_ICON, openPanel)
            addTab(l10n("ui.welcome.preferences"), PREFERENCES_ICON, preferencesPanel)
            addTab(l10n("ui.welcome.changelog"), GIFT_ICON, changelogPanel)
            addTab(l10n("ui.welcome.licenses"), PAGE_ICON, licensesPanel)
            addChangeListener { ctrl.onChangeTab(enteredPanel = selectedTab) }
        }
        layout = BorderLayout()
        add(tabPane, BorderLayout.CENTER)
    }

    var selectedTab: JPanel
        get() = tabPane.selectedComponent as JPanel
        set(selectedTab) {
            tabPane.selectedComponent = selectedTab
        }

    fun setTabsLocked(locked: Boolean) {
        for (index in 0 until tabPane.tabCount)
            if (!locked || index != tabPane.selectedIndex)
                tabPane.setEnabledAt(index, !locked)
    }

    fun addUpdateTab(newVersion: String) {
        val browseButton = JButton(l10n("ui.update.browse"), BEARING_BOTTOM_ICON.getScaledIcon(2f)).apply {
            iconTextGap = 10
            putClientProperty(STYLE_CLASS, "h2")
            putClientProperty(BUTTON_TYPE, BUTTON_TYPE_BORDERLESS)
            addActionListener { tryBrowse(URI("https://loadingbyte.com/cinecred/")) }
        }
        val updatePanel = JPanel(MigLayout("insets 60lp", "[grow 1]15lp[grow 1]", "[]30lp[]25lp[]")).apply {
            putClientProperty(STYLE, "background: $CONTENT_BG_COLOR")
            add(JLabel(SVGIcon.load("/logo.svg").getScaledIcon(0.4f)), "right")
            add(JLabel("Cinecred").apply { font = TITILLIUM_SEMI.deriveFont(H1) }, "split 2, flowy, left, gaptop 10lp")
            add(JLabel(newVersion).apply { font = TITILLIUM_BOLD.deriveFont(H0) })
            add(JLabel("<html><center>${l10n("ui.update.msg", newVersion)}</center></html>"), "newline, span 2, center")
            add(browseButton, "newline, span 2, center")
        }
        tabPane.addTab(l10n("ui.welcome.update"), UPDATE_ICON, updatePanel)
    }


    companion object {

        private const val CONTENT_BG_COLOR = "\$TabbedPane.hoverColor"

        private val TITILLIUM_REGU = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Regular Upright" }
        private val TITILLIUM_SEMI = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Semibold Upright" }
        private val TITILLIUM_BOLD = BUNDLED_FONTS.first { it.getFontName(Locale.ROOT) == "Titillium Bold Upright" }

        private val H0 = UIManager.getFont("h0.font").size2D
        private val H1 = UIManager.getFont("h1.font").size2D
        private val H2 = UIManager.getFont("h2.font").size2D

        private val CHANGELOG_HTML = useResourceStream("/CHANGELOG.md") { it.bufferedReader().readText() }.let { src ->
            val doc = Parser.builder().build().parse(src)
            // Remove the first heading which just says "Changelog".
            doc.firstChild.unlink()
            // The first version number heading should not have a top margin.
            val attrProvider = AttributeProvider { node, tagName, attrs ->
                if (tagName == "h2" && node.previous == null)
                    attrs["style"] = "margin-top: 0"
            }
            val mdHtml = HtmlRenderer.builder().attributeProviderFactory { attrProvider }.build().render(doc)
            """
                <html>
                    <head><style>
                        h2 { margin-top: 30; margin-bottom: 5; font-size: 20pt; text-decoration: underline }
                        h3 { margin-top: 15; margin-bottom: 5; font-size: 15pt }
                        h4 { margin-top: 15; margin-bottom: 4; font-size: 13pt }
                        ul { margin-top: 0; margin-bottom: 0; margin-left: 25 }
                        li { margin-top: 3 }
                    </style></head>
                    <body>$mdHtml</body>
                </html>
            """
        }

        private val LICENSES = useResourcePath("/licenses") { licensesDir ->
            Files.walk(licensesDir).filter(Files::isRegularFile).map { file ->
                Pair(licensesDir.relativize(file).toString(), file.readText())
            }.sorted(compareBy { it.first }).toList()
        }

    }


    class PreferencesPanel(ctrl: WelcomeController) : JPanel(MigLayout("insets 20lp")) {

        val preferencesForm = PreferencesForm(ctrl)

        init {
            preferencesForm.background = null
            for (line in l10n("ui.preferences.msg").split("\n"))
                add(newLabelTextArea(line), "width 100%, wrap")
            add(preferencesForm, "gaptop para")
        }

    }

}
