package com.loadingbyte.cinecred.ui

import com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT
import com.loadingbyte.cinecred.common.*
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
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SPATIAL_SCALING_LOG2
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
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate.Placeholder
import com.loadingbyte.cinecred.ui.helper.*
import java.awt.Dimension
import java.io.File
import java.nio.file.Path
import java.text.DecimalFormat
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.*
import javax.swing.*
import javax.swing.JOptionPane.*
import kotlin.io.path.*
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

        val RenderFormat.categoryLabel
            get() = when (this) {
                in WHOLE_PAGE_FORMATS -> l10n("ui.deliverConfig.wholePageFormat.short")
                in VIDEO_FORMATS -> l10n("ui.deliverConfig.videoFormat")
                in TapeTimelineRenderJob.FORMATS -> l10n("ui.deliverConfig.timelineFormat.short")
                else -> throw IllegalArgumentException()
            }

    }

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedFormatWidget get() = formatWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedProfileWidget get() = profileWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedDestinationWidget: Widget<*> get() = destinationWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedDestinationWidgetTemplateMenu get() = destinationWidget.templateMenu
    @Deprecated("ENCAPSULATION LEAK") val leakedTransparencyWidget get() = transparencyWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedSpatialScalingWidget get() = spatialScalingWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedScanWidget get() = scanWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedPrimariesWidget get() = primariesWidget
    @Deprecated("ENCAPSULATION LEAK") val leakedTransferWidget get() = transferWidget
    // =========================================

    private var drawnProject: DrawnProject? = null
    private var specs = DeliverySpecs.UNKNOWN

    private val spreadsheetNameWidget =
        OptionalComboBoxWidget(String::class.java, emptyList(), widthSpec = WidthSpec.SQUEEZE)

    private val pageIndicesWidget =
        MultiComboBoxWidget<Int>(
            emptyList(), naturalOrder(), widthSpec = WidthSpec.SQUEEZE,
            toString = { "${it + 1}" }, placeholder = l10n("ui.deliverConfig.pagesAll")
        )

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
                var fullLabel = format.label
                format.auxLabel?.let { fullLabel += " ($it)" }
                val label = when (format) {
                    in ImageSequenceRenderJob.FORMATS -> l10n("ui.deliverConfig.imageSequenceFormat", fullLabel)
                    else -> fullLabel
                }
                val suffix = when (format) {
                    VideoContainerRenderJob.H264, VideoContainerRenderJob.H265 ->
                        "  \u2013  " + l10n("ui.deliverConfig.reducedQualityFormat")
                    else -> ""
                }
                "${format.categoryLabel}  \u2013  ${label}$suffix"
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

    private val destinationWidget = DestinationWidget(ctrl)
    private val destinationFormRow = FormRow(l10n("ui.deliverRenderQueue.destination"), destinationWidget)
        .also(::addFormRow)

    private val transparencyWidget = addWidget(
        l10n("ui.deliverConfig.transparency"),
        ComboBoxWidget(
            Transparency::class.java, emptyList(), widthSpec = WidthSpec.WIDER,
            toString = { l10n("ui.deliverConfig.transparency.$it") }
        )
    )

    private val spatialScalingWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, emptyList(), widthSpec = WidthSpec.LITTLE,
            toString = { if (it >= 0) "\u00D7 ${1 shl it}" else "\u00F7 ${1 shl -it}" }
        )

    private val resolutionWidget =
        OptionalResolutionWidget(nullLabel = l10n("automatic"), compactCustom = true)
            .apply { value = Optional.empty() }

    init {
        addWidget(
            l10n("ui.styling.layer.scaling"),
            UnionWidget(
                listOf(spatialScalingWidget, resolutionWidget),
                labels = listOf(null, l10n("ui.styling.global.resolution"))
            )
        )
    }

    private val fpsScalingWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, emptyList(), widthSpec = WidthSpec.LITTLE,
            toString = { "\u00D7 $it" }
        )

    private val scanWidget =
        ComboBoxWidget(
            Scan::class.java, emptyList(), widthSpec = WidthSpec.SQUEEZE,
            toString = { scan -> l10n("ui.deliverConfig.scan.$scan") }
        )

    private val depthWidget =
        ComboBoxWidget(
            Int::class.javaObjectType, emptyList(), widthSpec = WidthSpec.LITTLE,
            toString = Int::toString
        )

    init {
        addWidget(
            l10n("ui.styling.global.fps"),
            UnionWidget(
                listOf(fpsScalingWidget, scanWidget, depthWidget),
                labels = listOf(null, l10n("ui.deliverConfig.scan"), l10n("bitDepth"))
            )
        )
    }

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
        onChange(formatWidget)
    }

    override fun onChange(widget: Widget<*>) {
        if (disableOnChange)
            return

        disableOnChange = true
        if (widget == spreadsheetNameWidget) {
            // Populate the page number options.
            pushPageIdxOptions(reset = true)
            // Notify the destination widget.
            destinationWidget.spreadsheetName = spreadsheetNameWidget.value.getOrNull()
        } else if (widget == formatWidget) {
            val format = formatWidget.value
            val wholePage = format in WHOLE_PAGE_FORMATS
            pageIndicesWidget.isVisible = wholePage
            firstPageIdxWidget.isVisible = !wholePage
            lastPageIdxWidget.isVisible = !wholePage
            destinationWidget.format = format
            pushFormatPropertyOptions(format.defaultConfig, formatChanged = true)
        } else if (widget == pageIndicesWidget || widget == firstPageIdxWidget || widget == lastPageIdxWidget) {
            destinationWidget.extremePageIndices = when {
                formatWidget.value in WHOLE_PAGE_FORMATS -> pageIndicesWidget.value.let {
                    if (it.isEmpty()) Pair(0, selectedDrawnCredits?.drawnPages?.ifEmpty { null }?.lastIndex ?: 0)
                    else Pair(it.first(), it.last())
                }
                else -> Pair(firstPageIdxWidget.value, lastPageIdxWidget.value)
            }
        } else if (widget != destinationWidget) {
            currentConfig(ignoreVolatile = true)?.let { pushFormatPropertyOptions(it, formatChanged = false) }
        }
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

        if (widget == destinationWidget) {
            val value = destinationWidget.value

            // Show an error if the destination path is illegal.
            var notice = when {
                value.fileOrDir.pathString.isBlank() -> Notice(Severity.ERROR, l10n("blank"))
                !value.fileOrDir.isAbsolute -> Notice(Severity.ERROR, l10n("ui.deliverConfig.nonAbsolutePath"))
                else -> null
            }
            destinationWidget.setPathWidgetSeverity(notice?.severity)

            if (notice == null && value.filenameHashPattern != null) {
                // Show an error if the sequence filename suffix doesn't have exactly one ### hole.
                val numHoles = Regex("#+").findAll(value.filenameHashPattern).count()
                fun h() = l10nQuoted("###")
                if (numHoles == 0)
                    notice = Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenameSuffixMissesHole", h()))
                else if (numHoles > 1)
                    notice = Notice(Severity.ERROR, l10n("ui.deliverConfig.seqFilenameSuffixHasTooManyHoles", h()))
                destinationWidget.setSuffixWidgetSeverity(notice?.severity)
            } else
                destinationWidget.setSuffixWidgetSeverity(null)

            destinationFormRow.noticeOverride = notice
        }

        // Update the specs labels and determine whether the specs are valid.
        // Then disable the add button if there are errors in the form or the specs are invalid.
        // We need the null-safe access because this method might be called before everything is initialized.
        val dialog = ctrl.deliveryDialog as DeliveryDialog?
        if (dialog != null)
            dialog.panel.addButton.isEnabled = updateAndVerifySpecs(dialog.panel) && isErrorFree
    }

    private fun updateAndVerifySpecs(panel: DeliveryPanel): Boolean {
        val project = (drawnProject ?: return false).project
        val credits = (selectedDrawnCredits ?: return false).credits

        val format = formatWidget.value
        val config = currentConfig() ?: return false
        val sliders = currentSliders()
        val spatialScaling = 2.0.pow(config.getOrDefault(SPATIAL_SCALING_LOG2))
        val fpsScaling = config.getOrDefault(FPS_SCALING)
        val scan = config.getOrDefault(SCAN)
        val primaries = config.getOrDefault(PRIMARIES)
        val transfer = config.getOrDefault(TRANSFER)

        // Determine the scaled specs.
        val resolution = project.styling.global.resolution
        val scaledWidth = sliders.resolution?.widthPx ?: (spatialScaling * resolution.widthPx).roundToInt()
        val scaledHeight = sliders.resolution?.heightPx ?: (spatialScaling * resolution.heightPx).roundToInt()
        val scaledFPS = project.styling.global.fps.frac * fpsScaling
        val scrollSpeeds = credits.pages.asSequence()
            .flatMap { page -> page.stages }
            .filter { stage -> stage.style.behavior == PageBehavior.SCROLL }
            .map { stage -> stage.style.scrollPxPerFrame }
            .associateWith { speed -> speed * spatialScaling / fpsScaling }

        // Format the scaled specs.
        val decFmt = DecimalFormat("0.##")
        val specsFPS = decFmt.format(scaledFPS)
        val specsScan = if (scan == Scan.PROGRESSIVE) "p" else "i"
        val specsChannels = when (config.getOrDefault(TRANSPARENCY)) {
            Transparency.GROUNDED -> "RGB"
            Transparency.TRANSPARENT -> "RGBA"
            Transparency.MATTE -> "A"
        }
        specs = DeliverySpecs(
            depth = config.getOrDefault(DEPTH),
            width = scaledWidth,
            height = scaledHeight,
            fps = specsFPS,
            scan = specsScan,
            channels = specsChannels,
            primaries = primaries.name,
            transfer = transfer.name,
            optResolution = if (SPATIAL_SCALING_LOG2 !in config) null else "$scaledWidth \u00D7 $scaledHeight",
            optFPSAndScan = if (FPS_SCALING !in config) null else specsFPS + specsScan,
            optColorSpace = if (PRIMARIES !in config) null else "$primaries / $transfer"
        )

        // Display the scaled specs in the specs labels.
        panel.specsLabels[0].text = specs.optResolution ?: "\u2014"
        panel.specsLabels[1].text = specs.optFPSAndScan ?: "\u2014"
        panel.specsLabels[2].text = specs.optColorSpace ?: "\u2014"
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
        if (SPATIAL_SCALING_LOG2 in config) {
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
        // Check for popping extremal scroll stages due to an enlarged vertical resolution.
        if (sliders.resolution != null && scaledHeight > (spatialScaling * resolution.heightPx).roundToInt() &&
            credits.pages
                .flatMap { page -> listOf(page.stages.first(), page.stages.last()) }
                .any { stage -> stage.style.behavior == PageBehavior.SCROLL }
        )
            warn(l10n("ui.delivery.issues.poppingScrollPages"))

        // Notify the destination widget about the new specs.
        destinationWidget.specs = specs

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
        pushFormatPropertyOptions(SPATIAL_SCALING_LOG2, spatialScalingWidget, config, formatChanged)
        resolutionWidget.isEnabled = formatWidget.value in VIDEO_FORMATS
        pushFormatPropertyOptions(FPS_SCALING, fpsScalingWidget, config, formatChanged)
        pushFormatPropertyOptions(SCAN, scanWidget, config, formatChanged)
        pushFormatPropertyOptions(DEPTH, depthWidget, config, formatChanged)
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
            val sliders = currentSliders()
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

            val destinationValue = destinationWidget.value
            val fileOrDir = destinationValue.fileOrDir

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
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.file", l10nQuoted(fileOrDir)))
                    return
                } else if (fileOrDir == ctrl.projectDir) {
                    overwriteProjectDirDialog()
                    return
                } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirReused", l10nQuoted(fileOrDir))))
                        return
                } else if (fileOrDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true))
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.seqDirNonEmpty", l10nQuoted(fileOrDir))))
                        return
            } else {
                if (fileOrDir.isDirectory()) {
                    wrongFileTypeDialog(l10n("ui.deliverConfig.wrongFileType.dir", l10nQuoted(fileOrDir)))
                    return
                } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.singleFileReused", l10nQuoted(fileOrDir))))
                        return
                } else if (fileOrDir.exists())
                    if (!overwriteDialog(l10n("ui.deliverConfig.overwrite.singleFileExists", l10nQuoted(fileOrDir))))
                        return
            }

            val filenameHashPattern = destinationValue.filenameHashPattern ?: ""
            val filenamePattern = filenameHashPattern.replace(Regex("#+")) { match -> "%0${match.value.length}d" }

            val renderJob = format.createRenderJob(
                config, sliders, styling, pageDefImages, video, fileOrDir, filenamePattern
            )

            val info = DeliverRenderQueuePanel.RenderJobInfo(
                spreadsheet = spreadsheetNameWidget.value.get(),
                pages = when {
                    pageIndices.size == drawnPages.size -> l10n("ui.deliverConfig.pagesAll")
                    wholePage -> pageIndices.joinToString { "${it + 1}" }
                    else -> "${pageIndices.first() + 1} \u2013 ${pageIndices.last() + 1}"
                },
                format.categoryLabel, format.label, specs.optResolution, specs.optFPSAndScan, specs.optColorSpace,
                destination = if (!format.fileSeq) fileOrDir.pathString else
                    fileOrDir.resolve(filenameHashPattern).pathString
            )

            ctrl.deliveryDialog.panel.renderQueuePanel.addRenderJobToQueue(renderJob, info)
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
        if (spatialScalingWidget.items.isNotEmpty())
            lookup[SPATIAL_SCALING_LOG2] = spatialScalingWidget.value
        if (fpsScalingWidget.items.isNotEmpty())
            lookup[FPS_SCALING] = fpsScalingWidget.value
        if (scanWidget.items.isNotEmpty())
            lookup[SCAN] = scanWidget.value
        if (depthWidget.items.isNotEmpty())
            lookup[DEPTH] = depthWidget.value
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

    private fun currentSliders(): Sliders =
        Sliders(if (formatWidget.value in VIDEO_FORMATS) resolutionWidget.value.getOrNull() else null)

    fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject

        // Filter out credits which have 0 runtime, as the renderer is probably not expecting this case.
        spreadsheetNameWidget.items =
            drawnProject.drawnCredits.filter { it.video.numFrames > 0 }.map { it.credits.spreadsheetName }

        // Update the page number options.
        pushPageIdxOptions(reset = false)

        // Re-verify the spatial scaling because with the update, the page scroll speeds might have changed.
        onChange(spatialScalingWidget)
    }

    fun setSelectedSpreadsheetName(spreadsheetName: String) {
        spreadsheetNameWidget.value = Optional.of(spreadsheetName)
    }

    fun updateDeliveryDestTemplates(templates: List<DeliveryDestTemplate>) {
        destinationWidget.updateTemplates(templates)
    }


    private class DeliverySpecs(
        val depth: Int,
        val width: Int,
        val height: Int,
        val fps: String,
        val scan: String,
        val channels: String,
        val primaries: String,
        val transfer: String,
        val optResolution: String?,
        val optFPSAndScan: String?,
        val optColorSpace: String?
    ) {
        companion object {
            val UNKNOWN = DeliverySpecs(0, 0, 0, "?", "?", "?", "?", "?", "?", "?", "?")
        }
    }


    private class DestinationWidget(private val ctrl: ProjectController) : AbstractWidget<DestinationWidget.Value>() {

        class Value(val fileOrDir: Path, val filenameHashPattern: String?)

        private val bundledTemplates = listOf(
            DeliveryDestTemplate(
                UUID.randomUUID(),
                "${Placeholder.PROJECT.l10nTag()} + ${Placeholder.SPREADSHEET.l10nTag()}",
                "${Placeholder.PROJECT.enumTagBraces} ${Placeholder.SPREADSHEET.enumTagBraces}"
            ),
            DeliveryDestTemplate(
                UUID.randomUUID(), Placeholder.PROJECT.l10nTag(), Placeholder.PROJECT.enumTagBraces
            ),
            DeliveryDestTemplate(
                UUID.randomUUID(), Placeholder.SPREADSHEET.l10nTag(), Placeholder.SPREADSHEET.enumTagBraces
            )
        )

        private val templateBtn = JButton(TEMPLATE_ICON).apply { toolTipText = l10n("template") }
        val templateMenu = DropdownPopupMenu(templateBtn).apply {
            addMouseListenerTo(templateBtn)
            addKeyListenerTo(templateBtn)
        }

        private val pathWidget = TextWidget(widthSpec = WidthSpec.NONE).apply {
            value = ctrl.projectDir.absolutePathString()
        }

        private val browseBtn = JButton(FOLDER_ICON).apply { toolTipText = l10n("ui.form.browse") }
        private val wrapper = JLabel()
        private val label1 = JLabel()
        private val suffixWidget = TextWidget(widthSpec = WidthSpec.NONE).apply { value = ".#######" }
        private val label2 = JLabel()
        private val extWidget = ComboBoxWidget(String::class.java, emptyList(), widthSpec = WidthSpec.NONE)

        override val components = listOf(templateBtn) + pathWidget.components +
                wrapper + label1 + suffixWidget.components + label2 + extWidget.components
        override val constraints = listOf("sg dw") + pathWidget.constraints +
                "newline, sg dw" + "wmin ${JLabel(".").preferredSize.width}" + suffixWidget.constraints + "" +
                extWidget.constraints

        init {
            pathWidget.components[0].putClientProperty(TEXT_FIELD_TRAILING_COMPONENT, browseBtn)
            pathWidget.changeListeners.add {
                val path = pathWidget.value
                for (ext in extWidget.items)
                    if (path.endsWith(".$ext", ignoreCase = true)) {
                        pathWidget.value = path.dropLast(ext.length + 1)
                        extWidget.value = ext
                        break
                    }
                if (format.fileSeq)
                    refreshLabels()
            }

            browseBtn.addActionListener {
                val win = SwingUtilities.getWindowAncestor(browseBtn)
                if (selectedTemplate == null && !format.fileSeq) {
                    val exts = extWidget.items
                    val singleExt = exts.singleOrNull()
                    val filterName = when {
                        singleExt != null -> l10n("ui.deliverConfig.singleFileTypeExt", singleExt.uppercase())
                        else -> l10n("ui.deliverConfig.singleFileTypeVideo")
                    }
                    val initial = "${pathWidget.value}.${extWidget.value}".toPathSafely()?.normalize()
                    var selected = showFileDialog(win, open = false, filterName, exts, initial)?.absolutePathString()
                        ?: return@addActionListener
                    for (ext in exts)
                        if (selected.endsWith(".$ext", ignoreCase = true)) {
                            extWidget.value = ext
                            selected = selected.dropLast(ext.length + 1)
                            break
                        }
                    pathWidget.value = selected
                } else
                    showFolderDialog(win, pathWidget.value.toPathSafely()?.normalize())?.absolutePathString()
                        ?.let { pathWidget.value = it }
            }

            pathWidget.changeListeners.add { notifyChangeListeners() }
            suffixWidget.changeListeners.add { notifyChangeListeners() }
            extWidget.changeListeners.add { notifyChangeListeners() }
        }

        private var selectedTemplate: DeliveryDestTemplate? = bundledTemplates.first()
        private var prevNonCustomPath: String? = null

        fun updateTemplates(templates: List<DeliveryDestTemplate>) {
            // Evaluates to null (= custom) if the previously selected template has been removed.
            val newSel = (bundledTemplates + templates).find { it.uuid == selectedTemplate?.uuid }

            fun makeMenuItem(template: DeliveryDestTemplate?, label: String, icon: Icon) =
                object : DropdownPopupMenuCheckBoxItem<DeliveryDestTemplate?>(
                    templateMenu, template, label, icon, isSelected = template == newSel, closeOnMouseClick = true
                ) {
                    init {
                        if (isSelected)
                            onToggle()
                    }

                    override fun onToggle() {
                        if (!isSelected) {
                            isSelected = true
                            return
                        }
                        for (menuItem in templateMenu.components)
                            if (menuItem is DropdownPopupMenuCheckBoxItem<*> && menuItem !== this)
                                menuItem.isSelected = false
                        if (item == null) {
                            prevNonCustomPath = pathWidget.value
                            pathWidget.value = value.fileOrDir.pathString
                        } else if (selectedTemplate == null)
                            prevNonCustomPath?.let { pathWidget.value = it }
                        selectedTemplate = item
                        pathWidget.components[0].preferredSize = Dimension(if (item == null) 320 else 472, 0)
                        pathWidget.isVisible = item == null || !item.isAbsolute
                        wrapper.isVisible = item != null && !item.isAbsolute
                        refreshLabels()
                        notifyChangeListeners()
                    }
                }

            templateMenu.removeAll()
            templateMenu.add(makeMenuItem(null, l10n("custom"), GEAR_ICON))
            templateMenu.add(JSeparator())
            for (template in bundledTemplates)
                templateMenu.add(makeMenuItem(template, template.name, TEMPLATE_ICON))
            if (templates.isNotEmpty()) {
                templateMenu.add(JSeparator())
                for (template in templates)
                    templateMenu.add(makeMenuItem(template, template.name, TEMPLATE_ICON))
            }
            templateMenu.add(JSeparator())
            templateMenu.add(JMenuItem(l10n("ui.deliverConfig.destinationTemplate.add"), ARROW_RIGHT_ICON).apply {
                addActionListener { ctrl.masterCtrl.showDeliveryDestTemplateCreation() }
            })
            templateMenu.pack()
        }

        var spreadsheetName: String? = null
            set(spreadsheetName) {
                field = spreadsheetName
                refreshLabels()
            }

        var extremePageIndices = Pair(0, 0)
            set(extremePageIndices) {
                field = extremePageIndices
                refreshLabels()
            }

        var format = ALL_FORMATS.first()
            set(format) {
                field = format
                suffixWidget.isVisible = format.fileSeq
                label2.isVisible = format.fileSeq
                extWidget.items = format.fileExts.sorted()
                extWidget.value = format.defaultFileExt
                extWidget.isVisible = extWidget.items.size > 1
                refreshLabels()
            }

        var specs = DeliverySpecs.UNKNOWN
            set(specs) {
                field = specs
                refreshLabels()
            }

        init {
            updateTemplates(emptyList())
        }

        override var value: Value
            get() {
                val template = selectedTemplate
                val ext = extWidget.value
                var path = pathWidget.value.trim().toPathSafely() ?: Path("")
                if (template != null)
                    path = path.resolve(fillTemplate(template).trim())
                if (!format.fileSeq)
                    path = path.resolveSibling("${path.name}.$ext")
                path = path.normalize()
                val filenameHashPattern = if (format.fileSeq) "${path.name}${suffixWidget.value}.$ext" else null
                return Value(path, filenameHashPattern)
            }
            set(_) = throw UnsupportedOperationException()

        fun setPathWidgetSeverity(severity: Severity?) = pathWidget.setSeverity(-1, severity)
        fun setSuffixWidgetSeverity(severity: Severity?) = suffixWidget.setSeverity(-1, severity)

        private fun refreshLabels() {
            val label2Text = if (extWidget.items.size == 1) ".${extWidget.items.single()}" else "."
            val label1Text = buildString {
                selectedTemplate?.let { template ->
                    if (!template.isAbsolute)
                        append(File.separator)
                    append(fillTemplate(template))
                }
                if (format.fileSeq)
                    append(File.separator).append("\u2013''\u2013")
                else
                    append(label2Text)
            }
            label1.text = label1Text
            label1.toolTipText = label1Text
            if (format.fileSeq)
                label2.text = label2Text
        }

        private fun fillTemplate(template: DeliveryDestTemplate): String {
            val dt = ZonedDateTime.now()
            return template.fill { placeholder ->
                when (placeholder) {
                    Placeholder.PROJECT -> ctrl.projectName
                    Placeholder.SPREADSHEET -> spreadsheetName ?: ""
                    Placeholder.FIRST_PAGE -> "%02d".format(extremePageIndices.first + 1)
                    Placeholder.LAST_PAGE -> "%02d".format(extremePageIndices.second + 1)
                    Placeholder.FORMAT_CATEGORY -> format.categoryLabel
                    Placeholder.FORMAT -> format.label
                    Placeholder.BIT_DEPTH -> specs.depth.toString()
                    Placeholder.WIDTH -> specs.width.toString()
                    Placeholder.HEIGHT -> specs.height.toString()
                    Placeholder.FRAME_RATE -> specs.fps
                    Placeholder.SCAN -> specs.scan
                    Placeholder.CHANNELS -> specs.channels
                    Placeholder.GAMUT -> specs.primaries
                    Placeholder.EOTF -> specs.transfer
                    Placeholder.YEAR -> "%04d".format(dt.year)
                    Placeholder.MONTH -> "%02d".format(dt.monthValue)
                    Placeholder.DAY -> "%02d".format(dt.dayOfMonth)
                    Placeholder.HOUR -> "%02d".format(dt.hour)
                    Placeholder.MINUTE -> "%02d".format(dt.minute)
                    Placeholder.SECOND -> "%02d".format(dt.second)
                    Placeholder.TIME_ZONE -> dt.zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
            }
        }

    }

}
