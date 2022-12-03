package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.drawer.BUNDLED_FONTS
import com.loadingbyte.cinecred.drawer.SYSTEM_FONTS
import java.awt.Font
import java.util.*
import kotlin.math.abs
import com.loadingbyte.cinecred.common.getStrings as getStringsReflect
import com.loadingbyte.cinecred.common.getWeight as getWeightReflect
import com.loadingbyte.cinecred.common.getWidth as getWidthReflect
import com.loadingbyte.cinecred.common.isItalic2D as isItalic2DReflect


val BUNDLED_FAMILIES: FontFamilies = FontFamilies(BUNDLED_FONTS)
val SYSTEM_FAMILIES: FontFamilies = FontFamilies(SYSTEM_FONTS)


class FontFamily(
    private val family: Map<Locale, String>,
    private val subfamilies: Map<Font, Map<Locale, String>>,
    private val sampleTexts: Map<Font, Map<Locale, String>>,
    val fonts: List<Font>,
    val canonicalFont: Font
) {

    private val familyCache = HashMap<Locale, String?>()
    private val subfamilyCaches = IdentityHashMap<Font, MutableMap<Locale, String?>>()
    private val sampleTextCaches = IdentityHashMap<Font, MutableMap<Locale, String?>>()
    private val fontNameToFont = fonts.associateBy { it.getFontName(Locale.ROOT) }

    fun getFamily(locale: Locale = Locale.ROOT): String = accessCache(family, familyCache, locale)!!

    fun getSubfamilyOf(font: Font, locale: Locale = Locale.ROOT): String =
        accessCache(subfamilies.getValue(font), subfamilyCaches.computeIfAbsent(font) { HashMap() }, locale)!!

    fun getSampleTextOf(font: Font, locale: Locale = Locale.ROOT): String? =
        accessCache(sampleTexts.getValue(font), sampleTextCaches.computeIfAbsent(font) { HashMap() }, locale)

    private fun accessCache(localizations: Map<Locale, String>, cache: MutableMap<Locale, String?>, locale: Locale) =
        cache.computeIfAbsent(locale) {
            // This algorithm does not consider anything beyond the country, however, neither do fonts most of the time,
            // nor do we with our limited set of translated locales.
            val closestLocale =
                localizations.keys.find { it.language == locale.language && it.country == locale.country }
                    ?: localizations.keys.find { it.language == locale.language }
                    ?: Locale.ROOT
            localizations[closestLocale]
        }

    fun getFont(fontName: String): Font? = fontNameToFont[fontName]

}


class FontFamilies(fonts: Iterable<Font>) {

    val list: List<FontFamily>
    private val fontToFamily: Map<Font, FontFamily>
    private val fontNameToFamily: Map<String, FontFamily>

    fun getFamily(font: Font): FontFamily? = fontToFamily[font]
    fun getFamily(fontName: String): FontFamily? = fontNameToFamily[fontName]

    init {
        list = object : FontSorter<Font, FontFamily>() {
            override fun Font.getWeight() = getWeightReflect()
            override fun Font.getWidth() = getWidthReflect()
            override fun Font.isItalic2D() = isItalic2DReflect()
            override fun Font.getStrings() = getStringsReflect()
            override fun makeFamily(
                family: Map<Locale, String>,
                subfamilies: Map<Font, Map<Locale, String>>,
                sampleTexts: Map<Font, Map<Locale, String>>,
                fonts: List<Font>,
                canonicalFont: Font
            ) = FontFamily(family, subfamilies, sampleTexts, fonts, canonicalFont)
        }.sort(fonts)
        fontToFamily = HashMap()
        fontNameToFamily = HashMap()
        for (family in list)
            for (font in family.fonts) {
                fontToFamily[font] = family
                fontNameToFamily[font.getFontName(Locale.ROOT)] = family
            }
    }

}


// Public for testing. This is a class for historical reasons, to not pollute the VCS diffs too much.
abstract class FontSorter<Font, Family> {

    abstract fun Font.getWeight(): Int
    abstract fun Font.getWidth(): Int
    abstract fun Font.isItalic2D(): Boolean
    abstract fun Font.getStrings(): FontStrings

