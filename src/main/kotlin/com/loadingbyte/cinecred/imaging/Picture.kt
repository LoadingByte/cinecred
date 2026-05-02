package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.pdf.PDFDrawer
import com.loadingbyte.cinecred.ui.helper.newLabelEditorPane
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes
import org.bytedeco.ffmpeg.global.avutil.*
import org.slf4j.LoggerFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Text
import org.w3c.dom.traversal.NodeFilter.SHOW_ELEMENT
import org.w3c.dom.traversal.NodeFilter.SHOW_TEXT
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import java.nio.file.Path
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.swing.FocusManager
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.concurrent.withLock
import kotlin.io.path.*
import kotlin.jvm.optionals.getOrElse
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/** An abstraction over all the kinds of input images that a [DeferredImage] can work with: Raster, SVG, and PDF. */
sealed interface Picture : AutoCloseable {

    val width: Double
    val height: Double

    fun drawTo(canvas: Canvas, transform: AffineTransform? = null, clip: List<Shape> = emptyList())

    fun prepareAsBitmap(
        canvas: Canvas, crop: Rectangle2D?, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
    ): Canvas.PreparedBitmap?

    /** A null return value means that the picture is fully blank. */
    fun nonBlankBounds(crop: Rectangle2D? = null, transform: AffineTransform? = null): Rectangle2D?


    class Raster private constructor(
        /**
         * A planar float32 RBG(A) bitmap with full range, linear transfer characteristics, and premultiplied alpha.
         *
         * We have chosen that format as it can directly be understood by zimg, which we use for scaling and other
         * transformations before passing the result to Skia for blitting. We use premultiplied alpha because scaling is
         * performed with premultiplied alpha, so we want the picture to already be in the correct format for that.
         */
        val bitmap: Bitmap
    ) : Picture {

        override val width get() = bitmap.spec.resolution.widthPx.toDouble()
        override val height get() = bitmap.spec.resolution.heightPx.toDouble()

        // If the project that opened the picture has been closed and with it the picture (which is possible because
        // materialization happens in a background thread), just silently skip the operation.
        override fun drawTo(canvas: Canvas, transform: AffineTransform?, clip: List<Shape>) {
            bitmap.ifNotClosed { canvas.drawImage(bitmap, transform = transform, clip = clip) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, crop: Rectangle2D?, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = bitmap.ifNotClosed {
            val crop = (crop as? Rectangle)
                ?: crop?.run { Rectangle(x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt()) }
            canvas.prepareBitmap(bitmap, crop = crop, transform = transform, cached = cached)
        }

        override fun close() {
            try {
                bitmap.close()
            } catch (_: IllegalStateException) {
                // If the bitmap is used right now, let the GC collect and close it later.
            }
        }

        private val nonBlankBoundaryPointsCache =
            Collections.synchronizedMap(object : LinkedHashMap<Rectangle, DoubleArray>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<Rectangle, DoubleArray>) = size > 64
            })

        override fun nonBlankBounds(crop: Rectangle2D?, transform: AffineTransform?): Rectangle2D? {
            val crop = (crop as? Rectangle)
                ?: crop?.run { Rectangle(x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt()) }
                ?: bitmap.spec.resolution.run { Rectangle(0, 0, widthPx, heightPx) }

            if (!bitmap.spec.representation.pixelFormat.hasAlpha)
                return Rectangle(crop.width, crop.height).transformedBy(transform).bounds

            val boundaryPoints = nonBlankBoundaryPointsCache.computeIfAbsent(crop) { findBoundaryPoints(bitmap, crop) }
            return findNonBlankBounds(boundaryPoints, transform)
        }

        companion object {

            fun compatibleRepresentation(primaries: ColorSpace.Primaries, hasAlpha: Boolean) = Bitmap.Representation(
                Bitmap.PixelFormat.of(if (hasAlpha) AV_PIX_FMT_GBRAPF32 else AV_PIX_FMT_GBRPF32),
                ColorSpace.of(primaries, ColorSpace.Transfer.LINEAR),
                if (hasAlpha) Bitmap.Alpha.PREMULTIPLIED else Bitmap.Alpha.OPAQUE
            )

            /** After this constructor returns, [bitmap] may be closed without affecting the new picture object. */
            operator fun invoke(bitmap: Bitmap): Raster {
                val (res, rep, scan) = bitmap.spec
                val cs = requireNotNull(rep.colorSpace) { "Cannot create picture from a bitmap without a color space." }
                val requiredRep = compatibleRepresentation(cs.primaries, rep.pixelFormat.hasAlpha)
                val newBitmap = when {
                    rep != requiredRep ->
                        Bitmap.allocate(Bitmap.Spec(res, requiredRep)).also { BitmapConverter.convert(bitmap, it) }
                    scan != Bitmap.Scan.PROGRESSIVE -> bitmap.reinterpretedView(Bitmap.Spec(res, rep))
                    else -> bitmap.view()
                }
                return Raster(newBitmap)
            }

            /** @throws IOException */
            fun load(bytes: ByteArray): Raster = Raster(BitmapReader.read(bytes, planar = true))
            /** @throws IOException */
            fun load(file: Path): Raster = Raster(BitmapReader.read(file, planar = true))

        }

    }


