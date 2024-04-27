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
import org.apache.poi.util.LocaleID
import sun.font.*
import java.awt.Font
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.color.ICC_ColorSpace
import java.awt.font.GlyphVector
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.io.OutputStream
import java.lang.Short.toUnsignedInt
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.MethodType.methodType
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.file.Path
import java.text.Bidi
import java.util.*
import kotlin.io.path.Path


fun Bidi.visualToLogicalMap(): IntArray = BidiUtils.createVisualToLogicalMap(BidiUtils.getLevels(this))
fun IntArray.inverseMap(): IntArray = BidiUtils.createInverseMap(this)


fun Font.isTTFOrOTF() =
    FontUtilities.getFont2D(this) is TrueTypeFont


fun Font.getFontFile(): Path {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is PhysicalFont)
        throw IllegalArgumentException("A non-physical font has no font file.")
    return Path(get_platName.invokeExact(font2D as PhysicalFont) as String)
}


class FontStrings(
    val family: Map<Locale, String>,
    val subfamily: Map<Locale, String>,
    val fullName: Map<Locale, String>,
    val typographicFamily: Map<Locale, String>,
    val typographicSubfamily: Map<Locale, String>,
    val sampleText: Map<Locale, String>
)

fun Font.getStrings(): FontStrings = fontStringsCache.computeIfAbsent(this) {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return@computeIfAbsent EMPTY_FONT_STRINGS
    val nameTable = getTableBuffer.invokeExact(font2D as TrueTypeFont, TrueTypeFont.nameTag) as ByteBuffer?
    if (nameTable == null || nameTable.capacity() < 6)
        return@computeIfAbsent EMPTY_FONT_STRINGS
    val nameCount = toUnsignedInt(nameTable.getShort(2))
    val stringAreaOffset = toUnsignedInt(nameTable.getShort(4))

    // These numbers are the name IDs associated with all the fields of "FontStrings" in the same order.
    val nameMaps = shortArrayOf(1, 2, 4, 16, 17, 19).associateWithTo(LinkedHashMap()) { HashMap<Locale, String>() }
    for (idx in 0..<nameCount) {
        val recordOffset = 6 + idx * 12
        val platformId = nameTable.getShort(recordOffset)
        if (platformId != WIN_PLATFORM && platformId != MAC_PLATFORM)
            continue
        val encodingId = nameTable.getShort(recordOffset + 2)
        val languageId = nameTable.getShort(recordOffset + 4)
        if (platformId == MAC_PLATFORM && (encodingId != MAC_ROMAN_ENCODING || languageId != MAC_ENGLISH_LANG))
            continue
        val nameId = nameTable.getShort(recordOffset + 6)
        val stringLength = toUnsignedInt(nameTable.getShort(recordOffset + 8))
        val stringOffset = stringAreaOffset + toUnsignedInt(nameTable.getShort(recordOffset + 10))

        val targetMap = nameMaps[nameId] ?: continue
        val stringBytes = ByteArray(stringLength)
        nameTable.get(stringOffset, stringBytes)
        val string = makeString.invokeExact(font2D as TrueTypeFont, stringBytes, stringLength, platformId, encodingId)
                as String
        val locale = if (platformId == MAC_PLATFORM) Locale.US else LCID_TO_LOCALE[languageId] ?: continue
        targetMap[locale] = string.trim()
    }

    val iter = nameMaps.values.iterator()
    FontStrings(iter.next(), iter.next(), iter.next(), iter.next(), iter.next(), iter.next())
}

private val fontStringsCache = WeakHashMap<Font, FontStrings>()

