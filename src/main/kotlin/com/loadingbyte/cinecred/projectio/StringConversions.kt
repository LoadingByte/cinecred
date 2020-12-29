package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.FontSpec
import java.awt.Color
import kotlin.math.floor


fun String.toInt(nonNegative: Boolean, nonZero: Boolean = false): Int {
    val i = toInt()
    if (nonNegative && i < 0 || nonZero && i == 0)
        throw NumberFormatException()
    return i
}


fun String.toFiniteFloat(nonNegative: Boolean = false, nonZero: Boolean = false): Float {
    val f = replace(',', '.').toFloat()
    if (!f.isFinite() || nonNegative && f < 0f || nonZero && f == 0f)
        throw NumberFormatException()
    return f
}


inline fun <reified T : Enum<T>> String.toEnum(): T = enumValueOf(this)


inline fun <reified T : Enum<T>, reified U : Enum<U>> String.toEnumPair(): Pair<T, U> =
    try {
        toUpperCase().split(" ").let { (fst, snd) ->
            // Allow either ordering.
            try {
                Pair(fst.toEnum(), snd.toEnum())
            } catch (_: IllegalArgumentException) {
                Pair(snd.toEnum(), fst.toEnum())
            }
        }
    } catch (_: IndexOutOfBoundsException) {
        throw IllegalArgumentException()
    }


inline fun <reified T : Enum<T>> String.toEnumList(): List<T> =
    toUpperCase().split(" ").map { part -> part.toEnum() }


fun List<Enum<*>>.toString2() = joinToString(" ") { it.name }


fun String.toColor(): Color =
    STR_TO_COLOR[toLowerCase()] ?: Color.decode(this)

private val STR_TO_COLOR = mapOf(
    "white" to Color.WHITE, "lightgray" to Color.LIGHT_GRAY, "lightgrey" to Color.LIGHT_GRAY,
    "gray" to Color.GRAY, "grey" to Color.GRAY, "darkGray" to Color.DARK_GRAY, "darkGrey" to Color.DARK_GRAY,
    "black" to Color.BLACK, "red" to Color.RED, "pink" to Color.PINK, "orange" to Color.ORANGE,
    "yellow" to Color.YELLOW, "green" to Color.GREEN, "magenta" to Color.MAGENTA, "purple" to Color.MAGENTA,
    "cyan" to Color.CYAN, "blue" to Color.BLUE
)


fun Color.toString2() = "#%06x".format(rgb and 0xFFFFFF)


fun String.toFPS(): FPS {
    val str = replace(',', '.')
    return when {
        // We have a special case for three quite common fractional fps to make sure that they're as precise as possible.
        str.startsWith("23.9") -> FPS(24000, 1001)
        str.startsWith("29.9") -> FPS(30000, 1001)
        str.startsWith("59.9") -> FPS(60000, 1001)
        "/" in this -> {
            val parts = split("/")
            FPS(parts[0].toInt(nonNegative = true, nonZero = true), parts[1].toInt(nonNegative = true, nonZero = true))
        }
        else -> {
            val f = toFiniteFloat(nonNegative = true, nonZero = true)
            if (floor(f) != f)
                throw IllegalArgumentException()
            FPS(f.toInt(), 1)
        }
    }
}


fun FPS.toString2() = "$numerator/$denominator"


fun String.toFontSpec(): FontSpec {
    var height: Int? = null
    var extraLineSpacing = 0f
    var color: Color? = null

    fun parsePart(part: String): Boolean {
        if (height == null)
            try {
                height = part.toInt(nonNegative = true, nonZero = true)
                return true
            } catch (_: NumberFormatException) {
            }
        if (height == null && extraLineSpacing == 0f && "+" in part)
            try {
                val (heightStr, extraLineSpacingStr) = part.split("+")
                extraLineSpacing = extraLineSpacingStr.toFiniteFloat(nonNegative = true)
                height = heightStr.toInt(nonNegative = true, nonZero = true)
                return true
            } catch (_: NumberFormatException) {
            }
        if (color == null)
            try {
                color = part.toColor()
                return true
            } catch (_: NumberFormatException) {
            }
        return false
    }

    // We interpret the first sequence of consecutive non-parsable parts as font face name.
    val nameParts = mutableListOf<String>()
    var wasLastPartParsable = false
    for (part in this.split(" ").asReversed()) {
        val parsable = parsePart(part)
        if (!parsable) {
            if (wasLastPartParsable)
                nameParts.clear()
            nameParts.add(part)
        }
        wasLastPartParsable = parsable
    }

    if (nameParts.isEmpty() || height == null || color == null)
        throw IllegalArgumentException()
    return FontSpec(nameParts.joinToString(" "), height!!, extraLineSpacing, color!!)
}


fun FontSpec.toString2(): String {
    var str = "$name $heightPx"
    if (extraLineSpacingPx != 0f)
        str += "+$extraLineSpacingPx"
    str += " ${color.toString2()}"
    return str
}
