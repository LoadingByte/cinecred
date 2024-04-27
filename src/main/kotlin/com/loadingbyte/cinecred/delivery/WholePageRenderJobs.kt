package com.loadingbyte.cinecred.delivery

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.*
import com.loadingbyte.cinecred.imaging.Bitmap.PixelFormat.Family.RGB
import com.loadingbyte.cinecred.imaging.BitmapWriter.TIFF.Compression.DEFLATE
import com.loadingbyte.cinecred.imaging.BitmapWriter.TIFF.Compression.PACK_BITS
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.STATIC
import com.loadingbyte.cinecred.imaging.DeferredImage.Companion.TAPES
import com.loadingbyte.cinecred.imaging.Y.Companion.toY
import org.apache.commons.io.FileUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import java.awt.Color
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.*
import javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
import javax.xml.XMLConstants.XML_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.io.path.bufferedWriter
import kotlin.io.path.exists
import kotlin.math.roundToInt


class WholePageSequenceRenderJob(
    private val pageDefImages: List<DeferredImage>,
    private val grounding: Color?,
    private val locale: Locale,
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

        val bitmapWriter = when (format) {
            Format.PNG -> BitmapWriter.PNG(RGB, grounding == null, ColorSpace.SRGB)
            Format.TIFF_PACK_BITS -> BitmapWriter.TIFF(RGB, grounding == null, ColorSpace.SRGB, compression = PACK_BITS)
            Format.TIFF_DEFLATE -> BitmapWriter.TIFF(RGB, grounding == null, ColorSpace.SRGB, compression = DEFLATE)
            else -> null
        }

        for ((idx, pageDefImage) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pageWidth = pageDefImage.width.roundToInt()
            val pageHeight = pageDefImage.height.resolve().roundToInt()
            val pageFile = dir.resolve(filenamePattern.format(idx + 1))

            when (format) {
                Format.PNG, Format.TIFF_PACK_BITS, Format.TIFF_DEFLATE -> {
                    val res = Resolution(pageWidth, pageHeight)
                    val rep = Canvas.compatibleRepresentation(ColorSpace.BLENDING)
                    Bitmap.allocate(Bitmap.Spec(res, rep)).use { bitmap ->
                        Canvas.forBitmap(bitmap).use { canvas ->
                            if (grounding == null) bitmap.zero() else canvas.fill(Canvas.Shader.Solid(grounding))
                            pageDefImage.materialize(canvas, cache = null, layers = listOf(STATIC, TAPES))
                        }
                        bitmapWriter!!.convertAndWrite(bitmap, pageFile, promiseOpaque = grounding != null)
                    }
                }
                Format.SVG -> {
                    val doc = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().domImplementation
                        .createDocument(SVG_NS_URI, "svg", null)
                    val svg = doc.documentElement

                    svg.setAttributeNS(XMLNS_ATTRIBUTE_NS_URI, "xmlns:xlink", XLINK_NS_URI)
                    svg.setAttributeNS(XML_NS_URI, "xml:lang", locale.toLanguageTag())
                    svg.setAttribute("width", pageWidth.toString())
                    svg.setAttribute("height", pageHeight.toString())
                    svg.setAttribute("viewBox", "0 0 $pageWidth $pageHeight")
                    if (grounding != null)
                        svg.appendChild(doc.createElementNS(SVG_NS_URI, "rect").apply {
                            setAttribute("width", pageWidth.toString())
                            setAttribute("height", pageHeight.toString())
                            setAttribute("fill", grounding.toHex24())
                        })
                    pageDefImage.materialize(svg, listOf(STATIC, TAPES))

                    pageFile.bufferedWriter().use { writer ->
                        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                        // Note: We have verified that the XML writer used a bit further down writes system-dependent
                        // line breaks, so we do too.
                        writer.newLine()
                        writer.write("<!-- Created with Cinecred $VERSION -->")
                        // The XML writer sadly doesn't put a newline after a comment placed before the root element.
                        // The simplest solution is to just write this comment ourselves.
                        writer.newLine()
                        TransformerFactory.newInstance().newTransformer().apply {
                            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                            setOutputProperty(OutputKeys.INDENT, "yes")
                            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                        }.transform(DOMSource(doc), StreamResult(writer))
                    }
                }
            }

            progressCallback(MAX_RENDER_PROGRESS * (idx + 1) / pageDefImages.size)
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
    private val locale: Locale,
    val file: Path
) : RenderJob {

    override val prefix: Path
        get() = file

    override fun render(progressCallback: (Int) -> Unit) {
        file.parent.createDirectoriesSafely()

        val pdfDoc = PDDocument()

        // We're embedding OpenType fonts, which requires PDF 1.6.
        pdfDoc.version = 1.6f
        pdfDoc.documentInformation.apply {
            creator = "Cinecred $VERSION"
            creationDate = Calendar.getInstance()
        }
        pdfDoc.documentCatalog.language = locale.toLanguageTag()

        // Add an output intent with the blending color space.
        val iccBytes = ICCProfile.of(ColorSpace.BLENDING).bytes
        pdfDoc.documentCatalog.addOutputIntent(PDOutputIntent(pdfDoc, ByteArrayInputStream(iccBytes)).apply {
            val id = if (ColorSpace.BLENDING == ColorSpace.SRGB) "sRGB IEC61966-2.1" else "Custom"
            outputConditionIdentifier = id
            info = id
        })

        for ((idx, page) in pageDefImages.withIndex()) {
            if (Thread.interrupted()) return

            val pdfPage = PDPage(PDRectangle(page.width.toFloat(), page.height.resolve().toFloat()))
            pdfDoc.addPage(pdfPage)

            PDPageContentStream(pdfDoc, pdfPage).use { cs ->
                // Let the backend draw the grounding so that it takes care of all the color space stuff.
                DeferredImage(page.width, page.height).apply {
                    if (grounding != null)
                        drawRect(grounding, 0.0, 0.0.toY(), page.width, page.height, fill = true)
                    drawDeferredImage(page, 0.0, 0.0.toY())
                }.materialize(pdfDoc, pdfPage, cs, ColorSpace.BLENDING, listOf(STATIC, TAPES))
            }

            progressCallback(MAX_RENDER_PROGRESS * (idx + 1) / pageDefImages.size)
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
