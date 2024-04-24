package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.ceilDiv
import com.loadingbyte.cinecred.imaging.Bitmap.Spec
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVFrame.AV_NUM_DATA_POINTERS
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.Pointer
import java.lang.Byte.toUnsignedLong
import java.lang.Float.float16ToFloat
import java.lang.Float.floatToFloat16
import java.lang.Short.toUnsignedLong
import java.lang.foreign.MemorySegment
import java.nio.ByteOrder


/**
 * A bitmap is a handle to an off-heap array of pixels. That array is wrapped into an [AVFrame], enabling us to use
 * bitmaps directly with FFmpeg. In contrast to BufferedImages, which by our convention always use the sRGB color space
 * and a 3 or 4 byte interleaved (A)BGR pixel format, bitmaps can use any representation. The contents of a bitmap are
 * described by an attached [Spec] object.
 *
 * The [Bitmap2BitmapConverter] can be used to convert bitmaps between different specs. There are additional converters
 * available to convert between bitmaps and BufferedImages, or even only between BufferedImages, but using FFmpeg's
 * APIs underneath.
 *
 * Bitmaps are memory-managed by the garbage collector. Still, if need be, they can be released early via [close].
 */
class Bitmap private constructor(val spec: Spec, private val _frame: AVFrame) : AutoCloseable {

    init {
        CLEANER.register(this, CleanerAction(_frame))
    }

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    private class CleanerAction(private val frame: AVFrame) : Runnable {
        override fun run() = av_frame_free(frame)
    }

    /** Releases the underlying memory early. After having called this, calls to memory-accessing methods will throw. */
    override fun close() {
        // Notice that multiple calls to av_frame_free() are fine as that function checks for null pointers.
        av_frame_free(frame)
    }

    val frame: AVFrame
        get() {
            check(!_frame.isNull) { "The bitmap is closed." }
            return _frame
        }

    /** This method is guaranteed to return a new instance, which can be [close]d without closing the original one. */
    fun view(x: Int, y: Int, width: Int, height: Int, yStep: Int): Bitmap {
        val (specWidth, specHeight) = spec.resolution
        require(x >= 0) { "Left view coordinate $x is < 0." }
        require(y >= 0) { "Top view coordinate $y is < 0." }
        require(width >= 1) { "View width $width is < 1." }
        require(height >= 1) { "View height $height is < 1." }
        require(yStep >= 1) { "Vertical view step $yStep is < 1." }
        require(x + width <= specWidth) { "Right view coordinate $x + $width exceeds bitmap width $specWidth." }
        require(y + height <= specHeight) { "Bottom view coordinate $y + $height exceeds bitmap height $specHeight." }
        val viewResolution = Resolution(width, ceilDiv(height, yStep))
        val evenY = y % 2 == 0
        val evenYSkip = yStep % 2 == 0
        val viewContent = when (spec.content) {
            Content.INTERLEAVED_FIELDS -> when {
                evenYSkip && evenY -> Content.ONLY_TOP_FIELD
                evenYSkip && !evenY -> Content.ONLY_BOT_FIELD
                !evenYSkip && evenY -> Content.INTERLEAVED_FIELDS
                else -> Content.INTERLEAVED_FIELDS_REVERSED
            }
            Content.INTERLEAVED_FIELDS_REVERSED -> when {
                evenYSkip && evenY -> Content.ONLY_BOT_FIELD
                evenYSkip && !evenY -> Content.ONLY_TOP_FIELD
                !evenYSkip && evenY -> Content.INTERLEAVED_FIELDS_REVERSED
                else -> Content.INTERLEAVED_FIELDS
            }
            else -> spec.content
        }
        val view = allocateWithoutBuffer(spec.copy(resolution = viewResolution, content = viewContent))
        val thisFrame = frame
        val viewFrame = view.frame
        for (i in 0..<AV_NUM_DATA_POINTERS) {
            val bufRef = av_buffer_ref(thisFrame.buf(i) ?: break)
                .ffmpegThrowIfNull("Could not create a reference to an existing frame buffer")
            viewFrame.buf(i, bufRef)
        }
        val pixelFormat = spec.representation.pixelFormat
        for (plane in 0..<4) {
            val data = thisFrame.data(plane) ?: continue
            val ls = thisFrame.linesize(plane)
            val step = pixelFormat.stepOfPlane(plane)
            val realX = if (pixelFormat.hChromaSub != 0 && plane != 0) x shr pixelFormat.hChromaSub else x
            val realY = if (pixelFormat.vChromaSub != 0 && plane != 0) y shr pixelFormat.vChromaSub else y
            viewFrame.linesize(plane, ls * yStep)
            viewFrame.data(plane, data.position((realY * ls + realX * step).toLong()))
        }
        return view
    }

