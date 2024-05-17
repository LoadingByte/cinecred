package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.delivery.RenderFormat.Config
import com.loadingbyte.cinecred.delivery.RenderFormat.Config.Assortment.Companion.choice
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.FPS_SCALING
import com.loadingbyte.cinecred.delivery.RenderFormat.Property.Companion.SCAN
import com.loadingbyte.cinecred.imaging.Bitmap
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.imaging.DeferredVideo.TapeSpan
import com.loadingbyte.cinecred.project.Project
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.math.min


class TapeTimelineRenderJob private constructor(
    private val format: Format,
    private val config: Config,
    private val project: Project,
    private val video: DeferredVideo,
    private val file: Path
) : RenderJob {

    override val prefix: Path
        get() = file

    // When exporting a timeline for the interlaced case, we scale the deferred video's FPS by 2 and later divide that
    // out again. This is necessary because:
    //   - The VideoRenderJob does the same thing.
    //   - FPS scaling can slightly shift around parts of the sequence.
    //   - We want to provide field-accurate rec in and out points.
    private val extraFPSMul = if (config[SCAN] == Bitmap.Scan.PROGRESSIVE) 1 else 2

    override fun render(progressCallback: (Int) -> Unit) {
        val tapeSpans = video.copy(fpsScaling = config[FPS_SCALING] * extraFPSMul)
            .collectTapeSpans(listOf(TAPES))
            .sortedWith(Comparator.comparingInt(TapeSpan::firstFrameIdx).thenComparingInt(TapeSpan::lastFrameIdx))
        when (format) {
            CSV -> writeCSV(tapeSpans)
            EDL -> writeEDL(tapeSpans)
        }
    }

    private fun writeCSV(tapeSpans: List<TapeSpan>) {
        val csv = StringBuilder()
        csv.appendLine("Record In,Record Out,Source In,Source In Clock,Source")

        val scan = config[SCAN]
        val global = project.styling.global

        for (tapeSpan in tapeSpans) {
            val startField = tapeSpan.firstFrameIdx
            val stopField = tapeSpan.lastFrameIdx + 1
            val startFrame = startField / extraFPSMul
            val stopFrame = ceilDiv(stopField, extraFPSMul)
            val tapeFPS = tapeSpan.embeddedTape.tape.fps ?: global.fps
            val tapeStartExact = tapeSpan.firstReadTimecode
            val tapeStartRounded = when (tapeStartExact) {
                is Timecode.Frames -> tapeStartExact
                is Timecode.Clock -> tapeStartExact.toSMPTENonDropFrame(tapeFPS)
                else -> throw IllegalStateException("Wrong timecode format: ${tapeStartExact.javaClass.simpleName}")
            }
            var recIn = formatTimecode(global.fps, global.timecodeFormat, startFrame)
            var recOut = formatTimecode(global.fps, global.timecodeFormat, stopFrame)
            if (scan != Bitmap.Scan.PROGRESSIVE) {
                val tff = scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                recIn += if ((startField % 2 == 0) == tff) " \u25D3" else " \u25D2"
                recOut += if ((stopField % 2 == 0) == tff) " \u25D3" else " \u25D2"
            }
            val srcIn = tapeStartRounded.toString(tapeFPS)
            val srcInClock = if (tapeStartExact is Timecode.Clock) tapeStartExact.toString(tapeFPS) else ""
            csv.append("\"$recIn\",\"$recOut\",\"$srcIn\",\"$srcInClock\",")
                .append("\"${tapeSpan.embeddedTape.tape.fileOrDir.name}\"")
                .appendLine()
        }

        file.writeText(csv)
    }

    private fun writeEDL(tapeSpans: List<TapeSpan>) {
        val projFPS = project.styling.global.fps
        val projDropFrame = project.styling.global.timecodeFormat == TimecodeFormat.SMPTE_DROP_FRAME

        val edl = StringBuilder()
        edl.append("TITLE: ${file.nameWithoutExtension}").crlf()
        edl.append("FCM: ").append(if (projDropFrame) "DROP FRAME" else "NON-DROP FRAME").crlf()

        var id = 1
        for ((idx, tapeSpan) in tapeSpans.withIndex()) {
            if (id == 1000)
                break
            var lastFrameIdx = tapeSpan.lastFrameIdx
            tapeSpans.getOrNull(idx + 1)?.let { lastFrameIdx = min(lastFrameIdx, it.lastFrameIdx) }
            val startFrame = tapeSpan.firstFrameIdx / extraFPSMul
            val stopFrame = ceilDiv(lastFrameIdx + 1, extraFPSMul)
            val clipStart = Timecode.Frames(startFrame)
            val clipLength = Timecode.Frames(stopFrame - startFrame)
            if (clipLength.frames > 0) {
                val tapeStart = tapeSpan.firstReadTimecode
                val tapeLength = if (tapeStart is Timecode.Frames) clipLength else clipLength.toClock(projFPS)
                // In a well-behaved project, the tape FPS equals the project FPS, and only in those cases, we can
                // actually produce and in-spec EDL. Otherwise, importing software usually assumes that the frame
                // counter is just "rescaled" from the tape FPS to the project FPS (e.g., when rescaling from tape FPS
                // of 50 to project FPS of 25, the timecode 00:01:23:40 becomes 00:01:23:20). So we do this as well.
                val srcIn = tapeStart.toSMPTE(projFPS, projDropFrame)
                val srcOut = (tapeStart + tapeLength).toSMPTE(projFPS, projDropFrame)
                val recIn = clipStart.toSMPTE(projFPS, projDropFrame)
                val recOut = (clipStart + clipLength).toSMPTE(projFPS, projDropFrame)
                edl.crlf()
                edl.append("%03d".format(id++)).append("  AX       V     C        ")
                    .append(srcIn).append(" ").append(srcOut).append(" ")
                    .append(recIn).append(" ").append(recOut).append(" ").crlf()
                edl.append("* FROM CLIP NAME: ").append(tapeSpan.embeddedTape.tape.edlFilename).crlf()
            }
        }

        file.writeText(edl)
    }

    private fun Timecode.toSMPTE(fps: FPS, dropFrame: Boolean): String = when (dropFrame) {
        true -> toSMPTEDropFrame(fps).toString(fps).replace(';', ':')
        false -> toSMPTENonDropFrame(fps).toString(fps)
    }

    private fun StringBuilder.crlf() = append("\r\n")


    companion object {
        private val CSV = Format("csv")
        private val EDL = Format("edl", " (CMX3600)")
        val FORMATS = listOf<RenderFormat>(CSV, EDL)
    }


    private class Format(fileExt: String, labelSuffix: String = "") : RenderFormat(
        fileExt.uppercase() + labelSuffix, fileSeq = false, setOf(fileExt), fileExt,
        choice(FPS_SCALING) * choice(SCAN)
    ) {
        override fun createRenderJob(
            config: Config,
            project: Project,
            pageDefImages: List<DeferredImage>?,
            video: DeferredVideo?,
            fileOrDir: Path,
            filenamePattern: String?
        ) = TapeTimelineRenderJob(this, config, project, video!!, fileOrDir)
    }

}
