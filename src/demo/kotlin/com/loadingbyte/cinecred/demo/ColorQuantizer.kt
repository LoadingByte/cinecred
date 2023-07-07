package com.loadingbyte.cinecred.demo

import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.IndexColorModel
import java.awt.image.Raster
import java.util.*


/** Performs the median-cut algorithm. */
fun quantizeColors(images: List<BufferedImage>): List<BufferedImage> {
    // Collect all colors that occur in any image. Don't duplicate colors, but instead store each color's multiplicity.
    val rgb2colors = HashMap<Int, MultiColor>()
    for (image in images) {
        val w = image.width
        val h = image.height
        for (y in 0..<h)
            for (x in 0..<w) {
                val rgb = image.getRGB(x, y) and 0xffffff
                val r = rgb shr 16
                val g = (rgb shr 8) and 0xff
                val b = rgb and 0xff
                rgb2colors.computeIfAbsent(rgb) { MultiColor(intArrayOf(r, g, b), 0) }.apply { multiplicity += 1 }
            }
    }
    val occurringColors = ArrayList(rgb2colors.values)

    // Create a priority queue of buckets with the highest variance bucket on top. Add a single bucket with all colors.
    val buckets = PriorityQueue<Bucket>(256, Comparator.comparing { bucket -> -bucket.maxChannelVar })
    buckets.add(Bucket(occurringColors))

    // Iteratively do the following:
    // Identify the bucket with the highest variance in one of its three color channels. Split the bucket along that
    // channel, with each sub-bucket containing roughly half of the colors of the original bucket.
    for (itr in 0..<255) {
        if (buckets.peek().colors.size == 1)
            break
        val bucket = buckets.poll()
        bucket.colors.sortWith(Comparator.comparingInt { color -> color.channels[bucket.maxVarChannel] })
        val halfCount = bucket.count / 2
        var counter = 0
        for ((colorIdx, color) in bucket.colors.withIndex()) {
            counter += color.multiplicity
            if (colorIdx != 0 && counter > halfCount) {
                // Note: ArrayList.SubList.subList() avoids multiple levels of SubList nesting, enhancing performance.
                buckets.offer(Bucket(bucket.colors.subList(0, colorIdx)))
                buckets.offer(Bucket(bucket.colors.subList(colorIdx, bucket.colors.size)))
                break
            }
        }
    }

    // Map RGB values to bucket indices, and create an IndexColorModel based on the mean color from each bucket.
    val rgb2palette = HashMap<Int, Byte>()
    val paletteChannels = Array(3) { ByteArray(buckets.size) }
    for ((buckIdx, bucket) in buckets.withIndex()) {
        for (c in 0..<3)
            paletteChannels[c][buckIdx] = bucket.channelMeans[c].toByte()
        for (color in bucket.colors)
            rgb2palette[(color.channels[0] shl 16) or (color.channels[1] shl 8) or color.channels[2]] = buckIdx.toByte()
    }
    val cm = IndexColorModel(8, buckets.size, paletteChannels[0], paletteChannels[1], paletteChannels[2])
    // Use the RGB-to-bucket lookup to convert all input images to quantized ones.
    return images.map { image ->
        val w = image.width
        val h = image.height
        val arr = ByteArray(w * h)
        var i = 0
        for (y in 0..<h)
            for (x in 0..<w)
                arr[i++] = rgb2palette.getValue(image.getRGB(x, y) and 0xffffff)
        val raster = Raster.createInterleavedRaster(DataBufferByte(arr, arr.size), w, h, w, 1, intArrayOf(0), null)
        BufferedImage(cm, raster, false, null)
    }
}


private class MultiColor(val channels: IntArray, var multiplicity: Int)


private class Bucket(val colors: MutableList<MultiColor>) {

    val count: Int
    val channelMeans: IntArray
    val channelVars: DoubleArray

    init {
        var count = 0
        val meanSums = LongArray(3)
        for (color in colors) {
            count += color.multiplicity
            for (c in 0..<3)
                meanSums[c] += color.multiplicity * color.channels[c].toLong()
        }
        this.count = count
        // This division should really round to the nearest integer, but that would require expensive floats.
        channelMeans = IntArray(3) { c -> (meanSums[c] / count).toInt() }
        val varianceSums = LongArray(3)
        for (color in colors)
            for (c in 0..<3)
                varianceSums[c] += color.multiplicity * sq(color.channels[c] - channelMeans[c]).toLong()
        channelVars = DoubleArray(3) { c -> varianceSums[c] / count.toDouble() }
    }

    val maxVarChannel =
        if (channelVars[0] > channelVars[1] && channelVars[0] > channelVars[2]) 0
        else if (channelVars[1] > channelVars[2]) 1 else 2
    val maxChannelVar = channelVars[maxVarChannel]

    private fun sq(x: Int) = x * x

}
