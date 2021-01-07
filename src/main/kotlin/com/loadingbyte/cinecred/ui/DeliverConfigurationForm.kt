package com.loadingbyte.cinecred.ui


import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.delivery.WholePagePDFRenderJob
import com.loadingbyte.cinecred.delivery.WholePageSequenceRenderJob
import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane.ERROR_MESSAGE
import javax.swing.JOptionPane.showMessageDialog
import javax.swing.SpinnerNumberModel
import kotlin.math.floor
import kotlin.math.roundToInt


object DeliverConfigurationForm : Form() {

    private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.Format.ALL + listOf(WholePagePDFRenderJob.FORMAT)
    private val ALL_FORMATS = WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL

    private val VIDEO_IMAGE_SEQ_FORMATS = VideoRenderJob.Format.ALL.filter { it.isImageSeq }
    private val SEQ_FORMATS = WholePageSequenceRenderJob.Format.ALL + VIDEO_IMAGE_SEQ_FORMATS
    private val ALPHA_FORMATS = WHOLE_PAGE_FORMATS + VideoRenderJob.Format.ALL.filter { it.supportsAlpha }

    private var project: Project? = null
    private var pageDefImages: List<DeferredImage> = emptyList()

    private val resolutionMultSpinner = addSpinner(
        "Resolution Multiplier", SpinnerNumberModel(1f, 0.01f, null, 0.5f),
        verify = { verifyResolutionMult(it as Float) }
    )
    private val formatComboBox = addComboBox("Output format", ALL_FORMATS.toTypedArray(), toString = { it.label })
        .apply {
            // Avoid the scrollbar.
            maximumRowCount = model.size
            // User a custom render that shows category headers.
            renderer = object : LabeledListCellRenderer() {
                override fun toString(value: Any) =
                    (if (value in WHOLE_PAGE_FORMATS) "Whole Page Stills: " else "Video: ") + (value as RenderFormat).label

                override fun getLabelLines(index: Int) = when (index) {
                    0 -> listOf("Whole Page Stills")
                    WHOLE_PAGE_FORMATS.size -> listOf("Video")
                    else -> emptyList()
                }
            }
        }

    private val seqDirField = addFileField(
        "Output Folder", FileType.DIRECTORY,
        isVisible = { formatComboBox.selectedItem in SEQ_FORMATS },
        verify = {
            if (Files.exists(it) && Files.list(it).findFirst().isPresent)
                throw VerifyResult(Severity.WARN, "Folder is not empty; its contents might be overwritten.")
        }
    )
    private val wholePageSeqFilenamePatternField = addFilenameField(
        "Filename Pattern",
        isVisible = { formatComboBox.selectedItem in WholePageSequenceRenderJob.Format.ALL },
        verify = {
            if (!it.contains(Regex("%0\\d+d")))
                throw VerifyResult(Severity.ERROR, "Pattern doesn't contain \"%0[num digits]d\", e.g., \"%02d\".")
        }
    ).apply { text = "page-%02d" }

    private val singlefileFileField = addFileField(
        "Output File", FileType.FILE,
        isVisible = { formatComboBox.selectedItem !in SEQ_FORMATS },
        verify = {
            if (Files.exists(it))
                throw VerifyResult(Severity.WARN, "File already exists and will be overwritten.")
        }
    )

    private val alphaCheckBox = addCheckBox(
        "Transparent Background",
        isVisible = { formatComboBox.selectedItem in ALPHA_FORMATS }
    )

    init {
        // Notify the file-related fields when the format (and with it the set of admissible file extensions) changes.
        addChangeListener { comp -> if (comp == formatComboBox) onFormatChange() }

        addSubmitButton("Add to Render Queue").apply { addActionListener { addRenderJobToQueue() } }
    }


    private fun verifyResolutionMult(resolutionMult: Float) {
        val project = project ?: return

        val scaledWidth = (project.styling.global.widthPx * resolutionMult).roundToInt()
        val scaledHeight = (project.styling.global.heightPx * resolutionMult).roundToInt()
        val info = "Yields resolution ${scaledWidth}x${scaledHeight}."

        val scrollSpeeds = project.pages
            .filter { page -> page.style.behavior == PageBehavior.SCROLL }
            .map { page -> page.style.scrollPxPerFrame }
            .toSet()
        val fractionalScrollSpeeds = scrollSpeeds
            .filter { floor(it * resolutionMult) != it * resolutionMult }
        if (fractionalScrollSpeeds.isNotEmpty())
            throw VerifyResult(
                Severity.WARN,
                "$info However, the following page scroll speeds are fractional or become fractional when " +
                        "multiplying them with the resolution multiplier, which may lead to jitter: " +
                        fractionalScrollSpeeds.joinToString() + "."
            )

        throw VerifyResult(Severity.INFO, info)
    }

    private fun onFormatChange() {
        val newFileExts = (formatComboBox.selectedItem as RenderFormat).fileExts
        wholePageSeqFilenamePatternField.fileExts = newFileExts
        singlefileFileField.fileExts = newFileExts
    }

    private fun addRenderJobToQueue() {
        if (project == null)
            showMessageDialog(null, "No error-free project has been opened yet.", "No Open Project", ERROR_MESSAGE)
        else {
            val scaling = resolutionMultSpinner.value as Float
            val scaledGlobalWidth = (scaling * project!!.styling.global.widthPx).roundToInt()
            val scaledPageDefImages = pageDefImages.map {
                DeferredImage().apply { drawDeferredImage(it, 0f, 0f, scaling) }
            }
            val alphaBackground = if (alphaCheckBox.isSelected) null else project!!.styling.global.background

            val format = formatComboBox.selectedItem as RenderFormat
            val renderJob = when (format) {
                is WholePageSequenceRenderJob.Format -> WholePageSequenceRenderJob(
                    scaledPageDefImages, scaledGlobalWidth, background = alphaBackground,
                    format, dir = Path.of(seqDirField.text), filenamePattern = wholePageSeqFilenamePatternField.text
                )
                WholePagePDFRenderJob.FORMAT -> WholePagePDFRenderJob(
                    scaledPageDefImages, scaledGlobalWidth, background = alphaBackground,
                    file = Path.of(singlefileFileField.text)
                )
                is VideoRenderJob.Format -> VideoRenderJob(
                    project!!, scaledPageDefImages, scaling, alpha = format.supportsAlpha && alphaCheckBox.isSelected,
                    format, fileOrDir = Path.of(if (format.isImageSeq) seqDirField.text else singlefileFileField.text)
                )
                else -> throw IllegalStateException("No renderer known for format '${format.label}'.")
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
        val outputLoc = projectDir.toAbsolutePath().resolve("delivery").toString()
        seqDirField.text = outputLoc
        singlefileFileField.text = outputLoc

        // This ensure that file extensions are sensible.
        onFormatChange()
    }

    fun updateProject(project: Project, pageDefImages: List<DeferredImage>) {
        this.project = project
        this.pageDefImages = pageDefImages

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultSpinner)
    }

}
