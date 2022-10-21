package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.json.Json
import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.projectio.copyTemplate
import com.loadingbyte.cinecred.projectio.locateCreditsFile
import com.loadingbyte.cinecred.ui.PreferencesStorage.LocaleWish
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists


class WelcomeController(
    private val onClose: () -> Unit,
    private val onOpenProject: (projectDir: Path, openOnScreen: GraphicsConfiguration) -> Unit
) {

    // Remove all memorized directories which are no longer project directories.
    init {
        val memProjectDirs = PreferencesStorage.memorizedProjectDirs.toMutableList()
        val modified = memProjectDirs.removeAll { dir -> !ProjectController.isProjectDir(dir) }
        if (modified)
            PreferencesStorage.memorizedProjectDirs = memProjectDirs
    }

    val welcomeFrame = WelcomeFrame(this)

    /**
     * When the user clicks on a project opening button multiple times while he is waiting for the project window to
     * open, we do not want this to really trigger multiple project openings, because that would confront him with
     * error messages like "this project is already opened". Hence, after the user opened a project, we just block
     * opening any more projects using the same WelcomeFrame+Controller.
     */
    private var blockOpening = false

    private var addedUpdateTab = false

    init {
        welcomeFrame.bounds = rememberedBounds ?: run {
            val maxWinBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val welcomeFrameWidth = (maxWinBounds.width * 8 / 20).coerceAtLeast(welcomeFrame.minimumSize.width)
            val welcomeFrameHeight = (maxWinBounds.height * 11 / 20).coerceAtLeast(welcomeFrame.minimumSize.height)
            Rectangle(
                (maxWinBounds.width - welcomeFrameWidth) / 2,
                (maxWinBounds.height - welcomeFrameHeight) / 2,
                welcomeFrameWidth, welcomeFrameHeight
            )
        }
        welcomeFrame.isVisible = true

        fun finishInit(initConfigChangedUILocaleWish: Boolean) {
            // If the program was started for the first time and the user changed the locale during the initial
            // configuration, reopen the welcome window so that the change applies.
            if (initConfigChangedUILocaleWish) {
                MasterController.tryCloseProjectsAndDisposeAllFrames()
                MasterController.showWelcomeFrame()
                return
            }
            // Otherwise, finish the regular initialization of the welcome window.
            doCheckForUpdates()
            makeOpenHintTrack(this).playIfPending()
        }

        if (PreferencesStorage.havePreferencesBeenSet())
            finishInit(false)
        else {
            // If we are here, the program is started for the first time as no preferences have been saved previously.
            // Lock all other tabs and let the user initially configure all preferences. This lets him specify whether
            // he wants update checks and hints before the program actually contacts the server or starts a hint track.
            // Also, it lets him directly set his preferred locale in case that differs from the system locale.
            val form = welcomeFrame.panel.preferencesPanel.preferencesForm
            welcomeFrame.panel.selectedTab = welcomeFrame.panel.preferencesPanel
            welcomeFrame.panel.setTabsLocked(true)
            val defaultUILocaleWish = PreferencesStorage.uiLocaleWish
            form.liveSavingViaCtrl = false
            form.addSubmitButton(l10n("ui.preferences.finishInitialSetup"), actionListener = {
                form.liveSavingViaCtrl = true
                form.removeSubmitButton()
                form.saveAllPreferences()
                MasterController.applyUILocaleWish()
                welcomeFrame.panel.setTabsLocked(false)
                finishInit(initConfigChangedUILocaleWish = defaultUILocaleWish != PreferencesStorage.uiLocaleWish)
            })
        }
    }

    /**
     * If configured accordingly, checks for updates (in the background if there's no cached information yet) and shows
     * an update tab if an update is available.
     */
    private fun doCheckForUpdates() {
        fun afterFetch() {
            if (!addedUpdateTab && latestStableVersion.get().let { it != null && it != VERSION }) {
                addedUpdateTab = true
                welcomeFrame.panel.addUpdateTab(latestStableVersion.get())
            }
        }

        if (latestStableVersion.get() != null)
            afterFetch()
        else if (PreferencesStorage.checkForUpdates) {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("https://loadingbyte.com/cinecred/dl/api/v1/components")).build(),
                HttpResponse.BodyHandlers.ofString()
            ).thenAccept { resp ->
                if (resp.statusCode() != 200)
                    return@thenAccept

                // Note: If the following processing fails for some reason, the thrown exception is just swallowed by
                // the CompletableFuture context. Since we do not need to react to a failed update check, this is fine.
                @Suppress("UNCHECKED_CAST")
                val root = Json.parse(StringReader(resp.body())) as Map<String, List<Map<String, String>>>
                val version = root
                    .getValue("components")
                    .firstOrNull { it["qualifier"].equals("Release", ignoreCase = true) }
                    ?.getValue("version")
                latestStableVersion.set(version)
                SwingUtilities.invokeLater(::afterFetch)
            }
        }
    }

    fun close() {
        onClose()
        rememberedBounds = welcomeFrame.bounds
        welcomeFrame.dispose()
    }

    fun onChangeTab(enteredPanel: JPanel) {
        if (enteredPanel == welcomeFrame.panel.preferencesPanel)
            welcomeFrame.panel.preferencesPanel.preferencesForm.loadAllPreferences()
    }

    fun tryOpenProject(projectDir: Path) {
        if (blockOpening || projectDir.isRegularFile())
            return

        if (MasterController.projectCtrls.any { it.projectDir == projectDir }) {
            JOptionPane.showMessageDialog(
                welcomeFrame, l10n("ui.open.alreadyOpen.msg", projectDir),
                l10n("ui.open.alreadyOpen.title"), JOptionPane.ERROR_MESSAGE
            )
            return
        }

        // If the two required project files don't exist yet, create them.
        val stylingFile = projectDir.resolve(ProjectController.STYLING_FILE_NAME)
        if (projectDir.notExists() || stylingFile.notExists() || locateCreditsFile(projectDir).first == null) {
            // If the user cancels the dialog, cancel opening the project directory.
            val (locale, format) = CreateForm.showDialog() ?: return
            copyTemplate(
                projectDir, locale, format,
                // Verify a second time that the two required project files still do not exists,
                // in case the user has created them manually in the meantime.
                copyStyling = stylingFile.notExists(), copyCredits = locateCreditsFile(projectDir).first == null
            )
        }

        blockOpening = true

        // Memorize the opened project directory at the first position in the list.
        PreferencesStorage.memorizedProjectDirs = PreferencesStorage.memorizedProjectDirs.toMutableList().apply {
            remove(projectDir)
            add(0, projectDir)
        }

        // Find the screen on which the WelcomeFrame currently occupies the most area.
        // The project will be opened on that screen.
        val openOnScreen = welcomeFrame.graphicsConfiguration

        onOpenProject(projectDir, openOnScreen)

        close()
    }

    fun configureUILocaleWish(wish: LocaleWish) {
        val hasChanged = wish != PreferencesStorage.uiLocaleWish
        PreferencesStorage.uiLocaleWish = wish

        if (hasChanged) {
            MasterController.applyUILocaleWish()
            SwingUtilities.invokeLater {
                val newLocale = wish.resolve()
                val restart = JOptionPane.showConfirmDialog(
                    welcomeFrame, l10n("ui.preferences.restartUILocale.msg", newLocale),
                    l10n("ui.preferences.restartUILocale.title", newLocale), JOptionPane.OK_CANCEL_OPTION
                ) == JOptionPane.OK_OPTION
                if (restart) {
                    MasterController.tryCloseProjectsAndDisposeAllFrames()
                    MasterController.showWelcomeFrame()
                }
            }
        }
    }

    fun configureCheckForUpdates(checkForUpdates: Boolean) {
        val hasChanged = checkForUpdates != PreferencesStorage.checkForUpdates
        PreferencesStorage.checkForUpdates = checkForUpdates

        if (hasChanged && checkForUpdates)
            doCheckForUpdates()
    }

    fun configureHintTrackPending(trackName: String, pending: Boolean) {
        val hasChanged = pending != PreferencesStorage.isHintTrackPending(trackName)
        PreferencesStorage.setHintTrackPending(trackName, pending)

        if (hasChanged && pending)
            when (trackName) {
                OPEN_HINT_TRACK_NAME ->
                    makeOpenHintTrack(this).playIfPending()
                PROJECT_HINT_TRACK_NAME ->
                    MasterController.projectCtrls.firstOrNull()?.let(::makeProjectHintTrack)?.playIfPending()
            }
    }

    companion object {
        private var rememberedBounds: Rectangle? = null
        private var latestStableVersion = AtomicReference<String>()
    }

}
