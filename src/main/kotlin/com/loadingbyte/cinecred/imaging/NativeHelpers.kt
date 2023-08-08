package com.loadingbyte.cinecred.imaging

import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer


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
    val string = BytePointer(avutil.AV_ERROR_MAX_STRING_SIZE.toLong())
    avutil.av_make_error_string(string, avutil.AV_ERROR_MAX_STRING_SIZE.toLong(), errnum)
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
