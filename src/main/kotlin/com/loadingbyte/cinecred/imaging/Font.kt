package com.loadingbyte.cinecred.imaging

import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.natives.harfbuzz.*
import com.loadingbyte.cinecred.natives.harfbuzz.hb_h.*
import sun.font.Script
import java.awt.GraphicsEnvironment
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.MemorySegment.NULL
import java.lang.foreign.ValueLayout.*
import java.lang.ref.WeakReference
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries


class Font private constructor(private val hbFace: MemorySegment) {

    val name: String = lookupName(HB_OT_NAME_ID_FULL_NAME(), HB_LANGUAGE_INVALID())
    val family: String = lookupName(HB_OT_NAME_ID_FONT_FAMILY(), HB_LANGUAGE_INVALID())

    val familyMap: Map<Locale, String>
    val subfamilyMap: Map<Locale, String>
    val fullNameMap: Map<Locale, String>
    val typographicFamilyMap: Map<Locale, String>
    val typographicSubfamilyMap: Map<Locale, String>
    val sampleTextMap: Map<Locale, String>

    val axes: List<Axis>
    val supportedFeatures: SortedSet<String>

    init {
        CLEANER.register(this, FontCleanerAction(hbFace))

        require(name.isNotBlank()) { "Font has no standard name." }

        // Prepare the standard name maps, and a lookup from name ID to name map.
        familyMap = HashMap()
        subfamilyMap = HashMap()
        fullNameMap = HashMap()
        typographicFamilyMap = HashMap()
        typographicSubfamilyMap = HashMap()
        sampleTextMap = HashMap()
        val nameMaps = hashMapOf(
            HB_OT_NAME_ID_FONT_FAMILY() to familyMap,
            HB_OT_NAME_ID_FONT_SUBFAMILY() to subfamilyMap,
            HB_OT_NAME_ID_FULL_NAME() to fullNameMap,
            HB_OT_NAME_ID_TYPOGRAPHIC_FAMILY() to typographicFamilyMap,
            HB_OT_NAME_ID_TYPOGRAPHIC_SUBFAMILY() to typographicSubfamilyMap,
            HB_OT_NAME_ID_SAMPLE_TEXT() to sampleTextMap
        )

        // Fill the axes list.
        axes = mutableListOf()
        Arena.ofConfined().use { arena ->
            val count = hb_ot_var_get_axis_count(hbFace)
            if (count > 0) {
                val cCount = arena.allocateFrom(JAVA_INT, count)
                val cInfos = hb_ot_var_axis_info_t.allocateArray(count.toLong(), arena)
                hb_ot_var_get_axis_infos(hbFace, 0, cCount, cInfos)
                for (idx in 0L..<cCount.get(JAVA_INT, 0)) {
                    val cInfo = hb_ot_var_axis_info_t.asSlice(cInfos, idx)
                    val defaultValue = hb_ot_var_axis_info_t.default_value(cInfo).toDouble()
                    val minValue = hb_ot_var_axis_info_t.min_value(cInfo).toDouble()
                    val maxValue = hb_ot_var_axis_info_t.max_value(cInfo).toDouble()
                    if (hb_ot_var_axis_info_t.flags(cInfo) and HB_OT_VAR_AXIS_FLAG_HIDDEN() == 0 &&
                        minValue < maxValue && defaultValue in minValue..maxValue
                    ) {
                        // This map fill be populated by the name listing code down below.
                        val axisNameMap = HashMap<Locale, String>()
                        nameMaps[hb_ot_var_axis_info_t.name_id(cInfo)] = axisNameMap
                        axes += Axis(
                            code2tag(hb_ot_var_axis_info_t.tag(cInfo)), axisNameMap, defaultValue, minValue, maxValue
                        )
                    }
                }
            }
        }

        // Fill the name maps.
        val hbNameEntries: MemorySegment
        val numNameEntries = Arena.ofConfined().use { arena ->
            val cCount = arena.allocateFrom(JAVA_INT, 0)
            hbNameEntries = hb_ot_name_list_names(hbFace, cCount)
            if (hbNameEntries == NULL) 0 else cCount.get(JAVA_INT, 0)
        }
        for (idx in 0L..<numNameEntries) {
            val hbNameEntry = hb_ot_name_entry_t.asSlice(hbNameEntries, idx)
            val nameId = hb_ot_name_entry_t.name_id(hbNameEntry)
            val language = hb_ot_name_entry_t.language(hbNameEntry)
            nameMaps[nameId]
                ?.put(Locale.forLanguageTag(hb_language_to_string(language).getString(0)), lookupName(nameId, language))
        }

        // Fill the supported features set.
        val supportedFeatures = TreeSet<String>()
        Arena.ofConfined().use { arena ->
            for (tableTag in intArrayOf(HB_OT_TAG_GSUB(), HB_OT_TAG_GPOS())) {
                val count = hb_ot_layout_table_get_feature_tags(hbFace, tableTag, 0, NULL, NULL)
                if (count == 0)
                    continue
                val cCount = arena.allocateFrom(JAVA_INT, count)
                var cTags = arena.allocate(JAVA_INT, count.toLong())
                hb_ot_layout_table_get_feature_tags(hbFace, tableTag, 0, cCount, cTags)
                for (idx in 0L..<cCount.get(JAVA_INT, 0))
                    supportedFeatures += code2tag(cTags.getAtIndex(JAVA_INT, idx))
            }
        }
        this.supportedFeatures = Collections.unmodifiableSortedSet(supportedFeatures)
    }

