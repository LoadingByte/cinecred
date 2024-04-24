package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.Graphics2DProxy
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.ui.helper.newLabelEditorPane
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import mkl.testarea.pdfbox2.extract.BoundingBoxFinder
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BaseScriptingEnvironment
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.SVGUtilities
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.LayerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.RenderDestination
import org.apache.poi.xslf.draw.SVGUserAgent
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.svg.SVGDocument
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_3BYTE_BGR
import java.awt.image.BufferedImage.TYPE_4BYTE_ABGR
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.swing.FocusManager
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import kotlin.concurrent.withLock
import kotlin.io.path.*
import kotlin.math.roundToInt


/** An abstraction over all the kinds of input images that a [DeferredImage] can work with: Raster, SVG, and PDF. */
sealed interface Picture : Closeable {

    val width: Double
    val height: Double
    override fun close() {}


    class Raster(img: BufferedImage) : Picture {

        // Conform non-standard raster images to the 8-bit (A)BGR pixel format and the sRGB color space. This needs to
        // be done at some point because some of our code expects (A)BGR, and we're compositing in sRGB.
        val img =
            if (img.colorModel.colorSpace.isCS_sRGB && img.type.let { it == TYPE_3BYTE_BGR || it == TYPE_4BYTE_ABGR })
                img
            else
                BufferedImage(img.width, img.height, if (img.colorModel.hasAlpha()) TYPE_4BYTE_ABGR else TYPE_3BYTE_BGR)
                    .withG2 { g2 -> g2.drawImage(img, 0, 0, null) }

        override val width get() = img.width.toDouble()
        override val height get() = img.height.toDouble()

    }


    sealed interface Vector : Picture {

        val cropX: Double
        val cropY: Double
        val cropWidth: Double
        val cropHeight: Double
        fun drawTo(g2: Graphics2D)

    }


    class SVG(
        private val doc: SVGDocument,
        private val gvtRoot: GraphicsNode,
        override val width: Double,
        override val height: Double,
    ) : Vector {

        private val lock = ReentrantLock()

        override val cropX get() = gvtRoot.bounds.x
        override val cropY get() = gvtRoot.bounds.y
        override val cropWidth get() = gvtRoot.bounds.width
        override val cropHeight get() = gvtRoot.bounds.height

        override fun drawTo(g2: Graphics2D) {
            // Batik might not be thread-safe, even though we haven't tested that.
            lock.withLock { gvtRoot.paint(g2) }
        }

        fun import(importer: Document): Element = importer.importNode(doc.rootElement, true) as Element

    }


    class PDF(private val doc: PDDocument) : Vector {

        private val lock = ReentrantLock()

        private val minBox: Rectangle2D by lazy {
            safely {
                val raw = BoundingBoxFinder(doc.pages[0]).apply { processPage(doc.pages[0]) }.boundingBox
                // The raw bounding box y coordinate is actually relative to the bottom of the crop box, so we need
                // to convert it such that it is relative to the top because the rest of our program works like that.
                Rectangle2D.Double(raw.x, height - raw.y - raw.height, raw.width, raw.height)
            }
            // If the document has already been closed, this picture is worthless, so just return a dummy rectangle.
                ?: Rectangle2D.Double(0.0, 0.0, 1.0, 1.0)
        }

        override val width get() = doc.pages[0].cropBox.width.toDouble()
        override val height get() = doc.pages[0].cropBox.height.toDouble()
        override val cropX get() = minBox.x
        override val cropY get() = minBox.y
        override val cropWidth get() = minBox.width
        override val cropHeight get() = minBox.height

        override fun drawTo(g2: Graphics2D) {
            // Note: We have to create a new Graphics2D object here because PDFBox modifies it heavily
            // and sometimes even makes it totally unusable.
            @Suppress("NAME_SHADOWING")
            g2.withNewG2 { g2 ->
                // PDFBox calls clearRect() before starting to draw. This sometimes results in a black
                // box even if g2.background is set to a transparent color. The most thorough fix is
                // to just block all calls to clearRect().
                val g2Proxy = object : Graphics2DProxy(g2) {
                    override fun clearRect(x: Int, y: Int, width: Int, height: Int) {
                        // Block call.
                    }
                }
                safely { PDFRenderer(doc).renderPageToGraphics(0, g2Proxy, 1f, 1f, RenderDestination.EXPORT) }
            }
        }

