package com.loadingbyte.cinecred.drawer

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.*
import kotlin.streams.toList


object Fonts {

    val bundledFamilies: List<FontFamily>
    val systemFamilies: List<FontFamily>

    private val nameToFont: Map<String, Font>
    private val nameToFamily: Map<String, FontFamily>

    val fontNames: Set<String>
        get() = nameToFont.keys

    // Note: If the font map doesn't contain a font with the specified name, we create a font object to find a font
    // that (hopefully) best matches the specified font.
    fun getFont(name: String): Font = nameToFont[name] ?: Font(name, 0, 1)

    fun getFamily(name: String): FontFamily? = nameToFamily[name]

    init {
        class FontStyle(val weight: Int? = null, val italic: Boolean? = null)

        val suffixes = mapOf(
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

        val suffixesWithSeparators = suffixes.keys.flatMap { listOf(" $it", "-$it", ".$it") }

        fun String.removeSuffixes(): Pair<String, List<String>> {
            var string = trim()
            val removedSuffixes = mutableListOf<String>()
            outer@ while (true) {
                for (suffixWithSep in suffixesWithSeparators)
                    if (string.endsWith(suffixWithSep, ignoreCase = true)) {
                        string = string.dropLast(suffixWithSep.length).trim()
                        removedSuffixes.add(suffixWithSep.drop(1))
                        continue@outer
                    }
                return Pair(string, removedSuffixes)
            }
        }

        class ComparabFontName(val name: String, val weight: Int, val italic: Boolean) : Comparable<ComparabFontName> {
            override fun compareTo(other: ComparabFontName): Int {
                val weightC = weight.compareTo(other.weight)
                if (weightC != 0) return weightC
                val italicC = italic.compareTo(other.italic)
                if (italicC != 0) return italicC
                return name.compareTo(other.name)
            }
        }

        fun buildFamilies(fonts: List<Font>): List<FontFamily> {
            // We use a tree map so that the families will be sorted by name.
            // We use tree sets as values so that the fonts of a family will be sorted by weight, italic, and then name.
            val familyToNames = TreeMap<String, TreeSet<ComparabFontName>>()

            for (font in fonts) {
                val name = font.getFontName(Locale.US)

                // Find the font's family.
                var (family, _) = font.getFamily(Locale.US).removeSuffixes()
                if (family == "Titillium Up")
                    family = "Titillium"

                // Find the font's weight and whether it's an italic font.
                var weight = 400
                var italic = false
                for (removedSuffix in name.removeSuffixes().second) {
                    val styleInfo = suffixes[removedSuffix]!!
                    weight = styleInfo.weight ?: weight
                    italic = styleInfo.italic ?: italic
                }

                familyToNames.getOrPut(family) { TreeSet() }.add(ComparabFontName(name, weight, italic))
            }

            return familyToNames.map { (family, names) -> FontFamily(family, names.map { it.name }) }
        }

        // Load fonts that are bundled with this program.
        val bundledFonts = javaClass.getResourceAsStream("/fonts").use { it.bufferedReader().lines() }
            .map { filename -> javaClass.getResourceAsStream("/fonts/$filename").use { Font.createFonts(it)[0] } }
            .toList()
        bundledFamilies = buildFamilies(bundledFonts)

        // Load fonts that are present on the system.
        val systemFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.toList()
        systemFamilies = buildFamilies(systemFonts)

        // Also create a map from font name to font. If a font name occurs both in the bundled fonts
        // as well as in the system fonts, the bundled font is preferred.
        val nameToFont = mutableMapOf<String, Font>()
        for (font in systemFonts + bundledFonts)
            nameToFont[font.getFontName(Locale.US)] = font
        this.nameToFont = nameToFont

        // Also create a map from font name to family. Again, bundled fonts are preferred in case of conflicts.
        val nameToFamily = mutableMapOf<String, FontFamily>()
        for (family in systemFamilies + bundledFamilies)
            for (name in family.fontNames)
                nameToFamily[name] = family
        this.nameToFamily = nameToFamily
    }

}


class FontFamily(val familyName: String, val fontNames: List<String>)
