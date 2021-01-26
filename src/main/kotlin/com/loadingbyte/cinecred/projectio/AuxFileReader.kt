package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.Picture
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.MainFrame
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.*
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.Font
import java.awt.FontFormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.JEditorPane
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import kotlin.streams.toList


private val Path.extension: String
    get() = fileName.toString().substringAfterLast('.').toLowerCase()


fun tryReadFont(fontFile: Path): Font? {
    val ext = fontFile.extension
    if (Files.isRegularFile(fontFile) && (ext == "ttf" || ext == "otf"))
        try {
            return Font.createFonts(fontFile.toFile())[0]
        } catch (_: FontFormatException) {
        }
    return null
}


private val RASTER_PICTURE_EXTS = ImageIO.getReaderFileSuffixes().toSet()

fun tryReadPictureLoader(pictureFile: Path): Lazy<Picture?>? {
    val ext = pictureFile.extension
    if (Files.isRegularFile(pictureFile))
        when (ext) {
            in RASTER_PICTURE_EXTS -> return lazy { runCatching { loadRaster(pictureFile) }.getOrNull() }
            "svg" -> return lazy { runCatching { loadSVG(pictureFile) }.getOrNull() }
            "pdf" -> return lazy { runCatching { loadPDF(pictureFile) }.getOrNull() }
            "ai", "eps", "ps" -> return lazy { runCatching { loadPostScript(pictureFile) }.getOrNull() }
        }
    return null
}


private fun loadRaster(rasterFile: Path): Picture.Raster =
    Picture.Raster(ImageIO.read(rasterFile.toFile()))


// Look here for references:
// https://github.com/apache/xmlgraphics-batik/blob/trunk/batik-transcoder/src/main/java/org/apache/batik/transcoder/SVGAbstractTranscoder.java
private fun loadSVG(svgFile: Path): Picture.SVG {
    val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        .createDocument(svgFile.toUri().toString(), Files.newBufferedReader(svgFile)) as SVGOMDocument
    val docRoot = doc.rootElement
    val ctx = when {
        doc.isSVG12 -> SVG12BridgeContext(UserAgentAdapter())
        else -> BridgeContext(UserAgentAdapter())
    }
    ctx.isDynamic = true

    val gvtRoot = GVTBuilder().build(ctx, doc)

    val width = ctx.documentSize.width.toFloat()
    val height = ctx.documentSize.height.toFloat()

    // Dispatch an 'onload' event if needed.
    if (ctx.isDynamic) {
        val se = BaseScriptingEnvironment(ctx)
        se.loadScripts()
        se.dispatchSVGLoadEvent()
        if (ctx.isSVG12)
            ctx.animationEngine.currentTime = SVGUtilities.convertSnapshotTime(docRoot, null)
    }

    return Picture.SVG(gvtRoot, width, height)
}


private fun loadPDF(pdfFile: Path): Picture.PDF {
    // Note: We manually create an input stream and pass it to PDDocument.load() because when passing a
    // file object to that method and letting PDFBox create the input stream, it seems to forget to close it.
    val doc = Files.newInputStream(pdfFile).use { stream -> PDDocument.load(stream) }
    if (doc.numberOfPages != 0)
        return Picture.PDF(doc)
    else
        throw IOException()
}


private val GS_EXECUTABLE: Path? by lazy {
    try {
        if (System.getProperty("os.name").startsWith("windows", ignoreCase = true)) {
            for (dir in listOf(Path.of("C:\\Program Files\\gs"), Path.of("C:\\Program Files (x86)\\gs")))
                if (Files.isDirectory(dir))
                    for (dir2 in Files.list(dir).toList().sortedDescending()) {
                        val gs = dir.resolve(dir2).resolve("bin\\gswin64c.exe")
                        if (Files.isExecutable(gs))
                            return@lazy gs
                    }
        } else {
            val home = Path.of(System.getProperty("user.home"))
            val candidateDirs = System.getenv("PATH").split(':').map(Path::of) +
                    listOf("/bin", "/usr/bin", "/usr/local", "/usr/local/bin", "/opt/local/bin").map(Path::of) +
                    listOf(home.resolve("bin"), home.resolve("Applications"))
            for (dir in candidateDirs) {
                val gs = dir.resolve("gs")
                if (Files.isExecutable(gs))
                    return@lazy gs
            }
        }
    } catch (_: IOException) {
        // This block shouldn't run, but maybe we have overlooked something or the operating system behaves in a
        // strange way. In either case, ignore the exception, as its most likely just another indication that
        // Ghostscript is not installed.
    }

    val linkWinLinux = "https://www.ghostscript.com/download/gsdnld.html"
    val linkMac = "https://pages.uoregon.edu/koch/"
    val ep = JEditorPane("text/html", l10n("projectIO.ghostscriptMissing.msg", linkWinLinux, linkMac))
    ep.addHyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED)
            Desktop.getDesktop().browse(e.url.toURI())
    }
    ep.isEditable = false
    JOptionPane.showMessageDialog(MainFrame, ep, l10n("projectIO.ghostscriptMissing.title"), JOptionPane.ERROR_MESSAGE)
    null
}

private val GS_STREAM_GOBBLER_EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "Ghostscript") }
private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

private fun loadPostScript(psFile: Path): Picture.PDF {
    val gs = GS_EXECUTABLE ?: throw IOException()
    val tmpFile = Files.createTempFile("cinecred-ps2pdf-", ".pdf")
    try {
        val cmd = arrayOf(gs.toString(), "-sDEVICE=pdfwrite", "-o", tmpFile.toString(), psFile.toString())
        val process = Runtime.getRuntime().exec(cmd)
        GS_STREAM_GOBBLER_EXECUTOR.submit {
            process.inputStream.bufferedReader().lines().forEach { GS_LOGGER.info(it) }
            process.errorStream.bufferedReader().lines().forEach { GS_LOGGER.error(it) }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0)
            throw IOException()
        // Try for at most then seconds to access the file generated by Ghostscript. On Windows machines, the file
        // is sometimes not unlocked right away even though the Ghostscript process has already terminated, so we
        // use this spinlock-like mechanism to wait for Windows to unlock the file. This seems to be the best option.
        repeat(10) {
            try {
                return loadPDF(tmpFile)
            } catch (_: Exception) {
            }
            Thread.sleep(100)
        }
        throw IOException()
    } finally {
        // Presumably because of some Kotlin compiler bug, we have to put try-catch block with a Files.delete()
        // into an extra method. If we don't, the compiler generates branches where the try-catch block is missing.
        tryDeleteFile(tmpFile)
    }
}

private fun tryDeleteFile(file: Path) {
    try {
        Files.delete(file)
    } catch (_: IOException) {
        // Ignore; don't bother the user when a probably tiny PDF file can't be deleted that will be deleted
        // by the OS the next time the user restarts his system anyways.
    }
}
