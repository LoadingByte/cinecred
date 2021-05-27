package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.TimecodeFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.ceil


val FPS.supportsDropFrameTimecode: Boolean
    get() = denominator == 1001 && numerator % 15000 == 0


private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)


fun formatTimecode(fps: FPS, format: TimecodeFormat, frames: Int): String = when (format) {
    TimecodeFormat.SMPTE_NON_DROP_FRAME -> {
        val intFPS = ceil(fps.frac).toInt()
        formatSmpteNonDropFrame(intFPS, frames, ':')
    }
    TimecodeFormat.SMPTE_DROP_FRAME -> {
        // If drop-frame timecode is not supported, fall back to non drop-frame timecode.
        if (!fps.supportsDropFrameTimecode)
            formatTimecode(fps, TimecodeFormat.SMPTE_NON_DROP_FRAME, frames)
        else {
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
            formatSmpteNonDropFrame(intFPS, framesPlusDropped, ';')
        }
    }
    TimecodeFormat.CLOCK -> {
        // nanos = frames * (1/fps) * NANOS_PER_SECOND; reordered for maximum accuracy.
        val nanos = 1000_000_000L * frames * fps.denominator / fps.numerator
        CLOCK_FORMATTER.format(LocalTime.ofNanoOfDay(nanos))
    }
    TimecodeFormat.FRAMES -> frames.toString().padStart(6, '0')
}

private fun formatSmpteNonDropFrame(intFPS: Int, frames: Int, frameSep: Char): String {
    val f = frames % intFPS
    val s = frames / intFPS % 60
    val m = frames / intFPS / 60 % 60
    val h = frames / intFPS / 60 / 60 % 24
    val fDigits = (intFPS - 1).toString().length
    return "%02d:%02d:%02d${frameSep}%0${fDigits}d".format(Locale.ROOT, h, m, s, f)
}
