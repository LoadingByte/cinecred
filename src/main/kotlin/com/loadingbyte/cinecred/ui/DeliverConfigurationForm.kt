package com.loadingbyte.cinecred.ui


import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.delivery.WholePagePDFRenderJob
import com.loadingbyte.cinecred.delivery.WholePageSequenceRenderJob
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import com.loadingbyte.cinecred.ui.helper.*
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane.ERROR_MESSAGE
import javax.swing.JOptionPane.showMessageDialog
import javax.swing.SpinnerNumberModel
import kotlin.math.floor
import kotlin.math.roundToInt


object DeliverConfigurationForm : Form() {

    private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.Format.ALL + listOf(WholePagePDFRenderJob.FORMAT)
    private val ALL_FORMATS = (WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL).toTypedArray()

    private val VIDEO_IMAGE_SEQ_FORMATS = VideoRenderJob.Format.ALL.filter { it.isImageSeq }
    private val SEQ_FORMATS = WholePageSequenceRenderJob.Format.ALL + VIDEO_IMAGE_SEQ_FORMATS
    private val ALPHA_FORMATS = WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL.filter { it.supportsAlpha }

    private var project: Project? = null
    private var drawnPages: List<DrawnPage> = emptyList()

    private val resolutionMultSpinner = addSpinner(
        l10n("ui.deliverConfig.resolutionMultiplier"), SpinnerNumberModel(1f, 0.01f, null, 0.5f),
        verify = { verifyResolutionMult(it as Float) }
    )
    private val formatComboBox = addComboBox(l10n("ui.deliverConfig.format"), ALL_FORMATS, toString = { it.label })
        .apply {
            // Avoid the scrollbar.
            maximumRowCount = model.size
            // User a custom render that shows category headers.
            val baseRenderer = CustomToStringListCellRenderer<RenderFormat> { value ->
                val key =
                    if (value in WHOLE_PAGE_FORMATS) "ui.deliverConfig.wholePagesFormatName"
                    else "ui.deliverConfig.videoFormatName"
                l10n(key, value.label)
            }
            renderer = LabeledListCellRenderer(baseRenderer) { index ->
                when (index) {
                    0 -> listOf(l10n("ui.deliverConfig.wholePagesFormatCategory"))
                    WHOLE_PAGE_FORMATS.size -> listOf(l10n("ui.deliverConfig.videoFormatCategory"))
                    else -> emptyList()
                }
            }
        }

    private val seqDirField = addFileField(
        l10n("ui.deliverConfig.seqDir"), FileType.DIRECTORY,
        isVisible = { formatComboBox.selectedItem in SEQ_FORMATS },
        verify = {
            if (Files.exists(it) && Files.list(it).findFirst().isPresent)
                throw VerifyResult(Severity.WARN, l10n("ui.deliverConfig.seqDirNonEmpty"))
        }
    )
    private val seqFilenamePatternField = addFilenameField(
        l10n("ui.deliverConfig.seqFilenamePattern"),
        isVisible = { formatComboBox.selectedItem in WholePageSequenceRenderJob.Format.ALL },
        verify = {
            if (!it.contains(Regex("%0\\d+d")))
                throw VerifyResult(Severity.ERROR, l10n("ui.deliverConfig.seqFilenamePatternMissesN"))
        }
    ).apply { text = "page-%02d" }

    private val singleFileField = addFileField(
        l10n("ui.deliverConfig.singleFile"), FileType.FILE,
        isVisible = { formatComboBox.selectedItem !in SEQ_FORMATS },
        verify = {
            if (Files.exists(it))
                throw VerifyResult(Severity.WARN, l10n("ui.deliverConfig.singleFileExists"))
        }
    )

    private val transparentCheckBox = addCheckBox(
        l10n("ui.deliverConfig.transparent"),
        isVisible = { formatComboBox.selectedItem in ALPHA_FORMATS }
    )

    init {
        finishInit()

        // Notify the file-related fields when the format (and with it the set of admissible file extensions) changes.
        changeListener = { comp -> if (comp == formatComboBox) onFormatChange() }

        addSubmitButton(l10n("ui.deliverConfig.addToRenderQueue")).apply { addActionListener { addRenderJobToQueue() } }
    }

