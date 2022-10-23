package com.loadingbyte.cinecred.ui.view.welcome

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.STYLING_FILE_NAME
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.WINDOW_ICON_IMAGES
import com.loadingbyte.cinecred.ui.makeWelcomeHintTrack
import com.loadingbyte.cinecred.ui.play
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.*
import javax.swing.JFrame
import javax.swing.JOptionPane


class WelcomeFrame(private val welcomeCtrl: WelcomeCtrlComms) : JFrame(l10n("ui.welcome.title")), WelcomeViewComms {

    val panel = WelcomePanel(welcomeCtrl)

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                welcomeCtrl.close()
            }
        })

        minimumSize = Dimension(700, 500)
        bounds = rememberedBounds ?: run {
            val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val welcomeFrameWidth = (maxWinBounds.width * 8 / 20).coerceAtLeast(minimumSize.width)
            val welcomeFrameHeight = (maxWinBounds.height * 11 / 20).coerceAtLeast(minimumSize.height)
            Rectangle(
                (maxWinBounds.width - welcomeFrameWidth) / 2,
                (maxWinBounds.height - welcomeFrameHeight) / 2,
                welcomeFrameWidth, welcomeFrameHeight
            )
        }

        iconImages = WINDOW_ICON_IMAGES

        contentPane.add(panel)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    // @formatter:off
    override fun display() { isVisible = true }
    override fun close() { rememberedBounds = bounds; dispose() }
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

    override fun setPreferences(preferences: Preferences) =
        panel.preferencesPanel.form.setPreferences(preferences)
    override fun setPreferencesSubmitButton(listener: (() -> Unit)?) =
        panel.preferencesPanel.form.setPreferencesSubmitButton(listener)
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


    companion object {
        private var rememberedBounds: Rectangle? = null
    }

}