    /** This method is guaranteed to return a new instance, which can be [close]d without closing the original one. */
    fun topFieldView(): Bitmap =
        when (spec.content) {
            Content.INTERLEAVED_FIELDS -> view(0, 0, spec.resolution.widthPx, spec.resolution.heightPx, 2)
            Content.INTERLEAVED_FIELDS_REVERSED -> view(0, 1, spec.resolution.widthPx, spec.resolution.heightPx - 1, 2)
            else -> throw IllegalArgumentException("Cannot get top field view as bitmap is not interleaved fields.")
        }

    /** This method is guaranteed to return a new instance, which can be [close]d without closing the original one. */
    fun botFieldView(): Bitmap =
        when (spec.content) {
            Content.INTERLEAVED_FIELDS -> view(0, 1, spec.resolution.widthPx, spec.resolution.heightPx - 1, 2)
            Content.INTERLEAVED_FIELDS_REVERSED -> view(0, 0, spec.resolution.widthPx, spec.resolution.heightPx, 2)
            else -> throw IllegalArgumentException("Cannot get bottom field view as bitmap is not interleaved fields.")
        }

    fun blit(src: Bitmap, srcX: Int, srcY: Int, srcWidth: Int, srcHeight: Int, dstX: Int, dstY: Int, yStep: Int) {
        val (srcSpecWidth, srcSpecHeight) = src.spec.resolution
        val (dstSpecWidth, dstSpecHeight) = spec.resolution
        require(spec.representation == src.spec.representation) { "Source and dest bitmap representations differ." }
        require(srcX >= 0) { "Source x coordinate $srcX is < 0." }
        require(srcY >= 0) { "Source y coordinate $srcY is < 0." }
        require(srcWidth >= 1) { "Source width $srcWidth is < 1." }
        require(srcHeight >= 1) { "Source height $srcHeight is < 1." }
        require(dstX >= 0) { "Dest x coordinate $dstX is < 0." }
        require(dstY >= 0) { "Dest y coordinate $dstY is < 0." }
        require(yStep >= 1) { "Vertical blit step $yStep is < 1." }
        require(srcX + srcWidth <= srcSpecWidth) { "$srcX + $srcWidth exceeds source width $srcSpecWidth." }
        require(dstX + srcWidth <= dstSpecWidth) { "$dstX + $srcWidth exceeds dest width $dstSpecWidth." }
        require(srcY + srcHeight <= srcSpecHeight) { "$srcY + $srcHeight exceeds source height $srcSpecHeight." }
        require(dstY + srcHeight <= dstSpecHeight) { "$dstY + $srcHeight exceeds dest height $dstSpecHeight." }
        if (spec.representation.pixelFormat.vChromaSub != 0)
            require(yStep == 1) { "Under vertical chroma subsampling, the vertical blit step must be 1, not $yStep." }
        val srcFrame = src.frame
        val dstFrame = frame
        val pixelFormat = spec.representation.pixelFormat
        for (plane in 0..<pixelFormat.planes) {
            val srcData = srcFrame.data(plane)
            val dstData = dstFrame.data(plane)
            val srcLs = srcFrame.linesize(plane)
            val dstLs = dstFrame.linesize(plane)
            val step = pixelFormat.stepOfPlane(plane).toLong()
            val hChromaSub = if (plane in 1..2) pixelFormat.hChromaSub else 0
            val vChromaSub = if (plane in 1..2) pixelFormat.vChromaSub else 0
            var l = 0
            while (l < srcHeight shr vChromaSub) {
                val srcPtr = srcData.position(((srcY shr vChromaSub) + l) * srcLs + (srcX shr hChromaSub) * step)
                val dstPtr = dstData.position(((dstY shr vChromaSub) + l) * dstLs + (dstX shr hChromaSub) * step)
                Pointer.memcpy(dstPtr, srcPtr, (srcWidth shr hChromaSub) * step)
                l += yStep
            }
        }
    }


