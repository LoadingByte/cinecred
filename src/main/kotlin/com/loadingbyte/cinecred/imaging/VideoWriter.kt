package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.VERSION
import com.loadingbyte.cinecred.imaging.Bitmap.Content.INTERLEAVED_FIELDS
import com.loadingbyte.cinecred.imaging.Bitmap.Content.INTERLEAVED_FIELDS_REVERSED
import com.loadingbyte.cinecred.imaging.Bitmap.Scan.*
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString


/**
 * This class provides a nice interface to FFmpeg and hides all its nastiness.
 * The code inside this class is adapted from here: https://ffmpeg.org/doxygen/trunk/muxing_8c-example.html
 */
class VideoWriter(
    fileOrPattern: Path,
    val spec: Bitmap.Spec,
    /** Note: When `scan` is interlaced, `fps` still refers to the number of full (and not half) frames per second. */
    fps: FPS,
    codecName: String,
    codecProfile: Int,
    /**
     * There are codec-specific and codec-agnostic options. The latter are enumerated here:
     * https://ffmpeg.org/doxygen/7.0/libavcodec_2options__table_8h_source.html
     */
    codecOptions: Map<String, String>,
    muxerOptions: Map<String, String>
) : AutoCloseable {

    private var oc: AVFormatContext? = null
    private var st: AVStream? = null
    private var enc: AVCodecContext? = null

    // Pts of the next frame that will be generated.
    private var frameCounter = 0L

    init {
        require(spec.representation.alpha != Bitmap.Alpha.PREMULTIPLIED) { "FFmpeg does not support premul alpha." }
        setupSafely({
            setup(
                fileOrPattern, fps,
                codecName, codecProfile, codecOptions, muxerOptions
            )
        }, ::release)
    }

    private fun setup(
        fileOrPattern: Path,
        fps: FPS,
        codecName: String,
        codecProfile: Int,
        codecOptions: Map<String, String>,
        muxerOptions: Map<String, String>
    ) {
        // Allocate the format context.
        val oc = AVFormatContext(null)
        this.oc = oc
        avformat_alloc_output_context2(oc, null, null, fileOrPattern.pathString)
            .ffmpegThrowIfErrnum("Could not deduce output muxer from file extension")
        // Will be freed by avformat_free_context().
        oc.metadata(AVDictionary(null).also { metaDict ->
            av_dict_set(metaDict, "encoding_tool", "Cinecred $VERSION", 0)
            av_dict_set(metaDict, "company_name", "Cinecred", 0)
            av_dict_set(metaDict, "product_name", "Cinecred $VERSION", 0)
        })

        // Find the codec.
        val codec = avcodec_find_encoder_by_name(codecName)
            .ffmpegThrowIfNull("Could not find encoder '$codecName'")

        // Add the video stream.
        val st = avformat_new_stream(oc, null)
            .ffmpegThrowIfNull("Could not allocate stream")
        this.st = st
        // Assigning the stream ID dynamically is technically unnecessary because we only have one stream.
        st.id(oc.nb_streams() - 1)

        // Allocate and configure the encoder.
        val enc = avcodec_alloc_context3(codec)
            .ffmpegThrowIfNull("Could not allocate encoder")
        this.enc = enc
        configureCodec(fps, codecProfile)

        // Now that all the parameters are set, we can open the encoder and allocate the necessary encode buffer.
        withOptionsDict(codecOptions) { codecOptionsDict ->
            avcodec_open2(enc, codec, codecOptionsDict)
                .ffmpegThrowIfErrnum("Could not open encoder")
        }

        // Copy the encoder parameters to the stream.
        avcodec_parameters_from_context(st.codecpar(), enc)
            .ffmpegThrowIfErrnum("Could not copy the encoder parameters to the stream")

        withOptionsDict(muxerOptions) { muxerOptionsDict ->
            // Open the output file, if needed.
            if (oc.oformat().flags() and AVFMT_NOFILE == 0) {
                val pb = AVIOContext(null)
                avio_open2(pb, fileOrPattern.pathString, AVIO_FLAG_WRITE, null, muxerOptionsDict)
                    .ffmpegThrowIfErrnum("Could not open output file '${fileOrPattern.name}'")
                oc.pb(pb)
            }

            // Write the stream header, if any.
            avformat_write_header(oc, muxerOptionsDict)
                .ffmpegThrowIfErrnum("Could not write stream header")
        }
    }

    private fun configureCodec(
        fps: FPS,
        codecProfile: Int
    ) {
        val oc = this.oc!!
        val st = this.st!!
        val enc = this.enc!!

        enc.profile(codecProfile)
        enc.width(spec.resolution.widthPx)
        enc.height(spec.resolution.heightPx)
        enc.pix_fmt(spec.representation.pixelFormat.code)

        // Specify color space metadata.
        spec.representation.colorSpace?.let { colorSpace ->
            enc.color_primaries(colorSpace.primaries.code)
            enc.color_trc(colorSpace.transfer.code(colorSpace.primaries, spec.representation.pixelFormat.depth))
        }
        enc.color_range(spec.representation.range.code)
        enc.chroma_sample_location(spec.representation.chromaLocation)

        val cs = when (spec.representation.pixelFormat.family) {
            Bitmap.PixelFormat.Family.GRAY -> AVCOL_SPC_UNSPECIFIED
            Bitmap.PixelFormat.Family.RGB -> AVCOL_SPC_RGB
            Bitmap.PixelFormat.Family.YUV -> spec.representation.yuvCoefficients!!.code
        }
        enc.colorspace(cs)

        // Specify progressive or interlaced scan.
        val fieldOrder = when {
            spec.scan == PROGRESSIVE -> AV_FIELD_PROGRESSIVE
            spec.scan == INTERLACED_TOP_FIELD_FIRST && spec.content == INTERLEAVED_FIELDS -> AV_FIELD_TT
            spec.scan == INTERLACED_TOP_FIELD_FIRST && spec.content == INTERLEAVED_FIELDS_REVERSED -> AV_FIELD_BT
            spec.scan == INTERLACED_BOT_FIELD_FIRST && spec.content == INTERLEAVED_FIELDS -> AV_FIELD_TB
            spec.scan == INTERLACED_BOT_FIELD_FIRST && spec.content == INTERLEAVED_FIELDS_REVERSED -> AV_FIELD_BB
            else -> throw IllegalArgumentException("Cannot write video with bitmap spec content ${spec.content}.")
        }
        enc.field_order(fieldOrder)
        if (spec.scan != PROGRESSIVE)
            enc.flags(AV_CODEC_FLAG_INTERLACED_DCT or AV_CODEC_FLAG_INTERLACED_ME)

        // Timebase: This is the fundamental unit of time (in seconds) in terms
        // of which frame timestamps are represented. For fixed-fps content,
        // timebase should be 1/framerate and timestamp increments should be
        // identical to 1.
        st.time_base().apply { num(fps.denominator); den(fps.numerator) }
        enc.time_base().apply { num(fps.denominator); den(fps.numerator) }

        // Some muxers, for example MKV, require the framerate to be set directly on the stream.
        // Otherwise, they infer it incorrectly.
        st.avg_frame_rate().apply { num(fps.numerator); den(fps.denominator) }

        // Some formats want stream headers to be separate.
        if (oc.oformat().flags() and AVFMT_GLOBALHEADER != 0)
            enc.flags(enc.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
    }

    /** Writes the next frame to the video. */
    fun write(bitmap: Bitmap) {
        require(bitmap.spec == spec)
        bitmap.requireNotClosed { writeFrame(bitmap.frame) }
    }

    /** Encodes one video frame and sends it to the muxer. */
    private fun writeFrame(frame: AVFrame?) {
        val st = this.st!!
        val enc = this.enc!!

        // Send the frame to the encoder.
        frame?.pts(frameCounter++)
        avcodec_send_frame(enc, frame)
            .ffmpegThrowIfErrnum("Error while sending a frame to the encoder")

        while (true) {
            val pkt = av_packet_alloc()
            try {
                val ret = avcodec_receive_packet(enc, pkt)
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    break
                ret.ffmpegThrowIfErrnum("Error while receiving an encoded packet from the encoder")

                // Inform the packet about which stream it should be written to.
                pkt.stream_index(st.index())

                // Set the duration of the frame delivered by the current packet. This is required for most codec/muxer
                // combinations to recognize the framerate. Relative to the encoder timebase (which is 1/fps) for now.
                pkt.duration(1)

                // Now rescale output packet timestamp values (including duration) from codec to stream timebase.
                av_packet_rescale_ts(pkt, enc.time_base(), st.time_base())

                // Write the compressed frame to the media file.
                av_interleaved_write_frame(oc, pkt)
                    .ffmpegThrowIfErrnum("Error while writing an encoded packet to the stream")
            } finally {
                av_packet_unref(pkt)
            }
        }
    }

    override fun close() {
        try {
            // Write a null frame to terminate the stream.
            writeFrame(null)

            // Write the trailer, if any.
            av_write_trailer(oc)
                .ffmpegThrowIfErrnum("Could not write stream trailer and close output file")
        } finally {
            release()
        }
    }

    private fun release() {
        enc.letIfNonNull(::avcodec_free_context)
        enc = null

        oc.letIfNonNull { oc ->
            // Close the output file, if needed.
            if (oc.oformat().flags() and AVFMT_NOFILE == 0)
                oc.pb().letIfNonNull(::avio_closep)

            avformat_free_context(oc)
        }
        oc = null
    }

}
