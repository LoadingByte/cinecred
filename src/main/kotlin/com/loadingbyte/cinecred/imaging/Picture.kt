package com.loadingbyte.cinecred.imaging

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
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.poi.xslf.draw.SVGUserAgent
import org.slf4j.LoggerFactory
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_3BYTE_BGR
import java.awt.image.BufferedImage.TYPE_4BYTE_ABGR
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.FocusManager
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import kotlin.io.path.*
import kotlin.math.roundToInt


/** An abstraction over all the kinds of input images that a [DeferredImage] can work with: Raster, SVG, and PDF. */
sealed interface Picture {

    val width: Double
    val height: Double
    fun withWidth(width: Double): Picture
    fun withHeight(height: Double): Picture
    fun dispose() {}


    class Raster private constructor(img: BufferedImage, val resolution: Resolution) : Picture {

        constructor(img: BufferedImage) : this(img, Resolution(img.width, img.height))

        // Conform non-standard raster images to the 8-bit (A)BGR pixel format and the sRGB color space. This needs to
        // be done at some point because some of our code expects (A)BGR, and we're compositing in sRGB.
        val img =
            if (img.colorModel.colorSpace.isCS_sRGB && img.type.let { it == TYPE_3BYTE_BGR || it == TYPE_4BYTE_ABGR })
                img
            else
                BufferedImage(img.width, img.height, if (img.colorModel.hasAlpha()) TYPE_4BYTE_ABGR else TYPE_3BYTE_BGR)
                    .withG2 { g2 -> g2.drawImage(img, 0, 0, null) }

        override val width get() = resolution.widthPx.toDouble()
        override val height get() = resolution.heightPx.toDouble()

        fun withWidth(width: Int) = Raster(img, Resolution(width, roundingDiv(img.height * width, img.width)))
        fun withHeight(height: Int) = Raster(img, Resolution(roundingDiv(img.width * height, img.height), height))
        override fun withWidth(width: Double) = withWidth(width.roundToInt())
        override fun withHeight(height: Double) = withHeight(height.roundToInt())

        /** In contrast to the other sizing methods, this one doesn't preserve the aspect ratio. */
        fun withForcedResolution(resolution: Resolution) = Raster(img, resolution)

    }


    sealed class Vector(
        protected val targetDim: Int, protected val targetSize: Double, val isCropped: Boolean
    ) : Picture {

        protected abstract val bw: Double
        protected abstract val bh: Double

        val scaling: Double get() = if (targetDim == 0) 1.0 else targetSize / if (targetDim == 1) bw else bh
        override val width get() = scaling * bw
        override val height get() = scaling * bh

        abstract override fun withWidth(width: Double): Vector
        abstract override fun withHeight(height: Double): Vector
        abstract fun cropped(): Vector

    }


    class SVG private constructor(
        val gvtRoot: GraphicsNode, private val docWidth: Double, private val docHeight: Double,
        targetDim: Int, targetSize: Double, isCropped: Boolean
    ) : Vector(targetDim, targetSize, isCropped) {

        constructor(gvtRoot: GraphicsNode, docWidth: Double, docHeight: Double) :
                this(gvtRoot, docWidth, docHeight, 0, 0.0, false)

        override val bw get() = if (isCropped) gvtRoot.bounds.width else docWidth
        override val bh get() = if (isCropped) gvtRoot.bounds.height else docHeight

        override fun withWidth(width: Double) = SVG(gvtRoot, docWidth, docHeight, 1, width, isCropped)
        override fun withHeight(height: Double) = SVG(gvtRoot, docWidth, docHeight, 2, height, isCropped)
        override fun cropped() = SVG(gvtRoot, docWidth, docHeight, targetDim, targetSize, isCropped = true)

    }


    class PDF private constructor(
        private val aDoc: AugmentedDoc,
        targetDim: Int, targetSize: Double, isCropped: Boolean
    ) : Vector(targetDim, targetSize, isCropped) {

        constructor(doc: PDDocument) : this(AugmentedDoc(doc), 0, 0.0, false)

        val doc get() = aDoc.doc
        val minBox get() = aDoc.minBox

        override val bw get() = if (isCropped) minBox.width else doc.pages[0].cropBox.width.toDouble()
        override val bh get() = if (isCropped) minBox.height else doc.pages[0].cropBox.height.toDouble()

        override fun withWidth(width: Double) = PDF(aDoc, 1, width, isCropped)
        override fun withHeight(height: Double) = PDF(aDoc, 2, height, isCropped)
        override fun cropped() = PDF(aDoc, targetDim, targetSize, isCropped = true)

        override fun dispose() {
            // Synchronize the closing operation so that other threads can safely check whether a document is closed
            // and then use it in their own synchronized block.
            synchronized(doc) {
                doc.close()
            }
        }

        private class AugmentedDoc(val doc: PDDocument) {
            val minBox: Rectangle2D by lazy(::computeMinBox)
            private fun computeMinBox() = synchronized(doc) {
                // If the document has already been closed, the responsible project has just been closed. In that case,
                // just return a dummy rectangle here to let the project closing finish regularly.
                if (doc.document.isClosed)
                    return Rectangle2D.Double(0.0, 0.0, 1.0, 1.0)
                val raw = BoundingBoxFinder(doc.pages[0]).apply { processPage(doc.pages[0]) }.boundingBox
                // The raw bounding box y coordinate is actually relative to the bottom of the crop box, so we need
                // to convert it such that it is relative to the top because the rest of our program works like that.
                Rectangle2D.Double(raw.x, doc.pages[0].cropBox.height - raw.y - raw.height, raw.width, raw.height)
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

            return SVG(gvtRoot, width, height)
        }

        private fun loadPDF(pdfFile: Path): PDF {
            // Note: We manually create an input stream and pass it to PDDocument.load() because when passing a
            // file object to that method and letting PDFBox create the input stream, it seems to forget to close it.
            val doc = pdfFile.inputStream().use { stream -> PDDocument.load(stream) }
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