    abstract fun makeFamily(
        family: Map<Locale, String>,
        subfamilies: Map<Font, Map<Locale, String>>,
        sampleTexts: Map<Font, Map<Locale, String>>,
        fonts: List<Font>,
        canonicalFont: Font
    ): Family


    fun sort(fonts: Iterable<Font>): List<Family> {
        // We use a tree map so that the families will be sorted by name, and tree sets as values so that the fonts of
        // a family will be sorted by weight, width, and slope (WWS).
        val rootFamilyToRichFonts = TreeMap<String, TreeSet<RichFont>>()
        val richFontComparator = Comparator
            .comparingInt(FontSorter<Font, Family>.RichFont::width)
            .thenComparingInt(FontSorter<Font, Family>.RichFont::weight)
            .thenComparing(FontSorter<Font, Family>.RichFont::slope)
            .thenComparing(FontSorter<Font, Family>.RichFont::rootSubfamily)

        // Step 1: Put all fonts into families based on the root (often English) naming information, preferably based on
        // the typographic families (previously "preferred families"), which are already split into family and
        // subfamily, and alternatively based on the legacy families, or even the full names which we have to split
        // ourselves. Also determine the weight, width, and slope (WWS) of each font to then order the fonts accordingly
        // inside their respective family.
        for (font in fonts) {
            val (rootLocale, rootFamily, rootSubfamily, styles) =
                getNamesAndStylesFromFamNames(font, typo = true) { it == Locale.US }
                    ?: getNamesAndStylesFromFamNames(font, typo = true) { it.language == "en" }
                    ?: getNamesAndStylesFromFamNames(font, typo = false) { it == Locale.US }
                    ?: getNamesAndStylesFromFamNames(font, typo = false) { it.language == "en" }
                    ?: getNamesAndStylesFromFullName(font) { it == Locale.US }
                    ?: getNamesAndStylesFromFullName(font) { it.language == "en" }
                    ?: getNamesAndStylesFromFamNames(font, typo = true) { true }
                    ?: getNamesAndStylesFromFamNames(font, typo = false) { true }
                    ?: getNamesAndStylesFromFullName(font) { true }!!

            var weight: Int? = null
            var width: Int? = null
            var slope: Boolean? = null
            // Try determining the WWS values from the style information extracted from the font name, which is often
            // more accurate than the OS/2 table, seemingly because fonts have to consider legacy implementations.
            for (style in styles) {
                weight = style.weight ?: weight
                width = style.width ?: width
                slope = style.slope ?: slope
            }
            // Only if that fails, fall back to the OS/2 table, or even to default values defined in Font2D.
            if (weight == null)
                weight = font.getWeight()
            if (width == null)
                width = font.getWidth()
            if (slope == null)
                slope = font.isItalic2D()

            rootFamilyToRichFonts
                .computeIfAbsent(rootFamily) { TreeSet(richFontComparator) }
                .add(RichFont(font, rootLocale, rootSubfamily, weight, width, slope))
        }

        // Step 2: For each family, find a localized family and localized subfamilies for each font. Also determine the
        // sample texts and the canonical font.
        return rootFamilyToRichFonts.map { (rootFamily, richFonts) ->
            val family = getLocalizedFamilies(richFonts, rootFamily)
            val subfamilies = richFonts.associate { it.font to getLocalizedSubfamilies(it, family) }

            val sampleTexts = richFonts.associate { richFont ->
                val sampleText = HashMap(richFont.font.getStrings().sampleText)
                if (sampleText.isNotEmpty()) {
                    // Add a root locale we can fall back to.
                    val locs = sampleText.keys
                    sampleText[Locale.ROOT] =
                        sampleText[locs.find { it == Locale.US } ?: locs.find { it.language == "en" } ?: locs.first()]
                }
                richFont.font to sampleText
            }

            // Finally, find the canonical font, i.e., the font selected by default when choosing a family. Do this by
            // assigning penalty points to each font according to how much it differs from a regular font, and selecting
            // the one which received the smallest penalty.
            val canonicalFont =
                richFonts.minByOrNull { abs(it.weight / 100f - 4) + abs(it.width - 5) + if (it.slope) 3 else 0 }!!.font

            makeFamily(family, subfamilies, sampleTexts, richFonts.map { it.font }, canonicalFont)
        }
    }

