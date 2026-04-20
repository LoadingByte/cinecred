package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nEnumQuoted
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.imaging.Font
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.ui.Shortcut.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.ctrl.*
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import com.loadingbyte.cinecred.ui.helper.FontFamilies
import com.loadingbyte.cinecred.ui.helper.JobSlot
import com.loadingbyte.cinecred.ui.helper.usableBounds
import com.loadingbyte.cinecred.ui.styling.EditStylingPanel
import com.loadingbyte.cinecred.ui.view.delivery.DeliveryDockable
import com.loadingbyte.cinecred.ui.view.log.LogDockable
import com.loadingbyte.cinecred.ui.view.playback.PlaybackDockable
import com.loadingbyte.cinecred.ui.view.preview.PreviewDockable
import com.loadingbyte.cinecred.ui.view.toolbar.ToolbarDockable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.io.path.name


/** @throws ProjectController.ProjectInitializationAbortedException */
class ProjectController(
    val masterCtrl: MasterCtrlComms,
    val projectDir: Path,
    openOnScreen: GraphicsConfiguration?,
    trees: List<DockingFrame.Tree>?,
    private val onClose: () -> Unit
) {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedToolbarDockable get() = getDockable<ToolbarDockable>()
    @Deprecated("ENCAPSULATION LEAK") val leakedPreviewDockable get() = getDockable<PreviewDockable>()
    @Deprecated("ENCAPSULATION LEAK") val leakedLogDockable get() = getDockable<LogDockable>()
    @Deprecated("ENCAPSULATION LEAK") val leakedPlaybackDockable get() = getDockable<PlaybackDockable>()
    @Deprecated("ENCAPSULATION LEAK") val leakedDeliveryDockable get() = getDockable<DeliveryDockable>()
    private inline fun <reified D> getDockable(): D = projectFrame.dockables.first { it is D } as D
    // =========================================

    class ProjectInitializationAbortedException : Exception()

    // START OF INITIALIZATION PROCEDURE

    val projectName: String = projectDir.name
    val stylingHistory: StylingHistory

    private data class Input(
        val creditsWorkbooks: List<ProjectIntake.CreditsWorkbook>,
        val ioLog: List<ParserMsg>,
        val projectFonts: SortedMap<String, Font>?,
        val pictureLoaders: SortedMap<String, Picture.Loader>?,
        val tapes: SortedMap<String, Tape>?,
        val styling: Styling?
    )

    private val currentInput = AtomicReference(Input(emptyList(), emptyList(), null, null, null, null))
    private val processingJobSlot = JobSlot()

    // STEP 1:
    // Create and open the project UI.

    val toolbarCtrl: ToolbarCtrlComms = ToolbarCtrl(this)
    val previewCtrl: PreviewCtrlComms = PreviewCtrl(this)
    val logCtrl: LogCtrlComms = LogCtrl()
    val playbackCtrl: PlaybackCtrlComms = PlaybackCtrl()
    val deliveryCtrl: DeliveryCtrlComms = DeliveryCtrl(this)

    val stylingDockable = EditStylingPanel(this)
    val projectFrame: DockingFrame

    init {
        val toolbarDockable = ToolbarDockable(toolbarCtrl, playbackCtrl)
        val previewDockable = PreviewDockable(previewCtrl)
        val logDockable = LogDockable(logCtrl)
        val playbackDockable = PlaybackDockable(playbackCtrl)
        val deliveryDockable = DeliveryDockable(deliveryCtrl)

        toolbarCtrl.ready()

        projectFrame = DockingFrame(
            listOf(toolbarDockable, previewDockable, logDockable, stylingDockable, playbackDockable, deliveryDockable),
            configureWindow = { window ->
                val title = "$projectName \u2013 Cinecred"
                if (window is Frame) window.title = title
                if (window is Dialog) window.title = title
                // On macOS, show the opened project folder in the window title bar.
                (window as RootPaneContainer).rootPane.putClientProperty("Window.documentFile", projectDir.toFile())
            }, onChangeCollapsed = { dockableId, collapsed ->
                setDockableCollapsed(DockableId.valueOf(dockableId), collapsed)
            })
        projectFrame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                tryCloseProject()
            }
        })
        projectFrame.setTrees(trees ?: run {
            val availableLayouts = PresetWindowLayout.ALL + WINDOW_LAYOUTS_PREFERENCE.get()
            val defaultLayoutName = DEFAULT_WINDOW_LAYOUT_PREFERENCE.get()
            val bounds = openOnScreen!!.usableBounds
            (availableLayouts.find { it.name == defaultLayoutName } ?: availableLayouts[0]).trees(bounds)
        })
        onChangeWindowLayout()

        if (PROJECT_HINT_TRACK_PENDING_PREFERENCE.get())
            makeProjectHintTrack(this).play(onPass = { PROJECT_HINT_TRACK_PENDING_PREFERENCE.set(false) })
    }

    // STEP 2:
    // Set up the project intake, which will notify us about changes to the credits spreadsheet and auxiliary files.
    // Upon construction, the intake will immediately push the project fonts, pic loaders, and tapes from this thread.
    // This in turn means that those three collections will be initialized before the constructor returns.

    private val projectIntake = ProjectIntake(projectDir, object : ProjectIntake.Callbacks {

        override fun pushCreditsWorkbooks(
            creditsWorkbooks: List<ProjectIntake.CreditsWorkbook>, log: List<ParserMsg>, pollable: Boolean
        ) {
            process(currentInput.updateAndGet { it.copy(creditsWorkbooks = creditsWorkbooks, ioLog = log) })
            SwingUtilities.invokeLater { toolbarCtrl.setCreditsPollable(pollable) }
        }

        override fun pushProjectFonts(projectFonts: SortedMap<String, Font>) {
            val projectFamilies = FontFamilies(projectFonts.values)
            process(currentInput.updateAndGet { it.copy(projectFonts = projectFonts) })
            SwingUtilities.invokeLater { stylingDockable.updateProjectFontFamilies(projectFamilies) }
        }

        override fun pushPictureLoaders(pictureLoaders: SortedMap<String, Picture.Loader>) {
            process(currentInput.updateAndGet { it.copy(pictureLoaders = pictureLoaders) })
            SwingUtilities.invokeLater { stylingDockable.updatePictureLoaders(pictureLoaders.values) }
        }

        override fun pushTapes(tapes: SortedMap<String, Tape>) {
            process(currentInput.updateAndGet { it.copy(tapes = tapes) })
            SwingUtilities.invokeLater { stylingDockable.updateTapes(tapes.values) }
        }

    })

    // STEP 3:
    // Load the initial state of the styling from disk. Then render the credits if the credits spreadsheet file has
    // already arrived; otherwise, its arrival later on will trigger the render.

    private val stylingFile = projectDir.resolve(STYLING_FILE_NAME)

    init {
        val styling = try {
            val input = currentInput.get()
            readStyling(stylingFile, input.projectFonts!!, input.pictureLoaders!!, input.tapes!!)
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(
                projectFrame, arrayOf(l10n("ui.edit.cannotLoadStyling.msg"), e.message ?: e.toString()),
                l10n("ui.edit.cannotLoadStyling.title"), JOptionPane.ERROR_MESSAGE
            )
            tryCloseProject(force = true)
            throw ProjectInitializationAbortedException()
        }
        // The constructor of StylingHistory eventually invokes styling verification, and that needs tape metadata. To
        // avoid the verifier sequentially loading one tape after the other, we load all of them in parallel in advance.
        for (style in styling.tapeStyles)
            style.tape.tape?.loadMetadataInBackground()
        stylingHistory = StylingHistory(styling)
        process(currentInput.updateAndGet { it.copy(styling = styling) })
    }

    // STEP 4:
    // Now that the creation of the ProjectController can no longer fail, we can add listeners to the window layouts,
    // overlays and delivery location templates preferences, and be sure that they will be removed when the project is
    // closed again.

    private val windowLayoutsListener = { layouts: List<ConfigurableWindowLayout> ->
        val availableLayouts = PresetWindowLayout.ALL + layouts
        toolbarCtrl.setAvailableWindowLayouts(availableLayouts)
    }

    private val overlaysListener = { overlays: List<ConfigurableOverlay> ->
        val availableOverlays = (Overlay.BUNDLED + overlays).sorted()
        toolbarCtrl.setAvailableOverlays(availableOverlays)
        previewCtrl.setAvailableOverlays(availableOverlays)
    }

    private val deliveryDestTemplatesListener = { templates: List<DeliveryDestTemplate> ->
        deliveryCtrl.setDeliveryDestTemplates(templates)
    }

    init {
        WINDOW_LAYOUTS_PREFERENCE.addListener(windowLayoutsListener)
        OVERLAYS_PREFERENCE.addListener(overlaysListener)
        DELIVERY_DEST_TEMPLATES_PREFERENCE.addListener(deliveryDestTemplatesListener)
        windowLayoutsListener(WINDOW_LAYOUTS_PREFERENCE.get())
        overlaysListener(OVERLAYS_PREFERENCE.get())
        deliveryDestTemplatesListener(DELIVERY_DEST_TEMPLATES_PREFERENCE.get())
    }

    // END OF INITIALIZATION PROCEDURE

    private fun process(input: Input) {
        val (creditsWorkbooks, _, projectFonts, pictureLoaders, tapes, origStyling) = input
        // If something hasn't been initialized yet, abort reading and drawing the credits.
        if (creditsWorkbooks.isEmpty() ||
            projectFonts == null || pictureLoaders == null || tapes == null || origStyling == null
        )
            return doneProcessing(input, emptyList(), null, emptyList())

        // Execute the reading and drawing in another thread to not block the UI thread.
        processingJobSlot.submit {
            var styling: Styling = origStyling

            // Update references to auxiliary files (fonts, pictures, and tapes) in the styling.
            updateAuxiliaryReferences(
                styling.letterStyles, LetterStyle::font.st(),
                FontRef::name, FontRef::font, ::FontRef, ::FontRef,
                { name -> projectFonts[name] ?: Font.bundled(name) ?: Font.system(name) }
            )?.let { styling = styling.copy(letterStyles = it) }
            updateAuxiliaryReferences(
                styling.pictureStyles, PictureStyle::picture.st(),
                PictureRef::name, PictureRef::loader, ::PictureRef, ::PictureRef,
                pictureLoaders::get
            )?.let { styling = styling.copy(pictureStyles = it) }
            updateAuxiliaryReferences(
                styling.tapeStyles, TapeStyle::tape.st(),
                TapeRef::name, TapeRef::tape, ::TapeRef, ::TapeRef,
                tapes::get
            )?.let { styling = styling.copy(tapeStyles = it) }

            // The following verification needs tape metadata. To avoid the verifier sequentially loading one tape after
            // the other, we load all of them in parallel in advance.
            for (style in styling.tapeStyles)
                style.tape.tape?.loadMetadataInBackground()

            // If the styling is erroneous, abort and notify the UI about the error.
            if (verifyConstraints(styling).any { it.severity == ERROR }) {
                val error = ParserMsg(null, null, null, null, null, ERROR, l10n("ui.edit.stylingError"))
                return@submit doneProcessing(input, listOf(error), null, emptyList())
            }

            // Parse each credits workbook.
            val log = mutableListOf<ParserMsg>()
            val runtimeGroupSources = HashMap<RuntimeGroup, RuntimeGroupSource>()
            val creditsBooks = creditsWorkbooks.map { creditsWorkbook ->
                val credits = mutableListOf<Credits>()  // retains insertion order
                for (spreadsheet in creditsWorkbook.spreadsheets) {
                    val (curCredits, curLog, curRuntimeGroupSources) =
                        readCredits(creditsWorkbook.fileName, spreadsheet, styling, pictureLoaders, tapes)
                    credits += curCredits
                    log += curLog
                    runtimeGroupSources += curCredits.runtimeGroups.zip(curRuntimeGroupSources)
                }
                CreditsBook(creditsWorkbook.fileName, creditsWorkbook.uri, credits.toPersistentList())
            }

            val usedStyles = findUsedStyles(creditsBooks.flatMap(CreditsBook::credits))

            // The styling may be updated depending on the credits spreadsheet, namely in the following cases:
            //   - If the sheet refers to a new picture/tape style, automatically add it.
            //   - If the sheet no longer references an automatically added picture/tape style, remove it.
            //   - If a page style has legacy settings which were not used to produce a migration message, clear them.
            addRemovePopupStyles(styling.pictureStyles, usedStyles)?.let { styling = styling.copy(pictureStyles = it) }
            addRemovePopupStyles(styling.tapeStyles, usedStyles)?.let { styling = styling.copy(tapeStyles = it) }
            clearLegacyPageStyleSettings(styling.pageStyles, log)?.let { styling = styling.copy(pageStyles = it) }

            // If any of the above are the case, or if auxiliary references have been updated further up this method,
            // put an updated styling into the history in place of the old styling, but only if the styling didn't
            // change again under our feet. In that case, the next run of this task will apply our updates.
            if (styling !== origStyling)
                SwingUtilities.invokeLater {
                    if (stylingHistory.current === origStyling)
                        stylingHistory.replaceCurrent(styling)
                }

            // Each picture and tape that's referenced in the credits must be loaded during drawing, e.g. because we
            // need to know the width and height. However, if we lazily loaded those files only once encountering them,
            // we'd load them sequentially. This can take a very long time if many such files are used. To avoid this,
            // we now trigger the loading of all files in parallel background threads.
            for (style in usedStyles)
                when (style) {
                    is PictureStyle -> style.picture.loader?.loadInBackground()
                    is TapeStyle -> style.tape.tape?.loadMetadataInBackground()
                    is PageStyle, is ContentStyle, is LetterStyle, is TransitionStyle -> {}
                }

            // Draw pages and video for each credits spreadsheet.
            val crushingStyles = HashMap<Style, MutableList<CreditsId>>()
            val drawnCreditsBooks = creditsBooks.map { creditsBook ->
                val drawnCredits = creditsBook.credits.map { curCredits ->
                    val (drawnPages, crushedRuntimeGroups) = drawPages(styling, curCredits)

                    // For each crushed runtime group:
                    //   - If the group is defined in a sheet, either emit a log message,
                    //   - If the group stems from a style, record it so that we can show a warning in the styling UI.
                    for (crushedRuntimeGroup in crushedRuntimeGroups)
                        when (val source = crushedRuntimeGroup?.let(runtimeGroupSources::get)) {
                            is RuntimeGroupSource.Style, null ->
                                crushingStyles.computeIfAbsent(source?.style ?: styling.global) { mutableListOf() } +=
                                    CreditsId(creditsBook.fileName, curCredits.spreadsheetName)
                            is RuntimeGroupSource.Sheet ->
                                log += ParserMsg(
                                    creditsBook.fileName, curCredits.spreadsheetName, source.recordNo, source.colHeader,
                                    source.cellValue, WARN, l10n("ui.log.crushedVGaps")
                                )
                        }

                    // Limit each page's height to prevent the program from crashing due to misconfiguration.
                    if (drawnPages.any { it.defImage.height.resolve() > 1_000_000.0 }) {
                        val error = ParserMsg(
                            creditsBook.fileName, curCredits.spreadsheetName, null, null, null, ERROR,
                            l10n("ui.edit.excessivePageSizeError")
                        )
                        return@submit doneProcessing(input, log + error, null, emptyList())
                    }

                    val video = drawVideo(styling, drawnPages)
                    DrawnCredits(curCredits, drawnPages.toPersistentList(), video)
                }
                DrawnCreditsBook(creditsBook, drawnCredits.toPersistentList())
            }

            // For each style that crushes a runtime group, show a warning in the styling UI.
            val extraConstraintViolations = buildList {
                for ((style, creditsIds) in crushingStyles) {
                    val sett = if (style is Global) Global::runtimeFrames.st() else PageStyle::scrollRuntimeFrames.st()
                    val sheets = l10nEnumQuoted(creditsIds.map { id -> "${id.fileName} \u2192 ${id.spreadsheetName}" })
                    val msg = l10n("ui.styling.page.crushedVGaps", sheets)
                    add(ConstraintViolation(style, style, sett, 0, WARN, msg))
                }
            }

            val project = Project(styling, creditsBooks.toPersistentList())
            val drawnProject = DrawnProject(project, drawnCreditsBooks.toPersistentList())

            doneProcessing(input, log, drawnProject, extraConstraintViolations)
        }
    }

    private inline fun <S : Style, R : Any, A : Any> updateAuxiliaryReferences(
        styles: List<S>,
        refSetting: DirectStyleSetting<S, R>,
        ref2name: (R) -> String,
        ref2aux: (R) -> A?,
        name2ref: (String) -> R,
        aux2ref: (A) -> R,
        name2aux: (String) -> A?,
    ): PersistentList<S>? {
        var updatedStyles: MutableList<S>? = null
        for ((idx, style) in styles.withIndex()) {
            val ref = refSetting.get(style)
            val newAux = name2aux(ref2name(ref))
            if (newAux !== ref2aux(ref)) {
                val updatedRef = if (newAux != null) aux2ref(newAux) else name2ref(ref2name(ref))
                if (updatedStyles == null)
                    updatedStyles = styles.toMutableList()
                updatedStyles[idx] = style.copy(refSetting.notarize(updatedRef))
            }
        }
        return updatedStyles?.toPersistentList()
    }

    private inline fun <reified S : PopupStyle> addRemovePopupStyles(
        styles: List<S>, usedStyles: Set<ListedStyle>
    ): PersistentList<S>? {
        var updatedStyles: MutableList<S>? = null

        // Remove automatically added styles that aren't used anymore.
        for (idx in styles.lastIndex downTo 0) {
            val style = styles[idx]
            if (style.volatile && style !in usedStyles) {
                if (updatedStyles == null)
                    updatedStyles = styles.toMutableList()
                updatedStyles.removeAt(idx)
            }
        }

        // Automatically add styles that have been generated by the credits reader.
        for (usedStyle in usedStyles)
            if (usedStyle is S && styles.none { it === usedStyle }) {
                if (updatedStyles == null)
                    updatedStyles = styles.toMutableList()
                updatedStyles.add(usedStyle)
            }

        return updatedStyles?.toPersistentList()
    }

    private fun clearLegacyPageStyleSettings(
        styles: List<PageStyle>, log: List<ParserMsg>
    ): PersistentList<PageStyle>? {
        fun <SUBJ : Any> clearSetting(style: PageStyle, setting: DirectStyleSetting<PageStyle, SUBJ>): PageStyle =
            style.copy(setting.notarize(setting.get(PRESET_PAGE_STYLE)))

        val legacySettings = arrayOf(PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st())
        val usedLegacySettings = log.mapNotNullTo(HashSet(), ParserMsg::migrationDataSource)

        var clearedStyles: MutableList<PageStyle>? = null
        for ((idx, style) in styles.withIndex()) {
            var clearedStyle = style
            for (st in legacySettings)
                if (st.get(style) != st.get(PRESET_PAGE_STYLE) && MigrationDataSource(style, st) !in usedLegacySettings)
                    clearedStyle = clearSetting(clearedStyle, st)
            if (clearedStyle !== style) {
                if (clearedStyles == null)
                    clearedStyles = styles.toMutableList()
                clearedStyles[idx] = clearedStyle
            }
        }
        return clearedStyles?.toPersistentList()
    }

    private fun doneProcessing(
        input: Input,
        processingLog: List<ParserMsg>,
        drawnProject: DrawnProject?,
        extraConstraintViolations: List<ConstraintViolation>
    ) {
        SwingUtilities.invokeLater {
            val logCmp = compareByDescending(ParserMsg::severity)
                .thenComparingInt { msg -> input.creditsWorkbooks.indexOfFirst { it.fileName == msg.fileName } }
                .thenComparingInt { msg ->
                    (input.creditsWorkbooks.find { it.fileName == msg.fileName } ?: return@thenComparingInt 0)
                        .spreadsheets.indexOfFirst { it.name == msg.spreadsheetName }
                }
                .thenBy(ParserMsg::spreadsheetName)
                .thenBy(ParserMsg::recordNo)
            val log = (input.ioLog + processingLog).sortedWith(logCmp)
            previewCtrl.updateLog(log)
            logCtrl.updateLog(log)
            if (drawnProject != null) {
                toolbarCtrl.updateProject(drawnProject)
                previewCtrl.updateProject(drawnProject)
                stylingDockable.updateProject(drawnProject, extraConstraintViolations)
                playbackCtrl.updateProject(drawnProject)
                deliveryCtrl.updateProject(drawnProject)
            }
        }
    }

    fun isCreditsFile(file: Path): Boolean {
        val uri = file.toUri()
        return currentInput.get().creditsWorkbooks.any { it.uri == uri }
    }

    fun tryCloseProject(force: Boolean = false): Boolean {
        if (!toolbarCtrl.onTryCloseProject(force) || !deliveryCtrl.onTryCloseProject(force))
            return false

        playbackCtrl.closeProject()
        // The listeners might still be null if this method is called during initialization.
        windowLayoutsListener?.let(WINDOW_LAYOUTS_PREFERENCE::removeListener)
        overlaysListener?.let(OVERLAYS_PREFERENCE::removeListener)
        deliveryDestTemplatesListener?.let(DELIVERY_DEST_TEMPLATES_PREFERENCE::removeListener)

        onClose()
        projectFrame.dispose()
        projectIntake.close()

        return true
    }

    var windowLayoutTrees: List<DockingFrame.Tree>
        get() = projectFrame.getTrees()
        set(trees) {
            projectFrame.setTrees(trees)
            onChangeWindowLayout()
        }

    fun setDockableCollapsed(dockableId: DockableId, collapsed: Boolean) {
        projectFrame.setCollapsed(dockableId.name, collapsed)
        toolbarCtrl.setDockableCollapsed(dockableId, collapsed)
        when (dockableId) {
            DockableId.TOOLBAR, DockableId.PREVIEW, DockableId.LOG, DockableId.STYLING -> {}
            DockableId.PLAYBACK -> playbackCtrl.onShowOrHide(!collapsed)
            DockableId.DELIVERY -> deliveryCtrl.onShowOrHide(!collapsed)
        }
    }

    private fun toggleDockableCollapsed(dockableId: DockableId) =
        setDockableCollapsed(dockableId, !projectFrame.isCollapsed(dockableId.name))

    private fun onChangeWindowLayout() {
        for (dockableId in DockableId.entries)
            setDockableCollapsed(dockableId, projectFrame.isCollapsed(dockableId.name))
    }

    fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        // Note that we cannot use getWindowAncestor() here as that would yield projectFrame if event.component already
        // is a dialog, e.g., videoDialog.
        var window = SwingUtilities.getRoot(event.component)
        // If the event happened in a popup, check the parent window which owns the popup. We need this to properly
        // process key events even inside the color picker popup.
        while (window is Window && window.type == Window.Type.POPUP)
            window = window.owner
        if (window !is Window || !projectFrame.isDockingWindow(window) && !playbackCtrl.isFullScreenWindow(window))
            return false
        if (!projectFrame.isCollapsed(DockableId.STYLING.name) && stylingDockable.onKeyEvent(event))
            return true
        when (Shortcut.match(event)) {
            ESCAPE -> if (!playbackCtrl.setFullScreen(false)) return false
            CLOSE_WINDOW -> window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
            QUIT -> tryCloseProject()

            TOOLBAR_POLL_CREDITS -> toolbarCtrl.pollCredits()
            TOOLBAR_OPEN_CREDITS -> toolbarCtrl.openCredits()
            TOOLBAR_BROWSE_PROJECT_DIR -> toolbarCtrl.browseProjectDir()
            TOOLBAR_UNDO_STYLING -> toolbarCtrl.undoStyling()
            TOOLBAR_REDO_STYLING -> toolbarCtrl.redoStyling()
            TOOLBAR_SAVE_STYLING -> toolbarCtrl.saveStyling()
            TOOLBAR_RESET_STYLING -> toolbarCtrl.resetStyling()
            TOOLBAR_ZOOM_IN -> toolbarCtrl.zoom(zoomIn = true)
            TOOLBAR_ZOOM_OUT -> toolbarCtrl.zoom(zoomIn = false)
            TOOLBAR_ZOOM_RESET -> toolbarCtrl.setZoom(1.0)
            TOOLBAR_TOGGLE_GUIDES -> toolbarCtrl.toggleGuides()
            TOOLBAR_OVERLAY_MENU -> toolbarCtrl.toggleOverlaysMenu()
            TOOLBAR_TOGGLE_STYLING_DOCKABLE -> toggleDockableCollapsed(DockableId.STYLING)
            TOOLBAR_TOGGLE_PLAYBACK_DOCKABLE -> toggleDockableCollapsed(DockableId.PLAYBACK)
            TOOLBAR_TOGGLE_DELIVERY_DOCKABLE -> toggleDockableCollapsed(DockableId.DELIVERY)
            TOOLBAR_HOME -> toolbarCtrl.showWelcomeFrame()

            PREVIEW_SWITCH_CREDITS_BOOK_TAB_LEFT -> previewCtrl.switchCreditsBookTab(false)
            PREVIEW_SWITCH_CREDITS_BOOK_TAB_RIGHT -> previewCtrl.switchCreditsBookTab(true)
            PREVIEW_SWITCH_CREDITS_TAB_LEFT -> previewCtrl.switchCreditsTab(false)
            PREVIEW_SWITCH_CREDITS_TAB_RIGHT -> previewCtrl.switchCreditsTab(true)
            PREVIEW_SWITCH_PAGE_TAB_LEFT -> previewCtrl.switchPageTab(false)
            PREVIEW_SWITCH_PAGE_TAB_RIGHT -> previewCtrl.switchPageTab(true)

            PLAYBACK_REWIND -> playbackCtrl.rewind()
            PLAYBACK_PAUSE -> playbackCtrl.pause()
            PLAYBACK_PLAY -> playbackCtrl.play()
            PLAYBACK_TOGGLE_PLAY -> playbackCtrl.togglePlay()
            PLAYBACK_ONE_FRAME_BACK -> playbackCtrl.scrubRelativeFrames(-1)
            PLAYBACK_ONE_FRAME_FORWARD -> playbackCtrl.scrubRelativeFrames(1)
            PLAYBACK_JUMP_TO_START -> playbackCtrl.scrub(0)
            PLAYBACK_JUMP_TO_END -> playbackCtrl.scrub(Int.MAX_VALUE)
            PLAYBACK_TOGGLE_FULL_SCREEN -> playbackCtrl.toggleFullScreen()
            PLAYBACK_ONE_SECOND_BACK -> playbackCtrl.scrubRelativeSeconds(-1)
            PLAYBACK_ONE_SECOND_FORWARD -> playbackCtrl.scrubRelativeSeconds(1)
            PLAYBACK_TOGGLE_DECK_LINK_CONNECTED -> playbackCtrl.toggleDeckLinkConnected()
            PLAYBACK_TOGGLE_ACTUAL_SIZE -> playbackCtrl.toggleActualSize()

            DELIVERY_ADD_RENDER_JOB -> deliveryCtrl.onClickAddRenderJobToQueue()

            STYLING_ADD_PAGE_STYLE, STYLING_ADD_CONTENT_STYLE, STYLING_ADD_LETTER_STYLE, STYLING_ADD_TRANSITION_STYLE,
            STYLING_ADD_PICTURE_STYLE, STYLING_ADD_TAPE_STYLE, STYLING_DUPLICATE_STYLE, STYLING_REMOVE_STYLE,
            HIDDEN_SHOW_LOG, null -> return false
        }
        return true
    }

    fun pollCredits() {
        projectIntake.pollCredits()
    }


    inner class StylingHistory(private var saved: Styling) {

        private val history = mutableListOf(saved)
        private var currentIdx = 0

        val current: Styling
            get() = history[currentIdx]

        private var lastEditedId: Any? = null
        private var lastEditedMillis = 0L

        init {
            toolbarCtrl.setStylingUnsaved(false)
            toolbarCtrl.setStylingUnReDoable(isUndoable = false, isRedoable = false)
            stylingDockable.setStyling(saved)
        }

        /** Returns false when the new styling is semantically equal to the current one. */
        fun editedAndRedraw(new: Styling, editedId: Any?): Boolean {
            if (!semanticallyEqual(new, current)) {
                // If the user edits the styling after having undoed some steps, those steps are now dropped.
                while (history.lastIndex != currentIdx)
                    history.removeAt(history.lastIndex)
                // If the user edits the same setting multiple times in quick succession, do not memorize a new
                // state for each edit, but instead overwrite the last state after each edit. This for example avoids
                // a new state being created for each increment of a spinner.
                val currMillis = System.currentTimeMillis()
                val rapidSucc = editedId != null && editedId == lastEditedId && currMillis - lastEditedMillis < 1000
                lastEditedId = editedId
                lastEditedMillis = currMillis
                if (rapidSucc) {
                    // Normally, if the user edits the same widget multiple times in rapid succession, we skip over
                    // the following case and overwrite the history's last state (the previous version of the rapid
                    // succession edit) by the current state. However, if the user round-tripped back to the state
                    // where he started out at the beginning of his rapid succession edits, we remove the rapid
                    // succession state entirely and terminate the rapid succession edit. We then overwrite the state
                    // from before the rapid succession edits by the current state; we do this despite them being
                    // equivalent to ensure that the current state is always reference-equivalent to the objects stored
                    // in the UI's styling tree.
                    if (history.size >= 2 && semanticallyEqual(new, history[currentIdx - 1])) {
                        history.removeAt(currentIdx--)
                        lastEditedId = null
                    }
                    history[currentIdx] = new
                } else {
                    history.add(new)
                    currentIdx++
                }
                process(currentInput.updateAndGet { it.copy(styling = new) })
                updateHistoryIndicators()
                return true
            } else
                return false
        }

        fun undoAndRedraw() {
            if (currentIdx != 0) {
                currentIdx--
                lastEditedId = null
                process(currentInput.updateAndGet { it.copy(styling = current) })
                updateHistoryIndicators()
                stylingDockable.setStyling(current)
            }
        }

        fun redoAndRedraw() {
            if (currentIdx != history.lastIndex) {/* this method is about replacing a specific object */
                currentIdx++
                lastEditedId = null
                process(currentInput.updateAndGet { it.copy(styling = current) })
                updateHistoryIndicators()
                stylingDockable.setStyling(current)
            }
        }

        fun resetAndRedraw() {
            loadAndRedraw(saved)
        }

        fun loadAndRedraw(new: Styling) {
            if (editedAndRedraw(new, null))
                stylingDockable.setStyling(new)
        }

        /** Replaces the current styling with [new], and also refreshes the UI. Doesn't trigger a redraw. */
        fun replaceCurrent(new: Styling) {
            if (current !== new) {
                history[currentIdx] = new
                currentInput.updateAndGet { it.copy(styling = new) }
                updateHistoryIndicators()
                stylingDockable.setStyling(new)
            }
        }

        fun save() {
            try {
                writeStyling(stylingFile, current)
            } catch (e: IOException) {
                JOptionPane.showMessageDialog(
                    projectFrame, arrayOf(l10n("ui.edit.cannotSaveStyling.msg"), e.message ?: e.toString()),
                    l10n("ui.edit.cannotSaveStyling.title"), JOptionPane.ERROR_MESSAGE
                )
            }
            saved = current
            lastEditedId = null  // Saving should always create a new undo state.
            toolbarCtrl.setStylingUnsaved(false)
        }

        private fun updateHistoryIndicators() {
            toolbarCtrl.setStylingUnsaved(!semanticallyEqual(current, saved))
            toolbarCtrl.setStylingUnReDoable(isUndoable = currentIdx != 0, isRedoable = currentIdx != history.lastIndex)
        }

        private fun semanticallyEqual(a: Styling, b: Styling) =
            a.equalsIgnoreStyleOrderAndIneffectiveSettings(b)

    }

}
