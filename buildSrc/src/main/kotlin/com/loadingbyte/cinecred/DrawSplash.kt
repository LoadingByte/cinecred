package com.loadingbyte.cinecred

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO


abstract class DrawSplash : DefaultTask() {

    @get:Input
    abstract val version: Property<String>
    @get:InputFile
    abstract val logoFile: RegularFileProperty
    @get:InputFile
    abstract val reguFontFile: RegularFileProperty
    @get:InputFile
    abstract val semiFontFile: RegularFileProperty
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        val title = "Cinecred"
        val logoSize = 128
        val logoTitleGap = 30
        val titleVersionGap = 15

        val logo = Logo(logoFile.get().asFile)
        val titleFont = Font.createFonts(semiFontFile.get().asFile)[0].deriveFont(80f)
        val versionFont = Font.createFonts(reguFontFile.get().asFile)[0].deriveFont(80f)
        val version = version.get()
        val outputFile = outputFile.get().asFile

        val image = BufferedImage(750, 250, BufferedImage.TYPE_INT_RGB)
        val g2 = image.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val titleWidth = g2.getFontMetrics(titleFont).stringWidth(title)
        val versionWidth = g2.getFontMetrics(versionFont).stringWidth(version)
        val contentWidth = logoSize + logoTitleGap + titleWidth + titleVersionGap + versionWidth
        val x = (image.width - contentWidth) / 2

        g2.color = Color(117, 117, 117)
        g2.fillRect(0, 0, image.width, image.height)
        g2.color = Color.BLACK
        g2.fillRect(1, 1, image.width - 2, image.height - 2)
        g2.drawImage(logo.rasterize(logoSize), x, (image.height - logoSize) / 2, null)
        g2.color = Color.WHITE
        g2.font = titleFont
        g2.drawString(title, x + logoSize + logoTitleGap, 152)
        g2.font = versionFont
        g2.drawString(version, x + logoSize + logoTitleGap + titleWidth + titleVersionGap, 152)

        g2.dispose()
        ImageIO.write(image, outputFile.extension, outputFile)
    }

}
