@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE", "USELESS_CAST")

package com.loadingbyte.cinecred.common

import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdfwriter.COSWriter
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.function.PDFunction
import org.apache.pdfbox.pdmodel.graphics.color.*
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading
import org.apache.pdfbox.util.Matrix
import sun.font.FontUtilities
import sun.font.PhysicalFont
import sun.font.TrueTypeFont
import java.awt.Font
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.color.ICC_ColorSpace
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.io.OutputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.MethodType.methodType
import java.lang.invoke.VarHandle
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path


fun Font.isTTFOrOTF() =
    FontUtilities.getFont2D(this) is TrueTypeFont


fun Font.getFontFile(): Path {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is PhysicalFont)
        throw IllegalArgumentException("A non-physical font has no font file.")
    return Path(get_platName.invokeExact(font2D as PhysicalFont) as String)
}


fun changeLocaleOfToolkitResources(locale: Locale) {
    fun forVarHandle(varHandle: VarHandle) {
        val bundle = varHandle.get() as ResourceBundle?
        if (bundle != null) {
            val baseName = bundle.baseBundleName ?: bundle.javaClass.name.substringBefore('_')
            varHandle.set(ResourceBundle.getBundle(baseName, locale, Toolkit::class.java.module))
        }
    }
    forVarHandle(resources)
    forVarHandle(platformResources)
}


fun appendCOSName(contentStream: Any /* PD(Page|Form)ContentStream */, name: COSName) {
    name.writePDF(get_outputStream(contentStream) as OutputStream)
}

fun appendRawCommands(contentStream: Any /* PD(Page|Form)ContentStream */, commands: String) {
    (get_outputStream(contentStream) as OutputStream).write(commands.toByteArray(Charsets.US_ASCII))
}

fun PDPageContentStream.showGlyphsWithPositioning(glyphs: IntArray, shifts: FloatArray, bytesPerGlyph: Int) {
    require(glyphs.size == shifts.size + 1)

    val os = get_outputStream(this) as OutputStream
    os.write('['.code)
    for (idx in glyphs.indices) {
        if (idx != 0)
            writeOperandFloat.invokeExact(this, shifts[idx - 1])

        val glyph = glyphs[idx]
        val glyphBytes = ByteArray(bytesPerGlyph)
        for (b in 0..<bytesPerGlyph)
            glyphBytes[b] = (glyph shr (8 * (bytesPerGlyph - 1 - b))).toByte()
        COSWriter.writeString(glyphBytes, os)
    }
    os.write("] ${OperatorName.SHOW_TEXT_ADJUSTED}\n".toByteArray(Charsets.US_ASCII))
}


fun PDICCBased.getAWTColorSpace(): ICC_ColorSpace? = get_awtColorSpace.invokeExact(this) as ICC_ColorSpace?
fun PDICCBased.getActivatedAlternateColorSpace(): PDColorSpace? =
    get_alternateColorSpace.invokeExact(this) as PDColorSpace?
@Suppress("UNCHECKED_CAST")
fun PDIndexed.getColorTable(): Array<FloatArray> = get_colorTable.invokeExact(this) as Array<FloatArray>
fun PDSeparation.getTintTransform(): PDFunction = get_tintTransform.invokeExact(this) as PDFunction
fun PDDeviceN.getColorantToComponent(): IntArray = get_colorantToComponent.invokeExact(this) as IntArray
fun PDDeviceN.getProcessColorSpace(): PDColorSpace = get_processColorSpace.invokeExact(this) as PDColorSpace
@Suppress("UNCHECKED_CAST")
fun PDDeviceN.getSpotColorSpaces(): Array<PDSeparation?> = get_spotColorSpaces.invokeExact(this) as Array<PDSeparation?>


@Suppress("UNCHECKED_CAST")
fun PDShading.collectTriangles(transform: AffineTransform): List<Any> =
    collectTriangles(this, transform, Matrix()) as List<Any>

@Suppress("UNCHECKED_CAST")
fun PDShading.collectTrianglesOfPatches(transform: AffineTransform, ctlPoints: Int): List<Any> =
    (collectPatches(this, transform, Matrix(), ctlPoints) as List<Any>).flatMap { get_listOfTriangles(it) as List<Any> }

@Suppress("UNCHECKED_CAST")
fun shadedTriangleGetCorner(tri: Any): Array<Point2D> = get_corner(tri) as Array<Point2D>
@Suppress("UNCHECKED_CAST")
fun shadedTriangleGetColor(tri: Any): Array<FloatArray> = get_color(tri) as Array<FloatArray>
fun shadedTriangleGetDeg(tri: Any): Int = getDeg(tri) as Int
fun shadedTriangleGetLine(tri: Any): Any = getLine(tri)
fun shadedTriangleContains(tri: Any, p: Point2D): Boolean = contains(tri, p) as Boolean
fun shadedTriangleCalcColor(tri: Any, p: Point2D): FloatArray = calcColor_tri(tri, p) as FloatArray

fun newLine(p0: Point, p1: Point, c0: FloatArray, c1: FloatArray): Any = newLine.invoke(p0, p1, c0, c1)
@Suppress("UNCHECKED_CAST")
fun lineGetLinePoints(line: Any): Set<Point> = get_linePoints(line) as Set<Point>
fun lineCalcColor(line: Any, p: Point): FloatArray = calcColor_line(line, p) as FloatArray


