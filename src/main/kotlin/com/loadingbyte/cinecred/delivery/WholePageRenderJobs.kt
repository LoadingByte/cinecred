package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.drawer.DeferredImage
import org.jfree.pdf.PDFDocument
import org.jfree.svg.SVGGraphics2D
import java.awt.Color
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
                    val g2 = pageImage.createGraphics()
                    g2.setHighQuality()
                    g2.color = background
                    g2.fillRect(0, 0, pageWidth, pageHeight)
                    pageDefImage.materialize(g2, drawGuides = false, DeferredImage.TextMode.PATH)
                    ImageIO.write(pageImage, "png", pageFile.toFile())
                }
                Format.SVG -> {
                    val g2 = SVGGraphics2D(pageWidth, pageHeight)
                    g2.color = background
                    g2.fillRect(0, 0, pageWidth, pageHeight)
                    pageDefImage.materialize(g2, drawGuides = false, DeferredImage.TextMode.PATH)
                    Files.writeString(pageFile, g2.svgDocument)
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
            page.materialize(g2, drawGuides = false, DeferredImage.TextMode.PATH_WITH_INVISIBLE_TEXT)

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }

        pdfDoc.writeToFile(file.toFile())
    }

}