    private fun lookupName(nameId: Int, language: MemorySegment): String {
        Arena.ofConfined().use { arena ->
            val len = hb_ot_name_get_utf8(hbFace, nameId, language, NULL, NULL)
            if (len == 0)
                return ""
            val cSize = arena.allocateFrom(JAVA_INT, len + 1 /* account for null terminator */)
            val cStr = arena.allocate(len + 1L)
            hb_ot_name_get_utf8(hbFace, nameId, language, cSize, cStr)
            return cStr.getString(0)
        }
    }

    fun toByteArray(): ByteArray {
        Arena.ofConfined().use { arena ->
            val hbBlob = hb_face_reference_blob(hbFace)
            val cLen = arena.allocate(JAVA_INT)
            val array = hb_blob_get_data(hbBlob, cLen)
                .reinterpret(cLen.get(JAVA_INT, 0).toLong())
                .toArray(JAVA_BYTE)
            hb_blob_destroy(hbBlob)
            return array
        }
    }

    fun staticNonShapeableSubset(codepoints: Set<Int>, glyphs: Set<Int>, variations: Set<Variation>): Font? {
        val hbInput = hb_subset_input_create_or_fail()
        if (hbInput == NULL)
            return null

        // Note: Our current subsetting solution retains all glyph codes, and HarfBuzz inserts empty glyphs for codes
        // that are now unused. We could theoretically use hb_subset_input_old_to_new_glyph_mapping() to tightly repack
        // the glyph codes and thus get rid of the empty glyphs. Unfortunately, when doing that, we've observed a case
        // where a glyph just disappeared from the PDF output: namely the "a" glyph when using the font "Bitcount" with
        // the "Cursive" variation axis set to 1.

        val flags = HB_SUBSET_FLAGS_NO_HINTING() or HB_SUBSET_FLAGS_RETAIN_GIDS() or
                HB_SUBSET_FLAGS_NOTDEF_OUTLINE() or HB_SUBSET_FLAGS_NO_LAYOUT_CLOSURE()
        hb_subset_input_set_flags(hbInput, flags)

        val hbUnicodeSet = hb_subset_input_unicode_set(hbInput)
        for (codepoint in codepoints)
            hb_set_add(hbUnicodeSet, codepoint)

        val hbGlyphSet = hb_subset_input_glyph_set(hbInput)
        for (glyph in glyphs)
            hb_set_add(hbGlyphSet, glyph)

        hb_subset_input_pin_all_axes_to_default(hbInput, hbFace)
        for (variation in variations)
            hb_subset_input_pin_axis_location(hbInput, hbFace, tag2code(variation.tag), variation.value.toFloat())

        val hbDropTableSet = hb_subset_input_set(hbInput, HB_SUBSET_SETS_DROP_TABLE_TAG())
        hb_set_add(hbDropTableSet, HB_OT_TAG_GSUB())
        hb_set_add(hbDropTableSet, HB_OT_TAG_GPOS())
        hb_set_add(hbDropTableSet, HB_OT_TAG_GDEF())

        val newHbFace = hb_subset_or_fail(hbFace, hbInput)
        hb_subset_input_destroy(hbInput)
        return if (newHbFace == NULL) null else Font(newHbFace)
    }