    companion object {

        /**
         * SIMD instructions require the buffers to have certain alignment. The strictest of them all is AVX-512, which
         * requires 512-bit alignment.
         */
        const val BYTE_ALIGNMENT = 64

        /**
         * Wraps the given [AVFrame] into a bitmap and thereby makes it memory-managed: once the bitmap is closed or
         * garbage-collected, the wrapped frame will be deallocated and the underlying buffer thereby dereferenced.
         */
        fun wrap(spec: Spec, frame: AVFrame): Bitmap {
            applySpecToFrame(spec, frame)
            return Bitmap(spec, frame)
        }

        /** Allocates a new frame with a corresponding buffer following the given spec. */
        fun allocate(spec: Spec): Bitmap {
            val bitmap = allocateWithoutBuffer(spec)
            // If this operation throws an exception, the bitmap will be garbage-collected and thus cleaned.
            av_frame_get_buffer(bitmap.frame, 8 * BYTE_ALIGNMENT)
                .ffmpegThrowIfErrnum("Could not allocate frame buffer")
            return bitmap
        }

        private fun allocateWithoutBuffer(spec: Spec): Bitmap {
            val frame = av_frame_alloc()
                .ffmpegThrowIfNull("Could not allocate frame struct")
            applySpecToFrame(spec, frame)
            return Bitmap(spec, frame)
        }

        private fun applySpecToFrame(spec: Spec, frame: AVFrame) {
            frame.apply {
                width(spec.resolution.widthPx)
                height(spec.resolution.heightPx)
                format(spec.representation.pixelFormat.code)
                color_range(spec.representation.range)
                color_primaries(spec.representation.primaries)
                color_trc(spec.representation.transferCharacteristic)
                colorspace(spec.representation.yCbCrCoefficients)
                chroma_location(spec.representation.chromaLocation)
                // If the video is interlaced, mark the frame that we send to the encoder accordingly.
                // If the field order is bff, but we don't specify that for every single frame, the resulting file would
                // have an additional (and wrong) metadata entry showing "original scan order = tff".
                if (spec.scan != Scan.PROGRESSIVE) {
                    interlaced_frame(1)
                    top_field_first(if (spec.scan == Scan.INTERLACED_TOP_FIELD_FIRST) 1 else 0)
                }
            }
        }

    }


    data class Spec(
        val resolution: Resolution,
        val representation: Representation,
        val scan: Scan,
        val content: Content
    ) {
        init {
            require(resolution.widthPx > 0 && resolution.heightPx > 0)
            require((scan == Scan.PROGRESSIVE) == (content == Content.PROGRESSIVE_FRAME))
            // Disallow interlacing if vertical chroma subsampling is enabled, as that introduces all kinds of problems.
            require(scan == Scan.PROGRESSIVE || representation.pixelFormat.vChromaSub == 0) {
                "Interlacing can't be used together with vertical subsampling."
            }
        }
    }


