package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.Resolution
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.*


class Image2BitmapConverter(
    dstSpec: Bitmap.Spec,
    srcResolution: Resolution = dstSpec.resolution,
    srcHasAlpha: Boolean = dstSpec.representation.pixelFormat.hasAlpha,
    srcIsAlphaPremultiplied: Boolean = srcHasAlpha && dstSpec.representation.isAlphaPremultiplied
) {

    private val srcMirrorBitmap: Bitmap
    private val delegate: Bitmap2BitmapConverter

    init {
        val srcMirrorSpec = mirrorSpec(srcResolution, srcHasAlpha, srcIsAlphaPremultiplied)
        srcMirrorBitmap = Bitmap.allocate(srcMirrorSpec)
        delegate = Bitmap2BitmapConverter(srcMirrorSpec, dstSpec, forceProgressive = true)
    }

    /**
     * Source images must be encoded as [BufferedImage.TYPE_3BYTE_BGR] or [BufferedImage.TYPE_4BYTE_ABGR],
     * depending on whether the destination spec has an alpha channel. They must also use the sRGB color space.
     */
    fun convert(src: BufferedImage, dst: Bitmap) {
        copyImageIntoMirrorBitmap(src, srcMirrorBitmap)
        delegate.convert(srcMirrorBitmap, dst)
    }

}


class Bitmap2ImageConverter(
    srcSpec: Bitmap.Spec,
    dstResolution: Resolution = srcSpec.resolution,
    dstHasAlpha: Boolean = srcSpec.representation.pixelFormat.hasAlpha,
    dstIsAlphaPremultiplied: Boolean = dstHasAlpha && srcSpec.representation.isAlphaPremultiplied
) {

    private val dstMirrorBitmap: Bitmap
    private val delegate: Bitmap2BitmapConverter

    init {
        val dstMirrorSpec = mirrorSpec(dstResolution, dstHasAlpha, dstIsAlphaPremultiplied)
        dstMirrorBitmap = Bitmap.allocate(dstMirrorSpec)
        delegate = Bitmap2BitmapConverter(srcSpec, dstMirrorSpec, forceProgressive = true)
    }

    fun convert(src: Bitmap): BufferedImage {
        delegate.convert(src, dstMirrorBitmap)
        return createImageFromMirrorBitmap(dstMirrorBitmap)
    }

}


/** This converter can be used to rescale [BufferedImage]s using swscale, which offers the Lanczos algorithm. */
class Image2ImageConverter(
    srcResolution: Resolution,
    dstResolution: Resolution,
    hasAlpha: Boolean,
    isAlphaPremultiplied: Boolean
) {

    private val srcMirrorBitmap: Bitmap
    private val dstMirrorBitmap: Bitmap
    private val delegate: Bitmap2BitmapConverter

    init {
        val srcMirrorSpec = mirrorSpec(srcResolution, hasAlpha, isAlphaPremultiplied)
        val dstMirrorSpec = mirrorSpec(dstResolution, hasAlpha, isAlphaPremultiplied)
        srcMirrorBitmap = Bitmap.allocate(srcMirrorSpec)
        dstMirrorBitmap = Bitmap.allocate(dstMirrorSpec)
        delegate = Bitmap2BitmapConverter(srcMirrorSpec, dstMirrorSpec, forceProgressive = true)
    }

    /** @see [Image2BitmapConverter.convert] */
    fun convert(src: BufferedImage): BufferedImage {
        copyImageIntoMirrorBitmap(src, srcMirrorBitmap)
        delegate.convert(srcMirrorBitmap, dstMirrorBitmap)
        return createImageFromMirrorBitmap(dstMirrorBitmap)
    }

}


/** A bitmap spec that mirrors how all the [BufferedImage]s we're using in our program are stored internally. */
private fun mirrorSpec(
    resolution: Resolution,
    hasAlpha: Boolean,
    isAlphaPremultiplied: Boolean
) = Bitmap.Spec(
    resolution,
    Bitmap.Representation(
        Bitmap.PixelFormat.of(if (hasAlpha) AV_PIX_FMT_ABGR else AV_PIX_FMT_BGR24),
        AVCOL_RANGE_JPEG,
        AVCOL_PRI_BT709,
        AVCOL_TRC_IEC61966_2_1, // sRGB
        AVCOL_SPC_RGB,
        AVCHROMA_LOC_UNSPECIFIED,
        isAlphaPremultiplied
    ),
    Bitmap.Scan.PROGRESSIVE,
    Bitmap.Content.PROGRESSIVE_FRAME
)

private fun copyImageIntoMirrorBitmap(image: BufferedImage, bitmap: Bitmap) {
    require(image.colorModel.colorSpace.isCS_sRGB) { "Converter only supports sRGB BufferedImages." }
    val reqT = if (bitmap.spec.representation.pixelFormat.hasAlpha)
        BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR
    require(image.type == reqT) { "Converter expects BufferedImage to be of type $reqT, not ${image.type}." }
    require(image.isAlphaPremultiplied == bitmap.spec.representation.isAlphaPremultiplied) { "Premultiply mismatch" }
    require(image.width == image.sampleModel.width && image.height == image.sampleModel.height) { "Subimage" }
    // Transfer the BufferedImage's data into the input frame. Two copies are necessary because:
    //   - We need av_image_copy_plane() to insert padding between lines according to the bitmap's linesize.
    //   - JavaCPP cannot directly pass pointers to Java arrays on the heap to the native C code.
    // We have empirically confirmed that two copies are just as fast as one copy in the grand scheme of things.
    val imageBuffer = image.raster.dataBuffer as DataBufferByte
    val imageData = BytePointer(imageBuffer.size.toLong()).put(imageBuffer.data, 0, imageBuffer.size)
    val imageLs = (image.raster.sampleModel as ComponentSampleModel).scanlineStride
    val frame = bitmap.frame
    av_image_copy_plane(frame.data(0), frame.linesize(0), imageData, imageLs, imageLs, image.height)
}

// This function duplicates what is done in the BufferedImage() constructor.
private fun createImageFromMirrorBitmap(bitmap: Bitmap): BufferedImage {
    val (resolution, representation) = bitmap.spec
    val frame = bitmap.frame
    val hasAlpha = representation.pixelFormat.hasAlpha
    val isAlphaPremultiplied = representation.isAlphaPremultiplied
    val transparency: Int
    val bOffs: IntArray
    if (!hasAlpha) {
        transparency = Transparency.OPAQUE
        bOffs = intArrayOf(2, 1, 0)
    } else {
        transparency = Transparency.TRANSLUCENT
        bOffs = intArrayOf(3, 2, 1, 0)
    }
    val dataBufferSize = resolution.heightPx * frame.linesize(0)
    val dataBuffer = DataBufferByte(ByteArray(dataBufferSize).also(frame.data(0)::get), dataBufferSize)
    val raster = Raster.createInterleavedRaster(
        dataBuffer, resolution.widthPx, resolution.heightPx, frame.linesize(0), bOffs.size, bOffs, null
    )
    val cs = ColorSpace.getInstance(ColorSpace.CS_sRGB)
    val cm = ComponentColorModel(cs, null, hasAlpha, isAlphaPremultiplied, transparency, DataBuffer.TYPE_BYTE)
    return BufferedImage(cm, raster, isAlphaPremultiplied, null)
}
