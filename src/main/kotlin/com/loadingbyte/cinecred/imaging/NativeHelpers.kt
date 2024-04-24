package com.loadingbyte.cinecred.imaging

import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder


fun MemorySegment.getByte(offset: Long) = MEM_SEG_BYTE_HANDLE.get(this, offset) as Byte
fun MemorySegment.putByte(offset: Long, value: Byte) = MEM_SEG_BYTE_HANDLE.set(this, offset, value)

fun MemorySegment.getShortLE(offset: Long) = MEM_SEG_SHORT_LE_HANDLE.get(this, offset) as Short
fun MemorySegment.getShortBE(offset: Long) = MEM_SEG_SHORT_BE_HANDLE.get(this, offset) as Short

fun MemorySegment.putShortLE(offset: Long, value: Short) = MEM_SEG_SHORT_LE_HANDLE.set(this, offset, value)
fun MemorySegment.putShortBE(offset: Long, value: Short) = MEM_SEG_SHORT_BE_HANDLE.set(this, offset, value)

fun MemorySegment.getFloatLE(offset: Long) = MEM_SEG_FLOAT_LE_HANDLE.get(this, offset) as Float
fun MemorySegment.getFloatBE(offset: Long) = MEM_SEG_FLOAT_BE_HANDLE.get(this, offset) as Float

fun MemorySegment.putFloatLE(offset: Long, value: Float) = MEM_SEG_FLOAT_LE_HANDLE.set(this, offset, value)
fun MemorySegment.putFloatBE(offset: Long, value: Float) = MEM_SEG_FLOAT_BE_HANDLE.set(this, offset, value)

private val MEM_SEG_BYTE_HANDLE = makeVarHandle(JAVA_BYTE, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_SHORT_LE_HANDLE = makeVarHandle(JAVA_SHORT_UNALIGNED, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_SHORT_BE_HANDLE = makeVarHandle(JAVA_SHORT_UNALIGNED, ByteOrder.BIG_ENDIAN)
private val MEM_SEG_FLOAT_LE_HANDLE = makeVarHandle(JAVA_FLOAT_UNALIGNED, ByteOrder.LITTLE_ENDIAN)
private val MEM_SEG_FLOAT_BE_HANDLE = makeVarHandle(JAVA_FLOAT_UNALIGNED, ByteOrder.BIG_ENDIAN)

private fun makeVarHandle(layout: ValueLayout, order: ByteOrder): VarHandle =
    MethodHandles.memorySegmentViewVarHandle(layout.withOrder(order)).withInvokeBehavior()


fun SegmentAllocator.allocateArray(elems: ByteArray): MemorySegment = allocateArray(JAVA_BYTE, elems.size.toLong())
    .also { MemorySegment.copy(elems, 0, it, JAVA_BYTE, 0L, elems.size) }

fun SegmentAllocator.allocateArray(elems: CharArray): MemorySegment = allocateArray(JAVA_CHAR, elems.size.toLong())
    .also { MemorySegment.copy(elems, 0, it, JAVA_CHAR, 0L, elems.size) }


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
