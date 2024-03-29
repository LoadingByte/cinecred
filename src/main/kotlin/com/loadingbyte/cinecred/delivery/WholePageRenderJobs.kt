package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.createDirectoriesSafely
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import org.apache.batik.dom.GenericDOMImplementation
import org.apache.batik.svggen.SVGGeneratorContext
import org.apache.batik.svggen.SVGGraphics2D
import org.apache.commons.io.FileUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.bufferedWriter
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.math.roundToInt


class WholePageSequenceRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val grounding: Color?,
    private val format: Format,
    val dir: Path,
    val filenamePattern: String
) : RenderJob {

    override val prefix: Path
        get() = dir

    override fun render(progressCallback: (Int) -> Unit) {
        if (dir.exists())
            FileUtils.cleanDirectory(dir.toFile())
        dir.createDirectoriesSafely()

        for ((idx, pageDefImage) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = pageDefImage.width.roundToInt()
            val pageHeight = pageDefImage.height.resolve().roundToInt()
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG, Format.TIFF_PACK_BITS, Format.TIFF_DEFLATE -> {
                    val imgType = if (grounding == null) BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_3BYTE_BGR
                    val pageImage = BufferedImage(pageWidth, pageHeight, imgType)

                    if (grounding != null)
                        pageImage.withG2 { g2 ->
                            g2.color = grounding
                            g2.fillRect(0, 0, pageWidth, pageHeight)
                        }
                    pageDefImage.materialize(pageImage, listOf(STATIC, TAPES))

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
                    if (grounding != null) {
                        g2.color = grounding
                        g2.fillRect(0, 0, pageWidth, pageHeight)
                    }
                    pageDefImage.materialize(g2, listOf(STATIC, TAPES))

                    pageFile.bufferedWriter().use { writer -> g2.stream(writer, true) }
                }
            }

            progressCallback(100 * (idx + 1) / pageDefImages.size)
        }
    }


    class Format private constructor(
        fileExt: String,
        labelSuffix: String = "",
        private val l10nNotice: String? = null
    ) : RenderFormat(fileSeq = true, setOf(fileExt), fileExt, supportsAlpha = true) {

        override val label = fileExt.uppercase() + labelSuffix
        override val notice get() = l10nNotice?.let(::l10n)

        companion object {
            val PNG = Format("png")
            val TIFF_PACK_BITS = Format("tiff", " (PackBits)", "delivery.packBits")
            val TIFF_DEFLATE = Format("tiff", " (Deflate)", "delivery.deflate")
            val SVG = Format("svg")
            val ALL = listOf(PNG, TIFF_PACK_BITS, TIFF_DEFLATE, SVG)
        }

    }

}


class WholePagePDFRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val grounding: Color?,
    val file: Path
) : RenderJob {

    override val prefix: Path
        get() = file

    override fun render(progressCallback: (Int) -> Unit) {
        file.parent.createDirectoriesSafely()

        val pdfDoc = PDDocument()

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = page.width.toFloat()
            val pageHeight = page.height.resolve().toFloat()

            val pdfPage = PDPage(PDRectangle(pageWidth, pageHeight))
            pdfDoc.addPage(pdfPage)

            PDPageContentStream(pdfDoc, pdfPage).use { cs ->
                if (grounding != null) {
                    cs.saveGraphicsState()
                    cs.setNonStrokingColor(grounding)
                    cs.addRect(0f, 0f, pageWidth, pageHeight)
                    cs.fill()
                    cs.restoreGraphicsState()
                }
                page.materialize(pdfDoc, pdfPage, cs, listOf(STATIC, TAPES))
            }

            progressCallback(100 * (idx + 1) / pageDefImages.size)
        }

        pdfDoc.save(file.toFile())
        pdfDoc.close()
    }


    companion object {
        val FORMAT = object : RenderFormat(fileSeq = false, setOf("pdf"), "pdf", supportsAlpha = true) {
            override val label get() = "PDF"
            override val notice get() = l10n("delivery.reducedQuality")
        }
    }

}
