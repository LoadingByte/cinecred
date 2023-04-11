package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.imaging.VideoWriter.Scan
import com.loadingbyte.cinecred.imaging.isRGBPixelFormat
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.ui.helper.*
import java.nio.file.Path
import java.text.DecimalFormat
import javax.swing.JOptionPane.*
import kotlin.io.path.*
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt


class DeliverConfigurationForm(private val ctrl: ProjectController) :
    EasyForm(insets = false, noticeArea = true, constLabelWidth = false) {

    companion object {
        private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.Format.ALL + listOf(WholePagePDFRenderJob.FORMAT)
        private val ALL_FORMATS = (WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL)
    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedFormatWidget get() = formatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedTransparentGroundingWidget get() = transparentGroundingWidget
    // =========================================

    private var drawnProject: DrawnProject? = null

    private val formatWidget = addWidget(
        l10n("ui.deliverConfig.format"),
        ComboBoxWidget(
            RenderFormat::class.java, ALL_FORMATS, widthSpec = WidthSpec.WIDER, scrollbar = false,
            toString = { format ->
                val prefix =
                    if (format in WHOLE_PAGE_FORMATS) l10n("ui.deliverConfig.wholePageFormat")
                    else l10n("ui.deliverConfig.videoFormat")
                val suffix = format.notice.let { if (it == null) "" else "  \u2013  $it" }
                "$prefix  \u2013  ${format.label}$suffix"
            },
            // User a custom render that shows category headers.
            decorateRenderer = { baseRenderer ->
                LabeledListCellRenderer(baseRenderer) { index ->
                    when (index) {
                        0 -> listOf(l10n("ui.deliverConfig.wholePageFormat"))
                        WHOLE_PAGE_FORMATS.size -> listOf(l10n("ui.deliverConfig.videoFormat"))
                        else -> emptyList()
                    }
                }
            }
        ).apply { value = VideoRenderJob.Format.ALL.first() }
    )

    private val singleFileWidget = addWidget(
        l10n("ui.deliverConfig.singleFile"),
        FileWidget(FileType.FILE, widthSpec = WidthSpec.WIDER),
        isVisible = { !formatWidget.value.fileSeq },
        verify = ::verifyFile
    )

    private val seqDirWidget = addWidget(
        l10n("ui.deliverConfig.seqDir"),
        FileWidget(FileType.DIRECTORY, widthSpec = WidthSpec.WIDER),
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
            l10n("ui.deliverConfig.resolutionMultiplier"),
            UnionWidget(
                listOf(resolutionMultWidget, fpsMultWidget),
                labels = listOf(null, l10n("ui.deliverConfig.fpsMultiplier"))
            )
        )
    }

    private val scanWidget = addWidget(
        l10n("ui.deliverConfig.scan"),
        ComboBoxWidget(
            Scan::class.java, Scan.values().asList(), widthSpec = WidthSpec.WIDER,
            toString = { scan -> l10n("ui.deliverConfig.scan.$scan") }
        ),
        isEnabled = { formatWidget.value.let { it is VideoRenderJob.Format && it.interlacing } }
    )

    private val colorSpaceWidget = addWidget(
        l10n("ui.deliverConfig.colorSpace"),
        ComboBoxWidget(
            VideoRenderJob.ColorSpace::class.java, VideoRenderJob.ColorSpace.values().asList(),
            widthSpec = WidthSpec.WIDER,
            toString = { cs ->
                when (cs) {
                    VideoRenderJob.ColorSpace.REC_709 ->
                        "Rec. 709  \u2013  BT.709 Gamut, BT.709 Gamma, Limited Range, BT.709 YCbCr Coefficients"
                    VideoRenderJob.ColorSpace.SRGB ->
                        "sRGB / sYCC  \u2013  BT.709 Gamut, sRGB Gamma, Full Range, BT.601 YCbCr Coefficients"
                }
            }
        ).apply { value = VideoRenderJob.ColorSpace.REC_709 },
        isEnabled = { formatWidget.value is VideoRenderJob.Format }
    )

    init {
        // Set the directory-related fields to the project dir.
        val defaultFilename = l10n("ui.deliverConfig.defaultFilename", ctrl.projectDir.fileName)
        val outputLoc = ctrl.projectDir.toAbsolutePath().parent.resolve(defaultFilename)
        singleFileWidget.value = outputLoc
        seqDirWidget.value = outputLoc
        // This ensures that file extensions are sensible.
        onFormatChange()
    }

    override fun onChange(widget: Widget<*>) {
        // Notify the file-related fields when the format (and with it the set of admissible file extensions) changes.
        if (widget == formatWidget)
            onFormatChange()

        super.onChange(widget)

        // This isEnabled check is separate because the FPS multiplier is part of a UnionWidget.
        fpsMultWidget.isEnabled = formatWidget.value is VideoRenderJob.Format

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
        val (project, _, _) = drawnProject ?: return false

        val format = formatWidget.value
        val resMult = 2.0.pow(resolutionMultWidget.value)
        val fpsMult = fpsMultWidget.value
        val scan = if (format is VideoRenderJob.Format && format.interlacing) scanWidget.value else Scan.PROGRESSIVE
        val cs = if (format is VideoRenderJob.Format) colorSpaceWidget.value else VideoRenderJob.ColorSpace.SRGB

        // Determine the scaled specs.
        val resolution = project.styling.global.resolution
        val scaledWidth = (resMult * resolution.widthPx).roundToInt()
        val scaledHeight = (resMult * resolution.heightPx).roundToInt()
        val scaledFPS = project.styling.global.fps.frac * fpsMult
        val scrollSpeeds = project.pages.asSequence()
            .flatMap { page -> page.stages }
            .filter { stage -> stage.style.behavior == PageBehavior.SCROLL }
            .map { stage -> stage.style.scrollPxPerFrame }
            .associateWith { speed -> speed * resMult * fpsMult }

        // Display the scaled specs in the specs labels.
        val decFmt = DecimalFormat("0.###")
        panel.specsLabels[0].text = "$scaledWidth \u00D7 $scaledHeight"
        panel.specsLabels[2].text = when (cs) {
            VideoRenderJob.ColorSpace.REC_709 -> "Rec. 709"
            VideoRenderJob.ColorSpace.SRGB ->
                if (format !is VideoRenderJob.Format || isRGBPixelFormat(format.pixelFormat)) "sRGB" else "sYCC"
        }
        if (format !is VideoRenderJob.Format) {
            panel.specsLabels[1].text = "\u2014"
            panel.specsLabels[3].text = "\u2014"
        } else {
            panel.specsLabels[1].text = decFmt.format(scaledFPS) + if (scan == Scan.PROGRESSIVE) "p" else "i"
            panel.specsLabels[3].text = if (scrollSpeeds.isEmpty()) "\u2014" else {
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
        if (format is VideoRenderJob.Format) {
            if (scrollSpeeds.values.any { s2 -> floor(s2) != s2 })
                warn(l10n("ui.delivery.issues.fractionalFrameShift"))
            if (scan != Scan.PROGRESSIVE && scrollSpeeds.values.any { s2 -> floor(s2 / 2.0) != s2 / 2.0 })
                warn(l10n("ui.delivery.issues.fractionalFieldShift"))
        }

        return !err
    }

    private fun onFormatChange() {
        val format = formatWidget.value
        val fileExtAssortment = FileExtAssortment(format.fileExts.sorted(), format.defaultFileExt)
        singleFileWidget.fileExtAssortment = fileExtAssortment
        seqFilenameSuffixWidget.fileExtAssortment = fileExtAssortment
    }

    fun addRenderJobToQueue() {
        val drawnProject = this.drawnProject
        if (drawnProject == null)
            showMessageDialog(
                ctrl.deliveryDialog, l10n("ui.deliverConfig.noPages.msg"),
                l10n("ui.deliverConfig.noPages.title"), ERROR_MESSAGE
            )
        else {
            val (project, drawnPages, video) = drawnProject

            val format = formatWidget.value
            val fileOrDir = (if (format.fileSeq) seqDirWidget.value else singleFileWidget.value).normalize()
            val transparentGrounding = transparentGroundingWidget.value && format.supportsAlpha
            val grounding = if (transparentGrounding) null else project.styling.global.grounding
            val resolutionScaling = 2.0.pow(resolutionMultWidget.value)
            val fpsScaling = fpsMultWidget.value
            val scan = if (format is VideoRenderJob.Format && format.interlacing) scanWidget.value else Scan.PROGRESSIVE
            val colorSpace = colorSpaceWidget.value

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
                if (fileOrDir.isRegularFile()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.file", fileOrDir))
                    return
                } else if (fileOrDir == ctrl.projectDir) {
                    overwriteProjectDirDialog()
                    return
                } else if (RenderQueue.getRemainingJobs().any { it.generatesFile(fileOrDir) }) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirReused", fileOrDir)))
                        return
                } else if (fileOrDir.isDirectory() && fileOrDir.useDirectoryEntries { seq -> seq.any() })
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirNonEmpty", fileOrDir)))
                        return
            } else {
                if (fileOrDir.isDirectory()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.dir", fileOrDir))
                    return
                } else if (RenderQueue.getRemainingJobs().any { it.generatesFile(fileOrDir) }) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.singleFileReused", fileOrDir)))
                        return
                } else if (fileOrDir.exists() || RenderQueue.getRemainingJobs().any { it.generatesFile(fileOrDir) })
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
                        grounding, format,
                        dir = fileOrDir, filenamePattern = hashPatternToFormatStr(getFilenameHashPattern())
                    )
                    destination = fileOrDir.resolve(getFilenameHashPattern()).pathString
                }
                WholePagePDFRenderJob.FORMAT -> {
                    renderJob = WholePagePDFRenderJob(
                        getScaledPageDefImages(),
                        grounding,
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
                else -> throw IllegalStateException("Internal bug: No renderer known for format '${format.label}'.")
            }

            ctrl.deliveryDialog.panel.renderQueuePanel.addRenderJobToQueue(renderJob, format.label, destination)
        }
    }

    fun updateProject(drawnProject: DrawnProject?) {
        this.drawnProject = drawnProject

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultWidget)
    }

}