    data class Representation(
        val pixelFormat: PixelFormat,
        /** One of the `AVCOL_RANGE_*` constants. */
        val range: Int,
        /** One of the `AVCOL_PRI_*` constants. */
        val primaries: Int,
        /** One of the `AVCOL_TRC_*` constants. */
        val transferCharacteristic: Int,
        /** One of the `AVCOL_SPC_*` constants. */
        val yCbCrCoefficients: Int,
        /** One of the `AVCHROMA_LOC_*` constants. */
        val chromaLocation: Int,
        val isAlphaPremultiplied: Boolean
    ) {
        init {
            require(range in 0..<AVCOL_RANGE_NB && range != AVCOL_RANGE_UNSPECIFIED)
            require(primaries in 0..<AVCOL_PRI_NB && primaries != AVCOL_PRI_UNSPECIFIED)
            require(transferCharacteristic in 0..<AVCOL_TRC_NB && transferCharacteristic != AVCOL_TRC_UNSPECIFIED)
            require(yCbCrCoefficients in 0..<AVCOL_SPC_NB && yCbCrCoefficients != AVCOL_SPC_UNSPECIFIED)
            require(chromaLocation in 0..<AVCHROMA_LOC_NB)
            require(pixelFormat.isRGB == (yCbCrCoefficients == AVCOL_SPC_RGB))
            require(pixelFormat.hasChromaSub == (chromaLocation != AVCHROMA_LOC_UNSPECIFIED))
            require(!isAlphaPremultiplied || pixelFormat.hasAlpha)
        }
    }


