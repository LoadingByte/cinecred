@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package com.loadingbyte.cinecred.common

import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.pdfwriter.COSWriter
import org.apache.pdfbox.pdmodel.PDPageContentStream
import sun.font.*
import java.awt.Font
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.MethodType.methodType
import java.nio.ByteBuffer
import java.nio.file.Path
import java.text.Bidi
import java.util.*
import kotlin.io.path.Path


fun Bidi.visualToLogicalMap(): IntArray = BidiUtils.createVisualToLogicalMap(BidiUtils.getLevels(this))
fun IntArray.inverseMap(): IntArray = BidiUtils.createInverseMap(this)


fun Font.getFontFile(): Path {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is PhysicalFont)
        throw IllegalArgumentException("A non-physical font has no font file.")
    return Path(get_platName(font2D) as String)
}


class SuperscriptMetrics(
    val subScaling: Float, val subHOffsetEm: Float, val subVOffsetEm: Float,
    val supScaling: Float, val supHOffsetEm: Float, val supVOffsetEm: Float
)

fun Font.getSuperscriptMetrics(): SuperscriptMetrics? {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return null
    val unitsPerEm = (getUnitsPerEm(font2D) as Long).toFloat()
    val os2Table = getTableBuffer(font2D, TrueTypeFont.os_2Tag) as ByteBuffer?
    if (os2Table == null || os2Table.capacity() < 26)
        return null
    val subscriptYSize = os2Table.getShort(12)
    val subscriptXOffset = os2Table.getShort(14)
    val subscriptYOffset = os2Table.getShort(16)
    val superscriptYSize = os2Table.getShort(20)
    val superscriptXOffset = os2Table.getShort(22)
    val superscriptYOffset = os2Table.getShort(24)
    return SuperscriptMetrics(
        subscriptYSize / unitsPerEm, subscriptXOffset / unitsPerEm, subscriptYOffset / unitsPerEm,
        superscriptYSize / unitsPerEm, superscriptXOffset / unitsPerEm, -superscriptYOffset / unitsPerEm
    )
}


class ExtraLineMetrics(val xHeightEm: Float, val capHeightEm: Float)

fun Font.getExtraLineMetrics(): ExtraLineMetrics? {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return null
    val unitsPerEm = (getUnitsPerEm(font2D) as Long).toFloat()
    val os2Table = getTableBuffer(font2D, TrueTypeFont.os_2Tag) as ByteBuffer?
    if (os2Table == null || os2Table.capacity() < 90 || os2Table.getShort(0) /* version */ < 2)
        return null
    val xHeight = os2Table.getShort(86)
    val capHeight = os2Table.getShort(88)
    return ExtraLineMetrics(xHeight / unitsPerEm, capHeight / unitsPerEm)
}


private val supportedFeaturesCache = WeakHashMap<Font, Set<String>>()

fun Font.getSupportedFeatures(): Set<String> = supportedFeaturesCache.getOrPut(this) {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return emptySet()
    val feats = TreeSet<String>() // ordered alphabetically
    extractFeatures(getTableBuffer(font2D, TrueTypeFont.GPOSTag) as ByteBuffer?, feats)
    extractFeatures(getTableBuffer(font2D, TrueTypeFont.GSUBTag) as ByteBuffer?, feats)
    feats
}

// Works for the GPOS and GSUB tables.
private fun extractFeatures(table: ByteBuffer?, out: MutableSet<String>) {
    if (table != null && table.capacity() >= 8) {
        val featListOffset = table.getShort(6).toUShort().toInt()
        val featCount = table.getShort(featListOffset).toUShort().toInt()
        for (idx in 0 until featCount) {
            val c = table.getInt(featListOffset + 2 + idx * 6)
            out.add(String(intArrayOf(c ushr 24, (c ushr 16) and 0xff, (c ushr 8) and 0xff, c and 0xff), 0, 4))
        }
    }
}


fun Font2D.getTableBytes(tag: Int): ByteArray? =
    getTableBytes(this, tag) as ByteArray?


fun TextLayout.getGlyphVectors(): List<GlyphVector> {
    val textLine = get_textLine(this)
    val fComponents = get_fComponents(textLine) as Array<*>
    return fComponents.map { tlc ->
        check(ExtendedTextSourceLabel.isInstance(tlc))
        getGV(tlc) as GlyphVector
    }
}


