package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.toPathSafely
import com.loadingbyte.cinecred.ui.comms.LocaleWish
import com.loadingbyte.cinecred.ui.comms.Preferences
import java.nio.file.Path
import java.util.*
import kotlin.io.path.pathString


object PersistentStorage : Preferences {

    private const val UI_LOCALE_KEY = "ui_locale"
    private const val CHECK_FOR_UPDATES_KEY = "check_for_updates"

    private val prefs = java.util.prefs.Preferences.userRoot().node("com/loadingbyte/cinecred")
    private val prefsProjectDirs = prefs.node("projectdirs")

    fun havePreferencesBeenSet() = UI_LOCALE_KEY in prefs.keys() && CHECK_FOR_UPDATES_KEY in prefs.keys()

    override var uiLocaleWish: LocaleWish
        get() = when (val str = prefs.get(UI_LOCALE_KEY, null)) {
            null, "system" -> LocaleWish.System
            else -> LocaleWish.Specific(Locale.forLanguageTag(str))
        }
        set(wish) {
            val value = when (wish) {
                is LocaleWish.System -> "system"
                is LocaleWish.Specific -> wish.locale.toLanguageTag()
            }
            prefs.put(UI_LOCALE_KEY, value)
        }

    override var checkForUpdates: Boolean
        get() = prefs.getBoolean(CHECK_FOR_UPDATES_KEY, true)
        set(check) {
            prefs.putBoolean(CHECK_FOR_UPDATES_KEY, check)
        }

    override var welcomeHintTrackPending: Boolean
        get() = prefs.getBoolean("open_hint_track_pending", true)
        set(pending) {
            prefs.putBoolean("open_hint_track_pending", pending)
        }

    override var projectHintTrackPending: Boolean
        get() = prefs.getBoolean("project_hint_track_pending", true)
        set(pending) {
            prefs.putBoolean("project_hint_track_pending", pending)
        }

    var memorizedProjectDirs: List<Path>
        get() = prefsProjectDirs.keys()
            .sortedBy(String::toInt)
            .mapNotNull { key -> prefsProjectDirs.get(key, null).toPathSafely() }
        set(dirs) {
            prefsProjectDirs.clear()
            for ((idx, dir) in dirs.withIndex())
                prefsProjectDirs.put(idx.toString(), dir.pathString)
        }

}
