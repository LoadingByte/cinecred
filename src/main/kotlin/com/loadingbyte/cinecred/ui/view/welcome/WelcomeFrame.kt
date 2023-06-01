package com.loadingbyte.cinecred.ui.view.welcome

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.STYLING_FILE_NAME
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.ui.ConfigurableOverlay
import com.loadingbyte.cinecred.ui.LocaleWish
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.FileExtAssortment
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import com.loadingbyte.cinecred.ui.helper.center
import com.loadingbyte.cinecred.ui.helper.setup
import com.loadingbyte.cinecred.ui.makeWelcomeHintTrack
import com.loadingbyte.cinecred.ui.play
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.*
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities


class WelcomeFrame(private val welcomeCtrl: WelcomeCtrlComms) : JFrame(l10n("ui.welcome.title")), WelcomeViewComms {

    val panel = WelcomePanel(welcomeCtrl)

    init {
        setup()
        iconImages = WINDOW_ICON_IMAGES

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                // Due to the card layout inside the panel, the focus is initially "lost" somewhere, and hence tabbing
                // has no effect. By calling this method, we make the focus available again.
                requestFocusInWindow()
            }

            override fun windowClosing(e: WindowEvent) {
                welcomeCtrl.close()
            }
        })

        rememberedBounds?.also(::setBounds) ?: center(
            onScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration,
            0.4, 0.55
        )

        contentPane.add(panel)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    // @formatter:off
    override fun display() { isVisible = true }
    override fun close() { rememberedBounds = bounds; dispose() }
    override fun isFromWelcomeWindow(event: KeyEvent): Boolean = SwingUtilities.getRoot(event.component) == this
    // @formatter:on

    override fun getMostOccupiedScreen() =
        if (isVisible)
            graphicsConfiguration!!
        else {
            // This branch is used when opening a project without actually showing the welcome frame, i.e., when
            // dragging a project folder onto the program.
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                .map { dev -> dev.defaultConfiguration }
                .maxByOrNull { cfg -> cfg.bounds.intersection(bounds).run { width * height } }!!
        }

    override fun playHintTrack() {
        makeWelcomeHintTrack(this).play(onPass = welcomeCtrl::onPassHintTrack)
    }

    override fun getTab() = panel.getTab()
    override fun setTab(tab: WelcomeTab) = panel.setTab(tab)
    override fun setTabsLocked(locked: Boolean) = panel.setTabsLocked(locked)

    // @formatter:off
    override fun projects_setCard(card: ProjectsCard) =
        panel.projectsPanel.projects_setCard(card)
    override fun projects_start_setMemorized(projectDirs: List<Path>) =
        panel.projectsPanel.projects_start_setMemorized(projectDirs)
    override fun projects_openBrowse_setCurrentDir(dir: Path) =
        panel.projectsPanel.projects_openBrowse_setCurrentDir(dir)
    override fun projects_openBrowse_setDoneEnabled(enabled: Boolean) =
        panel.projectsPanel.projects_openBrowse_setDoneEnabled(enabled)
    override fun projects_createBrowse_setCurrentDir(dir: Path) =
        panel.projectsPanel.projects_createBrowse_setCurrentDir(dir)
    override fun projects_createBrowse_setSelection(dir: Path) =
        panel.projectsPanel.projects_createBrowse_setSelection(dir)
    override fun projects_createBrowse_setNextEnabled(enabled: Boolean) =
        panel.projectsPanel.projects_createBrowse_setNextEnabled(enabled)
    override fun projects_createConfigure_setProjectDir(projectDir: Path) =
        panel.projectsPanel.projects_createConfigure_setProjectDir(projectDir)
    override fun projects_createConfigure_setAccounts(accounts: List<Account>) =
        panel.projectsPanel.projects_createConfigure_setAccounts(accounts)
    override fun projects_createConfigure_setCreditsFilename(filename: String) =
        panel.projectsPanel.projects_createConfigure_setCreditsFilename(filename)
    override fun projects_createWait_setError(error: String?) =
        panel.projectsPanel.projects_createWait_setError(error)

    override fun preferences_setCard(card: PreferencesCard) =
        panel.preferencesPanel.preferences_setCard(card)
    override fun preferences_start_setInitialSetup(initialSetup: Boolean, doneListener: (() -> Unit)?) =
        panel.preferencesPanel.preferences_start_setInitialSetup(initialSetup, doneListener)
    override fun preferences_start_setUILocaleWish(wish: LocaleWish) =
        panel.preferencesPanel.startPreferencesForm.preferences_start_setUILocaleWish(wish)
    override fun preferences_start_setCheckForUpdates(check: Boolean) =
        panel.preferencesPanel.startPreferencesForm.preferences_start_setCheckForUpdates(check)
    override fun preferences_start_setWelcomeHintTrackPending(pending: Boolean) =
        panel.preferencesPanel.startPreferencesForm.preferences_start_setWelcomeHintTrackPending(pending)
    override fun preferences_start_setProjectHintTrackPending(pending: Boolean) =
        panel.preferencesPanel.startPreferencesForm.preferences_start_setProjectHintTrackPending(pending)
    override fun preferences_start_setAccounts(accounts: List<Account>) =
        panel.preferencesPanel.preferences_start_setAccounts(accounts)
    override fun preferences_start_setAccountRemovalLocked(account: Account, locked: Boolean) =
        panel.preferencesPanel.preferences_start_setAccountRemovalLocked(account, locked)
    override fun preferences_start_setOverlays(overlays: List<ConfigurableOverlay>) =
        panel.preferencesPanel.preferences_start_setOverlays(overlays)
    override fun preferences_configureAccount_resetForm() =
        panel.preferencesPanel.preferences_configureAccount_resetForm()
    override fun preferences_authorizeAccount_setError(error: String?) =
        panel.preferencesPanel.preferences_authorizeAccount_setError(error)
    override fun preferences_configureOverlay_setForm(
        type: Class<out ConfigurableOverlay>, name: String, aspectRatioH: Double, aspectRatioV: Double, linesColor: Color?,
        linesH: List<Int>, linesV: List<Int>, imageFile: Path
    ) = panel.preferencesPanel.preferences_configureOverlay_setForm(
        type, name, aspectRatioH, aspectRatioV, linesColor, linesH, linesV, imageFile
    )
    override fun preferences_configureOverlay_setImageFileExtAssortment(fileExtAssortment: FileExtAssortment?) =
        panel.preferencesPanel.preferences_configureOverlay_setImageFileExtAssortment(fileExtAssortment)
    // @formatter:on

    override fun setChangelog(changelog: String) = panel.setChangelog(changelog)
    override fun setLicenses(licenses: List<License>) = panel.setLicenses(licenses)
    override fun setUpdate(version: String) = panel.setUpdate(version)

    override fun showNotADirMessage(path: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.notADir.msg", path),
            l10n("ui.projects.notADir.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showIllegalPathMessage(path: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.illegalPath.msg", path),
            l10n("ui.projects.illegalPath.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNotAProjectMessage(dir: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.notAProject.msg", dir, STYLING_FILE_NAME),
            l10n("ui.projects.notAProject.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showAlreadyOpenMessage(projectDir: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.alreadyOpen.msg", projectDir),
            l10n("ui.projects.alreadyOpen.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNotEmptyQuestion(projectDir: Path): Boolean {
        return JOptionPane.showConfirmDialog(
            this, l10n("ui.projects.create.notEmpty.msg", projectDir),
            l10n("ui.projects.create.notEmpty.title"), JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
    }

    override fun showRestartUILocaleQuestion(newLocale: Locale): Boolean {
        return JOptionPane.showConfirmDialog(
            this, l10n("ui.preferences.restartUILocale.msg", newLocale),
            l10n("ui.preferences.restartUILocale.title"), JOptionPane.OK_CANCEL_OPTION
        ) == JOptionPane.OK_OPTION
    }

    override fun showCannotRemoveAccountMessage(account: Account, error: String) {
        JOptionPane.showMessageDialog(
            this, arrayOf(l10n("ui.preferences.accounts.cannotRemove.msg", account.id), error),
            l10n("ui.preferences.accounts.cannotRemove.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showCannotReadOverlayImageMessage(file: Path, error: String) {
        JOptionPane.showMessageDialog(
            this, arrayOf(l10n("ui.preferences.overlays.configure.cannotReadImageFile.msg", file), error),
            l10n("ui.preferences.overlays.configure.cannotReadImageFile.title"), JOptionPane.ERROR_MESSAGE
        )
    }


    companion object {
        private var rememberedBounds: Rectangle? = null
    }

}
