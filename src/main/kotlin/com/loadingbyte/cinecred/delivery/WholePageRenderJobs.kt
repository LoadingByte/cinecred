package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D
import org.apache.batik.dom.GenericDOMImplementation
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


class WholePageSequenceRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val width: Int,
    private val background: Color?,
    private val format: Format,
    val dir: Path,
    val filenamePattern: String
) : RenderJob {

    override fun generatesFile(file: Path) = file.startsWith(dir)

    override fun render(progressCallback: (Float) -> Unit) {
        Files.createDirectories(dir)

        for ((idx, pageDefImage) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageHeight = pageDefImage.height.roundToInt()
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG, Format.TIFF -> {
                    val imageType = if (background != null) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
                    val pageImage = BufferedImage(width, pageHeight, imageType)

                    pageImage.withG2 { g2 ->
                        g2.setHighQuality()
                        if (background != null) {
                            g2.color = background
                            g2.fillRect(0, 0, width, pageHeight)
                        }
                        pageDefImage.materialize(g2, drawGuides = false)
                    }

                    ImageIO.write(pageImage, if (format == Format.PNG) "png" else "tiff", pageFile.toFile())
                }
                Format.SVG -> {
                    val doc = GenericDOMImplementation.getDOMImplementation()
                        .createDocument("http://www.w3.org/2000/svg", "svg", null)
                    val ctx = SVGGeneratorContext.createDefault(doc)
                    ctx.comment = null

                    val g2 = SVGGraphics2D(ctx, true)
                    g2.svgCanvasSize = Dimension(width, pageHeight)
                    if (background != null) {
                        g2.color = background
                        g2.fillRect(0, 0, width, pageHeight)
                    }
                    pageDefImage.materialize(g2, drawGuides = false)

                    Files.newBufferedWriter(pageFile).use { writer -> g2.stream(writer, true) }
                }
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }
    }


    class Format private constructor(label: String, fileExt: String) : RenderFormat(label, listOf(fileExt)) {
        companion object {
            val PNG = Format("PNG", "png")
            val TIFF = Format("TIFF", "tiff")
            val SVG = Format("SVG", "svg")
            val ALL = listOf(PNG, TIFF, SVG)
        }
    }

}


class WholePagePDFRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val width: Int,
    private val background: Color?,
    val file: Path,
) : RenderJob {

    override fun generatesFile(file: Path) = file == this.file

    override fun render(progressCallback: (Float) -> Unit) {
        Files.createDirectories(file.parent)

        val pdfDoc = PDDocument()

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pdfPage = PDPage(PDRectangle(width.toFloat(), page.height))
            pdfDoc.addPage(pdfPage)

            val g2 = PdfBoxGraphics2D(pdfDoc, width.toFloat(), page.height)
            if (background != null) {
                g2.color = background
                g2.fillRect(0, 0, width, ceil(page.height).toInt())
            }
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


    companion object {
        val FORMAT = RenderFormat("PDF", listOf("pdf"))
    }

}
