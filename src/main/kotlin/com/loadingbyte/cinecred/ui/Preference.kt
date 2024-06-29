package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.RGB
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*


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
val DECK_LINK_ID_PREFERENCE: Preference<String> = StringPreference("deckLinkId", "null")
val DECK_LINK_MODE_PREFERENCE: Preference<String> = StringPreference("deckLinkMode", "null")
val DECK_LINK_DEPTH_PREFERENCE: Preference<Int> = IntPreference("deckLinkDepth", 8)
val DECK_LINK_CONNECTED_PREFERENCE: Preference<Boolean> = BooleanPreference("deckLinkConnected", false)
val PROJECT_DIRS_PREFERENCE: Preference<List<Path>> = PathListPreference("projectDirs")
val OVERLAYS_PREFERENCE: Preference<List<ConfigurableOverlay>> = OverlayListPreference("overlay")


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


private class IntPreference(override val key: String, private val def: Int) : AbstractPreference<Int>() {
    override fun doGet() = PreferencesToml.get(key) as? Int ?: def
    override fun doSet(value: Int) = PreferencesToml.set(key, value)
}


private class StringPreference(override val key: String, private val def: String) : AbstractPreference<String>() {
    override fun doGet() = PreferencesToml.get(key) as? String ?: def
    override fun doSet(value: String) = PreferencesToml.set(key, value)
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


private class OverlayListPreference(override val key: String) : AbstractPreference<List<ConfigurableOverlay>>() {

    override fun doGet() = (PreferencesToml.get(key) as? List<*> ?: emptyList<Any>()).mapNotNull {
        if (it !is Map<*, *>) return@mapNotNull null
        val type = it["type"] as? String ?: return@mapNotNull null
        when (type) {
            "aspectRatio" -> {
                val ratio = it["ratio"] as? String ?: return@mapNotNull null
                val ratioParts = ratio.split(":")
                if (ratioParts.size != 2) return@mapNotNull null
                val h = ratioParts[0].toDoubleOrNull() ?: return@mapNotNull null
                val v = ratioParts[1].toDoubleOrNull() ?: return@mapNotNull null
                if (!h.isFinite() || h < 1.0 || !v.isFinite() || v < 1.0)
                    return@mapNotNull null
                AspectRatioOverlay(UUID.randomUUID(), h, v)
            }
            "lines" -> {
                val name = it["name"] as? String ?: return@mapNotNull null
                val colorList = (it["color"] as? List<*>)?.filterIsInstance<Number>() ?: emptyList()
                val color = if (colorList.size == 4) Color4f(colorList, ColorSpace.XYZD50) else null
                val hLines = (it["hLines"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
                val vLines = (it["vLines"] as? List<*>)?.filterIsInstance<Int>() ?: emptyList()
                LinesOverlay(UUID.randomUUID(), name, color, hLines, vLines)
            }
            "image" -> {
                val name = it["name"] as? String ?: return@mapNotNull null
                val underlay = it["lay"] == "under"
                val uuid = try {
                    UUID.fromString(it["image"] as? String ?: return@mapNotNull null)
                } catch (_: IllegalArgumentException) {
                    return@mapNotNull null
                }
                val file = IMAGE_DIR.resolve("$uuid.png")
                val raster = try {
                    Picture.Raster.load(file)
                } catch (e: IOException) {
                    LOGGER.error("Cannot read overlay image file '{}'.", file, e)
                    return@mapNotNull null
                }
                ImageOverlay(uuid, name, raster, rasterPersisted = true, underlay)
            }
            else -> null
        }
    }

    override fun doSet(value: List<ConfigurableOverlay>) {
        // Delete old overlay image files which are no longer used.
        try {
            if (IMAGE_DIR.exists())
                for (file in IMAGE_DIR.listDirectoryEntries()) {
                    val fileUUIDStr = file.nameWithoutExtension
                    if (value.none { overlay -> overlay is ImageOverlay && overlay.uuid.toString() == fileUUIDStr })
                        file.deleteIfExists()
                }
        } catch (e: IOException) {
            LOGGER.error("Cannot delete old overlay image files.", e)
        }
        // Save overlay image files which have not yet been saved.
        for (overlay in value)
            if (overlay is ImageOverlay) {
                val file = IMAGE_DIR.resolve("${overlay.uuid}.png")
                if (!file.exists() || !overlay.rasterPersisted)
                    try {
                        file.parent.createDirectoriesSafely()
                        val bitmap = overlay.raster.bitmap
                        // For now, we save overlay images in the sRGB color space.
                        BitmapWriter.PNG(RGB, bitmap.spec.representation.alpha != Bitmap.Alpha.OPAQUE, ColorSpace.SRGB)
                            .convertAndWrite(bitmap, file)
                        overlay.rasterPersisted = true
                    } catch (e: IOException) {
                        LOGGER.error("Cannot write the overlay image file '{}'.", file, e)
                    }
            }
        // Update the overlay list in preferences.toml.
        PreferencesToml.set(key, value.map { overlay ->
            when (overlay) {
                is AspectRatioOverlay -> mapOf(
                    "type" to "aspectRatio",
                    "ratio" to "${overlay.h}:${overlay.v}"
                )
                is LinesOverlay -> {
                    val toml = mutableMapOf<String, Any>(
                        "type" to "lines",
                        "name" to overlay.name
                    )
                    if (overlay.color != null) toml["color"] = overlay.color.convert(ColorSpace.XYZD50).rgba().asList()
                    if (overlay.hLines.isNotEmpty()) toml["hLines"] = overlay.hLines
                    if (overlay.vLines.isNotEmpty()) toml["vLines"] = overlay.vLines
                    toml
                }
                is ImageOverlay -> mapOf(
                    "type" to "image",
                    "name" to overlay.name,
                    "lay" to if (overlay.underlay) "under" else "over",
                    "image" to overlay.uuid.toString()
                )
            }
        })
    }

    companion object {
        private val IMAGE_DIR = CONFIG_DIR.resolve("overlays")
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
                toml[SET_UP_PREFERENCE.key] = true
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

    data object System : LocaleWish {
        override val locale = SYSTEM_LOCALE
    }

    data class Specific(override val locale: Locale) : LocaleWish

}
