package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.CLEANER
import com.loadingbyte.cinecred.natives.zimg.zimg_graph_builder_params
import com.loadingbyte.cinecred.natives.zimg.zimg_h.*
import com.loadingbyte.cinecred.natives.zimg.zimg_image_buffer
import com.loadingbyte.cinecred.natives.zimg.zimg_image_buffer_const
import com.loadingbyte.cinecred.natives.zimg.zimg_image_format
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.javacpp.DoublePointer
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemoryLayout.PathElement.sequenceElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.VarHandle
import kotlin.math.max


class Bitmap2BitmapConverter(
    private val srcSpec: Bitmap.Spec,
    private val dstSpec: Bitmap.Spec,
    /** Disregards [Bitmap.Content] information and instead assumes that the bitmaps are progressive. */
    forceProgressive: Boolean = false
) {

    class AlphaPointer {
        var bitmap: Bitmap? = null
    }

    private val processors = mutableListOf<Processor>()
    private val intermediates = mutableListOf<Bitmap>()

    init {
        require(forceProgressive || srcSpec.content == dstSpec.content) { "Cannot convert different bitmap content." }

        CLEANER.register(this, CleanerAction(processors))

        // Build a pipeline of processors that converts bitmaps to the desired spec.
        //
        // Note: In principle, we could add cases for when swscale suffices, in which case we could ditch zimg.
        // However, for consistency, we decided to always let zimg handle all conversion.
        // Note for future self: If we were to let swscale handle some conversion, we'd need this code:
        //     val swsCtx = sws_alloc_context()
        //     av_opt_set_int(swsCtx, "srcw", ..., 0)
        //     av_opt_set_int(swsCtx, "srch", ..., 0)
        //     av_opt_set_int(swsCtx, "src_format", ..., 0)
        //     av_opt_set_int(swsCtx, "dstw", ..., 0)
        //     av_opt_set_int(swsCtx, "dsth", ..., 0)
        //     av_opt_set_int(swsCtx, "dst_format", ..., 0)
        //     av_opt_set_int(swsCtx, "sws_flags", SWS_ACCURATE_RND.toLong(), 0)
        //     sws_setColorspaceDetails(
        //         swsCtx,
        //         sws_getCoefficients(SWS_CS_DEFAULT), 1,
        //         sws_getCoefficients(...), ...,
        //         0, 1 shl 16, 1 shl 16
        //     )
        //     sws_init_context(swsCtx, null, null)
        // zimg operates on planar pixel formats, so we use swscale to convert to and from that.
        val srcPixFmt = srcSpec.representation.pixelFormat
        val dstPixFmt = dstSpec.representation.pixelFormat
        var effSrcSpec = srcSpec
        var effDstSpec = dstSpec
        if (forceProgressive) {
            effSrcSpec = srcSpec.copy(scan = Bitmap.Scan.PROGRESSIVE, content = Bitmap.Content.PROGRESSIVE_FRAME)
            effDstSpec = dstSpec.copy(scan = Bitmap.Scan.PROGRESSIVE, content = Bitmap.Content.PROGRESSIVE_FRAME)
        }
        val zimgSrcSpec =
            effSrcSpec.copy(representation = srcSpec.representation.copy(pixelFormat = zimgSupportedEquiv(srcPixFmt)))
        val zimgDstSpec =
            effDstSpec.copy(representation = dstSpec.representation.copy(pixelFormat = zimgSupportedEquiv(dstPixFmt)))
        // Add processors one by one so if creating one throws an exception, all the previously created ones will
        // be released by the cleaner.
        if (zimgSrcSpec != effSrcSpec)
            processors += SwsProcessor(effSrcSpec, zimgSrcSpec)
        processors += ZimgProcessor(zimgSrcSpec, zimgDstSpec)
        if (zimgDstSpec != effDstSpec)
            processors += SwsProcessor(zimgDstSpec, effDstSpec)

        // Allocate reusable intermediate bitmaps which connect the processors with each other.
        for (i in 0..<processors.size - 1) {
            // Ensure that the dst spec of a processor is equal to the src spec of the following processor.
            require(processors[i].dstSpec == processors[i + 1].srcSpec)
            intermediates += Bitmap.allocate(processors[i].dstSpec)
        }
    }

    // Use a static class to absolutely ensure that no unwanted references leak into this object.
    private class CleanerAction(private val processors: List<Processor>) : Runnable {
        override fun run() = processors.forEach(Processor::release)
    }

    /** Gets a planar and native-endian pixel format that the given one can be converted to without information loss. */
    private fun zimgSupportedEquiv(pixelFormat: Bitmap.PixelFormat): Bitmap.PixelFormat {
        val eqCode = when (pixelFormat.code) {
            AV_PIX_FMT_YUYV422 -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_RGB24 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR24 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_UYVY422 -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_UYYVYY411 -> AV_PIX_FMT_YUV411P
            AV_PIX_FMT_BGR8 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR4 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR4_BYTE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB8 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB4 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB4_BYTE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_NV12 -> AV_PIX_FMT_YUV420P
            AV_PIX_FMT_NV21 -> AV_PIX_FMT_YUV420P
            AV_PIX_FMT_ARGB -> AV_PIX_FMT_GBRAP
            AV_PIX_FMT_RGBA -> AV_PIX_FMT_GBRAP
            AV_PIX_FMT_ABGR -> AV_PIX_FMT_GBRAP
            AV_PIX_FMT_BGRA -> AV_PIX_FMT_GBRAP
            AV_PIX_FMT_RGB48BE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_RGB48LE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_RGB565BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB565LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB555BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB555LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR565BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR565LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR555BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR555LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_YUV420P16LE -> AV_PIX_FMT_YUV420P16
            AV_PIX_FMT_YUV420P16BE -> AV_PIX_FMT_YUV420P16
            AV_PIX_FMT_YUV422P16LE -> AV_PIX_FMT_YUV422P16
            AV_PIX_FMT_YUV422P16BE -> AV_PIX_FMT_YUV422P16
            AV_PIX_FMT_YUV444P16LE -> AV_PIX_FMT_YUV444P16
            AV_PIX_FMT_YUV444P16BE -> AV_PIX_FMT_YUV444P16
            AV_PIX_FMT_RGB444LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB444BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR444LE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR444BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR48BE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_BGR48LE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_YUV420P9BE -> AV_PIX_FMT_YUV420P9
            AV_PIX_FMT_YUV420P9LE -> AV_PIX_FMT_YUV420P9
            AV_PIX_FMT_YUV420P10BE -> AV_PIX_FMT_YUV420P10
            AV_PIX_FMT_YUV420P10LE -> AV_PIX_FMT_YUV420P10
            AV_PIX_FMT_YUV422P10BE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_YUV422P10LE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_YUV444P9BE -> AV_PIX_FMT_YUV444P9
            AV_PIX_FMT_YUV444P9LE -> AV_PIX_FMT_YUV444P9
            AV_PIX_FMT_YUV444P10BE -> AV_PIX_FMT_YUV444P10
            AV_PIX_FMT_YUV444P10LE -> AV_PIX_FMT_YUV444P10
            AV_PIX_FMT_YUV422P9BE -> AV_PIX_FMT_YUV422P9
            AV_PIX_FMT_YUV422P9LE -> AV_PIX_FMT_YUV422P9
            AV_PIX_FMT_GBRP9BE -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_GBRP9LE -> AV_PIX_FMT_GBRP9
            AV_PIX_FMT_GBRP10BE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_GBRP10LE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_GBRP16BE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_GBRP16LE -> AV_PIX_FMT_GBRP16
            AV_PIX_FMT_YUVA420P9BE -> AV_PIX_FMT_YUVA420P9
            AV_PIX_FMT_YUVA420P9LE -> AV_PIX_FMT_YUVA420P9
            AV_PIX_FMT_YUVA422P9BE -> AV_PIX_FMT_YUVA422P9
            AV_PIX_FMT_YUVA422P9LE -> AV_PIX_FMT_YUVA422P9
            AV_PIX_FMT_YUVA444P9BE -> AV_PIX_FMT_YUVA444P9
            AV_PIX_FMT_YUVA444P9LE -> AV_PIX_FMT_YUVA444P9
            AV_PIX_FMT_YUVA420P10BE -> AV_PIX_FMT_YUVA420P10
            AV_PIX_FMT_YUVA420P10LE -> AV_PIX_FMT_YUVA420P10
            AV_PIX_FMT_YUVA422P10BE -> AV_PIX_FMT_YUVA422P10
            AV_PIX_FMT_YUVA422P10LE -> AV_PIX_FMT_YUVA422P10
            AV_PIX_FMT_YUVA444P10BE -> AV_PIX_FMT_YUVA444P10
            AV_PIX_FMT_YUVA444P10LE -> AV_PIX_FMT_YUVA444P10
            AV_PIX_FMT_YUVA420P16BE -> AV_PIX_FMT_YUVA420P16
            AV_PIX_FMT_YUVA420P16LE -> AV_PIX_FMT_YUVA420P16
            AV_PIX_FMT_YUVA422P16BE -> AV_PIX_FMT_YUVA422P16
            AV_PIX_FMT_YUVA422P16LE -> AV_PIX_FMT_YUVA422P16
            AV_PIX_FMT_YUVA444P16BE -> AV_PIX_FMT_YUVA444P16
            AV_PIX_FMT_YUVA444P16LE -> AV_PIX_FMT_YUVA444P16
            AV_PIX_FMT_NV16 -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_NV20LE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_NV20BE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_RGBA64BE -> AV_PIX_FMT_GBRAP16
            AV_PIX_FMT_RGBA64LE -> AV_PIX_FMT_GBRAP16
            AV_PIX_FMT_BGRA64BE -> AV_PIX_FMT_GBRAP16
            AV_PIX_FMT_BGRA64LE -> AV_PIX_FMT_GBRAP16
            AV_PIX_FMT_YVYU422 -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_0RGB -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_RGB0 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_0BGR -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_BGR0 -> AV_PIX_FMT_GBRP
            AV_PIX_FMT_YUV420P12BE -> AV_PIX_FMT_YUV420P12
            AV_PIX_FMT_YUV420P12LE -> AV_PIX_FMT_YUV420P12
            AV_PIX_FMT_YUV420P14BE -> AV_PIX_FMT_YUV420P14
            AV_PIX_FMT_YUV420P14LE -> AV_PIX_FMT_YUV420P14
            AV_PIX_FMT_YUV422P12BE -> AV_PIX_FMT_YUV422P12
            AV_PIX_FMT_YUV422P12LE -> AV_PIX_FMT_YUV422P12
            AV_PIX_FMT_YUV422P14BE -> AV_PIX_FMT_YUV422P14
            AV_PIX_FMT_YUV422P14LE -> AV_PIX_FMT_YUV422P14
            AV_PIX_FMT_YUV444P12BE -> AV_PIX_FMT_YUV444P12
            AV_PIX_FMT_YUV444P12LE -> AV_PIX_FMT_YUV444P12
            AV_PIX_FMT_YUV444P14BE -> AV_PIX_FMT_YUV444P14
            AV_PIX_FMT_YUV444P14LE -> AV_PIX_FMT_YUV444P14
            AV_PIX_FMT_GBRP12BE -> AV_PIX_FMT_GBRP12
            AV_PIX_FMT_GBRP12LE -> AV_PIX_FMT_GBRP12
            AV_PIX_FMT_GBRP14BE -> AV_PIX_FMT_GBRP14
            AV_PIX_FMT_GBRP14LE -> AV_PIX_FMT_GBRP14
            AV_PIX_FMT_YUV440P10LE -> AV_PIX_FMT_YUV440P10
            AV_PIX_FMT_YUV440P10BE -> AV_PIX_FMT_YUV440P10
            AV_PIX_FMT_YUV440P12LE -> AV_PIX_FMT_YUV440P12
            AV_PIX_FMT_YUV440P12BE -> AV_PIX_FMT_YUV440P12
            AV_PIX_FMT_AYUV64LE -> AV_PIX_FMT_YUVA444P16
            AV_PIX_FMT_AYUV64BE -> AV_PIX_FMT_YUVA444P16
            AV_PIX_FMT_P010LE -> AV_PIX_FMT_YUV420P10
            AV_PIX_FMT_P010BE -> AV_PIX_FMT_YUV420P10
            AV_PIX_FMT_GBRAP12BE -> AV_PIX_FMT_GBRAP12
            AV_PIX_FMT_GBRAP12LE -> AV_PIX_FMT_GBRAP12
            AV_PIX_FMT_GBRAP10BE -> AV_PIX_FMT_GBRAP10
            AV_PIX_FMT_GBRAP10LE -> AV_PIX_FMT_GBRAP10
            AV_PIX_FMT_P016LE -> AV_PIX_FMT_YUV420P16
            AV_PIX_FMT_P016BE -> AV_PIX_FMT_YUV420P16
            AV_PIX_FMT_GBRPF32BE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_GBRPF32LE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_GBRAPF32BE -> AV_PIX_FMT_GBRAPF32
            AV_PIX_FMT_GBRAPF32LE -> AV_PIX_FMT_GBRAPF32
            AV_PIX_FMT_YUVA422P12BE -> AV_PIX_FMT_YUVA422P12
            AV_PIX_FMT_YUVA422P12LE -> AV_PIX_FMT_YUVA422P12
            AV_PIX_FMT_YUVA444P12BE -> AV_PIX_FMT_YUVA444P12
            AV_PIX_FMT_YUVA444P12LE -> AV_PIX_FMT_YUVA444P12
            AV_PIX_FMT_NV24 -> AV_PIX_FMT_YUV444P
            AV_PIX_FMT_NV42 -> AV_PIX_FMT_YUV444P
            AV_PIX_FMT_Y210BE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_Y210LE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_X2RGB10LE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_X2RGB10BE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_X2BGR10LE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_X2BGR10BE -> AV_PIX_FMT_GBRP10
            AV_PIX_FMT_P210BE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_P210LE -> AV_PIX_FMT_YUV422P10
            AV_PIX_FMT_P410BE -> AV_PIX_FMT_YUV444P10
            AV_PIX_FMT_P410LE -> AV_PIX_FMT_YUV444P10
            AV_PIX_FMT_P216BE -> AV_PIX_FMT_YUV422P16
            AV_PIX_FMT_P216LE -> AV_PIX_FMT_YUV422P16
            AV_PIX_FMT_P416BE -> AV_PIX_FMT_YUV444P16
            AV_PIX_FMT_P416LE -> AV_PIX_FMT_YUV444P16
            /* The following pixel formats are supported in FFmpeg 6.0, but we can't upgrade yet due to JavaCPP issues:
            AV_PIX_FMT_VUYA -> AV_PIX_FMT_YUVA444P
            AV_PIX_FMT_RGBAF16BE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_RGBAF16LE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_VUYX -> AV_PIX_FMT_YUV444P
            AV_PIX_FMT_P012LE -> AV_PIX_FMT_YUV420P12
            AV_PIX_FMT_P012BE -> AV_PIX_FMT_YUV420P12
            AV_PIX_FMT_Y212BE -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_Y212LE -> AV_PIX_FMT_YUV422P
            AV_PIX_FMT_XV30BE -> AV_PIX_FMT_YUV444P
            AV_PIX_FMT_XV30LE -> AV_PIX_FMT_YUV444P
            AV_PIX_FMT_XV36BE -> AV_PIX_FMT_YUV444P12
            AV_PIX_FMT_XV36LE -> AV_PIX_FMT_YUV444P12
            AV_PIX_FMT_RGBF32BE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_RGBF32LE -> AV_PIX_FMT_GBRPF32
            AV_PIX_FMT_RGBAF32BE -> AV_PIX_FMT_GBRAPF32
            AV_PIX_FMT_RGBAF32LE -> AV_PIX_FMT_GBRAPF32
            */
            else -> pixelFormat.code
        }
        val eqPixelFormat = Bitmap.PixelFormat.of(eqCode)
        require(eqPixelFormat.isPlanar) { "Pixel format ${pixelFormat.code} has no planar equivalent." }
        require(!eqPixelFormat.isBitstream) { "Bitstream pixel formats are not supported." }
        require(eqPixelFormat.components.size == if (eqPixelFormat.hasAlpha) 4 else 3) { "Pixel format isn't RGB/YUV." }
        return eqPixelFormat
    }

    /**
     * @param dstAlpha If supplied, a bitmap whose last component houses the converted alpha is written there
     *                 even if [dst] no longer has alpha. Notice that alpha components always use full range,
     *                 even if the rest of the frame uses limited range.
     */
    fun convert(src: Bitmap, dst: Bitmap, dstAlpha: AlphaPointer? = null) {
        require(src.spec == srcSpec) { "Actual input bitmap's spec doesn't match spec expected by converter." }
        require(dst.spec == dstSpec) { "Actual output bitmap's spec doesn't match spec expected by converter." }
        for ((i, processor) in processors.withIndex()) {
            val processorSrc = if (i == 0) src else intermediates[i - 1]
            val processorDst = if (i == processors.lastIndex) dst else intermediates[i]
            processor.process(processorSrc, processorDst)
            // If dstAlpha has been requested and is available, return a bitmap that contains converted alpha.
            if (processor is ZimgProcessor && dstAlpha != null && src.spec.representation.pixelFormat.hasAlpha)
                dstAlpha.bitmap = processor.alp ?: processorDst
        }
    }


    private interface Processor {
        val srcSpec: Bitmap.Spec
        val dstSpec: Bitmap.Spec
        fun release()
        fun process(src: Bitmap, dst: Bitmap)
    }


    /** This processor should only be used to convert between pixel formats. */
    private class SwsProcessor(
        override val srcSpec: Bitmap.Spec,
        override val dstSpec: Bitmap.Spec
    ) : Processor {

        private val swsCtx = sws_getContext(
            srcSpec.resolution.widthPx, srcSpec.resolution.heightPx, srcSpec.representation.pixelFormat.code,
            dstSpec.resolution.widthPx, dstSpec.resolution.heightPx, dstSpec.representation.pixelFormat.code,
            0, null, null, null as DoublePointer?
        ).ffmpegThrowIfNull("Could not initialize SWS context")

        override fun release() {
            swsCtx.letIfNonNull(::sws_freeContext)
        }

        override fun process(src: Bitmap, dst: Bitmap) {
            val height = srcSpec.resolution.heightPx
            val srcFrame = src.frame
            val dstFrame = dst.frame
            sws_scale(swsCtx, srcFrame.data(), srcFrame.linesize(), 0, height, dstFrame.data(), dstFrame.linesize())
        }

    }


    private class ZimgProcessor(
        override val srcSpec: Bitmap.Spec,
        override val dstSpec: Bitmap.Spec
    ) : Processor {

        private val arena = Arena.ofConfined()
        private var graph1: MemorySegment? = null
        private var graph2: MemorySegment? = null
        private var srcBuf: MemorySegment? = null
        private var dstBuf: MemorySegment? = null
        private var tmpMem: MemorySegment? = null

        /** When alpha is discarded by this processor, the converted alpha will still be written to this bitmap. */
        var alp: Bitmap? = null
            private set

        init {
            setupSafely(::setup, ::release)
        }

        private fun setup() {
            // If we're converting from a bitmap with alpha to a bitmap without alpha, allocate a side bitmap where
            // converted alpha will be written to so that we can extract it during the convert() call if desired.
            if (srcSpec.representation.pixelFormat.hasAlpha && !dstSpec.representation.pixelFormat.hasAlpha) {
                val dstPixFmt = dstSpec.representation.pixelFormat
                val depth = dstPixFmt.components[0].depth
                val alpPixFmtCode = when {
                    dstPixFmt.isFloat -> when (depth) {
                        32 -> AV_PIX_FMT_GRAYF32
                        else -> throw IllegalArgumentException("Can't pull alpha $depth bit floats.")
                    }
                    else -> when (depth) {
                        8 -> AV_PIX_FMT_GRAY8
                        9 -> AV_PIX_FMT_GRAY9
                        10 -> AV_PIX_FMT_GRAY10
                        12 -> AV_PIX_FMT_GRAY12
                        14 -> AV_PIX_FMT_GRAY14
                        16 -> AV_PIX_FMT_GRAY16
                        else -> throw IllegalArgumentException("Can't pull alpha for bit depth $depth.")
                    }
                }
                val alpSpec = dstSpec.copy(
                    representation = dstSpec.representation.copy(pixelFormat = Bitmap.PixelFormat.of(alpPixFmtCode))
                )
                alp = Bitmap.allocate(alpSpec)
            }

            // Build the zimg filter graph(s).
            when (srcSpec.content) {
                // For progressive video, a single graph suffices.
                Bitmap.Content.PROGRESSIVE_FRAME -> graph1 = buildFieldGraph(ZIMG_FIELD_PROGRESSIVE())
                // For a single separated field, a single graph again suffices.
                Bitmap.Content.ONLY_TOP_FIELD -> graph1 = buildFieldGraph(ZIMG_FIELD_TOP())
                Bitmap.Content.ONLY_BOT_FIELD -> graph1 = buildFieldGraph(ZIMG_FIELD_BOTTOM())
                // For two interleaved fields, build an individual graph for each field.
                // graph1 is responsible for the field that's coded first, and graph2 for the field that's coded second.
                Bitmap.Content.INTERLEAVED_FIELDS -> {
                    graph1 = buildFieldGraph(ZIMG_FIELD_TOP())
                    graph2 = buildFieldGraph(ZIMG_FIELD_BOTTOM())
                }
                Bitmap.Content.INTERLEAVED_FIELDS_REVERSED -> {
                    graph1 = buildFieldGraph(ZIMG_FIELD_BOTTOM())
                    graph2 = buildFieldGraph(ZIMG_FIELD_TOP())
                }
            }

            // Populate the buffer structs.
            srcBuf = zimg_image_buffer_const.allocate(arena)
            dstBuf = zimg_image_buffer.allocate(arena)
            zimg_image_buffer_const.`version$set`(srcBuf, ZIMG_API_VERSION())
            zimg_image_buffer.`version$set`(dstBuf, ZIMG_API_VERSION())
            for (plane in 0L..<4L) {
                ZIMG_BUF_CONST_MASK.set(srcBuf, plane, ZIMG_BUFFER_MAX())
                ZIMG_BUF_MASK.set(dstBuf, plane, ZIMG_BUFFER_MAX())
            }

            // Find how much temporary memory we need, and allocate it.
            var tmpSize = getTmpSize(graph1!!)
            graph2?.let { tmpSize = max(tmpSize, getTmpSize(it)) }
            tmpMem = arena.allocate(tmpSize, Bitmap.BYTE_ALIGNMENT.toLong())
        }

        private fun buildFieldGraph(fieldParity: Int): MemorySegment {
            // Populate the format structs.
            val srcFmt = populateImageFormat(srcSpec, fieldParity)
            val dstFmt = populateImageFormat(dstSpec, fieldParity)
            // Populate the params struct.
            val params = zimg_graph_builder_params.allocate(arena)
            zimg_graph_builder_params_default(params, ZIMG_API_VERSION())
            zimg_graph_builder_params.`resample_filter$set`(params, ZIMG_RESIZE_LANCZOS())
            zimg_graph_builder_params.`cpu_type$set`(params, ZIMG_CPU_AUTO_64B())
            // Build the zimg filter graph.
            return zimg_filter_graph_build(srcFmt, dstFmt, params)
                .zimgThrowIfNull("Could not build zimg graph")
        }

        private fun populateImageFormat(spec: Bitmap.Spec, fieldParity: Int): MemorySegment {
            var height = spec.resolution.heightPx
            when (spec.content) {
                Bitmap.Content.PROGRESSIVE_FRAME, Bitmap.Content.ONLY_TOP_FIELD, Bitmap.Content.ONLY_BOT_FIELD -> {}
                Bitmap.Content.INTERLEAVED_FIELDS, Bitmap.Content.INTERLEAVED_FIELDS_REVERSED -> {
                    require(height % 2 == 0) { "For zimg, interleaved fields must share the same height." }
                    height /= 2
                }
            }
            val repr = spec.representation
            val pixFmt = repr.pixelFormat
            val depth = pixFmt.components[0].depth
            val pixelType = when {
                pixFmt.isFloat -> when (depth) {
                    16 -> ZIMG_PIXEL_HALF()
                    32 -> ZIMG_PIXEL_FLOAT()
                    else -> throw IllegalArgumentException("For zimg, the float bit depth ($depth) must be 16 or 32.")
                }
                else -> when {
                    depth <= 8 -> ZIMG_PIXEL_BYTE()
                    depth <= 16 -> ZIMG_PIXEL_WORD()
                    else -> throw IllegalArgumentException("For zimg, the bit depth ($depth) must not exceed 16.")
                }
            }
            val alpha = when {
                repr.isAlphaPremultiplied -> ZIMG_ALPHA_PREMULTIPLIED()
                pixFmt.hasAlpha || alp != null -> ZIMG_ALPHA_STRAIGHT()
                else -> ZIMG_ALPHA_NONE()
            }

            require(pixFmt.isPlanar) { "For zimg, the pixel format must be planar." }
            for (component in pixFmt.components)
                require(component.depth == depth) { "For zimg, all components must have equal bit depth." }

            val fmt = zimg_image_format.allocate(arena)
            zimg_image_format_default(fmt, ZIMG_API_VERSION())
            zimg_image_format.`width$set`(fmt, spec.resolution.widthPx)
            zimg_image_format.`height$set`(fmt, height)
            zimg_image_format.`pixel_type$set`(fmt, pixelType)
            if (!pixFmt.isRGB) {
                zimg_image_format.`subsample_w$set`(fmt, pixFmt.hChromaSub)
                zimg_image_format.`subsample_h$set`(fmt, pixFmt.vChromaSub)
            }
            zimg_image_format.`color_family$set`(fmt, if (pixFmt.isRGB) ZIMG_COLOR_RGB() else ZIMG_COLOR_YUV())
            zimg_image_format.`matrix_coefficients$set`(fmt, zimgMatrix(repr.yCbCrCoefficients))
            zimg_image_format.`transfer_characteristics$set`(fmt, zimgTransfer(repr.transferCharacteristic))
            zimg_image_format.`color_primaries$set`(fmt, zimgPrimaries(repr.primaries))
            zimg_image_format.`depth$set`(fmt, depth)
            zimg_image_format.`pixel_range$set`(fmt, zimgRange(repr.range))
            zimg_image_format.`field_parity$set`(fmt, fieldParity)
            zimg_image_format.`chroma_location$set`(fmt, zimgChroma(repr.chromaLocation))
            zimg_image_format.`alpha$set`(fmt, alpha)
            return fmt
        }

        private fun getTmpSize(graph: MemorySegment): Long {
            val out = arena.allocate(JAVA_LONG)
            zimg_filter_graph_get_tmp_size(graph, out)
                .zimgThrowIfErrnum("Could not obtain the temp zimg buffer size")
            return out.get(JAVA_LONG, 0L)
        }

        override fun release() {
            graph1?.let(::zimg_filter_graph_free)
            graph2?.let(::zimg_filter_graph_free)
            arena?.close()
        }

        override fun process(src: Bitmap, dst: Bitmap) {
            when (srcSpec.content) {
                Bitmap.Content.PROGRESSIVE_FRAME, Bitmap.Content.ONLY_TOP_FIELD, Bitmap.Content.ONLY_BOT_FIELD ->
                    processField(graph1!!, src, dst, offset = 0, step = 1)
                Bitmap.Content.INTERLEAVED_FIELDS, Bitmap.Content.INTERLEAVED_FIELDS_REVERSED -> {
                    processField(graph1!!, src, dst, offset = 0, step = 2)
                    processField(graph2!!, src, dst, offset = 1, step = 2)
                }
            }
        }

        private fun processField(graph: MemorySegment, src: Bitmap, dst: Bitmap, offset: Int, step: Int) {
            val srcFrame = src.frame
            for (i in srcSpec.representation.pixelFormat.components.indices) {
                val srcPlane = srcSpec.representation.pixelFormat.components[i].plane
                val srcData = srcFrame.data(srcPlane).address()
                val srcStride = srcFrame.linesize(srcPlane).toLong()
                require(srcData % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion src data[$srcPlane] is misaligned." }
                require(srcStride % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion src stride[$srcPlane] is misaligned." }
                ZIMG_BUF_CONST_DATA.set(srcBuf, i.toLong(), MemorySegment.ofAddress(srcData + offset * srcStride))
                ZIMG_BUF_CONST_STRIDE.set(srcBuf, i.toLong(), srcStride * step)
            }

            val dstFrame = dst.frame
            var i = 0
            while (i < dstSpec.representation.pixelFormat.components.size) {
                val dstPlane = dstSpec.representation.pixelFormat.components[i].plane
                val dstData = dstFrame.data(dstPlane).address()
                val dstStride = dstFrame.linesize(dstPlane).toLong()
                require(dstData % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion dst data[$dstPlane] is misaligned." }
                require(dstStride % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion dst stride[$dstPlane] is misaligned." }
                ZIMG_BUF_DATA.set(dstBuf, i.toLong(), MemorySegment.ofAddress(dstData + offset * dstStride))
                ZIMG_BUF_STRIDE.set(dstBuf, i.toLong(), dstStride * step)
                i++
            }
            alp?.let { alp ->
                val alpFrame = alp.frame
                val alpData = alpFrame.data(0).address()
                val alpStride = alpFrame.linesize(0).toLong()
                require(alpData % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion alpha data is misaligned." }
                require(alpStride % Bitmap.BYTE_ALIGNMENT == 0L) { "Conversion alpha stride is misaligned." }
                ZIMG_BUF_DATA.set(dstBuf, i.toLong(), alpData + offset * alpStride)
                ZIMG_BUF_STRIDE.set(dstBuf, i.toLong(), alpStride * step)
            }

            zimg_filter_graph_process(graph, srcBuf, dstBuf, tmpMem, NULL, NULL, NULL, NULL)
                .zimgThrowIfErrnum("Error while converting a frame or field with zimg")
        }

        private fun MemorySegment?.zimgThrowIfNull(message: String): MemorySegment =
            if (this == null || this == NULL)
                throw ZimgException(zimgExcStr(message))
            else this

        private fun Int.zimgThrowIfErrnum(message: String): Int =
            if (this < 0)
                throw ZimgException(zimgExcStr(message))
            else this

        private fun zimgExcStr(message: String): String {
            val string = arena.allocate(1024)
            val errnum = zimg_get_last_error(string, string.byteSize())
            return "$message: ${string.getUtf8String(0)} (zimg error number $errnum)."
        }

        companion object {

            private val ZIMG_BUF_CONST_DATA: VarHandle
            private val ZIMG_BUF_CONST_STRIDE: VarHandle
            private val ZIMG_BUF_CONST_MASK: VarHandle
            private val ZIMG_BUF_DATA: VarHandle
            private val ZIMG_BUF_STRIDE: VarHandle
            private val ZIMG_BUF_MASK: VarHandle

            init {
                val planePath = arrayOf(groupElement("plane"), sequenceElement())
                val bufConst = zimg_image_buffer_const.`$LAYOUT`()
                val buf = zimg_image_buffer.`$LAYOUT`()
                ZIMG_BUF_CONST_DATA = bufConst.varHandle(*planePath, groupElement("data")).withInvokeExactBehavior()
                ZIMG_BUF_CONST_STRIDE = bufConst.varHandle(*planePath, groupElement("stride")).withInvokeExactBehavior()
                ZIMG_BUF_CONST_MASK = bufConst.varHandle(*planePath, groupElement("mask")).withInvokeExactBehavior()
                ZIMG_BUF_DATA = buf.varHandle(*planePath, groupElement("data")).withInvokeExactBehavior()
                ZIMG_BUF_STRIDE = buf.varHandle(*planePath, groupElement("stride")).withInvokeExactBehavior()
                ZIMG_BUF_MASK = buf.varHandle(*planePath, groupElement("mask")).withInvokeExactBehavior()
            }

            private fun zimgRange(range: Int): Int = when (range) {
                AVCOL_RANGE_MPEG -> ZIMG_RANGE_LIMITED()
                AVCOL_RANGE_JPEG -> ZIMG_RANGE_FULL()
                else -> throw IllegalArgumentException("Unknown range: $range")
            }

            private fun zimgPrimaries(primaries: Int): Int = when (primaries) {
                AVCOL_PRI_BT709 -> ZIMG_MATRIX_BT709()
                AVCOL_PRI_BT470M -> ZIMG_PRIMARIES_BT470_M()
                AVCOL_PRI_BT470BG -> ZIMG_PRIMARIES_BT470_BG()
                AVCOL_PRI_SMPTE170M -> ZIMG_PRIMARIES_ST170_M()
                AVCOL_PRI_SMPTE240M -> ZIMG_PRIMARIES_ST240_M()
                AVCOL_PRI_FILM -> ZIMG_PRIMARIES_FILM()
                AVCOL_PRI_BT2020 -> ZIMG_PRIMARIES_BT2020()
                AVCOL_PRI_SMPTE428 -> ZIMG_PRIMARIES_ST428()
                AVCOL_PRI_SMPTE431 -> ZIMG_PRIMARIES_ST431_2()
                AVCOL_PRI_SMPTE432 -> ZIMG_PRIMARIES_ST432_1()
                AVCOL_PRI_EBU3213 -> ZIMG_PRIMARIES_EBU3213_E()
                else -> throw IllegalArgumentException("Unknown primaries: $primaries")
            }

            private fun zimgTransfer(transferCharacteristic: Int): Int = when (transferCharacteristic) {
                AVCOL_TRC_BT709 -> ZIMG_TRANSFER_BT709()
                AVCOL_TRC_GAMMA22 -> ZIMG_TRANSFER_BT470_M()
                AVCOL_TRC_GAMMA28 -> ZIMG_TRANSFER_BT470_BG()
                AVCOL_TRC_SMPTE170M -> ZIMG_TRANSFER_BT601()
                AVCOL_TRC_SMPTE240M -> ZIMG_TRANSFER_ST240_M()
                AVCOL_TRC_LINEAR -> ZIMG_TRANSFER_LINEAR()
                AVCOL_TRC_LOG -> ZIMG_TRANSFER_LOG_100()
                AVCOL_TRC_LOG_SQRT -> ZIMG_TRANSFER_LOG_316()
                AVCOL_TRC_IEC61966_2_4 -> ZIMG_TRANSFER_IEC_61966_2_4()
                AVCOL_TRC_IEC61966_2_1 -> ZIMG_TRANSFER_IEC_61966_2_1()
                AVCOL_TRC_BT2020_10 -> ZIMG_TRANSFER_BT2020_10()
                AVCOL_TRC_BT2020_12 -> ZIMG_TRANSFER_BT2020_12()
                AVCOL_TRC_SMPTE2084 -> ZIMG_TRANSFER_ST2084()
                AVCOL_TRC_ARIB_STD_B67 -> ZIMG_TRANSFER_ARIB_B67()
                else -> throw IllegalArgumentException("Unknown transfer characteristic: $transferCharacteristic")
            }

            private fun zimgMatrix(yCbCrCoefficients: Int): Int = when (yCbCrCoefficients) {
                AVCOL_SPC_RGB -> ZIMG_MATRIX_RGB()
                AVCOL_SPC_BT709 -> ZIMG_MATRIX_BT709()
                AVCOL_SPC_FCC -> ZIMG_MATRIX_FCC()
                AVCOL_SPC_BT470BG -> ZIMG_MATRIX_BT470_BG()
                AVCOL_SPC_SMPTE170M -> ZIMG_MATRIX_ST170_M()
                AVCOL_SPC_SMPTE240M -> ZIMG_MATRIX_ST240_M()
                AVCOL_SPC_YCGCO -> ZIMG_MATRIX_YCGCO()
                AVCOL_SPC_BT2020_NCL -> ZIMG_MATRIX_BT2020_NCL()
                AVCOL_SPC_BT2020_CL -> ZIMG_MATRIX_BT2020_CL()
                AVCOL_SPC_CHROMA_DERIVED_NCL -> ZIMG_MATRIX_CHROMATICITY_DERIVED_NCL()
                AVCOL_SPC_CHROMA_DERIVED_CL -> ZIMG_MATRIX_CHROMATICITY_DERIVED_CL()
                AVCOL_SPC_ICTCP -> ZIMG_MATRIX_ICTCP()
                else -> throw IllegalArgumentException("Unknown YCbCr coefficients: $yCbCrCoefficients")
            }

            private fun zimgChroma(chromaLocation: Int): Int = when (chromaLocation) {
                AVCHROMA_LOC_LEFT, AVCHROMA_LOC_UNSPECIFIED -> ZIMG_CHROMA_LEFT()
                AVCHROMA_LOC_CENTER -> ZIMG_CHROMA_CENTER()
                AVCHROMA_LOC_TOPLEFT -> ZIMG_CHROMA_TOP_LEFT()
                AVCHROMA_LOC_TOP -> ZIMG_CHROMA_TOP()
                AVCHROMA_LOC_BOTTOMLEFT -> ZIMG_CHROMA_BOTTOM_LEFT()
                AVCHROMA_LOC_BOTTOM -> ZIMG_CHROMA_BOTTOM()
                else -> throw IllegalArgumentException("Unknown chroma location: $chromaLocation")
            }

        }

        private class ZimgException(message: String) : RuntimeException(message)

    }

}
