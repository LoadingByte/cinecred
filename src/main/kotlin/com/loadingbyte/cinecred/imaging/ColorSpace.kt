package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.natives.skcms.skcms_h.*
import com.loadingbyte.cinecred.natives.skiacapi.skiacapi_h.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow


class ColorSpace private constructor(val primaries: Primaries, val transfer: Transfer) {

    val skiaHandle: MemorySegment by lazy {
        check(transfer.hasCurve) { "Only transfer characteristics with a standard curve admit a Skia color space." }
        val c = transfer.toLinear
        val m = primaries.toXYZD50.values
        SkColorSpace_MakeRGB(c.g, c.a, c.b, c.c, c.d, c.e, c.f, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8])
    }

    private val bmpConvCache = ConcurrentHashMap<ColorSpace, SoftReference<Triple<Bitmap, Bitmap, BitmapConverter>>>()

    fun convert(dst: ColorSpace, colors: FloatArray, alpha: Boolean, clamp: Boolean = false, ceiling: Float? = 1f) {
        if (this != dst)
            if (transfer.hasCurve && dst.transfer.hasCurve) {
                transfer.toLinear(colors, alpha)
                primaries.toXYZD50(colors, alpha)
                dst.primaries.fromXYZD50(colors, alpha)
                dst.transfer.fromLinear(colors, alpha)
            } else {
                val numComps = if (alpha) 4 else 3
                require(colors.size % numComps == 0)
                val w = colors.size / numComps
                val alp = if (alpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
                var srcBitmap: Bitmap? = null
                var dstBitmap: Bitmap? = null
                var converter: BitmapConverter? = null
                val cached = bmpConvCache.remove(dst)?.get()
                if (cached != null) {
                    srcBitmap = cached.first
                    dstBitmap = cached.second
                    converter = cached.third
                }
                if (srcBitmap == null || dstBitmap == null || converter == null ||
                    srcBitmap.spec.resolution.widthPx != w || srcBitmap.spec.representation.alpha != alp
                ) {
                    val res = Resolution(w, 1)
                    val pixelFormat = Bitmap.PixelFormat.of(if (alpha) AV_PIX_FMT_RGBAF32 else AV_PIX_FMT_RGBF32)
                    val srcSpec = Bitmap.Spec(res, Bitmap.Representation(pixelFormat, this, alp))
                    val dstSpec = Bitmap.Spec(res, Bitmap.Representation(pixelFormat, dst, alp))
                    srcBitmap = Bitmap.allocate(srcSpec)
                    dstBitmap = Bitmap.allocate(dstSpec)
                    converter = BitmapConverter(srcSpec, dstSpec)
                }
                srcBitmap.put(colors, colors.size)
                converter.convert(srcBitmap, dstBitmap)
                dstBitmap.get(colors, colors.size)
                bmpConvCache[dst] = SoftReference(Triple(srcBitmap, dstBitmap, converter))
            }
        if (clamp)
            for (i in colors.indices)
                if (!alpha || i and 3 != 3)
                    colors[i] = colors[i].coerceIn(0f, ceiling)
    }

    override fun toString() = "$primaries/$transfer"


    companion object {

        private val cache = ConcurrentHashMap<Pair<Primaries, Transfer>, ColorSpace>()

        fun of(primaries: Primaries, transfer: Transfer): ColorSpace =
            cache.computeIfAbsent(Pair(primaries, transfer)) { ColorSpace(primaries, transfer) }

        val XYZD50: ColorSpace = of(Primaries.XYZD50, Transfer.LINEAR)
        val BT709: ColorSpace = of(Primaries.BT709, Transfer.BT1886)
        val SRGB: ColorSpace = of(Primaries.BT709, Transfer.SRGB)

    }


    class Primaries private constructor(
        private val _code: Int,
        val name: String,
        val chromaticities: Chromaticities?,
        val toXYZD50: Matrix,
        val fromXYZD50: Matrix
    ) {

        val hasCode: Boolean get() = _code >= 0

        /** One of the `AVCOL_PRI_*` constants. */
        val code: Int
            get() = if (hasCode) _code else throw IllegalStateException("Primaries $name do not have a code.")

        override fun equals(other: Any?) =
            this === other || other is Primaries && _code == other._code && (_code != -1 || toXYZD50 == other.toXYZD50)

        override fun hashCode() = if (_code != -1) _code else toXYZD50.hashCode()
        override fun toString() = name

        /** @param values The values of the matrix in row-major order. */
        class Matrix(val values: FloatArray) {

            operator fun invoke(colors: FloatArray, alpha: Boolean) {
                if (this === XYZD50.toXYZD50)
                    return
                val m = values
                var i = 0
                while (i < colors.size) {
                    val c0 = colors[i + 0]
                    val c1 = colors[i + 1]
                    val c2 = colors[i + 2]
                    colors[i + 0] = m[0] * c0 + m[1] * c1 + m[2] * c2
                    colors[i + 1] = m[3] * c0 + m[4] * c1 + m[5] * c2
                    colors[i + 2] = m[6] * c0 + m[7] * c1 + m[8] * c2
                    i += if (alpha) 4 else 3
                }
            }

            operator fun times(other: Matrix): Matrix {
                val m = values
                val n = other.values
                val o = floatArrayOf(
                    m[0] * n[0] + m[1] * n[3] + m[2] * n[6],
                    m[0] * n[1] + m[1] * n[4] + m[2] * n[7],
                    m[0] * n[2] + m[1] * n[5] + m[2] * n[8],
                    m[3] * n[0] + m[4] * n[3] + m[5] * n[6],
                    m[3] * n[1] + m[4] * n[4] + m[5] * n[7],
                    m[3] * n[2] + m[4] * n[5] + m[5] * n[8],
                    m[6] * n[0] + m[7] * n[3] + m[8] * n[6],
                    m[6] * n[1] + m[7] * n[4] + m[8] * n[7],
                    m[6] * n[2] + m[7] * n[5] + m[8] * n[8]
                )
                return Matrix(o)
            }

            override fun equals(other: Any?) = other === this || other is Matrix && values.contentEquals(other.values)
            override fun hashCode() = values.contentHashCode()

        }

        data class Chromaticities(
            val rx: Float,
            val ry: Float,
            val gx: Float,
            val gy: Float,
            val bx: Float,
            val by: Float,
            val wx: Float,
            val wy: Float
        )

        companion object {

            private val CODE_BASED = arrayOfNulls<Primaries>(AVCOL_PRI_NB)

            init {
                populateCodeBased()
            }

            /** @throws IllegalArgumentException If the [code] does not refer to primaries. */
            fun of(code: Int): Primaries =
                requireNotNull(CODE_BASED.getOrNull(code)) { "Unknown primaries code: $code" }

            fun of(toXYZD50: Matrix): Primaries =
                obtainCustom(null, toXYZD50)

            /** @throws IllegalArgumentException If the chromaticities cannot be turned into a toXYZD50 matrix. */
            fun of(chromaticities: Chromaticities): Primaries =
                obtainCustom(chromaticities, toXYZD50(chromaticities))

            /** @throws IllegalArgumentException If the white point cannot be turned into a toXYZD50 matrix. */
            fun of(wx: Float, wy: Float): Primaries =
                obtainCustom(null, toXYZD50(wx, wy))

            val XYZD50: Primaries
            val XYZD65: Primaries = invertAndMake(-3, "XYZ-D65", null, toXYZD50(0.3127f, 0.329f))
            val BT709: Primaries = of(AVCOL_PRI_BT709)
            val DCI_P3: Primaries = of(AVCOL_PRI_SMPTE431)
            val DISPLAY_P3: Primaries = of(AVCOL_PRI_SMPTE432)
            val BT2020: Primaries = of(AVCOL_PRI_BT2020)

            init {
                // Our code expects that for XYZD50, toXYZD50 and fromXYZD50 are the same object.
                val idMatrix = Matrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
                XYZD50 = Primaries(-2, "XYZ-D50", null, idMatrix, idMatrix)
            }

            val COMMON = listOf(BT709, DCI_P3, DISPLAY_P3, BT2020)

            private fun obtainCustom(chroma: Chromaticities?, toXYZD50: Matrix) = when {
                areClose(toXYZD50.values, BT709.toXYZD50.values) -> BT709
                areClose(toXYZD50.values, XYZD50.toXYZD50.values) -> XYZD50
                else -> invertAndMake(-1, "Custom", chroma, toXYZD50)
            }

            private fun areClose(m1: FloatArray, m2: FloatArray): Boolean {
                // Threshold of 0.01 taken from color_space_almost_equal() in Skia's SkColorSpacePriv.h file.
                for (i in m1.indices) if (abs(m1[i] - m2[i]) > 0.01f) return false
                return true
            }

            private fun invertAndMake(code: Int, name: String, chroma: Chromaticities?, toXYZD50: Matrix): Primaries {
                val fromXYZD50 = Arena.ofConfined().use { arena ->
                    val inSeg = arena.allocateArray(toXYZD50.values)
                    val outSeg = arena.allocateArray(JAVA_FLOAT, 9)
                    require(skcms_Matrix3x3_invert(inSeg, outSeg)) { "Could not invert toXYZD50 matrix." }
                    Matrix(outSeg.toArray(JAVA_FLOAT))
                }
                return Primaries(code, name, chroma, toXYZD50, fromXYZD50)
            }

            private fun populateCodeBased() {
                val bt601 = Chromaticities(0.63f, 0.34f, 0.31f, 0.595f, 0.155f, 0.07f, 0.3127f, 0.329f)
                // Where possible, use the exact matrices from Skia because that (a) apparently improves performance and
                // (b) allows the ICC profile generator to detect them and thereby generate meaningful profile names.
                addCB(
                    AVCOL_PRI_BT709, "BT.709",
                    Chromaticities(0.64f, 0.33f, 0.3f, 0.6f, 0.15f, 0.06f, 0.3127f, 0.329f),
                    toXYZD50(SkNamedGamut_SRGB())
                )
                addCB(
                    AVCOL_PRI_BT470M, "BT.470 M",
                    Chromaticities(0.67f, 0.33f, 0.21f, 0.71f, 0.14f, 0.08f, 0.31f, 0.316f)
                )
                addCB(
                    AVCOL_PRI_BT470BG, "BT.470 BG",
                    Chromaticities(0.64f, 0.33f, 0.29f, 0.6f, 0.15f, 0.06f, 0.3127f, 0.329f)
                )
                addCB(AVCOL_PRI_SMPTE170M, "ST 170 M", bt601)
                addCB(AVCOL_PRI_SMPTE240M, "ST 240 M", bt601)
                addCB(
                    AVCOL_PRI_FILM, "Film",
                    Chromaticities(0.681f, 0.319f, 0.243f, 0.692f, 0.145f, 0.049f, 0.31f, 0.316f)
                )
                addCB(
                    AVCOL_PRI_BT2020, "BT.2020",
                    Chromaticities(0.708f, 0.292f, 0.17f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.329f),
                    toXYZD50(SkNamedGamut_Rec2020())
                )
                addCB(
                    AVCOL_PRI_SMPTE428, "ST 428",
                    Chromaticities(1f, 0f, 0f, 1f, 0f, 0f, 1f / 3f, 1f / 3f)
                )
                addCB(
                    AVCOL_PRI_SMPTE431, "DCI-P3",
                    Chromaticities(0.68f, 0.32f, 0.265f, 0.69f, 0.15f, 0.06f, 0.314f, 0.351f)
                )
                addCB(
                    AVCOL_PRI_SMPTE432, "Display P3",
                    Chromaticities(0.68f, 0.32f, 0.265f, 0.69f, 0.15f, 0.06f, 0.3127f, 0.329f),
                    toXYZD50(SkNamedGamut_DisplayP3())
                )
                addCB(
                    AVCOL_PRI_EBU3213, "EBU 3213-E",
                    Chromaticities(0.63f, 0.34f, 0.295f, 0.605f, 0.155f, 0.077f, 0.3127f, 0.329f)
                )
            }

            private fun addCB(code: Int, name: String, chroma: Chromaticities, toXYZD50: Matrix = toXYZD50(chroma)) {
                CODE_BASED[code] = invertAndMake(code, name, chroma, toXYZD50)
            }

            private fun toXYZD50(ptr: MemorySegment) =
                Matrix(ptr.reinterpret(9 * 4).toArray(JAVA_FLOAT))

            private fun toXYZD50(c: Chromaticities): Matrix =
                Arena.ofConfined().use { arena ->
                    val seg = arena.allocateArray(JAVA_FLOAT, 9)
                    require(skcms_PrimariesToXYZD50(c.rx, c.ry, c.gx, c.gy, c.bx, c.by, c.wx, c.wy, seg))
                    Matrix(seg.toArray(JAVA_FLOAT))
                }

            private fun toXYZD50(wx: Float, wy: Float): Matrix =
                Arena.ofConfined().use { arena ->
                    val seg = arena.allocateArray(JAVA_FLOAT, 9)
                    require(skcms_AdaptToXYZD50(wx, wy, seg))
                    Matrix(seg.toArray(JAVA_FLOAT))
                }

        }

    }


    class Transfer private constructor(
        private val _code: Int,
        val name: String,
        val isHDR: Boolean,
        private val _toLinear: Curve?,
        private val _fromLinear: Curve?
    ) {

        val hasCode: Boolean get() = _code >= 0
        val hasCurve: Boolean get() = _toLinear != null

        /** One of the `AVCOL_TRC_*` constants. */
        val code: Int
            get() = if (hasCode) _code else throw IllegalStateException("Transfer function $name does not have a code.")

        val toLinear: Curve
            get() = checkNotNull(_toLinear) { "Transfer characteristics $name do not have a known curve." }
        val fromLinear: Curve
            get() = checkNotNull(_fromLinear) { "Transfer characteristics $name do not have a known curve." }

        override fun equals(other: Any?) =
            this === other || other is Transfer && _code == other._code && (_code != -1 || toLinear == other.toLinear)

        override fun hashCode() = if (_code != -1) _code else toLinear.hashCode()
        override fun toString() = name

        /**
         * A transfer function which maps encoded values to linear values, or vice-versa.
         * It is represented by the following 7-parameter piecewise function:
         *
         *     y =  (c * x + f)         if 0 <= x < d
         *       = ((a * x + b)^g + e)  if d <= x
         *
         * A simple gamma transfer function sets g = gamma, a = 1, and the rest = 0.
         */
        data class Curve(
            val g: Float,
            val a: Float,
            val b: Float,
            val c: Float,
            val d: Float,
            val e: Float,
            val f: Float
        ) {

            private val skiaHandle = Arena.ofAuto().allocateArray(asArray())

            constructor(g: Float) : this(g, 1f, 0f, 0f, 0f, 0f, 0f)
            constructor(g: Float, a: Float) : this(g, a, 0f, 0f, 0f, 0f, 0f)
            constructor(a: FloatArray) : this(a[0], a[1], a[2], a[3], a[4], a[5], a[6])

            fun asArray() = floatArrayOf(g, a, b, c, d, e, f)

            operator fun invoke(value: Float): Float =
                if (this === LINEAR.toLinear) value else skcms_TransferFunction_eval(skiaHandle, value)

            operator fun invoke(colors: FloatArray, alpha: Boolean) {
                if (this === LINEAR.toLinear)
                    return
                val skiaHandle = this.skiaHandle
                for (i in colors.indices)
                    if (!alpha || i and 3 != 3)
                        colors[i] = skcms_TransferFunction_eval(skiaHandle, colors[i])
            }

        }

        companion object {

            private val CODE_BASED = arrayOfNulls<Transfer>(AVCOL_TRC_NB)

            init {
                populateCodeBased()
            }

            /** @throws IllegalArgumentException If the [code] does not refer to transfer characteristics. */
            fun of(code: Int): Transfer =
                requireNotNull(CODE_BASED.getOrNull(code)) { "Unknown transfer characteristics code: $code" }

            fun of(toLinear: Curve): Transfer {
                val c1 = toLinear
                val c2 = SRGB.toLinear
                if (abs(c1.g - c2.g) < 0.001f && abs(c1.a - c2.a) < 0.001f && abs(c1.b - c2.b) < 0.001f &&
                    abs(c1.c - c2.c) < 0.001f && abs(c1.d - c2.d) < 0.001f && abs(c1.e - c2.e) < 0.001f &&
                    abs(c1.f - c2.f) < 0.001f
                ) return SRGB
                // Taken from is_almost_linear() in Skia's SkColorSpacePriv.h file.
                val linearExp = abs(c1.a - 1f) < 0.001f && abs(c1.b) < 0.001f && abs(c1.e) < 0.001f &&
                        abs(c1.g - 1f) < 0.001f && c1.d <= 0f
                val linearFn = abs(c1.c - 1f) < 0.001f && abs(c1.f) < 0.001f && c1.d >= 1f
                if (linearExp || linearFn)
                    return LINEAR
                return Transfer(-1, "Custom", false, toLinear, invert(toLinear))
            }

            val LINEAR: Transfer = of(AVCOL_TRC_LINEAR)
            val BT1886: Transfer = of(AVCOL_TRC_BT709)
            val SRGB: Transfer = of(AVCOL_TRC_IEC61966_2_1)
            val PQ: Transfer = of(AVCOL_TRC_SMPTE2084)
            val HLG: Transfer = of(AVCOL_TRC_ARIB_STD_B67)

            /**
             * All color blending (i.e., alpha compositing) is not performed in a linear space, but in a nonlinear space
             * with pure gamma 2.2 transfer characteristics. This has the following advantages:
             *   - From decades of computer graphics, users became used to blending happening in sRGB. In the range
             *     [0, 1], gamma 2.2 is practically indistinguishable from sRGB, so we meet user expectation.
             *   - Linear color is not perceptually uniform, i.e., doubling the color value (which for linear color
             *     directly corresponds to the number of photons) doesn't lead to double the perceived brightness. In
             *     contrast, sRGB and its close cousin gamma 2.2 do a better job of modelling this, so they provide a
             *     pretty good scale of perceived brightness. Due to this, linearly increasing the alpha is actually
             *     perceived as a linear fade-in. Additionally, in a linear space, the antialiasing of text and other
             *     vector graphics makes white-on-black text appear thicker than black-on-white text. The perceptual
             *     brightness modelling of gamma 2.2 fixes this imbalance.
             *   - So why choose pure gamma 2.2 instead of sRGB then? sRGB actually has a small linear section near 0,
             *     followed by an offset gamma 2.4 curve. While this closely matches a pure gamma in the range [0, 1],
             *     we also encounter color values larger than 1 when rendering HDR content, and the higher the values,
             *     the more the two transfer characteristics deviate. We however want the same brightness modelling to
             *     apply throughout the entire brightness range, and we want our blending to be conceptually equivalent
             *     to first scaling down all color values to fit into [0, 1], then compositing, and then scaling them
             *     back up. This uniformity and self-similarity is only achieved by a pure gamma curve.
             */
            val BLENDING: Transfer = of(AVCOL_TRC_GAMMA22)

            val COMMON = listOf(LINEAR, BT1886, SRGB, PQ, HLG)

            private fun invert(toLinear: Curve): Curve =
                Arena.ofConfined().use { arena ->
                    val inSeg = arena.allocateArray(toLinear.asArray())
                    val outSeg = arena.allocateArray(JAVA_FLOAT, 7)
                    require(skcms_TransferFunction_invert(inSeg, outSeg)) { "Could not invert transfer curve." }
                    Curve(outSeg.toArray(JAVA_FLOAT))
                }

            private fun populateCodeBased() {
                val linear = Curve(1f)
                // We use BT1886 in various cases to mirror what zimg does (i.e., work display-referred).
                val bt1886 = Curve(2.4f)
                val bt1886Inv = invert(bt1886)
                addCB(AVCOL_TRC_BT709, "BT.1886", false, bt1886, bt1886Inv)
                addCB(AVCOL_TRC_GAMMA22, "Gamma 2.2", false, Curve(2.2f))
                addCB(AVCOL_TRC_GAMMA28, "Gamma 2.8", false, Curve(2.8f))
                addCB(AVCOL_TRC_SMPTE170M, "BT.1886 (ST 170 M)", false, bt1886, bt1886Inv)
                addCB(AVCOL_TRC_SMPTE240M, "BT.1886 (ST 240 M)", false, bt1886, bt1886Inv)
                // Our code expects that for linear, toLinear and fromLinear are the same object.
                addCB(AVCOL_TRC_LINEAR, "Linear", false, linear, linear)
                // We don't need the curves of these TRCs, so we might as well leave them empty for now.
                addCB(AVCOL_TRC_LOG, "Log 100", false, null)
                addCB(AVCOL_TRC_LOG_SQRT, "Log 316", false, null)
                addCB(AVCOL_TRC_IEC61966_2_4, "IEC 61966-2.4", false, null)
                addCB(AVCOL_TRC_BT1361_ECG, "BT.1361 ECG", false, null)
                // Where possible, use the exact numbers from Skia because that (a) apparently improves performance and
                // (b) allows the ICC profile generator to detect them and thereby generate meaningful profile names.
                addCB(AVCOL_TRC_IEC61966_2_1, "sRGB", false, toLinear(SkNamedTransferFn_SRGB()))
                addCB(AVCOL_TRC_BT2020_10, "BT.1886 (BT.2020 10-bit)", false, bt1886, bt1886Inv)
                addCB(AVCOL_TRC_BT2020_12, "BT.1886 (BT.2020 12-bit)", false, bt1886, bt1886Inv)
                // Our Curve class doesn't support the PQ and HLG formulations, so we'll leave them empty.
                addCB(AVCOL_TRC_SMPTE2084, "PQ", true, null)
                addCB(AVCOL_TRC_SMPTE428, "ST 428", false, Curve(2.6f, (52.37 / 48.0).pow(1.0 / 2.6).toFloat()))
                addCB(AVCOL_TRC_ARIB_STD_B67, "HLG", true, null)
            }

            private fun addCB(
                code: Int, name: String, isHDR: Boolean, toLinear: Curve?, fromLinear: Curve? = toLinear?.let(::invert)
            ) {
                CODE_BASED[code] = Transfer(code, name, isHDR, toLinear, fromLinear)
            }

            private fun toLinear(ptr: MemorySegment) =
                Curve(ptr.reinterpret(7 * 4).toArray(JAVA_FLOAT))

        }

    }

}
