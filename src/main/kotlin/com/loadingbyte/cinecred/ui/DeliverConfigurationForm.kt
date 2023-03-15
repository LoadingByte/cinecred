package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.project.DrawnProject
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.ui.helper.*
import java.nio.file.Path
import javax.swing.JOptionPane.*
import javax.swing.SpinnerNumberModel
import kotlin.io.path.*
import kotlin.math.floor
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
            })
    ).apply { value = VideoRenderJob.Format.ALL.first() }

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
        FilenameWidget(widthSpec = WidthSpec.WIDER),
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
    ).apply { value = ".#######" }

    private val resolutionMultWidget = addWidget(
        l10n("ui.deliverConfig.resolutionMultiplier"),
        SpinnerWidget(Double::class.javaObjectType, SpinnerNumberModel(1.0, 0.01, null, 1.0)),
        verify = ::verifyResolutionMult
    )
    private val transparentGroundingWidget = addWidget(
        l10n("ui.deliverConfig.transparentGrounding"),
        CheckBoxWidget(),
        isEnabled = { formatWidget.value.supportsAlpha }
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
        val outputLoc = ctrl.projectDir.toAbsolutePath().resolve(defaultFilename)
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

        // Disable the add button if there are errors in the form.
        // We need the null-safe access because this method might be called before everything is initialized.
        val dialog = ctrl.deliveryDialog as DeliveryDialog?
        if (dialog != null)
            dialog.panel.addButton.isEnabled = isErrorFree
    }

    private fun verifyFile(file: Path): Notice? = when {
        file.pathString.isBlank() -> Notice(Severity.ERROR, l10n("blank"))
        !file.isAbsolute -> Notice(Severity.ERROR, l10n("ui.deliverConfig.nonAbsolutePath"))
        else -> null
    }

    private fun verifyResolutionMult(resolutionMult: Double): Notice? {
        val (project, _, _) = drawnProject ?: return null

        val resolution = project.styling.global.resolution
        val scaledWidth = (resolutionMult * resolution.widthPx).roundToInt()
        val scaledHeight = (resolutionMult * resolution.heightPx).roundToInt()
        val yieldMsg = l10n("ui.deliverConfig.resolutionMultiplierYields", scaledWidth, scaledHeight)

        fun warn(msg: String) = Notice(Severity.WARN, "$yieldMsg $msg")
        fun err(msg: String) = Notice(Severity.ERROR, "$yieldMsg $msg")

        // Check for violated restrictions of the currently selected format.
        val format = formatWidget.value
        val forLabel = format.label
        if (format is VideoRenderJob.Format)
            if (format.widthMod2 && scaledWidth % 2 != 0)
                return err(l10n("ui.deliverConfig.resolutionMultiplierWidthMod2", forLabel))
            else if (format.heightMod2 && scaledHeight % 2 != 0)
                return err(l10n("ui.deliverConfig.resolutionMultiplierHeightMod2", forLabel))
            else if (format.minWidth != null && scaledWidth < format.minWidth)
                return err(l10n("ui.deliverConfig.resolutionMultiplierMinWidth", forLabel, format.minWidth))
            else if (format.minHeight != null && scaledWidth < format.minHeight)
                return err(l10n("ui.deliverConfig.resolutionMultiplierMinHeight", forLabel, format.minHeight))

        // Check for fractional scroll speeds.
        val scrollSpeeds = project.pages
            .flatMap { page -> page.stages }
            .filter { stage -> stage.style.behavior == PageBehavior.SCROLL }
            .map { stage -> stage.style.scrollPxPerFrame }
            .toSet()
        val fractionalScrollSpeeds = buildSet {
            for (scrollSpeed in scrollSpeeds) {
                val scaledScrollSpeed = scrollSpeed * resolutionMult
                if (floor(scrollSpeed) == scrollSpeed && floor(scaledScrollSpeed) != scaledScrollSpeed)
                    add("%d \u2192 %.2f".format(scrollSpeed.toInt(), scaledScrollSpeed))
            }
        }
        if (fractionalScrollSpeeds.isNotEmpty())
            return warn(l10n("ui.deliverConfig.resolutionMultiplierFractional", fractionalScrollSpeeds.joinToString()))

        return Notice(Severity.INFO, yieldMsg)
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
            val scaling = resolutionMultWidget.value
            val transparentGrounding = transparentGroundingWidget.value && format.supportsAlpha
            val grounding = if (transparentGrounding) null else project.styling.global.grounding
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

            fun getScaledPageDefImages() = drawnPages.map { it.defImage.copy(universeScaling = scaling) }
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
                        project, video, scaling, transparentGrounding, colorSpace, format, fileOrPattern
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
