package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.common.SYSTEM_LOCALE
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import java.awt.GraphicsConfiguration
import java.nio.file.Path
import java.util.*
import kotlin.reflect.KMutableProperty1


interface WelcomeCtrlComms {

    fun close()

    // ========== FOR MASTER CTRL ==========

    fun commence(openProjectDir: Path? = null)
    fun setTab(tab: WelcomeTab)

    // ========== FOR WELCOME VIEW ==========

    fun onPassHintTrack()
    fun onChangeTab(tab: WelcomeTab)

    fun projects_start_onClickOpen()
    fun projects_start_onClickCreate()
    fun projects_start_onClickOpenMemorized(projectDir: Path)
    fun projects_start_onDrop(path: Path)

    fun projects_openBrowse_shouldShowAppIcon(dir: Path): Boolean
    fun projects_openBrowse_onChangeSelection(dir: Path?)
    fun projects_openBrowse_onDoubleClickDir(dir: Path): Boolean // Returns whether to cancel the double click.
    fun projects_openBrowse_onClickBack()
    fun projects_openBrowse_onClickDone()

    fun projects_createBrowse_onChangeSelection(dir: Path?)
    fun projects_createBrowse_onClickBack()
    fun projects_createBrowse_onClickNext()

    fun projects_createConfigure_onClickBack()
    fun projects_createConfigure_onClickDone(locale: Locale, format: SpreadsheetFormat)

    fun <V> onChangePreference(pref: KMutableProperty1<Preferences, V>, value: V)

}


interface WelcomeViewComms {

    fun display()
    fun close()
    fun getMostOccupiedScreen(): GraphicsConfiguration

    fun playHintTrack()
    fun setTab(tab: WelcomeTab)
    fun setTabsLocked(locked: Boolean)

    fun projects_setCard(card: ProjectsCard)

    fun projects_start_setMemorized(projectDirs: List<Path>)

    fun projects_openBrowse_setCurrentDir(dir: Path)
    fun projects_openBrowse_setDoneEnabled(enabled: Boolean)

    fun projects_createBrowse_setCurrentDir(dir: Path)
    fun projects_createBrowse_setSelection(dir: Path)
    fun projects_createBrowse_setNextEnabled(enabled: Boolean)

    fun projects_createConfigure_setProjectDir(projectDir: Path)

    fun setPreferences(preferences: Preferences)
    fun setPreferencesSubmitButton(listener: (() -> Unit)?)

    fun setChangelog(changelog: String)
    fun setLicenses(licenses: List<License>)
    fun setUpdate(version: String)

    fun showNotADirMessage(path: Path)
    fun showNotAProjectMessage(dir: Path)
    fun showAlreadyOpenMessage(projectDir: Path)
    fun showNotEmptyQuestion(projectDir: Path): Boolean
    fun showRestartUILocaleQuestion(newLocale: Locale): Boolean

}


enum class WelcomeTab { PROJECTS, PREFERENCES, CHANGELOG, LICENSES, UPDATE }
enum class ProjectsCard { START, OPEN_BROWSE, CREATE_BROWSE, CREATE_CONFIGURE }


interface Preferences {

    var uiLocaleWish: LocaleWish
    var checkForUpdates: Boolean
    var welcomeHintTrackPending: Boolean
    var projectHintTrackPending: Boolean

    fun setFrom(other: Preferences) {
        uiLocaleWish = other.uiLocaleWish
        checkForUpdates = other.checkForUpdates
        welcomeHintTrackPending = other.welcomeHintTrackPending
        projectHintTrackPending = other.projectHintTrackPending
    }

}


sealed interface LocaleWish {

    val locale: Locale

    object System : LocaleWish {
        override val locale = SYSTEM_LOCALE
    }

    data class Specific(override val locale: Locale) : LocaleWish

}


class License(val name: String, val body: String)