    fun case(size: Double = 12.0, variations: Set<Variation> = emptySet()): Case {
        // The idea is to cache cases for a short duration, to make it extremely cheap to create them, and also keep
        // their internal glyph cache alive for a reasonable time. We don't need to cache them for a long time, since
        // they are still relatively cheap to construct.
        // By using weak references, we make sure that most cases don't make it to the old generation. And because cases
        // are so cheap to construct, we can just blindly clear the entire cache when it becomes too full.
        if (caseCache.size > 1000)
            caseCache.clear()
        val key = CaseKey(this, size, variations)
        caseCache[key]?.get()?.let { return it }
        val case = Case(this, size, variations, 0)
        caseCache[key] = WeakReference(case)
        return case
    }


    companion object {

        /** @throws Exception */
        fun read(file: Path): List<Font> {
            // Note: We use shared arenas because the cleaner thread will call the close() method.
            val arena = Arena.ofShared()

            val hbBlob: MemorySegment
            try {
                // Read the file into an off-heap MemorySegment. Note that we don't mmap the file, because the user might
                // change it under us.
                val seg: MemorySegment
                FileChannel.open(file, StandardOpenOption.READ).use { fc ->
                    seg = arena.allocate(fc.size())
                    val buf = seg.asByteBuffer()
                    while (fc.read(buf) > 0);
                }

                // Create a blob that wraps the file data. When the blob's reference count reaches zero, free the data.
                val destroyBlobFunc = hb_destroy_func_t.allocate({
                    try {
                        arena.close()
                    } catch (t: Throwable) {
                        // We have to catch all exceptions because if one escapes, a segfault happens.
                        runCatching { LOGGER.error("Cannot close native memory resource scope", t) }
                    }
                }, arena)
                hbBlob =
                    hb_blob_create(seg, seg.byteSize().toInt(), HB_MEMORY_MODE_WRITABLE(), NULL, destroyBlobFunc)
            } catch (t: Throwable) {
                arena.close()
                throw t
            }

            // Create fonts from the blob. Notice that each new font increments the blob's reference count.
            // When a font is collected by the GC, it's destroyed, which will also decrement the blob's reference count.
            val fonts = buildList {
                for (idx in 0..<hb_face_count(hbBlob)) {
                    val hbFace = hb_face_create_or_fail(hbBlob, idx)
                    if (hbFace != NULL)
                        add(Font(hbFace))
                }
            }

            // Now decrement the blob's reference count, such that it exactly equals the number of fonts.
            // Hence, when the last hbFace is destroyed, the blob will also be destroyed.
            // If no valid fonts have been found, this also closes the arena.
            hb_blob_destroy(hbBlob)

            return fonts
        }

        // Load the fonts that are bundled with this program.
        val BUNDLED: List<Font> = useResourcePath("/fonts") { fontsDir ->
            fontsDir.listDirectoryEntries().flatMap(::read)
        }

        // Load the fonts that are present on the system. We only want to include TrueType/OpenType fonts, because:
        //   - Logical fonts (e.g., "Dialog" and "Serif") and system-native fonts differ unpredictably between systems.
        //   - At various points in the code, notably ReflectionExt and PDF font embedding, we only support TTF/OTF.
        val SYSTEM: List<Font> =
            if (SystemInfo.isMacOS) {
                // On macOS, the JDK only supplies us with "CFont" implementations, which do not let us access the
                // underlying TTF/OTF structure, and not even contain a path to the originating font file. Hence, we
                // need to manually read in all font files from the well-known font directories.
                sequenceOf(
                    Path("/System/Library/Fonts"),
                    Path("/Network/Library/Fonts"),
                    Path("/Library/Fonts"),
                    Path(System.getProperty("user.home")).resolve("Library/Fonts")
                )
                    .filter(Path::exists)
                    .flatMap(Path::walkSafely)
                    .filter(Path::isRegularFile)
                    // The createFonts() method can only successfully read TrueType/OpenType fonts, which is desired.
                    // If a FontFormatException or IOException occurs, just skip over the problematic font file.
                    .flatMap(::tryReadSystemFont)
                    // Internal macOS fonts start with a dot; we do not want to include those.
                    .filter { !it.family.startsWith('.') && !it.name.startsWith('.') }
                    .toList()
            } else {
                GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts
                    .filter(java.awt.Font::isTTFOrOTF)
                    .mapTo(HashSet(), java.awt.Font::getFontFile)
                    .flatMap(::tryReadSystemFont)
            }

        private fun tryReadSystemFont(file: Path) =
            try {
                read(file)
            } catch (_: Exception) {
                LOGGER.warn("Skipping system font '{}' because it cannot be read.", file)
                emptyList()
            }

        private val nameToBundled = BUNDLED.associateBy(Font::name)
        private val nameToSystem = SYSTEM.associateBy(Font::name)

        fun bundled(name: String): Font? = nameToBundled[name]
        fun system(name: String): Font? = nameToSystem[name]

        const val KERNING_FEATURE = "kern"
        val LIGATURES_FEATURES = setOf("liga", "clig")
        const val SMALL_CAPS_FEATURE = "smcp"
        const val PETITE_CAPS_FEATURE = "pcap"
        const val CAPITAL_SPACING_FEATURE = "cpsp"
        val MANAGED_FEATURES = LIGATURES_FEATURES +
                setOf(KERNING_FEATURE, SMALL_CAPS_FEATURE, PETITE_CAPS_FEATURE, CAPITAL_SPACING_FEATURE)

        private val caseCache = ConcurrentHashMap<CaseKey, WeakReference<Case>>()

        private fun isValidTag(tag: String): Boolean =
            tag.length == 4 && tag.all { it.code in 0..255 }

        private fun code2tag(code: Int): String =
            String(intArrayOf(code ushr 24, (code ushr 16) and 0xFF, (code ushr 8) and 0xFF, code and 0xFF), 0, 4)

        private fun tag2code(tag: String): Int =
            (tag[0].code shl 24) or (tag[1].code shl 16) or (tag[2].code shl 8) or tag[3].code

    }


