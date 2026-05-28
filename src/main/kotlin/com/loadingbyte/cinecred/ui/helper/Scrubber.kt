package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.ui.FlatRoundBorder
import com.loadingbyte.cinecred.common.*
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.*
import java.awt.event.KeyEvent.*
import java.beans.PropertyChangeListener
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParseException
import javax.swing.JFormattedTextField
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.DefaultFormatter
import javax.swing.text.DefaultFormatterFactory
import javax.swing.text.NumberFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow


class Scrubber<T : Any>(scheme: Scheme<T>) : JFormattedTextField() {

    interface Scheme<T : Any> {
        fun createFormatterFactory(scrubber: Scrubber<T>): AbstractFormatterFactory
        fun cast(value: Any): T
        fun zero(): T
        fun step(value: T, steps: Double): T
    }

    interface Limiter<T : Any> {
        fun coerce(value: T): T
    }


    var scheme: Scheme<T> = scheme
        set(scheme) {
            if (field == scheme)
                return
            field = scheme
            // Note: We have verified that changing the formatter only causes valueToString(), but not stringToValue()
            // to be called. Hence, the number value remains unmodified each time the scheme is changed. This means that
            // neither floating point drift nor undesired value coercion can occur.
            formatterFactory = scheme.createFormatterFactory(this)
        }

    var limiter: Limiter<T>? = null
    var sensitivity: Double = 0.1

    init {
        formatterFactory = scheme.createFormatterFactory(this)
        border = FlatRoundBorder()

        cursor = SCRUB_CURSOR
        addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            }

