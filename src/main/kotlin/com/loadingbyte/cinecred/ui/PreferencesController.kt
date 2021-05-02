package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.FALLBACK_TRANSLATED_LOCALE
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.prefs.Preferences


object PreferencesController {

    // This is the translated locale which is closest to the system's default locale.
    private val systemLocale: Locale =
        Locale.lookup(
            listOf(Locale.LanguageRange(Locale.getDefault().toLanguageTag())), TRANSLATED_LOCALES
        ) ?: FALLBACK_TRANSLATED_LOCALE

    private val prefs = Preferences.userRoot().node("com/loadingbyte/cinecred")
    private val prefsProjectDirs = prefs.node("projectDirs")

    /**
     * Returns whether the startup process should be aborted (false) or continued (true).
     */
    fun onStartup(): Boolean {
        val havePreferencesBeenSet = "uiLocale" in prefs.keys() && "checkForUpdates" in prefs.keys()
        if (!havePreferencesBeenSet)
            prefFormValues = (PreferencesForm.showDialog(prefFormValues, null, false) ?: return false).first
        Locale.setDefault(uiLocale ?: systemLocale)
        return true
    }

    fun showPreferencesDialog(parent: Window) {
        val (new, exit) = PreferencesForm.showDialog(prefFormValues, parent, true) ?: return
        prefFormValues = new
        Locale.setDefault(uiLocale ?: systemLocale)

        if (exit)
            OpenController.tryExit()
    }

    /**
     * Shortcut to quickly create or apply this value object.
     */
    private var prefFormValues: PreferencesForm.Values
        get() = PreferencesForm.Values(uiLocale, checkForUpdates)
        set(values) {
            uiLocale = values.uiLocale
            checkForUpdates = values.checkForUpdates
        }

    /**
     * Null locale means that the supported locale closest to the system locale should be used.
     */
    private var uiLocale: Locale?
        get() {
            val str = prefs.get("uiLocale", null)
            return if (str == null || str == "system") null else Locale.forLanguageTag(str)
        }
        set(value) {
            prefs.put("uiLocale", value?.toLanguageTag() ?: "system")
        }

    private var checkForUpdates: Boolean
        get() = prefs.getBoolean("checkForUpdates", true)
        set(value) {
            prefs.putBoolean("checkForUpdates", value)
        }

    var memorizedProjectDirs: List<Path>
        get() {
            val dirs = prefsProjectDirs.keys()
                .sortedBy(String::toInt)
                .map { key -> Path.of(prefsProjectDirs.get(key, null)) }
                .toMutableList()
            // Remove all memorized directories which are no longer project directories.
            // Do not base this decision on the existence of a credits file because this file might just be
            // moved around a little by the user at the same time this function is called.
            val modified = dirs.removeAll { dir ->
                !Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(ProjectController.STYLING_FILE_NAME))
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

}
