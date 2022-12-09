package com.loadingbyte.cinecred.ui


import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.ui.helper.*
import javax.swing.JOptionPane.*
import javax.swing.SpinnerNumberModel
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.useDirectoryEntries
import kotlin.math.floor
import kotlin.math.roundToInt


class DeliverConfigurationForm(private val ctrl: ProjectController) : EasyForm(insets = false) {

    companion object {
        private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.Format.ALL + listOf(WholePagePDFRenderJob.FORMAT)
        private val ALL_FORMATS = (WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL)
    }

    private var project: Project? = null
    private var drawnPages: List<DrawnPage> = emptyList()

    private val formatWidget = addWidget(
        l10n("ui.deliverConfig.format"),
        ComboBoxWidget(
            RenderFormat::class.java, ALL_FORMATS, widthSpec = WidthSpec.FREE, scrollbar = false,
            toString = {
                val key =
                    if (it in WHOLE_PAGE_FORMATS) "ui.deliverConfig.wholePagesFormatName"
                    else "ui.deliverConfig.videoFormatName"
                l10n(key, it.label)
            },
            // User a custom render that shows category headers.
            decorateRenderer = { baseRenderer ->
                LabeledListCellRenderer(baseRenderer) { index ->
                    when (index) {
                        0 -> listOf(l10n("ui.deliverConfig.wholePagesFormatCategory"))
                        WHOLE_PAGE_FORMATS.size -> listOf(l10n("ui.deliverConfig.videoFormatCategory"))
                        else -> emptyList()
                    }
                }
            })
    )

    private val singleFileWidget = addWidget(
        l10n("ui.deliverConfig.singleFile"),
        FileWidget(FileType.FILE, widthSpec = WidthSpec.WIDER),
        isVisible = { !formatWidget.value.fileSeq },
        verify = { if (it.toString().isBlank()) Notice(Severity.ERROR, l10n("blank")) else null }
    )

    private val seqDirWidget = addWidget(
        l10n("ui.deliverConfig.seqDir"),
        FileWidget(FileType.DIRECTORY, widthSpec = WidthSpec.WIDER),
        isVisible = { formatWidget.value.fileSeq },
        verify = { if (it.toString().isBlank()) Notice(Severity.ERROR, l10n("blank")) else null }
    )
    private val seqFilenamePatternWidget = addWidget(
        l10n("ui.deliverConfig.seqFilenamePattern"),
        FilenameWidget(widthSpec = WidthSpec.WIDER),
        // Reserve space even if invisible to keep the form from changing height when selecting different formats.
        invisibleSpace = true,
        isVisible = { formatWidget.value in WholePageSequenceRenderJob.Format.ALL },
        verify = {
            if (!it.contains(Regex("%0\\d+d")))
                Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenamePatternMissesN"))
            else if (it.count { c -> c == '%' } > 1)
                Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenamePatternTooManyPercents"))
            else null
        }
    ).apply { value = "page-%02d" }

