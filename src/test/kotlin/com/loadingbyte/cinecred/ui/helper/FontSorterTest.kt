package com.loadingbyte.cinecred.ui.helper

import com.loadingbyte.cinecred.common.FontStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.*
import java.util.Locale.*


internal class FontSorterTest {

    @Test
    fun sort() {
        val expectedFamilies = makeExpectedFamilies()

        val fonts = expectedFamilies.flatMap(Family::fonts).shuffled(Random(42))
        val actualFamilies = TestFontSorter().sort(fonts)
        assertEquals(PrettyList(expectedFamilies), PrettyList(actualFamilies))
        for (font in fonts)
            font.assertPopped()
    }

    private class TestFontSorter : FontSorter<Font, Family>() {
        override fun Font.getWeight() = popWeight()
        override fun Font.getWidth() = popWidth()
        override fun Font.isItalic2D() = popItalic()
        override fun Font.getStrings() = strings
        override fun makeFamily(
            family: Map<Locale, String>,
            subfamilies: Map<Font, Map<Locale, String>>,
            sampleTexts: Map<Font, Map<Locale, String>>,
            fonts: List<Font>,
            canonicalFont: Font
        ) = Family(family, subfamilies, sampleTexts, fonts, canonicalFont)
    }

    private class PrettyList<E>(private val backing: List<E>) {
        override fun toString() = pretty(backing)
        override fun equals(other: Any?) = if (other !is PrettyList<*>) false else backing == other.backing
        override fun hashCode() = throw UnsupportedOperationException()
    }

}


