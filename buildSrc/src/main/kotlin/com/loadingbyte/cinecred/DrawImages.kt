package com.loadingbyte.cinecred

import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.ext.awt.image.GraphicsUtil
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream


/**
 * This task transcodes the logo SVG to the platform-specific icon image format and put it into the packaging folder.
 * For Windows and macOS, it additionally creates the appropriate installer background images.
 */
abstract class DrawImages : DefaultTask() {

    @get:Input
    abstract val version: Property<String>
    @get:Input
    abstract val forPlatform: Property<Platform>
    @get:InputFile
    abstract val logoFile: RegularFileProperty
    @get:InputFile
    abstract val semiFontFile: RegularFileProperty
    @get:InputFile
    abstract val boldFontFile: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val version = version.get()
        val logoFile = logoFile.get().asFile
        val outputDir = outputDir.get().asFile

        val logo = Logo(logoFile)
        val semiFont = Font.createFonts(semiFontFile.get().asFile)[0]!!
        val boldFont = Font.createFonts(boldFontFile.get().asFile)[0]!!

        when (forPlatform.get()) {
            Platform.WINDOWS -> {
                logo.transcode(16, 20, 24, 32, 40, 48, 64, 256, file = outputDir.resolve("icon.ico"))
                buildImage(outputDir.resolve("banner.bmp"), 493, 58, BufferedImage.TYPE_INT_RGB) { g2 ->
                    g2.color = Color.WHITE
                    g2.fillRect(0, 0, 493, 58)
                    g2.drawImage(logo.rasterize(48), 438, 5, null)
                }
                buildImage(outputDir.resolve("sidebar.bmp"), 493, 312, BufferedImage.TYPE_INT_RGB) { g2 ->
                    g2.color = Color.WHITE
                    g2.fillRect(165, 0, 493, 312)
                    g2.drawImage(logo.rasterize(100), 32, 28, null)
                    g2.font = semiFont.deriveFont(32f)
                    g2.drawCenteredString("Cinecred", 0, 176, 165)
                    g2.font = boldFont.deriveFont(20f)
                    g2.drawCenteredString(version, 0, 204, 165)
                }
            }
            Platform.MAC_OS -> {
                // Note: Currently, icons smaller than 128 written into an ICNS file by TwelveMonkeys cannot be
                // properly parsed by macOS. We have to leave out those sizes to avoid glitches.
                logo.transcode(128, 256, 512, 1024, margin = 0.055, file = outputDir.resolve("icon.icns"))
                fun buildBgImage(to: File, textColor: Color) {
                    buildImage(to, 182, 180, BufferedImage.TYPE_INT_ARGB) { g2 ->
                        g2.drawImage(logo.rasterize(80), 51, 0, null)
                        g2.color = textColor
                        g2.font = semiFont.deriveFont(24f)
                        g2.drawCenteredString("Cinecred", 0, 110, 182)
                        g2.font = boldFont.deriveFont(16f)
                        g2.drawCenteredString(version, 0, 130, 182)
                    }
                }
                buildBgImage(outputDir.resolve("background-light.png"), Color.BLACK)
                buildBgImage(outputDir.resolve("background-dark.png"), Color.WHITE)
            }
            Platform.LINUX -> {
                logoFile.copyTo(outputDir.resolve("cinecred.svg"), overwrite = true)
                logo.transcode(48, file = outputDir.resolve("cinecred.png"))
            }
        }
    }

    private fun buildImage(to: File, width: Int, height: Int, imageType: Int, block: (Graphics2D) -> Unit) {
        val image = BufferedImage(width, height, imageType)
        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        block(g2)
        g2.dispose()
        to.parentFile.mkdirs()
        ImageIO.write(image, to.extension, to)
    }

    private fun Graphics2D.drawCenteredString(str: String, x: Int, y: Int, w: Int) {
        drawString(str, x + (w - fontMetrics.stringWidth(str)) / 2, y)
    }


    private class Logo(file: File) {

        private val node: GraphicsNode
        private val width: Double

        init {
            val doc = SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
                .createDocument(null, file.bufferedReader())
            val ctx = BridgeContext(UserAgentAdapter())
            node = GVTBuilder().build(ctx, doc)
            width = ctx.documentSize.width
        }

        fun rasterize(size: Int, margin: Double = 0.0, imageType: Int = BufferedImage.TYPE_INT_ARGB): BufferedImage {
            val img = BufferedImage(size, size, imageType)
            val g2 = GraphicsUtil.createGraphics(img)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val transl = margin * size
            val scaling = (size * (1 - 2 * margin)) / width
            g2.translate(transl, transl)
            g2.scale(scaling, scaling)
            node.paint(g2)
            g2.dispose()
            return img
        }

        fun transcode(vararg sizes: Int, margin: Double = 0.0, file: File) {
            val imageType = if (file.extension == "ico") BufferedImage.TYPE_4BYTE_ABGR else BufferedImage.TYPE_INT_ARGB
            val images = sizes.map { size -> rasterize(size, margin, imageType) }

            file.delete()
            file.parentFile.mkdirs()
            FileImageOutputStream(file).use { stream ->
                val writer = ImageIO.getImageWritersBySuffix(file.extension).next()
                writer.output = stream
                if (images.size == 1)
                    writer.write(images[0])
                else {
                    writer.prepareWriteSequence(null)
                    for (image in images)
                        writer.writeToSequence(IIOImage(image, null, null), null)
                    writer.endWriteSequence()
                }
            }
        }

    }

}
