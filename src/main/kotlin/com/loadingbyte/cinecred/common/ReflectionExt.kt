package com.loadingbyte.cinecred.common

import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.pdfwriter.COSWriter
import org.apache.pdfbox.pdmodel.PDPageContentStream
import java.awt.Font
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.io.path.Path


fun Font.getFontFile(): Path {
    val font2D = getFont2D(this)
    if (!PhysicalFont.isInstance(font2D))
        throw IllegalArgumentException("A non-physical font has no font file.")
    return Path(get_platName(font2D) as String)
}


class SuperscriptMetrics(
    val subScaling: Float, val subHOffsetEm: Float, val subVOffsetEm: Float,
    val supScaling: Float, val supHOffsetEm: Float, val supVOffsetEm: Float
)

fun Font.getSuperscriptMetrics(): SuperscriptMetrics? {
    val font2D = getFont2D(this)
    if (!TrueTypeFont.isInstance(font2D))
        return null
    val unitsPerEm = (getUnitsPerEm(font2D) as Long).toFloat()
    val os2Table = getTableBuffer(font2D, os_2Tag) as ByteBuffer?
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
    return getNumCharacters(fComponents[gvIdx]) as Int
}


fun TextLayout.getGlyphVectorX(gvIdx: Int): Float {
    val textLine = get_textLine(this)
    val visualIdx = getComponentVisualIndex(textLine, gvIdx) as Int
    val locs = get_locs(textLine) as FloatArray
    return locs[visualIdx * 2]
}


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


private val FontUtilities = Class.forName("sun.font.FontUtilities")
private val Font2D = Class.forName("sun.font.Font2D")
private val PhysicalFont = Class.forName("sun.font.PhysicalFont")
private val TrueTypeFont = Class.forName("sun.font.TrueTypeFont")
private val TextLine = Class.forName("java.awt.font.TextLine")
private val TextLineComponent = Class.forName("sun.font.TextLineComponent")
private val ExtendedTextSourceLabel = Class.forName("sun.font.ExtendedTextSourceLabel")
private val StandardGlyphVector = Class.forName("sun.font.StandardGlyphVector")

private val os_2Tag = TrueTypeFont.findStaticGetter("os_2Tag", Int::class.java)() as Int

private val getFont2D = FontUtilities
    .findStatic("getFont2D", MethodType.methodType(Font2D, Font::class.java))

private val getUnitsPerEm = Font2D
    .findVirtual("getUnitsPerEm", MethodType.methodType(Long::class.java))
private val getTableBuffer = TrueTypeFont
    .findVirtual("getTableBuffer", MethodType.methodType(ByteBuffer::class.java, Int::class.java))
private val getComponentLogicalIndex = TextLine
    .findVirtual("getComponentLogicalIndex", MethodType.methodType(Int::class.java, Int::class.java))
private val getComponentVisualIndex = TextLine
    .findVirtual("getComponentVisualIndex", MethodType.methodType(Int::class.java, Int::class.java))
private val getNumCharacters = TextLineComponent
    .findVirtual("getNumCharacters", MethodType.methodType(Int::class.java))
private val getGV = ExtendedTextSourceLabel
    .findVirtual("getGV", MethodType.methodType(StandardGlyphVector))
private val writeOperandFloat = PDPageContentStream::class.java
    .findVirtual("writeOperand", MethodType.methodType(Void::class.javaPrimitiveType, Float::class.java))

private val get_platName = PhysicalFont.findGetter("platName", String::class.java)
private val get_textLine = TextLayout::class.java.findGetter("textLine", TextLine)
private val get_fComponents = TextLine.findGetter("fComponents", TextLineComponent.arrayType())
private val get_locs = TextLine.findGetter("locs", FloatArray::class.java)
private val get_output = PDPageContentStream::class.java.findGetter("output", OutputStream::class.java)


private fun Class<*>.findStatic(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findStatic(this, name, type)

private fun Class<*>.findVirtual(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findVirtual(this, name, type)

private fun Class<*>.findStaticGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findStaticGetter(this, name, type)

private fun Class<*>.findGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findGetter(this, name, type)
