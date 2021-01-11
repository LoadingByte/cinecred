package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.REF_G2
import com.loadingbyte.cinecred.common.RichFont
import com.loadingbyte.cinecred.project.DrawnPage
import com.loadingbyte.cinecred.project.FontSpec
import com.loadingbyte.cinecred.project.Project
import java.awt.Font
import java.awt.font.TextAttribute


fun draw(project: Project): List<DrawnPage> {
    // Get all font specs that appear somewhere on some page. Note that we do not just get the font specs from the
    // content styles list because that doesn't include STANDARD_CONTENT_STYLE font specs that are used whenever
    // the content styles list is empty.
    val fontSpecs = project.pages.asSequence()
        .flatMap { it.stages }.flatMap { it.segments }.flatMap { it.columns }.flatMap { it.blocks }
        .flatMap { listOf(it.style.bodyFontSpec, it.style.headFontSpec, it.style.tailFontSpec) }
        .toSet() // Ensure that each font spec is only contained once.

    // Generate AWT fonts that realize those font specs.
    val fonts = fontSpecs.associateWith { spec -> RichFont(spec, createAWTFont(project.fonts, spec)) }

    return project.pages.map { page -> drawPage(project.styling.global, fonts, page) }
}


private fun createAWTFont(projectFonts: Map<String, Font>, spec: FontSpec): Font {
    val baseFont = (projectFonts[spec.name] ?: getBundledFont(spec.name) ?: getSystemFont(spec.name))
        .deriveFont(100f)
        .deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON))

    // Now, we need to find a font size such that produces the requested font height in pixels.
    // Theoretically, this can be done in closed form, see:
    // https://stackoverflow.com/questions/5829703/java-getting-a-font-with-a-specific-height-in-pixels
    // However, tests have shown that the above method is not reliable with all fonts (e.g., not with Open Sans).
    // Therefore, we use a numerical search to find the font size.

    // Step 1: Exponential search to determine the rough range of the font size we're looking for.
    var size = 2f
    for (i in 0 until 20) {  // Upper-bound the number of repetitions to avoid accidental infinite looping.
        if (REF_G2.getFontMetrics(baseFont.deriveFont(size * size)).height >= spec.heightPx)
            break
        size *= size
    }

    // Step 2: Binary search to find the exact font size.
    // If $size is still 2, we look for a size between 0 and 4.
    // Otherwise, we look for a size between $size and $size^2.
    val minSize = if (size == 2f) 0f else size
    val maxSize = size * size
    var intervalLength = (maxSize - minSize) / 2f
    size = minSize + intervalLength
    for (i in 0 until 20) {  // Upper-bound the number of repetitions to avoid accidental infinite looping.
        intervalLength /= 2f
        val height = REF_G2.getFontMetrics(baseFont.deriveFont(size)).height
        when {
            height == spec.heightPx -> break
            height > spec.heightPx -> size -= intervalLength
            height < spec.heightPx -> size += intervalLength
        }
    }

    return baseFont.deriveFont(size)
}
