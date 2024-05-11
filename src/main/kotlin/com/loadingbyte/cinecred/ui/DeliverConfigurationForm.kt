package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.isAccessibleDirectory
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.YUV
import com.loadingbyte.cinecred.imaging.Bitmap.Scan
import com.loadingbyte.cinecred.project.DrawnCredits
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.ui.helper.*
import java.nio.file.Path
import java.text.DecimalFormat
import javax.swing.JOptionPane.*
import javax.swing.ListCellRenderer
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt


class DeliverConfigurationForm(private val ctrl: ProjectController) :
    EasyForm(insets = false, noticeArea = true, constLabelWidth = false) {

    companion object {
        private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.Format.ALL + WholePagePDFRenderJob.FORMAT
        private val ALL_FORMATS = WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL + TapeTimelineRenderJob.Format.ALL
    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedFormatWidget get() = formatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedTransparentGroundingWidget get() = transparentGroundingWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedResolutionMultWidget get() = resolutionMultWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedScanWidget get() = scanWidget
    // =========================================

    private var drawnProject: DrawnProject? = null

    private val spreadsheetNameWidget = addWidget(
        l10n("ui.deliverConfig.spreadsheet"),
        OptionalComboBoxWidget(String::class.java, emptyList(), widthSpec = WidthSpec.WIDER)
    )

    private val formatWidget = addWidget(
        l10n("ui.deliverConfig.format"),
        ComboBoxWidget(
            RenderFormat::class.java, ALL_FORMATS, widthSpec = WidthSpec.WIDER, scrollbar = false,
            toString = { format ->
                val prefix = when (format) {
                    in WHOLE_PAGE_FORMATS -> l10n("ui.deliverConfig.wholePageFormat")
                    is VideoRenderJob.Format -> l10n("ui.deliverConfig.videoFormat")
                    is TapeTimelineRenderJob.Format -> l10n("ui.deliverConfig.timelineFormat")
                    else -> throw IllegalArgumentException()
                }
                val suffix = format.notice.let { if (it == null) "" else "  \u2013  $it" }
                "$prefix  \u2013  ${format.label}$suffix"
            },
            // User a custom render that shows category headers.
            rendererDecorator = object : AbstractComboBoxWidget.RendererDecorator {
                override fun <I> decorate(renderer: ListCellRenderer<I>) = LabeledListCellRenderer(renderer) { index ->
                    when (index) {
                        0 -> listOf(l10n("ui.deliverConfig.wholePageFormat"))
                        WHOLE_PAGE_FORMATS.size -> listOf(l10n("ui.deliverConfig.videoFormat"))
                        WHOLE_PAGE_FORMATS.size + VideoRenderJob.Format.ALL.size ->
                            listOf(l10n("ui.deliverConfig.timelineFormat"))
                        else -> emptyList()
                    }
                }
            }
        ).apply { value = VideoRenderJob.Format.ALL.first() }
    )

    private val singleFileWidget = addWidget(
        l10n("ui.deliverConfig.singleFile"),
        FileWidget(FileType.FILE, FileAction.SAVE, widthSpec = WidthSpec.WIDER),
        isVisible = { !formatWidget.value.fileSeq },
        verify = ::verifyFile
    )

    private val seqDirWidget = addWidget(
        l10n("ui.deliverConfig.seqDir"),
        FileWidget(FileType.DIRECTORY, FileAction.SAVE, widthSpec = WidthSpec.WIDER),
        isVisible = { formatWidget.value.fileSeq },
        verify = ::verifyFile
    )
    private val seqFilenameSuffixWidget = addWidget(
        l10n("ui.deliverConfig.seqFilenameSuffix"),
        FilenameWidget(widthSpec = WidthSpec.WIDER).apply { value = ".#######" },
        // Reserve space even if invisible to keep the form from changing height when selecting different formats.
        invisibleSpace = true,
        isVisible = { formatWidget.value.fileSeq },
        verify = {
            val numHoles = Regex("#+").findAll(it).count()
            if (numHoles == 0)
                Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenameSuffixMissesHole"))
            else if (numHoles > 1)
                Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenameSuffixHasTooManyHoles"))
            else null
        }
    )

    private val transparentGroundingWidget = addWidget(
        l10n("ui.deliverConfig.transparentGrounding"),
        CheckBoxWidget(),
        isEnabled = { formatWidget.value.supportsAlpha }
    )

    private val resolutionMultWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, listOf(-2, -1, 0, 1, 2), widthSpec = WidthSpec.LITTLE,
            toString = { if (it >= 0) "\u00D7 ${1 shl it}" else "\u00F7 ${1 shl -it}" }
        ).apply { value = 0 }
    private val fpsMultWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, listOf(1, 2, 3, 4), widthSpec = WidthSpec.LITTLE,
            toString = { "\u00D7 $it" }
        )

    init {
        addWidget(
            l10n("ui.styling.global.resolution"),
            UnionWidget(
                listOf(resolutionMultWidget, fpsMultWidget),
                labels = listOf(null, l10n("ui.styling.global.fps"))
            )
        )
    }

    private val scanWidget = addWidget(
        l10n("ui.deliverConfig.scan"),
        ComboBoxWidget(
            Scan::class.java, Scan.entries, widthSpec = WidthSpec.WIDER,
            toString = { scan -> l10n("ui.deliverConfig.scan.$scan") }
        ),
        isEnabled = {
            val format = formatWidget.value
            format is VideoRenderJob.Format && format.interlacing || format is TapeTimelineRenderJob.Format
        }
    )

    private val colorSpaceWidget = addWidget(
        l10n("ui.deliverConfig.colorSpace"),
        ComboBoxWidget(
            VideoRenderJob.ColorSpace::class.java, VideoRenderJob.ColorSpace.entries,
            widthSpec = WidthSpec.WIDER,
            toString = { cs ->
                when (cs) {
                    VideoRenderJob.ColorSpace.REC_709 ->
                        "Rec. 709  \u2013  BT.709 Gamut, BT.709 Gamma, Limited YCbCr Range, BT.709 YCbCr Coefficients"
                    VideoRenderJob.ColorSpace.SRGB ->
                        "sRGB / sYCC  \u2013  BT.709 Gamut, sRGB Gamma, Full YCbCr Range, BT.601 YCbCr Coefficients"
                }
            }
        ).apply { value = VideoRenderJob.ColorSpace.REC_709 },
        isEnabled = { formatWidget.value is VideoRenderJob.Format }
    )

    init {
        // Get the form into a reasonable state even before the drawn project arrives.
        onChange(spreadsheetNameWidget)
    }

    override fun onChange(widget: Widget<*>) {
        // If another spreadsheet name is selected, set the output location fields to a reasonable default.
        if (widget == spreadsheetNameWidget) {
            // We've actually had a bug report where the project dir didn't have a parent, so in that case, just put the
            // default output location inside the project dir to at least avoid a crash.
            val dir = ctrl.projectDir.toAbsolutePath().let { it.parent ?: it }
            val filename = "${ctrl.projectDir.fileName} ${spreadsheetNameWidget.value.getOrNull() ?: ""} Render"
            singleFileWidget.value = (singleFileWidget.value.parent ?: dir).resolve(filename)
            seqDirWidget.value = (seqDirWidget.value.parent ?: dir).resolve(filename)
            // This ensures that file extensions are sensible.
            onFormatChange()
        }

        // Notify the file-related fields when the format (and with it the set of admissible file extensions) changes.
        if (widget == formatWidget)
            onFormatChange()

        super.onChange(widget)

        // These isEnabled checks are separate because the multiplier widgets are part of a UnionWidget.
        resolutionMultWidget.isEnabled = formatWidget.value !is TapeTimelineRenderJob.Format
        fpsMultWidget.isEnabled = formatWidget.value !in WHOLE_PAGE_FORMATS

        // Update the specs labels and determine whether the specs are valid.
        // Then disable the add button if there are errors in the form or the specs are invalid.
        // We need the null-safe access because this method might be called before everything is initialized.
        val dialog = ctrl.deliveryDialog as DeliveryDialog?
        if (dialog != null)
            dialog.panel.addButton.isEnabled = updateAndVerifySpecs(dialog.panel) && isErrorFree
    }

    private fun verifyFile(file: Path): Notice? = when {
        file.pathString.isBlank() -> Notice(Severity.ERROR, l10n("blank"))
        !file.isAbsolute -> Notice(Severity.ERROR, l10n("ui.deliverConfig.nonAbsolutePath"))
        else -> null
    }

    private fun updateAndVerifySpecs(panel: DeliveryPanel): Boolean {
        val project = (drawnProject ?: return false).project
        val credits = (selectedDrawnCredits ?: return false).credits

        val format = formatWidget.value
        val resMult = if (resolutionMultWidget.isEnabled) 2.0.pow(resolutionMultWidget.value) else 1.0
        val fpsMult = if (fpsMultWidget.isEnabled) fpsMultWidget.value else 1
        val scan = if (scanWidget.isEnabled) scanWidget.value else Scan.PROGRESSIVE
        val cs = if (colorSpaceWidget.isEnabled) colorSpaceWidget.value else VideoRenderJob.ColorSpace.SRGB

        // Determine the scaled specs.
        val resolution = project.styling.global.resolution
        val scaledWidth = (resMult * resolution.widthPx).roundToInt()
        val scaledHeight = (resMult * resolution.heightPx).roundToInt()
        val scaledFPS = project.styling.global.fps.frac * fpsMult
        val scrollSpeeds = credits.pages.asSequence()
            .flatMap { page -> page.stages }
            .filter { stage -> stage.style.behavior == PageBehavior.SCROLL }
            .map { stage -> stage.style.scrollPxPerFrame }
            .associateWith { speed -> speed * resMult / fpsMult }

        // Display the scaled specs in the specs labels.
        val decFmt = DecimalFormat("0.##")
        panel.specsLabels[0].text =
            if (format is TapeTimelineRenderJob.Format) "\u2014" else
                "$scaledWidth \u00D7 $scaledHeight"
        panel.specsLabels[1].text =
            if (format in WHOLE_PAGE_FORMATS) "\u2014" else
                decFmt.format(scaledFPS) + if (scan == Scan.PROGRESSIVE) "p" else "i"
        panel.specsLabels[2].text =
            if (format is TapeTimelineRenderJob.Format) "\u2014" else when (cs) {
                VideoRenderJob.ColorSpace.REC_709 -> "Rec. 709"
                VideoRenderJob.ColorSpace.SRGB ->
                    if (format is VideoRenderJob.Format && format.pixelFormat.family == YUV) "sYCC" else "sRGB"
            }
        panel.specsLabels[3].text =
            if (format !is VideoRenderJob.Format || scrollSpeeds.isEmpty()) "\u2014" else {
                val speedsDesc = when (scan) {
                    Scan.PROGRESSIVE -> l10n("ui.delivery.scrollPxPerFrame")
                    else -> l10n("ui.delivery.scrollPxPerFrameAndField")
                }
                val speedsCont = scrollSpeeds.entries.joinToString("   ") { (s1, s2) ->
                    buildString {
                        if (s1 != s2)
                            append(decFmt.format(s1)).append(" \u2192 ")
                        append(decFmt.format(s2))
                        if (scan != Scan.PROGRESSIVE)
                            append("/").append(decFmt.format(s2 / 2.0))
                    }
                }
                "$speedsDesc   $speedsCont"
            }

        // Clear all previous issues and create new ones if applicable.
        panel.clearIssues()
        var err = false
        fun warn(msg: String) = panel.addIssue(WARN_ICON, msg)
        fun error(msg: String) = panel.addIssue(ERROR_ICON, msg).also { err = true }
        // Check for violated restrictions of the currently selected format.
        val forLabel = format.label
        if (format is VideoRenderJob.Format) {
            if (format.widthMod2 && scaledWidth % 2 != 0)
                error(l10n("ui.delivery.issues.widthMod2", forLabel))
            if (format.heightMod2 && scaledHeight % 2 != 0)
                error(l10n("ui.delivery.issues.heightMod2", forLabel))
            if (format.minWidth != null && scaledWidth < format.minWidth)
                error(l10n("ui.delivery.issues.minWidth", forLabel, format.minWidth))
            if (format.minHeight != null && scaledWidth < format.minHeight)
                error(l10n("ui.delivery.issues.minHeight", forLabel, format.minHeight))
        }
        // Check for fractional scroll speeds.
        if (format !in WHOLE_PAGE_FORMATS) {
            if (scrollSpeeds.values.any { s2 -> floor(s2) != s2 })
                warn(l10n("ui.delivery.issues.fractionalFrameShift"))
            if (scan != Scan.PROGRESSIVE && scrollSpeeds.values.any { s2 -> floor(s2 / 2.0) != s2 / 2.0 })
                warn(l10n("ui.delivery.issues.fractionalFieldShift"))
        }

        return !err
    }

    private fun onFormatChange() {
        val format = formatWidget.value
        val singleExt = format.fileExts.singleOrNull()
        val assName = if (singleExt != null) l10n("ui.deliverConfig.singleFileTypeExt", singleExt.uppercase()) else
            l10n("ui.deliverConfig.singleFileTypeVideo")
        val fileExtAssortment = FileExtAssortment(assName, format.fileExts.sorted(), format.defaultFileExt)
        singleFileWidget.fileExtAssortment = fileExtAssortment
        seqFilenameSuffixWidget.fileExtAssortment = fileExtAssortment
    }

    fun addRenderJobToQueue() {
        val drawnProject = this.drawnProject
        val drawnCredits = selectedDrawnCredits
        if (drawnProject == null || drawnCredits == null)
            showMessageDialog(
                ctrl.deliveryDialog, l10n("ui.deliverConfig.noPages.msg"),
                l10n("ui.deliverConfig.noPages.title"), ERROR_MESSAGE
            )
        else {
            val project = drawnProject.project
            val drawnPages = drawnCredits.drawnPages
            val video = drawnCredits.video

            val format = formatWidget.value
            val fileOrDir = (if (format.fileSeq) seqDirWidget.value else singleFileWidget.value).normalize()
            val transparentGrounding = transparentGroundingWidget.isEnabled && transparentGroundingWidget.value
            val grounding = if (transparentGrounding) null else project.styling.global.grounding
            val resolutionScaling = if (resolutionMultWidget.isEnabled) 2.0.pow(resolutionMultWidget.value) else 1.0
            val fpsScaling = if (fpsMultWidget.isEnabled) fpsMultWidget.value else 1
            val scan = if (scanWidget.isEnabled) scanWidget.value else Scan.PROGRESSIVE
            val colorSpace = if (colorSpaceWidget.isEnabled) colorSpaceWidget.value else VideoRenderJob.ColorSpace.SRGB

            fun wrongFileTypeDialog(msg: String) = showMessageDialog(
                ctrl.deliveryDialog, msg, l10n("ui.deliverConfig.wrongFileType.title"), ERROR_MESSAGE
            )

            fun overwriteDialog(msg: String) = showConfirmDialog(
                ctrl.deliveryDialog, msg, l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
            ) == OK_OPTION

            fun overwriteProjectDirDialog() = showMessageDialog(
                ctrl.deliveryDialog, l10n("ui.deliverConfig.overwriteProjectDir.msg"),
                l10n("ui.deliverConfig.overwriteProjectDir.title"), ERROR_MESSAGE
            )

            // If there is any issue with the output file or folder, inform the user and abort if necessary.
            if (format.fileSeq) {
                if (fileOrDir.exists() && !fileOrDir.isAccessibleDirectory()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.file", fileOrDir))
                    return
                } else if (fileOrDir == ctrl.projectDir) {
                    overwriteProjectDirDialog()
                    return
                } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirReused", fileOrDir)))
                        return
                } else if (fileOrDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true))
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirNonEmpty", fileOrDir)))
                        return
            } else {
                if (fileOrDir.isDirectory()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.dir", fileOrDir))
                    return
                } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.singleFileReused", fileOrDir)))
                        return
                } else if (fileOrDir.exists())
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.singleFileExists", fileOrDir)))
                        return
            }

            fun getScaledPageDefImages() = drawnPages.map { it.defImage.copy(universeScaling = resolutionScaling) }
            fun getFilenameHashPattern() = fileOrDir.name + seqFilenameSuffixWidget.value
            fun hashPatternToFormatStr(pat: String) = pat.replace(Regex("#+")) { match -> "%0${match.value.length}d" }

            val renderJob: RenderJob
            val destination: String
            when (format) {
                is WholePageSequenceRenderJob.Format -> {
                    renderJob = WholePageSequenceRenderJob(
                        getScaledPageDefImages(),
                        grounding, project.styling.global.locale, format,
                        dir = fileOrDir, filenamePattern = hashPatternToFormatStr(getFilenameHashPattern())
                    )
                    destination = fileOrDir.resolve(getFilenameHashPattern()).pathString
                }
                WholePagePDFRenderJob.FORMAT -> {
                    renderJob = WholePagePDFRenderJob(
                        getScaledPageDefImages(),
                        grounding, project.styling.global.locale,
                        file = fileOrDir
                    )
                    destination = fileOrDir.pathString
                }
                is VideoRenderJob.Format -> {
                    val fileOrPattern: Path
                    if (format.fileSeq) {
                        fileOrPattern = fileOrDir.resolve(hashPatternToFormatStr(getFilenameHashPattern()))
                        destination = fileOrDir.resolve(getFilenameHashPattern()).pathString
                    } else {
                        fileOrPattern = fileOrDir
                        destination = fileOrDir.pathString
                    }
                    renderJob = VideoRenderJob(
                        project, video, transparentGrounding, resolutionScaling, fpsScaling,
                        scan, colorSpace, format, fileOrPattern
                    )
                }
                is TapeTimelineRenderJob.Format -> {
                    renderJob = TapeTimelineRenderJob(project, video, fpsScaling, scan, format, fileOrDir)
                    destination = fileOrDir.pathString
                }
                else -> throw IllegalStateException("Internal bug: No renderer known for format '${format.label}'.")
            }

            ctrl.deliveryDialog.panel.renderQueuePanel.addRenderJobToQueue(renderJob, format.label, destination)
        }
    }

    private val selectedDrawnCredits: DrawnCredits?
        get() = spreadsheetNameWidget.value.getOrNull()?.let { selName ->
            drawnProject?.drawnCredits?.find { it.credits.spreadsheetName == selName }
        }

    fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject

        // Filter out credits which have 0 runtime, as the renderer is probably not expecting this case.
        spreadsheetNameWidget.items =
            drawnProject.drawnCredits.filter { it.video.numFrames > 0 }.map { it.credits.spreadsheetName }

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultWidget)
    }

}