        fun import(importer: LayerUtility): PDFormXObject? = safely { importer.importPageAsForm(doc, 0) }

        override fun close() {
            safely(doc::close)
        }

        private inline fun <R> safely(block: () -> R): R? {
            // PDFBox is definitely not thread-safe. Also, we check whether the document has been closed prior
            // to using it and do not want it to close later while we are using it. Since the closing operation
            // in the Picture class is also synchronized, we avoid such a situation.
            return lock.withLock {
                // If the project that opened this document has been closed and with it the document (which is
                // possible because materialization might happen in a background thread), do not render the
                // PDF to avoid provoking an exception.
                if (!doc.document.isClosed) block() else null
            }
        }

    }


    sealed interface Embedded {

        val picture: Picture
        val width: Double
        val height: Double
        fun withWidthPreservingAspectRatio(width: Double): Embedded
        fun withHeightPreservingAspectRatio(height: Double): Embedded

        companion object {
            operator fun invoke(picture: Picture): Embedded = when (picture) {
                is Picture.Raster -> Raster(picture)
                is Picture.Vector -> Vector(picture)
            }
        }

        class Raster private constructor(
            override val picture: Picture.Raster,
            val resolution: Resolution
        ) : Embedded {

            constructor(picture: Picture.Raster) : this(picture, Resolution(picture.img.width, picture.img.height))

            init {
                require(resolution.run { widthPx > 0 && heightPx > 0 })
            }

            val scalingX: Double get() = width / picture.width
            val scalingY: Double get() = height / picture.height

            override val width = resolution.widthPx.toDouble()
            override val height = resolution.heightPx.toDouble()

            override fun withWidthPreservingAspectRatio(width: Double) =
                withWidthPreservingAspectRatio(width.roundToInt())

            override fun withHeightPreservingAspectRatio(height: Double) =
                withHeightPreservingAspectRatio(height.roundToInt())

            fun withWidthPreservingAspectRatio(width: Int) =
                Raster(picture, Resolution(width, roundingDiv(picture.img.height * width, picture.img.width)))

            fun withHeightPreservingAspectRatio(height: Int) =
                Raster(picture, Resolution(roundingDiv(picture.img.width * height, picture.img.height), height))

            /** In contrast to the other sizing methods, this one doesn't preserve the aspect ratio. */
            fun withForcedResolution(resolution: Resolution) = Raster(picture, resolution)

        }

        class Vector private constructor(
            override val picture: Picture.Vector,
            private val targetDim: Int,
            private val targetSize: Double,
            val isCropped: Boolean
        ) : Embedded {

            constructor(picture: Picture.Vector) : this(picture, 0, 0.0, false)

            init {
                require(targetDim == 0 || targetSize > 0.0)
            }

            val scaling: Double
                get() = when (targetDim) {
                    1 -> targetSize / if (isCropped) picture.cropWidth else picture.width
                    2 -> targetSize / if (isCropped) picture.cropHeight else picture.height
                    else -> 1.0
                }

            override val width get() = scaling * if (isCropped) picture.cropWidth else picture.width
            override val height get() = scaling * if (isCropped) picture.cropHeight else picture.height

            override fun withWidthPreservingAspectRatio(width: Double) = Vector(picture, 1, width, isCropped)
            override fun withHeightPreservingAspectRatio(height: Double) = Vector(picture, 2, height, isCropped)
            fun cropped() = Vector(picture, targetDim, targetSize, isCropped = true)

        }

    }


