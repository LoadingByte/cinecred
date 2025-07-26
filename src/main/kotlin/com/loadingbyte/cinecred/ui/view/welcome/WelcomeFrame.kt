package com.loadingbyte.cinecred.ui.view.welcome

import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nQuoted
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.projectio.STYLING_FILE_NAME
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.*
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
        setup(welcomeFrame = true)
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
                .maxByOrNull { cfg -> cfg.bounds.intersection(bounds).run { if (isEmpty) 0 else width * height } }!!
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
    override fun preferences_start_setDeliveryDestTemplates(templates: List<DeliveryDestTemplate>) =
        panel.preferencesPanel.preferences_start_setDeliveryDestTemplates(templates)
    override fun preferences_configureAccount_resetForm() =
        panel.preferencesPanel.preferences_configureAccount_resetForm()
    override fun preferences_establishAccount_setAction(authorize: Boolean) =
        panel.preferencesPanel.preferences_establishAccount_setAction(authorize)
    override fun preferences_establishAccount_setError(error: String?) =
        panel.preferencesPanel.preferences_establishAccount_setError(error)
    override fun preferences_configureOverlay_setForm(
        type: Class<out ConfigurableOverlay>, name: String, aspectRatioH: Double, aspectRatioV: Double,
        linesColor: Color4f?, linesH: List<Int>, linesV: List<Int>, imageFile: Path, imageUnderlay: Boolean
    ) = panel.preferencesPanel.preferences_configureOverlay_setForm(
        type, name, aspectRatioH, aspectRatioV, linesColor, linesH, linesV, imageFile, imageUnderlay
    )
    override fun preferences_configureOverlay_clearImageFile() =
        panel.preferencesPanel.preferences_configureOverlay_clearImageFile()
    override fun preferences_configureOverlay_setImageFileExtAssortment(fileExtAssortment: FileExtAssortment?) =
        panel.preferencesPanel.preferences_configureOverlay_setImageFileExtAssortment(fileExtAssortment)
    override fun preferences_configureDeliveryDestTemplate_setForm(name: String, templateStr: String) =
        panel.preferencesPanel.preferences_configureDeliveryDestTemplate_setForm(name, templateStr)
    // @formatter:on

    override fun setChangelog(changelog: String) = panel.setChangelog(changelog)
    override fun setAbout(about: String) = panel.setAbout(about)
    override fun setLicenses(licenses: List<License>) = panel.setLicenses(licenses)
    override fun setUpdate(version: String) = panel.setUpdate(version)

    override fun projects_start_showOpenDialog(dir: Path?) {
        welcomeCtrl.projects_start_onCompleteOpenDialog(showFolderDialog(this, dir))
    }

    override fun projects_start_showCreateDialog(dir: Path?) {
        welcomeCtrl.projects_start_onCompleteCreateDialog(showFolderDialog(this, dir))
    }

    override fun showNotADirMessage(path: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.notADir.msg", l10nQuoted(path)),
            l10n("ui.projects.notADir.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showIllegalPathMessage(path: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.illegalPath.msg", l10nQuoted(path)),
            l10n("ui.projects.illegalPath.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNotAProjectMessage(dir: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.notAProject.msg", l10nQuoted(dir), l10nQuoted(STYLING_FILE_NAME)),
            l10n("ui.projects.notAProject.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showAlreadyOpenMessage(projectDir: Path) {
        JOptionPane.showMessageDialog(
            this, l10n("ui.projects.alreadyOpen.msg", l10nQuoted(projectDir)),
            l10n("ui.projects.alreadyOpen.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNewerVersionQuestion(projectDir: Path, projectVersion: String): Boolean {
        return JOptionPane.showConfirmDialog(
            this, l10n("ui.projects.newerVersion.msg", l10nQuoted(projectDir), projectVersion, VERSION),
            l10n("ui.projects.newerVersion.title"), JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
    }

    override fun showNotEmptyQuestion(projectDir: Path): Boolean {
        return JOptionPane.showConfirmDialog(
            this, l10n("ui.projects.create.notEmpty.msg", l10nQuoted(projectDir)),
            l10n("ui.projects.create.notEmpty.title"), JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
    }

    override fun showRestartUILocaleQuestion(newLocale: Locale): Boolean {
        return JOptionPane.showConfirmDialog(
            this, l10n("ui.preferences.restartUILocale.msg", newLocale),
            l10n("ui.preferences.restartUILocale.title"), JOptionPane.YES_NO_OPTION
        ) == JOptionPane.OK_OPTION
    }

    override fun showCannotRemoveAccountMessage(account: Account, error: String) {
        JOptionPane.showMessageDialog(
            this, arrayOf(l10n("ui.preferences.accounts.cannotRemove.msg", l10nQuoted(account.id)), error),
            l10n("ui.preferences.accounts.cannotRemove.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showCannotReadOverlayImageMessage(file: Path, error: String) {
        JOptionPane.showMessageDialog(
            this, arrayOf(l10n("ui.preferences.overlays.configure.cannotReadImageFile.msg", l10nQuoted(file)), error),
            l10n("ui.preferences.overlays.configure.cannotReadImageFile.title"), JOptionPane.ERROR_MESSAGE
        )
    }


    companion object {
        private var rememberedBounds: Rectangle? = null
    }

}
