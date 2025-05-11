package com.loadingbyte.cinecred.ui.view.welcome

import com.loadingbyte.cinecred.common.ROOT_CASE_INSENSITIVE_COLLATOR
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.sortedWithCollator
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.helper.CheckBoxWidget
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.EasyForm
import java.util.*


class PreferencesForm(private val welcomeCtrl: WelcomeCtrlComms) :
    EasyForm(insets = false, noticeArea = false, constLabelWidth = false) {

    private val uiLocaleWishWidget = addWidget(
        l10n("ui.preferences.uiLocaleWish"),
        ComboBoxWidget(
            LocaleWish::class.java,
            listOf(LocaleWish.System) + TRANSLATED_LOCALES
                .sortedWithCollator(ROOT_CASE_INSENSITIVE_COLLATOR) { it.getDisplayName(it) }
                .map(LocaleWish::Specific),
            toString = { wish ->
                when (wish) {
                    is LocaleWish.System -> l10n("ui.preferences.uiLocaleWishSystem")
                    is LocaleWish.Specific ->
                        if (Locale.getDefault() != wish.locale)
                            "${wish.locale.getDisplayName(wish.locale)} \u2013 ${wish.locale.displayName}"
                        else
                            wish.locale.displayName
                }
            })
    )

    private val checkForUpdatesWidget = addWidget(
        l10n("ui.preferences.checkForUpdates"),
        CheckBoxWidget(),
        description = l10n("ui.preferences.checkForUpdates.desc")
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

    private fun <V : Any> load(widget: Widget<V>, value: V) {
        disableOnChange = true
        try {
            widget.value = value
        } finally {
            disableOnChange = false
        }
    }

    override fun onChange(widget: Widget<*>) {
        fun <P : Any> forward(preference: Preference<P>, value: P) {
            welcomeCtrl.preferences_start_onChangeTopPreference(preference, value)
        }

        if (disableOnChange)
            return
        when (widget) {
            uiLocaleWishWidget ->
                forward(UI_LOCALE_PREFERENCE, uiLocaleWishWidget.value)
            checkForUpdatesWidget ->
                forward(CHECK_FOR_UPDATES_PREFERENCE, checkForUpdatesWidget.value)
            welcomeHintTrckPendingWidget ->
                forward(WELCOME_HINT_TRACK_PENDING_PREFERENCE, welcomeHintTrckPendingWidget.value)
            projectHintTrckPendingWidget ->
                forward(PROJECT_HINT_TRACK_PENDING_PREFERENCE, projectHintTrckPendingWidget.value)
            else -> throw IllegalStateException("Unknown widget, should never happen.")
        }
        super.onChange(widget)
    }

    fun setPreferencesSubmitButton(listener: (() -> Unit)?) {
        removeSubmitButton()
        if (listener != null)
            addSubmitButton(l10n("ui.preferences.finishInitialSetup"), listener)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    fun preferences_start_setUILocaleWish(wish: LocaleWish) = load(uiLocaleWishWidget, wish)
    fun preferences_start_setCheckForUpdates(check: Boolean) = load(checkForUpdatesWidget, check)
    fun preferences_start_setWelcomeHintTrackPending(pending: Boolean) = load(welcomeHintTrckPendingWidget, pending)
    fun preferences_start_setProjectHintTrackPending(pending: Boolean) = load(projectHintTrckPendingWidget, pending)

}
