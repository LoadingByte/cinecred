package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.*
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.pathString


interface Preference<P : Any> {
    val key: String
    fun get(): P
    fun set(value: P)
    fun addListener(listener: (P) -> Unit)
    fun removeListener(listener: (P) -> Unit)
}


val SET_UP_PREFERENCE: Preference<Boolean> = BooleanPreference("setUp", false)
val UI_LOCALE_PREFERENCE: Preference<LocaleWish> = LocaleWishPreference("uiLocale")
val CHECK_FOR_UPDATES_PREFERENCE: Preference<Boolean> = BooleanPreference("checkForUpdates", true)
val WELCOME_HINT_TRACK_PENDING_PREFERENCE: Preference<Boolean> = BooleanPreference("welcomeHintTrackPending", true)
val PROJECT_HINT_TRACK_PENDING_PREFERENCE: Preference<Boolean> = BooleanPreference("projectHintTrackPending", true)
val PROJECT_DIRS_PREFERENCE: Preference<List<Path>> = PathListPreference("projectDirs")


private abstract class AbstractPreference<P : Any> : Preference<P> {

    private var cache: P? = null
    protected val listeners = mutableListOf<(P) -> Unit>()

    override fun addListener(listener: (P) -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: (P) -> Unit) {
        listeners.remove(listener)
    }

    override fun get(): P =
        cache ?: doGet().also { cache = it }

    override fun set(value: P) {
        if (value != get()) {
            cache = value
            doSet(value)
            for (listener in listeners)
                listener(value)
        }
    }

    protected abstract fun doGet(): P
    protected abstract fun doSet(value: P)

}


private class BooleanPreference(override val key: String, private val def: Boolean) : AbstractPreference<Boolean>() {
    override fun doGet() = PreferencesToml.get(key) as? Boolean ?: def
    override fun doSet(value: Boolean) = PreferencesToml.set(key, value)
}


private class PathListPreference(override val key: String) : AbstractPreference<List<Path>>() {

    override fun doGet() = (PreferencesToml.get(key) as? List<*> ?: emptyList<Any>())
        .mapNotNull { (it as? String)?.toPathSafely() }

    override fun doSet(value: List<Path>) = PreferencesToml.set(key, value.map(Path::pathString))

}


private class LocaleWishPreference(override val key: String) : AbstractPreference<LocaleWish>() {

    override fun doGet() = when (val str = PreferencesToml.get(key) as? String) {
        null, "system" -> LocaleWish.System
        else -> {
            val locale = Locale.forLanguageTag(str)
            if (locale in TRANSLATED_LOCALES) LocaleWish.Specific(locale) else LocaleWish.System
        }
    }

    override fun doSet(value: LocaleWish) {
        val str = when (value) {
            is LocaleWish.System -> "system"
            is LocaleWish.Specific -> value.locale.toLanguageTag()
        }
        PreferencesToml.set(key, str)
    }

}


private object PreferencesToml {

    private val file = CONFIG_DIR.resolve("preferences.toml")

    private val toml: MutableMap<String, Any> = run {
        try {
            if (exists())
                return@run readToml(file)
        } catch (e: IOException) {
            LOGGER.error("Cannot read the preferences file '{}'.", file, e)
        }
        emptyMap()
    }.toSortedMap()

    private fun save() {
        try {
            file.parent.createDirectoriesSafely()
            writeToml(file, toml)
        } catch (e: IOException) {
            LOGGER.error("Cannot write the preferences file '{}'.", file, e)
        }
    }

    init {
        // 1.4.1 -> 1.5.0: Preferences are now stored in a TOML file instead of Java's Preferences mechanism.
        if (!exists()) {
            val legacyRoot = java.util.prefs.Preferences.userRoot()
            if (legacyRoot.nodeExists("com/loadingbyte/cinecred")) {
                val legacyPrefs = legacyRoot.node("com/loadingbyte/cinecred")
                legacyPrefs.get("ui_locale", null)
                    ?.let { toml[UI_LOCALE_PREFERENCE.key] = it }
                legacyPrefs.get("check_for_updates", null)
                    ?.let { toml[CHECK_FOR_UPDATES_PREFERENCE.key] = it.toBoolean() }
                legacyPrefs.get("open_hint_track_pending", null)
                    ?.let { toml[WELCOME_HINT_TRACK_PENDING_PREFERENCE.key] = it.toBoolean() }
                legacyPrefs.get("project_hint_track_pending", null)
                    ?.let { toml[PROJECT_HINT_TRACK_PENDING_PREFERENCE.key] = it.toBoolean() }
                if (legacyPrefs.nodeExists("projectdirs")) {
                    val legacyProjectDirs = legacyPrefs.node("projectdirs")
                    toml[PROJECT_DIRS_PREFERENCE.key] = legacyProjectDirs.keys()
                        .sortedBy(String::toInt)
                        .mapNotNull { key -> legacyProjectDirs.get(key, null) }
                }
                legacyPrefs.removeNode()
                save()
            }
        }
    }

    fun exists(): Boolean = file.exists()
    fun get(key: String): Any? = toml[key]

    fun set(key: String, value: Any) {
        if (value != toml[key]) {
            toml[key] = value
            save()
        }
    }

}


sealed interface LocaleWish {

    val locale: Locale

    object System : LocaleWish {
        override val locale = SYSTEM_LOCALE
    }

    data class Specific(override val locale: Locale) : LocaleWish

}
