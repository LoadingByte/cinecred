package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.isAccessibleDirectory
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.roundingDiv
import com.loadingbyte.cinecred.delivery.MAX_RENDER_PROGRESS
import com.loadingbyte.cinecred.delivery.RenderFormat
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.DEPTH
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.PRIMARIES
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SPATIAL_SCALING_LOG2
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSFER
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.TRANSPARENCY
import com.loadingbyte.cinecred.delivery.RenderFormat.Transparency
import com.loadingbyte.cinecred.delivery.RenderQueue
import com.loadingbyte.cinecred.drawer.DrawnPage
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.Bitmap.Scan
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.tryRequestUserAttentionInTaskbar
import com.loadingbyte.cinecred.ui.helper.trySetTaskbarIconBadge
import com.loadingbyte.cinecred.ui.helper.trySetTaskbarProgress
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt


class DeliveryCtrl(private val projectCtrl: ProjectController) : DeliveryCtrlComms {

    private val views = mutableListOf<DeliveryViewComms>()

    private val category = projectCtrl.projectDir

    // Supplied by other controllers or the UI.
    private var drawnProject: DrawnProject? = null
    private var previewCreditsId: CreditsId? = null
    private var visible = false
    private var rendering = false

    private var specs = DeliverySpecs.UNKNOWN

    private val hasUnfinishedRenderJobs: Boolean
        get() = RenderQueue.hasUnfinishedJobs(category)

    private fun tryUpdateTaskbarBadge() {
        trySetTaskbarIconBadge(RenderQueue.getNumberOfRemainingJobs())
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun registerView(view: DeliveryViewComms) {
        views += view
    }

    override fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject

        val creditsIds = drawnProject.drawnCreditsBooks.flatMap { drCreditsBook ->
            drCreditsBook.drawnCredits
                // Filter out credits which have 0 runtime, as the renderer is probably not expecting this case.
                .filter { drCredits -> drCredits.video.numFrames > 0 }
                .map { drCredits -> CreditsId(drCreditsBook.creditsBook.fileName, drCredits.credits.spreadsheetName) }
        }
        val creditsId = views.firstNotNullOfOrNull { view -> view.setCreditsIds(creditsIds) }

        val pageIndices = drawnProject.drawnCreditsBooks.find { it.creditsBook.fileName == creditsId?.fileName }
            ?.drawnCredits?.find { it.credits.spreadsheetName == creditsId?.spreadsheetName }
            ?.run { credits.pages.indices.toList() } ?: emptyList()
        for (view in views) view.setPageIdxOptions(pageIndices, reset = false)

        for (view in views) view.refreshScrollSpeeds()
    }

    override fun onTryCloseProject(force: Boolean): Boolean {
        if (!force && rendering && hasUnfinishedRenderJobs && views.none(DeliveryViewComms::showCloseDespiteRenderingQuestion))
            return false
        RenderQueue.cancelAllJobs(category)
        return true
    }

    override fun getProjectName() = projectCtrl.projectName
    override fun getProjectDir() = projectCtrl.projectDir

    override fun setPreviewCreditsId(creditsId: CreditsId?) {
        previewCreditsId = creditsId
    }

    override fun onShowOrHide(visible: Boolean) {
        if (this.visible == visible)
            return
        this.visible = visible
        if (visible)
            previewCreditsId?.let { creditsId -> for (view in views) view.setSelectedCreditsId(creditsId) }
    }

    override fun setDeliveryDestTemplates(templates: List<DeliveryDestTemplate>) {
        for (view in views) view.setDeliveryDestTemplates(templates)
    }

    override fun showDeliveryDestTemplateCreation() {
        projectCtrl.masterCtrl.showDeliveryDestTemplateCreation()
    }