// Extracted into top-level function to gain horizontal space.
private fun makeExpectedFamilies() = listOf(
    // Full name only
    Family(
        fonts = listOf(
            Font(full = mapOf(US to "CineSans Narrow", DE to "CineSans Schmal"), b = 400, i = F),
            Font(full = mapOf(US to "CineSans Narrow Bold", DE to "CineSans Schmal Fett"), i = F),
            Font(full = mapOf(US to "CineSans Normal"), w = 5, b = 400, i = F, canon = T),
            Font(full = mapOf(US to "CineSans Italic", DE to "CineSans Kursiv"), w = 5, b = 400),
            Font(full = mapOf(US to "CineSans Bold", DE to "CineSans Fett"), w = 5, i = F),
            Font(full = mapOf(US to "CineSans Bold Italic", DE to "CineSans Fett Kursiv"), w = 5)
        ),
        family = outStrs(US, US to "CineSans"),
        subfamilies = listOf(
            outStrs(US, US to "Narrow", DE to "Schmal"),
            outStrs(US, US to "Narrow Bold", DE to "Schmal Fett"),
            outStrs(US, US to "Normal"),
            outStrs(US, US to "Italic", DE to "Kursiv"),
            outStrs(US, US to "Bold", DE to "Fett"),
            outStrs(US, US to "Bold Italic", DE to "Fett Kursiv")
        )
    ),
    // Full name only w/ language confusion and no regular-width font
    Family(
        fonts = listOf(
            Font(full = mapOf(UK to "CredSans Expanded"), b = 400, i = F, canon = true),  // Non-US English locale
            Font(full = mapOf(DE to "CredSans Expanded Italic"), b = 400),  // Wrong locale, but English name
        ),
        family = outStrs(UK, UK to "CredSans", DE to "CredSans"),
        subfamilies = listOf(
            outStrs(UK, UK to "Expanded"),
            outStrs(DE, DE to "Expanded Italic"),
            outStrs(DE, DE to "Gestreckt Fett")
        )
    ),
    // Legacy family & subfamily only
    Family(
        fonts = listOf(
            Font(fam = mapOf(US to "Epic Cond", DE to "Epic Kond"), sub = mapOf(US to "Normal"), b = 400, i = F),
            Font(fam = mapOf(US to "Epic Cond", DE to "Epic Kond"), sub = mapOf(US to "Oblique", DE to "Kur"), b = 400),
            Font(fam = mapOf(US to "Epic Cond"), sub = mapOf(US to "Bold", DE to "Fett"), i = F),
            Font(fam = mapOf(US to "Epic"), sub = mapOf(US to "Normal"), w = 5, b = 400, i = F, canon = T),
            Font(fam = mapOf(US to "Epic"), sub = mapOf(US to "Oblique", DE to "Schräg"), w = 5, b = 400),
            Font(fam = mapOf(US to "Epic"), sub = mapOf(US to "Bold", DE to "Fett"), w = 5, i = F),
            Font(fam = mapOf(US to "Epic"), sub = mapOf(US to "BoldOblique", DE to "FettSchräg"), w = 5)
        ),
        family = outStrs(US, US to "Epic"),
        subfamilies = listOf(
            outStrs(US, US to "Cond Normal", DE to "Kond Normal"),
            outStrs(US, US to "Cond Oblique", DE to "Kond Kur"),
            outStrs(US, US to "Cond Bold", DE to "Cond Fett"),
            outStrs(US, US to "Normal"),
            outStrs(US, US to "Oblique", DE to "Schräg"),
            outStrs(US, US to "Bold", DE to "Fett"),
            outStrs(US, US to "BoldOblique", DE to "FettSchräg")
        )
    ),
    // Legacy family & subfamily only w/ language confusion
    Family(
        fonts = listOf(
            Font(fam = mapOf(DE to "Expo"), sub = mapOf(US to "Normal"), w = 5, b = 400, i = F, canon = T),
            Font(fam = mapOf(US to "Expo"), sub = mapOf(DE to "Schräg"), w = 5, b = 400, i = T),
            Font(fam = mapOf(DE to "Expo"), sub = mapOf(DE to "Fett"), w = 5, b = 700, i = F),
            Font(fam = mapOf(US to "Expo"), sub = mapOf(US to "Black"), w = 5, i = F)
        ),
        family = outStrs(US, US to "Expo", DE to "Expo"),
        subfamilies = listOf(
            outStrs(US, US to "Normal", DE to "Normal"),
            outStrs(DE, US to "Schräg", DE to "Schräg"),
            outStrs(DE, DE to "Fett"),
            outStrs(US, US to "Black")
        )
    ),
    // Typographic family & subfamily only
    Family(
        fonts = listOf(
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Book", DE to "Normal"), w = 5, i = F, canon = T),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Book Italic"), w = 5),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Semi Bold", DE to "Halbfett"), w = 5, i = F),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Bold", DE to "Fett"), w = 5, i = F),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Bold Italic"), w = 5),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Extra Bold", DE to "Extrafett"), w = 5, i = F),
            Font(tfam = mapOf(US to "Foo"), tsub = mapOf(US to "Expanded", DE to "Gestreckt"), b = 400, i = F),
            Font(tfam = mapOf(US to "Foo Expanded", DE to "Foo Str"), tsub = mapOf(US to "Bold", DE to "Fett"), i = F)
        ),
        family = outStrs(US, US to "Foo"),
        subfamilies = listOf(
            outStrs(US, US to "Book", DE to "Normal"),
            outStrs(US, US to "Book Italic"),
            outStrs(US, US to "Semi Bold", DE to "Halbfett"),
            outStrs(US, US to "Bold", DE to "Fett"),
            outStrs(US, US to "Bold Italic"),
            outStrs(US, US to "Extra Bold", DE to "Extrafett"),
            outStrs(US, US to "Expanded", DE to "Gestreckt"),
            outStrs(US, US to "Expanded Bold", DE to "Str Fett")
        )
    ),
    // Typographic family & subfamily only w/ no English name
    Family(
        fonts = listOf(
            Font(tfam = mapOf(DE to "Fun"), tsub = mapOf(DE to "Regulär"), w = 5, b = 400, i = F, canon = T),
            Font(tfam = mapOf(DE to "Fun"), tsub = mapOf(DE to "Regulär Kursiv"), w = 5, b = 400, i = T),
            Font(tfam = mapOf(DE to "Fun"), tsub = mapOf(DE to "Fett"), w = 5, b = 700, i = F),
        ),
        family = outStrs(DE, DE to "Fun"),
        subfamilies = listOf(
            outStrs(DE, DE to "Regulär"),
            outStrs(DE, DE to "Regulär Kursiv"),
            outStrs(DE, DE to "Fett"),
        )
    ),
    // The real-world Mitra Mono font:
    Family(
        fonts = listOf(
            Font(
                full = mapOf(US to "Mitra Mono", Locale("bn", "IN") to "SomethingElseEntirely"),
                fam = mapOf(US to "Mitra"), sub = mapOf(US to "Regular"), w = 5, b = 400, i = F, canon = T
            )
        ),
        family = outStrs(US, US to "Mitra", Locale("bn", "IN") to "SomethingElseEntirely"),
        subfamilies = listOf(outStrs(US, US to "Regular"))
    ),
    // The real-world Courier New font (abbreviated to New):
    Family(
        fonts = listOf(
            Font(
                full = mapOf(US to "New"), fam = mapOf(US to "New"), sub = mapOf(US to "Regular", DE to "Standard"),
                w = 5, b = 400, i = F, canon = T
            ), Font(
                full = mapOf(US to "New Italic", DE to "New Kursiv"), fam = mapOf(US to "New"),
                sub = mapOf(US to "Italic", DE to "Kursiv"), w = 5, b = 400
            ), Font(
                full = mapOf(US to "New Bold Italic", DE to "New Fett Kursiv"), fam = mapOf(US to "New"),
                sub = mapOf(US to "Bold Italic", DE to "Fett Kursiv"), w = 5
            )
        ),
        family = outStrs(US, US to "New"),
        subfamilies = listOf(
            outStrs(US, US to "Regular", DE to "Standard"),
            outStrs(US, US to "Italic", DE to "Kursiv"),
            outStrs(US, US to "Bold Italic", DE to "Fett Kursiv")
        )
    ),
    // The real-world Oswald font (abbreviated to Osw), which has confusing naming:
    Family(
        fonts = listOf(
            Font(
                full = mapOf(US to "Osw Regular"), fam = mapOf(US to "Osw Regular"), sub = mapOf(US to "Regular"),
                w = 5, b = 400, i = F, canon = T
            ),
            Font(
                full = mapOf(US to "Osw RegularItalic"), fam = mapOf(US to "Osw"), sub = mapOf(US to "Regular"),
                w = 5, b = 400
            ),
            Font(
                full = mapOf(US to "Osw Demi-BoldItalic"), fam = mapOf(US to "Osw"), sub = mapOf(US to "Demi-Bold"),
                w = 5
            ),
            Font(full = mapOf(US to "Osw Bold"), fam = mapOf(US to "Osw"), sub = mapOf(US to "Bold"), w = 5, i = F),
            Font(full = mapOf(US to "Osw BoldItalic"), fam = mapOf(US to "Osw"), sub = mapOf(US to "Bold"), w = 5)
        ),
        family = outStrs(US, US to "Osw"),
        subfamilies = listOf(
            outStrs(US, US to "Regular"),
            outStrs(US, US to "RegularItalic"),
            outStrs(US, US to "Demi-BoldItalic"),
            outStrs(US, US to "Bold"),
            outStrs(US, US to "BoldItalic")
        )
    )
)


