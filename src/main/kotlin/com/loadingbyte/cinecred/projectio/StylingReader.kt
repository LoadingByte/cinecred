package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import java.awt.Font
import java.io.IOException
import java.nio.file.Path
import java.util.*


/** @throws IOException */
fun readStylingVersion(stylingFile: Path): String? =
    readToml(stylingFile)["version"] as? String


/** @throws IOException */
fun readStyling(stylingFile: Path, projectFonts: Collection<Font>): Styling {
    val toml = readToml(stylingFile)

    val rawStyling = RawStyling(
        asMap(toml["global"]),
        asMaps(toml["pageStyle"]),
        asMaps(toml["contentStyle"]),
        asMaps(toml["letterStyle"])
    )

    val ctx = StylingReaderContext(projectFonts.associateBy { font -> font.getFontName(Locale.ROOT) })

    migrateStyling(ctx, rawStyling)

    return Styling(
        readStyle(ctx, rawStyling.global, Global::class.java),
        readStyles(ctx, rawStyling.pageStyles, PageStyle::class.java),
        readStyles(ctx, rawStyling.contentStyles, ContentStyle::class.java),
        readStyles(ctx, rawStyling.letterStyles, LetterStyle::class.java),
    )
}


class RawStyling(
    val global: MutableMap<String, Any>,
    val pageStyles: MutableList<MutableMap<String, Any>>,
    val contentStyles: MutableList<MutableMap<String, Any>>,
    val letterStyles: MutableList<MutableMap<String, Any>>
)


class StylingReaderContext(private val fontsByName: Map<String, Font>) {
    fun resolveFont(name: String): Font? =
        fontsByName[name] ?: getBundledFont(name) ?: getSystemFont(name)
}


@Suppress("UNCHECKED_CAST")
private fun asMap(map: Any?): MutableMap<String, Any> =
    if (map !is MutableMap<*, *>) mutableMapOf()
    else map as MutableMap<String, Any>

private fun asMaps(mapList: Any?): MutableList<MutableMap<String, Any>> =
    if (mapList !is List<*>) mutableListOf()
    else mapList.filterIsInstanceTo(mutableListOf())


private fun <S : ListedStyle> readStyles(
    ctx: StylingReaderContext,
    maps: List<Map<*, *>>,
    styleClass: Class<S>
): PersistentList<S> {
    val styles = maps.map { readStyle(ctx, it, styleClass) }
    val updates = ensureConsistency(styles)
    return styles.map { style -> updates.getOrDefault(style, style) }.toPersistentList()
}

private fun <S : Style> readStyle(ctx: StylingReaderContext, map: Map<*, *>, styleClass: Class<S>): S {
    val notarizedSettingValues = buildList {
        for (setting in getStyleSettings(styleClass))
            try {
                add(readSetting(ctx, setting, map[setting.name]!!))
            } catch (_: RuntimeException) {
                // Catches IllegalArgumentException, NullPointerException, and ClassCastException.
            }
    }
    return getPreset(styleClass).copy(notarizedSettingValues)
}

private fun <S : Style, SUBJ : Any> readSetting(
    ctx: StylingReaderContext,
    setting: StyleSetting<S, SUBJ>,
    raw: Any
): NotarizedStyleSettingValue<S> =
    when (setting) {
        is DirectStyleSetting ->
            setting.notarize(convert(ctx, setting.type, raw))
        is OptStyleSetting ->
            setting.notarize(Opt(true, convert(ctx, setting.type, raw)))
        is ListStyleSetting ->
            setting.notarize((raw as List<*>).filterNotNull().map { convert(ctx, setting.type, it) }.toPersistentList())
    }


@Suppress("UNCHECKED_CAST")
private fun <T> convert(ctx: StylingReaderContext, type: Class<T>, raw: Any): T = convertUntyped(ctx, type, raw) as T

private fun convertUntyped(ctx: StylingReaderContext, type: Class<*>, raw: Any): Any = when (type) {
    Int::class.javaPrimitiveType, Int::class.javaObjectType -> (raw as Number).toInt()
    Double::class.javaPrimitiveType, Double::class.javaObjectType -> (raw as Number).toDouble()
    Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> raw as Boolean
    String::class.java -> raw as String
    Locale::class.java -> Locale.forLanguageTag(raw as String)
    Color4f::class.java -> Color4f((raw as List<*>).requireIsInstance<Number>(), ColorSpace.XYZD50)
    Resolution::class.java -> Resolution.fromString(raw as String)
    FPS::class.java -> FPS.fromString(raw as String)
    FontRef::class.java -> ctx.resolveFont(raw as String)?.let(::FontRef) ?: FontRef(raw)
    FontFeature::class.java -> fontFeatureFromKV(raw as String)
    else -> when {
        Enum::class.java.isAssignableFrom(type) -> enumFromName(raw as String, type)
        NestedStyle::class.java.isAssignableFrom(type) ->
            readStyle(ctx, raw as Map<*, *>, type.asSubclass(NestedStyle::class.java))
        else -> throw UnsupportedOperationException("Reading objects of type ${type.name} is not supported.")
    }
}

private fun fontFeatureFromKV(kv: String): FontFeature {
    val parts = kv.split("=")
    require(parts.size == 2)
    return FontFeature(parts[0], parts[1].toInt())
}

private fun enumFromName(name: String, enumClass: Class<*>): Any =
    enumClass.enumConstants.first { (it as Enum<*>).name.equals(name, ignoreCase = true) }
