package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.REF_G2
import com.loadingbyte.cinecred.project.*
import java.awt.Font
import java.awt.font.TextAttribute.*


fun draw(project: Project): List<DrawnPage> {
    // Generate AWT fonts that realize the configured letter styles, as well as the placeholder letter style, which
    // functions as a fallback if the letter style name referenced in a content style is unknown.
    val fonts = (project.styling.letterStyles + PLACEHOLDER_LETTER_STYLE)
        .associateWith { style -> createAWTFonts(project.fonts, style) }

    // Convert the list of custom uppercase exception patterns to a single regex.
    val uppercaseExceptionsRegex = createUppercaseExceptionsRegex(project.styling.global.uppercaseExceptions)

    // Store both in a TextContext object.
    val textCtx = TextContext(fonts, uppercaseExceptionsRegex)

    return project.pages.map { page -> drawPage(project.styling.global, textCtx, page) }
}


private fun createAWTFonts(projectFonts: Map<String, Font>, style: LetterStyle): Pair<Font, Font> {
    val baseFont = (projectFonts[style.fontName] ?: getBundledFont(style.fontName) ?: getSystemFont(style.fontName))
        .deriveFont(100f)

    // Now, we need to find a font size such that produces the requested font height in pixels.
    // Theoretically, this can be done in closed form, see:
    // https://stackoverflow.com/questions/5829703/java-getting-a-font-with-a-specific-height-in-pixels
    // However, tests have shown that the above method is not reliable with all fonts (e.g., not with Open Sans).
    // Therefore, we use a numerical search to find the font size.

    // Step 1: Exponential search to determine the rough range of the font size we're looking for.
    var size = 2f
    // Upper-bound the number of repetitions to avoid:
    //   - Accidental infinite looping.
    //   - Too large fonts, as they cause the Java font rendering engine to destroy its own fonts.
    for (i in 0 until 10) {
        if (REF_G2.getFontMetrics(baseFont.deriveFont(size * 2f)).height >= style.heightPx)
            break
        size *= 2f
    }

    // Step 2: Binary search to find the exact font size.
    // If $size is still 2, we look for a size between 0 and 4.
    // Otherwise, we look for a size between $size and $size*2.
    val minSize = if (size == 2f) 0f else size
    val maxSize = size * 2f
    var intervalLength = (maxSize - minSize) / 2f
    size = minSize + intervalLength
    // Upper-bound the number of repetitions to avoid accidental infinite looping.
    for (i in 0 until 20) {
        intervalLength /= 2f
        val height = REF_G2.getFontMetrics(baseFont.deriveFont(size)).height
        when {
            height == style.heightPx -> break
            height > style.heightPx -> size -= intervalLength
            height < style.heightPx -> size += intervalLength
        }
    }

    // Finally, derive a font using the found size and other properties configured in the letter style.
    // Also turn on kerning and ligatures.
    val fontAttrs = mapOf(
        SIZE to size,
        KERNING to KERNING_ON,
        LIGATURES to LIGATURES_ON,
        TRACKING to style.tracking,
        SUPERSCRIPT to when (style.superscript) {
            Superscript.SUP_2 -> 2
            Superscript.SUP_1 -> 1
            Superscript.NONE -> 0
            Superscript.SUB_1 -> -1
            Superscript.SUB_2 -> -2
        }
    )
    val stdFont = baseFont.deriveFont(fontAttrs)
    val smallCapsFont = stdFont.deriveFont(size * 0.7f)
    return Pair(stdFont, smallCapsFont)
}


private fun createUppercaseExceptionsRegex(uppercaseExceptions: List<String>): Regex? = uppercaseExceptions
    .filter { it.isNotBlank() && it != "_" && it != "#" }
    .also { if (it.isEmpty()) return null }
    .groupBy { charToKey(it.first(), 1, 2, 4) or charToKey(it.last(), 8, 16, 32) }
    .map { (key, patterns) ->
        val prefix = when {
            key and 2 != 0 -> "(\\s|^)"
            key and 4 != 0 -> "[\\p{Lu}\\p{Lt}]"
            else -> ""
        }
        val suffix = when {
            key and 16 != 0 -> "(\\s|$)"
            key and 32 != 0 -> "[\\p{Lu}\\p{Lt}]"
            else -> ""
        }
        val joinedPatterns = patterns
            // Note: By sorting the patterns in descending order, we ensure that in the case of long patterns which
            // contain shorter patterns (e.g., "_von und zu_" and "_von_"), the long pattern is matched if it applies,
            // and the short pattern is only matched if the long pattern doesn't apply. If we don't enforce this,
            // the shorter pattern might be matched, but the long pattern isn't even though it applies, which is
            // highly unexpected behavior.
            .sortedByDescending(String::length)
            .joinToString("|") { pattern ->
                when {
                    key and (1 or 8) == 0 -> pattern.substring(1, pattern.length - 1)
                    key and 1 == 0 -> pattern.drop(1)
                    key and 8 == 0 -> pattern.dropLast(1)
                    else -> pattern
                }.let(Regex::escape)
            }
        "$prefix($joinedPatterns)$suffix"
    }
    .joinToString("|")
    .toRegex()

private fun charToKey(char: Char, forOther: Int, forUnderscore: Int, forHash: Int) = when (char) {
    '_' -> forUnderscore
    '#' -> forHash
    else -> forOther
}
