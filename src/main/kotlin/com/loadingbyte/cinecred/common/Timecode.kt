package com.loadingbyte.cinecred.common

import com.google.common.math.LongMath
import java.util.*
import kotlin.math.max


enum class TimecodeFormat { SMPTE_NON_DROP_FRAME, SMPTE_DROP_FRAME, EXACT_FRAMES_IN_SECOND, CLOCK, FRAMES }

/** Helper function to quickly format a frame count to a timecode string. */
fun formatTimecode(fps: FPS, format: TimecodeFormat, frames: Int): String = when (format) {
    TimecodeFormat.SMPTE_NON_DROP_FRAME ->
        Timecode.Frames(frames).toSMPTENonDropFrame(fps).toString(fps)
    TimecodeFormat.SMPTE_DROP_FRAME ->
        Timecode.Frames(frames).toSMPTEDropFrame(fps).toString(fps)
    TimecodeFormat.EXACT_FRAMES_IN_SECOND ->
        Timecode.Frames(frames).toExactFramesInSecond(fps).toString(fps)
    TimecodeFormat.CLOCK ->
        Timecode.Frames(frames).toClock(fps).toString()
    TimecodeFormat.FRAMES ->
        Timecode.Frames(frames).toString()
}

/** Helper function to quickly parse a timecode string to a frame count. */
fun parseTimecode(fps: FPS, format: TimecodeFormat, str: String): Int =
    parseTimecode(format, str).toFrames(fps).frames

fun parseTimecode(format: TimecodeFormat, str: String): Timecode = when (format) {
    TimecodeFormat.SMPTE_NON_DROP_FRAME ->
        Timecode.fromSMPTENonDropFrameString(str)
    TimecodeFormat.SMPTE_DROP_FRAME ->
        Timecode.fromSMPTEDropFrameString(str)
    TimecodeFormat.EXACT_FRAMES_IN_SECOND ->
        Timecode.fromExactFramesInSecondString(str)
    TimecodeFormat.CLOCK ->
        Timecode.fromClockString(str)
    TimecodeFormat.FRAMES ->
        Timecode.fromFramesString(str)
}

val FPS.canonicalTimecodeFormats: EnumSet<TimecodeFormat>
    get() {
        val set = EnumSet.of(TimecodeFormat.SMPTE_NON_DROP_FRAME, TimecodeFormat.CLOCK, TimecodeFormat.FRAMES)
        if (isFractional) set += TimecodeFormat.EXACT_FRAMES_IN_SECOND
        if (supportsDropFrameTimecode) set += TimecodeFormat.SMPTE_DROP_FRAME
        return set
    }


val FPS.supportsDropFrameTimecode: Boolean
    get() = denominator == 1001 && numerator % 15000 == 0


sealed interface Timecode : Comparable<Timecode> {

    operator fun plus(other: Timecode): Timecode = throw UnsupportedOperationException()
    operator fun minus(other: Timecode): Timecode = throw UnsupportedOperationException()
    operator fun times(n: Int): Timecode = throw UnsupportedOperationException()
    operator fun div(n: Int): Timecode = throw UnsupportedOperationException()
    override fun compareTo(other: Timecode): Int = throw UnsupportedOperationException()

    fun toFrames(fps: FPS): Frames
    fun toClock(fps: FPS): Clock = toFrames(fps).toClock(fps)
    fun toExactFramesInSecond(fps: FPS): ExactFramesInSecond = toFrames(fps).toExactFramesInSecond(fps)
    fun toSMPTENonDropFrame(fps: FPS): SMPTENonDropFrame = toFrames(fps).toSMPTENonDropFrame(fps)
    fun toSMPTEDropFrame(fps: FPS): SMPTEDropFrame = toFrames(fps).toSMPTEDropFrame(fps)

    fun toString(fps: FPS? = null): String


    data class Frames(val frames: Int) : Timecode {

        init {
            require(frames >= 0) { "Frames timecode must have non-negative number of frames." }
        }

        override fun plus(other: Timecode) = Frames(frames + (other as Frames).frames)
        override fun minus(other: Timecode) = Frames(frames - (other as Frames).frames)
        override fun times(n: Int) = Frames(frames * n)
        override fun div(n: Int) = Frames(frames / n)
        override fun compareTo(other: Timecode) = frames.compareTo((other as Frames).frames)

        override fun toFrames(fps: FPS) = this
        override fun toClock(fps: FPS) = Clock(frames * fps.denominator.toLong(), fps.numerator.toLong())

        override fun toExactFramesInSecond(fps: FPS): ExactFramesInSecond {
            val seconds = frames * fps.denominator / fps.numerator
            return ExactFramesInSecond(seconds, frames - ceilDiv(seconds * fps.numerator, fps.denominator))
        }