    /** Tries to determine the family, subfamily, and style information from the full font name alone. */
    private fun getNamesAndStylesFromFullName(font: Font, filter: (Locale) -> Boolean): NamesAndStyles? {
        val (locale, fullName) = font.getStrings().fullName.entries.find { filter(it.key) } ?: return null
        val (family, styles) = removeStyleSuffixes(fullName)
        val subfamily = fullName.substring(family.length).trimStart(*SUFFIX_SEPARATORS)
        return NamesAndStyles(locale, family, subfamily, styles)
    }

    /** Tries to determine the family, subfamily, and style information from the (typographic) family and subfamily. */
    private fun getNamesAndStylesFromFamNames(font: Font, typo: Boolean, filter: (Locale) -> Boolean): NamesAndStyles? {
        val strings = font.getStrings()
        val locale: Locale
        val rawFamily: String
        val rawSubfamily: String
        if (typo) {
            rawFamily = (strings.typographicFamily.entries.find { filter(it.key) } ?: return null).value
            (strings.typographicSubfamily.entries.find { filter(it.key) } ?: return null)
                .run { locale = key; rawSubfamily = value }
        } else {
            rawFamily = (strings.family.entries.find { filter(it.key) } ?: return null).value
            (strings.subfamily.entries.find { filter(it.key) } ?: return null)
                .run { locale = key; rawSubfamily = value }
        }
        // Do not take the family/subfamily split supplied by the font at face value because many font still provide
        // subfamily information in their family (think font width like "Condensed"). Instead, manually remove any
        // leftover subfamily information from the family.
        val (family, styles1) = removeStyleSuffixes(rawFamily)
        // And of course determine all styles indicated in the subfamily.
        val (_, styles2) = removeStyleSuffixes(rawSubfamily)
        // If we have indeed pruned some subfamily information from the family, prepend that to the actual subfamily.
        val familyRest = rawFamily.substring(family.length).trimStart(*SUFFIX_SEPARATORS)
        val subfamily = if (familyRest.isNotEmpty()) "$familyRest $rawSubfamily" else rawSubfamily
        return NamesAndStyles(locale, family, subfamily, styles1 + styles2)
    }

    /**
     * Removes style-indicating suffixes (like "Bold") from the given string until no more can be found. Suffixes must
     * be separated by some char from the preceding string; thereby we avoid accidentally pruning the actual font name.
     * Also returns the font styles indicated by the removed suffixes.
     */
    private fun removeStyleSuffixes(str: String): Pair<String, List<FontStyle>> {
        // Add a fake separator to the beginning to ensure that the whole string can be consumed if it's all suffixes.
        var string = SUFFIX_SEPARATORS[0] + str
        val styles = mutableListOf<FontStyle>()
        outer@ while (true) {
            for ((suffixWithSep, style) in EXTENDED_SUFFIX_TO_STYLE)
                if (string.endsWith(suffixWithSep, ignoreCase = true)) {
                    string = string.dropLast(suffixWithSep.length).trimEnd()
                    styles.add(style)
                    continue@outer
                }
            // Remove the fake separator we added to the beginning of the string.
            if (string.isNotEmpty())
                string = string.substring(1)
            return Pair(string, styles)
        }
    }

