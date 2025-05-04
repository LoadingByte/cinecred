package com.loadingbyte.cinecred.delivery

import com.google.gson.GsonBuilder
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
import com.loadingbyte.cinecred.imaging.Tape
import com.loadingbyte.cinecred.project.Styling
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.bufferedWriter
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.math.min


class TapeTimelineRenderJob private constructor(
    private val format: Format,
    private val config: Config,
    private val styling: Styling,
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
            OTIO -> writeOTIO(tapeSpans)
            FCPXML -> writeFCPXML(tapeSpans)
            XML -> writeXML(tapeSpans)
        }
    }

    private fun writeCSV(tapeSpans: List<TapeSpan>) {
        val csv = StringBuilder()
        csv.appendLine("Record In,Record Out,Source In,Source In Clock,Source")

        val scan = config[SCAN]
        val global = styling.global

        for (tapeSpan in tapeSpans) {
            val startField = tapeSpan.firstFrameIdx
            val stopField = tapeSpan.lastFrameIdx + 1
            val startFrame = startField / extraFPSMul
            val stopFrame = ceilDiv(stopField, extraFPSMul)
            val tapeFPS = tapeSpan.embeddedTape.tape.fps ?: global.fps
            val tapeStartExact = tapeSpan.firstReadTimecode
            val tapeStartRounded = tapeStartExact.toSMPTENonDropFrame(tapeFPS)
            var recIn = formatTimecode(global.fps, global.timecodeFormat, startFrame)
            var recOut = formatTimecode(global.fps, global.timecodeFormat, stopFrame)
            if (scan != Bitmap.Scan.PROGRESSIVE) {
                val tff = scan == Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                recIn += if ((startField % 2 == 0) == tff) " \u25D3" else " \u25D2"
                recOut += if ((stopField % 2 == 0) == tff) " \u25D3" else " \u25D2"
            }
            val srcIn = tapeStartRounded.toString(tapeFPS)
            val srcInClock = tapeStartExact.toString(tapeFPS)
            csv.append("\"$recIn\",\"$recOut\",\"$srcIn\",\"$srcInClock\",")
                .append("\"${tapeSpan.embeddedTape.tape.name}\"")
                .appendLine()
        }

        file.writeText(csv)
    }

    private fun writeEDL(tapeSpans: List<TapeSpan>) {
        val projFPS = styling.global.fps
        val projDropFrame = projFPS.supportsDropFrameTimecode

        val edl = StringBuilder()
        edl.append("TITLE: ${file.nameWithoutExtension}").crlf()
        edl.append("FCM: ").append(if (projDropFrame) "DROP FRAME" else "NON-DROP FRAME").crlf()

        var id = 1
        for ((idx, tapeSpan) in tapeSpans.withIndex()) {
            if (id == 1000)
                break
            val startFrame = tapeSpan.firstFrameIdx / extraFPSMul
            var stopFrame = ceilDiv(tapeSpan.lastFrameIdx + 1, extraFPSMul)
            tapeSpans.getOrNull(idx + 1)?.let { stopFrame = min(stopFrame, it.firstFrameIdx / extraFPSMul) }
            val clipStart = Timecode.Frames(startFrame)
            val clipLength = Timecode.Frames(stopFrame - startFrame)
            if (clipLength.frames > 0) {
                val tapeStart = tapeSpan.firstReadTimecode
                val tapeLength = clipLength.toClock(projFPS)
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
                edl.append("* FROM CLIP NAME: ").append(tapeSpan.embeddedTape.tape.name).crlf()
            }
        }

        file.writeText(edl)
    }

    private fun Timecode.toSMPTE(fps: FPS, dropFrame: Boolean): String = when (dropFrame) {
        true -> toSMPTEDropFrame(fps).toString(fps).replace(';', ':')
        false -> toSMPTENonDropFrame(fps).toString(fps)
    }

    private fun StringBuilder.crlf() = append("\r\n")

    private fun writeOTIO(tapeSpans: List<TapeSpan>) {
        val projFPS = styling.global.fps

        val trackObjs = mutableListOf<Any>()
        for (track in arrangeOnTracks(tapeSpans)) {
            val clipObjs = mutableListOf<Any>()
            var frameIdx = 0
            for (tapeSpan in track) {
                // Add a gap between clips.
                val gapFrames = tapeSpan.firstFrameIdx - frameIdx
                if (gapFrames != 0)
                    clipObjs += mapOf(
                        "OTIO_SCHEMA" to "Gap.1",
                        "source_range" to makeOTIOTimeRange(0.0, gapFrames / extraFPSMul.toDouble(), projFPS.frac)
                    )
                frameIdx = tapeSpan.lastFrameIdx + 1
                // Add the clip itself.
                val tape = tapeSpan.embeddedTape.tape
                val tapeFPS = tape.fps ?: projFPS
                clipObjs += mapOf(
                    "OTIO_SCHEMA" to "Clip.1",
                    "name" to tape.name,
                    "source_range" to makeOTIOTimeRange(
                        tapeSpan.firstReadTimecode as Timecode.Clock,
                        Timecode.Frames(tapeSpan.durationFrames).toClock(projFPS) / extraFPSMul,
                        tapeFPS
                    ),
                    "media_reference" to mapOf(
                        "OTIO_SCHEMA" to "ExternalReference.1",
                        "name" to tape.name,
                        "target_url" to tape.name,
                        "available_range" to makeOTIOTimeRange(tape.availableStart, tape.availableDuration, tapeFPS)
                    )
                )
            }
            trackObjs += mapOf(
                "OTIO_SCHEMA" to "Track.1",
                "kind" to "Audio",
                "source_range" to makeOTIOTimeRange(0.0, video.numFrames.toDouble(), projFPS.frac),
                "children" to clipObjs
            )
        }

        val timelineObj = mapOf(
            "OTIO_SCHEMA" to "Timeline.1",
            "name" to file.nameWithoutExtension,
            "global_start_time" to makeOTIOTime(0.0, projFPS.frac),
            "tracks" to mapOf(
                "OTIO_SCHEMA" to "Stack.1",
                "name" to file.nameWithoutExtension,
                "children" to trackObjs
            )
        )

        file.bufferedWriter().use { writer -> GsonBuilder().setPrettyPrinting().create().toJson(timelineObj, writer) }
    }

    private fun makeOTIOTime(time: Double, rate: Double) =
        mapOf(
            "OTIO_SCHEMA" to "RationalTime.1",
            "value" to time,
            "rate" to rate
        )

    private fun makeOTIOTimeRange(startTime: Double, duration: Double, rate: Double) =
        mapOf(
            "OTIO_SCHEMA" to "TimeRange.1",
            "start_time" to makeOTIOTime(startTime, rate),
            "duration" to makeOTIOTime(duration, rate)
        )

    private fun makeOTIOTimeRange(startTime: Timecode.Clock, duration: Timecode.Clock, rate: FPS) =
        makeOTIOTimeRange(
            startTime.numerator * rate.numerator / (startTime.denominator * rate.denominator).toDouble(),
            duration.numerator * rate.numerator / (duration.denominator * rate.denominator).toDouble(),
            rate.frac
        )

    private fun writeFCPXML(tapeSpans: List<TapeSpan>) {
        val global = styling.global

        val tapes = tapeSpans.mapTo(LinkedHashSet()) { tapeSpan -> tapeSpan.embeddedTape.tape }
        val formats = linkedSetOf(Pair(global.resolution, global.fps))
        tapes.mapTo(formats) { tape -> Pair(tape.spec.resolution, tape.fps ?: global.fps) }

        // Create the document and root element.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().domImplementation
            .createDocument(null, "fcpxml", null)
        val fcpxml = doc.documentElement
        fcpxml.setAttribute("version", "1.9")

        // Create the resources.
        val resources = fcpxml.appendChild(doc.createElement("resources"))
        var idCtr = 0
        val formatIds = formats.associateWith { (res, fps) ->
            val id = "r" + idCtr++
            resources.appendChild(doc.createElement("format").apply {
                setAttribute("id", id)
                setAttribute("width", res.widthPx.toString())
                setAttribute("height", res.heightPx.toString())
                setAttribute("frameDuration", "${fps.denominator}/${fps.numerator}s")
            })
            id
        }
        val tapeIds = tapes.associateWith { tape ->
            val id = "r" + idCtr++
            resources.appendChild(doc.createElement("asset").apply {
                setAttribute("id", id)
                setAttribute("name", tape.name)
                setAttribute("format", formatIds[Pair(tape.spec.resolution, tape.fps ?: global.fps)])
                setAttribute("start", makeFCPXMLTime(tape.availableStart))
                setAttribute("duration", makeFCPXMLTime(tape.availableDuration))
                setAttribute("hasAudio", "1")
                appendChild(doc.createElement("media-rep").apply {
                    setAttribute("kind", "original-media")
                    setAttribute("src", tape.name)
                })
            })
            id
        }

        // Create the sequence.
        val spine = fcpxml
            .appendChild(doc.createElement("library"))
            .appendChild(doc.createElement("event").apply {
                setAttribute("name", file.nameWithoutExtension)
            })
            .appendChild(doc.createElement("project").apply {
                setAttribute("name", file.nameWithoutExtension)
            }).appendChild(doc.createElement("sequence").apply {
                setAttribute("format", formatIds[Pair(global.resolution, global.fps)])
                setAttribute("tcStart", "0/1s")
                setAttribute("tcFormat", if (global.fps.supportsDropFrameTimecode) "DF" else "NDF")
                setAttribute("duration", makeFCPXMLTime(Timecode.Frames(video.numFrames).toClock(global.fps)))
            }).appendChild(doc.createElement("spine"))
        val gap = spine.appendChild(doc.createElement("gap").apply {
            setAttribute("offset", "0/1s")
            setAttribute("duration", makeFCPXMLTime(Timecode.Frames(video.numFrames).toClock(global.fps)))
        })
        for ((trackIdx, track) in arrangeOnTracks(tapeSpans).withIndex())
            for (tapeSpan in track) {
                val tape = tapeSpan.embeddedTape.tape
                val offset = Timecode.Frames(tapeSpan.firstFrameIdx).toClock(global.fps) / extraFPSMul
                val duration = Timecode.Frames(tapeSpan.durationFrames).toClock(global.fps) / extraFPSMul
                gap.appendChild(doc.createElement("asset-clip").apply {
                    setAttribute("name", tape.name)
                    setAttribute("ref", tapeIds[tape])
                    setAttribute("offset", makeFCPXMLTime(offset))
                    setAttribute("start", makeFCPXMLTime(tapeSpan.firstReadTimecode as Timecode.Clock))
                    setAttribute("duration", makeFCPXMLTime(duration))
                    setAttribute("lane", (trackIdx + 1).toString())
                })
            }

        // Write the XML tree to a file.
        file.bufferedWriter().use { writer ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
            }.transform(DOMSource(doc), StreamResult(writer))
        }
    }

    private fun makeFCPXMLTime(timecode: Timecode.Clock) = "${timecode.numerator}/${timecode.denominator}s"

    private fun writeXML(tapeSpans: List<TapeSpan>) {
        val global = styling.global

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().domImplementation
            .createDocument(null, "xmeml", null)
        val xmeml = doc.documentElement
        xmeml.setAttribute("version", "5")

        val sequence = xmeml.appendChild(doc.createElement("sequence")).apply {
            appendChild(doc.createElement("name", file.nameWithoutExtension))
            appendChild(doc.createElement("duration", video.numFrames))
            appendChild(makeXMLRate(doc, global.fps))
            appendChild(makeXMLTimecode(doc, global.fps, Timecode.Frames(0)))
        }

        val media = sequence.appendChild(doc.createElement("media"))
        media
            .appendChild(doc.createElement("video"))
            .appendChild(doc.createElement("format"))
            .appendChild(doc.createElement("samplecharacteristics")).apply {
                appendChild(doc.createElement("width").apply { textContent = global.resolution.widthPx.toString() })
                appendChild(doc.createElement("height").apply { textContent = global.resolution.heightPx.toString() })
                appendChild(makeXMLRate(doc, global.fps))
            }

        val audio = media.appendChild(doc.createElement("audio"))
        val tapeIds = HashMap<Tape, String>()
        for (track in arrangeOnTracks(tapeSpans)) {
            val trackElem = audio.appendChild(doc.createElement("track"))
            for (tapeSpan in track) {
                val tape = tapeSpan.embeddedTape.tape
                val startFrame = tapeSpan.firstFrameIdx / extraFPSMul
                val stopFrame = ceilDiv(tapeSpan.lastFrameIdx + 1, extraFPSMul)
                val inPoint = tapeSpan.firstReadTimecode.toFrames(global.fps).frames
                val file = doc.createElement("file")
                trackElem.appendChild(doc.createElement("clipitem").apply {
                    appendChild(doc.createElement("name", tape.name))
                    appendChild(doc.createElement("duration", stopFrame - startFrame))
                    appendChild(makeXMLRate(doc, global.fps))
                    appendChild(doc.createElement("start", startFrame))
                    appendChild(doc.createElement("end", stopFrame))
                    appendChild(doc.createElement("in", inPoint))
                    appendChild(doc.createElement("out", inPoint + (stopFrame - startFrame)))
                    appendChild(file)
                })
                if (tape !in tapeIds) {
                    tapeIds[tape] = "f" + tapeIds.size
                    val tapeFPS = tape.fps ?: global.fps
                    file.appendChild(doc.createElement("duration", tape.availableDuration.toFramesCeil(tapeFPS).frames))
                    file.appendChild(makeXMLRate(doc, tapeFPS))
                    file.appendChild(doc.createElement("name", tape.name))
                    file.appendChild(doc.createElement("pathurl", tape.name))
                    file.appendChild(makeXMLTimecode(doc, tapeFPS, tape.availableStart))
                    file.appendChild(doc.createElement("media")).appendChild(doc.createElement("audio"))
                }
                file.setAttribute("id", tapeIds[tape])
            }
        }

        // Write the XML tree to a file.
        file.bufferedWriter().use { writer ->
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
            }.transform(DOMSource(doc), StreamResult(writer))
        }
    }

    private fun makeXMLRate(doc: Document, fps: FPS) =
        doc.createElement("rate").apply {
            appendChild(doc.createElement("timebase", roundingDiv(fps.numerator, fps.denominator)))
            appendChild(doc.createElement("ntsc", fps.supportsDropFrameTimecode.toString().uppercase()))
        }

    private fun makeXMLTimecode(doc: Document, fps: FPS, timecode: Timecode): Element {
        val dropFrame = fps.supportsDropFrameTimecode
        val string = when (dropFrame) {
            true -> timecode.toSMPTEDropFrame(fps).toString(fps)
            false -> timecode.toSMPTENonDropFrame(fps).toString(fps)
        }
        return doc.createElement("timecode").apply {
            appendChild(doc.createElement("string", string))
            appendChild(doc.createElement("displayformat", if (dropFrame) "DF" else "NDF"))
            appendChild(makeXMLRate(doc, fps))
        }
    }

    private fun Document.createElement(tagName: String, textContent: Any) =
        createElement(tagName).also { it.textContent = textContent.toString() }

    private fun arrangeOnTracks(tapeSpans: List<TapeSpan>): List<MutableList<TapeSpan>> {
        val tracks = mutableListOf<MutableList<TapeSpan>>()
        for (tapeSpan in tapeSpans) {
            val freeTrack = tracks.find { track -> track.last().lastFrameIdx < tapeSpan.firstFrameIdx }
            if (freeTrack != null)
                freeTrack += tapeSpan
            else
                tracks += mutableListOf(tapeSpan)
        }
        return tracks
    }

    private val Tape.name get() = fileOrDir.name
    private val Tape.availableStart get() = availableRange.start as Timecode.Clock
    private val Tape.availableDuration get() = availableRange.let { it.endExclusive - it.start } as Timecode.Clock
    private val TapeSpan.durationFrames get() = lastFrameIdx - firstFrameIdx + 1


    companion object {
        private val CSV = Format("csv")
        private val EDL = Format("edl", "CMX3600")
        private val OTIO = Format("otio", "OpenTimelineIO")
        private val FCPXML = Format("fcpxml", "Final Cut Pro X XML")
        private val XML = Format("xml", "Final Cut Pro 7 XML")
        val FORMATS = listOf<RenderFormat>(CSV, EDL, OTIO, FCPXML, XML)
    }


    private class Format(fileExt: String, auxLabel: String? = null) : RenderFormat(
        fileExt.uppercase(), auxLabel, fileSeq = false, setOf(fileExt), fileExt,
        choice(FPS_SCALING) * choice(SCAN)
    ) {
        override fun createRenderJob(
            config: Config,
            sliders: Sliders,
            styling: Styling,
            pageDefImages: List<DeferredImage>?,
            video: DeferredVideo?,
            fileOrDir: Path,
            filenamePattern: String?
        ) = TapeTimelineRenderJob(this, config, styling, video!!, fileOrDir)
    }

}
