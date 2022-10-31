package com.loadingbyte.cinecred.projectio

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.Picture
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.newLabelEditorPane
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.*
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.pdmodel.PDDocument
import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.FontFormatException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.event.HyperlinkEvent
import kotlin.io.path.*


private fun String.equalsAny(other: Collection<String>, ignoreCase: Boolean = false) =
    other.any { equals(it, ignoreCase) }


private val FONT_FILE_EXTS = listOf("ttf", "ttc", "otf", "otc")

fun tryReadFonts(fontFile: Path): List<Font> {
    val ext = fontFile.extension
    if (fontFile.isRegularFile() && ext.equalsAny(FONT_FILE_EXTS, ignoreCase = true))
        try {
            return Font.createFonts(fontFile.toFile()).toList()
        } catch (_: FontFormatException) {
        }
    return emptyList()
}


private val RASTER_PICTURE_EXTS = ImageIO.getReaderFileSuffixes().toSet()
private val POSTSCRIPT_EXTS = listOf("ai", "eps", "ps")

fun tryReadPictureLoader(pictureFile: Path): Lazy<Picture?>? {
    val ext = pictureFile.extension
    if (pictureFile.isRegularFile())
        when {
            ext.equalsAny(RASTER_PICTURE_EXTS, ignoreCase = true) ->
                return lazy { runCatching { loadRaster(pictureFile) }.getOrNull() }
            ext.equals("svg", ignoreCase = true) ->
                return lazy { runCatching { loadSVG(pictureFile) }.getOrNull() }
            ext.equals("pdf", ignoreCase = true) ->
                return lazy { runCatching { loadPDF(pictureFile) }.getOrNull() }
            ext.equalsAny(POSTSCRIPT_EXTS, ignoreCase = true) ->
                return lazy { runCatching { loadPostScript(pictureFile) }.getOrNull() }
        }
    return null
}


private fun loadRaster(rasterFile: Path): Picture.Raster =
    Picture.Raster(ImageIO.read(rasterFile.toFile()))


// Look here for references:
// https://github.com/apache/xmlgraphics-batik/blob/trunk/batik-transcoder/src/main/java/org/apache/batik/transcoder/SVGAbstractTranscoder.java
private fun loadSVG(svgFile: Path): Picture.SVG {
    val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
        .createDocument(svgFile.toUri().toString(), svgFile.bufferedReader()) as SVGOMDocument
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
    val doc = pdfFile.inputStream().use { stream -> PDDocument.load(stream) }
    if (doc.numberOfPages != 0)
        return Picture.PDF(doc)
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
    } catch (_: IOException) {
        // This block shouldn't run, but maybe we have overlooked something or the operating system behaves in a
        // strange way. In either case, ignore the exception, as its most likely just another indication that
        // Ghostscript is not installed.
    }

    val linkWin = "https://ghostscript.com/releases/gsdnld.html"
    val osSpecific = when {
        SystemInfo.isWindows -> l10n("projectIO.ghostscriptMissing.msg.windows", linkWin)
        SystemInfo.isMacOS -> l10n("projectIO.ghostscriptMissing.msg.macos", "https://pages.uoregon.edu/koch/")
        else -> l10n("projectIO.ghostscriptMissing.msg.linux")
    }
    val msg = l10n("projectIO.ghostscriptMissing.msg", osSpecific)
    val ep = newLabelEditorPane("text/html", msg)
    ep.addHyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED)
            tryBrowse(e.url.toURI())
    }
    JOptionPane.showMessageDialog(null, ep, l10n("projectIO.ghostscriptMissing.title"), JOptionPane.WARNING_MESSAGE)
    null
}

private val GS_STREAM_GOBBLER_EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "Ghostscript") }
private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

private fun loadPostScript(psFile: Path): Picture.PDF {
    val gs = GS_EXECUTABLE ?: throw IOException()
    val tmpFile = createTempFile("cinecred-ps2pdf-", ".pdf")
    try {
        val cmd = arrayOf(gs.toString(), "-sDEVICE=pdfwrite", "-o", tmpFile.toString(), psFile.toString())
        val process = Runtime.getRuntime().exec(cmd)
        GS_STREAM_GOBBLER_EXECUTOR.submit {
            process.inputStream.bufferedReader().lines().forEach { GS_LOGGER.info(it) }
            process.errorStream.bufferedReader().lines().forEach { GS_LOGGER.error(it) }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0)
            throw RuntimeException("Ghostscript terminated with error exit code $exitCode.")
        // Try for at most then seconds to access the file generated by Ghostscript. On Windows machines, the file
        // is sometimes not unlocked right away even though the Ghostscript process has already terminated, so we
        // use this spinlock-like mechanism to wait for Windows to unlock the file. This seems to be the best option.
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
        // Presumably because of some Kotlin compiler bug, we have to put try-catch block with a deleteExisting() call
        // into an extra method. If we don't, the compiler generates branches where the try-catch block is missing.
        tryDeleteFile(tmpFile)
    }
}

private fun tryDeleteFile(file: Path) {
    try {
        file.deleteExisting()
    } catch (_: IOException) {
        // Ignore; don't bother the user when a probably tiny PDF file can't be deleted that will be deleted
        // by the OS the next time the user restarts his system anyways.
    }
}
