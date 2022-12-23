package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.DeferredImage.Companion.BACKGROUND
import com.loadingbyte.cinecred.common.DeferredImage.Companion.FOREGROUND
import com.loadingbyte.cinecred.common.DeferredImage.Companion.GROUNDING
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.setHighQuality
import com.loadingbyte.cinecred.common.withG2
import org.apache.batik.dom.GenericDOMImplementation
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D
import org.apache.commons.io.FileUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.math.roundToInt


class WholePageSequenceRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val transparentGrounding: Boolean,
    private val format: Format,
    val dir: Path,
    val filenamePattern: String
) : RenderJob {

    override fun generatesFile(file: Path) = file.startsWith(dir)

    override fun render(progressCallback: (Float) -> Unit) {
        if (dir.exists())
            FileUtils.cleanDirectory(dir.toFile())
        dir.createDirectories()

        val layers =
            if (transparentGrounding) listOf(BACKGROUND, FOREGROUND) else listOf(GROUNDING, BACKGROUND, FOREGROUND)

        for ((idx, pageDefImage) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = pageDefImage.width.roundToInt()
            val pageHeight = pageDefImage.height.resolve().roundToInt()
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG, Format.TIFF_PACK_BITS, Format.TIFF_DEFLATE -> {
                    val imageType =
                        if (transparentGrounding) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
                    val pageImage = BufferedImage(pageWidth, pageHeight, imageType)

                    pageImage.withG2 { g2 ->
                        g2.setHighQuality()
                        pageDefImage.materialize(g2, layers)
                    }

                    // Note: We do not use ImageIO.write() for two reasons:
                    //   - We need to support TIFF compression.
                    //   - ImageIO.write() eventually uses the com.sun class FileImageOutputStreamSpi,
                    //     which swallows IO exceptions. Eventually, another exception for the same error
                    //     will be thrown, but the error message is lost.
                    val writer = ImageIO.getImageWritersBySuffix(format.fileExts.single()).next()
                    try {
                        pageFile.deleteIfExists()
                        FileImageOutputStream(pageFile.toFile()).use { stream ->
                            writer.output = stream
                            val param = writer.defaultWriteParam
                            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                            when (format) {
                                Format.TIFF_PACK_BITS -> param.compressionType = "PackBits"
                                Format.TIFF_DEFLATE -> param.compressionType = "Deflate"
                            }
                            writer.write(null, IIOImage(pageImage, null, null), param)
                        }
                    } finally {
                        writer.dispose()
                    }
                }
                Format.SVG -> {
                    val doc = GenericDOMImplementation.getDOMImplementation()
                        .createDocument("http://www.w3.org/2000/svg", "svg", null)
                    val ctx = SVGGeneratorContext.createDefault(doc)
                    ctx.comment = null

                    val g2 = SVGGraphics2D(ctx, true)
                    g2.svgCanvasSize = Dimension(pageWidth, pageHeight)
                    pageDefImage.materialize(g2, layers)

                    pageFile.bufferedWriter().use { writer -> g2.stream(writer, true) }
                }
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }
    }


    class Format private constructor(label: String, fileExt: String) :
        RenderFormat(label, fileSeq = true, setOf(fileExt), fileExt, supportsAlpha = true) {
        companion object {
            val PNG = Format("PNG", "png")
            val TIFF_PACK_BITS = Format(l10n("delivery.packBits", "TIFF"), "tiff")
            val TIFF_DEFLATE = Format(l10n("delivery.deflate", "TIFF"), "tiff")
            val SVG = Format("SVG", "svg")
            val ALL = listOf(PNG, TIFF_PACK_BITS, TIFF_DEFLATE, SVG)
        }
    }

}


class WholePagePDFRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val transparentGrounding: Boolean,
    val file: Path,
) : RenderJob {

    override fun generatesFile(file: Path) = file == this.file

    override fun render(progressCallback: (Float) -> Unit) {
        file.parent.createDirectories()

        val layers =
            if (transparentGrounding) listOf(BACKGROUND, FOREGROUND) else listOf(GROUNDING, BACKGROUND, FOREGROUND)

        val pdfDoc = PDDocument()

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = page.width.toFloat()
            val pageHeight = page.height.resolve().toFloat()

            val pdfPage = PDPage(PDRectangle(pageWidth, pageHeight))
            pdfDoc.addPage(pdfPage)

            PDPageContentStream(pdfDoc, pdfPage).use { stream ->
                page.materialize(pdfDoc, stream, pageHeight, layers)
            }

            progressCallback((idx + 1).toFloat() / pageDefImages.size)
        }

        pdfDoc.save(file.toFile())
        pdfDoc.close()
    }


    companion object {
        val FORMAT = RenderFormat("PDF", fileSeq = false, setOf("pdf"), "pdf", supportsAlpha = true)
    }

}
