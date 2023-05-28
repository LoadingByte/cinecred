package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.service.Service
import com.loadingbyte.cinecred.projectio.service.ServiceProvider
import com.loadingbyte.cinecred.ui.LocaleWish
import com.loadingbyte.cinecred.ui.Preference
import java.awt.GraphicsConfiguration
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.*


interface WelcomeCtrlComms {

    fun close()

    // ========== FOR MASTER CTRL ==========

    fun onGlobalKeyEvent(event: KeyEvent): Boolean
    fun commence(openProjectDir: Path? = null)
    fun setTab(tab: WelcomeTab)

    // ========== FOR WELCOME VIEW ==========

    fun onPassHintTrack()

    fun projects_start_onClickOpen()
    fun projects_start_onClickCreate()
    fun projects_start_onClickOpenMemorized(projectDir: Path)
    fun projects_start_onDrop(path: Path)

    fun projects_openBrowse_shouldShowAppIcon(dir: Path): Boolean
    fun projects_openBrowse_onChangeSelection(dir: Path?)
    fun projects_openBrowse_onDoubleClickDir(dir: Path): Boolean // Returns whether to cancel the double click.
    fun projects_openBrowse_onClickCancel()
    fun projects_openBrowse_onClickDone()

    fun projects_createBrowse_onChangeSelection(dir: Path?)
    fun projects_createBrowse_onClickCancel()
    fun projects_createBrowse_onClickNext()

    fun projects_createConfigure_onClickBack()
    fun projects_createConfigure_onClickDone(
        locale: Locale,
        scale: Int,
        sample: Boolean,
        creditsLocation: CreditsLocation,
        creditsFormat: SpreadsheetFormat,
        creditsService: Service?,
        creditsFilename: String
    )

    fun projects_createWait_onClickCancel()

    fun <P : Any> preferences_start_onChangeTopPreference(preference: Preference<P>, value: P)
    fun preferences_start_onClickAddService()
    fun preferences_start_onClickRemoveService(service: Service)

    fun preferences_verifyLabel(label: String): String? // Returns an error.
    fun preferences_configureService_onClickCancel()
    fun preferences_configureService_onClickAuthorize(label: String, provider: ServiceProvider)

    fun preferences_authorizeService_onClickCancel()

}


interface WelcomeViewComms {

    fun display()
    fun close()
    fun isFromWelcomeWindow(event: KeyEvent): Boolean
    fun getMostOccupiedScreen(): GraphicsConfiguration

    fun playHintTrack()
    fun getTab(): WelcomeTab
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
    fun projects_createConfigure_setServices(services: List<Service>)
    fun projects_createConfigure_setCreditsFilename(filename: String)

    fun projects_createWait_setError(error: String?)

    fun preferences_setCard(card: PreferencesCard)

    fun preferences_start_setInitialSetup(initialSetup: Boolean, doneListener: (() -> Unit)?)
    fun preferences_start_setUILocaleWish(wish: LocaleWish)
    fun preferences_start_setCheckForUpdates(check: Boolean)
    fun preferences_start_setWelcomeHintTrackPending(pending: Boolean)
    fun preferences_start_setProjectHintTrackPending(pending: Boolean)
    fun preferences_start_setServices(services: List<Service>)
    fun preferences_start_setServiceRemovalLocked(service: Service, locked: Boolean)

    fun preferences_authorizeService_setError(error: String?)

    fun setChangelog(changelog: String)
    fun setLicenses(licenses: List<License>)
    fun setUpdate(version: String)

    fun showNotADirMessage(path: Path)
    fun showIllegalPathMessage(path: Path)
    fun showNotAProjectMessage(dir: Path)
    fun showAlreadyOpenMessage(projectDir: Path)
    fun showNotEmptyQuestion(projectDir: Path): Boolean
    fun showRestartUILocaleQuestion(newLocale: Locale): Boolean
    fun showCannotRemoveServiceMessage(service: Service, error: String)

}


enum class WelcomeTab { PROJECTS, PREFERENCES, CHANGELOG, LICENSES, UPDATE }
enum class ProjectsCard { START, OPEN_BROWSE, CREATE_BROWSE, CREATE_CONFIGURE, CREATE_WAIT }
enum class CreditsLocation { LOCAL, SERVICE, SKIP }
enum class PreferencesCard { START, CONFIGURE_SERVICE, AUTHORIZE_SERVICE }


class License(val name: String, val body: String)
