package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.project.FPS
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_COMPLIANCE_STRICT
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_PROFILE_H264_CONSTRAINED_BASELINE
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.file.Path


class MuxerFormat(val supportedCodecIds: Set<Int>, val extensions: List<String>) {
    companion object {
        val ALL: List<MuxerFormat>

        init {
            val codecIds = collect { av_codec_iterate(it)?.id() }
            val avMuxerFormats = collect { av_muxer_iterate(it) }

            ALL = avMuxerFormats.map { avMuxerFormat ->
                MuxerFormat(
                    supportedCodecIds = codecIds.filter { codecId ->
                        avformat_query_codec(avMuxerFormat, codecId, FF_COMPLIANCE_STRICT) == 1
                    }.toSet(),
                    extensions = avMuxerFormat.extensions()?.string?.split(',') ?: emptyList()
                )
            }
        }

        private inline fun <R> collect(iterate: (Pointer) -> R?): List<R> {
            val list = mutableListOf<R>()
            val iter = BytePointer()
            while (true)
                list.add(iterate(iter) ?: return list)
        }
    }
}


/**
 * This class provides a nice interface to FFmpeg and hides all its nastiness.
 * The code inside this class is adapted from here: https://ffmpeg.org/doxygen/trunk/muxing_8c-example.html
 */