    class Axis(
        val tag: String,
        val nameMap: Map<Locale, String>,
        val defaultValue: Double,
        val minValue: Double,
        val maxValue: Double
    )


    data class Variation(val tag: String, val value: Double)
    data class Feature(val tag: String, val value: Int)


    class Case(val font: Font, val size: Double = 12.0, val variations: Set<Variation> = emptySet(), doNotCall: Int) {

        val ascent: Double
        val descent: Double
        val lineGap: Double
        val height: Double get() = ascent + descent + lineGap

        val xHeight: Double
        val capHeight: Double

        val subScaling: Double
        val subHOffsetEm: Double
        val subVOffsetEm: Double
        val supScaling: Double
        val supHOffsetEm: Double
        val supVOffsetEm: Double

        val strikethroughThickness: Double
        val strikethroughOffset: Double
        val underlineThickness: Double
        val underlineOffset: Double

        val italic: Boolean
        val width: Double
        val weight: Double

        private val hbFont: MemorySegment

        private val glyphBoundsCache: Array<Rectangle2D?>
        private val glyphOutlineCache: Array<Path2D.Float?>

        init {
            hbFont = hb_font_create(font.hbFace)
            CLEANER.register(this, CaseCleanerAction(hbFont))

            val hbSize = (size * U).toInt()
            hb_font_set_scale(hbFont, hbSize, hbSize)

            Arena.ofConfined().use { arena ->
                if (variations.isNotEmpty()) {
                    val cVariations = hb_variation_t.allocateArray(variations.size.toLong(), arena)
                    var idx = 0
                    for (variation in variations)
                        if (isValidTag(variation.tag)) {
                            val cVariation = hb_variation_t.asSlice(cVariations, (idx++).toLong())
                            hb_variation_t.tag(cVariation, tag2code(variation.tag))
                            hb_variation_t.value(cVariation, variation.value.toFloat())
                        }
                    hb_font_set_variations(hbFont, cVariations, idx)
                }

                val int = arena.allocate(JAVA_INT)

                // Note: Here, "horizontal" means that these metrics are for horizontal lines of text, as opposed to vertical
                // lines of text as sometimes seen in CJK.
                ascent = getMetric(int, HB_OT_METRICS_TAG_HORIZONTAL_ASCENDER())
                descent = -getMetric(int, HB_OT_METRICS_TAG_HORIZONTAL_DESCENDER())
                lineGap = getMetric(int, HB_OT_METRICS_TAG_HORIZONTAL_LINE_GAP())

                xHeight = getMetric(int, HB_OT_METRICS_TAG_X_HEIGHT())
                capHeight = getMetric(int, HB_OT_METRICS_TAG_CAP_HEIGHT())

                // Note: Even though these tags claim that em-relative values are returned, we in fact get absolute
                // values. Since we need the relative values, manually divide out the size.
                subScaling = getMetric(int, HB_OT_METRICS_TAG_SUBSCRIPT_EM_Y_SIZE()) / size
                subHOffsetEm = getMetric(int, HB_OT_METRICS_TAG_SUBSCRIPT_EM_X_OFFSET()) / size
                subVOffsetEm = getMetric(int, HB_OT_METRICS_TAG_SUBSCRIPT_EM_Y_OFFSET()) / size
                supScaling = getMetric(int, HB_OT_METRICS_TAG_SUPERSCRIPT_EM_Y_SIZE()) / size
                supHOffsetEm = getMetric(int, HB_OT_METRICS_TAG_SUPERSCRIPT_EM_X_OFFSET()) / size
                supVOffsetEm = -getMetric(int, HB_OT_METRICS_TAG_SUPERSCRIPT_EM_Y_OFFSET()) / size

                strikethroughThickness = getMetric(int, HB_OT_METRICS_TAG_STRIKEOUT_SIZE())
                strikethroughOffset = -getMetric(int, HB_OT_METRICS_TAG_STRIKEOUT_OFFSET())
                underlineThickness = getMetric(int, HB_OT_METRICS_TAG_UNDERLINE_SIZE())
                underlineOffset = -getMetric(int, HB_OT_METRICS_TAG_UNDERLINE_OFFSET())
            }

            italic = hb_style_get_value(hbFont, HB_STYLE_TAG_ITALIC()) != 0f
            width = hb_style_get_value(hbFont, HB_STYLE_TAG_WIDTH()).toDouble()
            weight = hb_style_get_value(hbFont, HB_STYLE_TAG_WEIGHT()).toDouble()

            val glyphCount = hb_face_get_glyph_count(font.hbFace)
            glyphBoundsCache = arrayOfNulls(glyphCount)
            glyphOutlineCache = arrayOfNulls(glyphCount)
        }

