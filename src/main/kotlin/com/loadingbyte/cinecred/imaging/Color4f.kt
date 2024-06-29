package com.loadingbyte.cinecred.imaging

import java.awt.Color
import kotlin.math.floor
import kotlin.math.roundToInt


data class Color4f(val r: Float, val g: Float, val b: Float, val a: Float, val colorSpace: ColorSpace) {

    init {
        require(a in 0f..1f) { "Alpha must be between 0 and 1, so $a is out of bounds." }
    }

    constructor(r: Float, g: Float, b: Float, colorSpace: ColorSpace) : this(r, g, b, 1f, colorSpace)

    constructor(rgba: FloatArray, colorSpace: ColorSpace) : this(
        rgba[0], rgba[1], rgba[2], rgba.getOrElse(3) { 1f }, colorSpace
    )

    constructor(rgba: List<Number>, colorSpace: ColorSpace) : this(
        rgba[0].toFloat(), rgba[1].toFloat(), rgba[2].toFloat(), rgba.getOrElse(3) { 1f }.toFloat(), colorSpace
    )

    fun rgb() = floatArrayOf(r, g, b)
    fun rgba() = floatArrayOf(r, g, b, a)

    fun isClamped(ceiling: Float? = 1f): Boolean =
        r >= 0f && g >= 0f && b >= 0f && (ceiling == null || r <= ceiling && g <= ceiling && b <= ceiling)

    fun convert(toColorSpace: ColorSpace, clamp: Boolean = false, ceiling: Float? = 1f): Color4f {
        if (!clamp && colorSpace == toColorSpace)
            return this
        val rgb = rgb()
        colorSpace.convert(toColorSpace, rgb, alpha = false, clamp, ceiling)
        return Color4f(rgb[0], rgb[1], rgb[2], a, toColorSpace)
    }

    fun toHSB(): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val diff = max - min
        val s = if (max != 0f) diff / max else 0f
        var h = 0f
        if (s != 0f) {
            val rc = (max - r) / diff
            val gc = (max - g) / diff
            val bc = (max - b) / diff
            h = (if (r == max) bc - gc else if (g == max) 2f + rc - bc else 4f + gc - rc) / 6f
            if (h < 0f)
                h += 1f
        }
        return floatArrayOf(h, s, max)
    }

    fun toSRGBPacked(): Int {
        val c = convert(ColorSpace.SRGB, clamp = true)
        return ((c.r * 255f).roundToInt() shl 16) or
                ((c.g * 255f).roundToInt() shl 8) or
                (c.b * 255f).roundToInt()
    }

    fun toSRGBHexString(): String =
        "#%06X".format(toSRGBPacked())

    fun toSRGBAWT(): Color {
        val c = convert(ColorSpace.SRGB, clamp = true)
        return Color(c.r, c.g, c.b, a)
    }

    companion object {

        val BLACK = fromSRGBHexString("#000000")
        val WHITE = fromSRGBHexString("#FFFFFF")
        val GRAY = fromSRGBHexString("#808080")
        val ORANGE = fromSRGBHexString("#FFC800")

        fun fromHSB(h: Float, s: Float, b: Float, colorSpace: ColorSpace): Color4f =
            if (s == 0f)
                Color4f(b, b, b, colorSpace)
            else {
                val w = (h - floor(h)) * 6f
                val f = w - floor(w)
                val p = b * (1f - s)
                val q = b * (1f - s * f)
                val t = b * (1f - (s * (1f - f)))
                when (w.toInt()) {
                    0 -> Color4f(b, t, p, colorSpace)
                    1 -> Color4f(q, b, p, colorSpace)
                    2 -> Color4f(p, b, t, colorSpace)
                    3 -> Color4f(p, q, b, colorSpace)
                    4 -> Color4f(t, p, b, colorSpace)
                    5 -> Color4f(b, p, q, colorSpace)
                    else -> throw IllegalStateException()
                }
            }

        fun fromSRGBPacked(argb: Int) = Color4f(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            ColorSpace.SRGB
        )

        fun fromSRGBHexString(hex: String): Color4f {
            val hash = hex[0] == '#'
            require(hex.length == if (hash) 7 else 6) { "sRGB hex string '$hex' has wrong length." }
            return fromSRGBPacked((if (hash) hex.substring(1) else hex).toInt(16))
        }

    }

}
