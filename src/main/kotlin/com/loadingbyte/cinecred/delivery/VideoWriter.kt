package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.l10n
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


class MuxerFormat(val name: String, val supportedCodecIds: Set<Int>, val extensions: List<String>) {
    companion object {
        val ALL: List<MuxerFormat>

        init {
            val codecIds = collect { av_codec_iterate(it)?.id() }
            val avMuxerFormats = collect { av_muxer_iterate(it) }

            ALL = avMuxerFormats.map { avMuxerFormat ->
                MuxerFormat(
                    name = avMuxerFormat.name().string,
                    supportedCodecIds = codecIds.filter { codecId ->
                        avformat_query_codec(avMuxerFormat, codecId, FF_COMPLIANCE_STRICT) == 1
                    }.toSet(),
                    extensions = avMuxerFormat.extensions()?.string?.split(',').orEmpty()
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
    fileOrDir: Path,
    private val width: Int,
    private val height: Int,
    fps: FPS,
    codecId: Int,
    private val outPixelFormat: Int,
    muxerOptions: Map<String, String>,
    codecOptions: Map<String, String>
) : Closeable {

    private var inPixelFormat: Int? = null

    private var oc: AVFormatContext? = null
    private var st: AVStream? = null
    private var enc: AVCodecContext? = null
    private var swsCtx: SwsContext? = null
    private var inFrame: AVFrame? = null
    private var outFrame: AVFrame? = null

    // Pts of the next frame that will be generated.
    private var frameCounter = 0L

    init {
        try {
            setup(fileOrDir, fps, codecId, muxerOptions, codecOptions)
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
        fileOrDir: Path,
        fps: FPS,
        codecId: Int,
        muxerOptions: Map<String, String>,
        codecOptions: Map<String, String>
    ) {
        // Allocate the output media context.
        val oc = AVFormatContext(null)
        this.oc = oc
        avformat_alloc_output_context2(oc, null, null, fileOrDir.toString())
            .throwIfErrnum("delivery.ffmpeg.unknownMuxerError")

        // Find the encoder.
        val codec = avcodec_find_encoder(codecId)
            .throwIfNull("delivery.ffmpeg.unknownCodecError", avcodec_get_name(codecId).string)

        // Add the video stream.
        val st = avformat_new_stream(oc, null)
            .throwIfNull("delivery.ffmpeg.allocStreamError")
        this.st = st
        // Assigning the stream ID dynamically is technically unnecessary because we only have one stream.
        st.id(oc.nb_streams() - 1)

        // Allocate and configure the codec.
        val enc = avcodec_alloc_context3(codec)
            .throwIfNull("delivery.ffmpeg.allocEncoderError")
        this.enc = enc
        configureCodec(fps, codecId)

        // Now that all the parameters are set, we can open the video codec and allocate the necessary encode buffer.
        withOptionsDict(codecOptions) { codecOptionsDict ->
            avcodec_open2(enc, codec, codecOptionsDict)
                .throwIfErrnum("delivery.ffmpeg.openEncoderError")
        }

        // Determine whether the output pixel format has an alpha component.
        // From that, determine the input pixel format.
        val alpha = av_pix_fmt_desc_get(outPixelFormat).throwIfNull("delivery.ffmpeg.getPixFmtError")
            .flags() and AV_PIX_FMT_FLAG_ALPHA.toLong() != 0L
        val inPixelFormat = if (alpha) AV_PIX_FMT_ABGR else AV_PIX_FMT_BGR24
        this.inPixelFormat = inPixelFormat

        // Allocate and initialize a reusable frame.
        outFrame = allocFrame(outPixelFormat)
        // If the output format is not equal to the input format, then a temporary picture which uses the input format
        // is needed too. That picture will then be converted to the required output format by an SWS context, which
        // we need to create as well.
        if (outPixelFormat != inPixelFormat) {
            inFrame = allocFrame(inPixelFormat)
            swsCtx = sws_getContext(
                width, height, inPixelFormat, width, height, outPixelFormat, 0,
                null, null, null as DoublePointer?
            ).throwIfNull("delivery.ffmpeg.getConverterError")
        }

        // Copy the stream parameters to the muxer.
        avcodec_parameters_from_context(st.codecpar(), enc).throwIfErrnum("delivery.ffmpeg.copyParamsError")

        withOptionsDict(muxerOptions) { muxerOptionsDict ->
            // Open the output file, if needed.
            if (oc.oformat().flags() and AVFMT_NOFILE == 0) {
                val pb = AVIOContext(null)
                avio_open2(pb, fileOrDir.toString(), AVIO_FLAG_WRITE, null, muxerOptionsDict)
                    .throwIfErrnum("delivery.ffmpeg.openFileError", fileOrDir)
                oc.pb(pb)
            }

            // Write the stream header, if any.
            avformat_write_header(oc, muxerOptionsDict).throwIfErrnum("delivery.ffmpeg.openFileError", fileOrDir)
        }
    }

    private fun configureCodec(fps: FPS, codecId: Int) {
        val oc = this.oc!!
        val st = this.st!!
        val enc = this.enc!!

        enc.codec_id(codecId)
        enc.width(width)
        enc.height(height)
        enc.pix_fmt(outPixelFormat)

        // Timebase: This is the fundamental unit of time (in seconds) in terms
        // of which frame timestamps are represented. For fixed-fps content,
        // timebase should be 1/framerate and timestamp increments should be
        // identical to 1.
        val timebase = AVRational().apply { num(fps.denominator); den(fps.numerator) }
        st.time_base(timebase)
        enc.time_base(timebase)

        // Some muxers, for example MKV, require the framerate to be set directly on the stream.
        // Otherwise, they infer it incorrectly.
        st.avg_frame_rate(AVRational().apply { num(fps.numerator); den(fps.denominator) })

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
        val frame = av_frame_alloc().throwIfNull("delivery.ffmpeg.allocFrameError").apply {
            format(pixelFormat)
            width(width)
            height(height)
        }
        // Allocate the buffers for the frame data.
        av_frame_get_buffer(frame, 0).throwIfErrnum("delivery.ffmpeg.allocFrameDataError")
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
     * Images must be encoded as [BufferedImage.TYPE_3BYTE_BGR] or [BufferedImage.TYPE_4BYTE_ABGR],
     * depending on whether the [outPixelFormat] has an alpha channel.
     */
    fun writeFrame(image: BufferedImage) {
        val inPixelFormat = this.inPixelFormat!!
        val outFrame = this.outFrame!!

        // We only accept BufferedImages whose format is equivalent to the input pixel format.
        val inImageType = when (inPixelFormat) {
            AV_PIX_FMT_BGR24 -> BufferedImage.TYPE_3BYTE_BGR
            AV_PIX_FMT_ABGR -> BufferedImage.TYPE_4BYTE_ABGR
            else -> throw AVException("Illegal state, should never happen.")
        }
        require(image.type == inImageType)

        // When we pass a frame to the encoder, it may keep a reference to it internally;
        // make sure we do not overwrite it here.
        av_frame_make_writable(outFrame).throwIfErrnum("delivery.ffmpeg.makeFrameWritableError")

        // Transfer the BufferedImage's data to the output frame. When the input and output pixel formats differ,
        // instead transfer the data to the input frame and then use the SWS context which converts it to the
        // output format and writes it to the output frame.
        if (outPixelFormat == inPixelFormat)
            fillFrame(outFrame, image)
        else {
            val inFrame = this.inFrame!!
            fillFrame(inFrame, image)
            sws_scale(swsCtx!!, inFrame.data(), inFrame.linesize(), 0, height, outFrame.data(), outFrame.linesize())
        }

        outFrame.pts(frameCounter++)

        writeFrame(outFrame)
    }

    private fun fillFrame(frame: AVFrame, image: BufferedImage) {
        val destination = PointerPointer<AVFrame>(frame)
        val source = BytePointer(ByteBuffer.wrap(((image.raster.dataBuffer) as DataBufferByte).data))
        av_image_fill_arrays(destination, frame.linesize(), source, inPixelFormat!!, width, height, 1)
            .throwIfErrnum("delivery.ffmpeg.fillFrameError")
    }

    private fun writeFrame(frame: AVFrame?): Boolean {
        val st = this.st!!
        val enc = this.enc!!

        // Send the frame to the encoder.
        avcodec_send_frame(enc, frame).throwIfErrnum("delivery.ffmpeg.sendFrameError")

        var ret = 0
        while (ret >= 0) {
            val pkt = AVPacket()
            try {
                ret = avcodec_receive_packet(enc, pkt)
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    break
                ret.throwIfErrnum("delivery.ffmpeg.encodeFrameError")

                // Inform the packet about which stream it should be written to.
                pkt.stream_index(st.index())

                // Set the duration of the frame delivered by the current packet. This is required for most codec/muxer
                // combinations to recognize the framerate. Relative to the encoder timebase (which is 1/fps) for now.
                pkt.duration(1)

                // Now rescale output packet timestamp values (including duration) from codec to stream timebase.
                av_packet_rescale_ts(pkt, enc.time_base(), st.time_base())

                // Write the compressed frame to the media file.
                ret = av_interleaved_write_frame(oc, pkt).throwIfErrnum("delivery.ffmpeg.writeFrameError")
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
            av_write_trailer(oc).throwIfErrnum("delivery.ffmpeg.closeFileError")
        } finally {
            release()
        }
    }

    private fun release() {
        inFrame.letIfNonNull(::av_frame_free)
        outFrame.letIfNonNull(::av_frame_free)
        enc.letIfNonNull(::avcodec_free_context)
        swsCtx.letIfNonNull(::sws_freeContext)

        oc.letIfNonNull { oc ->
            // Close the output file, if needed.
            if (oc.oformat().flags() and AVFMT_NOFILE == 0)
                avio_closep(oc.pb())

            avformat_free_context(oc)
        }
    }

    private inline fun <P : Pointer> P?.letIfNonNull(block: (P) -> Unit) {
        if (this != null && !this.isNull)
            block(this)
    }

    private fun <P : Pointer> P?.throwIfNull(l10nKey: String, vararg l10nArgs: Any?): P =
        if (this == null || this.isNull)
            throw AVException(l10n("delivery.ffmpeg.errorNull", l10n(l10nKey, *l10nArgs)))
        else this

    private fun Int.throwIfErrnum(l10nKey: String, vararg l10nArgs: Any?): Int =
        if (this < 0)
            throw AVException(l10n("delivery.ffmpeg.errorNum", l10n(l10nKey, *l10nArgs), err2str(this), this))
        else this

    // Replicates the macro of the same name from error.h.
    private fun err2str(errnum: Int) = String(
        ByteArray(AV_ERROR_MAX_STRING_SIZE).also { av_make_error_string(it, AV_ERROR_MAX_STRING_SIZE.toLong(), errnum) }
    )

    private class AVException(message: String) : RuntimeException(message)

}