        private fun getMetric(int: MemorySegment, tag: Int): Double {
            hb_ot_metrics_get_position_with_fallback(hbFont, tag, int)
            return int.get(JAVA_INT, 0) * IU
        }

        fun withSize(size: Double): Case = font.case(size, variations)

        fun getGlyphAdvance(glyph: Int): Double =
            hb_font_get_glyph_h_advance(hbFont, glyph) * IU

        /** The returned object is shared; do not mutate it! */
        fun getGlyphBounds(glyph: Int): Rectangle2D {
            glyphBoundsCache[glyph]?.let { return it }
            val bounds: Rectangle2D
            Arena.ofConfined().use { arena ->
                val extents = hb_glyph_extents_t.allocate(arena)
                if (hb_font_get_glyph_extents(hbFont, glyph, extents) != 0) {
                    val xBearing = hb_glyph_extents_t.x_bearing(extents) * IU
                    val yBearing = hb_glyph_extents_t.y_bearing(extents) * IU
                    val width = hb_glyph_extents_t.width(extents) * IU
                    val height = hb_glyph_extents_t.height(extents) * IU
                    bounds = Rectangle2D.Double(xBearing, -yBearing, width, -height)
                } else
                    bounds = getGlyphOutline(glyph).bounds2D
            }
            glyphBoundsCache[glyph] = bounds
            return bounds
        }

