package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.drawPages
import com.loadingbyte.cinecred.drawer.drawVideo
import com.loadingbyte.cinecred.drawer.getBundledFont
import com.loadingbyte.cinecred.drawer.getSystemFont
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.projectio.*
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.helper.FontFamilies
import com.loadingbyte.cinecred.ui.helper.JobSlot
import kotlinx.collections.immutable.toPersistentList
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.KeyEvent
import java.io.IOException
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

    val stylingCtx: StylingContext get() = currentInput.get().stylingCtx!!
    val stylingHistory: StylingHistory

    private data class Input(
        val creditsSpreadsheets: List<Spreadsheet>,
        val ioLog: List<ParserMsg>,
        val stylingCtx: StylingContext?,
        val pictureLoaders: Collection<PictureLoader>?,
        val tapes: Collection<Tape>?,
        val styling: Styling?
    )

    private val currentInput = AtomicReference(Input(emptyList(), emptyList(), null, null, null, null))
    private val processingJobSlot = JobSlot()
    private var processingLog = emptyList<ParserMsg>()

    // STEP 1:
    // Create and open the project UI.

    val projectFrame = ProjectFrame(this)
    val stylingDialog = StylingDialog(this)
    val videoDialog = VideoDialog(this)
    val deliveryDialog = DeliveryDialog(this)

    init {
        projectFrame.isVisible = true
        stylingDialog.isVisible = true

        if (PROJECT_HINT_TRACK_PENDING_PREFERENCE.get())
            makeProjectHintTrack(this).play(onPass = { PROJECT_HINT_TRACK_PENDING_PREFERENCE.set(false) })
    }

    // STEP 2:
    // Set up the project intake, which will notify us about changes to the credits spreadsheet and auxiliary files.
    // Upon construction, the intake will immediately push the project fonts and picture loaders from this thread.
    // This in turn means that the styling context will be initialized before the constructor returns.

    private val projectIntake = ProjectIntake(projectDir, object : ProjectIntake.Callbacks {

        override fun creditsPolling(possible: Boolean) {
            SwingUtilities.invokeLater { projectFrame.panel.updateCreditsPolling(possible) }
        }

        override fun pushCreditsSpreadsheets(creditsSpreadsheets: List<Spreadsheet>, log: List<ParserMsg>) {
            process(currentInput.updateAndGet { it.copy(creditsSpreadsheets = creditsSpreadsheets, ioLog = log) })
        }

        override fun pushProjectFonts(projectFonts: Collection<Font>) {
            val projectFontsByName = projectFonts.associateBy { font -> font.getFontName(Locale.ROOT) }
            val projectFamilies = FontFamilies(projectFonts)
            process(currentInput.updateAndGet { it.copy(stylingCtx = StylingContextImpl(projectFontsByName)) })
            SwingUtilities.invokeLater { stylingDialog.panel.updateProjectFontFamilies(projectFamilies) }
        }

        override fun pushPictureLoaders(pictureLoaders: Collection<PictureLoader>) {
            process(currentInput.updateAndGet { it.copy(pictureLoaders = pictureLoaders) })
        }

        override fun pushTapes(tapes: Collection<Tape>) {
            process(currentInput.updateAndGet { it.copy(tapes = tapes) })
        }

    })

    // STEP 3:
    // Load the initial state of the styling from disk. Then render the credits if the credits spreadsheet file has
    // already arrived; otherwise, its arrival later on will trigger the render.

    private val stylingFile = projectDir.resolve(STYLING_FILE_NAME)

    init {
        val styling = try {
            readStyling(stylingFile, stylingCtx)
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(
                projectFrame, arrayOf(l10n("ui.edit.cannotLoadStyling.msg"), e.toString()),
                l10n("ui.edit.cannotLoadStyling.title"), JOptionPane.ERROR_MESSAGE
            )
            tryCloseProject(force = true)
            throw ProjectInitializationAbortedException()
        }
        stylingHistory = StylingHistory(styling)
        process(currentInput.updateAndGet { it.copy(styling = styling) })
    }

    // STEP 4:
    // Now that the creation of the ProjectController can no longer fail, we can add a listener to the overlays
    // preference and be sure that it will be removed when the project is closed again.

    private val overlaysListener = { overlays: List<ConfigurableOverlay> ->
        projectFrame.panel.availableOverlays = (Overlay.BUNDLED + overlays).sorted()
    }

    init {
        OVERLAYS_PREFERENCE.addListener(overlaysListener)
        overlaysListener(OVERLAYS_PREFERENCE.get())
    }

    // END OF INITIALIZATION PROCEDURE

    private fun process(input: Input) {
        val (creditsSpreadsheets, _, stylingCtx, pictureLoaders, tapes, styling) = input
        // If something hasn't been initialized yet, abort reading and drawing the credits.
        if (creditsSpreadsheets.isEmpty() || stylingCtx == null ||
            pictureLoaders == null || tapes == null || styling == null
        )
            return doneProcessing(input, null, null)

        // Execute the reading and drawing in another thread to not block the UI thread.
        processingJobSlot.submit {
            // Parse each credits spreadsheet.
            val credits = mutableListOf<Credits>()  // retains insertion order
            val log = mutableListOf<ParserMsg>()
            for (spreadsheet in creditsSpreadsheets) {
                val (curCredits, curLog) = readCredits(spreadsheet, styling, pictureLoaders, tapes)
                credits += curCredits
                log += curLog
            }

            val project = Project(styling, stylingCtx, credits.toPersistentList())

            // Verify the styling in the extra thread because that is not entirely cheap.
            // If the styling is erroneous, abort and notify the UI about the error.
            if (verifyConstraints(stylingCtx, styling).any { it.severity == ERROR }) {
                val error = ParserMsg(null, null, null, null, ERROR, l10n("ui.edit.stylingError"))
                return@submit doneProcessing(input, log + error, null)
            }

            // If some page styles contains legacy settings which have not been used to produce a migration message,
            // clear them and put the cleared styling into the history in place of the legacy styling.
            clearLegacyPageStyleSettings(styling, log)?.let {
                SwingUtilities.invokeLater { stylingHistory.replaceInHistory(styling, it) }
            }

            // Draw pages and video for each credits spreadsheet.
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

    private fun clearLegacyPageStyleSettings(styling: Styling, log: List<ParserMsg>): Styling? {
        fun <SUBJ : Any> clearSetting(style: PageStyle, setting: DirectStyleSetting<PageStyle, SUBJ>): PageStyle =
            style.copy(setting.notarize(setting.get(PRESET_PAGE_STYLE)))

        val legacySettings = arrayOf(PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st())
        val usedLegacySettings = log.mapNotNullTo(HashSet(), ParserMsg::migrationDataSource)

        var clearedStyles: MutableList<PageStyle>? = null
        for ((idx, style) in styling.pageStyles.withIndex()) {
            var clearedStyle = style
            for (st in legacySettings)
                if (st.get(style) != st.get(PRESET_PAGE_STYLE) && MigrationDataSource(style, st) !in usedLegacySettings)
                    clearedStyle = clearSetting(clearedStyle, st)
            if (clearedStyle !== style) {
                if (clearedStyles == null)
                    clearedStyles = styling.pageStyles.toMutableList()
                clearedStyles[idx] = clearedStyle
            }
        }
        return clearedStyles?.let { styling.copy(pageStyles = it.toPersistentList()) }
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
                    videoDialog.panel.updateProject(drawnProject)
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

        OVERLAYS_PREFERENCE.removeListener(overlaysListener)

        onClose()

        projectFrame.dispose()
        for (type in ProjectDialogType.entries)
            getDialog(type).dispose()

        projectIntake.close()

        return true
    }

    private fun getDialog(type: ProjectDialogType) = when (type) {
        ProjectDialogType.STYLING -> stylingDialog
        ProjectDialogType.VIDEO -> videoDialog
        ProjectDialogType.DELIVERY -> deliveryDialog
    }

    fun setDialogVisible(type: ProjectDialogType, isVisible: Boolean) {
        getDialog(type).isVisible = isVisible
        projectFrame.panel.onSetDialogVisible(type, isVisible)
        if (type == ProjectDialogType.VIDEO && !isVisible)
            videoDialog.panel.onHide()
    }

    fun onGlobalKeyEvent(event: KeyEvent): Boolean {
        // Note that we cannot use getWindowAncestor() here as that would yield projectFrame if event.component already
        // is a dialog, e.g., videoDialog.
        var window = SwingUtilities.getRoot(event.component)
        // If the event happened in a popup, check the parent window which owns the popup. We need this to properly
        // process key events even inside the color picker popup.
        while (window is Window && window.type == Window.Type.POPUP)
            window = window.owner
        return when {
            window == videoDialog && videoDialog.panel.onKeyEvent(event) -> true
            (window == projectFrame || window == stylingDialog || window == videoDialog) &&
                    projectFrame.panel.onKeyEvent(event) -> true
            (window == projectFrame || window == stylingDialog || window == videoDialog) &&
                    stylingDialog.isVisible && stylingDialog.panel.onKeyEvent(event) -> true
            else -> false
        }
    }

    fun pollCredits() {
        projectIntake.pollCredits()
    }


    private class StylingContextImpl(private val fontsByName: Map<String, Font>) : StylingContext {
        override fun resolveFont(name: String): Font? =
            fontsByName[name] ?: getBundledFont(name) ?: getSystemFont(name)
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

        /** Replaces the exact object that is [old] with [new], and also refreshes the UI if necessary. */
        fun replaceInHistory(old: Styling, new: Styling) {
            // Look from the back of the history because that's where we're likely replacing things.
            val idx = history.indexOfLast { it === old /* === because we replace a specific object */ }
            if (idx != -1) {
                history[idx] = new
                if (idx == currentIdx) {
                    currentInput.updateAndGet { it.copy(styling = new) }
                    updateHistoryIndicators()
                    stylingDialog.panel.setStyling(new)
                }
            }
        }

        fun save() {
            try {
                writeStyling(stylingFile, stylingCtx, current)
            } catch (e: IOException) {
                JOptionPane.showMessageDialog(
                    projectFrame, arrayOf(l10n("ui.edit.cannotSaveStyling.msg"), e.toString()),
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
            a.equalsIgnoreStyleOrderAndIneffectiveSettings(stylingCtx, b)

    }

}