    // Update the specs labels and determine whether the specs are valid.
    // Then disable the add button if there are errors in the form or the specs are invalid.
    override fun onChangeForm(
        creditsId: CreditsId?,
        creditsIdChanged: Boolean,
        format: RenderFormat,
        config: RenderFormat.Config?,
        sliders: RenderFormat.Sliders,
        isErrorFree: Boolean
    ) {
        val project = drawnProject?.project
        val credits = drawnProject?.drawnCreditsBooks?.find { it.creditsBook.fileName == creditsId?.fileName }
            ?.drawnCredits?.find { it.credits.spreadsheetName == creditsId?.spreadsheetName }?.credits

        if (creditsIdChanged) {
            val pageIndices = credits?.run { pages.indices.toList() } ?: emptyList()
            for (view in views) view.setPageIdxOptions(pageIndices, reset = true)
        }

        if (project == null || credits == null || config == null) {
            for (view in views) view.setCanAddToRenderQueue(false)
            return
        }

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
        specs = DeliverySpecs(
            depth = config.getOrDefault(DEPTH),
            width = scaledWidth,
            height = scaledHeight,
            fps = specsFPS,
            scan = specsScan,
            channels = when (config.getOrDefault(TRANSPARENCY)) {
                Transparency.GROUNDED -> "RGB"
                Transparency.TRANSPARENT -> "RGBA"
                Transparency.MATTE -> "A"
            },
            primaries = primaries.name,
            transfer = transfer.name,
            optResolution = if (SPATIAL_SCALING_LOG2 !in config) null else "$scaledWidth \u00D7 $scaledHeight",
            optFPSAndScan = if (FPS_SCALING !in config) null else specsFPS + specsScan,
            optColorSpace = if (PRIMARIES !in config) null else "$primaries / $transfer",
            optScrollSpeeds = if (FPS_SCALING !in config || scrollSpeeds.isEmpty()) null else {
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
        )

        for (view in views) view.setSpecs(specs)

        // Clear all previous issues and create new ones if applicable.
        for (view in views) view.clearIssues()
        var err = false
        fun warn(msg: String) = views.forEach { view -> view.addIssue(Severity.WARN, msg) }
        fun error(msg: String) = views.forEach { view -> view.addIssue(Severity.ERROR, msg) }.also { err = true }
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

        val can = !err && isErrorFree
        for (view in views) view.setCanAddToRenderQueue(can)
    }

    override fun onClickAddRenderJobToQueue() {
        for (view in views) view.triggerAddRenderJobToQueue()
    }

    override fun addRenderJobToQueue(
        creditsId: CreditsId,
        format: RenderFormat,
        config: RenderFormat.Config,
        sliders: RenderFormat.Sliders,
        pageIndices: List<Int>,
        destination: RenderJobDestination
    ) {
        if (!visible)
            return

        val drawnProject = drawnProject
        val drawnCredits = drawnProject?.drawnCreditsBooks?.find { it.creditsBook.fileName == creditsId.fileName }
            ?.drawnCredits?.find { it.credits.spreadsheetName == creditsId.spreadsheetName }
        if (drawnProject == null || drawnCredits == null) {
            for (view in views) view.showErroneousProjectMessage()
            return
        }

        val styling = drawnProject.project.styling

        val wholePage = format in RenderFormatCategory.WHOLE_PAGE.formats
        val drawnPages = drawnCredits.drawnPages
        val pageDefImages = drawnPages.filterIndexed { idx, _ -> idx in pageIndices }.map(DrawnPage::defImage)
        val video = if (wholePage) null else drawnCredits.video.sub(
            // Special cases for the first and large image ensure that surrounding black frames are retained.
            if (0 in pageIndices) null else pageDefImages.first(),
            if (drawnPages.lastIndex in pageIndices) null else pageDefImages.last()
        )

        val fileOrDir = destination.fileOrDir

        // If there is any issue with the output file or folder, inform the user and abort if necessary.
        if (format.fileSeq) {
            if (fileOrDir.exists() && !fileOrDir.isAccessibleDirectory()) {
                for (view in views) view.showNotADirMessage(fileOrDir)
                return
            } else if (fileOrDir == projectCtrl.projectDir) {
                for (view in views) view.showOverwriteProjectDirMessage()
                return
            } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                if (views.none { view -> view.showOverwriteSeqDirReusedQuestion(fileOrDir) })
                    return
            } else if (fileOrDir.isAccessibleDirectory(thatContainsNonHiddenFiles = true))
                if (views.none { view -> view.showOverwriteSeqDirNonEmptyQuestion(fileOrDir) })
                    return
        } else {
            if (fileOrDir.isDirectory()) {
                for (view in views) view.showNotAFileMessage(fileOrDir)
                return
            } else if (projectCtrl.isCreditsFile(fileOrDir)) {
                for (view in views) view.showOverwriteCreditsFileMessage(fileOrDir)
                return
            } else if (RenderQueue.isRenderedFileOfRemainingJob(fileOrDir)) {
                if (views.none { view -> view.showOverwriteSingleFileReusedQuestion(fileOrDir) })
                    return
            } else if (fileOrDir.exists())
                if (views.none { view -> view.showOverwriteSingleFileExistsQuestion(fileOrDir) })
                    return
        }

        val filenameHashPattern = destination.filenameHashPattern ?: ""
        val filenamePattern = filenameHashPattern.replace(Regex("#+")) { match -> "%0${match.value.length}d" }

        val job = format.createRenderJob(
            config, sliders, styling, pageDefImages, video, fileOrDir, filenamePattern
        )
        val jobInfo = RenderJobInfo(
            job, creditsId,
            pages = when {
                pageIndices.size == drawnPages.size -> l10n("ui.deliverConfig.pagesAll")
                wholePage -> pageIndices.joinToString { "${it + 1}" }
                else -> "${pageIndices.first() + 1} \u2013 ${pageIndices.last() + 1}"
            },
            formatCategory = RenderFormatCategory.of(format),
            format.label, specs.optResolution, specs.optFPSAndScan, specs.optColorSpace,
            destination = if (!format.fileSeq) fileOrDir.pathString else
                fileOrDir.resolve(filenameHashPattern).pathString
        )

        for (view in views) view.addRenderJobToQueue(jobInfo, RenderJobStatus.Queued)
        tryUpdateTaskbarBadge()

        var startTime: Instant? = null
        var updatedTime: Instant? = null
        var prevProgress = -1

        fun setProgress(progress: Int) {
            if (progress == prevProgress)
                return
            prevProgress = progress
            var timeRemaining: Duration? = null
            updatedTime = Instant.now()
            // When we receive a progress notification for the first time, we record the current timestamp.
            if (startTime == null)
                startTime = updatedTime
            else
                timeRemaining = Duration.between(startTime, updatedTime)
                    .multipliedBy(MAX_RENDER_PROGRESS - progress.toLong()).dividedBy(progress.toLong())
            val jobStatus = RenderJobStatus.Running(progress, timeRemaining)
            for (view in views) view.setRenderJobStatus(jobInfo, jobStatus)
            trySetTaskbarProgress(projectCtrl.projectFrame, roundingDiv(jobStatus.progress * 100, MAX_RENDER_PROGRESS))
        }

        fun onFinish(exc: Exception?) {
            val jobStatus = if (exc != null) RenderJobStatus.Failed(exc) else
                RenderJobStatus.Done(startTime?.let { Duration.between(it, updatedTime) })
            for (view in views) view.setRenderJobStatus(jobInfo, jobStatus)
            tryUpdateTaskbarBadge()
            trySetTaskbarProgress(projectCtrl.projectFrame, -1)

            // If we just finished the last remaining job, deselect the toggle button and request user attention.
            if (!hasUnfinishedRenderJobs) {
                setRendering(false)
                tryRequestUserAttentionInTaskbar(projectCtrl.projectFrame)
            }
        }

        RenderQueue.submitJob(
            category, job,
            progressCallback = { SwingUtilities.invokeLater { setProgress(it) } },
            finishCallback = { SwingUtilities.invokeLater { onFinish(it) } }
        )
    }

    override fun onClickCancelRenderJob(jobInfo: RenderJobInfo) {
        RenderQueue.cancelJob(category, jobInfo.job)
        for (view in views) view.removeRenderJobFromQueue(jobInfo)
        tryUpdateTaskbarBadge()
    }

    override fun setRendering(rendering: Boolean) {
        if (rendering == this.rendering)
            return
        val rendering = rendering && hasUnfinishedRenderJobs
        this.rendering = rendering
        RenderQueue.setPaused(category, !rendering)
        for (view in views) view.setRendering(rendering)
    }

}