        /** The returned object is shared; do not mutate it! */
        fun getGlyphOutline(glyph: Int): Path2D.Float {
            glyphOutlineCache[glyph]?.let { return it }
            val path = Path2D.Float()
            Arena.ofConfined().use { arena ->
                val moveFunc = hb_draw_move_to_func_t.allocate({ _, _, _, x, y, _ ->
                    path.moveTo(x * IU, -y * IU)
                }, arena)
                val lineFunc = hb_draw_line_to_func_t.allocate({ _, _, _, x, y, _ ->
                    path.lineTo(x * IU, -y * IU)
                }, arena)
                val quadFunc = hb_draw_quadratic_to_func_t.allocate({ _, _, _, x1, y1, x2, y2, _ ->
                    path.quadTo(x1 * IU, -y1 * IU, x2 * IU, -y2 * IU)
                }, arena)
                val cubicFunc = hb_draw_cubic_to_func_t.allocate({ _, _, _, x1, y1, x2, y2, x3, y3, _ ->
                    path.curveTo(x1 * IU, -y1 * IU, x2 * IU, -y2 * IU, x3 * IU, -y3 * IU)
                }, arena)
                val closeFunc = hb_draw_close_path_func_t.allocate({ _, _, _, _ ->
                    path.closePath()
                }, arena)
                val hbFuncs = hb_draw_funcs_create()
                hb_draw_funcs_set_move_to_func(hbFuncs, moveFunc, NULL, NULL)
                hb_draw_funcs_set_line_to_func(hbFuncs, lineFunc, NULL, NULL)
                hb_draw_funcs_set_quadratic_to_func(hbFuncs, quadFunc, NULL, NULL)
                hb_draw_funcs_set_cubic_to_func(hbFuncs, cubicFunc, NULL, NULL)
                hb_draw_funcs_set_close_path_func(hbFuncs, closeFunc, NULL, NULL)
                hb_font_draw_glyph(hbFont, glyph, hbFuncs, NULL)
                hb_draw_funcs_destroy(hbFuncs)
            }
            glyphOutlineCache[glyph] = path
            return path
        }

