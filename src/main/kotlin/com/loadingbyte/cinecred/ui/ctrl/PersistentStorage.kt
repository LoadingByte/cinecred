package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.ui.comms.LocaleWish
import com.loadingbyte.cinecred.ui.comms.Preferences
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.pathString


object PersistentStorage : Preferences {

    private const val UI_LOCALE_KEY = "uiLocale"
    private const val CHECK_FOR_UPDATES_KEY = "checkForUpdates"
    private const val WELCOME_HINT_TRACK_PENDING_KEY = "welcomeHintTrackPending"
    private const val PROJECT_HINT_TRACK_PENDING_KEY = "projectHintTrackPending"
    private const val PROJECT_DIRS_KEY = "projectDirs"

    private val prefsFile = CONFIG_DIR.resolve("preferences.toml")
    private val prefs = run {
        try {
            if (prefsFile.exists())
                return@run readToml(prefsFile)
        } catch (e: IOException) {
            LOGGER.error("Cannot read the preferences file '{}'.", prefsFile, e)
        }
        HashMap()
    }

    init {
        // 1.4.1 -> 1.5.0: Preferences are now stored in a TOML file instead of Java's Preferences mechanism.
        if (!havePreferencesBeenSet()) {
            val legacyRoot = java.util.prefs.Preferences.userRoot()
            if (legacyRoot.nodeExists("com/loadingbyte/cinecred")) {
                val legacyPrefs = legacyRoot.node("com/loadingbyte/cinecred")
                legacyPrefs.get("ui_locale", null)
                    ?.let { prefs[UI_LOCALE_KEY] = it }
                legacyPrefs.get("check_for_updates", null)
                    ?.let { prefs[CHECK_FOR_UPDATES_KEY] = it.toBoolean() }
                legacyPrefs.get("open_hint_track_pending", null)
                    ?.let { prefs[WELCOME_HINT_TRACK_PENDING_KEY] = it.toBoolean() }
                legacyPrefs.get("project_hint_track_pending", null)
                    ?.let { prefs[PROJECT_HINT_TRACK_PENDING_KEY] = it.toBoolean() }
                if (legacyPrefs.nodeExists("projectdirs")) {
                    val legacyProjectDirs = legacyPrefs.node("projectdirs")
                    prefs[PROJECT_DIRS_KEY] = legacyProjectDirs.keys()
                        .sortedBy(String::toInt)
                        .mapNotNull { key -> legacyProjectDirs.get(key, null) }
                }
                legacyPrefs.removeNode()
                save()
            }
        }
    }

    private fun save() {
        try {
            prefsFile.parent.createDirectoriesSafely()
            writeToml(prefsFile, prefs)
        } catch (e: IOException) {
            LOGGER.error("Cannot write the preferences file '{}'.", prefsFile, e)
        }
    }

    fun havePreferencesBeenSet() = prefsFile.exists()

    override var uiLocaleWish: LocaleWish
        get() = when (val str = prefs[UI_LOCALE_KEY] as? String) {
            null, "system" -> LocaleWish.System
            else -> LocaleWish.Specific(Locale.forLanguageTag(str))
        }
        set(wish) {
            val value = when (wish) {
                is LocaleWish.System -> "system"
                is LocaleWish.Specific -> wish.locale.toLanguageTag()
            }
            prefs[UI_LOCALE_KEY] = value
            save()
        }

    override var checkForUpdates: Boolean
        get() = prefs[CHECK_FOR_UPDATES_KEY] as? Boolean ?: true
        set(check) {
            prefs[CHECK_FOR_UPDATES_KEY] = check
            save()
        }

    override var welcomeHintTrackPending: Boolean
        get() = prefs[WELCOME_HINT_TRACK_PENDING_KEY] as? Boolean ?: true
        set(pending) {
            prefs[WELCOME_HINT_TRACK_PENDING_KEY] = pending
            save()
        }

    override var projectHintTrackPending: Boolean
        get() = prefs[PROJECT_HINT_TRACK_PENDING_KEY] as? Boolean ?: true
        set(pending) {
            prefs["project_hint_track_pending"] = pending
            save()
        }

    var memorizedProjectDirs: List<Path>
        get() = (prefs[PROJECT_DIRS_KEY] as? List<*> ?: emptyList<Any>()).mapNotNull { (it as? String)?.toPathSafely() }
        set(dirs) {
            prefs[PROJECT_DIRS_KEY] = dirs.map(Path::pathString)
            save()
        }

}
