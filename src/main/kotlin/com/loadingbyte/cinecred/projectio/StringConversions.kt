package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import java.awt.Color
import kotlin.math.floor


fun String.toInt(nonNeg: Boolean, non0: Boolean = false): Int {
    val i = toInt()
    if (nonNeg && i < 0 || non0 && i == 0)
        throw NumberFormatException()
    return i
}


fun String.toFiniteFloat(nonNeg: Boolean = false, non0: Boolean = false): Float {
    val f = replace(',', '.').toFloat()
    if (!f.isFinite() || nonNeg && f < 0f || non0 && f == 0f)
        throw NumberFormatException()
    return f
}


inline fun <reified T : Enum<T>> String.toEnum(): T = enumValueOf(toUpperCase())
inline fun <reified T : Enum<T>> String.toEnumList(): List<T> = split(" ").map { part -> part.toEnum() }
fun List<Enum<*>>.toString2() = joinToString(" ") { it.name }


fun String.toColor(): Color = if (length == 7) Color.decode(this) else Color(Integer.decode(this), true)
fun Color.toHex24() = "#%06x".format(rgb and 0x00ffffff)
fun Color.toHex32() = "#%08x".format(rgb)


fun String.toFPS(): FPS {
    val str = replace(',', '.')
    return when {
        // We have a special case for three quite common fractional fps to make sure that they're as precise as possible.
        str.startsWith("23.9") -> FPS(24000, 1001)
        str.startsWith("29.9") -> FPS(30000, 1001)
        str.startsWith("59.9") -> FPS(60000, 1001)
        "/" in this -> {
            val parts = split("/")
            FPS(parts[0].toInt(nonNeg = true, non0 = true), parts[1].toInt(nonNeg = true, non0 = true))
        }
        else -> {
            val f = toFiniteFloat(nonNeg = true, non0 = true)
            require(floor(f) == f)
            FPS(f.toInt(), 1)
        }
    }
}


fun FPS.toString2() = "$numerator/$denominator"