fun TextLayout.visualToLogicalGvIdx(visualGvIdx: Int): Int {
    val textLine = get_textLine(this)
    return getComponentLogicalIndex(textLine, visualGvIdx) as Int
}


fun TextLayout.getGlyphVectorNumChars(gvIdx: Int): Int {
    val textLine = get_textLine(this)
    val fComponents = get_fComponents(textLine) as Array<*>
    return (fComponents[gvIdx] as TextLineComponent).numCharacters
}


fun TextLayout.getGlyphVectorX(gvIdx: Int): Float {
    val textLine = get_textLine(this)
    val visualIdx = getComponentVisualIndex(textLine, gvIdx) as Int
    val locs = get_locs(textLine) as FloatArray
    return locs[visualIdx * 2]
}


fun GlyphLayout.LayoutEngineKey.getFont(): Font2D = font(this) as Font2D
fun GlyphLayout.LayoutEngineKey.getScript(): Int = script(this) as Int


fun PDPageContentStream.showGlyphsWithPositioning(glyphs: IntArray, shifts: FloatArray, bytesPerGlyph: Int) {
    require(glyphs.size == shifts.size + 1)

    val os = get_output(this) as OutputStream
    os.write('['.code)
    for (idx in glyphs.indices) {
        if (idx != 0)
            writeOperandFloat(this, shifts[idx - 1])

        val glyph = glyphs[idx]
        val glyphBytes = ByteArray(bytesPerGlyph)
        for (b in 0 until bytesPerGlyph)
            glyphBytes[b] = (glyph shr (8 * (bytesPerGlyph - 1 - b))).toByte()
        COSWriter.writeString(glyphBytes, os)
    }
    os.write("] ${OperatorName.SHOW_TEXT_ADJUSTED}\n".toByteArray(Charsets.US_ASCII))
}


fun resolveGnomeFont(): Font {
    return getGnomeFont() as Font
}


private val TextLine = Class.forName("java.awt.font.TextLine")
private val ExtendedTextSourceLabel = Class.forName("sun.font.ExtendedTextSourceLabel")
private val LinuxFontPolicy = Class.forName("com.formdev.flatlaf.LinuxFontPolicy")

private val getGnomeFont = LinuxFontPolicy
    .findStatic("getGnomeFont", methodType(Font::class.java))

private val getTableBytes = Font2D::class.java
    .findVirtual("getTableBytes", methodType(ByteArray::class.java, Int::class.java))
private val getUnitsPerEm = Font2D::class.java
    .findVirtual("getUnitsPerEm", methodType(Long::class.java))
private val getTableBuffer = TrueTypeFont::class.java
    .findVirtual("getTableBuffer", methodType(ByteBuffer::class.java, Int::class.java))
private val getComponentLogicalIndex = TextLine
    .findVirtual("getComponentLogicalIndex", methodType(Int::class.java, Int::class.java))
private val getComponentVisualIndex = TextLine
    .findVirtual("getComponentVisualIndex", methodType(Int::class.java, Int::class.java))
private val getGV = ExtendedTextSourceLabel
    .findVirtual("getGV", methodType(StandardGlyphVector::class.java))
private val font = GlyphLayout.LayoutEngineKey::class.java
    .findVirtual("font", methodType(Font2D::class.java))
private val script = GlyphLayout.LayoutEngineKey::class.java
    .findVirtual("script", methodType(Int::class.java))
private val writeOperandFloat = PDPageContentStream::class.java
    .findVirtual("writeOperand", methodType(Void::class.javaPrimitiveType, Float::class.java))

private val get_platName = PhysicalFont::class.java.findGetter("platName", String::class.java)
private val get_textLine = TextLayout::class.java.findGetter("textLine", TextLine)
private val get_fComponents = TextLine.findGetter("fComponents", TextLineComponent::class.java.arrayType())
private val get_locs = TextLine.findGetter("locs", FloatArray::class.java)
private val get_output = PDPageContentStream::class.java.findGetter("output", OutputStream::class.java)


private fun Class<*>.findStatic(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findStatic(this, name, type)

private fun Class<*>.findVirtual(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findVirtual(this, name, type)

private fun Class<*>.findGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findGetter(this, name, type)
