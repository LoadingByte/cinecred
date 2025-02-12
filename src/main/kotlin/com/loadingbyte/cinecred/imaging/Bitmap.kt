package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.ceilDiv
import jdk.incubator.vector.FloatVector
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVFrame.AV_NUM_DATA_POINTERS
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import java.lang.Byte.toUnsignedInt
import java.lang.Short.toUnsignedInt
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.*
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.max


/**
 * A bitmap is a handle to an off-heap array of pixels. That array is wrapped into an [AVFrame], enabling us to use
 * bitmaps directly with FFmpeg. The contents of a bitmap are described by an attached [Spec] object.
 *
 * A [BitmapConverter] can be used to convert bitmaps between different specs.
 *
 * Bitmaps are memory-managed by the garbage collector. However, as the off-heap memory is potentially invisible to the
 * GC, it greatly underestimates the memory consumption of bitmaps and hence frees them very late. As such, it is highly
 * advised to manually free bitmaps by calling [close].
 */
class Bitmap private constructor(
    val spec: Spec,
    /**
     * The underlying [AVFrame]. Only access it while in an [ifNotClosed] or [requireNotClosed] block. And if possible,
     * please use [memorySegment] and [linesize] instead.
     */
    val frame: AVFrame
) : AutoCloseable {

    private val arena = Arena.ofShared()
    private val closureListeners = CopyOnWriteArrayList<Runnable>()
    private val cleanable = CLEANER.register(this, CleanerAction(frame, arena, closureListeners))

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    private class CleanerAction(
        private val frame: AVFrame, private val arena: Arena, private val listeners: List<Runnable>
    ) : Runnable {
        override fun run() {
            listeners.forEach(Runnable::run)
            // Close the arena first, so that access to memory segments is prohibited before we free the memory.
            arena.close()
            av_frame_free(frame)
        }
    }

    private val closureProtector = ClosureProtector()

    // These MemorySegments are not only useful for accessing the bitmap, they hopefully also make the garbage
    // collector aware that this object holds a large chunk of off-heap memory, so when it looks for non-reachable
    // or soft-reachable objects to free under memory pressure, it actually considers this one.
    private val bufferSegment = frame.buf(0)?.let { buf ->
        MemorySegment.ofAddress(buf.data().address()).reinterpret(buf.size(), arena, null)
    }
    private val planeSegments = Array(spec.representation.pixelFormat.planes) { plane ->
        val addr = frame.data(plane).address()
        val size = frame.linesize(plane) * spec.resolution.heightPx.toLong()
        MemorySegment.ofAddress(addr).reinterpret(size, arena, null)
    }
    private val linesizes = IntArray(planeSegments.size, frame::linesize)

    /** Releases the underlying memory early. */
    override fun close() {
        closureProtector.close()
        cleanable.clean()
    }

    /** Keeps the bitmap open until the given action returns. If the bitmap is already closed, the action is not run. */
    fun <T> ifNotClosed(action: () -> T): T? = closureProtector.ifNotClosed(action)

    /** Keeps the bitmap open until the given action returns. If the bitmap is already closed, throws an exception. */
    fun <T> requireNotClosed(action: () -> T): T = closureProtector.requireNotClosed(action)

    /** Pay attention to never reference this bitmap object from the listener! */
    fun addClosureListener(listener: Runnable) {
        if (ifNotClosed { closureListeners += listener; Any() } == null) listener.run()
    }

    /**
     * Returns a safe [MemorySegment] that grants direct access to the given [plane] of the bitmap. When operating on
     * the raw [MemorySegment.address], or implicitly writing the address to some struct and then passing that struct to
     * a downcall handle, make sure to be in an [ifNotClosed] or [requireNotClosed] block. When instead using Java's
     * memory access methods or passing the segment directly to a downcall handle (as opposed to first resolving the raw
     * address), Java guarantees that the segment is not closed during the operation, so the *NotClosed blocks don't
     * need to be used.
     */
    fun memorySegment(plane: Int): MemorySegment =
        planeSegments.getOrElse(plane) {
            throw IllegalArgumentException("Bitmap only has ${planeSegments.size} planes, so $plane is out of bounds.")
        }

    /** Returns the number of bytes in one picture line of the given [plane], including padding. */
    fun linesize(plane: Int): Int =
        linesizes.getOrElse(plane) {
            throw IllegalArgumentException("Bitmap only has ${linesizes.size} planes, so $plane is out of bounds.")
        }

    /**
     * Returns whether each plane of the bitmap starts at a [BYTE_ALIGNMENT]-aligned position and has a linesize that is
     * a multiple of [BYTE_ALIGNMENT].
     */
    val isAligned: Boolean
        get() {
            for (plane in 0..<spec.representation.pixelFormat.planes)
                if (memorySegment(plane).address() % BYTE_ALIGNMENT != 0L || linesize(plane) % BYTE_ALIGNMENT != 0)
                    return false
            return true
        }

    /** Returns whether each plane of the bitmap is contiguous, i.e., has no padding bytes in between lines. */
    val isContiguous: Boolean
        get() {
            val width = spec.resolution.widthPx
            val pixelFormat = spec.representation.pixelFormat
            for (plane in 0..<pixelFormat.planes)
                if (linesize(plane) != width * pixelFormat.stepOfPlane(plane))
                    return false
            return true
        }

    fun sharesStorageWith(other: Bitmap): Boolean =
        bufferSegment != null && bufferSegment == other.bufferSegment

    /** Copies the bitmap object but shares the data. The new object can be [close]d without closing this one. */
    fun view(): Bitmap = reinterpretedView(spec)

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
        return allocateWithoutBufAndSetup(spec.copy(resolution = viewResolution, content = viewContent)) { viewFrame ->
            requireNotClosed { referenceBuffers(frame, viewFrame) }
            val pixelFormat = spec.representation.pixelFormat
            for (plane in 0..<pixelFormat.planes) {
                val ls = linesize(plane)
                val step = pixelFormat.stepOfPlane(plane)
                val realX = if (pixelFormat.hChromaSub != 0 && plane in 1..2) x shr pixelFormat.hChromaSub else x
                val realY = if (pixelFormat.vChromaSub != 0 && plane in 1..2) y shr pixelFormat.vChromaSub else y
                val offset = realY * ls + realX * step
                viewFrame.data(plane, BytePointer().position(memorySegment(plane).address() + offset))
                viewFrame.linesize(plane, ls * yStep)
            }
        }
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

    /** This method is guaranteed to return a new instance, which can be [close]d without closing the original one. */
    fun alphaPlaneView(): Bitmap {
        val pixelFormat = spec.representation.pixelFormat
        require(pixelFormat.isPlanar) { "Cannot get alpha plane as bitmap is not planar." }
        require(pixelFormat.hasAlpha) { "Cannot get alpha plane as bitmap does not have alpha." }
        val comp = pixelFormat.components.last()
        val depth = comp.depth
        val le = pixelFormat.byteOrder == ByteOrder.LITTLE_ENDIAN
        val viewPixelFormatCode = when {
            pixelFormat.isFloat -> when (depth) {
                32 -> if (le) AV_PIX_FMT_GRAYF32LE else AV_PIX_FMT_GRAYF32BE
                else -> throw IllegalArgumentException("Cannot get alpha plane of $depth-bit float bitmap.")
            }
            else -> when (depth) {
                8 -> AV_PIX_FMT_GRAY8
                9 -> if (le) AV_PIX_FMT_GRAY9LE else AV_PIX_FMT_GRAY9BE
                10 -> if (le) AV_PIX_FMT_GRAY10LE else AV_PIX_FMT_GRAY10BE
                12 -> if (le) AV_PIX_FMT_GRAY12LE else AV_PIX_FMT_GRAY12BE
                14 -> if (le) AV_PIX_FMT_GRAY14LE else AV_PIX_FMT_GRAY14BE
                16 -> if (le) AV_PIX_FMT_GRAY16LE else AV_PIX_FMT_GRAY16BE
                else -> throw IllegalArgumentException("Cannot get alpha plane of $depth-bit bitmap.")
            }
        }
        val viewRep = Representation(PixelFormat.of(viewPixelFormatCode))
        return allocateWithoutBufAndSetup(spec.copy(representation = viewRep)) { viewFrame ->
            requireNotClosed { referenceBuffers(frame, viewFrame) }
            viewFrame.data(0, BytePointer().position(memorySegment(comp.plane).address()))
            viewFrame.linesize(0, linesize(comp.plane))
        }
    }

    /** This method is guaranteed to return a new instance, which can be [close]d without closing the original one. */
    fun reinterpretedView(newSpec: Spec): Bitmap {
        require(spec.resolution == newSpec.resolution) {
            "Bitmap reinterpretation cannot change the resolution. Use a regular view instead."
        }
        val oldPixelFormat = spec.representation.pixelFormat
        val newPixelFormat = newSpec.representation.pixelFormat
        require(oldPixelFormat.isReinterpretableTo(newPixelFormat)) {
            "Pixel format $oldPixelFormat cannot be reinterpreted to pixel format $newPixelFormat."
        }
        return allocateWithoutBufAndSetup(newSpec) { viewFrame ->
            requireNotClosed { referenceBuffers(frame, viewFrame) }
            for (plane in 0..<newPixelFormat.planes) {
                viewFrame.data(plane, BytePointer().position(memorySegment(plane).address()))
                viewFrame.linesize(plane, linesize(plane))
            }
        }
    }

    fun zero(): Bitmap {
        checkNotNull(bufferSegment) { "Cannot zero a bitmap that wraps a custom memory segment." }.fill(0)
        return this
    }

    /** Clamps the color values of float bitmaps to [0, ceiling]. Assumes that alpha values are already in [0, 1]. */
    fun clampFloatColors(ceiling: Float? = 1f, promiseOpaque: Boolean = false) {
        val pixelFormat = spec.representation.pixelFormat
        check(pixelFormat.isFloat && pixelFormat.depth == 32) { "Can only clamp float32 bitmaps." }
        check(isAligned) { "Can only clamp aligned bitmaps." }
        require(ceiling == null || ceiling >= 1f) { "Cannot clamp to a ceiling < 1." }
        val (w, h) = spec.resolution
        val bo = pixelFormat.byteOrder
        val ls = linesize(0)
        val vecFloats = FloatVector.SPECIES_PREFERRED.length()
        val vec0 = FloatVector.zero(FloatVector.SPECIES_PREFERRED)
        val vecC = ceiling?.let { FloatVector.broadcast(FloatVector.SPECIES_PREFERRED, it) }
        // Notice: We have verified through benchmarks that the JIT successfully lifts the switching over this variable
        // out of the hot loop. Performance matches that of manual lifting.
        val action = if (ceiling == null) 0 else if (!pixelFormat.hasAlpha || promiseOpaque) 1 else 2
        if (!pixelFormat.isPlanar) {
            val seg = memorySegment(0)
            val stepsPerLine = ceilDiv(w * pixelFormat.components.size, vecFloats)
            for (y in 0L..<h.toLong()) {
                var idx = y * ls
                repeat(stepsPerLine) {
                    var vecRGBA = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, seg, idx, bo).max(vec0)
                    when (action) {
                        1 -> vecRGBA = vecRGBA.min(vecC)
                        2 -> vecRGBA = vecRGBA.min(vecRGBA.rearrange(SPREAD_ALPHA).mul(vecC))
                    }
                    vecRGBA.intoMemorySegment(seg, idx, bo)
                    idx += vecFloats * 4
                }
            }
        } else {
            val segR = memorySegment(2)
            val segG = memorySegment(0)
            val segB = memorySegment(1)
            val segA = if (action == 2) memorySegment(3) else null
            val stepsPerLine = ceilDiv(w, vecFloats)
            for (y in 0L..<h.toLong()) {
                var idx = y * ls
                repeat(stepsPerLine) {
                    var vecR = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, segR, idx, bo).max(vec0)
                    var vecG = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, segG, idx, bo).max(vec0)
                    var vecB = FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, segB, idx, bo).max(vec0)
                    when (action) {
                        1 -> {
                            vecR = vecR.min(vecC)
                            vecG = vecG.min(vecC)
                            vecB = vecB.min(vecC)
                        }
                        2 -> {
                            val vecL =
                                FloatVector.fromMemorySegment(FloatVector.SPECIES_PREFERRED, segA, idx, bo).mul(vecC)
                            vecR = vecR.min(vecL)
                            vecG = vecG.min(vecL)
                            vecB = vecB.min(vecL)
                        }
                    }
                    vecR.intoMemorySegment(segR, idx, bo)
                    vecG.intoMemorySegment(segG, idx, bo)
                    vecB.intoMemorySegment(segB, idx, bo)
                    idx += vecFloats * 4
                }
            }
        }
    }

    fun blit(src: Bitmap) {
        val srcSpecRes = src.spec.resolution
        blit(src, 0, 0, srcSpecRes.widthPx, srcSpecRes.heightPx, 0, 0, 1)
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
        val pixelFormat = spec.representation.pixelFormat
        for (plane in 0..<pixelFormat.planes) {
            val srcSeg = src.memorySegment(plane)
            val dstSeg = memorySegment(plane)
            val srcLs = src.linesize(plane)
            val dstLs = linesize(plane)
            val step = pixelFormat.stepOfPlane(plane).toLong()
            val hChromaSub = if (plane in 1..2) pixelFormat.hChromaSub else 0
            val vChromaSub = if (plane in 1..2) pixelFormat.vChromaSub else 0
            var l = 0
            while (l < srcHeight shr vChromaSub) {
                val srcOffset = ((srcY shr vChromaSub) + l) * srcLs + (srcX shr hChromaSub) * step
                val dstOffset = ((dstY shr vChromaSub) + l) * dstLs + (dstX shr hChromaSub) * step
                MemorySegment.copy(srcSeg, srcOffset, dstSeg, dstOffset, (srcWidth shr hChromaSub) * step)
                l += yStep
            }
        }
    }

    /** Like [blit], but crops the [src] if parts of it would be blitted outside of this bitmap. */
    fun blitLeniently(src: Bitmap, srcX: Int, srcY: Int, srcWidth: Int, srcHeight: Int, dstX: Int, dstY: Int) {
        val (srcSpecWidth, srcSpecHeight) = src.spec.resolution
        val (dstSpecWidth, dstSpecHeight) = spec.resolution
        val newSrcX = max(0, srcX + max(0, -dstX))
        val newSrcY = max(0, srcY + max(0, -dstY))
        val newDstX = max(0, dstX + max(0, -srcX))
        val newDstY = max(0, dstY + max(0, -srcY))
        val newSrcWidth = minOf(srcWidth, srcSpecWidth - newSrcX, dstSpecWidth - newDstX)
        val newSrcHeight = minOf(srcHeight, srcSpecHeight - newSrcY, dstSpecHeight - newDstY)
        if (newSrcWidth > 0 && newSrcHeight > 0)
            blit(src, newSrcX, newSrcY, newSrcWidth, newSrcHeight, newDstX, newDstY, 1)
    }

    fun blitComponent(src: Bitmap, srcComponent: PixelFormat.Component, dstComponent: PixelFormat.Component) {
        val srcPixelFormat = src.spec.representation.pixelFormat
        val dstPixelFormat = spec.representation.pixelFormat
        val componentSize = ceilDiv(srcComponent.depth, 8)
        val shift = dstComponent.shift - srcComponent.shift
        require(spec.resolution == src.spec.resolution) { "Source and dest resolutions differ." }
        require(srcComponent in srcPixelFormat.components) { "Source component does not belong to source." }
        require(dstComponent in dstPixelFormat.components) { "Dest component does not belong to dest." }
        require(srcComponent.depth == dstComponent.depth) { "Source and dest components have different depths." }
        require(componentSize in 1..2 || componentSize == 4) { "Component has not 1, 2, or 4 bytes." }
        val (width, height) = spec.resolution
        val srcSeg = src.memorySegment(srcComponent.plane)
        val dstSeg = memorySegment(dstComponent.plane)
        val srcLs = src.linesize(srcComponent.plane)
        val dstLs = linesize(dstComponent.plane)
        val srcBO = srcPixelFormat.byteOrder
        val dstBO = dstPixelFormat.byteOrder
        val srcStep = srcComponent.step
        val dstStep = dstComponent.step
        for (y in 0L..<height) {
            var s = y * srcLs + srcComponent.offset
            var d = y * dstLs + dstComponent.offset
            for (x in 0..<width) {
                when (componentSize) {
                    1 -> dstSeg.putByte(d, (toUnsignedInt(srcSeg.getByte(s)) shl shift).toByte())
                    2 -> dstSeg.putShort(d, dstBO, (toUnsignedInt(srcSeg.getShort(s, srcBO)) shl shift).toShort())
                    4 -> dstSeg.putInt(d, dstBO, srcSeg.getInt(s, srcBO) shl shift)
                }
                s += srcStep
                d += dstStep
            }
        }
    }

    fun blitComponent(src: Bitmap, srcComponentIndex: Int, dstComponentIndex: Int) {
        blitComponent(
            src,
            src.spec.representation.pixelFormat.components[srcComponentIndex],
            spec.representation.pixelFormat.components[dstComponentIndex]
        )
    }

    // Be aware that the following bulk get/put functions don't care about the actual pixel format, and hence can,
    // for example, read a 32bpp bitmap into an int array.
    // Implementation note: We've benchmarked MemorySegment.copy against ByteBuffer access, and on JDK 21, MemSeg wins.

    fun getB(rowBytes: Int, plane: Int = 0, dstOffset: Int = 0, byteOrder: ByteOrder? = null): ByteArray =
        get(ByteArray(dstOffset + spec.resolution.heightPx * rowBytes), rowBytes, plane, dstOffset, byteOrder)

    fun getS(rowShorts: Int, plane: Int = 0, dstOffset: Int = 0, byteOrder: ByteOrder? = null): ShortArray =
        get(ShortArray(dstOffset + spec.resolution.heightPx * rowShorts), rowShorts, plane, dstOffset, byteOrder)

    fun getI(rowInts: Int, plane: Int = 0, dstOffset: Int = 0, byteOrder: ByteOrder? = null): IntArray =
        get(IntArray(dstOffset + spec.resolution.heightPx * rowInts), rowInts, plane, dstOffset, byteOrder)

    fun getF(rowFloats: Int, plane: Int = 0, dstOffset: Int = 0, byteOrder: ByteOrder? = null): FloatArray =
        get(FloatArray(dstOffset + spec.resolution.heightPx * rowFloats), rowFloats, plane, dstOffset, byteOrder)

    fun <A : Any> get(dst: A, rowElems: Int, plane: Int = 0, dstOffset: Int = 0, byteOrder: ByteOrder? = null): A {
        getOrPut(dst, rowElems, plane, dstOffset, byteOrder) { arrIndex, seg, layout, segOffset, elems ->
            MemorySegment.copy(seg, layout, segOffset, dst, arrIndex, elems)
        }
        return dst
    }

    fun <A : Any> put(src: A, rowElems: Int, plane: Int = 0, srcOffset: Int = 0, byteOrder: ByteOrder? = null) {
        getOrPut(src, rowElems, plane, srcOffset, byteOrder) { arrIndex, seg, layout, segOffset, elems ->
            MemorySegment.copy(src, arrIndex, seg, layout, segOffset, elems)
        }
    }

    private inline fun getOrPut(
        array: Any,
        rowElems: Int,
        plane: Int,
        arrayOffset: Int,
        byteOrder: ByteOrder?,
        copy: (Int, MemorySegment, ValueLayout, Long, Int) -> Unit
    ) {
        val layout = when (array) {
            is ByteArray -> JAVA_BYTE
            is ShortArray -> JAVA_SHORT
            is IntArray -> JAVA_INT
            is FloatArray -> JAVA_FLOAT
            is DoubleArray -> JAVA_DOUBLE
            else -> throw IllegalArgumentException("Unsupported array type: ${array.javaClass.name}")
        }.withOrder(byteOrder ?: spec.representation.pixelFormat.byteOrder)

        val (width, height) = spec.resolution
        val frameRowBytes = linesize(plane)
        val elemBytes = layout.byteSize().toInt()
        val seg = memorySegment(plane)

        if (rowElems == frameRowBytes / elemBytes)
            copy(arrayOffset, seg, layout, 0L, height * rowElems)
        else {
            val stepBytes = spec.representation.pixelFormat.stepOfPlane(plane)
            check(stepBytes % elemBytes == 0) { "Plane w/ $stepBytes-byte step forbids $elemBytes-byte bulk get/put." }
            val meaningfulRowElems = width * (stepBytes / elemBytes)
            require(rowElems >= meaningfulRowElems) { "Bulk get/put stride $rowElems < $meaningfulRowElems." }
            for (y in 0..<height)
                copy(arrayOffset + y * rowElems, seg, layout, y * frameRowBytes.toLong(), meaningfulRowElems)
        }
    }


    companion object {

        /**
         * SIMD instructions require the buffers to have certain alignment. The strictest of them all is AVX-512, which
         * requires 512-bit alignment.
         *
         * In addition, a lot of our code conveniently depends on buffers and scan lines having sizes that are multiples
         * of various powers of two.
         *
         * Be aware that not all bitmaps are guaranteed to be fully aligned. Use the [isAligned] method to check that.
         */
        const val BYTE_ALIGNMENT = 64

        /**
         * Wraps the given [AVFrame] into a bitmap and thereby makes it memory-managed: once the bitmap is closed or
         * garbage-collected, the wrapped frame will be deallocated and the underlying buffer thereby dereferenced.
         */
        fun wrap(spec: Spec, frame: AVFrame): Bitmap {
            require(!frame.isNull) { "Cannot create a bitmap from a null frame." }
            applySpecToFrame(spec, frame)
            return Bitmap(spec, frame)
        }

        /**
         * Wraps the given [MemorySegment] into a bitmap, but doesn't make it memory-managed. When the memory is freed,
         * make sure to also close the bitmap, or otherwise access to it will result in a segfault.
         */
        fun wrap(spec: Spec, plane: MemorySegment, linesize: Int): Bitmap {
            require(plane.address() != 0L) { "Cannot create a bitmap from a null pointer." }
            return allocateWithoutBufAndSetup(spec) { frame ->
                frame.data(0, BytePointer().position(plane.address()))
                frame.linesize(0, linesize)
                // Note: It is important to leave frame.buf empty! Otherwise, we've observed that FFmpeg does
                // unpredictable things and tries to free the memory even though it really shouldn't!
            }
        }

        /**
         * Allocates a new bitmap with an [aligned][isAligned] buffer following the given spec. Be aware that the
         * content of the bitmap is undefined; if you need it to be zeroed, use [zero].
         */
        fun allocate(spec: Spec): Bitmap {
            return allocateWithoutBufAndSetup(spec) { frame ->
                // Allocate the buffer.
                av_frame_get_buffer(frame, BYTE_ALIGNMENT)
                    .ffmpegThrowIfErrnum("Could not allocate frame buffer")
            }
        }

        /**
         * Allocates a bitmap with a contiguous buffer, i.e., without padding, following the given spec. Be aware that
         * the content of the bitmap is undefined; if you need it to be zeroed, use [zero].
         */
        fun allocateContiguous(spec: Spec): Bitmap {
            return allocateWithoutBufAndSetup(spec) { frame ->
                // Allocate the buffer.
                av_frame_get_buffer(frame, 1)
                    .ffmpegThrowIfErrnum("Could not allocate frame buffer")
            }
        }

        private inline fun allocateWithoutBufAndSetup(spec: Spec, setup: (AVFrame) -> Unit): Bitmap {
            val frame = av_frame_alloc()
                .ffmpegThrowIfNull("Could not allocate frame struct")
            try {
                applySpecToFrame(spec, frame)
                setup(frame)
            } catch (t: Throwable) {
                av_frame_free(frame)
                throw t
            }
            return Bitmap(spec, frame)
        }

        private fun applySpecToFrame(spec: Spec, frame: AVFrame) {
            val cs = spec.representation.colorSpace
            frame.apply {
                width(spec.resolution.widthPx)
                height(spec.resolution.heightPx)
                format(spec.representation.pixelFormat.code)
                color_range(spec.representation.range.code)
                if (cs == null) {
                    color_primaries(AVCOL_PRI_UNSPECIFIED)
                    color_trc(AVCOL_TRC_LINEAR)
                } else {
                    color_primaries(if (cs.primaries.hasCode) cs.primaries.code else AVCOL_PRI_UNSPECIFIED)
                    color_trc(
                        if (cs.transfer.hasCode) cs.transfer.code(cs.primaries, spec.representation.pixelFormat.depth)
                        else AVCOL_TRC_UNSPECIFIED
                    )
                }
                when (spec.representation.pixelFormat.family) {
                    PixelFormat.Family.GRAY -> colorspace(AVCOL_SPC_UNSPECIFIED)
                    PixelFormat.Family.RGB -> colorspace(AVCOL_SPC_RGB)
                    PixelFormat.Family.YUV -> colorspace(spec.representation.yuvCoefficients!!.code)
                }
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

        private fun referenceBuffers(srcFrame: AVFrame, dstFrame: AVFrame) {
            // It is absolutely vital that we look at all buffer slots and not only the first one, because some bitmaps
            // received from FFmpeg have more than one buffer. Not accounting for this has previously led to strange and
            // hard-to-debug bugs.
            for (i in 0..<AV_NUM_DATA_POINTERS) {
                val bufRef = av_buffer_ref(srcFrame.buf(i) ?: break)
                    .ffmpegThrowIfNull("Could not create a reference to an existing frame buffer")
                dstFrame.buf(i, bufRef)
            }
        }

        private val SPREAD_ALPHA = FloatVector.SPECIES_PREFERRED.shuffleFromOp { i -> i / 4 * 4 + 3 }

    }


    data class Spec(
        val resolution: Resolution,
        val representation: Representation,
        val scan: Scan = Scan.PROGRESSIVE,
        val content: Content = Content.PROGRESSIVE_FRAME
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
        val range: Range,
        val colorSpace: ColorSpace?,
        val yuvCoefficients: YUVCoefficients?,
        /** One of the `AVCHROMA_LOC_*` constants. */
        val chromaLocation: Int,
        val alpha: Alpha
    ) {

        init {
            require(chromaLocation in 0..<AVCHROMA_LOC_NB)
            require((pixelFormat.family == PixelFormat.Family.GRAY) == (colorSpace == null))
            require((pixelFormat.family == PixelFormat.Family.YUV) == (yuvCoefficients != null))
            require(pixelFormat.hasChromaSub == (chromaLocation != AVCHROMA_LOC_UNSPECIFIED))
            require(pixelFormat.hasAlpha == (alpha != Alpha.OPAQUE))
        }

        constructor(pixelFormat: PixelFormat) :
                this(pixelFormat, Range.FULL, colorSpace = null, Alpha.OPAQUE)

        constructor(pixelFormat: PixelFormat, colorSpace: ColorSpace?, alpha: Alpha) :
                this(pixelFormat, Range.FULL, colorSpace, alpha)

        constructor(pixelFormat: PixelFormat, range: Range, colorSpace: ColorSpace?, alpha: Alpha) :
                this(pixelFormat, range, colorSpace, yuvCoefficients = null, AVCHROMA_LOC_UNSPECIFIED, alpha)

    }


    class PixelFormat private constructor(
        /** One of the `AV_PIX_FMT_*` constants. */
        val code: Int
    ) {

        val name: String
        val isPlanar: Boolean
        val isBitstream: Boolean
        val family: Family
        val hasAlpha: Boolean
        val isFloat: Boolean
        val byteOrder: ByteOrder
        private val _depth: Int
        val hChromaSub: Int
        val vChromaSub: Int
        val components: List<Component>
        val planes: Int

        init {
            val desc = av_pix_fmt_desc_get(code)
                .ffmpegThrowIfNull("Could not retrieve pixel format descriptor")
            val f = desc.flags()

            require(code != AV_PIX_FMT_XYZ12LE && code != AV_PIX_FMT_XYZ12BE) { "XYZ pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_PAL.toLong() == 0L) { "Palette pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_BAYER.toLong() == 0L) { "Bayer pixel formats are not supported." }
            require(f and AV_PIX_FMT_FLAG_HWACCEL.toLong() == 0L) { "Hardware accel pixel formats are not supported." }

            name = desc.name().string
            isBitstream = f and AV_PIX_FMT_FLAG_BITSTREAM.toLong() != 0L
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

            family = when {
                components.size <= 2 -> Family.GRAY
                f and AV_PIX_FMT_FLAG_RGB.toLong() != 0L -> Family.RGB
                else -> Family.YUV
            }

            val depth = components[0].depth
            _depth = if (components.all { it.depth == depth }) depth else -1

            planes = components.maxOf(Component::plane) + 1
            isPlanar = desc.nb_components().toInt() == planes
        }

        val depth: Int
            get() = if (_depth != -1) _depth else throw IllegalStateException("Two components have different depths.")

        val hasChromaSub: Boolean
            get() = hChromaSub != 0 || vChromaSub != 0

        private val _planeSteps by lazy {
            val steps = IntArray(planes) { -1 }
            for (component in components) {
                val plane = component.plane
                if (steps[plane] == -1)
                    steps[plane] = component.step
                else if (steps[plane] != component.step)
                    throw IllegalStateException("Two components define different steps for plane $plane.")
            }
            steps
        }

        fun stepOfPlane(plane: Int): Int =
            _planeSteps.getOrElse(plane) { throw IllegalArgumentException("Unknown plane: $plane") }

        fun isReinterpretableTo(other: PixelFormat): Boolean {
            if (planes < other.planes) return false
            for (plane in 0..<other.planes) if (_planeSteps[plane] != other._planeSteps[plane]) return false
            return hChromaSub == other.hChromaSub && vChromaSub == other.vChromaSub
        }

        // Note: We don't need to override equals() and hashCode() because there's always only one object for each code.
        override fun toString() = name

        enum class Family { GRAY, RGB, YUV }
        data class Component(val plane: Int, val step: Int, val offset: Int, val shift: Int, val depth: Int)

        companion object {

            private val cache = AtomicReferenceArray<PixelFormat>(AV_PIX_FMT_NB)

            fun of(code: Int): PixelFormat {
                cache.get(code)?.let { return it }
                cache.compareAndSet(code, null, PixelFormat(code))
                return cache.get(code)
            }

        }

    }


    enum class Range(
        /** One of the `AVCOL_RANGE_*` constants. */
        val code: Int
    ) {

        FULL(AVCOL_RANGE_JPEG),
        LIMITED(AVCOL_RANGE_MPEG);

        companion object {
            fun of(code: Int): Range = when (code) {
                AVCOL_RANGE_JPEG -> FULL
                AVCOL_RANGE_MPEG -> LIMITED
                else -> throw IllegalArgumentException("Unknown color range: $code")
            }
        }

    }


    class YUVCoefficients private constructor(
        /** One of the `AVCOL_SPC_*` constants. */
        val code: Int,
        val name: String
    ) {

        // Note: We don't need to override equals() and hashCode() because there's always only one object for each code.
        override fun toString() = name

        companion object {

            private val CODE_BASED = arrayOfNulls<YUVCoefficients?>(AVCOL_SPC_NB)

            init {
                populateCodeBased()
            }

            /** @throws IllegalArgumentException If the [code] does not refer to YUV coefficients. */
            fun of(code: Int): YUVCoefficients =
                requireNotNull(CODE_BASED.getOrNull(code)) { "Unknown YUV coefficients code: $code" }

            val BT709_NCL: YUVCoefficients = of(AVCOL_SPC_BT709)
            val SRGB_NCL: YUVCoefficients = of(AVCOL_SPC_BT470BG)
            val BT2020_NCL: YUVCoefficients = of(AVCOL_SPC_BT2020_NCL)
            val BT2020_CL: YUVCoefficients = of(AVCOL_SPC_BT2020_CL)
            val ICTCP: YUVCoefficients = of(AVCOL_SPC_ICTCP)

            private fun populateCodeBased() {
                addCB(AVCOL_SPC_BT709, "BT.709")
                addCB(AVCOL_SPC_FCC, "FCC")
                addCB(AVCOL_SPC_BT470BG, "BT.470 BG")
                addCB(AVCOL_SPC_SMPTE170M, "ST 170 M")
                addCB(AVCOL_SPC_SMPTE240M, "ST 240 M")
                addCB(AVCOL_SPC_YCGCO, "YCgCo")
                addCB(AVCOL_SPC_BT2020_NCL, "BT.2020 Non-Constant Luminance")
                addCB(AVCOL_SPC_BT2020_CL, "BT.2020 Constant Luminance")
                addCB(AVCOL_SPC_SMPTE2085, "Y'D'zD'x")
                addCB(AVCOL_SPC_CHROMA_DERIVED_NCL, "Chromaticity-Derived Constant Luminance")
                addCB(AVCOL_SPC_CHROMA_DERIVED_CL, "Chromaticity-Derived Non-Constant Luminance")
                addCB(AVCOL_SPC_ICTCP, "ICtCp")
            }

            private fun addCB(code: Int, name: String) {
                CODE_BASED[code] = YUVCoefficients(code, name)
            }

        }

    }


    enum class Alpha {
        /** The bitmap has no alpha channel. */
        OPAQUE,
        /** The bitmap has an alpha channel that is not premultiplied into the color channels. */
        STRAIGHT,
        /**
         * The bitmap has an alpha channel that is premultiplied into the color channels.
         *
         * Note: While premultiplication and color primaries conversion commute, premultiplication and transfer
         * characteristics conversion don't. So there are two ways that, e.g., "premultiplied sRGB" could be understood:
         * either we convert the linear colors to sRGB and then premultiply, or we premultiply the linear colors and
         * then convert to sRGB. We choose the first way, which is also employed by TIFF's associated alpha, and in
         * general seems to be the standard way of doing this.
         */
        PREMULTIPLIED
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

}
