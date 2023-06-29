package com.loadingbyte.cinecred.imaging

import org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_COMPLIANCE_STRICT
import org.bytedeco.ffmpeg.global.avcodec.av_codec_iterate
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer


sealed interface VideoContainerFormat {

    val name: String
    val extensions: List<String>

    class Reader(override val name: String, override val extensions: List<String>) : VideoContainerFormat

    class Writer(override val name: String, override val extensions: List<String>, val supportedCodecIds: Set<Int>) :
        VideoContainerFormat

    companion object {

        val READER: List<Reader>
        val WRITER: List<Writer>

        init {
            val codecIds = collect { av_codec_iterate(it)?.id() }.toSet()

            READER = collect(::av_demuxer_iterate).map { inputFormat ->
                Reader(
                    name = inputFormat.name().string,
                    extensions = extensionsToList(inputFormat.extensions()),
                )
            }

            WRITER = collect(::av_muxer_iterate).map { avMuxerFormat ->
                Writer(
                    name = avMuxerFormat.name().string,
                    extensions = extensionsToList(avMuxerFormat.extensions()),
                    supportedCodecIds = codecIds.filterTo(HashSet()) { codecId ->
                        avformat_query_codec(avMuxerFormat, codecId, FF_COMPLIANCE_STRICT) == 1
                    }
                )
            }
        }

        private inline fun <R> collect(iterate: (Pointer) -> R?): List<R> {
            val list = mutableListOf<R>()
            val iter = BytePointer()
            while (true)
                list.add(iterate(iter) ?: return list)
        }

        private fun extensionsToList(extensions: BytePointer?) =
            extensions?.string?.split(',').orEmpty()

    }

}
