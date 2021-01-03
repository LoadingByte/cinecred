package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.drawer.setHighQuality
import org.apache.batik.dom.GenericDOMImplementation
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D
import org.jfree.pdf.PDFDocument
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO


class PageSequenceRenderJob(
    private val background: Color,
    private val pageDefImages: List<DeferredImage>,
    private val format: Format,
    private val dir: Path,
    private val filenamePattern: String
) : RenderJob {

    enum class Format { PNG, SVG }

    override val destination: String
        get() = dir.resolve(filenamePattern).toString()

    override fun render(progressCallback: (Float) -> Unit) {
        Files.createDirectories(dir)

        for ((idx, pageDefImage) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = pageDefImage.intWidth
            val pageHeight = pageDefImage.intHeight
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG -> {
                    val pageImage = BufferedImage(pageWidth, pageHeight, BufferedImage.TYPE_INT_RGB)
                    // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
                    val g2 = GraphicsUtil.createGraphics(pageImage)
                    try {
                        g2.setHighQuality()
                        g2.color = background
                        g2.fillRect(0, 0, pageWidth, pageHeight)
                        pageDefImage.materialize(g2, drawGuides = false)
                    } finally {
                        g2.dispose()
                    }
                    ImageIO.write(pageImage, "png", pageFile.toFile())
                }
                Format.SVG -> {
                    val doc = GenericDOMImplementation.getDOMImplementation()
                        .createDocument("http://www.w3.org/2000/svg", "svg", null)
                    val ctx = SVGGeneratorContext.createDefault(doc)
                    ctx.comment = null
                    val g2 = SVGGraphics2D(ctx, false)
                    g2.svgCanvasSize = Dimension(pageWidth, pageHeight)
                    g2.color = background
                    g2.fillRect(0, 0, pageWidth, pageHeight)
                    pageDefImage.materialize(g2, drawGuides = false)
                    Files.newBufferedWriter(pageFile).use { writer -> g2.stream(writer, true) }
                }
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }
    }

}


class PDFRenderJob(
    private val background: Color,
    private val pageDefImages: List<DeferredImage>,
    private val file: Path,
) : RenderJob {

    override val destination: String
        get() = file.toString()

    override fun render(progressCallback: (Float) -> Unit) {
        Files.createDirectories(file.parent)

        val pdfDoc = PDFDocument()

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pdfPage = pdfDoc.createPage(Rectangle(page.intWidth, page.intHeight))

            val g2 = pdfPage.graphics2D
            g2.color = background
            g2.fillRect(0, 0, page.intWidth, page.intHeight)
            page.materialize(g2, drawGuides = false, addInvisibleText = true)

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }

        pdfDoc.writeToFile(file.toFile())
    }

}