        override fun toSMPTENonDropFrame(fps: FPS): SMPTENonDropFrame {
            val intFPS = ceilDiv(fps.numerator, fps.denominator)
            return SMPTENonDropFrame(frames / intFPS, frames % intFPS)
        }

        override fun toSMPTEDropFrame(fps: FPS): SMPTEDropFrame {
            require(fps.supportsDropFrameTimecode)
            val intFPS = fps.numerator / 1000
            // Number of frames that are dropped every minute (apart from every 10th minute).
            val dropFactor = intFPS / 15
            // Number of frames in a 10-minute-cycle, i.e., the number of frames in 00:00:00:00 to 00:00:09:23.
            val numFramesPerCycle = 9 * (intFPS * 60 - dropFactor) + 1 * (intFPS * 60)
            // A frame counter that includes the dropped frames.
            val framesPlusDropped = frames +
                    dropFactor * ((frames / numFramesPerCycle) * 9) +
                    dropFactor * ((frames % numFramesPerCycle - dropFactor) / (intFPS * 60 - dropFactor))
            return SMPTEDropFrame(framesPlusDropped / intFPS, framesPlusDropped % intFPS)
        }

        // Note: We must override toString() (without parameters) in every subclass, or else the data class's default
        // implementation would take prevalence.
        override fun toString() = toString(null)
        override fun toString(fps: FPS?) = "%06d".format(Locale.ROOT, frames)

    }


    data class Clock(val numerator: Long, val denominator: Long) : Timecode {

        init {
            require(numerator >= 0L) { "Clock timecode must have non-negative numerator." }
            require(denominator > 0L) { "Clock timecode must have positive denominator." }
        }

        val seconds: Int = (numerator / denominator).toInt()

        override fun plus(other: Timecode) = op(other, 1L)
        override fun minus(other: Timecode) = op(other, -1L)

        private fun op(other: Timecode, sign: Long): Clock {
            if (denominator == (other as Clock).denominator)
                return Clock(numerator + other.numerator * sign, denominator)
            val gcd = LongMath.gcd(denominator, other.denominator)
            return Clock(
                other.denominator / gcd * numerator + denominator / gcd * other.numerator * sign,
                denominator / gcd * other.denominator
            )
        }

        override fun times(n: Int) = Clock(numerator * n, denominator)
        override fun div(n: Int) = Clock(numerator, denominator * n)

        override fun compareTo(other: Timecode): Int {
            if (denominator == (other as Clock).denominator)
                return numerator.compareTo(other.numerator)
            return try {
                Math.multiplyExact(other.denominator, numerator)
                    .compareTo(Math.multiplyExact(denominator, other.numerator))
            } catch (_: ArithmeticException) {
                val gcd = LongMath.gcd(denominator, other.denominator)
                (other.denominator / gcd * numerator).compareTo(denominator / gcd * other.numerator)
            }
        }

        override fun toFrames(fps: FPS) =
            Frames(((numerator * fps.numerator) / (denominator * fps.denominator)).toInt())

        fun toFramesCeil(fps: FPS) =
            Frames(ceilDiv(numerator * fps.numerator, denominator * fps.denominator).toInt())

        override fun toClock(fps: FPS) = this

        override fun toString() = toString(null)
        override fun toString(fps: FPS?): String {
            val s = seconds % 60
            val m = seconds / 60 % 60
            val h = seconds / 60 / 60 % 24
            val millis = (numerator * 1000L / denominator) % 1000L
            return "%02d:%02d:%02d.%03d".format(Locale.ROOT, h, m, s, millis)
        }

    }


    /** Each second gets exactly the amount of frames which are actually located in that second. */
    data class ExactFramesInSecond(val seconds: Int, val frames: Int) : Timecode {

        init {
            validateComponents(seconds, frames)
        }

        override fun toFrames(fps: FPS): Frames {
            val frames = Frames(frames + ceilDiv(seconds * fps.numerator, fps.denominator))
            // Multiple different combinations of "seconds" and "frames" merge to the same total frame count, but
            // only one of those combinations is actually a valid timecode. Require that we're that timecode.
            require(frames.toExactFramesInSecond(fps) == this) { "Timecode $this is invalid at $fps." }
            return frames
        }

        override fun toExactFramesInSecond(fps: FPS) = this

        override fun toString() = toString(null)
        override fun toString(fps: FPS?) = toClockAndFramesString(seconds, frames, fps, '+')

    }


    /** Each second gets the same amount of frames, even if this causes the clock to drift. */
    data class SMPTENonDropFrame(val seconds: Int, val frames: Int) : Timecode {