private val EMPTY_FONT_STRINGS = FontStrings(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
private const val WIN_PLATFORM = TrueTypeFont.MS_PLATFORM_ID.toShort()
private const val MAC_PLATFORM = TrueTypeFont.MAC_PLATFORM_ID.toShort()
private const val MAC_ROMAN_ENCODING = TrueTypeFont.MACROMAN_SPECIFIC_ID.toShort()
private const val MAC_ENGLISH_LANG = TrueTypeFont.MACROMAN_ENGLISH_LANG.toShort()

private val LCID_TO_LOCALE: Map<Short, Locale> = HashMap<Short, Locale>().apply {
    for (id in LocaleID.entries)
        put(id.lcid.toShort(), Locale.forLanguageTag(id.languageTag))
}


fun Font.getWeight(): Int = FontUtilities.getFont2D(this).weight
fun Font.getWidth(): Int = FontUtilities.getFont2D(this).width
fun Font.isItalic2D(): Boolean = (FontUtilities.getFont2D(this) as Font2D).style and Font.ITALIC != 0


class SuperscriptMetrics(
    val subScaling: Double, val subHOffsetEm: Double, val subVOffsetEm: Double,
    val supScaling: Double, val supHOffsetEm: Double, val supVOffsetEm: Double
)

fun Font.getSuperscriptMetrics(): SuperscriptMetrics? {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return null
    val unitsPerEm = (getUnitsPerEm.invokeExact(font2D) as Long).toDouble()
    val os2Table = getTableBuffer.invokeExact(font2D as TrueTypeFont, TrueTypeFont.os_2Tag) as ByteBuffer?
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


class ExtraLineMetrics(val xHeightEm: Double, val capHeightEm: Double)

fun Font.getExtraLineMetrics(): ExtraLineMetrics? {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return null
    val unitsPerEm = (getUnitsPerEm.invokeExact(font2D) as Long).toDouble()
    val os2Table = getTableBuffer.invokeExact(font2D as TrueTypeFont, TrueTypeFont.os_2Tag) as ByteBuffer?
    if (os2Table == null || os2Table.capacity() < 90 || os2Table.getShort(0) /* version */ < 2)
        return null
    val xHeight = os2Table.getShort(86)
    val capHeight = os2Table.getShort(88)
    return ExtraLineMetrics(xHeight / unitsPerEm, capHeight / unitsPerEm)
}


fun Font.getSupportedFeatures(): SortedSet<String> = supportedFeaturesCache.computeIfAbsent(this) {
    val font2D = FontUtilities.getFont2D(this)
    if (font2D !is TrueTypeFont)
        return@computeIfAbsent Collections.emptySortedSet()
    val feats = TreeSet<String>() // ordered alphabetically
    extractFeatures(getTableBuffer.invokeExact(font2D as TrueTypeFont, TrueTypeFont.GPOSTag) as ByteBuffer?, feats)
    extractFeatures(getTableBuffer.invokeExact(font2D as TrueTypeFont, TrueTypeFont.GSUBTag) as ByteBuffer?, feats)
    Collections.unmodifiableSortedSet(feats)
}

private val supportedFeaturesCache = WeakHashMap<Font, SortedSet<String>>()

// Works for the GPOS and GSUB tables.
private fun extractFeatures(table: ByteBuffer?, out: MutableSet<String>) {
    if (table != null && table.capacity() >= 8) {
        val featListOffset = toUnsignedInt(table.getShort(6))
        val featCount = toUnsignedInt(table.getShort(featListOffset))
        for (idx in 0..<featCount) {
            val c = table.getInt(featListOffset + 2 + idx * 6)
            out.add(String(intArrayOf(c ushr 24, (c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF), 0, 4))
        }
    }
}


fun Font2D.getTableBytes(tag: Int): ByteArray? =
    getTableBytes.invokeExact(this, tag) as ByteArray?


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


fun GlyphLayout.LayoutEngineKey.getFont(): Font2D = font.invokeExact(this) as Font2D
fun GlyphLayout.LayoutEngineKey.getScript(): Int = script.invokeExact(this) as Int


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


private val TextLine = Class.forName("java.awt.font.TextLine")
private val ExtendedTextSourceLabel = Class.forName("sun.font.ExtendedTextSourceLabel")
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

private val getTableBytes = Font2D::class.java
    .findVirtual("getTableBytes", methodType(ByteArray::class.java, Int::class.java))
private val getUnitsPerEm = Font2D::class.java
    .findVirtual("getUnitsPerEm", methodType(Long::class.java))
private val getTableBuffer = TrueTypeFont::class.java
    .findVirtual("getTableBuffer", methodType(ByteBuffer::class.java, Int::class.java))
private val makeString = TrueTypeFont::class.java
    .findVirtual(
        "makeString", methodType(
            String::class.java, ByteArray::class.java, Int::class.java, Short::class.java, Short::class.java
        )
    )
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
private val get_textLine = TextLayout::class.java.findGetter("textLine", TextLine)
private val get_fComponents = TextLine.findGetter("fComponents", TextLineComponent::class.java.arrayType())
private val get_locs = TextLine.findGetter("locs", FloatArray::class.java)
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
