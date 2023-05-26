package com.loadingbyte.cinecred.ui.view.welcome

import com.formdev.flatlaf.FlatClientProperties.STYLE
import com.formdev.flatlaf.ui.FlatRoundBorder
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.service.SERVICE_PROVIDERS
import com.loadingbyte.cinecred.projectio.service.Service
import com.loadingbyte.cinecred.projectio.service.ServiceProvider
import com.loadingbyte.cinecred.ui.comms.Preferences
import com.loadingbyte.cinecred.ui.comms.PreferencesCard
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import javax.swing.*


class PreferencesPanel(private val welcomeCtrl: WelcomeCtrlComms) : JPanel() {

    private val cards = CardLayout().also { layout = it }

    private val startPreferencesForm: PreferencesForm
    private val startLowerPanel: JPanel
    private val startServicesPanel: JPanel
    private val startServicesRemovalButtons = HashMap<Service, JButton>()
    private val configureServiceForm: ConfigureServiceForm
    private val configureServiceAuthorizeButton: JButton
    private val authorizeServiceErrorTextArea: JTextArea
    private val authorizeServiceResponseTextArea: JTextArea

    init {
        startPreferencesForm = PreferencesForm(welcomeCtrl).apply {
            background = null
        }

        val startAddServiceButton = JButton(l10n("ui.preferences.services.add"), ADD_ICON).apply {
            addActionListener { welcomeCtrl.preferences_start_onClickAddService() }
        }
        startServicesPanel = JPanel(MigLayout("insets 0, wrap 2, fillx", "[sg, fill][sg, fill]")).apply {
            background = null
        }

        startLowerPanel = JPanel(MigLayout("insets 0, wrap")).apply {
            background = null
            add(JSeparator(), "growx, gapy unrel unrel")
            add(startAddServiceButton)
            add(startServicesPanel, "grow, push, gaptop rel")
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

        configureServiceForm = ConfigureServiceForm().apply {
            background = null
        }
        val configureServiceCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.preferences_configureService_onClickCancel() }
        }
        configureServiceAuthorizeButton = JButton(l10n("ui.preferences.services.configure.authorize"), ARROW_RIGHT_ICON)
        configureServiceAuthorizeButton.addActionListener {
            welcomeCtrl.preferences_configureService_onClickAuthorize(
                configureServiceForm.labelWidget.value,
                configureServiceForm.providerWidget.value
            )
        }
        val configureServicePanel = JPanel(MigLayout("insets 20, wrap")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.preferences.services.configure.prompt")), "growx")
            add(configureServiceForm, "grow, push, gaptop para")
            add(configureServiceCancelButton, "split 2, right")
            add(configureServiceAuthorizeButton)
        }

        authorizeServiceErrorTextArea = newLabelTextArea(l10n("ui.preferences.services.authorize.error")).apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        authorizeServiceResponseTextArea = newLabelTextArea().apply {
            putClientProperty(STYLE, "foreground: $PALETTE_RED")
        }
        val authorizeServiceCancelButton = JButton(l10n("cancel"), CROSS_ICON).apply {
            addActionListener { welcomeCtrl.preferences_authorizeService_onClickCancel() }
        }
        val authorizeServicePanel = JPanel(MigLayout("insets 20, wrap", "", "[][][]push[]")).apply {
            background = null
            add(newLabelTextArea(l10n("ui.preferences.services.authorize.msg")), "growx, pushx")
            add(authorizeServiceErrorTextArea, "growx")
            add(authorizeServiceResponseTextArea, "growx")
            add(authorizeServiceCancelButton, "right")
        }

        add(startPanel, PreferencesCard.START.name)
        add(configureServicePanel, PreferencesCard.CONFIGURE_SERVICE.name)
        add(authorizeServicePanel, PreferencesCard.AUTHORIZE_SERVICE.name)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun preferences_setCard(card: PreferencesCard) {
        if (card == PreferencesCard.CONFIGURE_SERVICE)
            configureServiceForm.labelWidget.value = ""
        cards.show(this, card.name)
    }

    fun preferences_start_setPreferences(preferences: Preferences) {
        startPreferencesForm.setPreferences(preferences)
    }

    fun preferences_start_setInitialSetup(initialSetup: Boolean, doneListener: (() -> Unit)?) {
        require(initialSetup == (doneListener != null))
        startLowerPanel.isVisible = !initialSetup
        startPreferencesForm.setPreferencesSubmitButton(doneListener)
    }

    fun preferences_start_setServices(services: List<Service>) {
        startServicesPanel.removeAll()
        startServicesRemovalButtons.keys.retainAll(services)
        for (service in services) {
            val removeButton = JButton(TRASH_ICON).apply {
                addActionListener { welcomeCtrl.preferences_start_onClickRemoveService(service) }
            }
            val servicePanel = JPanel(MigLayout("", "[]push[]")).apply {
                border = FlatRoundBorder()
                add(JLabel(service.provider.label, GLOBE_ICON, JLabel.LEADING), "split 2, flowy")
                add(JLabel(service.id, LABEL_ICON, JLabel.LEADING))
                add(removeButton)
            }
            startServicesPanel.add(servicePanel)
            startServicesRemovalButtons.merge(service, removeButton) { o, n -> n.apply { isEnabled = o.isEnabled } }
        }
    }

    fun preferences_start_setServiceRemovalLocked(service: Service, locked: Boolean) {
        startServicesRemovalButtons[service]?.isEnabled = !locked
    }

    fun preferences_authorizeService_setError(error: String?) {
        val hasError = error != null
        authorizeServiceErrorTextArea.isVisible = hasError
        authorizeServiceResponseTextArea.isVisible = hasError
        authorizeServiceResponseTextArea.text = error ?: ""
    }


    private inner class ConfigureServiceForm : EasyForm(insets = false, noticeArea = true, constLabelWidth = false) {

        val labelWidget = addWidget(
            l10n("ui.preferences.services.configure.label"),
            TextWidget().apply { value = " " /* so that the first resetting of this widget triggers a verification */ },
            verify = { label -> welcomeCtrl.preferences_verifyLabel(label)?.let { Notice(Severity.ERROR, it) } }
        )

        val providerWidget = addWidget(
            l10n("ui.preferences.services.configure.provider"),
            ComboBoxWidget(
                ServiceProvider::class.java, SERVICE_PROVIDERS, widthSpec = WidthSpec.WIDE,
                toString = ServiceProvider::label
            )
        )

        override fun onChange(widget: Widget<*>) {
            super.onChange(widget)
            configureServiceAuthorizeButton.isEnabled = isErrorFree
        }

    }

}
