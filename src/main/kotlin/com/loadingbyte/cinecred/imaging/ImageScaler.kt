package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.SystemInfo
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.Raster
import java.util.*


private val cache = Collections.synchronizedMap(WeakHashMap<BufferedImage, BufferedImage>())

/**
 * Scales a [BufferedImage] using the Lanczos algorithm, implemented by FFmpeg.
 *
 * Scaling happens in the sRGB color space, as opposed to the often recommended linear RGB color space. This is for a
 * couple of reasons:
 *   - Converting from the sRGB to a linear transfer characteristic requires the zimg library, whose usage in turn needs
 *     quite a bit of ceremony, as is evident from the VideoWriter class. By staying in sRGB, we can instead solely rely
 *     on FFmpeg's swscale, which needs less ceremony as it can directly handle Java2D's pixel format.
 *   - Scaling in linear RGB is not always advantageous, as is shown here: https://entropymine.com/imageworsener/gamma/
 */
fun scaleImageLanczos(inImage: BufferedImage, outW: Int, outH: Int): BufferedImage {
    if (inImage.width == outW && inImage.height == outH)
        return inImage

    val cachedOutImage = cache[inImage]
    if (cachedOutImage != null && cachedOutImage.width == outW && cachedOutImage.height == outH)
        return cachedOutImage

    // Determine various values.
    val pixelFormat = when (inImage.type) {
        BufferedImage.TYPE_3BYTE_BGR -> AV_PIX_FMT_BGR24
        BufferedImage.TYPE_4BYTE_ABGR -> AV_PIX_FMT_ABGR
        else -> throw IllegalArgumentException("Image type does not support scaling: ${inImage.type}")
    }
    val inW = inImage.width
    val inH = inImage.height
    val bytesPerPixel = inImage.colorModel.numComponents
    val inSize = inW * inH * bytesPerPixel
    val outSize = outW * outH * bytesPerPixel

    // On macOS, some (but not all) swscale pixel format converters read frame data beyond the last pixel. This
    // is most certainly a bug. As a workaround, adding 1 extra byte at the end of the frame array prevents
    // segfaults, and we have empirically confirmed that the resulting videos and image sequences do not differ
    // from ones generated on Linux without this extra byte. Further, to be absolutely safe, we not only add 1,
    // but 256 bytes.
    // The issue is tracked here: https://trac.ffmpeg.org/ticket/10144
    val inSizeWithPadding = inSize + if (SystemInfo.isMacOS) 256 else 0

    // Allocate input and output memory, and fill the input memory with the image's data.
    val inMem = BytePointer(av_malloc(inSizeWithPadding.toLong()))
    val outMem = BytePointer(av_malloc(outSize.toLong()))
    val inImageBuffer = inImage.raster.dataBuffer as DataBufferByte
    require(inImageBuffer.size == inSize) { "Scaling doesn't support subimages." }
    inMem.put(inImageBuffer.data, 0, inSize)

    // Do the scaling.
    val swsCtx = sws_getContext(
        inW, inH, pixelFormat, outW, outH, pixelFormat, SWS_LANCZOS or SWS_ACCURATE_RND,
        null, null, null as DoublePointer?
    )
    val srcSlice = PointerPointer(*arrayOf(inMem))
    val dstSlice = PointerPointer(*arrayOf(outMem))
    val srcStride = IntPointer(inW * bytesPerPixel)
    val dstStride = IntPointer(outW * bytesPerPixel)
    sws_scale(swsCtx, srcSlice, srcStride, 0, inH, dstSlice, dstStride)

    // Get a byte array from the output memory and construct and image with it.
    val outRaster = Raster.createWritableRaster(
        inImage.sampleModel.createCompatibleSampleModel(outW, outH),
        DataBufferByte(ByteArray(outSize).also(outMem::get), outSize),
        null
    )
    val outImage = BufferedImage(inImage.colorModel, outRaster, false, null)

    av_free(inMem)
    av_free(outMem)

    cache[inImage] = outImage
    return outImage
}
