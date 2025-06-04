package com.loadingbyte.cinecred.ui.ctrl

import com.formdev.flatlaf.json.Json
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.projectio.service.*
import com.loadingbyte.cinecred.ui.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.FileExtAssortment
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
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
    private val deliveryDestTemplatesListener = { templates: List<DeliveryDestTemplate> ->
        welcomeView.preferences_start_setDeliveryDestTemplates(templates)
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

    private var newBrowseSelection: Path? = null
    private var currentlyEditedOverlay: ConfigurableOverlay? = null
    private var currentlyEditedDeliveryDestTemplate: DeliveryDestTemplate? = null

    private val createProjectThread = AtomicReference<Thread?>()
    private val addAccountThread = AtomicReference<Thread?>()

    private var withheldPrefChanges: MutableMap<Preference<*>, () -> Unit>? = null

    fun supplyView(welcomeView: WelcomeViewComms) {
        check(!::welcomeView.isInitialized)
        this.welcomeView = welcomeView

        // Register the accounts, overlays, and delivery location templates listeners.
        addAccountListListener(accountListListener)
        OVERLAYS_PREFERENCE.addListener(overlaysListener)
        DELIVERY_DEST_TEMPLATES_PREFERENCE.addListener(deliveryDestTemplatesListener)

        // Retrieve the current preferences, accounts, overlays, and delivery location templates.
        welcomeView.preferences_start_setUILocaleWish(UI_LOCALE_PREFERENCE.get())
        welcomeView.preferences_start_setCheckForUpdates(CHECK_FOR_UPDATES_PREFERENCE.get())
        welcomeView.preferences_start_setWelcomeHintTrackPending(WELCOME_HINT_TRACK_PENDING_PREFERENCE.get())
        welcomeView.preferences_start_setProjectHintTrackPending(PROJECT_HINT_TRACK_PENDING_PREFERENCE.get())
        accountListListener()
        overlaysListener(OVERLAYS_PREFERENCE.get())
        deliveryDestTemplatesListener(DELIVERY_DEST_TEMPLATES_PREFERENCE.get())

        // Remove all memorized directories which are no longer project directories.
        val memProjectDirs = PROJECT_DIRS_PREFERENCE.get().toMutableList()
        val modified = memProjectDirs.removeAll { dir -> !isProjectDir(dir) }
        if (modified)
            PROJECT_DIRS_PREFERENCE.set(memProjectDirs)

        // Show "open" buttons for the memorized projects.
        welcomeView.projects_start_setMemorized(memProjectDirs)

        // Start the project creation file chooser where the previous project was opened.
        newBrowseSelection = memProjectDirs.firstOrNull()?.parent

        // We will later use the Picture mechanism for reading overlay images, so tell the UI which files that accepts.
        val imageAss = FileExtAssortment(l10n("ui.preferences.overlays.configure.fileType"), Picture.EXTS.toList())
        welcomeView.preferences_configureOverlay_setImageFileExtAssortment(imageAss)

        welcomeView.setChangelog(CHANGELOG_HTML)
        welcomeView.setAbout(aboutHtml())
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

        // If the project version is present, but either unrecognizable (meaning the version format has changed in the
        // future) or recognizable but higher than our version, warn the user.
        val projectVersion = try {
            readStylingVersion(projectDir.resolve(STYLING_FILE_NAME))
        } catch (_: IOException) {
            // If the styling file cannot be read, ignore the error for now. The full styling reader will later display
            // a proper error message.
            null
        }
        if (!projectVersion.isNullOrBlank() && !VERSION.endsWith("-SNAPSHOT") &&
            (!projectVersion.matches(Regex("\\d+\\.\\d+\\.\\d+")) ||
                    Arrays.compare(
                        VERSION.split('.').map(String::toInt).toIntArray(),
                        projectVersion.split('.').map(String::toInt).toIntArray()
                    ) < 0)
        ) {
            welcomeView.display()
            if (!welcomeView.showNewerVersionQuestion(projectDir, projectVersion))
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
            welcomeView.projects_setCard(ProjectsCard.START)
            welcomeView.display()
            projects_start_onCompleteCreateDialog(projectDir)
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
                .setHeader("User-Agent", USER_AGENT).build()
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
        if (!welcomeView.isFromWelcomeWindow(event))
            return false
        if (event.modifiersEx == 0 && event.keyCode == VK_ESCAPE) {
            val tab = welcomeView.getTab()
            if (tab == WelcomeTab.PROJECTS) {
                projects_createWait_onClickCancel()
                return true
            } else if (tab == WelcomeTab.PREFERENCES) {
                preferences_establishAccount_onClickCancel()
                preferences_configureOverlay_onClickCancel()
                return true
            }
        }
        if (event.modifiersEx == CTRL_DOWN_MASK && (event.keyCode.let { it == VK_Q || it == VK_W })) {
            close()
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
        DELIVERY_DEST_TEMPLATES_PREFERENCE.removeListener(deliveryDestTemplatesListener)

        createProjectThread.getAndSet(null)?.interrupt()
        addAccountThread.getAndSet(null)?.interrupt()
        welcomeView.close()
        masterCtrl.onCloseWelcomeFrame()
    }

    override fun setTab(tab: WelcomeTab) {
        welcomeView.setTab(tab)
    }

    override fun showOverlayCreation() {
        setTab(WelcomeTab.PREFERENCES)
        preferences_start_onClickAddOverlay()
    }

    override fun showDeliveryDestTemplateCreation() {
        setTab(WelcomeTab.PREFERENCES)
        preferences_start_onClickAddDeliveryDestTemplate()
    }

    override fun onPassHintTrack() {
        WELCOME_HINT_TRACK_PENDING_PREFERENCE.set(false)
    }

    override fun projects_start_onClickOpen() {
        welcomeView.projects_start_showOpenDialog(PROJECT_DIRS_PREFERENCE.get().firstOrNull())
    }

    override fun projects_start_onCompleteOpenDialog(projectDir: Path?) {
        tryOpenProject(projectDir ?: return)
    }

    override fun projects_start_onClickOpenMemorized(projectDir: Path) {
        tryOpenProject(projectDir)
    }

    override fun projects_start_onClickCreate() {
        welcomeView.projects_start_showCreateDialog(newBrowseSelection)
    }

    override fun projects_start_onCompleteCreateDialog(projectDir: Path?) {
        projectDir ?: return
        // Ask for confirmation if the selected directory is not empty; maybe the user made a mistake.
        if (projectDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true)) {
            if (!welcomeView.showNotEmptyQuestion(projectDir))
                return
        }
        newBrowseSelection = projectDir
        welcomeView.projects_createConfigure_setProjectDir(projectDir)
        welcomeView.projects_createConfigure_setCreditsFilename("${projectDir.name} Credits")
        welcomeView.projects_setCard(ProjectsCard.CREATE_CONFIGURE)
    }

    override fun projects_start_onDrop(path: Path) {
        tryOpenOrCreateProject(path)
    }

    override fun projects_createConfigure_onClickBack() = welcomeView.projects_setCard(ProjectsCard.START)

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
            } catch (e: IOException) {
                SwingUtilities.invokeLater { welcomeView.projects_createWait_setError(e.message ?: e.toString()) }
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
                    welcomeView.showCannotRemoveAccountMessage(account, e.message ?: e.toString())
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

    override fun preferences_configureAccount_verifyServer(service: Service?, server: String): String? {
        val server = try {
            URI(server)
        } catch (_: URISyntaxException) {
            null
        }
        return if (server == null ||
            service != null && service.accountNeedsServer && service.isServerPlausible(server)
        ) l10n("ui.preferences.accounts.configure.invalidURL") else null
    }

    override fun preferences_configureAccount_onClickCancel() = welcomeView.preferences_setCard(PreferencesCard.START)

    override fun preferences_configureAccount_onClickEstablish(label: String, service: Service, server: String) {
        welcomeView.preferences_establishAccount_setAction(authorize = service.authorizer != null)
        welcomeView.preferences_establishAccount_setError(null)
        welcomeView.preferences_setCard(PreferencesCard.ESTABLISH_ACCOUNT)
        addAccountThread.set(Thread({
            try {
                service.addAccount(label, if (service.accountNeedsServer) URI(server) else null)
                SwingUtilities.invokeLater { welcomeView.preferences_setCard(PreferencesCard.START) }
            } catch (e: IOException) {
                val error = e.message ?: e.toString()
                SwingUtilities.invokeLater { welcomeView.preferences_establishAccount_setError(error) }
            } catch (_: InterruptedException) {
                // Let the thread come to a stop.
            }
            addAccountThread.set(null)
        }, "AddAccount").apply { isDaemon = true; start() })
    }

    override fun preferences_establishAccount_onClickCancel() {
        addAccountThread.getAndSet(null)?.interrupt()
        welcomeView.preferences_setCard(PreferencesCard.START)
    }

    override fun preferences_start_onClickAddOverlay() {
        currentlyEditedOverlay = null
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

    override fun preferences_configureOverlay_verifyName(name: String) = when {
        name.isBlank() -> l10n("blank")
        OVERLAYS_PREFERENCE.get().any { overlay ->
            overlay.uuid != currentlyEditedOverlay?.uuid && overlay.label == name
        } ->
            l10n("ui.preferences.accounts.configure.labelAlreadyInUse")
        else -> null
    }

    override fun preferences_configureOverlay_onClickCancel() {
        welcomeView.preferences_setCard(PreferencesCard.START)
    }

    override fun preferences_configureOverlay_onClickDoneOrApply(
        done: Boolean,
        type: Class<out ConfigurableOverlay>,
        name: String,
        aspectRatioH: Double,
        aspectRatioV: Double,
        linesColor: Color4f?,
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
                    val raster =
                        when (val pic = Picture.load(imageFile)) {
                            is Picture.Raster -> pic
                            is Picture.Vector -> pic.use {
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
                    ImageOverlay(uuid, name, raster, rasterPersisted = false, imageUnderlay)
                } catch (e: Exception) {
                    welcomeView.showCannotReadOverlayImageMessage(imageFile, e.message ?: e.toString())
                    welcomeView.preferences_setCard(PreferencesCard.START)
                    return
                }
            else -> throw IllegalArgumentException()
        }
        currentlyEditedOverlay = newOverlay
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

    override fun preferences_start_onClickAddDeliveryDestTemplate() {
        currentlyEditedDeliveryDestTemplate = null
        welcomeView.preferences_configureDeliveryDestTemplate_setForm(name = "", templateStr = "")
        welcomeView.preferences_setCard(PreferencesCard.CONFIGURE_DELIVERY_LOC_TEMPLATE)
    }

    override fun preferences_start_onClickEditDeliveryDestTemplate(template: DeliveryDestTemplate) {
        currentlyEditedDeliveryDestTemplate = template
        welcomeView.preferences_configureDeliveryDestTemplate_setForm(
            name = template.name, templateStr = template.l10nStr()
        )
        welcomeView.preferences_setCard(PreferencesCard.CONFIGURE_DELIVERY_LOC_TEMPLATE)
    }

    override fun preferences_start_onClickRemoveDeliveryDestTemplate(template: DeliveryDestTemplate) {
        DELIVERY_DEST_TEMPLATES_PREFERENCE.set(DELIVERY_DEST_TEMPLATES_PREFERENCE.get().filter { it != template })
    }

    override fun preferences_configureDeliveryDestTemplate_verifyName(name: String) = when {
        name.isBlank() -> l10n("blank")
        DELIVERY_DEST_TEMPLATES_PREFERENCE.get().any { template ->
            template.uuid != currentlyEditedDeliveryDestTemplate?.uuid && template.name == name
        } ->
            l10n("ui.preferences.accounts.configure.labelAlreadyInUse")
        else -> null
    }

    override fun preferences_configureDeliveryDestTemplate_verifyTemplateStr(templateStr: String): String? {
        if (templateStr.isBlank())
            return l10n("blank")
        try {
            DeliveryDestTemplate(UUID.randomUUID(), "", templateStr)
            return null
        } catch (e: DeliveryDestTemplate.UnrecognizedPlaceholderException) {
            val tag = l10nQuoted("{{${e.placeholderTag}}}")
            return l10n("ui.preferences.deliveryDestTemplates.configure.unknownPlaceholder", tag)
        }
    }

    override fun preferences_configureDeliveryDestTemplate_onClickCancel() {
        welcomeView.preferences_setCard(PreferencesCard.START)
    }

    override fun preferences_configureDeliveryDestTemplate_onClickDoneOrApply(
        done: Boolean, name: String, templateStr: String
    ) {
        val edited = currentlyEditedDeliveryDestTemplate
        val newTemplate = DeliveryDestTemplate(edited?.uuid ?: UUID.randomUUID(), name, templateStr)
        currentlyEditedDeliveryDestTemplate = newTemplate
        val newTemplates = DELIVERY_DEST_TEMPLATES_PREFERENCE.get().toMutableList()
        edited?.let(newTemplates::remove)
        newTemplates.add(newTemplate)
        newTemplates.sortBy(DeliveryDestTemplate::name)
        DELIVERY_DEST_TEMPLATES_PREFERENCE.set(newTemplates)
        if (done)
            welcomeView.preferences_setCard(PreferencesCard.START)
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

        private fun aboutHtml(): String {
            val translators = useResourceStream("/translators.properties") { Properties().apply { load(it.reader()) } }
                .entries
                .map { (tag, people) -> Locale.forLanguageTag(tag as String).displayName to people }
                .sortedWithCollator(caseInsensitiveCollator()) { (lang, _) -> lang }
                .joinToString("  \u2022  ") { (lang, people) ->
                    "$lang: <i>".replace(' ', '\u00A0') +
                            l10nEnum((people as String).split(',').map { it.replace(' ', '\u00A0') }) + "</i>"
                }
            return """
                <html>
                    <body style="text-align: center">
                        <p style="margin-top: 0">
                            Cinecred $VERSION: ${l10n("slogan")}<br>
                            <i>$COPYRIGHT</i>
                        </p>
                        <p>
                            <u>${l10n("ui.about.translators")}</u><br>
                            $translators
                        </p>
                    </body>
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