    private val resolutionMultWidget = addWidget(
        l10n("ui.deliverConfig.resolutionMultiplier"),
        SpinnerWidget(Float::class.javaObjectType, SpinnerNumberModel(1f, 0.01f, null, 1f)),
        verify = ::verifyResolutionMult
    )
    private val transparentGroundingWidget = addWidget(
        l10n("ui.deliverConfig.transparentGrounding"),
        CheckBoxWidget(),
        isEnabled = { formatWidget.value.supportsAlpha }
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

    private fun verifyResolutionMult(resolutionMult: Float): Notice? {
        val project = this.project ?: return null

        val scaledWidth = (resolutionMult * project.styling.global.widthPx).roundToInt()
        val scaledHeight = (resolutionMult * project.styling.global.heightPx).roundToInt()
        val yieldMsg = l10n("ui.deliverConfig.resolutionMultiplierYields", scaledWidth, scaledHeight)

        fun err(msg: String) = Notice(Severity.ERROR, msg)

        // Check for violated restrictions of the currently selected format.
        val format = formatWidget.value
        val forLabel = format.label
        if (format is VideoRenderJob.Format)
            if (format.widthMod2 && scaledWidth % 2 != 0)
                return err(l10n("ui.deliverConfig.resolutionMultiplierWidthMod2", yieldMsg, forLabel))
            else if (format.heightMod2 && scaledHeight % 2 != 0)
                return err(l10n("ui.deliverConfig.resolutionMultiplierHeightMod2", yieldMsg, forLabel))
            else if (format.minWidth != null && scaledWidth < format.minWidth)
                return err(l10n("ui.deliverConfig.resolutionMultiplierMinWidth", yieldMsg, forLabel, format.minWidth))
            else if (format.minHeight != null && scaledWidth < format.minHeight)
                return err(l10n("ui.deliverConfig.resolutionMultiplierMinHeight", yieldMsg, forLabel, format.minHeight))

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
                    add("$scrollSpeed \u2192 $scaledScrollSpeed")
            }
        }
        if (fractionalScrollSpeeds.isNotEmpty())
            return Notice(
                Severity.WARN,
                l10n("ui.deliverConfig.resolutionMultiplierFractional", yieldMsg, fractionalScrollSpeeds.joinToString())
            )

        return Notice(Severity.INFO, yieldMsg)
    }

    private fun onFormatChange() {
        val format = formatWidget.value
        val fileExtAssortment = FileExtAssortment(format.fileExts.sorted(), format.defaultFileExt)
        singleFileWidget.fileExtAssortment = fileExtAssortment
        seqFilenamePatternWidget.fileExtAssortment = fileExtAssortment
    }

    fun addRenderJobToQueue() {
        if (drawnPages.isEmpty())
            showMessageDialog(
                ctrl.deliveryDialog, l10n("ui.deliverConfig.noPages.msg"),
                l10n("ui.deliverConfig.noPages.title"), ERROR_MESSAGE
            )
        else {
            val format = formatWidget.value
            val fileOrDir = (if (format.fileSeq) seqDirWidget.value else singleFileWidget.value).normalize()
            val scaling = resolutionMultWidget.value

            fun wrongFileTypeDialog(msg: String) = showMessageDialog(
                ctrl.deliveryDialog, msg, l10n("ui.deliverConfig.wrongFileType.title"), ERROR_MESSAGE
            )

            fun overwriteDialog(msg: String) = showConfirmDialog(
                ctrl.deliveryDialog, msg, l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
            ) == OK_OPTION

            // If there is any issue with the output file or folder, inform the user and abort if necessary.
            if (format.fileSeq) {
                if (fileOrDir.isRegularFile()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.file", fileOrDir))
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

            val renderJob = when (format) {
                is WholePageSequenceRenderJob.Format -> WholePageSequenceRenderJob(
                    getScaledPageDefImages(),
                    transparentGroundingWidget.value, format,
                    dir = fileOrDir, filenamePattern = seqFilenamePatternWidget.value
                )
                WholePagePDFRenderJob.FORMAT -> WholePagePDFRenderJob(
                    getScaledPageDefImages(),
                    transparentGroundingWidget.value,
                    file = fileOrDir
                )
                is VideoRenderJob.Format -> VideoRenderJob(
                    project!!, drawnPages,
                    scaling, transparentGroundingWidget.value && format.supportsAlpha, format,
                    fileOrDir = fileOrDir
                )
                else -> throw IllegalStateException("Internal bug: No renderer known for format '${format.label}'.")
            }
            val destination = when (renderJob) {
                is WholePageSequenceRenderJob -> renderJob.dir.resolve(renderJob.filenamePattern).toString()
                is WholePagePDFRenderJob -> renderJob.file.toString()
                is VideoRenderJob -> renderJob.fileOrPattern.toString()
                else -> throw IllegalStateException()
            }
            ctrl.deliveryDialog.panel.renderQueuePanel.addRenderJobToQueue(renderJob, format.label, destination)
        }
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>) {
        this.project = project
        this.drawnPages = drawnPages

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultWidget)
    }

}
