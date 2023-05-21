package com.loadingbyte.cinecred.ui.ctrl

import com.formdev.flatlaf.json.Json
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.isAllowedToBeProjectDir
import com.loadingbyte.cinecred.projectio.isProjectDir
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView
import kotlin.io.path.*
import kotlin.reflect.KMutableProperty1


class WelcomeCtrl(private val masterCtrl: MasterCtrlComms) : WelcomeCtrlComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedWelcomeView get() = welcomeView
    // =========================================

    private lateinit var welcomeView: WelcomeViewComms

    /*
     * The [commence] method can be called multiple times on the same object. However, some things should only be run
     * once, for example the hint track. These variables are used to enforce that.
     */
    private var alreadyStartedInitialConfiguration = false
    private var alreadyRanOpenActions = false
    /**
     * When the user clicks on a project opening button multiple times while he is waiting for the project window to
     * open, we do not want this to really trigger multiple project openings, because that would confront him with
     * error messages like "this project is already opened". Hence, after the user opened a project, we just block
     * opening any more projects using the same WelcomeFrame+Controller.
     */
    private var blockOpening = false

    private var openBrowseSelection: Path? = null
    private var newBrowseSelection: Path? = null

    private val createProjectThread = AtomicReference<Thread?>()

    private var cachePrefs: PreferencesDTO? = null

    fun supplyView(welcomeView: WelcomeViewComms) {
        check(!::welcomeView.isInitialized)
        this.welcomeView = welcomeView

        // Remove all memorized directories which are no longer project directories.
        val memProjectDirs = PersistentStorage.memorizedProjectDirs.toMutableList()
        val modified = memProjectDirs.removeAll { dir -> !isProjectDir(dir) }
        if (modified)
            PersistentStorage.memorizedProjectDirs = memProjectDirs

        // Show "open" buttons for the memorized projects.
        welcomeView.projects_start_setMemorized(memProjectDirs)

        // Start the project dir file choosers where the previous project was opened, or at the default location. It is
        // imperative that we set the current dir either case, as we need to trigger the selected dir change listeners.
        val dir = memProjectDirs.firstOrNull()?.parent ?: FileSystemView.getFileSystemView().defaultDirectory.toPath()
        welcomeView.projects_openBrowse_setCurrentDir(dir)
        welcomeView.projects_createBrowse_setCurrentDir(dir)

        welcomeView.setChangelog(CHANGELOG_HTML)
        welcomeView.setLicenses(LICENSES)
    }

    private fun tryOpenProject(projectDir: Path): Boolean {
        if (blockOpening || !projectDir.isAbsolute)
            return false

        if (!projectDir.isDirectory()) {
            welcomeView.display()
            SwingUtilities.invokeLater { welcomeView.showNotADirMessage(projectDir) }
            return false
        }
        if (!isAllowedToBeProjectDir(projectDir)) {
            welcomeView.display()
            SwingUtilities.invokeLater { welcomeView.showIllegalPathMessage(projectDir) }
            return false
        }
        if (!isProjectDir(projectDir)) {
            welcomeView.display()
            SwingUtilities.invokeLater { welcomeView.showNotAProjectMessage(projectDir) }
            return false
        }
        if (masterCtrl.isProjectDirOpen(projectDir)) {
            welcomeView.display()
            SwingUtilities.invokeLater { welcomeView.showAlreadyOpenMessage(projectDir) }
            return false
        }

        blockOpening = true

        // Memorize the opened project directory at the first position in the list.
        PersistentStorage.memorizedProjectDirs = PersistentStorage.memorizedProjectDirs.toMutableList().apply {
            remove(projectDir)
            add(0, projectDir)
        }

        try {
            masterCtrl.openProject(projectDir, openOnScreen = welcomeView.getMostOccupiedScreen())
        } catch (_: ProjectController.ProjectInitializationAbortedException) {
            blockOpening = false
            return false
        }
        close()
        return true
    }

    private fun tryOpenOrCreateProject(projectDir: Path) = when {
        !projectDir.isAbsolute -> false
        isProjectDir(projectDir) -> tryOpenProject(projectDir)
        else -> {
            welcomeView.setTab(WelcomeTab.PROJECTS)
            welcomeView.projects_setCard(ProjectsCard.CREATE_BROWSE)
            welcomeView.projects_createBrowse_setSelection(projectDir)
            welcomeView.display()
            projects_createBrowse_onClickNext()
            false
        }
    }

    /**
     * If configured accordingly, checks for updates (in the background if there's no cached information yet) and shows
     * an update tab if an update is available.
     */
    private fun tryCheckForUpdates() {
        fun afterFetch() {
            val version = latestStableVersion.get()
            if (version != null && version != VERSION)
                welcomeView.setUpdate(version)
        }

        if (latestStableVersion.get() != null)
            afterFetch()
        else if (PersistentStorage.checkForUpdates) {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
            val req = HttpRequest.newBuilder(URI.create("https://cinecred.com/dl/api/v1/components"))
                .setHeader("User-Agent", "Cinecred/$VERSION").build()
            client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept { resp ->
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


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        if (welcomeView.isFromWelcomeWindow(event) && welcomeView.getTab() == WelcomeTab.PROJECTS &&
            event.id == KEY_PRESSED && event.modifiersEx == 0 && event.keyCode == VK_ESCAPE
        ) {
            welcomeView.projects_setCard(ProjectsCard.START)
            return true
        }
        return false
    }

    // Be aware that this method might be called multiple times on the same object!
    // For example, it happens if a user opens the welcome window multiple times.
    override fun commence(openProjectDir: Path?) {
        fun finishInit(initConfigChangedUILocaleWish: Boolean) {
            // If the program was started for the first time and the user changed the locale during the initial
            // configuration, create a new welcome window so that the change applies. If you look at the code, you see
            // that the new window will take over at this exact point.
            if (initConfigChangedUILocaleWish) {
                masterCtrl.tryCloseProjectsAndDisposeAllFrames()
                masterCtrl.showWelcomeFrame(openProjectDir)
                return
            }
            // If the user dragged a folder onto the program, try opening that. If opening closed the window, stop here.
            if (openProjectDir != null && tryOpenOrCreateProject(openProjectDir))
                return
            // Otherwise, show the welcome window or, if it is already visible, bring it to the front.
            welcomeView.display()
            // If the welcome window was previously open, stop now. Otherwise, complete its regular initialization.
            if (alreadyRanOpenActions)
                return
            alreadyRanOpenActions = true
            tryCheckForUpdates()
            if (PersistentStorage.welcomeHintTrackPending)
                welcomeView.playHintTrack()
        }

        if (PersistentStorage.havePreferencesBeenSet())
            finishInit(false)
        else if (!alreadyStartedInitialConfiguration) {
            alreadyStartedInitialConfiguration = true
            // If we are here, the program is started for the first time as no preferences have been saved previously.
            // Lock all other tabs and let the user initially configure all preferences. This lets him specify whether
            // he wants update checks and hints before the program actually contacts the server or starts a hint track.
            // Also, it lets him directly set his preferred locale in case that differs from the system locale.
            welcomeView.setTab(WelcomeTab.PREFERENCES)
            welcomeView.setTabsLocked(true)
            val initPrefs = PreferencesDTO(PersistentStorage)
            cachePrefs = initPrefs
            val defaultUILocaleWish = initPrefs.uiLocaleWish
            welcomeView.setPreferencesSubmitButton {
                PersistentStorage.setFrom(initPrefs)
                cachePrefs = null
                comprehensivelyApplyLocale(initPrefs.uiLocaleWish.locale)
                welcomeView.setTabsLocked(false)
                welcomeView.setPreferencesSubmitButton(null)
                finishInit(initConfigChangedUILocaleWish = initPrefs.uiLocaleWish != defaultUILocaleWish)
            }
            welcomeView.display()
        }
    }

    override fun close() {
        createProjectThread.getAndSet(null)?.interrupt()
        welcomeView.close()
        masterCtrl.onCloseWelcomeFrame()
    }

    override fun setTab(tab: WelcomeTab) {
        welcomeView.setTab(tab)
    }

    override fun onPassHintTrack() {
        PersistentStorage.welcomeHintTrackPending = false
    }

    override fun onChangeTab(tab: WelcomeTab) {
        if (tab == WelcomeTab.PREFERENCES)
            welcomeView.setPreferences(PersistentStorage)
    }

    override fun projects_start_onClickOpen() = welcomeView.projects_setCard(ProjectsCard.OPEN_BROWSE)
    override fun projects_start_onClickCreate() = welcomeView.projects_setCard(ProjectsCard.CREATE_BROWSE)
    override fun projects_openBrowse_onClickCancel() = welcomeView.projects_setCard(ProjectsCard.START)
    override fun projects_createBrowse_onClickCancel() = welcomeView.projects_setCard(ProjectsCard.START)
    override fun projects_createConfigure_onClickBack() = welcomeView.projects_setCard(ProjectsCard.CREATE_BROWSE)

    override fun projects_start_onClickOpenMemorized(projectDir: Path) {
        tryOpenProject(projectDir)
    }

    override fun projects_start_onDrop(path: Path) {
        tryOpenOrCreateProject(path)
    }

    override fun projects_openBrowse_shouldShowAppIcon(dir: Path) =
        isProjectDir(dir)

    override fun projects_openBrowse_onChangeSelection(dir: Path?) {
        openBrowseSelection = dir?.normalize()
        if (openBrowseSelection?.let(::isProjectDir) == false) openBrowseSelection = null
        // Gray out the "open" button if the selected directory is not real or not a project dir.
        welcomeView.projects_openBrowse_setDoneEnabled(openBrowseSelection != null)
    }

    override fun projects_openBrowse_onDoubleClickDir(dir: Path): Boolean =
        if (isProjectDir(dir)) {
            // Try opening the project dir.
            tryOpenProject(dir)
            // Do not navigate into the project dir as opening the project might have come back with an error,
            // in which case we want to still be in the parent dir.
            true
        } else
            false

    override fun projects_openBrowse_onClickDone() {
        tryOpenProject(openBrowseSelection ?: return)
    }

    override fun projects_createBrowse_onChangeSelection(dir: Path?) {
        newBrowseSelection = dir?.normalize()
        if (newBrowseSelection?.let(::isAllowedToBeProjectDir) == false) newBrowseSelection = null
        // Gray out the "next" button if the selected directory is not real or not allowed to be a project dir.
        welcomeView.projects_createBrowse_setNextEnabled(newBrowseSelection != null)
    }

    override fun projects_createBrowse_onClickNext() {
        val projectDir = newBrowseSelection ?: return
        // Ask for confirmation if the selected directory is not empty; maybe the user made a mistake.
        if (projectDir.exists() && projectDir.useDirectoryEntries { seq -> !seq.all(Path::isHidden) }) {
            if (!welcomeView.showNotEmptyQuestion(projectDir))
                return
        }
        welcomeView.projects_createConfigure_setProjectDir(projectDir)
        welcomeView.projects_setCard(ProjectsCard.CREATE_CONFIGURE)
    }

    override fun projects_createConfigure_onClickDone(locale: Locale, format: SpreadsheetFormat, scale: Int) {
        val projectDir = newBrowseSelection ?: return
        welcomeView.projects_createWait_setError(null)
        welcomeView.projects_setCard(ProjectsCard.CREATE_WAIT)
        createProjectThread.set(Thread({
            try {
                tryCopyTemplate(projectDir, locale, format, scale)
                SwingUtilities.invokeLater { tryOpenProject(projectDir) }
            } catch (e: IOException) {
                SwingUtilities.invokeLater { welcomeView.projects_createWait_setError(e.toString()) }
            } catch (_: InterruptedException) {
                // Let the thread come to a stop.
            }
            createProjectThread.set(null)
        }, "CreateProject").apply { isDaemon = true; start() })
    }

    override fun projects_createWait_onClickCancel() {
        createProjectThread.getAndSet(null)?.interrupt()
        welcomeView.projects_setCard(ProjectsCard.START)
    }

    override fun <V> onChangePreference(pref: KMutableProperty1<Preferences, V>, value: V) {
        val cachePrefs = cachePrefs
        if (cachePrefs != null)
            pref.set(cachePrefs, value)
        else {
            pref.set(PersistentStorage, value)
            pref.set(PreferenceListener(), value)
        }
    }


    companion object {

        private val CHANGELOG_HTML = useResourceStream("/CHANGELOG.md") { it.bufferedReader().readText() }.let { src ->
            val doc = Parser.builder().build().parse(src)
            // Remove the first heading which just says "Changelog".
            doc.firstChild.unlink()
            // The first version number heading should not have a top margin.
            val attrProvider = AttributeProvider { node, tagName, attrs ->
                if (tagName == "h2" && node.previous == null)
                    attrs["style"] = "margin-top: 0"
            }
            val mdHtml = HtmlRenderer.builder().attributeProviderFactory { attrProvider }.build().render(doc)
            """
                <html>
                    <head><style>
                        h2 { margin-top: 30; margin-bottom: 5; font-size: 20pt; text-decoration: underline }
                        h3 { margin-top: 15; margin-bottom: 5; font-size: 15pt }
                        h4 { margin-top: 15; margin-bottom: 4; font-size: 13pt }
                        ul { margin-top: 0; margin-bottom: 0; margin-left: 25 }
                        li { margin-top: 3 }
                    </style></head>
                    <body>$mdHtml</body>
                </html>
            """
        }

        private val LICENSES = run {
            val rawAppLicenses = useResourcePath("/licenses") { appLicensesDir ->
                appLicensesDir.walkSafely().filter(Files::isRegularFile).map { file ->
                    val relPath = appLicensesDir.relativize(file).pathString
                    val splitIdx = relPath.indexOfAny(listOf("LICENSE", "NOTICE", "COPYING", "COPYRIGHT", "README"))
                    Triple(relPath.substring(0, splitIdx - 1), relPath.substring(splitIdx), file.readText())
                }
            }
            val appLicenses = rawAppLicenses.map { (lib, clf, text) ->
                val fancyLib = lib.replace("/", " \u2192 ").replaceFirstChar(Char::uppercase)
                val fancyClf = if (rawAppLicenses.count { (o, _, _) -> o == lib } > 1) "  [${clf.lowercase()}]" else ""
                val fancyText = text.trim('\n')
                License(fancyLib + fancyClf, fancyText)
            }

            val jdkLicensesDir = Path(System.getProperty("java.home")).resolve("legal")
            val jdkLicenses = if (jdkLicensesDir.notExists()) emptyList() else
                jdkLicensesDir.walkSafely().filter(Files::isRegularFile).mapNotNull { file ->
                    val relFile = jdkLicensesDir.relativize(file)
                    when {
                        file.name.endsWith(".md") -> " \u2192 " + file.nameWithoutExtension
                        relFile.startsWith("java.base") -> "  [${file.name.lowercase().replace('_', ' ')}]"
                        else -> null
                    }?.let { suffix -> License("OpenJDK$suffix", file.readText()) }
                }

            (appLicenses + jdkLicenses).sortedBy(License::name)
        }

        private val latestStableVersion = AtomicReference<String>()

    }


    private class PreferencesDTO(
        override var uiLocaleWish: LocaleWish,
        override var checkForUpdates: Boolean,
        override var welcomeHintTrackPending: Boolean,
        override var projectHintTrackPending: Boolean
    ) : Preferences {
        constructor(other: Preferences) : this(
            other.uiLocaleWish,
            other.checkForUpdates,
            other.welcomeHintTrackPending,
            other.projectHintTrackPending
        )
    }


    private inner class PreferenceListener : Preferences {

        override var uiLocaleWish: LocaleWish
            get() = throw NotImplementedError()
            set(wish) {
                comprehensivelyApplyLocale(wish.locale)
                SwingUtilities.invokeLater {
                    if (welcomeView.showRestartUILocaleQuestion(newLocale = wish.locale)) {
                        masterCtrl.tryCloseProjectsAndDisposeAllFrames()
                        masterCtrl.showWelcomeFrame()
                    }
                }
            }

        override var checkForUpdates: Boolean
            get() = throw NotImplementedError()
            set(_) {
                tryCheckForUpdates()
            }

        override var welcomeHintTrackPending: Boolean
            get() = throw NotImplementedError()
            set(pending) {
                if (pending)
                    welcomeView.playHintTrack()
            }

        override var projectHintTrackPending: Boolean
            get() = throw NotImplementedError()
            set(pending) {
                if (pending)
                    masterCtrl.tryPlayProjectHintTrack()
            }

    }

}
