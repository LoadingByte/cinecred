package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import java.awt.Color


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


// Note: We first have to convert to long and then to int because Integer.decode() throws an exception when a
// overflowing number is decoded (which happens whenever alpha > 128, since the first bit of the color number is then 1,
// which is interpreted as a negative sign, so this is an overflow).
fun String.toColor(allowAlpha: Boolean = true) = Color(java.lang.Long.decode(this).toInt(), allowAlpha)
fun Color.toHex24() = "#%06x".format(rgb and 0x00ffffff)
fun Color.toHex32() = "#%08x".format(rgb)


fun String.toFPS(): FPS {
    val parts = split("/")
    require(parts.size == 2)
    return FPS(parts[0].toInt(nonNeg = true, non0 = true), parts[1].toInt(nonNeg = true, non0 = true))
}


fun FPS.toString2() = "$numerator/$denominator"