    private fun getLocalizedFamilies(richFonts: Set<RichFont>, rootFamily: String): Map<Locale, String> {
        // Usually, this contains just one locale, mostly US. However, some font families screw up and sometimes specify
        // their main family name under the English locale and sometimes under another locale. This leaves us with
        // multiple locales that in fact contain the same language. Hence, in these rare cases, there can be multiple
        // root locales.
        val rootLocales = richFonts.mapTo(HashSet()) { it.rootLocale }

        // Set up the localized family map and already insert the root family (often English) both under its locale and
        // the root locales, which will be used as fallbacks.
        val family = hashMapOf<Locale, String>(Locale.ROOT to rootFamily)
        for (rootLocale in rootLocales)
            family[rootLocale] = rootFamily

        // Find all locales for which a (typographic) family or full name is provided by some font. These are the
        // locales for which we can potentially find localized families. Quicken the process by dropping the root
        // locales as they have already been inserted.
        val locales = richFonts.flatMapTo(HashSet()) { richFont ->
            val strings = richFont.font.getStrings()
            strings.typographicFamily.keys + strings.family.keys + strings.fullName.keys
        }
        locales.removeAll(rootLocales)

        // Find the common prefix shared by the localized names of all fonts. This we will use as the localized family.
        // Even when the typographic family string is broadly available, we still cannot just pull it from one random
        // font and then use it as the localized family as it might wrongly include subfamily information like the font
        // width (think "Condensed"). By taking the common prefix, we ensure that there's no subfamily information left
        // in the family name, at least none that differs between fonts.
        for (locale in locales) {
            var commonPrefix: String? = null
            for (richFont in richFonts) {
                val strs = richFont.font.getStrings()
                // If the typographic family is unavailable in the given locale, fall back to the legacy family, or even
                // the full name, which should start with the family as well.
                val locFam = strs.typographicFamily[locale] ?: strs.family[locale] ?: strs.fullName[locale] ?: continue
                commonPrefix = if (commonPrefix == null) locFam else commonPrefix.commonPrefixWith(locFam)
            }
            commonPrefix = commonPrefix!!.trimEnd(*SUFFIX_SEPARATORS)
            // If the localized family is the same as the root family, we can assume that it is identical (plus maybe
            // some accidental subfamily information which we do not want anyway), so we can drop this locale.
            if (commonPrefix.isNotEmpty() && !commonPrefix.startsWith(rootFamily))
                family[locale] = commonPrefix
        }

        return family
    }

    private fun getLocalizedSubfamilies(richFont: RichFont, family: Map<Locale, String>): Map<Locale, String> {
        // Find all locales for which a (typographic) subfamily or full name is provided by some font. These are the
        // locales for which we can potentially find localized subfamilies.
        val strings = richFont.font.getStrings()
        val locales = strings.typographicSubfamily.keys + strings.subfamily.keys + strings.fullName.keys

        // Set up the localized subfamily map and once again already insert the root family (often English).
        val subfamily = hashMapOf(Locale.ROOT to richFont.rootSubfamily, richFont.rootLocale to richFont.rootSubfamily)

        for (locale in locales) {
            // Find the localized family which best matches the requested locale. Do not consider anything beyond the
            // country information, as that is rarely used by fonts.
            val closestFamilyLocale =
                family.keys.find { it.language == locale.language && it.country == locale.country }
                    ?: family.keys.find { it.language == locale.language }
                    ?: Locale.ROOT
            val prefix = family.getValue(closestFamilyLocale)

            // Find the localized subfamily by taking the localized (typographic or legacy) "family + subfamily" from
            // the font and cutting off the closest localized true family as determined by us ("prefix").
            //   - If the family string is not actually localized by the font, relax the localization requirement.
            //   - If the subfamily string is not actually localized by the font, fall back to the localized full name.
            //   - If there is no localized name at all, do not record a localized subfamily.
            // If for some reason the localized string pulled from the font does not start with the localized family
            // determined by us, there is something strange going on with the font, and by virtue of using
            // removePrefix() we just keep everything as the localized subfamily.
            fun concatLocFamilyAndSubfamily(familyFilter: (Locale) -> Boolean): String? {
                val typoFamily = strings.typographicFamily.entries.find { familyFilter(it.key) }?.value
                val typoSubfamily = strings.typographicSubfamily[locale]
                if (typoFamily != null && typoSubfamily != null)
                    return "$typoFamily $typoSubfamily"
                val legacyFamily = strings.family.entries.find { familyFilter(it.key) }?.value
                val legacySubfamily = strings.subfamily[locale]
                if (legacyFamily != null && legacySubfamily != null)
                    return "$legacyFamily $legacySubfamily"
                return null
            }

            val familyPlusSubfamily =
                concatLocFamilyAndSubfamily { it == locale }
                    ?: concatLocFamilyAndSubfamily { it.language == locale.language && it.country == locale.country }
                    ?: concatLocFamilyAndSubfamily { it.language == locale.language }
                    ?: concatLocFamilyAndSubfamily { true }
                    ?: strings.fullName[locale]
                    ?: continue

            subfamily[locale] = familyPlusSubfamily.removePrefix(prefix).trimStart(*SUFFIX_SEPARATORS)
        }

        return subfamily
    }