    private fun verifyResolutionMult(resolutionMult: Float) {
        val project = project ?: return

        val scaledWidth = (resolutionMult * project.styling.global.widthPx).roundToInt()
        val scaledHeight = (resolutionMult * project.styling.global.heightPx).roundToInt()
        val yieldMsg = l10n("ui.deliverConfig.resolutionMultiplierYields", scaledWidth, scaledHeight)

        fun err(msg: String) {
            throw VerifyResult(Severity.ERROR, msg)
        }

        // Check for violated restrictions of the currently selected format.
        val format = formatComboBox.selectedItem
        if (format is VideoRenderJob.Format)
            if (format.widthMod2 && scaledWidth % 2 != 0)
                err(l10n("ui.deliverConfig.resolutionMultiplierWidthMod2", yieldMsg, format.label))
            else if (format.heightMod2 && scaledHeight % 2 != 0)
                err(l10n("ui.deliverConfig.resolutionMultiplierHeightMod2", yieldMsg, format.label))
            else if (format.minWidth != null && scaledWidth < format.minWidth)
                err(l10n("ui.deliverConfig.resolutionMultiplierMinWidth", yieldMsg, format.label, format.minWidth))
            else if (format.minHeight != null && scaledWidth < format.minHeight)
                err(l10n("ui.deliverConfig.resolutionMultiplierMinHeight", yieldMsg, format.label, format.minHeight))

        // Check for fractional scroll speeds.
        val scrollSpeeds = project.pages
            .flatMap { page -> page.stages }
            .filter { stage -> stage.style.behavior == PageBehavior.SCROLL }
            .map { stage -> stage.style.scrollPxPerFrame }
            .toSet()
        val fractionalScrollSpeeds = scrollSpeeds
            .filter { floor(it * resolutionMult) != it * resolutionMult }
        if (fractionalScrollSpeeds.isNotEmpty())
            throw VerifyResult(
                Severity.WARN,
                l10n("ui.deliverConfig.resolutionMultiplierFractional", yieldMsg, fractionalScrollSpeeds.joinToString())
            )

        throw VerifyResult(Severity.INFO, yieldMsg)
    }

    private fun onFormatChange() {
        val newFileExts = (formatComboBox.selectedItem as RenderFormat).fileExts
        seqFilenamePatternField.fileExts = newFileExts
        singleFileField.fileExts = newFileExts
    }

    private fun addRenderJobToQueue() {
        if (drawnPages.isEmpty())
            showMessageDialog(
                null, l10n("ui.deliverConfig.noPages.msg"), l10n("ui.deliverConfig.noPages.title"), ERROR_MESSAGE
            )
        else {
            val scaling = resolutionMultSpinner.value as Float

            fun getScaledPageDefImages() = drawnPages.map {
                DeferredImage().apply { drawDeferredImage(it.defImage, 0f, 0f, scaling) }
            }

            val format = formatComboBox.selectedItem as RenderFormat
            val renderJob = when (format) {
                is WholePageSequenceRenderJob.Format -> WholePageSequenceRenderJob(
                    getScaledPageDefImages(),
                    transparent = transparentCheckBox.isSelected,
                    format, dir = Path.of(seqDirField.text).normalize(), filenamePattern = seqFilenamePatternField.text
                )
                WholePagePDFRenderJob.FORMAT -> WholePagePDFRenderJob(
                    getScaledPageDefImages(),
                    transparent = transparentCheckBox.isSelected,
                    file = Path.of(singleFileField.text).normalize()
                )
                is VideoRenderJob.Format -> VideoRenderJob(
                    project!!, drawnPages,
                    scaling, transparent = format.supportsAlpha && transparentCheckBox.isSelected, format,
                    fileOrDir = Path.of(if (format.isImageSeq) seqDirField.text else singleFileField.text).normalize()
                )
                else -> throw IllegalStateException("Internal bug: No renderer known for format '${format.label}'.")
            }
            val destination = when (renderJob) {
                is WholePageSequenceRenderJob -> renderJob.dir.resolve(renderJob.filenamePattern).toString()
                is WholePagePDFRenderJob -> renderJob.file.toString()
                is VideoRenderJob -> renderJob.fileOrPattern.toString()
                else -> throw IllegalStateException()
            }
            DeliverRenderQueuePanel.addRenderJobToQueue(renderJob, format.label, destination)
        }
    }

    fun onOpenProjectDir(projectDir: Path) {
        // Reset the directory-related fields to the newly opened project dir.
        val defaultFilename = l10n("ui.deliverConfig.defaultFilename", projectDir.fileName)
        val outputLoc = projectDir.toAbsolutePath().resolve(defaultFilename).toString()
        seqDirField.text = outputLoc
        singleFileField.text = outputLoc

        // This ensure that file extensions are sensible.
        onFormatChange()
    }

    fun updateProject(project: Project?, drawnPages: List<DrawnPage>) {
        this.project = project
        this.drawnPages = drawnPages

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultSpinner)
    }

}
