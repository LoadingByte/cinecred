package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.PreferencesStorage.LocaleWish
import com.loadingbyte.cinecred.ui.helper.CheckBoxWidget
import com.loadingbyte.cinecred.ui.helper.ComboBoxWidget
import com.loadingbyte.cinecred.ui.helper.EasyForm
import java.util.*


class PreferencesForm(private val ctrl: WelcomeController) : EasyForm(insets = false) {

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

    private val hintTrackPendingWidgets = HINT_TRACK_NAMES.associateWith { trackName ->
        addWidget(
            l10n("ui.preferences.hintTrackPending.$trackName"),
            CheckBoxWidget()
        )
    }

    var liveSaving = true

    fun loadAllPreferences() {
        liveSaving = false
        try {
            uiLocaleWishWidget.value = PreferencesStorage.uiLocaleWish
            checkForUpdatesWidget.value = PreferencesStorage.checkForUpdates
            for ((trackName, widget) in hintTrackPendingWidgets.entries)
                widget.value = PreferencesStorage.isHintTrackPending(trackName)
        } finally {
            liveSaving = true
        }
    }

    fun saveAllPreferences() {
        ctrl.configureUILocaleWish(uiLocaleWishWidget.value)
        ctrl.configureCheckForUpdates(checkForUpdatesWidget.value)
        for ((trackName, widget) in hintTrackPendingWidgets.entries)
            ctrl.configureHintTrackPending(trackName, widget.value)
    }

    override fun onChange(widget: Widget<*>) {
        if (!liveSaving)
            return
        when (widget) {
            uiLocaleWishWidget -> ctrl.configureUILocaleWish(uiLocaleWishWidget.value)
            checkForUpdatesWidget -> ctrl.configureCheckForUpdates(checkForUpdatesWidget.value)
            in hintTrackPendingWidgets.values -> hintTrackPendingWidgets.entries
                .first { it.value == widget }
                .let { ctrl.configureHintTrackPending(it.key, it.value.value) }
            else -> throw IllegalStateException("Unknown widget, should never happen.")
        }
        super.onChange(widget)
    }

}