    companion object {

        private val SUFFIX_TO_STYLE = listOf(
            "Bd" to FontStyle(weight = 700),
            "BdIta" to FontStyle(weight = 700, slope = true),
            "Bk" to FontStyle(weight = 900),
            "Black" to FontStyle(weight = 900),
            "Blk" to FontStyle(weight = 900),
            "Book" to FontStyle(weight = 400),
            "BookItalic" to FontStyle(weight = 400, slope = true),
            "Bold" to FontStyle(weight = 700),
            "BoldItalic" to FontStyle(weight = 700, slope = true),
            "BoldOblique" to FontStyle(weight = 700, slope = true),
            "Compressed" to FontStyle(width = 2),
            "Cond" to FontStyle(width = 3),
            "Condensed" to FontStyle(width = 3),
            "DemBd" to FontStyle(weight = 600),
            "Demi" to FontStyle(weight = 600),
            "DemiBold" to FontStyle(weight = 600),
            "DemiItalic" to FontStyle(weight = 600, slope = true),
            "DemiLight" to FontStyle(weight = 350),  // used by Noto Sans CJK
            "DemiOblique" to FontStyle(weight = 600, slope = true),
            "ExBold" to FontStyle(weight = 800),
            "Expanded" to FontStyle(width = 7),
            "ExtBd" to FontStyle(weight = 800),
            "Extra" to FontStyle(weight = 800),
            "Extra Condensed" to FontStyle(width = 2),
            "Extra Expanded" to FontStyle(width = 8),
            "ExtraBold" to FontStyle(weight = 800),
            "ExtraLight" to FontStyle(weight = 200),
            "Hairline" to FontStyle(weight = 100),
            "Heavy" to FontStyle(weight = 900),
            "HeavyItalic" to FontStyle(weight = 900, slope = true),
            "Italic" to FontStyle(slope = true),
            "Light" to FontStyle(weight = 300),
            "LightItalic" to FontStyle(weight = 300, slope = true),
            "Lt" to FontStyle(weight = 300),
            "Md" to FontStyle(weight = 500),
            "Med" to FontStyle(weight = 500),
            "Medium" to FontStyle(weight = 500),
            "MediumItalic" to FontStyle(weight = 500, slope = true),
            "Narrow" to FontStyle(width = 4),
            "Normal" to FontStyle(),
            "Oblique" to FontStyle(slope = true),
            "Plain" to FontStyle(),
            "Regular" to FontStyle(),
            "RegularItalic" to FontStyle(slope = true),
            "SemBd" to FontStyle(weight = 600),
            "Semi" to FontStyle(weight = 600),
            "Semi Condensed" to FontStyle(width = 4),
            "Semi Expanded" to FontStyle(width = 6),
            "SemiBold" to FontStyle(weight = 600),
            "SemiBoldItalic" to FontStyle(weight = 600, slope = true),
            "Th" to FontStyle(weight = 100),
            "Thin" to FontStyle(weight = 100),
            "Ultra" to FontStyle(weight = 800),
            "Ultra Condensed" to FontStyle(width = 1),
            "Ultra Expanded" to FontStyle(width = 9),
            "UltraBold" to FontStyle(weight = 800),
            "UltraLight" to FontStyle(weight = 200),
            "Upright" to FontStyle(slope = false)
        )

        private val SUFFIX_SEPARATORS = charArrayOf(' ', '-', '.')

        private val EXTENDED_SUFFIX_TO_STYLE = buildList {
            for ((suffix, style) in SUFFIX_TO_STYLE.sortedByDescending { it.first.length })
                for (sep in SUFFIX_SEPARATORS)
                    if (' ' in suffix)
                        for (sep2 in SUFFIX_SEPARATORS)
                            add(sep + suffix.replace(' ', sep2) to style)
                    else
                        add(sep + suffix to style)
        }

    }


    private class FontStyle(val weight: Int? = null, val width: Int? = null, val slope: Boolean? = null)

    private inner class RichFont(
        val font: Font,
        val rootLocale: Locale, val rootSubfamily: String,
        val weight: Int, val width: Int, val slope: Boolean
    )

    private data class NamesAndStyles(
        val locale: Locale, val family: String, val subfamily: String,
        val styles: List<FontStyle>
    )

}
