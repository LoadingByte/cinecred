package com.loadingbyte.cinecred.ui.ctrl

import com.formdev.flatlaf.json.Json
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.Canvas
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.projectio.service.*
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.FileExtAssortment
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.VK_ESCAPE
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView
import kotlin.io.path.*
import kotlin.math.ceil


class WelcomeCtrl(private val masterCtrl: MasterCtrlComms) : WelcomeCtrlComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedWelcomeView get() = welcomeView
    // =========================================

    private lateinit var welcomeView: WelcomeViewComms

    private val uiLocaleListener = { wish: LocaleWish ->
        welcomeView.preferences_start_setUILocaleWish(wish)
        SwingUtilities.invokeLater {
            if (welcomeView.showRestartUILocaleQuestion(newLocale = wish.locale)) {
                masterCtrl.tryCloseProjectsAndDisposeAllFrames()
                masterCtrl.showWelcomeFrame(tab = WelcomeTab.PREFERENCES)
            }
        }
    }
    private val checkForUpdatesListener = { check: Boolean ->
        welcomeView.preferences_start_setCheckForUpdates(check)
        tryCheckForUpdates()
    }
    private val welcomeHintTrackPendingListener = { pending: Boolean ->
        welcomeView.preferences_start_setWelcomeHintTrackPending(pending)
        if (pending)
            welcomeView.playHintTrack()
    }
    private val projectHintTrackPendingListener = { pending: Boolean ->
        welcomeView.preferences_start_setProjectHintTrackPending(pending)
    }
    private val accountListListener = {
        SwingUtilities.invokeLater {
            val accounts = SERVICES.flatMap(Service::accounts)
            welcomeView.projects_createConfigure_setAccounts(accounts)
            welcomeView.preferences_start_setAccounts(accounts)
        }
    }
    private val overlaysListener = { overlays: List<ConfigurableOverlay> ->
        welcomeView.preferences_start_setOverlays(overlays)
    }

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
    private var currentlyEditedOverlay: ConfigurableOverlay? = null

    private val createProjectThread = AtomicReference<Thread?>()
    private val addAccountThread = AtomicReference<Thread?>()

    private var withheldPrefChanges: MutableMap<Preference<*>, () -> Unit>? = null

    fun supplyView(welcomeView: WelcomeViewComms) {
        check(!::welcomeView.isInitialized)
        this.welcomeView = welcomeView

        // Register the accounts and overlays listeners.
        addAccountListListener(accountListListener)
        OVERLAYS_PREFERENCE.addListener(overlaysListener)

        // Retrieve the current preferences, accounts, and overlays.
        welcomeView.preferences_start_setUILocaleWish(UI_LOCALE_PREFERENCE.get())
        welcomeView.preferences_start_setCheckForUpdates(CHECK_FOR_UPDATES_PREFERENCE.get())
        welcomeView.preferences_start_setWelcomeHintTrackPending(WELCOME_HINT_TRACK_PENDING_PREFERENCE.get())
        welcomeView.preferences_start_setProjectHintTrackPending(PROJECT_HINT_TRACK_PENDING_PREFERENCE.get())
        accountListListener()
        overlaysListener(OVERLAYS_PREFERENCE.get())

        // Remove all memorized directories which are no longer project directories.
        val memProjectDirs = PROJECT_DIRS_PREFERENCE.get().toMutableList()
        val modified = memProjectDirs.removeAll { dir -> !isProjectDir(dir) }
        if (modified)
            PROJECT_DIRS_PREFERENCE.set(memProjectDirs)

        // Show "open" buttons for the memorized projects.
        welcomeView.projects_start_setMemorized(memProjectDirs)

        // Start the project dir file choosers where the previous project was opened, or at the default location. It is
        // imperative that we set the current dir either case, as we need to trigger the selected dir change listeners.
        val dir = memProjectDirs.firstOrNull()?.parent ?: FileSystemView.getFileSystemView().defaultDirectory.toPath()
        welcomeView.projects_openBrowse_setCurrentDir(dir)
        welcomeView.projects_createBrowse_setCurrentDir(dir)

        // We will later use the Picture mechanism for reading overlay images, so tell the UI which files that accepts.
        welcomeView.preferences_configureOverlay_setImageFileExtAssortment(FileExtAssortment(Picture.EXTS.toList()))

        welcomeView.setChangelog(CHANGELOG_HTML)
        welcomeView.setAbout(ABOUT_HTML)
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
        PROJECT_DIRS_PREFERENCE.set(PROJECT_DIRS_PREFERENCE.get().toMutableList().apply {
            remove(projectDir)
            add(0, projectDir)
        })

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
        else if (CHECK_FOR_UPDATES_PREFERENCE.get()) {
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
                    .first { it["qualifier"].equals("Release", ignoreCase = true) }
                    .getValue("version")
                latestStableVersion.compareAndSet(null, version)
                SwingUtilities.invokeLater(::afterFetch)
            }
        }
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        if (welcomeView.isFromWelcomeWindow(event) && welcomeView.getTab() == WelcomeTab.PROJECTS &&
            event.modifiersEx == 0 && event.keyCode == VK_ESCAPE
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
                masterCtrl.showWelcomeFrame(openProjectDir, tab = WelcomeTab.PREFERENCES)
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
            // Register the preference listeners.
            UI_LOCALE_PREFERENCE.addListener(uiLocaleListener)
            CHECK_FOR_UPDATES_PREFERENCE.addListener(checkForUpdatesListener)
            WELCOME_HINT_TRACK_PENDING_PREFERENCE.addListener(welcomeHintTrackPendingListener)
            PROJECT_HINT_TRACK_PENDING_PREFERENCE.addListener(projectHintTrackPendingListener)
            // If enabled, check for updates and run the welcome hint track.
            tryCheckForUpdates()
            if (WELCOME_HINT_TRACK_PENDING_PREFERENCE.get())
                welcomeView.playHintTrack()
        }

        if (SET_UP_PREFERENCE.get())
            finishInit(false)
        else if (!alreadyStartedInitialConfiguration) {
            alreadyStartedInitialConfiguration = true
            // If we are here, the program is started for the first time as no preferences have been saved previously.
            // Lock all other tabs and let the user initially configure all preferences. This lets him specify whether
            // he wants update checks and hints before the program actually contacts the server or starts a hint track.
            // Also, it lets him directly set his preferred locale in case that differs from the system locale.
            welcomeView.setTab(WelcomeTab.PREFERENCES)
            welcomeView.setTabsLocked(true)
            withheldPrefChanges = HashMap()
            val defaultUILocaleWish = UI_LOCALE_PREFERENCE.get()
            welcomeView.preferences_start_setInitialSetup(true) {
                SET_UP_PREFERENCE.set(true)
                withheldPrefChanges?.values?.forEach { it() }
                withheldPrefChanges = null
                welcomeView.setTabsLocked(false)
                welcomeView.preferences_start_setInitialSetup(false, null)
                finishInit(initConfigChangedUILocaleWish = UI_LOCALE_PREFERENCE.get() != defaultUILocaleWish)
            }
            welcomeView.display()
        }
    }

    override fun close() {
        UI_LOCALE_PREFERENCE.removeListener(uiLocaleListener)
        CHECK_FOR_UPDATES_PREFERENCE.removeListener(checkForUpdatesListener)
        WELCOME_HINT_TRACK_PENDING_PREFERENCE.removeListener(welcomeHintTrackPendingListener)
        PROJECT_HINT_TRACK_PENDING_PREFERENCE.removeListener(projectHintTrackPendingListener)
        removeAccountListListener(accountListListener)
        OVERLAYS_PREFERENCE.removeListener(overlaysListener)

        createProjectThread.getAndSet(null)?.interrupt()
        addAccountThread.getAndSet(null)?.interrupt()
        welcomeView.close()
        masterCtrl.onCloseWelcomeFrame()
    }

    override fun setTab(tab: WelcomeTab) {
        welcomeView.setTab(tab)
    }

    override fun onPassHintTrack() {
        WELCOME_HINT_TRACK_PENDING_PREFERENCE.set(false)
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
        if (projectDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true)) {
            if (!welcomeView.showNotEmptyQuestion(projectDir))
                return
        }
        welcomeView.projects_createConfigure_setProjectDir(projectDir)
        welcomeView.projects_createConfigure_setCreditsFilename("${projectDir.name} Credits")
        welcomeView.projects_setCard(ProjectsCard.CREATE_CONFIGURE)
    }

    override fun projects_createConfigure_onClickDone(
        locale: Locale,
        resolution: Resolution,
        fps: FPS,
        timecodeFormat: TimecodeFormat,
        sample: Boolean,
        creditsLocation: CreditsLocation,
        creditsFormat: SpreadsheetFormat,
        creditsAccount: Account?,
        creditsFilename: String
    ) {
        val projectDir = newBrowseSelection ?: return
        val effSample = if (creditsLocation == CreditsLocation.SKIP) false else sample
        val template = Template(locale, resolution, fps, timecodeFormat, effSample)
        welcomeView.projects_createWait_setError(null)
        welcomeView.projects_setCard(ProjectsCard.CREATE_WAIT)
        createProjectThread.set(Thread({
            try {
                when (creditsLocation) {
                    CreditsLocation.LOCAL ->
                        tryCopyTemplate(projectDir, template, creditsFormat)
                    CreditsLocation.SERVICE ->
                        tryCopyTemplate(projectDir, template, creditsAccount!!, creditsFilename)
                    CreditsLocation.SKIP ->
                        tryCopyTemplate(projectDir, template)
                }
                SwingUtilities.invokeLater { tryOpenProject(projectDir) }
            } catch (e: ForbiddenException) {
                val error = l10n("ui.projects.create.error.access")
                SwingUtilities.invokeLater { welcomeView.projects_createWait_setError(error) }
            } catch (e: DownException) {
                val error = l10n("ui.projects.create.error.unresponsive")
                SwingUtilities.invokeLater { welcomeView.projects_createWait_setError(error) }
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

    override fun <P : Any> preferences_start_onChangeTopPreference(preference: Preference<P>, value: P) =
        withheldPrefChanges?.let { it[preference] = { preference.set(value) } } ?: preference.set(value)

    override fun preferences_start_onClickAddAccount() {
        welcomeView.preferences_configureAccount_resetForm()
        welcomeView.preferences_setCard(PreferencesCard.CONFIGURE_ACCOUNT)
    }

    override fun preferences_start_onClickRemoveAccount(account: Account) {
        welcomeView.preferences_start_setAccountRemovalLocked(account, true)
        val service = SERVICES.first { account in it.accounts }
        Thread({
            try {
                service.removeAccount(account)
            } catch (e: IOException) {
                SwingUtilities.invokeLater {
                    welcomeView.showCannotRemoveAccountMessage(account, e.toString())
                    welcomeView.preferences_start_setAccountRemovalLocked(account, false)
                }
            }
        }, "RemoveAccount").apply { isDaemon = true; start() }
    }

    override fun preferences_configureAccount_verifyLabel(label: String) = when {
        label.isBlank() ->
            l10n("blank")
        SERVICES.any { service -> service.accounts.any { account -> account.id == label } } ->
            l10n("ui.preferences.accounts.configure.labelAlreadyInUse")
        else -> null
    }

    override fun preferences_configureAccount_onClickCancel() = welcomeView.preferences_setCard(PreferencesCard.START)

    override fun preferences_configureAccount_onClickAuthorize(label: String, service: Service) {
        welcomeView.preferences_authorizeAccount_setError(null)
        welcomeView.preferences_setCard(PreferencesCard.AUTHORIZE_ACCOUNT)
        addAccountThread.set(Thread({
            try {
                service.addAccount(label)
                SwingUtilities.invokeLater { welcomeView.preferences_setCard(PreferencesCard.START) }
            } catch (e: IOException) {
                SwingUtilities.invokeLater { welcomeView.preferences_authorizeAccount_setError(e.toString()) }
            } catch (_: InterruptedException) {
                // Let the thread come to a stop.
            }
            addAccountThread.set(null)
        }, "AddAccount").apply { isDaemon = true; start() })
    }

    override fun preferences_authorizeAccount_onClickCancel() {
        addAccountThread.getAndSet(null)?.interrupt()
        welcomeView.preferences_setCard(PreferencesCard.START)
    }

    override fun preferences_start_onClickAddOverlay() {
        welcomeView.preferences_configureOverlay_setForm(
            name = "",
            type = AspectRatioOverlay::class.java,
            aspectRatioH = 1.0,
            aspectRatioV = 1.0,
            linesColor = null,
            linesH = emptyList(),
            linesV = emptyList(),
            imageFile = Path(""),
            imageUnderlay = false
        )
        welcomeView.preferences_setCard(PreferencesCard.CONFIGURE_OVERLAY)
    }

    override fun preferences_start_onClickEditOverlay(overlay: ConfigurableOverlay) {
        currentlyEditedOverlay = overlay
        welcomeView.preferences_configureOverlay_setForm(
            type = overlay.javaClass,
            name = when (overlay) {
                is AspectRatioOverlay -> ""
                is LinesOverlay -> overlay.name
                is ImageOverlay -> overlay.name
            },
            aspectRatioH = if (overlay is AspectRatioOverlay) overlay.h else 1.0,
            aspectRatioV = if (overlay is AspectRatioOverlay) overlay.v else 1.0,
            linesColor = if (overlay is LinesOverlay) overlay.color else null,
            linesH = if (overlay is LinesOverlay) overlay.hLines else emptyList(),
            linesV = if (overlay is LinesOverlay) overlay.vLines else emptyList(),
            imageFile = Path(""),
            imageUnderlay = if (overlay is ImageOverlay) overlay.underlay else false
        )
        welcomeView.preferences_setCard(PreferencesCard.CONFIGURE_OVERLAY)
    }

    override fun preferences_start_onClickRemoveOverlay(overlay: ConfigurableOverlay) {
        OVERLAYS_PREFERENCE.set(OVERLAYS_PREFERENCE.get().filter { it != overlay })
    }

    override fun preferences_configureOverlay_onClickCancel() {
        currentlyEditedOverlay = null
        welcomeView.preferences_setCard(PreferencesCard.START)
    }

    override fun preferences_configureOverlay_onClickDoneOrApply(
        done: Boolean,
        type: Class<out ConfigurableOverlay>,
        name: String,
        aspectRatioH: Double,
        aspectRatioV: Double,
        linesColor: Color?,
        linesH: List<Int>,
        linesV: List<Int>,
        imageFile: Path,
        imageUnderlay: Boolean
    ) {
        val edited = currentlyEditedOverlay
        val uuid = edited?.uuid ?: UUID.randomUUID()
        val newOverlay = when (type) {
            AspectRatioOverlay::class.java -> AspectRatioOverlay(uuid, aspectRatioH, aspectRatioV)
            LinesOverlay::class.java -> LinesOverlay(uuid, name, linesColor, linesH, linesV)
            ImageOverlay::class.java ->
                if (edited is ImageOverlay && imageFile == Path(""))
                    ImageOverlay(uuid, name, edited.raster, edited.rasterPersisted, imageUnderlay)
                else try {
                    val raster = Picture.load(imageFile).use { pic ->
                        when (pic) {
                            is Picture.Raster -> pic
                            is Picture.Vector -> {
                                val res = Resolution(ceil(pic.width).toInt(), ceil(pic.height).toInt())
                                // For now, we materialize vector overlays in the sRGB color space. SVGs draw natively
                                // in this color space, as do most PDFs, and those who use another color space are drawn
                                // in that and the result is converted to sRGB.
                                val rep = Canvas.compatibleRepresentation(ColorSpace.SRGB)
                                val bitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
                                Canvas.forBitmap(bitmap.zero()).use(pic::drawTo)
                                Picture.Raster(bitmap)
                            }
                        }
                    }
                    ImageOverlay(uuid, name, raster, rasterPersisted = false, imageUnderlay)
                } catch (e: Exception) {
                    welcomeView.showCannotReadOverlayImageMessage(imageFile, e.toString())
                    welcomeView.preferences_setCard(PreferencesCard.START)
                    return
                }
            else -> throw IllegalArgumentException()
        }
        currentlyEditedOverlay = if (done) null else newOverlay
        val newOverlays = OVERLAYS_PREFERENCE.get().toMutableList()
        edited?.let(newOverlays::remove)
        newOverlays.add(newOverlay)
        newOverlays.sort()
        OVERLAYS_PREFERENCE.set(newOverlays)
        if (done)
            welcomeView.preferences_setCard(PreferencesCard.START)
        // If the user stays in the form, ensure that the image is not saved again the next time he confirms.
        else if (type == ImageOverlay::class.java)
            welcomeView.preferences_configureOverlay_clearImageFile()
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

        private val ABOUT_HTML = MessageFormat.format(
            useResourceStream("/about.html") { it.bufferedReader().readText() },
            VERSION, l10n("slogan"), COPYRIGHT, l10n("ui.about.translators")
        )

        private val LICENSES = run {
            val rawAppLicenses = useResourcePath("/licenses") { appLicensesDir ->
                appLicensesDir.walkSafely().filter(Files::isRegularFile).map { file ->
                    val relPath = appLicensesDir.relativize(file).pathString
                    val splitIdx = relPath.indexOfAny(listOf("LICENSE", "NOTICE", "COPYING", "COPYRIGHT", "README"))
                    Triple(relPath.substring(0, splitIdx - 1), relPath.substring(splitIdx), file.readText())
                }
            }
            val appLicenses = rawAppLicenses.map { (lib, clf, text) ->
                val fancyLib = lib.replace("/", " \u2192 ").replaceFirstChar(Char::uppercaseChar)
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

}