    sealed class Vector : Picture {

        @Volatile private var roughNonBlankBounds: Optional<Rectangle2D>? = null
        private val nonBlankBoundaryPointsCache = object : LinkedHashMap<Rectangle2D, DoubleArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Rectangle2D, DoubleArray>) = size > 64
        }

        override fun nonBlankBounds(crop: Rectangle2D?, transform: AffineTransform?): Rectangle2D? {
            if (roughNonBlankBounds == null) {
                val s = 1000.0 / max(width, height)
                roughNonBlankBounds = render(s, Rectangle2D.Double(0.0, 0.0, width, height))
                    ?.use { bitmap -> findNonBlankBounds(findBoundaryPoints(bitmap)) }
                    ?.apply { setRect((x - 2) / s, (y - 2) / s, (width + 4) / s, (height + 4) / s) }
                    .let(Optional<*>::ofNullable)
            }
            val roughNonBlankBounds = roughNonBlankBounds!!.getOrElse { return null }

            val crop = crop ?: Rectangle2D.Double(0.0, 0.0, width, height)
            val renderCrop = crop.createIntersection(roughNonBlankBounds)
            val renderScaling = 1000.0 / max(renderCrop.width, renderCrop.height)

            // Note: This cache exactly matches float-based rectangles, but float inaccuracy is not a problem since the
            // crop directly stems from user configuration without any intermediate computational steps.
            val boundaryPoints = nonBlankBoundaryPointsCache.computeIfAbsent(crop) {
                render(renderScaling, renderCrop)?.use(::findBoundaryPoints) ?: DoubleArray(0)
            }
            return findNonBlankBounds(boundaryPoints, (transform?.let(::AffineTransform) ?: AffineTransform()).apply {
                translate(renderCrop.x - crop.x, renderCrop.y - crop.y)
                scale(1 / renderScaling)
            })
        }

        private fun render(scaling: Double, crop: Rectangle2D): Bitmap? {
            val res = Resolution(ceil(crop.width * scaling).toInt(), ceil(crop.height * scaling).toInt())
            if (res.widthPx <= 0 || res.heightPx <= 0)
                return null
            // The sRGB color space is correct for SVGs and a good guess for most PDFs.
            val rep = Canvas.compatibleRepresentation(ColorSpace.SRGB)
            val tr = AffineTransform.getScaleInstance(scaling, scaling).apply { translate(-crop.x, -crop.y) }
            val bitmap = Bitmap.allocate(Bitmap.Spec(res, rep))
            Canvas.forBitmap(bitmap.zero()).use { canvas -> drawTo(canvas, transform = tr) }
            return bitmap
        }

    }


    class SVG private constructor(
        private val doc: Document,
        private val src: Canvas.SourceSVG,
        override val width: Double,
        override val height: Double
    ) : Vector() {

        private val lock = ReentrantLock()

        // If the project that opened the picture has been closed and with it the picture (which is possible because
        // materialization happens in a background thread), just silently skip the operation.
        override fun drawTo(canvas: Canvas, transform: AffineTransform?, clip: List<Shape>) {
            lock.withLock { if (src.handle.scope().isAlive) canvas.drawSVG(src, transform, clip) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, crop: Rectangle2D?, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = lock.withLock {
            if (src.handle.scope().isAlive) canvas.prepareSVGAsBitmap(src, crop, transform, cached) else null
        }

        fun import(importer: Document): Element =
            lock.withLock { importer.importNode(doc.documentElement, true) as Element }

        override fun close() {
            lock.withLock(src::close)
        }

        companion object {

            /** @throws Exception */
            fun load(bytes: ByteArray): SVG = load(ByteArrayInputStream(bytes))

            /** @throws Exception */
            fun load(file: Path): SVG = file.inputStream().use(::load)

            private fun load(stream: InputStream): SVG {
                val doc = DocumentBuilderFactory.newNSInstance().apply {
                    isCoalescing = true
                    // When this wasn't disabled, we've observed huge latencies due to the DTD being downloaded.
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }.newDocumentBuilder().parse(stream)
                cleanDoc(doc)
                return parseDoc(doc)
            }

            private fun cleanDoc(doc: Document) {
                val root = doc.documentElement
                val rootNS = root.namespaceURI
                if (root.localName != "svg")
                    throw IOException("SVG is missing root <svg> element.")
                if (rootNS != null && rootNS != SVG_NS_URI)
                    throw IOException("SVG has wrong namespace '$rootNS'")

                // Clean up the XML:
                //   - Remove all stretches of pure whitespace in between markup.
                //   - Assign the SVG namespace to elements which don't have one.
                //   - Assign the xlink namespace to href attributes to make Skia see them.
                //   - Strip namespace prefixes from all SVG elements and SVG attributes.
                //   - Remove elements and attributes with namespaces other than SVG or xlink.
                //   - Parse all <style> elements and then remove them from the document.
                //     We mainly do this because Skia doesn't understand CSS, but it also makes for nicer SVG exports.
                //   - Remove all <text> elements, because our build of Skia doesn't implement text rendering. However,
                //     SVG text rendering varies a lot across user agents anyway, so SVGs usually use paths instead.
                var usesXlink = false
                val cssRulesets = mutableListOf<CSS.Ruleset>()
                root.forEachNodeInSubtree(SHOW_ELEMENT or SHOW_TEXT) { elem ->
                    if (elem is Text) {
                        if (elem.data.isBlank())
                            elem.parentNode.removeChild(elem)
                        return@forEachNodeInSubtree
                    }
                    val elemNS = elem.namespaceURI
                    if (elemNS != null && elemNS != SVG_NS_URI)
                        elem.parentNode.removeChild(elem)
                    else {
                        if (elemNS == null || elem.prefix != null)
                            doc.renameNode(elem, SVG_NS_URI, elem.localName)
                        val attrs = elem.attributes
                        for (idx in attrs.length - 1 downTo 0) {
                            val attr = attrs.item(idx)
                            val attrNS = attr.namespaceURI
                            when (attrNS) {
                                null -> {}
                                XLINK_NS_URI -> usesXlink = true
                                SVG_NS_URI -> doc.renameNode(attr, null, attr.localName)
                                else -> (elem as Element).removeAttributeNode(attr as Attr)
                            }
                            if (attr.localName == "href" && attrNS == null) {
                                usesXlink = true
                                doc.renameNode(attr, XLINK_NS_URI, "xlink:href")
                            }
                        }
                    }
                    when (elem.localName) {
                        "style" -> {
                            cssRulesets += CSS.parseStylesheet(elem.textContent)
                            elem.parentNode.removeChild(elem)
                        }
                        "text" ->
                            elem.parentNode.removeChild(elem)
                    }
                }

                // Introduce the xlink namespace on the root element, as otherwise, each element would re-introduce it.
                if (usesXlink)
                    root.setAttributeNS(XMLNS_ATTRIBUTE_NS_URI, "xmlns:xlink", XLINK_NS_URI)

                // Apply the parsed styling rules to all matching elements.
                // Also consider inline "style" attributes, and remove them from the document as well.
                root.forEachNodeInSubtree(SHOW_ELEMENT) { elem ->
                    elem as Element
                    val attrCascades = HashMap<String, Cascade>()
                    for (ruleset in cssRulesets) {
                        val maxSpecificity = ruleset.selectors.maxOf { if (it.matches(elem)) it.specificity else -1 }
                        if (maxSpecificity != -1)
                            for (decl in ruleset.declarations)
                                attrCascades.computeIfAbsent(decl.property) { Cascade() }.apply(decl, maxSpecificity)
                    }
                    val styleAttr = elem.getAttribute("style")
                    if (styleAttr.isNotEmpty()) {
                        elem.removeAttribute("style")
                        for (decl in CSS.parseDeclarations(styleAttr))
                            attrCascades.computeIfAbsent(decl.property) { Cascade() }.apply(decl, 1 shl 24)
                    }
                    // Note: As per the SVG specification, CSS properties always override attribute-declared properties.
                    for ((attr, cascade) in attrCascades)
                        elem.setAttribute(attr, cascade.value)
                }
            }

            private class Cascade {
                var value = ""
                private var weight = -1
                fun apply(decl: CSS.Declaration, specificity: Int) {
                    var declWeight = specificity
                    if (decl.important) declWeight = declWeight or 1 shl 25
                    if (weight <= declWeight) {
                        weight = declWeight
                        value = decl.value
                    }
                }
            }

            private fun parseDoc(doc: Document): SVG {
                val writer = StringWriter()
                TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(writer))
                val xml = writer.toString()
                val sourceSVG = try {
                    Canvas.SourceSVG(xml)
                } catch (e: IllegalArgumentException) {
                    throw IOException(e)
                }
                return SVG(doc, sourceSVG, sourceSVG.width, sourceSVG.height)
            }

        }

    }


    class PDF private constructor(
        private val doc: PDDocument,
        override val width: Double,
        override val height: Double
    ) : Vector() {

        private val lock = ReentrantLock()

        // If the project that opened the picture has been closed and with it the picture (which is possible because
        // materialization happens in a background thread), just silently skip the operation.
        override fun drawTo(canvas: Canvas, transform: AffineTransform?, clip: List<Shape>) {
            lock.withLock { if (!doc.document.isClosed) canvas.drawPDF(doc, transform, clip) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, crop: Rectangle2D?, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = lock.withLock {
            if (!doc.document.isClosed) canvas.preparePDFAsBitmap(doc, crop, transform, cached) else null
        }

        fun import(importer: LayerUtility): PDFormXObject = lock.withLock {
            if (!doc.document.isClosed) {
                val page = doc.getPage(0)
                val form = importer.importPageAsForm(doc, page)
                // The matrix set by LayerUtility does wrong scaling if the page is rotated, so we'll set it ourselves.
                form.setMatrix(PDFDrawer.compensateForCropBoxAndRotation(andFlip = false, page))
                // Our implementation of Canvas.drawPDF() composites the picture against a transparent backdrop, and
                // then draws the result to the canvas in one go. To replicate this behavior in exported PDFs, we treat
                // imported PDFs as isolated transparency groups.
                if (form.group == null)
                    form.cosObject.setItem(COSName.GROUP, PDTransparencyGroupAttributes().apply {
                        cosObject.setItem(COSName.TYPE, COSName.GROUP)
                    })
                // The I (isolated) entry is not needed on page groups, but must be explicitly set on nested groups.
                form.group.cosObject.setBoolean(COSName.I, true)
                // If the page doesn't define a page group color space, but it does define an RGB output intent, section
                // 11.4.7 of the PDF specification says that the output intent's color space is used for blending. To
                // preserve that blending color space after the page has been embedded into another PDF, we set it as
                // the form's transparency group color space. Notice that this group color space is inherited to nested
                // groups, just like the output intent.
                // In addition, output intents redefine the Device* color spaces (tested in Acrobat). As a transparency
                // group color space doesn't do that (also tested in Acrobat), we need to supply the output intent color
                // spaces as Default* color spaces. Technically, this needs to be applied to all nested transparency
                // groups as well except those sub-hierarchies used as softmasks, but that's complicated to implement
                // (because we'd need to traverse the nested transparency groups ourselves and also clone their
                // resources dicts manually, since LayerUtility only clones the root page's resources dict), so for now,
                // we only add Default* color spaces for the root page.
                val devCS = PDFDrawer.getDeviceColorSpaces(doc, page)
                val pdCSGray = devCS.deviceGray?.let { makePDICCBased(doc, 1, it.bytes) }
                val pdCSRGB = devCS.deviceRGB?.let { makePDICCBased(doc, 3, it.bytes) }
                val pdCSCMYK = devCS.deviceCMYK?.let { makePDICCBased(doc, 4, it.bytes) }
                val resources = form.resources
                if (pdCSGray != null && !resources.hasColorSpace(COSName.DEFAULT_GRAY))
                    resources.put(COSName.DEFAULT_GRAY, pdCSGray)
                if (pdCSRGB != null && !resources.hasColorSpace(COSName.DEFAULT_RGB))
                    resources.put(COSName.DEFAULT_RGB, pdCSRGB)
                if (pdCSCMYK != null && !resources.hasColorSpace(COSName.DEFAULT_CMYK))
                    resources.put(COSName.DEFAULT_CMYK, pdCSCMYK)
                if (pdCSRGB != null && !form.group.cosObject.containsKey(COSName.CS))
                    form.group.cosObject.setItem(COSName.CS, pdCSRGB)
                form
            } else
                PDFormXObject(importer.document)
        }

        override fun close() {
            try {
                lock.withLock(doc::close)
            } catch (e: IOException) {
                LOGGER.error("Could not close a PDF document.", e)
            }
        }

        companion object {

            /** @throws IOException */
            fun load(bytes: ByteArray): PDF = wrap(org.apache.pdfbox.Loader.loadPDF(bytes))

            /** @throws IOException */
            fun load(file: Path): PDF = wrap(org.apache.pdfbox.Loader.loadPDF(file.toFile()))

            private fun wrap(doc: PDDocument): PDF {
                if (doc.numberOfPages == 0) {
                    doc.close()
                    throw IOException("PDF has 0 pages.")
                }
                val size = PDFDrawer.sizeOfRotatedCropBox(doc.getPage(0))
                if (size.width < 0.001 || size.height < 0.001) {
                    doc.close()
                    throw IOException("PDF's crop box is vanishingly small.")
                }
                return PDF(doc, size.width, size.height)
            }

        }

    }


    companion object {

        private val RASTER_EXTS = ImageIO.getReaderFileSuffixes().asList().toSortedSet(String.CASE_INSENSITIVE_ORDER)
        private const val SVG_EXT = "svg"
        private val PDF_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "pdf", "ai")
        private val POSTSCRIPT_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "eps", "ps")

        val EXTS = (RASTER_EXTS + SVG_EXT + PDF_EXTS + POSTSCRIPT_EXTS).toSortedSet(String.CASE_INSENSITIVE_ORDER)

        /** @throws Exception */
        fun load(file: Path): Picture {
            val ext = file.extension
            if (file.isRegularFile())
                when {
                    ext in RASTER_EXTS -> return Raster.load(file)
                    ext.equals(SVG_EXT, ignoreCase = true) -> return SVG.load(file)
                    ext in PDF_EXTS -> return PDF.load(file)
                    ext in POSTSCRIPT_EXTS -> return loadPostScript(file)
                }
            throw IllegalArgumentException("Not a picture file: $file")
        }

        private val GS_EXECUTABLE: Path? by lazy {
            try {
                if (SystemInfo.isWindows) {
                    for (dir in listOf(Path("C:\\Program Files\\gs"), Path("C:\\Program Files (x86)\\gs")))
                        if (dir.isDirectory())
                            for (dir2 in dir.listDirectoryEntries().sortedDescending()) {
                                val gs = dir.resolve(dir2).resolve("bin\\gswin64c.exe")
                                if (gs.isExecutable())
                                    return@lazy gs
                            }
                } else {
                    val home = Path(System.getProperty("user.home"))
                    val candidateDirs = System.getenv("PATH").split(':').map(Path::of) +
                            listOf("/bin", "/usr/bin", "/usr/local", "/usr/local/bin", "/opt/local/bin").map(Path::of) +
                            listOf(home.resolve("bin"), home.resolve("Applications"))
                    for (dir in candidateDirs) {
                        val gs = dir.resolve("gs")
                        if (gs.isExecutable())
                            return@lazy gs
                    }
                }
            } catch (e: IOException) {
                // This block shouldn't run, but maybe we have overlooked something or the operating system behaves in a
                // strange way. In either case, ignore the exception, as its most likely just another indication that
                // Ghostscript is not installed.
                LOGGER.warn("Encountered unexpected exception while looking for the Ghostscript executable.", e)
            }

            val line1 = l10n("imaging.ghostscriptMissing.msg.1", "<code>eps</code>/<code>ps</code>")
            val line2 = when {
                SystemInfo.isWindows -> l10n(
                    "imaging.ghostscriptMissing.msg.windows",
                    " href=\"https://ghostscript.com/releases/gsdnld.html\"", l10nQuoted("Ghostscript AGPL Release")
                )
                SystemInfo.isMacOS -> l10n(
                    "imaging.ghostscriptMissing.msg.macos",
                    " href=\"https://pages.uoregon.edu/koch/\""
                )
                else -> l10n("imaging.ghostscriptMissing.msg.linux")
            }
            val line3 = l10n("imaging.ghostscriptMissing.msg.3")
            val msg = "<html>$line1<br>$line2<br>$line3</html>"
            val ep = newLabelEditorPane("text/html", msg)
            ep.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED)
                    tryBrowse(e.url.toURI())
            }
            JOptionPane.showMessageDialog(
                FocusManager.getCurrentKeyboardFocusManager().activeWindow,
                ep, l10n("imaging.ghostscriptMissing.title"), JOptionPane.WARNING_MESSAGE
            )
            null
        }

        private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

        private fun loadPostScript(psFile: Path): PDF {
            val gs = GS_EXECUTABLE ?: throw IOException("Ghostscript not found.")
            val tmpFile = createTempFile("cinecred-ps2pdf-", ".pdf")
            try {
                val cmd = listOf(gs.pathString, "-q", "-sDEVICE=pdfwrite", "-o", tmpFile.pathString, psFile.pathString)
                execProcess(cmd, logger = GS_LOGGER)
                // Try for at most ten seconds to access the file generated by Ghostscript. On Windows, the file is
                // sometimes not unlocked right away even though the Ghostscript process has already terminated, so we
                // try multiple times while we wait for Windows to unlock the file. This seems to be the best option.
                var tries = 0
                while (true) {
                    tries++
                    try {
                        // First read the entire file into memory and then pass it to the PDF library.
                        // If we don't do this, the library later complains that the file has been deleted.
                        return PDF.load(tmpFile.readBytes())
                    } catch (e: Exception) {
                        if (tries == 10)
                            throw e
                        Thread.sleep(100)
                    }
                }
            } finally {
                // Presumably because of a Kotlin compiler bug, we have to put the content of this finally block into
                // an extra method. If we don't, the compiler generates branches where the finally block is missing.
                tryDeleteFile(tmpFile)
            }
        }

        private fun tryDeleteFile(file: Path) {
            try {
                file.deleteExisting()
            } catch (e: IOException) {
                // Ignore; don't bother the user when a probably tiny PDF file can't be deleted that will be deleted
                // by the OS the next time the user restarts his system anyways.
                LOGGER.error("Cannot delete temporary PDF file '{}' created by Ghostscript.", file, e)
            }
        }

        private fun findBoundaryPoints(bitmap: Bitmap, crop: Rectangle? = null): DoubleArray {
            val (res, rep) = bitmap.spec
            val crop = crop ?: Rectangle(0, 0, res.widthPx, res.heightPx)
            val cropW = crop.width
            val cropH = crop.height
            // @formatter:off
            val plane: Int; val offset: Int; val stride: Int
            when (rep.pixelFormat.code) {
                AV_PIX_FMT_GBRAPF32 -> { plane = 3; offset = 0; stride = 4 }
                AV_PIX_FMT_RGBAF32 -> { plane = 0; offset = 12; stride = 16; }
                else -> throw IllegalArgumentException("Unsupported pixel format: ${rep.pixelFormat}")
            }
            // @formatter:on
            val ls = bitmap.linesize(plane)
            val seg = bitmap.memorySegment(plane).asSlice(crop.y * ls.toLong() + crop.x * stride + offset)
            val lef = IntArray(cropH).apply { fill(Int.MAX_VALUE) }
            val rig = IntArray(cropH).apply { fill(Int.MIN_VALUE) }
            val top = IntArray(cropW).apply { fill(Int.MAX_VALUE) }
            val bot = IntArray(cropW).apply { fill(Int.MIN_VALUE) }
            for (y in 0..<cropH) {
                var i = y * ls.toLong()
                for (x in 0..<cropW) {
                    if (seg.getFloat(i) /* alpha */ > 0.001f) {
                        lef[y] = min(lef[y], x)
                        rig[y] = max(rig[y], x)
                        top[x] = min(top[x], y)
                        bot[x] = max(bot[x], y)
                    }
                    i += stride
                }
            }
            val bd = DoubleArray(4 * (cropH + cropW))
            var b = 0
            for (y in 0..<cropH) {
                val lx = lef[y]
                if (lx == Int.MAX_VALUE) continue
                val rx = rig[y]
                val sy = y + 0.5
                bd[b++] = lx.toDouble()
                bd[b++] = sy
                bd[b++] = rx + 1.0
                bd[b++] = sy
            }
            for (x in 0..<cropW) {
                val ty = top[x]
                if (ty == Int.MAX_VALUE) continue
                val by = bot[x]
                val sx = x + 0.5
                bd[b++] = sx
                bd[b++] = ty.toDouble()
                bd[b++] = sx
                bd[b++] = by + 1.0
            }
            return bd.copyOf(b)
        }

        private fun findNonBlankBounds(boundaryPoints: DoubleArray, transform: AffineTransform? = null): Rectangle2D? {
            if (boundaryPoints.isEmpty())
                return null

            val transformedBoundaryPoints: DoubleArray
            if (transform == null || transform.isIdentity)
                transformedBoundaryPoints = boundaryPoints
            else {
                transformedBoundaryPoints = DoubleArray(boundaryPoints.size)
                transform.transform(boundaryPoints, 0, transformedBoundaryPoints, 0, boundaryPoints.size / 2)
            }

            var x0 = Double.POSITIVE_INFINITY
            var y0 = Double.POSITIVE_INFINITY
            var x1 = Double.NEGATIVE_INFINITY
            var y1 = Double.NEGATIVE_INFINITY
            var i = 0
            while (i < transformedBoundaryPoints.size) {
                val x = transformedBoundaryPoints[i++]
                val y = transformedBoundaryPoints[i++]
                x0 = min(x0, x)
                y0 = min(y0, y)
                x1 = max(x1, x)
                y1 = max(y1, y)
            }
            return Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0)
        }

    }


    class Loader private constructor(val file: Path) : AutoCloseable {

        private val lock = ReentrantLock()
        private var loaded: Any? = null
        private var closed = false

        /** @throws IllegalStateException */
        val picture: Picture
            get() = lock.withLock {
                check(!closed) { "The picture loader has already been closed." }
                if (loaded == null)
                    try {
                        loaded = load(file)
                    } catch (e: Exception) {
                        LOGGER.error("Picture '{}' cannot be read.", file.name, e)
                        loaded = e
                    }
                when (val l = loaded) {
                    is Exception -> throw IllegalStateException("Picture '${file.name}' cannot be read.", l)
                    else -> l as Picture
                }
            }

        fun loadInBackground() {
            if (lock.withLock { loaded == null })
                GLOBAL_THREAD_POOL.submit { picture }
        }

        override fun close() {
            lock.withLock {
                (loaded as? Picture)?.close()
                loaded = null
                closed = true
            }
        }

        companion object {
            /** If the given [Path] points to a picture file, returns a [Loader] that can be used to lazily load it. */
            fun recognize(file: Path): Loader? =
                if (file.isRegularFile() && file.extension in EXTS) Loader(file) else null
        }

    }

}
