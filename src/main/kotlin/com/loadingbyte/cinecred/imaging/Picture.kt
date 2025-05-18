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
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GBRAPF32
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_GBRPF32
import org.slf4j.LoggerFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Text
import org.w3c.dom.traversal.NodeFilter.SHOW_ELEMENT
import org.w3c.dom.traversal.NodeFilter.SHOW_TEXT
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import java.nio.file.Path
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
import kotlin.math.ceil
import kotlin.math.max


/** An abstraction over all the kinds of input images that a [DeferredImage] can work with: Raster, SVG, and PDF. */
sealed interface Picture : AutoCloseable {

    val width: Double
    val height: Double

    fun drawTo(canvas: Canvas, transform: AffineTransform? = null)

    fun prepareAsBitmap(canvas: Canvas, transform: AffineTransform?, cached: Canvas.PreparedBitmap?):
            Canvas.PreparedBitmap?


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
        override fun drawTo(canvas: Canvas, transform: AffineTransform?) {
            bitmap.ifNotClosed { canvas.drawImage(bitmap, transform = transform) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = bitmap.ifNotClosed { canvas.prepareBitmap(bitmap, transform = transform, cached = cached) }

        override fun close() {
            try {
                bitmap.close()
            } catch (_: IllegalStateException) {
                // If the bitmap is used right now, let the GC collect and close it later.
            }
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

        val cropX: Double get() = minBox.x
        val cropY: Double get() = minBox.y
        val cropWidth: Double get() = minBox.width
        val cropHeight: Double get() = minBox.height

        private val minBox: Rectangle2D by lazy {
            val origBox = Rectangle2D.Double(0.0, 0.0, width, height)
            val minBox = empiricallyFindMinBox(origBox)
            // If the reduction is severe, do a second pass to better identify the exact boundaries.
            if (minBox.width >= 0.5 * origBox.width && minBox.height >= 0.5 * origBox.height) minBox else
                empiricallyFindMinBox(minBox.apply { x -= 2.0; y -= 2.0; width += 4.0; height += 4.0 })
        }

        // This method finds the minimal bounding box by just rendering the picture and looking at the pixels.
        private fun empiricallyFindMinBox(curBox: Rectangle2D.Double): Rectangle2D.Double {
            if (curBox.width < 0.001 || curBox.height < 0.001)
                return curBox
            val s = 1000.0 / max(curBox.width, curBox.height)
            val res = Resolution(ceil(curBox.width * s).toInt(), ceil(curBox.height * s).toInt())
            if (res.widthPx <= 0 || res.heightPx <= 0)
                return curBox
            // The sRGB color space is correct for SVGs and a good guess for most PDFs.
            val rep = Canvas.compatibleRepresentation(ColorSpace.SRGB)
            val tr = AffineTransform.getScaleInstance(s, s).apply { translate(-curBox.x, -curBox.y) }
            val px = Bitmap.allocate(Bitmap.Spec(res, rep)).use { bitmap ->
                Canvas.forBitmap(bitmap.zero()).use { canvas -> drawTo(canvas, transform = tr) }
                bitmap.getF(res.widthPx * 4)
            }
            val minX = locateBoundary(px, 0, res.widthPx, 1, res.heightPx, 1, res.widthPx, true)
            // All pixels are transparent, so just abort.
            if (minX.isNaN())
                return curBox
            val minY = locateBoundary(px, 0, res.heightPx, 1, res.widthPx, res.widthPx, 1, true)
            val maxX = locateBoundary(px, res.widthPx - 1, -1, -1, res.heightPx, 1, res.widthPx, false)
            val maxY = locateBoundary(px, res.heightPx - 1, -1, -1, res.widthPx, res.widthPx, 1, false)
            return Rectangle2D.Double(curBox.x + minX / s, curBox.y + minY / s, (maxX - minX) / s, (maxY - minY) / s)
        }

        private fun locateBoundary(
            px: FloatArray, uStart: Int, uEnd: Int, uStep: Int, vEnd: Int, uStride: Int, vStride: Int, invAlpha: Boolean
        ): Double {
            var u = uStart
            while (u != uEnd) {
                var alpha = 0f
                var i = u * uStride * 4 + 3
                for (v in 0..<vEnd) {
                    alpha = max(alpha, px[i])
                    i += vStride * 4
                }
                // We look at the outermost pixel with non-zero alpha, and then place the boundary at a point inside
                // that pixel that depends on the pixel's alpha. In other words, we exploit the antialiasing to locate
                // the boundary at sub-pixel accuracy. Of course, this will give ever so slightly wrong results if the
                // drawn object is partially transparent, but the deviation is only a fraction of a pixel, so it's fine.
                if (alpha != 0f)
                    return u + if (invAlpha) 1.0 - alpha else alpha.toDouble()
                u += uStep
            }
            return Double.NaN
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
        override fun drawTo(canvas: Canvas, transform: AffineTransform?) {
            lock.withLock { if (src.handle.scope().isAlive) canvas.drawSVG(src, transform) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = lock.withLock {
            if (src.handle.scope().isAlive) canvas.prepareSVGAsBitmap(src, transform, cached) else null
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
        override fun drawTo(canvas: Canvas, transform: AffineTransform?) {
            lock.withLock { if (!doc.document.isClosed) canvas.drawPDF(doc, transform) }
        }

        override fun prepareAsBitmap(
            canvas: Canvas, transform: AffineTransform?, cached: Canvas.PreparedBitmap?
        ) = lock.withLock { if (!doc.document.isClosed) canvas.preparePDFAsBitmap(doc, transform, cached) else null }

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
                if (size.width < 0.001 || size.height < 0.001f) {
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
        private const val PDF_EXT = "pdf"
        private val POSTSCRIPT_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "ai", "eps", "ps")

        val EXTS = (RASTER_EXTS + SVG_EXT + PDF_EXT + POSTSCRIPT_EXTS).toSortedSet(String.CASE_INSENSITIVE_ORDER)

        /** @throws Exception */
        fun load(file: Path): Picture {
            val ext = file.extension
            if (file.isRegularFile())
                when {
                    ext in RASTER_EXTS -> return Raster.load(file)
                    ext.equals(SVG_EXT, ignoreCase = true) -> return SVG.load(file)
                    ext.equals(PDF_EXT, ignoreCase = true) -> return PDF.load(file)
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

            val line1 = l10n("imaging.ghostscriptMissing.msg.1", "<code>ai</code>/<code>eps</code>/<code>ps</code>")
            val linkWin = " href=\"https://ghostscript.com/releases/gsdnld.html\""
            val linkMac = " href=\"https://pages.uoregon.edu/koch/\""
            val line2 = when {
                SystemInfo.isWindows -> l10n("imaging.ghostscriptMissing.msg.windows", linkWin, l10nQuoted("Ghostscript AGPL Release"))
                SystemInfo.isMacOS -> l10n("imaging.ghostscriptMissing.msg.macos", linkMac)
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

    }


    class Loader private constructor(val file: Path) : AutoCloseable {

        private val lock = ReentrantLock()
        private var loaded: Any? = null
        private var closed = false

        val isRaster: Boolean
            get() = file.extension in RASTER_EXTS

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
