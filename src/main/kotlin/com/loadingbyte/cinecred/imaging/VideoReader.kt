package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.Timecode
import com.loadingbyte.cinecred.common.toPathSafely
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import java.nio.file.Path
import java.util.*
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString


/**
 * This class provides a nice interface for reading videos with FFmpeg and hides all the nastiness.
 * The code inside this class is adapted from here: https://ffmpeg.org/doxygen/trunk/demux_decode_8c-example.html
 */
class VideoReader(
    fileOrPattern: Path,
    /**
     * When reading a file sequence, this should be an instance of [Timecode.Frames] that indicates the number in
     * the filename of the first file that should be read.
     *
     * When reading a container video file, this should be an instance of [Timecode.Clock] or null and is a hint where
     * in the video to start reading. Be aware that reading can start earlier than this timecode, but never later.
     */
    startTimecode: Timecode?
) : AutoCloseable {

    class Frame(val bitmap: Bitmap, val timecode: Timecode)

    private val fileSeq = fileOrPattern.pathString.contains(Regex("%[0-9]*d"))
    private val filename = fileOrPattern.name

    private var ic: AVFormatContext? = null
    private var st: AVStream? = null
    private var dec: AVCodecContext? = null
    private var pkt: AVPacket? = null

    private val queue: Queue<Frame> = ArrayDeque()
    private var frameNumber = -1
    private var done = false

    var fps: FPS? = null; private set
    var estimatedDuration: Timecode.Clock? = null; private set
    lateinit var spec: Bitmap.Spec; private set

    init {
        setupSafely({ setup(fileOrPattern, startTimecode) }, ::close)
    }

    private fun setup(fileOrPattern: Path, startTimecode: Timecode?) {
        // Open the file and allocate the format context.
        val formatOptions = HashMap<String, String>()
        if (fileSeq) {
            frameNumber = (startTimecode as Timecode.Frames).frames
            // If we're reading a file sequence and the first file doesn't exist, FFmpeg would by itself throw an
            // exception. However, when reading a video container and seeking to an out-of-bounds timestamp, FFmpeg
            // doesn't throw an exception, and instead we just always return null in the read() method. To resolve this
            // mismatch, if the first file doesn't exist, we abort setup BEFORE passing anything to FFmpeg. Later in the
            // read() method, we detect this incomplete initialization and return null.
            if (fileOrPattern.pathString.format(frameNumber).toPathSafely().let { it == null || !it.isRegularFile() })
                return
            formatOptions["start_number"] = frameNumber.toString()
        }
        val ic = AVFormatContext(null)
        this.ic = ic
        withOptionsDict(formatOptions) { formatOptionsDict ->
            avformat_open_input(ic, fileOrPattern.pathString, null, formatOptionsDict)
                .ffmpegThrowIfErrnum("Could not open input file '$filename'")
        }

        // Retrieve the stream information.
        avformat_find_stream_info(ic, null as AVDictionary?)
            .ffmpegThrowIfErrnum("Could not find stream information in '$filename'")

        // Find the video stream that is going to be read, and the matching codec.
        val codec = AVCodec(null)
        val stIdx = av_find_best_stream(ic, AVMEDIA_TYPE_VIDEO, -1, -1, codec, 0)
            .ffmpegThrowIfErrnum("Could not find a readable video stream in '$filename'")
        val st = ic.streams(stIdx)
        this.st = st
        val tb = st!!.time_base()

        // Allocate and configure the decoder.
        val dec = avcodec_alloc_context3(codec)
            .ffmpegThrowIfNull("Could not allocate decoder for '$filename'")
        this.dec = dec

        // Copy the stream parameters to the decoder.
        avcodec_parameters_to_context(dec, st.codecpar())
            .ffmpegThrowIfErrnum("Could not copy the stream parameters to the decoder for '$filename'")

        // Open the decoder and allocate the necessary decode buffer.
        avcodec_open2(dec, codec, null as AVDictionary?)
            .ffmpegThrowIfErrnum("Could not open decoder for '$filename'")

        // Seek to the keyframe immediately prior to the desired start time.
        if (!fileSeq && startTimecode != null) {
            startTimecode as Timecode.Clock
            val timestamp = (startTimecode.numerator * tb.den()) / (startTimecode.denominator * tb.num())
            av_seek_frame(ic, stIdx, timestamp, AVSEEK_FLAG_BACKWARD)
                .ffmpegThrowIfErrnum("Could not seek to desired start time in '$filename'")
        }

        // Allocate a packet struct.
        val pkt = av_packet_alloc()
            .ffmpegThrowIfNull("Could not allocate packet")
        this.pkt = pkt

        // Extract the frame rate and duration, unless they're degenerate, in which case they're probably unspecified.
        if (!fileSeq) {
            val decFR = dec.framerate()
            if (decFR.num() > 0 && decFR.den() > 0)
                fps = FPS(decFR.num(), decFR.den())
            else {
                val avgFR = st.avg_frame_rate()
                if (avgFR.num() > 0 && avgFR.den() > 0)
                    fps = FPS(avgFR.num(), avgFR.den())
            }
            val stDur = st.duration()
            if (stDur > 0)
                estimatedDuration = Timecode.Clock(stDur * tb.num(), tb.den().toLong())
            else {
                val icDur = ic.duration()
                if (icDur > 0)
                    estimatedDuration = Timecode.Clock(icDur, AV_TIME_BASE.toLong())
            }
        }

        // Extract a bitmap spec from the decoder parameters. If some metadata is unspecified, assume Rec. 709.
        val pixelFormat = Bitmap.PixelFormat.of(dec.pix_fmt())
        val pri = dec.color_primaries()
        val trc = dec.color_trc()
        val cs = dec.colorspace()
        val chromaLoc = dec.chroma_sample_location()
        spec = Bitmap.Spec(
            Resolution(dec.width(), dec.height()),
            Bitmap.Representation(
                pixelFormat,
                if (dec.color_range() != AVCOL_RANGE_UNSPECIFIED) Bitmap.Range.of(dec.color_range()) else
                    if (pixelFormat.family == Bitmap.PixelFormat.Family.YUV) Bitmap.Range.LIMITED
                    else Bitmap.Range.FULL,
                ColorSpace.of(
                    if (pri != AVCOL_PRI_UNSPECIFIED) ColorSpace.Primaries.of(pri) else ColorSpace.Primaries.BT709,
                    if (trc != AVCOL_TRC_UNSPECIFIED) ColorSpace.Transfer.of(trc) else ColorSpace.Transfer.BT1886
                ),
                if (pixelFormat.family != Bitmap.PixelFormat.Family.YUV) null else
                    if (cs != AVCOL_SPC_UNSPECIFIED) Bitmap.YUVCoefficients.of(cs)
                    else Bitmap.YUVCoefficients.BT709_NCL,
                if (!pixelFormat.hasChromaSub) AVCHROMA_LOC_UNSPECIFIED else
                    if (chromaLoc != AVCHROMA_LOC_UNSPECIFIED) chromaLoc else AVCHROMA_LOC_LEFT,
                // According to the documentation of the flag AV_PIX_FMT_FLAG_ALPHA, alpha is always straight in FFmpeg,
                // never pre-multiplied.
                if (pixelFormat.hasAlpha) Bitmap.Alpha.STRAIGHT else Bitmap.Alpha.OPAQUE
            ),
            when (dec.field_order()) {
                AV_FIELD_TT, AV_FIELD_BT -> Bitmap.Scan.INTERLACED_TOP_FIELD_FIRST
                AV_FIELD_BB, AV_FIELD_TB -> Bitmap.Scan.INTERLACED_BOT_FIELD_FIRST
                else -> Bitmap.Scan.PROGRESSIVE
            },
            when (dec.field_order()) {
                AV_FIELD_TT, AV_FIELD_TB -> Bitmap.Content.INTERLEAVED_FIELDS
                AV_FIELD_BB, AV_FIELD_BT -> Bitmap.Content.INTERLEAVED_FIELDS_REVERSED
                else -> Bitmap.Content.PROGRESSIVE_FRAME
            }
        )
    }

    /** Reads the next frame from the video, or returns null if the video has come to an end. */
    fun read(): Frame? {
        // For file sequences, setup can be aborted early, in which case the goal is to return null here.
        if (fileSeq && ic == null)
            return null

        val st = this.st!!
        val pkt = this.pkt!!

        while (!done && queue.isEmpty()) {
            val ret = av_read_frame(ic, pkt)
            if (ret == AVERROR_EOF) {
                done = true
                readPacket(null)
                break
            }
            ret.ffmpegThrowIfErrnum("Error while reading an encoded packet from the stream of '$filename'")
            try {
                if (pkt.stream_index() == st.index())
                    readPacket(pkt)
            } finally {
                av_packet_unref(pkt)
            }
        }

        return queue.poll()
    }

    /** Decodes one packet and enqueues the found frames. */
    private fun readPacket(packet: AVPacket?) {
        val dec = this.dec!!

        // Send the packet to the decoder.
        avcodec_send_packet(dec, packet)
            .ffmpegThrowIfErrnum("Error while sending a packet to the decoder for '$filename'")

        // Receive all available frames from the decoder.
        while (true) {
            val frame = av_frame_alloc()
                .ffmpegThrowIfNull("Could not allocate frame struct")
            val ret = avcodec_receive_frame(dec, frame)
            // Handle errors.
            if (ret < 0) {
                av_frame_free(frame)
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    break
                ret.ffmpegThrowIfErrnum("Error while receiving a decoded frame from the decoder for '$filename'")
            }
            val timecode = if (fileSeq) Timecode.Frames(frameNumber++) else {
                val tb = st!!.time_base()
                Timecode.Clock(frame.pts() * tb.num(), tb.den().toLong())
            }
            queue.offer(Frame(Bitmap.wrap(spec, frame), timecode))
        }
    }

    override fun close() {
        pkt.letIfNonNull(::av_packet_free)
        pkt = null
        dec.letIfNonNull(::avcodec_free_context)
        dec = null
        ic.letIfNonNull(::avformat_close_input)
        ic = null
        while (true)
            (queue.poll() ?: break).bitmap.close()
    }

}