fun resolveGnomeFont(): Font {
    return getGnomeFont.invokeExact() as Font
}


fun setWindowCanFullScreenMacOS(window: Window, canFullScreen: Boolean) {
    // Resort to regular reflection because this method isn't performance-critical and only available on macOS.
    Class.forName("com.apple.eawt.FullScreenUtilities")
        .getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.java)
        .invoke(null, window, canFullScreen)
}

fun trySetAWTAppClassNameLinux(awtAppClassName: String) {
    // Resort to regular reflection because this method isn't performance-critical and only available on X11.
    val tkCls = Toolkit.getDefaultToolkit().javaClass
    if (tkCls.name == "sun.awt.X11.XToolkit")
        tkCls.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, awtAppClassName)
}


private val PDAbstractContentStream = Class.forName("org.apache.pdfbox.pdmodel.PDAbstractContentStream")
private val PDTriangleBasedShadingType =
    Class.forName("org.apache.pdfbox.pdmodel.graphics.shading.PDTriangleBasedShadingType")
private val PDMeshBasedShadingType = Class.forName("org.apache.pdfbox.pdmodel.graphics.shading.PDMeshBasedShadingType")
private val ShadedTriangle = Class.forName("org.apache.pdfbox.pdmodel.graphics.shading.ShadedTriangle")
private val Patch = Class.forName("org.apache.pdfbox.pdmodel.graphics.shading.Patch")
private val Line = Class.forName("org.apache.pdfbox.pdmodel.graphics.shading.Line")
private val LinuxFontPolicy = Class.forName("com.formdev.flatlaf.LinuxFontPolicy")

private val getGnomeFont = LinuxFontPolicy
    .findStatic("getGnomeFont", methodType(Font::class.java))

private val resources = Toolkit::class.java.findStaticVar("resources", ResourceBundle::class.java)
private val platformResources = Toolkit::class.java.findStaticVar("platformResources", ResourceBundle::class.java)

private val newLine = Line
    .findConstructor(
        methodType(
            Void::class.javaPrimitiveType,
            Point::class.java, Point::class.java, FloatArray::class.java, FloatArray::class.java
        )
    )

private val writeOperandFloat = PDPageContentStream::class.java
    .findVirtual("writeOperand", methodType(Void::class.javaPrimitiveType, Float::class.java))
private val collectTriangles = PDTriangleBasedShadingType
    .findVirtual("collectTriangles", methodType(List::class.java, AffineTransform::class.java, Matrix::class.java))
private val collectPatches = PDMeshBasedShadingType
    .findVirtual(
        "collectPatches", methodType(List::class.java, AffineTransform::class.java, Matrix::class.java, Int::class.java)
    )
private val getDeg = ShadedTriangle
    .findVirtual("getDeg", methodType(Int::class.java))
private val getLine = ShadedTriangle
    .findVirtual("getLine", methodType(Line))
private val contains = ShadedTriangle
    .findVirtual("contains", methodType(Boolean::class.java, Point2D::class.java))
private val calcColor_tri = ShadedTriangle
    .findVirtual("calcColor", methodType(FloatArray::class.java, Point2D::class.java))
private val calcColor_line = Line
    .findVirtual("calcColor", methodType(FloatArray::class.java, Point::class.java))

private val get_platName = PhysicalFont::class.java.findGetter("platName", String::class.java)
private val get_outputStream = PDAbstractContentStream.findGetter("outputStream", OutputStream::class.java)
private val get_awtColorSpace = PDICCBased::class.java.findGetter("awtColorSpace", ICC_ColorSpace::class.java)
private val get_alternateColorSpace = PDICCBased::class.java.findGetter("alternateColorSpace", PDColorSpace::class.java)
private val get_colorTable = PDIndexed::class.java.findGetter("colorTable", FloatArray::class.java.arrayType())
private val get_tintTransform = PDSeparation::class.java.findGetter("tintTransform", PDFunction::class.java)
private val get_colorantToComponent = PDDeviceN::class.java.findGetter("colorantToComponent", IntArray::class.java)
private val get_processColorSpace = PDDeviceN::class.java.findGetter("processColorSpace", PDColorSpace::class.java)
private val get_spotColorSpaces = PDDeviceN::class.java
    .findGetter("spotColorSpaces", PDSeparation::class.java.arrayType())
private val get_listOfTriangles = Patch.findGetter("listOfTriangles", List::class.java)
private val get_corner = ShadedTriangle.findGetter("corner", Point2D::class.java.arrayType())
private val get_color = ShadedTriangle.findGetter("color", FloatArray::class.java.arrayType())
private val get_linePoints = Line.findGetter("linePoints", Set::class.java)


private fun Class<*>.findStatic(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findStatic(this, name, type)

private fun Class<*>.findStaticVar(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findStaticVarHandle(this, name, type)
        .withInvokeExactBehavior()

private fun Class<*>.findConstructor(type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findConstructor(this, type)

private fun Class<*>.findVirtual(name: String, type: MethodType) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findVirtual(this, name, type)

private fun Class<*>.findGetter(name: String, type: Class<*>) =
    MethodHandles.privateLookupIn(this, MethodHandles.lookup()).findGetter(this, name, type)