        init {
            validateComponents(seconds, frames)
        }

        override fun toFrames(fps: FPS): Frames {
            val intFPS = ceilDiv(fps.numerator, fps.denominator)
            require(frames < intFPS) { "The frame count $frames exceeds the integer FPS $intFPS." }
            return Frames(frames + seconds * intFPS)
        }

        override fun toSMPTENonDropFrame(fps: FPS) = this

        override fun toString() = toString(null)
        override fun toString(fps: FPS?) = toClockAndFramesString(seconds, frames, fps, ':')

    }


    /** The SMPTE Drop Frame standard is used to avoid drifts; this is only available for FPS that support it. */
    data class SMPTEDropFrame(val seconds: Int, val frames: Int) : Timecode {

        init {
            validateComponents(seconds, frames)
        }

        override fun toFrames(fps: FPS): Frames {
            require(fps.supportsDropFrameTimecode)
            val intFPS = fps.numerator / 1000
            require(frames < intFPS) { "The drop frame timecode $this does not exist." }
            val m = seconds / 60 % 60
            val h = seconds / 60 / 60 % 24
            // Number of frames that are dropped every minute (apart from every 10th minute).
            val dropFactor = intFPS / 15
            // Ensure that the timecode doesn't depict a dropped frame.
            if (seconds % 60 == 0 && m % 10 != 0)
                require(frames >= dropFactor) { "The drop frame timecode $this does not exist." }
            return Frames(
                frames + intFPS * seconds -
                        dropFactor * (h * 54) -
                        dropFactor * (m - m / 10)
            )
        }

        override fun toSMPTEDropFrame(fps: FPS) = this

        override fun toString() = toString(null)
        override fun toString(fps: FPS?) = toClockAndFramesString(seconds, frames, fps, ';')

    }


    companion object {

        private val CLOCK_REGEX = Regex("(\\d+):(\\d+):(\\d+).(\\d+)")
        private val CAF_REGEX_COLON = Regex("(\\d+):(\\d+):(\\d+):(\\d+)")
        private val CAF_REGEX_SEMICOLON = Regex("(\\d+):(\\d+):(\\d+);(\\d+)")
        private val CAF_REGEX_PLUS = Regex("(\\d+):(\\d+):(\\d+)\\+(\\d+)")

        fun fromFramesString(str: String): Frames =
            Frames(str.toInt())

        fun fromClockString(str: String): Clock {
            val match = CLOCK_REGEX.matchEntire(str) ?: throw IllegalArgumentException()
            val h = match.groupValues[1].toLong()
            val m = match.groupValues[2].toLong()
            val s = match.groupValues[3].toLong()
            val millis = match.groupValues[4].toLong()
            require(h >= 0L)
            require(m in 0L..<60L)
            require(s in 0L..<60L)
            require(millis in 0L..<1000L)
            return Clock(millis + 1000L * (s + 60L * (m + 60L * h)), 1000L)
        }

        fun fromSMPTENonDropFrameString(str: String): SMPTENonDropFrame =
            fromClockAndFramesString(str, CAF_REGEX_COLON, Timecode::SMPTENonDropFrame)

        fun fromSMPTEDropFrameString(str: String): SMPTEDropFrame =
            fromClockAndFramesString(str, CAF_REGEX_SEMICOLON, Timecode::SMPTEDropFrame)

        fun fromExactFramesInSecondString(str: String): ExactFramesInSecond =
            fromClockAndFramesString(str, CAF_REGEX_PLUS, Timecode::ExactFramesInSecond)

        private inline fun <T> fromClockAndFramesString(str: String, regex: Regex, new: (Int, Int) -> T): T {
            val match = regex.matchEntire(str) ?: throw IllegalArgumentException()
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            val s = match.groupValues[3].toInt()
            val f = match.groupValues[4].toInt()
            require(h >= 0)
            require(m in 0..<60)
            require(s in 0..<60)
            require(f >= 0)
            return new(s + 60 * (m + 60 * h), f)
        }

        private fun toClockAndFramesString(seconds: Int, frames: Int, fps: FPS?, sep: Char): String {
            val s = seconds % 60
            val m = seconds / 60 % 60
            val h = seconds / 60 / 60
            val fDig = if (fps == null) 2 else max(2, (ceilDiv(fps.numerator, fps.denominator) - 1).toString().length)
            return "%02d:%02d:%02d${sep}%0${fDig}d".format(Locale.ROOT, h, m, s, frames)
        }

        private fun validateComponents(seconds: Int, frames: Int) {
            require(seconds >= 0) { "Components timecode must have non-negative number of frames." }
            require(frames >= 0) { "Components timecode must have non-negative number of seconds." }
        }

    }

}