private const val T = true
private const val F = false
private val DE = GERMAN

private fun outStrs(rootLocale: Locale, vararg pairs: Pair<Locale, String>) =
    mutableMapOf(*pairs).apply { put(ROOT, getValue(rootLocale)) }


private fun pretty(m: Map<*, *>) = if (m.isEmpty()) "{}" else
    "{\n" + m.entries.joinToString("\n") { it.toString().prependIndent("  ") } + "\n}"

private fun pretty(l: List<*>) = if (l.isEmpty()) "[]" else
    "[\n" + l.joinToString(",\n") { it.toString().prependIndent("  ") } + "\n]"


private class Font(
    full: Map<Locale, String> = emptyMap(),
    fam: Map<Locale, String> = emptyMap(),
    sub: Map<Locale, String> = emptyMap(),
    tfam: Map<Locale, String> = emptyMap(),
    tsub: Map<Locale, String> = emptyMap(),
    sample: Map<Locale, String> = emptyMap(),
    private var w: Int? = null,
    private var b: Int? = null,
    private var i: Boolean? = null,
    val canon: Boolean = false
) {

    val strings = FontStrings(fam, sub, full, tfam, tsub, sample)

    fun popWidth() = w.also { w = null } ?: failPop("its width")
    fun popWeight() = b.also { b = null } ?: failPop("its weight")
    fun popItalic() = i.also { i = null } ?: failPop("whether it is italic")
    private fun failPop(what: String): Nothing = fail("Font $this should not be asked for $what")

    fun assertPopped() {
        assertNull(w) { "Font $this has not been asked for its width" }
        assertNull(b) { "Font $this has not been asked for its weight" }
        assertNull(i) { "Font $this has not been asked for whether it is italic" }
    }

    override fun toString(): String {
        val full = strings.fullName[US] ?: strings.fullName.values.firstOrNull()
        if (full != null) return full
        val fam = strings.family[US] ?: strings.family.values.firstOrNull()
        val sub = strings.subfamily[US] ?: strings.subfamily.values.firstOrNull()
        if (fam != null && sub != null) return "$fam $sub"
        val tfam = strings.typographicFamily[US] ?: strings.typographicFamily.values.firstOrNull()
        val tsub = strings.typographicSubfamily[US] ?: strings.typographicSubfamily.values.firstOrNull()
        return "$tfam $tsub"
    }

}


