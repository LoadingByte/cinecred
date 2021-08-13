package com.loadingbyte.cinecred.common

import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.pdfwriter.COSWriter
import org.apache.pdfbox.pdmodel.PDPageContentStream
import java.awt.Font
import java.awt.Shape
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.awt.geom.GeneralPath
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import kotlin.io.path.Path


fun Font.getFontFile(): Path {
    val font2D = getFont2D(this)
    if (!PhysicalFont.isInstance(font2D))
        throw IllegalArgumentException("A non-physical font has no font file.")
    return Path(get_platName(font2D) as String)
}


class TextLayoutGV(val startCharIdx: Int, val stopCharIdx: Int, val x: Float, val y: Float, val gv: GlyphVector)

fun TextLayout.getGlyphVectors(): List<TextLayoutGV> {
    val gvs = mutableListOf<TextLayoutGV>()

    forEachTLC { charIdx, numChars, x, y, tlc ->
        if (ExtendedTextSourceLabel.isInstance(tlc))
            gvs.add(TextLayoutGV(charIdx, charIdx + numChars, x, y, getGV(tlc) as GlyphVector))
    }

    return gvs
}


/**
 * This method is directly derived from information contained in TextLayout.getOutline(), TextLine.getOutline(),
 * and TextLine.getCharBounds().
 *
 * If the start and stop bounds are not aligned with the TextLineComponent bounds, this method will silently
 * cut off parts from the beginning and add parts at the end.
 */
fun TextLayout.getOutline(start: Int, stop: Int): Shape {
    require(start < stop)
    require(start >= 0 && stop <= characterCount)

    val outline = GeneralPath(GeneralPath.WIND_NON_ZERO)

    forEachTLC { charIdx, _, x, y, tlc ->
        if (charIdx in start until stop)
            outline.append(getOutline(tlc, x, y) as Shape, false)
    }

    val layoutPath = this.layoutPath
    return if (layoutPath == null) outline else mapShape(layoutPath, outline) as Shape
}


private inline fun TextLayout.forEachTLC(action: (charIdx: Int, numChars: Int, x: Float, y: Float, tlc: Any) -> Unit) {
    val textLine = get_textLine(this)
    val fComponents = get_fComponents(textLine) as Array<*>
    val locs = get_locs(textLine) as FloatArray

    var charIdx = 0
    for (logicalIdx in fComponents.indices) {
        val tlc = fComponents[logicalIdx]!!
        val tlcNumChars = getNumCharacters(tlc) as Int
        val visualIdx = getComponentVisualIndex(textLine, logicalIdx) as Int
        action(charIdx, tlcNumChars, locs[visualIdx * 2], locs[visualIdx * 2 + 1], tlc)
        charIdx += tlcNumChars
    }
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
private val TextLine = Class.forName("java.awt.font.TextLine")
private val TextLineComponent = Class.forName("sun.font.TextLineComponent")
private val ExtendedTextSourceLabel = Class.forName("sun.font.ExtendedTextSourceLabel")
private val StandardGlyphVector = Class.forName("sun.font.StandardGlyphVector")
private val LayoutPathImpl = Class.forName("sun.font.LayoutPathImpl")

private val getFont2D = FontUtilities
    .findStatic("getFont2D", MethodType.methodType(Font2D, Font::class.java))
private val getComponentVisualIndex = TextLine
    .findVirtual("getComponentVisualIndex", MethodType.methodType(Int::class.java, Int::class.java))
private val getNumCharacters = TextLineComponent
    .findVirtual("getNumCharacters", MethodType.methodType(Int::class.java))
private val getOutline = TextLineComponent
    .findVirtual("getOutline", MethodType.methodType(Shape::class.java, Float::class.java, Float::class.java))
private val getGV = ExtendedTextSourceLabel
    .findVirtual("getGV", MethodType.methodType(StandardGlyphVector))
private val mapShape = LayoutPathImpl
    .findVirtual("mapShape", MethodType.methodType(Shape::class.java, Shape::class.java))
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

private fun Class<*>.findGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findGetter(this, name, type)
