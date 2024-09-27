package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.isAccessibleDirectory
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.delivery.RenderFormat.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.CINEFORM_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DNXHR_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DPX_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.EXR_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.HDR
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PDF_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRIMARIES
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRORES_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.RESOLUTION_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TIFF_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSFER
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSPARENCY
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.YUV
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnPage
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.Bitmap.Scan
import com.loadingbyte.cinecred.imaging.BitmapWriter.*
import com.loadingbyte.cinecred.imaging.ColorSpace
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

        private val WHOLE_PAGE_FORMATS = WholePageSequenceRenderJob.FORMATS + WholePagePDFRenderJob.FORMATS
        private val VIDEO_FORMATS = VideoContainerRenderJob.FORMATS + ImageSequenceRenderJob.FORMATS
        private val ALL_FORMATS = WHOLE_PAGE_FORMATS + VIDEO_FORMATS + TapeTimelineRenderJob.FORMATS

        private val VOLATILE_PROPERTIES: Set<Property<*>> = hashSetOf(DEPTH, YUV)

    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedFormatWidget get() = formatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedProfileWidget get() = profileWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedSingleFileWidget get() = singleFileWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedTransparencyWidget get() = transparencyWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedResolutionMultWidget get() = resolutionMultWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedScanWidget get() = scanWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedPrimariesWidget get() = primariesWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedTransferWidget get() = transferWidget
    // =========================================

    private var drawnProject: DrawnProject? = null

    private val spreadsheetNameWidget =
        OptionalComboBoxWidget(String::class.java, emptyList(), widthSpec = WidthSpec.SQUEEZE)

    private val pageIndicesWidget =
        MultiComboBoxWidget<Int>(emptyList(), naturalOrder(), toString = { "${it + 1}" }, widthSpec = WidthSpec.SQUEEZE)

    private val firstPageIdxWidget =
        ComboBoxWidget(Int::class.javaObjectType, emptyList(), toString = { "${it + 1}" }, widthSpec = WidthSpec.TINIER)
    private val lastPageIdxWidget =
        ComboBoxWidget(Int::class.javaObjectType, emptyList(), toString = { "${it + 1}" }, widthSpec = WidthSpec.TINIER)

    private val spreadsheetAndPagesFormRow = FormRow(
        l10n("ui.deliverConfig.spreadsheet"),
        UnionWidget(
            listOf(spreadsheetNameWidget, pageIndicesWidget, firstPageIdxWidget, lastPageIdxWidget),
            labels = listOf(null, l10n("ui.deliverConfig.pages"), l10n("ui.deliverConfig.pages"), "\u2013"),
            gaps = listOf(null, null, "rel")
        )
    ).also(::addFormRow)

    private val formatWidget = addWidget(
        l10n("ui.deliverConfig.format"),
        ComboBoxWidget(
            RenderFormat::class.java, ALL_FORMATS, widthSpec = WidthSpec.WIDER, scrollbar = false,
            toString = { format ->
                val prefix = when (format) {
                    in WHOLE_PAGE_FORMATS -> l10n("ui.deliverConfig.wholePageFormat")
                    in VIDEO_FORMATS -> l10n("ui.deliverConfig.videoFormat")
                    in TapeTimelineRenderJob.FORMATS -> l10n("ui.deliverConfig.timelineFormat")
                    else -> throw IllegalArgumentException()
                }
                val label = when (format) {
                    in ImageSequenceRenderJob.FORMATS -> l10n("ui.deliverConfig.imageSequenceFormat", format.label)
                    else -> format.label
                }
                val suffix = when (format) {
                    VideoContainerRenderJob.H264, VideoContainerRenderJob.H265 ->
                        "  \u2013  " + l10n("ui.deliverConfig.reducedQualityFormat")
                    else -> ""
                }
                "$prefix  \u2013  ${label}$suffix"
            },
            // Use a custom render that shows category headers.
            rendererDecorator = object : AbstractComboBoxWidget.RendererDecorator {
                override fun <I> decorate(renderer: ListCellRenderer<I>) = LabeledListCellRenderer(renderer) { index ->
                    when (index) {
                        0 -> listOf(l10n("ui.deliverConfig.wholePageFormat"))
                        WHOLE_PAGE_FORMATS.size -> listOf(l10n("ui.deliverConfig.videoFormat"))
                        WHOLE_PAGE_FORMATS.size + VIDEO_FORMATS.size -> listOf(l10n("ui.deliverConfig.timelineFormat"))
                        else -> emptyList()
                    }
                }
            }
        ).apply { value = VideoContainerRenderJob.H264 }
    )

    private val profileWidget = addWidget(
        l10n("ui.deliverConfig.profile"),
        ComboBoxWidget(
            Enum::class.java, emptyList(), widthSpec = WidthSpec.WIDER,
            toString = { prof ->
                fun bigger() = "  \u2013  " + l10n("ui.deliverConfig.profile.biggerCommoner")
                fun smaller() = "  \u2013  " + l10n("ui.deliverConfig.profile.smallerRarer")
                fun pdfLossy() = l10n("ui.deliverConfig.profile.pdfLossy")
                fun pdfLossless() = l10n("ui.deliverConfig.profile.pdfLossless")
                fun pdfAndRasterizeSVGs(msg: String) = l10n("ui.deliverConfig.profile.pdfAndRasterizeSVGs", msg)
                when (prof) {
                    is TIFF.Compression -> when (prof) {
                        TIFF.Compression.NONE -> l10n("ui.deliverConfig.profile.uncompressed") + bigger()
                        TIFF.Compression.PACK_BITS -> "PackBits" + bigger()
                        TIFF.Compression.LZW -> "LZW" + smaller()
                        TIFF.Compression.DEFLATE -> "Deflate" + smaller()
                    }
                    is DPX.Compression -> when (prof) {
                        DPX.Compression.NONE -> l10n("ui.deliverConfig.profile.uncompressed") + bigger()
                        DPX.Compression.RLE -> l10n("ui.deliverConfig.profile.rle") + smaller()
                    }
                    is EXR.Compression -> when (prof) {
                        EXR.Compression.NONE -> l10n("ui.deliverConfig.profile.uncompressed")
                        EXR.Compression.RLE -> l10n("ui.deliverConfig.profile.rle")
                        EXR.Compression.ZIPS -> "ZIPS"
                        EXR.Compression.ZIP -> "ZIP"
                    }
                    is ProResProfile -> when (prof) {
                        ProResProfile.PRORES_422_PROXY -> "ProRes 422 Proxy"
                        ProResProfile.PRORES_422_LT -> "ProRes 422 LT"
                        ProResProfile.PRORES_422 -> "ProRes 422"
                        ProResProfile.PRORES_422_HQ -> "ProRes 422 HQ"
                        ProResProfile.PRORES_4444 -> "ProRes 4444"
                        ProResProfile.PRORES_4444_XQ -> "ProRes 4444 XQ"
                    }
                    is DNxHRProfile -> when (prof) {
                        DNxHRProfile.DNXHR_LB -> "DNxHR LB"
                        DNxHRProfile.DNXHR_SQ -> "DNxHR SQ"
                        DNxHRProfile.DNXHR_HQ -> "DNxHR HQ"
                        DNxHRProfile.DNXHR_HQX -> "DNxHR HQX"
                        DNxHRProfile.DNXHR_444 -> "DNxHR 444"
                    }
                    is CineFormProfile -> when (prof) {
                        CineFormProfile.CF_422_LOW -> "CineForm 422 Low"
                        CineFormProfile.CF_422_MED -> "CineForm 422 Medium"
                        CineFormProfile.CF_422_HI -> "CineForm 422 High"
                        CineFormProfile.CF_422_FILM1 -> "CineForm 422 Filmscan 1"
                        CineFormProfile.CF_422_FILM2 -> "CineForm 422 Filmscan 2"
                        CineFormProfile.CF_422_FILM3 -> "CineForm 422 Filmscan 3"
                        CineFormProfile.CF_444_LOW -> "CineForm 444 Low"
                        CineFormProfile.CF_444_MED -> "CineForm 444 Medium"
                        CineFormProfile.CF_444_HI -> "CineForm 444 High"
                        CineFormProfile.CF_444_FILM1 -> "CineForm 444 Filmscan 1"
                        CineFormProfile.CF_444_FILM2 -> "CineForm 444 Filmscan 2"
                        CineFormProfile.CF_444_FILM3 -> "CineForm 444 Filmscan 3"
                    }
                    is PDFProfile -> when (prof) {
                        PDFProfile.LOSSY_VECTORSVG -> pdfLossy()
                        PDFProfile.LOSSY_RASTERSVG -> pdfAndRasterizeSVGs(pdfLossy())
                        PDFProfile.LOSSLESS_VECTORSVG -> pdfLossless()
                        PDFProfile.LOSSLESS_RASTERSVG -> pdfAndRasterizeSVGs(pdfLossless())
                    }
                    else -> throw IllegalArgumentException()
                }
            }
        )
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

    private val transparencyWidget = addWidget(
        l10n("ui.deliverConfig.transparency"),
        ComboBoxWidget(
            Transparency::class.java, emptyList(), widthSpec = WidthSpec.WIDER,
            toString = { l10n("ui.deliverConfig.transparency.$it") }
        )
    )

    private val resolutionMultWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, RESOLUTION_SCALING_LOG2.standardOptions.asList(), widthSpec = WidthSpec.LITTLE,
            toString = { if (it >= 0) "\u00D7 ${1 shl it}" else "\u00F7 ${1 shl -it}" }
        ).apply { value = RESOLUTION_SCALING_LOG2.standardDefault }

    private val fpsMultWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, FPS_SCALING.standardOptions.asList(), widthSpec = WidthSpec.LITTLE,
            toString = { "\u00D7 $it" }
        ).apply { value = FPS_SCALING.standardDefault }

    private val depthWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, emptyList(), widthSpec = WidthSpec.LITTLE,
            toString = Int::toString
        )

    init {
        addWidget(
            l10n("ui.styling.global.resolution"),
            UnionWidget(
                listOf(resolutionMultWidget, fpsMultWidget, depthWidget),
                labels = listOf(null, l10n("ui.styling.global.fps"), l10n("bitDepth"))
            )
        )
    }

    private val scanWidget = addWidget(
        l10n("ui.deliverConfig.scan"),
        ComboBoxWidget(
            Scan::class.java, emptyList(), widthSpec = WidthSpec.WIDER,
            toString = { scan -> l10n("ui.deliverConfig.scan.$scan") }
        )
    )

    private val primariesWidget =
        ComboBoxWidget(ColorSpace.Primaries::class.java, emptyList(), widthSpec = WidthSpec.NARROW)
    private val transferWidget =
        ComboBoxWidget(ColorSpace.Transfer::class.java, emptyList(), widthSpec = WidthSpec.NARROW)
    private val yuvWidget =
        ComboBoxWidget(Bitmap.YUVCoefficients::class.java, emptyList(), widthSpec = WidthSpec.SQUEEZE)
    private val hdrWidget =
        CheckBoxWidget()

    init {
        addWidget(
            l10n("gamut"),
            UnionWidget(
                listOf(primariesWidget, transferWidget, yuvWidget, hdrWidget),
                labels = listOf(null, "EOTF", "YUV", "HDR")
            )
        )
    }

    private var disableOnChange = false

    init {
        // Get the form into a reasonable state even before the drawn project arrives.
        onChange(spreadsheetNameWidget)
        onChange(formatWidget)
    }

    override fun onChange(widget: Widget<*>) {
        if (disableOnChange)
            return

        disableOnChange = true
        if (widget == spreadsheetNameWidget) {
            // If another spreadsheet name is selected, set the output location fields to a reasonable default.
            // We've actually had a bug report where the project dir didn't have a parent, so in that case, just put the
            // default output location inside the project dir to at least avoid a crash.
            val dir = ctrl.projectDir.toAbsolutePath().let { it.parent ?: it }
            val filename = ctrl.projectDir.fileName.pathString + spreadsheetNameWidget.value.map { " $it" }.orElse("")
            singleFileWidget.value =
                (singleFileWidget.value.parent ?: dir).resolve("$filename.${formatWidget.value.defaultFileExt}")
            seqDirWidget.value = (seqDirWidget.value.parent ?: dir).resolve("$filename Render")
            // Also populate the page number options.
            pushPageIdxOptions(reset = true)
        } else if (widget == formatWidget) {
            val format = formatWidget.value
            val wholePage = format in WHOLE_PAGE_FORMATS
            pageIndicesWidget.isVisible = wholePage
            firstPageIdxWidget.isVisible = !wholePage
            lastPageIdxWidget.isVisible = !wholePage
            val singleExt = format.fileExts.singleOrNull()
            val assName = if (singleExt != null) l10n("ui.deliverConfig.singleFileTypeExt", singleExt.uppercase()) else
                l10n("ui.deliverConfig.singleFileTypeVideo")
            val fileExtAssortment = FileExtAssortment(assName, format.fileExts.sorted(), format.defaultFileExt)
            singleFileWidget.fileExtAssortment = fileExtAssortment
            seqFilenameSuffixWidget.fileExtAssortment = fileExtAssortment
            pushFormatPropertyOptions(format.defaultConfig, formatChanged = true)
        } else if (widget != pageIndicesWidget && widget != firstPageIdxWidget && widget != lastPageIdxWidget &&
            widget != singleFileWidget && widget != seqDirWidget && widget != seqFilenameSuffixWidget
        )
            currentConfig(ignoreVolatile = true)?.let { pushFormatPropertyOptions(it, formatChanged = false) }
        disableOnChange = false

        super.onChange(widget)

        if (widget == firstPageIdxWidget || widget == lastPageIdxWidget || widget == formatWidget) {
            // Show an error when the selected page range is empty.
            val notice = if (firstPageIdxWidget.isVisible && firstPageIdxWidget.items.isNotEmpty() &&
                firstPageIdxWidget.value > lastPageIdxWidget.value
            ) Notice(Severity.ERROR, l10n("ui.deliverConfig.pagesEmpty")) else null
            spreadsheetAndPagesFormRow.noticeOverride = notice
            firstPageIdxWidget.setSeverity(-1, notice?.severity)
            lastPageIdxWidget.setSeverity(-1, notice?.severity)
        }

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
        val config = currentConfig() ?: return false
        val resMult = 2.0.pow(config.getOrDefault(RESOLUTION_SCALING_LOG2))
        val fpsMult = config.getOrDefault(FPS_SCALING)
        val scan = config.getOrDefault(SCAN)
        val primaries = config.getOrDefault(PRIMARIES)
        val transfer = config.getOrDefault(TRANSFER)

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
            if (RESOLUTION_SCALING_LOG2 !in config) "\u2014" else
                "$scaledWidth \u00D7 $scaledHeight"
        panel.specsLabels[1].text =
            if (FPS_SCALING !in config) "\u2014" else
                decFmt.format(scaledFPS) + if (scan == Scan.PROGRESSIVE) "p" else "i"
        panel.specsLabels[2].text =
            if (PRIMARIES !in config) "\u2014" else "$primaries / $transfer"
        panel.specsLabels[3].text =
            if (FPS_SCALING !in config || scrollSpeeds.isEmpty()) "\u2014" else {
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
        if (RESOLUTION_SCALING_LOG2 in config) {
            if (scaledWidth % format.widthMod != 0)
                error(l10n("ui.delivery.issues.widthMod", forLabel, format.widthMod))
            if (scaledHeight % format.heightMod != 0)
                error(l10n("ui.delivery.issues.heightMod", forLabel, format.heightMod))
            if (format.minWidth != null && scaledWidth < format.minWidth)
                error(l10n("ui.delivery.issues.minWidth", forLabel, format.minWidth))
            if (format.minHeight != null && scaledHeight < format.minHeight)
                error(l10n("ui.delivery.issues.minHeight", forLabel, format.minHeight))
        }
        // Check for fractional scroll speeds.
        if (FPS_SCALING in config) {
            if (scrollSpeeds.values.any { s2 -> floor(s2) != s2 })
                warn(l10n("ui.delivery.issues.fractionalFrameShift"))
            if (scan != Scan.PROGRESSIVE && scrollSpeeds.values.any { s2 -> floor(s2 / 2.0) != s2 / 2.0 })
                warn(l10n("ui.delivery.issues.fractionalFieldShift"))
        }

        return !err
    }

    private fun pushPageIdxOptions(reset: Boolean) {
        val pageIndices = selectedDrawnCredits?.drawnPages?.indices?.toList() ?: emptyList()
        val pageCountChanged = pageIndices.size != lastPageIdxWidget.items.size
        pageIndicesWidget.items = pageIndices
        firstPageIdxWidget.items = pageIndices
        lastPageIdxWidget.items = pageIndices
        if (pageIndices.isNotEmpty() && (reset || pageCountChanged)) {
            pageIndicesWidget.value = emptyList()
            firstPageIdxWidget.value = pageIndices.first()
            lastPageIdxWidget.value = pageIndices.last()
        }
    }

    private fun pushFormatPropertyOptions(config: Config, formatChanged: Boolean) {
        pushFormatPropertyOptions(profilePropertyFor(config), profileWidget, config, formatChanged)
        pushFormatPropertyOptions(TRANSPARENCY, transparencyWidget, config, formatChanged)
        resolutionMultWidget.isEnabled = RESOLUTION_SCALING_LOG2 in config
        pushFormatPropertyOptions(RESOLUTION_SCALING_LOG2, resolutionMultWidget, config, formatChanged)
        fpsMultWidget.isEnabled = FPS_SCALING in config
        pushFormatPropertyOptions(FPS_SCALING, fpsMultWidget, config, formatChanged)
        pushFormatPropertyOptions(DEPTH, depthWidget, config, formatChanged)
        pushFormatPropertyOptions(SCAN, scanWidget, config, formatChanged)
        pushFormatPropertyOptions(PRIMARIES, primariesWidget, config, formatChanged)
        pushFormatPropertyOptions(TRANSFER, transferWidget, config, formatChanged)
        pushFormatPropertyOptions(YUV, yuvWidget, config, formatChanged)
        hdrWidget.isVisible = HDR in config
    }

    private fun <T : Any, W> pushFormatPropertyOptions(
        property: Property<T>?, widget: W, config: Config, formatChanged: Boolean
    ) where W : Widget<in T>, W : Choice<in T> {
        val format = formatWidget.value
        val items = property?.let { format.options(it, config, VOLATILE_PROPERTIES).toList() }.orEmpty()
        widget.items = items
        if (property != null && (formatChanged || property !in VOLATILE_PROPERTIES) && property in config)
            widget.value = config[property]
        widget.isEnabled = items.size > 1
    }

    private fun profilePropertyFor(config: Config) = when {
        TIFF_COMPRESSION in config -> TIFF_COMPRESSION
        DPX_COMPRESSION in config -> DPX_COMPRESSION
        EXR_COMPRESSION in config -> EXR_COMPRESSION
        PRORES_PROFILE in config -> PRORES_PROFILE
        DNXHR_PROFILE in config -> DNXHR_PROFILE
        CINEFORM_PROFILE in config -> CINEFORM_PROFILE
        PDF_PROFILE in config -> PDF_PROFILE
        else -> null
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
            val format = formatWidget.value
            val config = currentConfig() ?: return
            val styling = drawnProject.project.styling

            val wholePage = format in WHOLE_PAGE_FORMATS
            val drawnPages = drawnCredits.drawnPages
            val pageIndices =
                if (wholePage) pageIndicesWidget.value.ifEmpty { drawnPages.indices.toList() }
                else (firstPageIdxWidget.value..lastPageIdxWidget.value).toList()
            val pageDefImages = drawnPages.filterIndexed { idx, _ -> idx in pageIndices }.map(DrawnPage::defImage)
            val video = if (wholePage) null else drawnCredits.video.sub(
                // Special cases for the first and large image ensure that surrounding black frames are retained.
                if (0 in pageIndices) null else pageDefImages.first(),
                if (drawnPages.lastIndex in pageIndices) null else pageDefImages.last()
            )

            val fileOrDir = (if (format.fileSeq) seqDirWidget.value else singleFileWidget.value).normalize()

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

            val filenameHashPattern = fileOrDir.name + seqFilenameSuffixWidget.value
            val filenamePattern = filenameHashPattern.replace(Regex("#+")) { match -> "%0${match.value.length}d" }

            val renderJob = format.createRenderJob(config, styling, pageDefImages, video, fileOrDir, filenamePattern)

            val destination = if (!format.fileSeq) fileOrDir.pathString else
                fileOrDir.resolve(filenameHashPattern).pathString

            ctrl.deliveryDialog.panel.renderQueuePanel.addRenderJobToQueue(renderJob, format.label, destination)
        }
    }

    private val selectedDrawnCredits: DrawnCredits?
        get() = spreadsheetNameWidget.value.getOrNull()?.let { selName ->
            drawnProject?.drawnCredits?.find { it.credits.spreadsheetName == selName }
        }

    private fun currentConfig(ignoreVolatile: Boolean = false): Config? {
        val format = formatWidget.value
        val lookup = Config.Lookup()
        if (profileWidget.items.isNotEmpty())
            when (val value = profileWidget.value) {
                is TIFF.Compression -> lookup[TIFF_COMPRESSION] = value
                is DPX.Compression -> lookup[DPX_COMPRESSION] = value
                is EXR.Compression -> lookup[EXR_COMPRESSION] = value
                is ProResProfile -> lookup[PRORES_PROFILE] = value
                is DNxHRProfile -> lookup[DNXHR_PROFILE] = value
                is CineFormProfile -> lookup[CINEFORM_PROFILE] = value
                is PDFProfile -> lookup[PDF_PROFILE] = value
            }
        if (transparencyWidget.items.isNotEmpty())
            lookup[TRANSPARENCY] = transparencyWidget.value
        if (resolutionMultWidget.items.isNotEmpty())
            lookup[RESOLUTION_SCALING_LOG2] = resolutionMultWidget.value
        if (fpsMultWidget.items.isNotEmpty())
            lookup[FPS_SCALING] = fpsMultWidget.value
        if (depthWidget.items.isNotEmpty())
            lookup[DEPTH] = depthWidget.value
        if (scanWidget.items.isNotEmpty())
            lookup[SCAN] = scanWidget.value
        if (primariesWidget.items.isNotEmpty())
            lookup[PRIMARIES] = primariesWidget.value
        if (transferWidget.items.isNotEmpty())
            lookup[TRANSFER] = transferWidget.value
        if (yuvWidget.items.isNotEmpty())
            lookup[YUV] = yuvWidget.value
        lookup[HDR] = hdrWidget.value
        if (ignoreVolatile)
            lookup -= VOLATILE_PROPERTIES
        return lookup.findConfig(format)
    }

    fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject

        // Filter out credits which have 0 runtime, as the renderer is probably not expecting this case.
        spreadsheetNameWidget.items =
            drawnProject.drawnCredits.filter { it.video.numFrames > 0 }.map { it.credits.spreadsheetName }

        // Update the page number options.
        pushPageIdxOptions(reset = false)

        // Re-verify the resolution multiplier because with the update, the page scroll speeds might have changed.
        onChange(resolutionMultWidget)
    }

}
