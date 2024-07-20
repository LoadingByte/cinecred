package com.loadingbyte.cinecred

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream


class Logo(file: File) {

    private val svg = requireNotNull(
        file.inputStream().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }
    ) { "Failed to load SVG: $file" }

    fun rasterize(size: Int, margin: Double = 0.0, imageType: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
        val img = BufferedImage(size, size, imageType)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val transl = margin * size
        val scaling = (size * (1 - 2 * margin)) / svg.size().width
        g2.translate(transl, transl)
        g2.scale(scaling, scaling)
        svg.render(null, g2)
        g2.dispose()
        return img
    }

    fun transcode(vararg sizes: Int, margin: Double = 0.0, file: File) {
        val imageType = if (file.extension == "ico") BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_INT_ARGB
        val images = sizes.map { size -> rasterize(size, margin, imageType) }

        file.delete()
        file.parentFile.mkdirs()
        FileImageOutputStream(file).use { stream ->
            val writer = ImageIO.getImageWritersBySuffix(file.extension).next()
            writer.output = stream
            if (images.size == 1)
                writer.write(images[0])
            else {
                writer.prepareWriteSequence(null)
                for (image in images)
                    writer.writeToSequence(IIOImage(image, null, null), null)
                writer.endWriteSequence()
            }
        }
    }

}
