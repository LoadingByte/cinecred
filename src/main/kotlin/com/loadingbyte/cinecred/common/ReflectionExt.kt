package com.loadingbyte.cinecred.common

import java.awt.Shape
import java.awt.font.TextLayout
import java.awt.geom.GeneralPath
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType


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

    val textLine = get_textLine(this)
    val fComponents = get_fComponents(textLine) as Array<*>
    val locs = get_locs(textLine) as FloatArray

    var charIdx = 0
    for (logicalIdx in fComponents.indices) {
        val tlc = fComponents[logicalIdx]
        val tlcNumChars = getNumCharacters(tlc) as Int
        if (charIdx >= stop)
            break
        if (charIdx >= start) {
            val visualIdx = getComponentVisualIndex(textLine, logicalIdx) as Int
            val tlcOutline = getOutline(tlc, locs[visualIdx * 2], locs[visualIdx * 2 + 1]) as Shape
            outline.append(tlcOutline, false)
        }
        charIdx += tlcNumChars
    }

    val layoutPath = this.layoutPath
    return if (layoutPath == null) outline else mapShape(layoutPath, outline) as Shape
}


private val TextLine = Class.forName("java.awt.font.TextLine")
private val TextLineComponent = Class.forName("sun.font.TextLineComponent")
private val LayoutPathImpl = Class.forName("sun.font.LayoutPathImpl")

private val getComponentVisualIndex = TextLine
    .findVirtual("getComponentVisualIndex", MethodType.methodType(Int::class.java, Int::class.java))
private val getNumCharacters = TextLineComponent
    .findVirtual("getNumCharacters", MethodType.methodType(Int::class.java))
private val getOutline = TextLineComponent
    .findVirtual("getOutline", MethodType.methodType(Shape::class.java, Float::class.java, Float::class.java))
private val mapShape = LayoutPathImpl
    .findVirtual("mapShape", MethodType.methodType(Shape::class.java, Shape::class.java))

private val get_textLine = TextLayout::class.java.findGetter("textLine", TextLine)
private val get_fComponents = TextLine.findGetter("fComponents", TextLineComponent.arrayType())
private val get_locs = TextLine.findGetter("locs", FloatArray::class.java)


private fun Class<*>.findVirtual(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findVirtual(this, name, type)

private fun Class<*>.findGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findGetter(this, name, type)
