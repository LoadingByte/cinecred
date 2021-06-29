package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import java.awt.Color


fun String.toFiniteFloat(nonNeg: Boolean = false, non0: Boolean = false): Float {
    val f = replace(',', '.').toFloat()
    if (!f.isFinite() || nonNeg && f < 0f || non0 && f == 0f)
        throw NumberFormatException()
    return f
}


fun <T> String.toEnum(enumClass: Class<T>): T =
    enumClass.enumConstants.first { (it as Enum<*>).name.equals(this, ignoreCase = true) }


// Note: We first have to convert to long and then to int because String.toInt() throws an exception when an
// overflowing number is decoded (which happens whenever alpha > 128, since the first bit of the color number is then 1,
// which is interpreted as a negative sign, so this is an overflow).
fun String.hexToColor(): Color {
    require(length == 7 || length == 9 && get(0) == '#')
    return Color(drop(1).toLong(16).toInt(), length == 9)
}

fun Color.toHex24() = "#%06x".format(rgb and 0x00FFFFFF)
fun Color.toHex32() = "#%08x".format(rgb)


fun String.toFPS(): FPS {
    val parts = split("/")
    require(parts.size == 2)
    return FPS(parts[0].toInt(), parts[1].toInt())
}

fun FPS.toString2() = "$numerator/$denominator"
