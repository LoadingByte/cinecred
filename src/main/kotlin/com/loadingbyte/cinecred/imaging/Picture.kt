package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.equalsAny
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.withG2
import com.loadingbyte.cinecred.ui.helper.newLabelEditorPane
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import mkl.testarea.pdfbox2.extract.BoundingBoxFinder
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.*
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.BufferedImage.TYPE_3BYTE_BGR
import java.awt.image.BufferedImage.TYPE_4BYTE_ABGR
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import kotlin.io.path.*


sealed interface Picture {

    val width: Double
    val height: Double
    fun scaled(scaling: Double): Picture
    fun dispose() {}


    class Raster(img: BufferedImage, val scaling: Double = 1.0) : Picture {

        // Conform non-standard raster images to the 8-bit (A)BGR pixel format and the sRGB color space. This needs to
        // be done at some point because some of our code expects (A)BGR, and we're compositing in sRGB.
        val img =
            if (img.colorModel.colorSpace.isCS_sRGB && img.type.let { it == TYPE_3BYTE_BGR || it == TYPE_4BYTE_ABGR })
                img
            else
                BufferedImage(img.width, img.height, if (img.colorModel.hasAlpha()) TYPE_4BYTE_ABGR else TYPE_3BYTE_BGR)
                    .withG2 { g2 -> g2.drawImage(img, 0, 0, null) }

        override val width get() = scaling * img.width
        override val height get() = scaling * img.height

        override fun scaled(scaling: Double) = Raster(img, this.scaling * scaling)

    }


    class SVG(
        val gvtRoot: GraphicsNode, private val docWidth: Double, private val docHeight: Double,
        val scaling: Double = 1.0, val isCropped: Boolean = false
    ) : Picture {

        override val width get() = scaling * (if (isCropped) gvtRoot.bounds.width else docWidth)
        override val height get() = scaling * (if (isCropped) gvtRoot.bounds.height else docHeight)

        override fun scaled(scaling: Double) = SVG(gvtRoot, docWidth, docHeight, this.scaling * scaling, isCropped)
        fun cropped() = SVG(gvtRoot, docWidth, docHeight, scaling, isCropped = true)

    }


    class PDF private constructor(
        private val aDoc: AugmentedDoc, val scaling: Double, val isCropped: Boolean
    ) : Picture {

        constructor(doc: PDDocument) : this(AugmentedDoc(doc), 1.0, false)

        val doc get() = aDoc.doc
        val minBox get() = aDoc.minBox

        override val width get() = scaling * (if (isCropped) minBox.width else doc.pages[0].cropBox.width.toDouble())
        override val height get() = scaling * (if (isCropped) minBox.height else doc.pages[0].cropBox.height.toDouble())

        override fun scaled(scaling: Double) = PDF(aDoc, this.scaling * scaling, isCropped)
        fun cropped() = PDF(aDoc, scaling, isCropped = true)

        override fun dispose() {
            // Synchronize the closing operation so that other threads can safely check whether a document is closed
            // and then use it in their own synchronized block.
            synchronized(doc) {
                doc.close()
            }
        }

        private class AugmentedDoc(val doc: PDDocument) {
            val minBox: Rectangle2D by lazy {
                // If the document has already been closed, the responsible project has just been closed. In that case,
                // just return a dummy rectangle here to let the project closing finish regularly.
                if (doc.document.isClosed)
                    return@lazy Rectangle2D.Double(0.0, 0.0, 1.0, 1.0)
                val raw = BoundingBoxFinder(doc.pages[0]).apply { processPage(doc.pages[0]) }.boundingBox
                // The raw bounding box y coordinate is actually relative to the bottom of the crop box, so we need
                // to convert it such that it is relative to the top because the rest of our program works like that.
                Rectangle2D.Double(raw.x, doc.pages[0].cropBox.height - raw.y - raw.height, raw.width, raw.height)
            }
        }

    }


    companion object {

        private val RASTER_EXTS = ImageIO.getReaderFileSuffixes().asList()
        private const val SVG_EXT = "svg"
        private const val PDF_EXT = "pdf"
        private val POSTSCRIPT_EXTS = listOf("ai", "eps", "ps")

        val EXTS = RASTER_EXTS + SVG_EXT + PDF_EXT + POSTSCRIPT_EXTS

        /** @throws Exception */
        fun read(file: Path): Picture {
            val ext = file.extension
            if (file.isRegularFile())
                when {
                    ext.equalsAny(RASTER_EXTS, ignoreCase = true) -> return loadRaster(file)
                    ext.equals(SVG_EXT, ignoreCase = true) -> return loadSVG(file)
                    ext.equals(PDF_EXT, ignoreCase = true) -> return loadPDF(file)
                    ext.equalsAny(POSTSCRIPT_EXTS, ignoreCase = true) -> return loadPostScript(file)
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
            val ctx = when {
                doc.isSVG12 -> SVG12BridgeContext(UserAgentAdapter())
                else -> BridgeContext(UserAgentAdapter())
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

            val linkWin = "https://ghostscript.com/releases/gsdnld.html"
            val osSpecific = when {
                SystemInfo.isWindows -> l10n("imaging.ghostscriptMissing.msg.windows", linkWin)
                SystemInfo.isMacOS -> l10n("imaging.ghostscriptMissing.msg.macos", "https://pages.uoregon.edu/koch/")
                else -> l10n("imaging.ghostscriptMissing.msg.linux")
            }
            val msg = "<html>" + l10n("imaging.ghostscriptMissing.msg", osSpecific).replace("\n", "<br>") + "</html>"
            val ep = newLabelEditorPane("text/html", msg)
            ep.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED)
                    tryBrowse(e.url.toURI())
            }
            JOptionPane.showMessageDialog(
                null, ep, l10n("imaging.ghostscriptMissing.title"), JOptionPane.WARNING_MESSAGE
            )
            null
        }

        private val GS_STREAM_GOBBLER_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GhostscriptStreamGobbler").apply { isDaemon = true }
        }
        private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

        private fun loadPostScript(psFile: Path): PDF {
            val gs = GS_EXECUTABLE ?: throw IOException()
            val tmpFile = createTempFile("cinecred-ps2pdf-", ".pdf")
            try {
                val cmd = arrayOf(gs.pathString, "-sDEVICE=pdfwrite", "-o", tmpFile.pathString, psFile.pathString)
                val process = Runtime.getRuntime().exec(cmd)
                GS_STREAM_GOBBLER_EXECUTOR.submit {
                    process.inputStream.bufferedReader().lines().forEach { GS_LOGGER.info(it) }
                    process.errorStream.bufferedReader().lines().forEach { GS_LOGGER.error(it) }
                }
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
