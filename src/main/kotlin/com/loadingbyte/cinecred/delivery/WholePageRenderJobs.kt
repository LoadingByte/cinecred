package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import com.loadingbyte.cinecred.drawer.setHighQuality
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.batik.dom.GenericDOMImplementation
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToInt


class PageSequenceRenderJob(
    private val width: Int,
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

            val pageHeight = pageDefImage.height.roundToInt()
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG -> {
                    val pageImage = BufferedImage(width, pageHeight, BufferedImage.TYPE_INT_RGB)
                    // Let Batik create the graphics object. It makes sure that SVG content can be painted correctly.
                    val g2 = GraphicsUtil.createGraphics(pageImage)
                    try {
                        g2.setHighQuality()
                        g2.color = background
                        g2.fillRect(0, 0, width, pageHeight)
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
                    g2.svgCanvasSize = Dimension(width, pageHeight)
                    g2.color = background
                    g2.fillRect(0, 0, width, pageHeight)
                    pageDefImage.materialize(g2, drawGuides = false)
                    Files.newBufferedWriter(pageFile).use { writer -> g2.stream(writer, true) }
                }
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }
    }

}


class PDFRenderJob(
    private val width: Int,
    private val background: Color,
    private val pageDefImages: List<DeferredImage>,
    private val file: Path,
) : RenderJob {

    override val destination: String
        get() = file.toString()

    override fun render(progressCallback: (Float) -> Unit) {
        Files.createDirectories(file.parent)

        val pdfDoc = PDDocument()

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pdfPage = PDPage(PDRectangle(width.toFloat(), page.height))
            pdfDoc.addPage(pdfPage)

            val g2 = PdfBoxGraphics2D(pdfDoc, width.toFloat(), page.height)
            g2.color = background
            g2.fillRect(0, 0, width, ceil(page.height).toInt())
            page.materialize(g2, drawGuides = false)
            g2.dispose()

            PDPageContentStream(pdfDoc, pdfPage).use { stream ->
                stream.drawForm(g2.xFormObject)
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }

        pdfDoc.save(file.toFile())
        pdfDoc.close()
    }

}
