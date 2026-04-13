package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.delivery.*
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.ui.DeliveryDestTemplate
import java.nio.file.Path
import java.time.Duration


interface DeliveryCtrlComms {

    fun registerView(view: DeliveryViewComms)
    fun updateProject(drawnProject: DrawnProject)
    fun onTryCloseProject(force: Boolean): Boolean
    fun getProjectName(): String
    fun getProjectDir(): Path
    fun setPreviewCreditsId(creditsId: CreditsId?)
    fun onShowOrHide(visible: Boolean)

    fun setDeliveryDestTemplates(templates: List<DeliveryDestTemplate>)
    fun showDeliveryDestTemplateCreation()
    fun onChangeForm(
        creditsId: CreditsId?,
        creditsIdChanged: Boolean,
        format: RenderFormat,
        config: RenderFormat.Config?,
        sliders: RenderFormat.Sliders,
        pageIndices: List<Int>,
        isErrorFree: Boolean
    )

    fun onClickAddRenderJobToQueue()
    fun addRenderJobToQueue(
        creditsId: CreditsId,
        format: RenderFormat,
        config: RenderFormat.Config,
        sliders: RenderFormat.Sliders,
        pageIndices: List<Int>,
        destination: RenderJobDestination
    )

    fun onClickCancelRenderJob(jobInfo: RenderJobInfo)
    fun setRendering(rendering: Boolean)

}


interface DeliveryViewComms {

    fun setSpecs(specs: DeliverySpecs) {}
    fun clearIssues() {}
    fun addIssue(severity: Severity, msg: String) {}
    fun setCanAddToRenderQueue(can: Boolean) {}
    fun setRendering(rendering: Boolean) {}

    fun setCreditsIds(creditsIds: List<CreditsId>): CreditsId? = null
    fun setSelectedCreditsId(creditsId: CreditsId) {}
    fun setPageIdxOptions(pageIndices: List<Int>, reset: Boolean) {}
    fun refreshScrollSpeeds() {}
    fun setDeliveryDestTemplates(templates: List<DeliveryDestTemplate>) {}
    fun triggerAddRenderJobToQueue() {}

    fun addRenderJobToQueue(jobInfo: RenderJobInfo, jobStatus: RenderJobStatus) {}
    fun setRenderJobStatus(jobInfo: RenderJobInfo, jobStatus: RenderJobStatus) {}
    fun removeRenderJobFromQueue(jobInfo: RenderJobInfo) {}

    fun showErroneousProjectMessage() {}
    fun showNotAFileMessage(path: Path) {}
    fun showNotADirMessage(path: Path) {}
    fun showOverwriteSeqDirReusedQuestion(dir: Path): Boolean = false
    fun showOverwriteSeqDirNonEmptyQuestion(dir: Path): Boolean = false
    fun showOverwriteSingleFileReusedQuestion(file: Path): Boolean = false
    fun showOverwriteSingleFileExistsQuestion(file: Path): Boolean = false
    fun showOverwriteProjectDirMessage() {}
    fun showOverwriteCreditsFileMessage(file: Path) {}
    fun showCloseDespiteRenderingQuestion(): Boolean = false

}


enum class RenderFormatCategory(val formats: List<RenderFormat>) {

    WHOLE_PAGE(WholePageSequenceRenderJob.FORMATS + WholePagePDFRenderJob.FORMATS),
    VIDEO(VideoContainerRenderJob.FORMATS + ImageSequenceRenderJob.FORMATS),
    TAPE_TIMELINE(TapeTimelineRenderJob.FORMATS);

    companion object {
        fun of(format: RenderFormat): RenderFormatCategory =
            RenderFormatCategory.entries.first { category -> format in category.formats }
    }

}


class DeliverySpecs(
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
    val optColorSpace: String?,
    val optScrollSpeeds: String?
) {
    companion object {
        val UNKNOWN = DeliverySpecs(0, 0, 0, "?", "?", "?", "?", "?", "?", "?", "?", "?")
    }
}


class RenderJobDestination(val fileOrDir: Path, val filenameHashPattern: String?)


class RenderJobInfo(
    val job: RenderJob,
    val creditsId: CreditsId,
    val pages: String,
    val formatCategory: RenderFormatCategory,
    val format: String,
    val resolution: String?,
    val fpsAndScan: String?,
    val colorSpace: String?,
    val destination: String
)


sealed interface RenderJobStatus {
    object Queued : RenderJobStatus
    class Running(val progress: Int, val timeRemaining: Duration?) : RenderJobStatus
    class Done(val timeTaken: Duration?) : RenderJobStatus
    class Failed(val exc: Exception) : RenderJobStatus
}