private data class Family(
    val family: Map<Locale, String>,
    val subfamilies: Map<Font, Map<Locale, String>>,
    val sampleTexts: Map<Font, Map<Locale, String>>,
    val fonts: List<Font>,
    val canonicalFont: Font,
    val marker: Boolean
) {

    constructor(
        family: Map<Locale, String>,
        subfamilies: Map<Font, Map<Locale, String>>,
        sampleTexts: Map<Font, Map<Locale, String>>,
        fonts: List<Font>,
        canonicalFont: Font
    ) : this(
        makeLocaleTreeMap(family),
        subfamilies.mapValues { (_, v) -> makeLocaleTreeMap(v) },
        sampleTexts.mapValues { (_, v) -> makeLocaleTreeMap(v) },
        fonts, canonicalFont, true
    )

    constructor(
        fonts: List<Font>,
        family: Map<Locale, String>,
        subfamilies: List<Map<Locale, String>>,
        sampleTexts: List<Map<Locale, String>>? = null
    ) : this(
        family,
        fonts.zip(subfamilies).toMap(),
        if (sampleTexts != null) fonts.zip(sampleTexts).toMap() else fonts.associateWith { emptyMap() },
        fonts,
        fonts.single(Font::canon)
    )

    override fun toString() = "Family(\n" +
            "  family=$family,\n" +
            "  subfamilies=${pretty(subfamilies).prependIndent("  ").substring(2)},\n" +
            "  sampleTexts=${pretty(sampleTexts).prependIndent("  ").substring(2)},\n" +
            "  fonts=$fonts,\n" +
            "  canonicalFont=$canonicalFont\n" +
            ")"

    companion object {
        private fun <V> makeLocaleTreeMap(map: Map<Locale, V>? = null) =
            TreeMap<Locale, V>(Comparator.comparing(Locale::getLanguage).thenComparing(Locale::getCountry))
                .also { map?.let(it::putAll) }
    }

}