        fun shape(
            chars: CharArray,
            startCharIdx: Int,
            charCount: Int,
            ltr: Boolean,
            script: Int,  // see sun.font.Script
            locale: Locale,
            kerning: Boolean,
            ligatures: Boolean,
            features: List<Feature>,
            tracking: Double,
            startX: Double,
            startY: Double
        ): ShapingResult {
            // Note: Whenever we allocate memory here, we do not have to check for allocation errors in the form of a
            // NULL pointer since (a) Java throws an OutOfMemoryError whenever some allocation we directly request fails
            // and (b) HarfBuzz returns empty singletons instead of NULL pointers whenever it can't allocate memory.
            Arena.ofConfined().use { arena ->
                // Create an HB buffer, configure it and fill it with the text.
                val hbBuffer = hb_buffer_create()
                hb_buffer_set_direction(hbBuffer, if (ltr) HB_DIRECTION_LTR() else HB_DIRECTION_RTL())
                hb_buffer_set_script(hbBuffer, icuToHBScriptCode(script))
                val lang = locale.toLanguageTag()
                hb_buffer_set_language(hbBuffer, hb_language_from_string(arena.allocateFrom(lang), -1))
                // Note: We need this cluster level for correctly identifying graphemes, which tracking shouldn't break.
                hb_buffer_set_cluster_level(hbBuffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES())

                val charsSeg = arena.allocate(JAVA_CHAR, charCount.toLong())
                MemorySegment.copy(chars, startCharIdx, charsSeg, JAVA_CHAR, 0L, charCount)
                hb_buffer_add_utf16(hbBuffer, charsSeg, charCount, 0, charCount)

                // Create an HB feature array and fill it.
                val hbFeatures = hb_feature_t.allocateArray(1L + LIGATURES_FEATURES.size + features.size, arena)
                var featureIdx = 0
                configureFeature(hbFeatures, featureIdx++, KERNING_FEATURE, if (kerning) 1 else 0)
                for (tag in LIGATURES_FEATURES)
                    configureFeature(hbFeatures, featureIdx++, tag, if (ligatures) 1 else 0)
                for (feat in features)
                    if (isValidTag(feat.tag))
                        configureFeature(hbFeatures, featureIdx++, feat.tag, feat.value)

                // Run the HB shaping algorithm.
                hb_shape(hbFont, hbBuffer, hbFeatures, featureIdx)

                // Extract the shaping result.
                val glyphCount = hb_buffer_get_length(hbBuffer)
                val glyphInfo = hb_buffer_get_glyph_infos(hbBuffer, NULL)
                val glyphPos = hb_buffer_get_glyph_positions(hbBuffer, NULL)

                // Create the arrays into which we will store the shaping result.
                val glyphs = IntArray(glyphCount)
                val positions = DoubleArray(glyphCount * 2)
                val boxes = DoubleArray(glyphCount * 2)
                val charIndices = IntArray(glyphCount)

                // Transfer the shaping result into those arrays.
                var x = startX
                var y = startY
                for (glyphIdx in 0..<glyphCount) {
                    val curGlyphInfo = hb_glyph_info_t.asSlice(glyphInfo, glyphIdx.toLong())
                    val curGlyphPos = hb_glyph_position_t.asSlice(glyphPos, glyphIdx.toLong())
                    glyphs[glyphIdx] = hb_glyph_info_t.codepoint(curGlyphInfo)
                    charIndices[glyphIdx] = hb_glyph_info_t.cluster(curGlyphInfo)
                    if (glyphIdx != 0 && charIndices[glyphIdx] != charIndices[glyphIdx - 1])
                        x += tracking
                    positions[glyphIdx * 2 + 0] = x + hb_glyph_position_t.x_offset(curGlyphPos) * IU
                    positions[glyphIdx * 2 + 1] = y - hb_glyph_position_t.y_offset(curGlyphPos) * IU
                    boxes[glyphIdx * 2 + 0] = x
                    x += hb_glyph_position_t.x_advance(curGlyphPos) * IU
                    y -= hb_glyph_position_t.y_advance(curGlyphPos) * IU
                    boxes[glyphIdx * 2 + 1] = x
                }

                // Free the manually allocated memory.
                hb_buffer_destroy(hbBuffer)

                return ShapingResult(glyphs, positions, boxes, charIndices)
            }
        }

        class ShapingResult(
            val glyphs: IntArray,
            val positions: DoubleArray,
            val boxes: DoubleArray,
            val charIndices: IntArray
        )

