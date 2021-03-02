package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.FPS
import com.loadingbyte.cinecred.project.FontSpec
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
fun Color.toString2() = "#%08x".format(rgb)


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


fun String.toFontSpec(): FontSpec {
    var height: Int? = null
    var color: Color? = null

    fun parsePart(part: String): Boolean {
        if (height == null)
            try {
                height = part.toInt(nonNeg = true, non0 = true)
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

    require(nameParts.isNotEmpty() && height != null && color != null)
    return FontSpec(nameParts.asReversed().joinToString(" "), height!!, color!!)
}


fun FontSpec.toString2() = "$name $heightPx ${color.toString2()}"
