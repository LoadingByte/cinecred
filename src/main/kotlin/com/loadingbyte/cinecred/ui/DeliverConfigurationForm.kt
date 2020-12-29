package com.loadingbyte.cinecred.ui


import com.loadingbyte.cinecred.Severity
import com.loadingbyte.cinecred.delivery.PDFRenderJob
import com.loadingbyte.cinecred.delivery.PageSequenceRenderJob
import com.loadingbyte.cinecred.delivery.RenderJob
import com.loadingbyte.cinecred.delivery.VideoRenderJob
import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.Project
import java.nio.file.Path
import javax.swing.JOptionPane.ERROR_MESSAGE
import javax.swing.JOptionPane.showMessageDialog
import javax.swing.SpinnerNumberModel
import kotlin.math.ceil
import kotlin.math.floor


object DeliverConfigurationForm : Form() {

    private var project: Project? = null
    private var pageDefImages: List<DeferredImage> = emptyList()

    private val resolutionMultSpinner = addSpinner(
        "Resolution Multiplier", SpinnerNumberModel(1f, 0.01f, null, 0.5f),
        verify = { verifyResolutionMult(it as Float) }
    )
    private val formatComboBox = addComboBox("Output format", Format.ALL.toTypedArray(), toString = { it.label })
        .apply { /* Avoid the scrollbar */ maximumRowCount = model.size }

    private val seqDirField = addFileField(
        "Output Directory", FileType.DIRECTORY,
        isVisible = { formatComboBox.selectedItem in Format.PAGE_SEQ }
    )
    private val seqFilenamePatternField = addFilenameField(
        "Filename Pattern",
        isVisible = { formatComboBox.selectedItem in Format.PAGE_SEQ },
        verify = {
            if (!it.contains(Regex("%0\\d+d")))
                throw VerifyResult(Severity.ERROR, "Pattern doesn't contain \"%0[num digits]d\", e.g., \"%02d\".")
        }
    ).apply { text = "page-%02d" }

    private val singlefileFileField = addFileField(
        "Output File", FileType.FILE,
        isVisible = { formatComboBox.selectedItem !in Format.PAGE_SEQ }
    )

    init {
        // Notify the file-related fields when the format (and with it the set of admissible file extensions) changes.
        addChangeListener { comp -> if (comp == formatComboBox) onFormatChange() }

        addSubmitButton("Add to Render Queue").apply { addActionListener { addRenderJobToQueue() } }
    }


    private fun verifyResolutionMult(resolutionMult: Float) {
        val project = project ?: return

        val scaledWidth = ceil(project.styling.global.widthPx * resolutionMult).toInt()
        val scaledHeight = ceil(project.styling.global.heightPx * resolutionMult).toInt()
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
        val newFileExts = (formatComboBox.selectedItem as Format).fileExts
        seqFilenamePatternField.fileExts = newFileExts
        singlefileFileField.fileExts = newFileExts
    }

    private fun addRenderJobToQueue() {
        if (project == null)
            showMessageDialog(null, "No project has been opened yet.", "No open project", ERROR_MESSAGE)
        else {
            val format = formatComboBox.selectedItem as Format
            DeliverRenderQueuePanel.addRenderJobToQueue(format.toRenderJob(), format.label)
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


    private class Format private constructor(
        val label: String,
        val fileExts: List<String>,
        val toRenderJob: () -> RenderJob
    ) {
        companion object {
            val PAGE_SEQ: List<Format> = PageSequenceRenderJob.Format.values().map { pageSeqFormat ->
                val fileExt = pageSeqFormat.name.toLowerCase()
                Format("Whole Pages as ${pageSeqFormat.name}s", listOf(fileExt)) {
                    val background = project!!.styling.global.background
                    val dir = Path.of(seqDirField.text)
                    val filenamePattern = seqFilenamePatternField.text
                    PageSequenceRenderJob(background, scaledPageDefImages, pageSeqFormat, dir, filenamePattern)
                }
            }

            val PDF = Format("Whole Pages in PDF", listOf("pdf")) {
                val file = Path.of(singlefileFileField.text)
                PDFRenderJob(project!!.styling.global.background, scaledPageDefImages, file)
            }

            val VIDEO: List<Format> = VideoRenderJob.Format.ALL.map { videoFormat ->
                Format(videoFormat.label, videoFormat.fileExts) {
                    val file = Path.of(singlefileFileField.text)
                    VideoRenderJob(project!!, scaledPageDefImages, scaling, videoFormat, file)
                }
            }

            val ALL: List<Format> = PAGE_SEQ + listOf(PDF) + VIDEO

            // Utilities
            private val scaling
                get() = resolutionMultSpinner.value as Float
            private val scaledPageDefImages
                get() = pageDefImages.map {
                    DeferredImage().apply { drawDeferredImage(it, 0f, 0f, scaling) }
                }
        }
    }

}
