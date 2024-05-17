package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.SystemInfo
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.awt.image.BufferedImage
import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger


fun MemorySegment.getByte(offset: Long) = MEM_SEG_BYTE_HANDLE.get(this, offset) as Byte
fun MemorySegment.putByte(offset: Long, value: Byte) = MEM_SEG_BYTE_HANDLE.set(this, offset, value)

fun MemorySegment.getShortLE(offset: Long) = MEM_SEG_SHORT_LE_HANDLE.get(this, offset) as Short
fun MemorySegment.getShortBE(offset: Long) = MEM_SEG_SHORT_BE_HANDLE.get(this, offset) as Short

// Note: We have confirmed in a benchmark that all this switching gets optimized away nicely when called in a loop, so
// e.g. on a little-endian machine, calling getShort() becomes just as fast as calling getShortLE().
fun MemorySegment.getShort(offset: Long): Short = getShort(offset, ByteOrder.nativeOrder())
fun MemorySegment.getShort(offset: Long, order: ByteOrder): Short =
    if (order === ByteOrder.LITTLE_ENDIAN) getShortLE(offset) else getShortBE(offset)

fun MemorySegment.putShortLE(offset: Long, value: Short) = MEM_SEG_SHORT_LE_HANDLE.set(this, offset, value)
fun MemorySegment.putShortBE(offset: Long, value: Short) = MEM_SEG_SHORT_BE_HANDLE.set(this, offset, value)

fun MemorySegment.putShort(offset: Long, value: Short) = putShort(offset, ByteOrder.nativeOrder(), value)
fun MemorySegment.putShort(offset: Long, order: ByteOrder, value: Short) =
    if (order === ByteOrder.LITTLE_ENDIAN) putShortLE(offset, value) else putShortBE(offset, value)

fun MemorySegment.getIntLE(offset: Long) = MEM_SEG_INT_LE_HANDLE.get(this, offset) as Int
fun MemorySegment.getIntBE(offset: Long) = MEM_SEG_INT_BE_HANDLE.get(this, offset) as Int

fun MemorySegment.getInt(offset: Long): Int = getInt(offset, ByteOrder.nativeOrder())
fun MemorySegment.getInt(offset: Long, order: ByteOrder): Int =
    if (order === ByteOrder.LITTLE_ENDIAN) getIntLE(offset) else getIntBE(offset)

fun MemorySegment.putIntLE(offset: Long, value: Int) = MEM_SEG_INT_LE_HANDLE.set(this, offset, value)
fun MemorySegment.putIntBE(offset: Long, value: Int) = MEM_SEG_INT_BE_HANDLE.set(this, offset, value)

fun MemorySegment.putInt(offset: Long, value: Int) = putInt(offset, ByteOrder.nativeOrder(), value)
fun MemorySegment.putInt(offset: Long, order: ByteOrder, value: Int) =
    if (order === ByteOrder.LITTLE_ENDIAN) putIntLE(offset, value) else putIntBE(offset, value)

fun MemorySegment.getFloatLE(offset: Long) = MEM_SEG_FLOAT_LE_HANDLE.get(this, offset) as Float
fun MemorySegment.getFloatBE(offset: Long) = MEM_SEG_FLOAT_BE_HANDLE.get(this, offset) as Float

fun MemorySegment.getFloat(offset: Long): Float = getFloat(offset, ByteOrder.nativeOrder())
fun MemorySegment.getFloat(offset: Long, order: ByteOrder): Float =
    if (order === ByteOrder.LITTLE_ENDIAN) getFloatLE(offset) else getFloatBE(offset)

fun MemorySegment.putFloatLE(offset: Long, value: Float) = MEM_SEG_FLOAT_LE_HANDLE.set(this, offset, value)
fun MemorySegment.putFloatBE(offset: Long, value: Float) = MEM_SEG_FLOAT_BE_HANDLE.set(this, offset, value)

fun MemorySegment.putFloat(offset: Long, value: Float) = putFloat(offset, ByteOrder.nativeOrder(), value)
fun MemorySegment.putFloat(offset: Long, order: ByteOrder, value: Float) =
    if (order === ByteOrder.LITTLE_ENDIAN) putFloatLE(offset, value) else putFloatBE(offset, value)