        companion object {

            // The HarfBuzz discretization unit. We compute with up to 16 binary digits of precision after the dot.
            private const val U = (1 shl 16).toDouble()
            private const val IU = 1.0 / U

            @Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")
            private fun icuToHBScriptCode(code: Int) = when (code) {
                Script.COMMON -> HB_SCRIPT_COMMON()
                Script.INHERITED -> HB_SCRIPT_INHERITED()
                Script.ARABIC -> HB_SCRIPT_ARABIC()
                Script.ARMENIAN -> HB_SCRIPT_ARMENIAN()
                Script.BENGALI -> HB_SCRIPT_BENGALI()
                Script.BOPOMOFO -> HB_SCRIPT_BOPOMOFO()
                Script.CHEROKEE -> HB_SCRIPT_CHEROKEE()
                Script.COPTIC -> HB_SCRIPT_COPTIC()
                Script.CYRILLIC -> HB_SCRIPT_CYRILLIC()
                Script.DESERET -> HB_SCRIPT_DESERET()
                Script.DEVANAGARI -> HB_SCRIPT_DEVANAGARI()
                Script.ETHIOPIC -> HB_SCRIPT_ETHIOPIC()
                Script.GEORGIAN -> HB_SCRIPT_GEORGIAN()
                Script.GOTHIC -> HB_SCRIPT_GOTHIC()
                Script.GREEK -> HB_SCRIPT_GREEK()
                Script.GUJARATI -> HB_SCRIPT_GUJARATI()
                Script.GURMUKHI -> HB_SCRIPT_GURMUKHI()
                Script.HAN -> HB_SCRIPT_HAN()
                Script.HANGUL -> HB_SCRIPT_HANGUL()
                Script.HEBREW -> HB_SCRIPT_HEBREW()
                Script.HIRAGANA -> HB_SCRIPT_HIRAGANA()
                Script.KANNADA -> HB_SCRIPT_KANNADA()
                Script.KATAKANA -> HB_SCRIPT_KATAKANA()
                Script.KHMER -> HB_SCRIPT_KHMER()
                Script.LAO -> HB_SCRIPT_LAO()
                Script.LATIN -> HB_SCRIPT_LATIN()
                Script.MALAYALAM -> HB_SCRIPT_MALAYALAM()
                Script.MONGOLIAN -> HB_SCRIPT_MONGOLIAN()
                Script.MYANMAR -> HB_SCRIPT_MYANMAR()
                Script.OGHAM -> HB_SCRIPT_OGHAM()
                Script.OLD_ITALIC -> HB_SCRIPT_OLD_ITALIC()
                Script.ORIYA -> HB_SCRIPT_ORIYA()
                Script.RUNIC -> HB_SCRIPT_RUNIC()
                Script.SINHALA -> HB_SCRIPT_SINHALA()
                Script.SYRIAC -> HB_SCRIPT_SYRIAC()
                Script.TAMIL -> HB_SCRIPT_TAMIL()
                Script.TELUGU -> HB_SCRIPT_TELUGU()
                Script.THAANA -> HB_SCRIPT_THAANA()
                Script.THAI -> HB_SCRIPT_THAI()
                Script.TIBETAN -> HB_SCRIPT_TIBETAN()
                Script.CANADIAN_ABORIGINAL -> HB_SCRIPT_CANADIAN_SYLLABICS()
                Script.YI -> HB_SCRIPT_YI()
                Script.TAGALOG -> HB_SCRIPT_TAGALOG()
                Script.HANUNOO -> HB_SCRIPT_HANUNOO()
                Script.BUHID -> HB_SCRIPT_BUHID()
                Script.TAGBANWA -> HB_SCRIPT_TAGBANWA()
                else -> HB_SCRIPT_INVALID()
            }

            private fun configureFeature(seg: MemorySegment, idx: Int, tag: String, value: Int) {
                val feature = hb_feature_t.asSlice(seg, idx.toLong())
                hb_feature_t.tag(feature, tag2code(tag))
                if (value > 0)
                    hb_feature_t.value(feature, value)
                hb_feature_t.start(feature, HB_FEATURE_GLOBAL_START())
                hb_feature_t.end(feature, HB_FEATURE_GLOBAL_END())
            }

        }

    }


    private class CaseKey(font: Font, private val size: Double, private val variations: Set<Variation>) {

        private val font = WeakReference(font)
        private val hashCode = 31 * (31 * size.hashCode() + variations.hashCode()) + font.hashCode()

        // If the font is GCed, the key objects with that font just stop being equals to anything. That's not a problem,
        // because if a font is GCed, nobody will ever access its cached cases again, and they will eventually be
        // removed from the cache when it's cleared.
        override fun equals(other: Any?) = this === other ||
                other is CaseKey && size == other.size && variations == other.variations &&
                font.get().let { it != null && it == other.font.get() }

        override fun hashCode() = hashCode

    }


    // Use a static classes to absolutely ensure that no unwanted references leak into these objects.

    private class FontCleanerAction(private val hbFace: MemorySegment) : Runnable {
        override fun run() = hb_face_destroy(hbFace)
    }

    private class CaseCleanerAction(private val hbFont: MemorySegment) : Runnable {
        override fun run() = hb_font_destroy(hbFont)
    }

}
