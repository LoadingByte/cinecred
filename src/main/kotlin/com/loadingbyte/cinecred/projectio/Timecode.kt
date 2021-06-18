package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.TimecodeFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import kotlin.math.ceil


val FPS.supportsDropFrameTimecode: Boolean
    get() = denominator == 1001 && numerator % 15000 == 0


private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)


fun formatTimecode(fps: FPS, format: TimecodeFormat, frames: Int): String = when (format) {
    TimecodeFormat.SMPTE_NON_DROP_FRAME -> {
        val intFPS = ceil(fps.frac).toInt()
        formatSmpteNonDropFrame(intFPS, ':', frames)
    }
    TimecodeFormat.SMPTE_DROP_FRAME -> {
        require(fps.supportsDropFrameTimecode)
        val intFPS = fps.numerator / 1000
        // Number of frames that are dropped every minute (apart from every 10th minute).
        val dropFactor = intFPS / 15
        // Number of frames in a 10-minute-cycle, i.e., the number of frames in 00:00:00:00 to 00:00:09:23.
        val numFramesPerCycle = 9 * (intFPS * 60 - dropFactor) + 1 * (intFPS * 60)
        // A frame counter that includes the dropped frames. As soon as we have this, we can just pass it
        // through a non drop-frame timecode formatter to obtain our final timecode string.
        val framesPlusDropped = frames +
                dropFactor * ((frames / numFramesPerCycle) * 9) +
                dropFactor * ((frames % numFramesPerCycle - dropFactor) / (intFPS * 60 - dropFactor))
        formatSmpteNonDropFrame(intFPS, ';', framesPlusDropped)
    }
    TimecodeFormat.CLOCK -> {
        // nanos = frames * (1/fps) * NANOS_PER_SECOND; reordered for maximum accuracy.
        val nanos = 1000_000_000L * frames * fps.denominator / fps.numerator
        CLOCK_FORMATTER.format(LocalTime.ofNanoOfDay(nanos))
    }
    TimecodeFormat.FRAMES -> frames.toString().padStart(6, '0')
}

private fun formatSmpteNonDropFrame(intFPS: Int, frameSep: Char, frames: Int): String {
    val f = frames % intFPS
    val s = frames / intFPS % 60
    val m = frames / intFPS / 60 % 60
    val h = frames / intFPS / 60 / 60 % 24
    val fDigits = (intFPS - 1).toString().length
    return "%02d:%02d:%02d${frameSep}%0${fDigits}d".format(Locale.ROOT, h, m, s, f)
}


fun parseTimecode(fps: FPS, format: TimecodeFormat, timecode: String): Int = when (format) {
    TimecodeFormat.SMPTE_NON_DROP_FRAME -> {
        val intFPS = ceil(fps.frac).toInt()
        parseSmpteNonDropFrame(intFPS, ':', timecode).frames
    }
    TimecodeFormat.SMPTE_DROP_FRAME -> {
        require(fps.supportsDropFrameTimecode)
        val intFPS = fps.numerator / 1000
        // Number of frames that are dropped every minute (apart from every 10th minute).
        val dropFactor = intFPS / 15
        val res = parseSmpteNonDropFrame(intFPS, ';', timecode)
        // Ensure that the timecode doesn't depict a dropped frame.
        require(res.m % 10 == 0 || res.f !in 0 until dropFactor)
        res.frames -
                dropFactor * (res.h * 54) -
                dropFactor * (res.m - res.m / 10)
    }
    TimecodeFormat.CLOCK ->
        try {
            val nanos = LocalTime.from(CLOCK_FORMATTER.parse(timecode)).toNanoOfDay()
            // frames = nanos * (1/NANOS_PER_SECOND) * fps; reordered for maximum accuracy.
            (nanos * fps.numerator / (fps.denominator * 1000_000_000L)).toInt()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException(e)
        }
    TimecodeFormat.FRAMES -> timecode.toInt()
}

private val SMPTE_REGEX_COLON = Regex("(\\d+):(\\d+):(\\d+):(\\d+)")
private val SMPTE_REGEX_SEMICOLON = Regex("(\\d+):(\\d+):(\\d+);(\\d+)")

private class SmpteParsingResult(val frames: Int, val h: Int, val m: Int, val s: Int, val f: Int)

private fun parseSmpteNonDropFrame(intFPS: Int, frameSep: Char, timecode: String): SmpteParsingResult {
    val regex = when (frameSep) {
        ':' -> SMPTE_REGEX_COLON
        ';' -> SMPTE_REGEX_SEMICOLON
        else -> throw IllegalArgumentException()
    }
    val match = regex.matchEntire(timecode) ?: throw IllegalArgumentException()
    val h = match.groupValues[1].toInt()
    val m = match.groupValues[2].toInt()
    val s = match.groupValues[3].toInt()
    val f = match.groupValues[4].toInt()
    require(h in 0 until 24)
    require(m in 0 until 60)
    require(s in 0 until 60)
    require(f in 0 until intFPS)
    val frames = f + intFPS * (s + 60 * (m + 60 * h))
    return SmpteParsingResult(frames, h, m, s, f)
}
