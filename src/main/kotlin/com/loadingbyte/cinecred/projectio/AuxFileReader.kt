package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.project.Picture
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.*
import org.apache.batik.bridge.svg12.SVG12BridgeContext
import org.apache.batik.util.XMLResourceDescriptor
import org.xml.sax.SAXException
import java.awt.Font
import java.awt.FontFormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO


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
        if (ext in RASTER_PICTURE_EXTS)
            return lazy {
                try {
                    Picture.Raster(ImageIO.read(pictureFile.toFile()))
                } catch (_: IOException) {
                    null
                }
            }
        else if (ext == "svg")
            return lazy {
                try {
                    loadSVG(pictureFile)
                } catch (_: IOException) {
                    null
                } catch (_: SAXException) {
                    null
                } catch (_: BridgeException) {
                    null
                }
            }
    return null
}

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
