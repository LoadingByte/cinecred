package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.FALLBACK_TRANSLATED_LOCALE
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import java.nio.file.Path
import java.util.*
import java.util.prefs.Preferences
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile


object PreferencesStorage {

    private const val UI_LOCALE_KEY = "ui_locale"
    private const val CHECK_FOR_UPDATES_KEY = "check_for_updates"

    /**
     * This is the translated locale which is closest to the system's default locale. It necessarily has to be computed
     * before [MasterController.applyUILocaleWish] changes the default locale for the first time and thereby destroys
     * the information. Because applyUILocaleWish() first needs to obtain the configured locale wish via [uiLocaleWish],
     * that necessity is fulfilled.
     */
    private val SYSTEM_LOCALE: Locale =
        Locale.lookup(
            listOf(Locale.LanguageRange(Locale.getDefault().toLanguageTag())), TRANSLATED_LOCALES
        ) ?: FALLBACK_TRANSLATED_LOCALE

    private val prefs = Preferences.userRoot().node("com/loadingbyte/cinecred")
    private val prefsProjectDirs = prefs.node("projectdirs")

    fun havePreferencesBeenSet() = UI_LOCALE_KEY in prefs.keys() && CHECK_FOR_UPDATES_KEY in prefs.keys()

    var uiLocaleWish: LocaleWish
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

    var checkForUpdates: Boolean
        get() = prefs.getBoolean(CHECK_FOR_UPDATES_KEY, true)
        set(value) {
            prefs.putBoolean(CHECK_FOR_UPDATES_KEY, value)
        }

    fun isHintTrackPending(trackName: String): Boolean = prefs.getBoolean("${trackName}_hint_track_pending", true)
    fun setHintTrackPending(trackName: String, pending: Boolean) {
        prefs.putBoolean("${trackName}_hint_track_pending", pending)
    }

    var memorizedProjectDirs: List<Path>
        get() {
            val dirs = prefsProjectDirs.keys()
                .sortedBy(String::toInt)
                .map { key -> Path(prefsProjectDirs.get(key, null)) }
                .toMutableList()
            // Remove all memorized directories which are no longer project directories.
            // Do not base this decision on the existence of a credits file because this file might just be
            // moved around a little by the user at the same time this function is called.
            val modified = dirs.removeAll { dir ->
                !dir.isDirectory() || !dir.resolve(ProjectController.STYLING_FILE_NAME).isRegularFile()
            }
            if (modified)
                memorizedProjectDirs = dirs
            return dirs
        }
        set(dirs) {
            prefsProjectDirs.clear()
            for ((idx, dir) in dirs.withIndex())
                prefsProjectDirs.put(idx.toString(), dir.toString())
        }


    sealed class LocaleWish {

        abstract fun resolve(): Locale

        object System : LocaleWish() {
            override fun resolve() = SYSTEM_LOCALE
        }

        data class Specific(val locale: Locale) : LocaleWish() {
            override fun resolve() = locale
        }

    }

}