            override fun focusLost(e: FocusEvent) {
                cursor = SCRUB_CURSOR
            }
        })

        val mouseListener = MouseListener(this)
        addMouseListener(mouseListener)
        addMouseMotionListener(mouseListener)
        isRequestFocusEnabled = false

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    VK_ENTER, VK_ESCAPE -> parent.requestFocusInWindow()
                    VK_UP, VK_KP_UP -> move(getValue(), 10, e.isShiftDown, e.isControlDown)
                    VK_DOWN, VK_KP_DOWN -> move(getValue(), -10, e.isShiftDown, e.isControlDown)
                    else -> return
                }
                e.consume()
            }
        })
    }

    override fun getValue(): T =
        super.getValue().let { if (it == null) scheme.zero() else scheme.cast(it) }

    fun addValueListener(listener: PropertyChangeListener) {
        addPropertyChangeListener("value", listener)
    }

    // There is a subtle bug in the JDK which causes JTextField and its subclasses to not utilize a one pixel wide
    // column at the right for drawing; instead, it is always empty. However, this column is needed to draw the caret at
    // the rightmost position when the text field has a preferred size that exactly matches the text width. Hence, when
    // the user positions the caret at the rightmost location, the text scrolls one pixel to the left to accommodate the
    // caret. We did not manage to localize the source of this bug, but as a workaround, we just add one more pixel to
    // the preferred width, thereby providing the required space for the caret when it is at the rightmost position.
    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().apply { if (!isPreferredSizeSet) width += 1 }

    private fun postprocessParsedValue(value: T): T {
        val coercedValue = limiter?.coerce(value) ?: value
        // While editing (i.e., when the scrubber has focus), make out-of-range values invalid by throwing.
        // Thanks to that, the user can freely type in the text field without worrying about his intermediate values
        // being coerced.
        return if (hasFocus())
            if (value != coercedValue) throw ParseException("", 0) else value
        // Once editing is done, coerce and commit out-of-range values.
        else
            coercedValue
    }

    private fun move(startValue: T, pixels: Int, shift: Boolean, ctrl: Boolean) {
        var speed = sensitivity
        if (shift && !ctrl) speed *= 10.0 else if (!shift && ctrl) speed *= 0.1
        val value = scheme.step(startValue, pixels * speed)
        setValue(limiter?.coerce(value) ?: value)
    }


    companion object {

        private operator fun <N : Number> N.plus(other: N): N = when (this) {
            is Byte -> this + other as Byte
            is Short -> this + other as Short
            is Int -> this + other as Int
            is Long -> this + other as Long
            is Float -> this + other as Float
            is Double -> this + other as Double
            else -> throw IllegalArgumentException()
        }.let { @Suppress("UNCHECKED_CAST") (it as N) }

        private operator fun <N : Number> N.times(other: N): N = when (this) {
            is Byte -> this * other as Byte
            is Short -> this * other as Short
            is Int -> this * other as Int
            is Long -> this * other as Long
            is Float -> this * other as Float
            is Double -> this * other as Double
            else -> throw IllegalArgumentException()
        }.let { @Suppress("UNCHECKED_CAST") (it as N) }

        private operator fun <N : Number> N.div(other: N): N = when (this) {
            is Byte -> this / other as Byte
            is Short -> this / other as Short
            is Int -> this / other as Int
            is Long -> this / other as Long
            is Float -> this / other as Float
            is Double -> this / other as Double
            else -> throw IllegalArgumentException()
        }.let { @Suppress("UNCHECKED_CAST") (it as N) }

        private fun <N : Number> Class<N>.convert(n: Number): N = when (this) {
            Byte::class.javaObjectType -> n.toByte()
            Short::class.javaObjectType -> n.toShort()
            Int::class.javaObjectType -> n.toInt()
            Long::class.javaObjectType -> n.toLong()
            Float::class.javaObjectType -> n.toFloat()
            Double::class.javaObjectType -> n.toDouble()
            else -> throw IllegalArgumentException()
        }.let(::cast)

    }


    data class NumericScheme<T : Number>(
        val valueClass: Class<T>,
        val precision: Int = 3,
        val unit: String? = null,
        val multiplier: T? = null,
        val logStep: Boolean = false
    ) : Scheme<T> {

        override fun createFormatterFactory(scrubber: Scrubber<T>): AbstractFormatterFactory {
            val editFormat = when (valueClass) {
                Float::class.javaObjectType, Double::class.javaObjectType ->
                    NumberFormat.getInstance().apply { maximumFractionDigits = precision }
                else -> NumberFormat.getIntegerInstance()
            }
            val displayFormat = editFormat.clone() as NumberFormat
            if (unit != null) {
                val suffix = " $unit"
                (displayFormat as DecimalFormat).apply { positiveSuffix = suffix; negativeSuffix = suffix }
            }
            val displayFormatter = Formatter(scrubber, valueClass, displayFormat, multiplier)
            val editFormatter = Formatter(scrubber, valueClass, editFormat, multiplier)
            return DefaultFormatterFactory(displayFormatter, displayFormatter, editFormatter)
        }

        override fun cast(value: Any): T = valueClass.cast(value)
        override fun zero() = valueClass.convert(0)

        override fun step(value: T, steps: Double): T =
            when (logStep) {
                false -> value + valueClass.convert(steps).let { if (multiplier == null) it else it / multiplier }
                true -> value * valueClass.convert(10.0.pow(steps))
            }

        private class Formatter<T : Number>(
            private val scrubber: Scrubber<T>,
            private val valueClass: Class<T>,
            format: NumberFormat,
            private val multiplier: T?
        ) : NumberFormatter(format) {

            init {
                super.valueClass = valueClass
                commitsOnValidEdit = true
            }

            override fun valueToString(value: Any): String {
                var value = valueClass.cast(value)
                if (multiplier != null)
                    value *= multiplier
                return super.valueToString(value)
            }

            override fun stringToValue(string: String): Number {
                var value = valueClass.cast(super.stringToValue(string))
                if (multiplier != null)
                    value /= multiplier
                return scrubber.postprocessParsedValue(value)
            }

        }

    }


    data class FramesAsTimecodeScheme(val fps: FPS, val timecodeFormat: TimecodeFormat) : Scheme<Int> {

        override fun createFormatterFactory(scrubber: Scrubber<Int>) =
            DefaultFormatterFactory(Formatter(scrubber, fps, timecodeFormat))

        override fun cast(value: Any) = value as Int
        override fun zero() = 0
        override fun step(value: Int, steps: Double) = value + steps.toInt()

        private class Formatter(
            private val scrubber: Scrubber<Int>,
            private val fps: FPS,
            private val timecodeFormat: TimecodeFormat
        ) : DefaultFormatter() {

            init {
                valueClass = Int::class.javaObjectType
                commitsOnValidEdit = true
            }

            // We need to catch exceptions here because formatTimecode() throws some when using non-fractional FPS
            // together with a drop-frame timecode.
            override fun valueToString(value: Any): String =
                try {
                    val tc = formatTimecode(fps, timecodeFormat, abs(value as Int))
                    if (value < 0) "-$tc" else if (isSigned) "+$tc" else tc
                } catch (_: IllegalArgumentException) {
                    ""
                }

            override fun stringToValue(string: String): Int {
                val value = try {
                    val c0 = string[0]
                    val n =
                        parseTimecode(fps, timecodeFormat, if (c0 == '+' || c0 == '-') string.substring(1) else string)
                    if (isSigned && c0 == '-') -n else n
                } catch (_: Exception) {
                    throw ParseException("", 0)
                }
                return scrubber.postprocessParsedValue(value)
            }

            private val isSigned: Boolean
                get() = scrubber.limiter.let { it !is NumberLimiter || it.min == null || it.min < 0 }

        }

    }


    data class TimecodeScheme(val fps: FPS?, val timecodeFormat: TimecodeFormat) : Scheme<Timecode> {

        override fun createFormatterFactory(scrubber: Scrubber<Timecode>) =
            DefaultFormatterFactory(Formatter(scrubber, fps, timecodeFormat))

        override fun cast(value: Any) = value as Timecode
        override fun zero() = zeroTimecode(timecodeFormat)

        override fun step(value: Timecode, steps: Double): Timecode {
            val steps = steps.toInt()
            // Always step a frames timecode by "steps" frames.
            if (value is Timecode.Frames)
                return Timecode.Frames(max(0, value.frames + steps))
            // Always step a clock timecode by fractions of a second. Don't step by frames to not lose precision.
            if (value is Timecode.Clock) {
                val offsetTc = Timecode.Clock(abs(steps).toLong(), 10)
                return when {
                    steps >= 0 -> value + offsetTc
                    value > offsetTc -> value - offsetTc
                    else -> Timecode.Clock(0, 1)
                }
            }
            // If FPS are known, step by "steps" frames.
            if (fps != null)
                try {
                    return Timecode.Frames(max(0, value.toFrames(fps).frames + steps))
                        .toFormat(timecodeFormat, fps)
                } catch (_: IllegalArgumentException) {
                }
            // If FPS are unknown, step only the seconds.
            val offsetSeconds = steps / 10
            return when (value) {
                is Timecode.ExactFramesInSecond -> {
                    val s = value.seconds + offsetSeconds
                    if (s >= 0) Timecode.ExactFramesInSecond(s, value.frames) else Timecode.ExactFramesInSecond(0, 0)
                }
                is Timecode.SMPTENonDropFrame -> {
                    val s = value.seconds + offsetSeconds
                    if (s >= 0) Timecode.SMPTENonDropFrame(s, value.frames) else Timecode.SMPTENonDropFrame(0, 0)
                }
                // A drop frame time timecode is difficult to step without knowing the FPS due to the dropped frames,
                // and it only makes sense for a few cases of non-variable FPS anyway, so just don't step at all.
                is Timecode.SMPTEDropFrame -> value
            }
        }

        private class Formatter(
            private val scrubber: Scrubber<Timecode>,
            private val fps: FPS?,
            private val timecodeFormat: TimecodeFormat
        ) : DefaultFormatter() {

            init {
                valueClass = Timecode::class.javaObjectType
                commitsOnValidEdit = true
            }

            override fun valueToString(value: Any): String =
                try {
                    (value as Timecode).toString(fps)
                } catch (_: IllegalArgumentException) {
                    ""
                }

            override fun stringToValue(string: String): Timecode {
                val value: Timecode
                try {
                    value = parseTimecode(timecodeFormat, string)
                    // This check throws if the entered timecode is invalid.
                    fps?.let(value::toFrames)
                } catch (_: Exception) {
                    throw ParseException("", 0)
                }
                return scrubber.postprocessParsedValue(value)
            }

        }

    }


    data class NumberLimiter<T : Number>(val min: T? = null, val max: T? = null, val atom: T? = null) : Limiter<T> {

        override fun coerce(value: T): T {
            @Suppress("UNCHECKED_CAST")
            value as Comparable<Number>
            var value = value
            when {
                min != null && value < min -> value = min
                max != null && value > max -> value = max
            }
            if (atom != null)
                value = value / atom * atom
            return value
        }

    }


    data class TimecodeLimiter(val min: Timecode, val max: Timecode, val fps: FPS? = null) : Limiter<Timecode> {

        override fun coerce(value: Timecode): Timecode {
            if (value is Timecode.Frames && min is Timecode.Frames && max is Timecode.Frames ||
                value is Timecode.Clock && min is Timecode.Clock && max is Timecode.Clock
            )
                return value.coerceIn(min, max)
            try {
                fps?.let { fps -> return value.toClock(fps).coerceIn(min.toClock(fps), max.toClock(fps)) }
            } catch (_: IllegalArgumentException) {
            }
            // This is a last-resort best-effort check.
            if (value is Timecode.ExactFramesInSecond && min is Timecode.Clock && max is Timecode.Clock)
                when {
                    value.seconds < min.seconds -> return value.copy(seconds = min.seconds)
                    value.seconds > max.seconds -> return value.copy(seconds = max.seconds)
                }
            return value
        }

    }


    private class MouseListener<T : Any>(private val scrubber: Scrubber<T>) : MouseAdapter() {

        private var startX = 0
        private var startValue: T? = null
        private var glassPane: Component? = null

        override fun mouseClicked(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e))
                scrubber.requestFocusInWindow()
        }

        override fun mousePressed(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                startX = e.x
                startValue = scrubber.value
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                startValue = null
                updateGlassPane(false)
            }
        }

        override fun mouseDragged(e: MouseEvent) {
            startValue?.let { startValue ->
                if (glassPane == null)
                    updateGlassPane(true)
                scrubber.move(startValue, e.x - startX, e.isShiftDown, e.isControlDown)
            }
        }

        private fun updateGlassPane(dragging: Boolean) {
            if (dragging)
                glassPane = (SwingUtilities.getWindowAncestor(scrubber) as RootPaneContainer).glassPane
            glassPane?.cursor = if (dragging) SCRUB_CURSOR else Cursor.getDefaultCursor()
            glassPane?.isVisible = dragging
            if (!dragging)
                glassPane = null
        }

    }

}
