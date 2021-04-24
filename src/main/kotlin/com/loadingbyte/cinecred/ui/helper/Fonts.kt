package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.drawer.SYSTEM_FONTS
import java.awt.Font
import java.util.*


val BUNDLED_FAMILIES: FontFamilies = FontFamilies(BUNDLED_FONTS)
val SYSTEM_FAMILIES: FontFamilies = FontFamilies(SYSTEM_FONTS)


class FontFamily(val familyName: String, val fonts: List<Font>, val canonicalFont: Font) {

    private val fontNameToFont = fonts.associateBy { it.getFontName(Locale.ROOT) }

    fun getFont(fontName: String): Font? = fontNameToFont[fontName]

}


class FontFamilies(fonts: Iterable<Font>) {

    val list: List<FontFamily>

    fun getFamily(fontName: String): FontFamily? = fontNameToFamily[fontName]

    init {
        class ComparableFont(val font: Font, val name: String, val weight: Int, val italic: Boolean) :
            Comparable<ComparableFont> {
            override fun compareTo(other: ComparableFont): Int {
                val weightC = weight.compareTo(other.weight)
                if (weightC != 0) return weightC
                val italicC = italic.compareTo(other.italic)
                if (italicC != 0) return italicC
                return name.compareTo(other.name)
            }
        }

        // We use a tree map so that the families will be sorted by name.
        // We use tree sets as values so that the fonts of a family will be sorted by weight, italic, and then name.
        val familyToCFonts = TreeMap<String, TreeSet<ComparableFont>>()

        for (font in fonts) {
            val name = font.getFontName(Locale.ROOT)

            // Find the font's family.
            var (family, _) = font.getFamily(Locale.ROOT).removeSuffixes()
            if (family == "Titillium Up")
                family = "Titillium"

            // Find the font's weight and whether it's an italic font.
            var weight = 400
            var italic = false
            val cleanName = if (name.startsWith("Titillium")) name.removeSuffix("Upright") else name
            for (removedSuffix in cleanName.removeSuffixes().second) {
                val styleInfo = SUFFIX_TO_STYLE.getValue(removedSuffix)
                weight = styleInfo.weight ?: weight
                italic = styleInfo.italic ?: italic
            }

            familyToCFonts.getOrPut(family) { TreeSet() }.add(ComparableFont(font, name, weight, italic))
        }

        list = familyToCFonts.map { (family, cFonts) ->
            val canonicalFont = (cFonts.find { it.weight == 400 && !it.italic } ?: cFonts.first()).font
            FontFamily(family, cFonts.map { it.font }, canonicalFont)
        }
    }

    private fun String.removeSuffixes(): Pair<String, List<String>> {
        var string = trim()
        val removedSuffixes = mutableListOf<String>()
        outer@ while (true) {
            for (suffixWithSep in SUFFIXES_WITH_SEPARATORS)
                if (string.endsWith(suffixWithSep, ignoreCase = true)) {
                    string = string.dropLast(suffixWithSep.length).trim()
                    removedSuffixes.add(suffixWithSep.drop(1))
                    continue@outer
                }
            return Pair(string, removedSuffixes)
        }
    }

    private val fontNameToFamily = list
        .flatMap { family -> family.fonts.map { font -> font.name to family } }
        .toMap()


    companion object {

        private class FontStyle(val weight: Int? = null, val italic: Boolean? = null)

        private val SUFFIX_TO_STYLE = mapOf(
            "Bd" to FontStyle(weight = 700),
            "BdIta" to FontStyle(weight = 700, italic = true),
            "Bk" to FontStyle(weight = 900),
            "Black" to FontStyle(weight = 900),
            "Blk" to FontStyle(weight = 900),
            "Book" to FontStyle(weight = 400),
            "BookItalic" to FontStyle(weight = 400, italic = true),
            "Bold" to FontStyle(weight = 700),
            "BoldItalic" to FontStyle(weight = 700, italic = true),
            "DemBd" to FontStyle(weight = 600),
            "Demi" to FontStyle(weight = 600),
            "DemiBold" to FontStyle(weight = 600),
            "ExBold" to FontStyle(weight = 800),
            "ExtBd" to FontStyle(weight = 800),
            "Extra" to FontStyle(weight = 800),
            "ExtraBold" to FontStyle(weight = 800),
            "ExtraLight" to FontStyle(weight = 200),
            "Hairline" to FontStyle(weight = 100),
            "Heavy" to FontStyle(weight = 900),
            "HeavyItalic" to FontStyle(weight = 900, italic = true),
            "Italic" to FontStyle(italic = true),
            "Light" to FontStyle(weight = 300),
            "Lt" to FontStyle(weight = 300),
            "Md" to FontStyle(weight = 500),
            "Med" to FontStyle(weight = 500),
            "Medium" to FontStyle(weight = 500),
            "MediumItalic" to FontStyle(weight = 500, italic = true),
            "Normal" to FontStyle(weight = 400),
            "Oblique" to FontStyle(italic = true),
            "Plain" to FontStyle(weight = 400),
            "Regular" to FontStyle(weight = 400),
            "RegularItalic" to FontStyle(weight = 400, italic = true),
            "Roman" to FontStyle(weight = 400),
            "SemBd" to FontStyle(weight = 600),
            "Semi" to FontStyle(weight = 600),
            "SemiBold" to FontStyle(weight = 600),
            "SemiBoldItalic" to FontStyle(weight = 600, italic = true),
            "Th" to FontStyle(weight = 100),
            "Thin" to FontStyle(weight = 100),
            "Ultra" to FontStyle(weight = 800),
            "UltraBold" to FontStyle(weight = 800),
            "UltraLight" to FontStyle(weight = 200)
        )

        private val SUFFIXES_WITH_SEPARATORS = SUFFIX_TO_STYLE.keys.flatMap { listOf(" $it", "-$it", ".$it") }

    }

}