    class PixelFormat private constructor(
        /** One of the `AV_PIX_FMT_*` constants. */
        val code: Int
    ) {

        val isPlanar: Boolean
        val isBitstream: Boolean
        val isRGB: Boolean
        val hasAlpha: Boolean
        val isFloat: Boolean
        val byteOrder: ByteOrder
        val hChromaSub: Int
        val vChromaSub: Int
        val components: List<Component>

        init {
            val desc = av_pix_fmt_desc_get(code)
                .ffmpegThrowIfNull("Could not retrieve pixel format descriptor")
            val f = desc.flags()

            require(code != AV_PIX_FMT_XYZ12LE && code != AV_PIX_FMT_XYZ12BE) { "XYZ pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_PAL.toLong() == 0L) { "Palette pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_BAYER.toLong() == 0L) { "Bayer pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_HWACCEL.toLong() == 0L) { "Hardware accel pixel formats are not supported." }

            val foundPlanes = BooleanArray(4)
            for (i in 0..<desc.nb_components())
                foundPlanes[desc.comp(i).plane()] = true
            val numPlanes = foundPlanes.count { it }

            isPlanar = desc.nb_components().toInt() == numPlanes
            isBitstream = f and AV_PIX_FMT_FLAG_BITSTREAM.toLong() != 0L
            isRGB = f and AV_PIX_FMT_FLAG_RGB.toLong() != 0L
            hasAlpha = f and AV_PIX_FMT_FLAG_ALPHA.toLong() != 0L
            isFloat = f and AV_PIX_FMT_FLAG_FLOAT.toLong() != 0L
            byteOrder = if (f and AV_PIX_FMT_FLAG_BE.toLong() != 0L) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            hChromaSub = desc.log2_chroma_w().toInt()
            vChromaSub = desc.log2_chroma_h().toInt()

            components = buildList {
                for (i in 0..<desc.nb_components()) {
                    val c = desc.comp(i)
                    add(Component(c.plane(), c.step(), c.offset(), c.shift(), c.depth()))
                }
            }
        }

        val hasChromaSub: Boolean
            get() = hChromaSub != 0 || vChromaSub != 0

        private val _planeSteps by lazy {
            val steps = IntArray(4) { -1 }
            for (component in components) {
                val plane = component.plane
                if (steps[plane] == -1)
                    steps[plane] = component.step
                else if (steps[plane] != component.step)
                    throw IllegalStateException("Two components define different steps for plane $plane.")
            }
            steps
        }

        fun stepOfPlane(plane: Int): Int {
            val step = _planeSteps[plane]
            require(step != -1) { "\"Unknown plane: $plane\"" }
            return step
        }

        override fun toString() = code.toString()

        data class Component(val plane: Int, val step: Int, val offset: Int, val shift: Int, val depth: Int)

        companion object {
            private val cache = arrayOfNulls<PixelFormat>(AV_PIX_FMT_NB)
            fun of(code: Int): PixelFormat = cache[code] ?: PixelFormat(code).also { cache[code] = it }
        }

    }


    enum class Content {
        /** The bitmap contains a progressive frame. */
        PROGRESSIVE_FRAME,
        /** The bitmap contains only the top field of an interlaced frame. */
        ONLY_TOP_FIELD,
        /** The bitmap contains only the bottom field of an interlaced frame. */
        ONLY_BOT_FIELD,
        /** The bitmap contains both fields of an interlaced frame, with the top field coded first. */
        INTERLEAVED_FIELDS,
        /** The bitmap contains both fields of an interlaced frame, with the bottom field coded first. */
        INTERLEAVED_FIELDS_REVERSED
    }


    enum class Scan {
        /** The video stream is progressive. */
        PROGRESSIVE,
        /** The video stream is interlaced, and the top field is displayed first. */
        INTERLACED_TOP_FIELD_FIRST,
        /** The video stream is interlaced, and the bottom field is displayed first. */
        INTERLACED_BOT_FIELD_FIRST
    }


    /**
     * This class provides methods for reading and writing pixels in one component of a bitmap.
     *
     * The class is written in such a way that it hopefully exploits the JIT optimizations of method inlining and
     * code motion (specifically expression hoisting and loop unswitching). For optimal performance, do not store
     * accessor objects in an array, but only directly in local fields.
     */
    class Accessor(bitmap: Bitmap, component: PixelFormat.Component) {

        private val switch: Int
        private val offset: Int
        private val step: Int
        private val ls: Int
        private val seg: MemorySegment

        init {
            val pixelFormat = bitmap.spec.representation.pixelFormat
            val componentIdx = pixelFormat.components.indexOf(component)

            require(componentIdx != -1)
            require(!pixelFormat.isBitstream) { "Cannot access bitstream bitmaps." }
            require(component.shift == 0) { "Cannot access shifted bitmap components." }

            val isShallow = if (!pixelFormat.isFloat) {
                require(component.depth <= 16) { "Integer pixel bit depth ${component.depth} exceeds 16." }
                component.depth <= 8
            } else {
                require(component.depth <= 32) { "Float pixel bit depth ${component.depth} exceeds 32." }
                component.depth == 16
            }
            val isLE = bitmap.spec.representation.pixelFormat.byteOrder == ByteOrder.LITTLE_ENDIAN
            switch = (if (isShallow) 0 else 2) or (if (isLE) 0 else 1)

            offset = component.offset
            step = component.step
            ls = bitmap.frame.linesize(component.plane)

            val size = ls * bitmap.spec.resolution.heightPx.toLong()
            seg = MemorySegment.ofAddress(bitmap.frame.data(component.plane).address()).reinterpret(size)
        }

        fun getL(x: Int, y: Int): Long {
            val addr = (offset + y * ls + x * step).toLong()
            return when (switch) {
                0, 1 -> toUnsignedLong(seg.getByte(addr))
                2 -> toUnsignedLong(seg.getShortLE(addr))
                3 -> toUnsignedLong(seg.getShortBE(addr))
                else -> throw IllegalStateException()
            }
        }

        fun putL(x: Int, y: Int, value: Long) {
            val addr = (offset + y * ls + x * step).toLong()
            when (switch) {
                0, 1 -> seg.putByte(addr, value.toByte())
                2 -> seg.putShortLE(addr, value.toShort())
                3 -> seg.putShortBE(addr, value.toShort())
            }
        }

        fun getF(x: Int, y: Int): Float {
            val addr = (offset + y * ls + x * step).toLong()
            return when (switch) {
                0 -> float16ToFloat(seg.getShortLE(addr))
                1 -> float16ToFloat(seg.getShortBE(addr))
                2 -> seg.getFloatLE(addr)
                3 -> seg.getFloatBE(addr)
                else -> throw IllegalStateException()
            }
        }

        fun putF(x: Int, y: Int, value: Float) {
            val addr = (offset + y * ls + x * step).toLong()
            when (switch) {
                0 -> seg.putShortLE(addr, floatToFloat16(value))
                1 -> seg.putShortBE(addr, floatToFloat16(value))
                2 -> seg.putFloatLE(addr, value)
                3 -> seg.putFloatBE(addr, value)
            }
        }

    }

}