private val MEM_SEG_BYTE_HANDLE = makeVarHandle(JAVA_BYTE, ByteOrder.LITTLE_ENDIAN)
// Note: We have benchmarked whether aligned or unaligned ValueLayouts are faster here, and unaligned was ~1.35x faster.
private val MEM_SEG_SHORT_LE_HANDLE = makeVarHandle(JAVA_SHORT_UNALIGNED, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_SHORT_BE_HANDLE = makeVarHandle(JAVA_SHORT_UNALIGNED, ByteOrder.BIG_ENDIAN)
private val MEM_SEG_INT_LE_HANDLE = makeVarHandle(JAVA_INT_UNALIGNED, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_INT_BE_HANDLE = makeVarHandle(JAVA_INT_UNALIGNED, ByteOrder.BIG_ENDIAN)
private val MEM_SEG_FLOAT_LE_HANDLE = makeVarHandle(JAVA_FLOAT_UNALIGNED, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_FLOAT_BE_HANDLE = makeVarHandle(JAVA_FLOAT_UNALIGNED, ByteOrder.BIG_ENDIAN)

private fun makeVarHandle(layout: ValueLayout, order: ByteOrder): VarHandle =
    MethodHandles.memorySegmentViewVarHandle(layout.withOrder(order)).withInvokeBehavior()


fun SegmentAllocator.allocateArray(elems: ByteArray): MemorySegment = allocateArray(JAVA_BYTE, elems.size.toLong())
    .also { MemorySegment.copy(elems, 0, it, JAVA_BYTE, 0L, elems.size) }

fun SegmentAllocator.allocateArray(elems: CharArray): MemorySegment = allocateArray(JAVA_CHAR, elems.size.toLong())
    .also { MemorySegment.copy(elems, 0, it, JAVA_CHAR, 0L, elems.size) }

fun SegmentAllocator.allocateArray(elems: FloatArray): MemorySegment = allocateArray(JAVA_FLOAT, elems.size.toLong())
    .also { MemorySegment.copy(elems, 0, it, JAVA_FLOAT, 0L, elems.size) }


/**
 * Invokes setlocale(LC_NUMERIC, "C") to make future calls to strtod() and printf() use dots as the decimal separator.
 * This is needed because both Skia's SVG parser and SVG writer use those functions to read/write numbers.
 * Notice that we can't simply invoke this once at program startup because Java tends to undo it sometimes.
 */
fun setNativeNumericLocaleToC() {
    val lcNumeric = if (SystemInfo.isWindows || SystemInfo.isMacOS) 4 else 1
    Arena.ofConfined().use { arena -> SETLOCALE.invokeExact(lcNumeric, arena.allocateUtf8String("C")) as MemorySegment }
}

private val SETLOCALE = Linker.nativeLinker().downcallHandle(
    Linker.nativeLinker().defaultLookup().find("setlocale").get(),
    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS)
)


class FFmpegException(message: String) : RuntimeException(message)

fun <P : Pointer> P?.ffmpegThrowIfNull(message: String): P =
    if (this == null || this.isNull)
        throw FFmpegException("$message.")
    else this

fun Int.ffmpegThrowIfErrnum(message: String): Int =
    if (this < 0)
        throw FFmpegException("$message: ${err2str(this)} (FFmpeg error number $this).")
    else this

// Replicates the macro of the same name from error.h.
private fun err2str(errnum: Int): String {
    val string = BytePointer(AV_ERROR_MAX_STRING_SIZE.toLong())
    av_make_error_string(string, AV_ERROR_MAX_STRING_SIZE.toLong(), errnum)
    string.limit(BytePointer.strlen(string))
    return string.string
}


inline fun <P : Pointer> P?.letIfNonNull(block: (P) -> Unit) {
    if (this != null && !this.isNull)
        block(this)
}


inline fun withOptionsDict(options: Map<String, String>, block: (AVDictionary) -> Unit) {
    val dict = AVDictionary(null)
    try {
        for ((key, value) in options)
            av_dict_set(dict, key, value, 0)
        block(dict)
    } finally {
        av_dict_free(dict)
    }
}


inline fun setupSafely(setup: () -> Unit, release: () -> Unit) {
    try {
        setup()
    } catch (t: Throwable) {
        try {
            release()
        } catch (t2: Throwable) {
            t.addSuppressed(t2)
        }
        throw t
    }
}


inline fun BufferedImage.forEachPixelAsNormalizedComponents(action: (FloatArray) -> Unit) {
    val w = width
    val h = height
    val cm = colorModel
    var dataElems: Any? = null
    val comps = IntArray(cm.numComponents)
    val normComps = FloatArray(cm.numComponents)
    for (y in 0..<h)
        for (x in 0..<w) {
            dataElems = raster.getDataElements(x, y, dataElems)
            cm.getComponents(dataElems, comps, 0)
            cm.getNormalizedComponents(comps, 0, normComps, 0)
            action(normComps)
        }
}


fun makePDICCBased(doc: PDDocument, components: Int, iccBytes: ByteArray): PDICCBased {
    val alternate = when (components) {
        1 -> COSName.DEVICEGRAY
        3 -> COSName.DEVICERGB
        4 -> COSName.DEVICECMYK
        else -> throw IllegalArgumentException()
    }
    val pdCS = PDICCBased(doc)
    val stream = pdCS.pdStream.cosObject
    stream.setInt(COSName.N, components)
    stream.setItem(COSName.ALTERNATE, alternate)
    stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(iccBytes) }
    return pdCS
}


class ClosureProtector {

    private val counter = AtomicInteger()

    fun close() {
        val old = counter.compareAndExchange(0, -1000000)
        check(old <= 0) { "The resource cannot be closed because it is in use." }
        check(old >= 0) { "The resource cannot be closed because it has already been closed." }
    }

    fun <T> ifNotClosed(action: () -> T): T? =
        try {
            if (counter.incrementAndGet() > 0) action() else null
        } finally {
            counter.decrementAndGet()
        }

    /** Keeps the bitmap open until the given action returns. If the bitmap is already closed, throws an exception. */
    fun <T> requireNotClosed(action: () -> T): T =
        try {
            check(counter.incrementAndGet() > 0) { "The resource is already closed." }
            action()
        } finally {
            counter.decrementAndGet()
        }

}
