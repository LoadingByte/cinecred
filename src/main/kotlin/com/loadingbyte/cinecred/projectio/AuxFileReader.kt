package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.Picture
import com.loadingbyte.cinecred.ui.MainFrame
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.*
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.util.XMLResourceDescriptor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.slf4j.LoggerFactory
import org.xml.sax.SAXException
import java.awt.Desktop
import java.awt.Font
import java.awt.FontFormatException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
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
            in RASTER_PICTURE_EXTS -> return lazy {
                try {
                    Picture.Raster(ImageIO.read(pictureFile.toFile()))
                } catch (_: IOException) {
                    null
                }
            }
            "svg" -> return lazy { loadSVG(pictureFile) }
            "pdf" -> return lazy { loadPDF(pictureFile) }
            "ps", "eps" -> return lazy { loadPostScript(pictureFile) }
        }
    return null
}


// Look here for references:
// https://github.com/apache/xmlgraphics-batik/blob/trunk/batik-transcoder/src/main/java/org/apache/batik/transcoder/SVGAbstractTranscoder.java
private fun loadSVG(svgFile: Path): Picture.SVG? {
    try {
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
    } catch (_: IOException) {
    } catch (_: SAXException) {
    } catch (_: BridgeException) {
    }
    return null
}


private fun loadPDF(pdfFile: Path): Picture.PDF? {
    try {
        val doc = PDDocument.load(pdfFile.toFile())
        if (doc.numberOfPages != 0)
            return Picture.PDF(doc)
    } catch (_: IOException) {
    } catch (_: InvalidPasswordException) {
    }
    return null
}


private val GS_EXECUTABLE: Path? by lazy {
    if (System.getProperty("os.name").startsWith("windows", ignoreCase = true)) {
        for (dir in listOf(Path.of("C:\\Program Files\\gs"), Path.of("C:\\Program Files (x86)\\gs")))
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

    val msg = "<html>Ghostscript, which is required to incorporate <code>.ps</code> and <code>.eps</code> files, " +
            "could not be found.<br>" +
            "If you're on Windows or Linux, please download and install Ghostscript from here: " +
            "<a href=\"https://www.ghostscript.com/download/gsdnld.html\">Ghostscript Download Site</a> " +
            "(choose the \"AGPL Release\")<br>" +
            "If you're on Mac, please download and install Ghostscript from here: " +
            "<a href=\"https://pages.uoregon.edu/koch/\">Richard Koch's Inofficial Mac Builds</a><br>" +
            "Afterwards, restart Cinecred, which then tries to locate Ghostscript again.</html>"
    val ep = JEditorPane("text/html", msg)
    ep.addHyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED)
            Desktop.getDesktop().browse(e.url.toURI())
    }
    ep.isEditable = false
    JOptionPane.showMessageDialog(MainFrame, ep, "Ghostscript Not Found", JOptionPane.ERROR_MESSAGE)
    null
}

private val GS_STREAM_GOBBLER_EXECUTOR = Executors.newSingleThreadExecutor { r -> Thread(r, "Ghostscript") }
private val GS_LOGGER = LoggerFactory.getLogger("Ghostscript")

private fun loadPostScript(psFile: Path): Picture.PDF? {
    val gs = GS_EXECUTABLE ?: return null
    val tmpFile = Files.createTempFile("cinecred-ps2pdf-", ".pdf")
    try {
        val cmd = arrayOf(gs.toString(), "-sDEVICE=pdfwrite", "-o", tmpFile.toString(), psFile.toString())
        val process = Runtime.getRuntime().exec(cmd)
        GS_STREAM_GOBBLER_EXECUTOR.submit {
            BufferedReader(InputStreamReader(process.inputStream)).lines().forEach { GS_LOGGER.info(it) }
            BufferedReader(InputStreamReader(process.errorStream)).lines().forEach { GS_LOGGER.error(it) }
        }
        val exitCode = process.waitFor()
        return if (exitCode != 0) null else loadPDF(tmpFile)
    } finally {
        Files.delete(tmpFile)
    }
}
