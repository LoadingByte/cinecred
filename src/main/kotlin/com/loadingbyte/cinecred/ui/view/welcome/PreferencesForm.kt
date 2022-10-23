package com.loadingbyte.cinecred.ui.view.welcome

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.comms.LocaleWish
import com.loadingbyte.cinecred.ui.comms.Preferences
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.CheckBoxWidget
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.EasyForm
import java.util.*


class PreferencesForm(private val welcomeCtrl: WelcomeCtrlComms) : EasyForm(insets = false) {

    private val uiLocaleWishWidget = addWidget(
        l10n("ui.preferences.uiLocaleWish"),
        ComboBoxWidget(
            LocaleWish::class.java,
            listOf(LocaleWish.System) + TRANSLATED_LOCALES.map(LocaleWish::Specific),
            toString = { wish ->
                when (wish) {
                    is LocaleWish.System -> l10n("ui.preferences.uiLocaleWishSystem")
                    is LocaleWish.Specific ->
                        if (Locale.getDefault() != wish.locale)
                            "${wish.locale.getDisplayName(wish.locale)} (${wish.locale.displayName})"
                        else
                            wish.locale.displayName
                }
            })
    )

    private val checkForUpdatesWidget = addWidget(
        l10n("ui.preferences.checkForUpdates"),
        CheckBoxWidget()
    )

    private val welcomeHintTrckPendingWidget = addWidget(
        l10n("ui.preferences.hintTrackPending.welcome"),
        CheckBoxWidget()
    )

    private val projectHintTrckPendingWidget = addWidget(
        l10n("ui.preferences.hintTrackPending.project"),
        CheckBoxWidget()
    )

    private var disableOnChange = false

    fun setPreferences(preferences: Preferences) {
        disableOnChange = true
        try {
            uiLocaleWishWidget.value = preferences.uiLocaleWish
            checkForUpdatesWidget.value = preferences.checkForUpdates
            welcomeHintTrckPendingWidget.value = preferences.welcomeHintTrackPending
            projectHintTrckPendingWidget.value = preferences.projectHintTrackPending
        } finally {
            disableOnChange = false
        }
    }

    override fun onChange(widget: Widget<*>) {
        if (disableOnChange)
            return
        when (widget) {
            uiLocaleWishWidget ->
                welcomeCtrl.onChangePreference(Preferences::uiLocaleWish, uiLocaleWishWidget.value)
            checkForUpdatesWidget ->
                welcomeCtrl.onChangePreference(Preferences::checkForUpdates, checkForUpdatesWidget.value)
            welcomeHintTrckPendingWidget ->
                welcomeCtrl.onChangePreference(Preferences::welcomeHintTrackPending, welcomeHintTrckPendingWidget.value)
            projectHintTrckPendingWidget ->
                welcomeCtrl.onChangePreference(Preferences::projectHintTrackPending, projectHintTrckPendingWidget.value)
            else -> throw IllegalStateException("Unknown widget, should never happen.")
        }
        super.onChange(widget)
    }

    fun setPreferencesSubmitButton(listener: (() -> Unit)?) {
        removeSubmitButton()
        if (listener != null)
            addSubmitButton(l10n("ui.preferences.finishInitialSetup"), listener)
    }

}
