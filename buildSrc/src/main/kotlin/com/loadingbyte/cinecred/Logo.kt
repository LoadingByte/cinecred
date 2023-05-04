package com.loadingbyte.cinecred

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream


class Logo(file: File) {

    private val node: GraphicsNode
    private val width: Double

    init {
        val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
            .createDocument(null, file.bufferedReader())
        val ctx = BridgeContext(UserAgentAdapter())
        node = GVTBuilder().build(ctx, doc)
        width = ctx.documentSize.width
    }

    fun rasterize(size: Int, margin: Double = 0.0, imageType: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
        val img = BufferedImage(size, size, imageType)
        val g2 = GraphicsUtil.createGraphics(img)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val transl = margin * size
        val scaling = (size * (1 - 2 * margin)) / width
        g2.translate(transl, transl)
        g2.scale(scaling, scaling)
        node.paint(g2)
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