class VideoWriter(
    file: Path,
    private val width: Int,
    private val height: Int,
    fps: FPS,
    codecId: Int,
    private val pixelFormat: Int,
    muxerOptions: Map<String, String>,
    codecOptions: Map<String, String>
) : Closeable {

    private var oc: AVFormatContext? = null
    private var st: AVStream? = null
    private var enc: AVCodecContext? = null
    private var swsCtx: SwsContext? = null
    private var frame: AVFrame? = null
    private var tmpFrame: AVFrame? = null

    // Pts of the next frame that will be generated.
    var frameCounter = 0L
        private set

    init {
        try {
            setup(file, fps, codecId, muxerOptions, codecOptions)
        } catch (e: AVException) {
            try {
                release()
            } catch (e2: AVException) {
                e.addSuppressed(e2)
            }
            throw e
        }
    }

    private fun setup(
        file: Path,
        fps: FPS,
        codecId: Int,
        muxerOptions: Map<String, String>,
        codecOptions: Map<String, String>
    ) {
        // Allocate the output media context.
        val oc = AVFormatContext(null)
        this.oc = oc
        avformat_alloc_output_context2(oc, null, null, file.toString())
            .throwIfErrnum("Could not deduce output format from file extension")

        // Find the encoder.
        val codec = avcodec_find_encoder(codecId)
            .throwIfNull("Could not find encoder for '${avcodec_get_name(codecId).string}'.")

        // Add the video stream.
        val st = avformat_new_stream(oc, null)
            .throwIfNull("Could not allocate stream.")
        this.st = st
        // Assigning the stream ID dynamically is technically unnecessary because we only have one stream.
        st.id(oc.nb_streams() - 1)

        // Allocate and configure the codec.
        val enc = avcodec_alloc_context3(codec)
            .throwIfNull("Could not alloc an encoding context")
        this.enc = enc
        configureCodec(fps, codecId)

        // Now that all the parameters are set, we can open the video codec and allocate the necessary encode buffer.
        withOptionsDict(codecOptions) { codecOptionsDict ->
            avcodec_open2(enc, codec, codecOptionsDict).throwIfErrnum("Could not open video codec")
        }

        // Allocate and initialize a reusable frame.
        frame = allocFrame(pixelFormat)
        // If the output format is not BGR24, then a temporary BGR24 picture is needed too.
        // That picture will then be converted to the required output format.
        if (pixelFormat != AV_PIX_FMT_BGR24)
            tmpFrame = allocFrame(AV_PIX_FMT_BGR24)

        // Copy the stream parameters to the muxer.
        avcodec_parameters_from_context(st.codecpar(), enc).throwIfErrnum("Could not copy the stream parameters")

        withOptionsDict(muxerOptions) { muxerOptionsDict ->
            // Open the output file, if needed.
            if (oc.oformat().flags() and AVFMT_NOFILE == 0) {
                val pb = AVIOContext(null)
                avio_open2(pb, file.toString(), AVIO_FLAG_WRITE, null, muxerOptionsDict)
                    .throwIfErrnum("Could not open '$file'")
                oc.pb(pb)
            }

            // Write the stream header, if any.
            avformat_write_header(oc, muxerOptionsDict).throwIfErrnum("Error occurred when opening output file")
        }
    }

    private fun configureCodec(fps: FPS, codecId: Int) {
        val oc = this.oc!!
        val st = this.st!!
        val enc = this.enc!!

        enc.codec_id(codecId)
        enc.width(width)
        enc.height(height)
        enc.pix_fmt(pixelFormat)

        // Timebase: This is the fundamental unit of time (in seconds) in terms
        // of which frame timestamps are represented. For fixed-fps content,
        // timebase should be 1/framerate and timestamp increments should be
        // identical to 1.
        val timebase = AVRational().apply { num(fps.denominator); den(fps.numerator) }
        st.time_base(timebase)
        enc.time_base(timebase)

        when (codecId) {
            // Needed to avoid using macroblocks in which some coeffs overflow.
            // This does not happen with normal video, it just happens here as
            // the motion of the chroma plane does not match the luma plane.
            AV_CODEC_ID_MPEG1VIDEO -> enc.mb_decision(2)
            // Default to constrained baseline to produce content that plays back on anything,
            // without any significant tradeoffs for most use cases.
            AV_CODEC_ID_H264 -> enc.profile(FF_PROFILE_H264_CONSTRAINED_BASELINE)
        }

        // Some formats want stream headers to be separate.
        if (oc.oformat().flags() and AVFMT_GLOBALHEADER != 0)
            enc.flags(enc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
    }

    private fun allocFrame(pixelFormat: Int): AVFrame {
        val frame = av_frame_alloc().throwIfNull("Could not allocate video frame.").apply {
            format(pixelFormat)
            width(width)
            height(height)
        }
        // Allocate the buffers for the frame data.
        av_frame_get_buffer(frame, 0).throwIfErrnum("Could not allocate video frame data.")
        return frame
    }

    private inline fun withOptionsDict(options: Map<String, String>, block: (AVDictionary) -> Unit) {
        val dict = AVDictionary()
        try {
            for ((key, value) in options)
                av_dict_set(dict, key, value, 0)
            block(dict)
        } finally {
            av_dict_free(dict)
        }
    }

    /**
     * Encodes one video frame and sends it to the muxer.
     * Images must be encoded as [BufferedImage.TYPE_3BYTE_BGR].
     */
    fun writeFrame(image: BufferedImage) {
        val frame = this.frame!!

        // We only accept TYPE_3BYTE_BGR.
        require(image.type == BufferedImage.TYPE_3BYTE_BGR)

        // When we pass a frame to the encoder, it may keep a reference to it internally;
        // make sure we do not overwrite it here.
        av_frame_make_writable(frame).throwIfErrnum("Could not make frame writable")

        // As we only generate BGR24 pictures, we must convert it to the codec pixel format if needed.
        if (pixelFormat == AV_PIX_FMT_BGR24)
            fillFrame(frame, image)
        else {
            val tmpFrame = this.tmpFrame!!
            fillFrame(tmpFrame, image)
            if (swsCtx == null)
                swsCtx = sws_getContext(
                    width, height, AV_PIX_FMT_BGR24, width, height, pixelFormat, SWS_BICUBIC,
                    null, null, null as DoublePointer?
                ).throwIfNull("Could not initialize the conversion context")
            sws_scale(swsCtx, tmpFrame.data(), tmpFrame.linesize(), 0, height, frame.data(), frame.linesize())
        }

        frame.pts(frameCounter++)

        writeFrame(frame)
    }

    private fun fillFrame(frame: AVFrame, image: BufferedImage) {
        val destination = PointerPointer<AVFrame>(frame)
        val source = BytePointer(ByteBuffer.wrap(((image.raster.dataBuffer) as DataBufferByte).data))
        av_image_fill_arrays(destination, frame.linesize(), source, AV_PIX_FMT_BGR24, width, height, 1)
    }

    private fun writeFrame(frame: AVFrame?): Boolean {
        val st = this.st!!
        val enc = this.enc!!

        // Send the frame to the encoder.
        avcodec_send_frame(enc, frame).throwIfErrnum("Error sending a frame to the encoder")

        var ret = 0
        while (ret >= 0) {
            val pkt = AVPacket()
            try {
                ret = avcodec_receive_packet(enc, pkt)
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    break
                ret.throwIfErrnum("Error encoding a frame")

                // Rescale output packet timestamp values from codec to stream timebase.
                av_packet_rescale_ts(pkt, enc.time_base(), st.time_base())
                pkt.stream_index(st.index())

                // Write the compressed frame to the media file.
                ret = av_write_frame(oc, pkt).throwIfErrnum("Error while writing output packet")
            } finally {
                av_packet_unref(pkt)
            }
        }
        return ret == AVERROR_EOF
    }

    override fun close() {
        try {
            // Write a null frame to terminate the stream.
            writeFrame(null)

            // Write the trailer, if any.
            av_write_trailer(oc).throwIfErrnum("Error occurred when closing output file")
        } finally {
            release()
        }
    }

    private fun release() {
        av_frame_free(frame)
        av_frame_free(tmpFrame)
        avcodec_free_context(enc)
        sws_freeContext(swsCtx)

        // Close the output file, if needed.
        val oc = this.oc!!
        if (oc.oformat().flags() and AVFMT_NOFILE == 0)
            avio_closep(oc.pb())

        avformat_free_context(oc)
    }

    private fun <P : Pointer> P?.throwIfNull(message: String): P =
        if (this == null || this.isNull)
            throw AVException(message)
        else this

    private fun Int.throwIfErrnum(message: String): Int =
        if (this < 0)
            throw AVException("$message: ${err2str(this)} (errnum $this)")
        else this

    // Replicates the macro of the same name from error.h.
    private fun err2str(errnum: Int) = String(
        ByteArray(AV_ERROR_MAX_STRING_SIZE).also { av_make_error_string(it, AV_ERROR_MAX_STRING_SIZE.toLong(), errnum) }
    )

    class AVException(message: String) : RuntimeException(message)

}
