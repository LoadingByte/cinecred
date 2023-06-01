package com.loadingbyte.cinecred.ui.view.welcome

import com.formdev.flatlaf.FlatClientProperties.STYLE
import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.formdev.flatlaf.ui.FlatRoundBorder
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.projectio.service.SERVICES
import com.loadingbyte.cinecred.projectio.service.Service
import com.loadingbyte.cinecred.ui.comms.PreferencesCard
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.*
import java.util.*
import javax.swing.*
import kotlin.jvm.optionals.getOrNull


class PreferencesPanel(private val welcomeCtrl: WelcomeCtrlComms) : JPanel() {

    val startPreferencesForm: PreferencesForm

    private val cards = CardLayout().also { layout = it }

    private val startLowerPanel: JPanel
    private val startAccountsPanel: JPanel
    private val startAccountsRemovalButtons = HashMap<Account, JButton>()

    private val configureAccountForm: ConfigureAccountForm
    private val configureAccountAuthorizeButton: JButton

    private val authorizeAccountErrorTextArea: JTextArea
    private val authorizeAccountResponseTextArea: JTextArea

    init {
        startPreferencesForm = PreferencesForm(welcomeCtrl).apply {
            background = null
        }

        val startAddAccountButton = JButton(l10n("ui.preferences.accounts.add"), ADD_ICON).apply {
            addActionListener { welcomeCtrl.preferences_start_onClickAddAccount() }
        }
        startAccountsPanel = JPanel(MigLayout("insets 0, wrap 2, fillx", "[sg, fill][sg, fill]")).apply {
            background = null
        }

        startLowerPanel = JPanel(MigLayout("insets 0, wrap")).apply {
            background = null
            add(JSeparator(), "growx, pushx, gapy unrel unrel")
            add(startAddAccountButton)
            add(startAccountsPanel, "growx, pushx, gaptop rel")
        }
        val startWidgetsPanel = JPanel(MigLayout("insets 0, wrap, gapy 0")).apply {
            background = null
            add(startPreferencesForm, "growx")
            add(startLowerPanel, "grow, push")
        }
        val startScrollPane = JScrollPane(startWidgetsPanel).apply {
            border = null
            background = null
            viewport.background = null
            verticalScrollBar.unitIncrement = 10
            verticalScrollBar.blockIncrement = 100
        }
        val startPanel = JPanel(MigLayout("insets 20, wrap")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.preferences.msg")), "hmin pref, growx")
            add(startScrollPane, "grow, push, gaptop para")
        }

        configureAccountForm = ConfigureAccountForm().apply {
            background = null
        }
        val configureAccountCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.preferences_configureAccount_onClickCancel() }
        }
        configureAccountAuthorizeButton = JButton().apply {
            margin = Insets(1, 1, 1, margin.right)
            iconTextGap = margin.right
            putClientProperty(STYLE_CLASS, "large")
        }
        configureAccountAuthorizeButton.addActionListener {
            welcomeCtrl.preferences_configureAccount_onClickAuthorize(
                configureAccountForm.labelWidget.value,
                configureAccountForm.serviceWidget.value.get()
            )
        }
        configureAccountForm.onChange(configureAccountForm.labelWidget)  // Run validation
        val configureAccountPanel = JPanel(MigLayout("insets 20, wrap")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.preferences.accounts.configure.prompt")), "growx")
            add(configureAccountForm, "grow, push, gaptop para")
            add(configureAccountCancelButton, "split 2, right, bottom")
            add(configureAccountAuthorizeButton, "gapleft unrel, hidemode 3")
        }

        authorizeAccountErrorTextArea = newLabelTextArea(l10n("ui.preferences.accounts.authorize.error")).apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        authorizeAccountResponseTextArea = newLabelTextArea().apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        val authorizeAccountCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.preferences_authorizeAccount_onClickCancel() }
        }
        val authorizeAccountPanel = JPanel(MigLayout("insets 20, wrap", "", "[][][]push[]")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.preferences.accounts.authorize.msg")), "growx, pushx")
            add(authorizeAccountErrorTextArea, "growx")
            add(authorizeAccountResponseTextArea, "growx")
            add(authorizeAccountCancelButton, "right")
        }

        add(startPanel, PreferencesCard.START.name)
        add(configureAccountPanel, PreferencesCard.CONFIGURE_ACCOUNT.name)
        add(authorizeAccountPanel, PreferencesCard.AUTHORIZE_ACCOUNT.name)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun preferences_setCard(card: PreferencesCard) {
        cards.show(this, card.name)
    }

    fun preferences_start_setInitialSetup(initialSetup: Boolean, doneListener: (() -> Unit)?) {
        require(initialSetup == (doneListener != null))
        startLowerPanel.isVisible = !initialSetup
        startPreferencesForm.setPreferencesSubmitButton(doneListener)
    }

    fun preferences_start_setAccounts(accounts: List<Account>) {
        startAccountsPanel.removeAll()
        startAccountsRemovalButtons.keys.retainAll(accounts)
        for (account in accounts) {
            val removeButton = JButton(TRASH_ICON).apply {
                addActionListener { welcomeCtrl.preferences_start_onClickRemoveAccount(account) }
            }
            val accountPanel = JPanel(MigLayout("", "[]push[]")).apply {
                border = FlatRoundBorder()
                add(JLabel(account.service.product, GLOBE_ICON, JLabel.LEADING), "split 2, flowy")
                add(JLabel(account.id, LABEL_ICON, JLabel.LEADING).apply { toolTipText = account.id }, "wmax 230")
                add(removeButton)
            }
            startAccountsPanel.add(accountPanel)
            startAccountsRemovalButtons.merge(account, removeButton) { o, n -> n.apply { isEnabled = o.isEnabled } }
        }
        startAccountsPanel.isVisible = accounts.isNotEmpty()
    }

    fun preferences_start_setAccountRemovalLocked(account: Account, locked: Boolean) {
        startAccountsRemovalButtons[account]?.isEnabled = !locked
    }

    fun preferences_configureAccount_resetForm() {
        configureAccountForm.apply {
            labelWidget.value = ""
            serviceWidget.value = Optional.empty()
        }
        configureAccountForm.labelWidget.value = ""
    }

    fun preferences_authorizeAccount_setError(error: String?) {
        val hasError = error != null
        authorizeAccountErrorTextArea.isVisible = hasError
        authorizeAccountResponseTextArea.isVisible = hasError
        authorizeAccountResponseTextArea.text = error ?: ""
    }


    private inner class ConfigureAccountForm : EasyForm(insets = false, noticeArea = true, constLabelWidth = false) {

        val labelWidget = addWidget(
            l10n("ui.preferences.accounts.configure.label"),
            TextWidget(),
            verify = { label ->
                welcomeCtrl.preferences_configureAccount_verifyLabel(label)?.let { Notice(Severity.ERROR, it) }
            }
        )

        val serviceWidget = addWidget(
            l10n("ui.preferences.accounts.configure.service"),
            OptionalComboBoxWidget(
                Service::class.java, SERVICES, widthSpec = WidthSpec.WIDE,
                toString = Service::product
            ).apply { value = Optional.empty() },
            verify = { optional ->
                if (optional.isPresent) null else
                    Notice(Severity.ERROR, l10n("ui.preferences.accounts.configure.noServiceSelected"))
            }
        )

        public override fun onChange(widget: Widget<*>) {
            super.onChange(widget)
            val service = serviceWidget.value.getOrNull()
            val authorizeText = service
                ?.let { l10n("ui.preferences.accounts.configure.authorize", service.authenticator) }.orEmpty()
            val authorizeIcon = service?.let {
                val icon = service.icon
                object : FlatAbstractIcon(36, 36, null) {
                    override fun paintIcon(c: Component, g2: Graphics2D) {
                        g2.color = Color.WHITE
                        g2.fillRoundRect(0, 0, iconWidth, iconHeight, 4, 4)
                        icon.paintIcon(c, g2, (iconWidth - icon.iconWidth) / 2, (iconHeight - icon.iconHeight) / 2)
                    }
                }
            }
            configureAccountAuthorizeButton.apply {
                isVisible = isErrorFree
                text = authorizeText
                icon = authorizeIcon
            }
        }

    }

}