    companion object {

        private val RASTER_EXTS = ImageIO.getReaderFileSuffixes().asList().toSortedSet(String.CASE_INSENSITIVE_ORDER)
        private const val SVG_EXT = "svg"
        private const val PDF_EXT = "pdf"
        private val POSTSCRIPT_EXTS = sortedSetOf(String.CASE_INSENSITIVE_ORDER, "ai", "eps", "ps")

        val EXTS = (RASTER_EXTS + SVG_EXT + PDF_EXT + POSTSCRIPT_EXTS).toSortedSet(String.CASE_INSENSITIVE_ORDER)

        /** @throws Exception */
        fun read(file: Path): Picture {
            val ext = file.extension
            if (file.isRegularFile())
                when {
                    ext in RASTER_EXTS -> return loadRaster(file)
                    ext.equals(SVG_EXT, ignoreCase = true) -> return loadSVG(file)
                    ext.equals(PDF_EXT, ignoreCase = true) -> return loadPDF(file)
                    ext in POSTSCRIPT_EXTS -> return loadPostScript(file)
                }
            throw IllegalArgumentException("Not a picture file: $file")
        }

        private fun loadRaster(rasterFile: Path): Raster =
            Raster(ImageIO.read(rasterFile.toFile()))

        // Look here for references:
        // https://github.com/apache/xmlgraphics-batik/blob/trunk/batik-transcoder/src/main/java/org/apache/batik/transcoder/SVGAbstractTranscoder.java
        private fun loadSVG(svgFile: Path): SVG {
            val factory = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
            val doc = svgFile.bufferedReader().use {
                factory.createDocument(svgFile.toUri().toString(), it) as SVGOMDocument
            }
            val docRoot = doc.rootElement
            // By default, Batik assumes that the viewport has size (1,1). If the SVG doesn't have the width and height
            // attributes, Batik fits the SVG into the viewport, and as such, the SVG basically vanishes. Of course, the
            // user can still get the SVG to show by scaling it back up in the program, but we'd like a better default.
            // So we read the viewBox attribute of the SVG (which must exist when width/height are missing) and set the
            // viewport size to the viewBox size. Apache POI has already implemented this, so we just steal their class.
            val userAgent = SVGUserAgent().apply { initViewbox(doc) }
            val ctx = when {
                doc.isSVG12 -> SVG12BridgeContext(userAgent)
                else -> BridgeContext(userAgent)
            }
            ctx.isDynamic = true

            val gvtRoot = GVTBuilder().build(ctx, doc)

            val width = ctx.documentSize.width
            val height = ctx.documentSize.height

            // Dispatch an 'onload' event if needed.
            if (ctx.isDynamic) {
                val se = BaseScriptingEnvironment(ctx)
                se.loadScripts()
                se.dispatchSVGLoadEvent()
                if (ctx.isSVG12)
                    ctx.animationEngine.currentTime = SVGUtilities.convertSnapshotTime(docRoot, null)
            }

            return SVG(doc, gvtRoot, width, height)
        }

        private fun loadPDF(pdfFile: Path): PDF {
            val doc = Loader.loadPDF(pdfFile.toFile())
            if (doc.numberOfPages != 0)
                return PDF(doc)
            else
                throw IOException()
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

            val linkWin = " href=\"https://ghostscript.com/releases/gsdnld.html\""
            val linkMac = " href=\"https://pages.uoregon.edu/koch/\""
            val osSpecific = when {
                SystemInfo.isWindows -> l10n("imaging.ghostscriptMissing.msg.windows", linkWin)
                SystemInfo.isMacOS -> l10n("imaging.ghostscriptMissing.msg.macos", linkMac)
                else -> l10n("imaging.ghostscriptMissing.msg.linux")
            }
            val msg = "<html>" + l10n("imaging.ghostscriptMissing.msg", osSpecific).replace("\n", "<br>") + "</html>"
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

        private val GS_STREAM_GOBBLER_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GhostscriptStreamGobbler").apply { isDaemon = true }
        }
        private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

        private fun loadPostScript(psFile: Path): PDF {
            val gs = GS_EXECUTABLE ?: throw IOException("Ghostscript not found.")
            val tmpFile = createTempFile("cinecred-ps2pdf-", ".pdf")
            try {
                val cmd = arrayOf(gs.pathString, "-sDEVICE=pdfwrite", "-o", tmpFile.pathString, psFile.pathString)
                val process = Runtime.getRuntime().exec(cmd)
                GS_STREAM_GOBBLER_EXECUTOR.submit(throwableAwareTask {
                    process.inputReader().lines().forEach { GS_LOGGER.info(it) }
                    process.errorReader().lines().forEach { GS_LOGGER.error(it) }
                })
                val exitCode = process.waitFor()
                if (exitCode != 0)
                    throw RuntimeException("Ghostscript terminated with error exit code $exitCode.")
                // Try for at most ten seconds to access the file generated by Ghostscript. On Windows, the file is
                // sometimes not unlocked right away even though the Ghostscript process has already terminated, so we
                // try multiple times while we wait for Windows to unlock the file. This seems to be the best option.
                var tries = 0
                while (true) {
                    tries++
                    try {
                        return loadPDF(tmpFile)
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

}
