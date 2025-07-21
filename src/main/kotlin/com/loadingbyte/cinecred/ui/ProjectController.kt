package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.getBundledFont
import com.loadingbyte.cinecred.common.getSystemFont
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.drawer.drawVideo
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.ctrl.PlaybackCtrl
import com.loadingbyte.cinecred.ui.helper.FontFamilies
import com.loadingbyte.cinecred.ui.helper.JobSlot
import com.loadingbyte.cinecred.ui.view.playback.PlaybackDialog
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.io.path.name


/** @throws ProjectController.ProjectInitializationAbortedException */
class ProjectController(
    val masterCtrl: MasterCtrlComms,
    val projectDir: Path,
    val openOnScreen: GraphicsConfiguration,
    private val onClose: () -> Unit
) {

    class ProjectInitializationAbortedException : Exception()

    // START OF INITIALIZATION PROCEDURE

    val projectName: String = projectDir.name
    val stylingHistory: StylingHistory

    private data class Input(
        val creditsSpreadsheets: List<Spreadsheet>,
        val ioLog: List<ParserMsg>,
        val projectFonts: SortedMap<String, Font>?,
        val pictureLoaders: SortedMap<String, Picture.Loader>?,
        val tapes: SortedMap<String, Tape>?,
        val styling: Styling?
    )

    private val currentInput = AtomicReference(Input(emptyList(), emptyList(), null, null, null, null))
    private val processingJobSlot = JobSlot()
    private var processingLog = emptyList<ParserMsg>()

    // STEP 1:
    // Create and open the project UI.

    val playbackCtrl: PlaybackCtrlComms = PlaybackCtrl(this)
    val projectFrame = ProjectFrame(this)
    val stylingDialog = StylingDialog(this)
    val playbackDialog = PlaybackDialog(this, playbackCtrl)
    val deliveryDialog = DeliveryDialog(this)

    init {
        projectFrame.isVisible = true
        stylingDialog.isVisible = true

        if (PROJECT_HINT_TRACK_PENDING_PREFERENCE.get())
            makeProjectHintTrack(this).play(onPass = { PROJECT_HINT_TRACK_PENDING_PREFERENCE.set(false) })
    }

    // STEP 2:
    // Set up the project intake, which will notify us about changes to the credits spreadsheet and auxiliary files.
    // Upon construction, the intake will immediately push the project fonts, pic loaders, and tapes from this thread.
    // This in turn means that those three collections will be initialized before the constructor returns.

    private val projectIntake = ProjectIntake(projectDir, object : ProjectIntake.Callbacks {

        override fun creditsPolling(possible: Boolean) {
            SwingUtilities.invokeLater { projectFrame.panel.updateCreditsPolling(possible) }
        }

        override fun pushCreditsSpreadsheets(creditsSpreadsheets: List<Spreadsheet>, uri: URI?, log: List<ParserMsg>) {
            process(currentInput.updateAndGet { it.copy(creditsSpreadsheets = creditsSpreadsheets, ioLog = log) })
            SwingUtilities.invokeLater { projectFrame.panel.updateCreditsURI(uri) }
        }

        override fun pushCreditsSpreadsheetsLog(log: List<ParserMsg>) {
            doneProcessing(currentInput.updateAndGet { it.copy(ioLog = log) }, null, null)
        }

        override fun pushProjectFonts(projectFonts: SortedMap<String, Font>) {
            val projectFamilies = FontFamilies(projectFonts.values)
            process(currentInput.updateAndGet { it.copy(projectFonts = projectFonts) })
            SwingUtilities.invokeLater { stylingDialog.panel.updateProjectFontFamilies(projectFamilies) }
        }

        override fun pushPictureLoaders(pictureLoaders: SortedMap<String, Picture.Loader>) {
            process(currentInput.updateAndGet { it.copy(pictureLoaders = pictureLoaders) })
            SwingUtilities.invokeLater { stylingDialog.panel.updatePictureLoaders(pictureLoaders.values) }
        }

        override fun pushTapes(tapes: SortedMap<String, Tape>) {
            process(currentInput.updateAndGet { it.copy(tapes = tapes) })
            SwingUtilities.invokeLater { stylingDialog.panel.updateTapes(tapes.values) }
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
    // Now that the creation of the ProjectController can no longer fail, we can add listeners to the overlays and
    // delivery location templates preferences, and be sure that they will be removed when the project is closed again.

    private val overlaysListener = { overlays: List<ConfigurableOverlay> ->
        projectFrame.panel.availableOverlays = (Overlay.BUNDLED + overlays).sorted()
    }

    private val deliveryDestTemplatesListener = { templates: List<DeliveryDestTemplate> ->
        deliveryDialog.panel.configurationForm.updateDeliveryDestTemplates(templates)
    }

    init {
        OVERLAYS_PREFERENCE.addListener(overlaysListener)
        overlaysListener(OVERLAYS_PREFERENCE.get())
        DELIVERY_DEST_TEMPLATES_PREFERENCE.addListener(deliveryDestTemplatesListener)
        deliveryDestTemplatesListener(DELIVERY_DEST_TEMPLATES_PREFERENCE.get())
    }

    // END OF INITIALIZATION PROCEDURE

    private fun process(input: Input) {
        val (creditsSpreadsheets, _, projectFonts, pictureLoaders, tapes, origStyling) = input
        // If something hasn't been initialized yet, abort reading and drawing the credits.
        if (creditsSpreadsheets.isEmpty() ||
            projectFonts == null || pictureLoaders == null || tapes == null || origStyling == null
        )
            return doneProcessing(input, null, null)

        // Execute the reading and drawing in another thread to not block the UI thread.
        processingJobSlot.submit {
            var styling: Styling = origStyling

            // Update references to auxiliary files (fonts, pictures, and tapes) in the styling.
            updateAuxiliaryReferences(
                styling.letterStyles, LetterStyle::font.st(),
                FontRef::name, FontRef::font, ::FontRef, ::FontRef,
                { name -> projectFonts[name] ?: getBundledFont(name) ?: getSystemFont(name) }
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
                val error = ParserMsg(null, null, null, null, ERROR, l10n("ui.edit.stylingError"))
                return@submit doneProcessing(input, listOf(error), null)
            }

            // Parse each credits spreadsheet.
            val credits = mutableListOf<Credits>()  // retains insertion order
            val log = mutableListOf<ParserMsg>()
            for (spreadsheet in creditsSpreadsheets) {
                val (curCredits, curLog) = readCredits(spreadsheet, styling, pictureLoaders, tapes)
                credits += curCredits
                log += curLog
            }

            // If the credits are erroneous, abort.
            if (log.any { it.severity == ERROR })
                return@submit doneProcessing(input, log, null)

            val usedStyles = findUsedStyles(credits)

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
            val project = Project(styling, credits.toPersistentList())
            val drawnCredits = credits.map { curCredits ->
                val drawnPages = drawPages(project, curCredits)

                // Limit each page's height to prevent the program from crashing due to misconfiguration.
                if (drawnPages.any { it.defImage.height.resolve() > 1_000_000.0 }) {
                    val sName = curCredits.spreadsheetName
                    val error = ParserMsg(sName, null, null, null, ERROR, l10n("ui.edit.excessivePageSizeError"))
                    return@submit doneProcessing(input, log + error, null)
                }

                val video = drawVideo(project, drawnPages)
                DrawnCredits(curCredits, drawnPages.toPersistentList(), video)
            }

            val drawnProject = DrawnProject(project, drawnCredits.toPersistentList())
            doneProcessing(input, log, drawnProject)
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

    private fun doneProcessing(input: Input, processingLog: List<ParserMsg>?, drawnProject: DrawnProject?) {
        SwingUtilities.invokeLater {
            val logCmp = compareByDescending(ParserMsg::severity)
                .thenComparingInt { msg -> input.creditsSpreadsheets.indexOfFirst { it.name == msg.spreadsheetName } }
                .thenBy(ParserMsg::spreadsheetName)
                .thenBy(ParserMsg::recordNo)
            if (processingLog == null)
                projectFrame.panel.updateLog((input.ioLog + this.processingLog).sortedWith(logCmp))
            else {
                this.processingLog = processingLog
                projectFrame.panel.updateLog((input.ioLog + processingLog).sortedWith(logCmp))
                if (drawnProject != null) {
                    projectFrame.panel.updateProject(drawnProject)
                    stylingDialog.panel.updateProject(drawnProject)
                    playbackCtrl.updateProject(drawnProject)
                    deliveryDialog.panel.configurationForm.updateProject(drawnProject)
                }
            }
        }
    }

    fun tryCloseProject(force: Boolean = false): Boolean {
        if (!projectFrame.panel.onTryCloseProject(force) ||
            !deliveryDialog.panel.renderQueuePanel.onTryCloseProject(force)
        )
            return false

        playbackCtrl.closeProject()
        // The listeners might still be null if this method is called during initialization.
        overlaysListener?.let(OVERLAYS_PREFERENCE::removeListener)
        deliveryDestTemplatesListener?.let(DELIVERY_DEST_TEMPLATES_PREFERENCE::removeListener)

        onClose()

        projectFrame.dispose()
        for (type in ProjectDialogType.entries)
            getDialog(type).dispose()

        projectIntake.close()

        return true
    }

    private fun getDialog(type: ProjectDialogType) = when (type) {
        ProjectDialogType.STYLING -> stylingDialog
        ProjectDialogType.VIDEO -> playbackDialog
        ProjectDialogType.DELIVERY -> deliveryDialog
    }

    fun setDialogVisible(type: ProjectDialogType, isVisible: Boolean) {
        getDialog(type).isVisible = isVisible
        projectFrame.panel.onSetDialogVisible(type, isVisible)
        if (type == ProjectDialogType.VIDEO)
            playbackCtrl.setDialogVisibility(isVisible)
        if (type == ProjectDialogType.DELIVERY && isVisible)
            projectFrame.panel.selectedSpreadsheetName
                ?.let(deliveryDialog.panel.configurationForm::setSelectedSpreadsheetName)
    }

    fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        // Note that we cannot use getWindowAncestor() here as that would yield projectFrame if event.component already
        // is a dialog, e.g., videoDialog.
        var window = SwingUtilities.getRoot(event.component)
        // If the event happened in a popup, check the parent window which owns the popup. We need this to properly
        // process key events even inside the color picker popup.
        while (window is Window && window.type == Window.Type.POPUP)
            window = window.owner
        if (window != projectFrame && window != stylingDialog && window != playbackDialog && window != deliveryDialog)
            return false
        if (projectFrame.panel.onKeyEvent(event) || stylingDialog.isVisible && stylingDialog.panel.onKeyEvent(event))
            return true
        when (event.modifiersEx) {
            0 -> when (event.keyCode) {
                VK_J -> playbackCtrl.rewind()
                VK_K -> playbackCtrl.pause()
                VK_L -> playbackCtrl.play()
                VK_SPACE -> playbackCtrl.togglePlay()
                VK_LEFT, VK_KP_LEFT -> playbackCtrl.scrubRelativeFrames(-1)
                VK_RIGHT, VK_KP_RIGHT -> playbackCtrl.scrubRelativeFrames(1)
                VK_HOME -> playbackCtrl.scrub(0)
                VK_END -> playbackCtrl.scrub(Int.MAX_VALUE)
                VK_F11 -> playbackCtrl.toggleFullScreen()
                VK_ESCAPE -> playbackCtrl.setFullScreen(false)
                else -> return false
            }
            SHIFT_DOWN_MASK -> when (event.keyCode) {
                // Don't listen for the regular left/right keys here as those are reserved for text fields.
                VK_KP_LEFT -> playbackCtrl.scrubRelativeSeconds(-1)
                VK_KP_RIGHT -> playbackCtrl.scrubRelativeSeconds(1)
                else -> return false
            }
            CTRL_DOWN_MASK -> when (event.keyCode) {
                VK_Q -> tryCloseProject()
                VK_W -> if (window == projectFrame) tryCloseProject() else
                    setDialogVisible(ProjectDialogType.entries.first { getDialog(it) == window }, false)
                VK_B if deliveryDialog.isVisible -> deliveryDialog.panel.configurationForm.addRenderJobToQueue()
                VK_L -> playbackCtrl.toggleDeckLinkConnected()
                VK_1 -> playbackCtrl.toggleActualSize()
                else -> return false
            }
            else -> return false
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
            projectFrame.panel.onStylingChange(isUnsaved = false, isUndoable = false, isRedoable = false)
            stylingDialog.panel.setStyling(saved)
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
                stylingDialog.panel.setStyling(current)
            }
        }

        fun redoAndRedraw() {
            if (currentIdx != history.lastIndex) {/* this method is about replacing a specific object */
                currentIdx++
                lastEditedId = null
                process(currentInput.updateAndGet { it.copy(styling = current) })
                updateHistoryIndicators()
                stylingDialog.panel.setStyling(current)
            }
        }

        fun resetAndRedraw() {
            loadAndRedraw(saved)
        }

        fun loadAndRedraw(new: Styling) {
            if (editedAndRedraw(new, null))
                stylingDialog.panel.setStyling(new)
        }

        /** Replaces the current styling with [new], and also refreshes the UI. Doesn't trigger a redraw. */
        fun replaceCurrent(new: Styling) {
            if (current !== new) {
                history[currentIdx] = new
                currentInput.updateAndGet { it.copy(styling = new) }
                updateHistoryIndicators()
                stylingDialog.panel.setStyling(new)
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
            projectFrame.panel.onStylingSave()
        }

        private fun updateHistoryIndicators() {
            projectFrame.panel.onStylingChange(
                isUnsaved = !semanticallyEqual(current, saved),
                isUndoable = currentIdx != 0,
                isRedoable = currentIdx != history.lastIndex
            )
        }

        private fun semanticallyEqual(a: Styling, b: Styling) =
            a.equalsIgnoreStyleOrderAndIneffectiveSettings(b)

    }

}
