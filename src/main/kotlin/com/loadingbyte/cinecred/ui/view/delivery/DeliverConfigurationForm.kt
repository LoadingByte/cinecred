package com.loadingbyte.cinecred.ui.view.delivery

import com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT
import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nQuoted
import com.loadingbyte.cinecred.common.toPathSafely
import com.loadingbyte.cinecred.delivery.ImageSequenceRenderJob
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.RenderFormat.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.CINEFORM_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DNXHR_PROFILE
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DPX_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.EXR_COMPRESSION
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.GENERIC_PROFILE
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
import com.loadingbyte.cinecred.delivery.VideoContainerRenderJob
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.Bitmap.Scan
import com.loadingbyte.cinecred.imaging.BitmapWriter.*
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate.Placeholder
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.*
import java.awt.Dimension
import java.io.File
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.*
import javax.swing.*
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.jvm.optionals.getOrNull


class DeliverConfigurationForm(private val deliveryCtrl: DeliveryCtrlComms) :
    EasyForm(insets = "dialog", noticeArea = true, constLabelWidth = false), DeliveryViewComms {

    companion object {

        private val VOLATILE_PROPERTIES: Set<Property<*>> = hashSetOf(DEPTH, YUV)

        val RenderFormatCategory.label
            get() = when (this) {
                RenderFormatCategory.WHOLE_PAGE -> l10n("ui.deliverConfig.wholePageFormat.short")
                RenderFormatCategory.VIDEO -> l10n("ui.deliverConfig.videoFormat")
                RenderFormatCategory.TAPE_TIMELINE -> l10n("ui.deliverConfig.timelineFormat.short")
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

    private val creditsIdWidget =
        OptionalComboBoxWidget(
            CreditsId::class.java, emptyList(), widthSpec = WidthSpec.FIT,
            toString = { creditsId -> "${creditsId.fileName} \u2192 ${creditsId.spreadsheetName}" }
        )

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
            listOf(creditsIdWidget, pageIndicesWidget, firstPageIdxWidget, lastPageIdxWidget),
            labels = listOf(null, l10n("ui.deliverConfig.pages"), l10n("ui.deliverConfig.pages"), "\u2013"),
            gaps = listOf(null, null, "rel")
        )
    ).also(::addFormRow)

    private val formatWidget = addWidget(
        l10n("ui.deliverConfig.format"),
        ComboBoxWidget(
            RenderFormat::class.java,
            items = RenderFormatCategory.WHOLE_PAGE.formats +
                    RenderFormatCategory.VIDEO.formats +
                    RenderFormatCategory.TAPE_TIMELINE.formats,
            widthSpec = WidthSpec.WIDER, scrollbar = false,
            toString = { format ->
                var fullLabel = format.label
                format.auxLabel?.let { fullLabel += " ($it)" }
                val label = when (format) {
                    in ImageSequenceRenderJob.FORMATS -> l10n("ui.deliverConfig.imageSequenceFormat", fullLabel)
                    else -> fullLabel
                }
                "${RenderFormatCategory.of(format).label}  \u2013  $label"
            },
            // Use a custom render that shows category headers.
            rendererDecorator = object : AbstractComboBoxWidget.RendererDecorator {
                override fun <I> decorate(renderer: ListCellRenderer<I>) = LabeledListCellRenderer(renderer) { index ->
                    when (index) {
                        0 -> listOf(l10n("ui.deliverConfig.wholePageFormat"))
                        RenderFormatCategory.WHOLE_PAGE.formats.size -> listOf(l10n("ui.deliverConfig.videoFormat"))
                        RenderFormatCategory.WHOLE_PAGE.formats.size + RenderFormatCategory.VIDEO.formats.size ->
                            listOf(l10n("ui.deliverConfig.timelineFormat"))
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
                    is GenericProfile -> l10n("ui.deliverConfig.profile.generic.$prof")
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

    private val destinationWidget = DestinationWidget(deliveryCtrl)
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
                labels = listOf(null, l10n("resolution"))
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
                labels = listOf(null, l10n("scan"), l10n("bitDepth"))
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
        deliveryCtrl.registerView(this)

        // Get the form into a reasonable state even before the drawn project arrives.
        onChange(formatWidget)
    }

    override fun onChange(widget: Widget<*>) {
        if (disableOnChange)
            return

        disableOnChange = true
        if (widget == creditsIdWidget) {
            // Notify the destination widget.
            destinationWidget.creditsId = creditsIdWidget.value.getOrNull()
        } else if (widget == formatWidget) {
            val format = formatWidget.value
            val wholePage = format in RenderFormatCategory.WHOLE_PAGE.formats
            pageIndicesWidget.isVisible = wholePage
            firstPageIdxWidget.isVisible = !wholePage
            lastPageIdxWidget.isVisible = !wholePage
            destinationWidget.format = format
            pushFormatPropertyOptions(format.defaultConfig, formatChanged = true)
        } else if (widget == pageIndicesWidget || widget == firstPageIdxWidget || widget == lastPageIdxWidget) {
            destinationWidget.extremePageIndices = when {
                formatWidget.value in RenderFormatCategory.WHOLE_PAGE.formats -> pageIndicesWidget.value.let {
                    if (it.isEmpty()) Pair(0, pageIndicesWidget.items.lastOrNull() ?: 0)
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

        disableOnChange = true
        deliveryCtrl.onChangeForm(
            creditsIdWidget.value.getOrNull(),
            widget == creditsIdWidget,
            formatWidget.value,
            currentConfig(),
            currentSliders(),
            currentPageIndices(),
            isErrorFree
        )
        disableOnChange = false
    }

    private fun pushFormatPropertyOptions(config: Config, formatChanged: Boolean) {
        pushFormatPropertyOptions(profilePropertyFor(config), profileWidget, config, formatChanged)
        pushFormatPropertyOptions(TRANSPARENCY, transparencyWidget, config, formatChanged)
        pushFormatPropertyOptions(SPATIAL_SCALING_LOG2, spatialScalingWidget, config, formatChanged)
        resolutionWidget.isEnabled = formatWidget.value in RenderFormatCategory.VIDEO.formats
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
        GENERIC_PROFILE in config -> GENERIC_PROFILE
        PRORES_PROFILE in config -> PRORES_PROFILE
        DNXHR_PROFILE in config -> DNXHR_PROFILE
        CINEFORM_PROFILE in config -> CINEFORM_PROFILE
        PDF_PROFILE in config -> PDF_PROFILE
        else -> null
    }

    private fun currentConfig(ignoreVolatile: Boolean = false): Config? {
        val format = formatWidget.value
        val lookup = Config.Lookup()
        if (profileWidget.items.isNotEmpty())
            when (val value = profileWidget.value) {
                is TIFF.Compression -> lookup[TIFF_COMPRESSION] = value
                is DPX.Compression -> lookup[DPX_COMPRESSION] = value
                is EXR.Compression -> lookup[EXR_COMPRESSION] = value
                is GenericProfile -> lookup[GENERIC_PROFILE] = value
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

    private fun currentSliders() = Sliders(
        if (formatWidget.value in RenderFormatCategory.VIDEO.formats) resolutionWidget.value.getOrNull() else null
    )

    private fun currentPageIndices() = when {
        formatWidget.value in RenderFormatCategory.WHOLE_PAGE.formats ->
            pageIndicesWidget.value.ifEmpty { pageIndicesWidget.items }
        firstPageIdxWidget.items.isEmpty() ->
            pageIndicesWidget.items
        else ->
            (firstPageIdxWidget.value..lastPageIdxWidget.value).toList()
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setSpecs(specs: DeliverySpecs) {
        destinationWidget.specs = specs
    }

    override fun setCreditsIds(creditsIds: List<CreditsId>): CreditsId? {
        creditsIdWidget.items = creditsIds
        return creditsIdWidget.value.getOrNull()
    }

    override fun setSelectedCreditsId(creditsId: CreditsId) {
        creditsIdWidget.value = Optional.of(creditsId)
    }

    override fun setPageIdxOptions(pageIndices: List<Int>, reset: Boolean) {
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

    override fun refreshScrollSpeeds() {
        onChange(spatialScalingWidget)
    }

    override fun setDeliveryDestTemplates(templates: List<DeliveryDestTemplate>) {
        destinationWidget.updateTemplates(templates)
    }

    override fun triggerAddRenderJobToQueue() {
        val format = formatWidget.value
        deliveryCtrl.addRenderJobToQueue(
            creditsIdWidget.value.getOrNull() ?: return,
            format,
            currentConfig() ?: return,
            currentSliders(),
            currentPageIndices(),
            destinationWidget.value
        )
    }


    private class DestinationWidget(private val deliveryCtrl: DeliveryCtrlComms) :
        AbstractWidget<RenderJobDestination>() {

        private val bundledTemplates = listOf(
            listOf(Placeholder.PROJECT),
            listOf(Placeholder.PROJECT, Placeholder.SPREADSHEET),
            listOf(Placeholder.PROJECT, Placeholder.CREDITS_FILENAME),
            listOf(Placeholder.PROJECT, Placeholder.CREDITS_FILENAME, Placeholder.SPREADSHEET),
        ).map { placeholders ->
            DeliveryDestTemplate(
                UUID.randomUUID(),
                placeholders.joinToString(" + ", transform = Placeholder::l10nTag),
                placeholders.joinToString(" ", transform = Placeholder::enumTagBraces)
            )
        }

        private val templateBtn = JButton(TEMPLATE_ICON).apply { toolTipText = l10n("template") }
        val templateMenu = DropdownPopupMenu(templateBtn).apply {
            addMouseListenerTo(templateBtn)
            addKeyListenerTo(templateBtn)
        }

        private val pathWidget = TextWidget(widthSpec = WidthSpec.NONE).apply {
            value = deliveryCtrl.getProjectDir().absolutePathString()
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
                        if (item == null && selectedTemplate != null) {
                            prevNonCustomPath = pathWidget.value
                            pathWidget.value = value.fileOrDir.pathString
                        } else if (item != null && selectedTemplate == null)
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
                addActionListener { deliveryCtrl.showDeliveryDestTemplateCreation() }
            })
            templateMenu.pack()
        }

        var creditsId: CreditsId? = null
            set(creditsId) {
                field = creditsId
                refreshLabels()
            }

        var extremePageIndices = Pair(0, 0)
            set(extremePageIndices) {
                field = extremePageIndices
                refreshLabels()
            }

        var format = RenderFormatCategory.WHOLE_PAGE.formats.first()
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

        override var value: RenderJobDestination
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
                return RenderJobDestination(path, filenameHashPattern)
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
                    Placeholder.PROJECT -> deliveryCtrl.getProjectName()
                    Placeholder.CREDITS_FILENAME ->
                        SPREADSHEET_FORMATS.fold(creditsId?.fileName ?: "") { f, s -> f.removeSuffix("." + s.fileExt) }
                    Placeholder.SPREADSHEET -> creditsId?.spreadsheetName ?: ""
                    Placeholder.FIRST_PAGE -> "%02d".format(extremePageIndices.first + 1)
                    Placeholder.LAST_PAGE -> "%02d".format(extremePageIndices.second + 1)
                    Placeholder.FORMAT_CATEGORY -> RenderFormatCategory.of(format).label
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
