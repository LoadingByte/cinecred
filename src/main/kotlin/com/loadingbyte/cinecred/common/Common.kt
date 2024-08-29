package com.loadingbyte.cinecred.common

import com.electronwill.toml.Toml
import com.electronwill.toml.TomlWriter
import com.formdev.flatlaf.util.SystemInfo
import com.google.common.math.IntMath
import org.apache.pdfbox.util.Matrix
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Node
import org.w3c.dom.traversal.DocumentTraversal
import java.awt.Font
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.font.LineMetrics
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import java.lang.ref.Cleaner
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.text.MessageFormat
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.JComponent
import javax.swing.UIManager
import kotlin.io.path.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt


val VERSION = useResourceStream("/version") { it.bufferedReader().readText().trim() }
val COPYRIGHT = useResourceStream("/copyright") { it.bufferedReader().readText().trim() }


val LOGGER: Logger = LoggerFactory.getLogger("Cinecred")
val CLEANER: Cleaner = Cleaner.create()
val GLOBAL_THREAD_POOL: ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "GlobalThreadPool").apply { isDaemon = true }
}


enum class Severity { INFO, WARN, MIGRATE, ERROR }


data class Resolution(val widthPx: Int, val heightPx: Int) {

    override fun toString() = "${widthPx}x${heightPx}"

    companion object {
        fun fromString(str: String): Resolution {
            val parts = str.split("x")
            require(parts.size == 2)
            return Resolution(parts[0].toInt(), parts[1].toInt())
        }
    }

}


class FPS(numerator: Int, denominator: Int) {

    val numerator: Int
    val denominator: Int

    init {
        val gcd = IntMath.gcd(numerator, denominator)
        this.numerator = numerator / gcd
        this.denominator = denominator / gcd
    }

    val frac: Double
        get() = numerator.toDouble() / denominator
    val isFractional: Boolean
        get() = numerator / denominator * denominator != numerator

    override fun equals(other: Any?) =
        this === other || other is FPS && numerator == other.numerator && denominator == other.denominator

    override fun hashCode() = 31 * numerator + denominator
    override fun toString() = if (denominator == 1) "$numerator" else "$numerator/$denominator"

    companion object {
        fun fromString(str: String): FPS {
            val parts = str.split("/")
            return when (parts.size) {
                1 -> FPS(str.toInt(), 1)
                2 -> FPS(parts[0].toInt(), parts[1].toInt())
                else -> throw IllegalArgumentException()
            }
        }
    }

}


// Note: We don't use Java's inbuilt floorDiv() and ceilDiv() because they contain branches, which make them slower.

/** Implements `floor(a / b)`, but works only for non-negative denominators! */
fun floorDiv(a: Int, b: Int) = (a - ((b - 1) and (a shr 31))) / b

/** Implements `ceil(a / b)`, but works only for non-negative denominators! */
fun ceilDiv(a: Int, b: Int) = (a + ((b - 1) and (a.inv() shr 31))) / b
/** Implements `ceil(a / b)`, but works only for non-negative denominators! */
fun ceilDiv(a: Long, b: Long) = (a + ((b - 1L) and (a.inv() shr 31))) / b

/** Implements `round(a / b)`, but works only for non-negative numbers! */
fun roundingDiv(a: Int, b: Int): Int = (a + (b shr 1)) / b


inline fun throwableAwareTask(crossinline task: () -> Unit) = Runnable {
    try {
        task()
    } catch (t: Throwable) {
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t)
    }
}

inline fun <V> throwableAwareValuedTask(crossinline task: () -> V) = Callable<V?> {
    try {
        task()
    } catch (t: Throwable) {
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t)
        null
    }
}


inline fun <R> useResourceStream(path: String, action: (InputStream) -> R): R =
    Severity::class.java.getResourceAsStream(path)!!.use(action)

inline fun <R> useResourcePath(path: String, action: (Path) -> R): R {
    val uri = (Severity::class.java).getResource(path)!!.toURI()
    return if (uri.scheme == "jar")
        FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fs ->
            action(fs.getPath(path))
        }
    else
        action(Path.of(uri))
}


fun String.toPathSafely(): Path? =
    try {
        Path(this)
    } catch (_: InvalidPathException) {
        null
    }

fun File.toPathSafely(): Path? =
    try {
        toPath()
    } catch (_: InvalidPathException) {
        null
    }


fun Path.isAccessibleDirectory(thatContainsNonHiddenFiles: Boolean = false): Boolean =
    if (!isDirectory())
        false
    else
        try {
            useDirectoryEntries { seq -> !thatContainsNonHiddenFiles || !seq.all(Path::isHidden) }
        } catch (_: IOException) {
            false
        }


/**
 * The implementation of [createDirectories] will throw an exception if the path already exists and is a symbolic link
 * to a directory. This function does not fail in such cases.
 */
fun Path.createDirectoriesSafely() {
    if (!isDirectory() /* follows symlinks */)
        createDirectories()
}


/**
 * The implementation of [Files.walk] will throw exceptions when encountering various failure conditions, for example
 * when looping back on itself. We instead want our walker to swallow (but log) errors and continue scanning the rest of
 * the file tree as if nothing happened.
 */
fun Path.walkSafely(): List<Path> {
    val list = ArrayList<Path>(1024)
    val root = this
    val opts = EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(root, opts, Int.MAX_VALUE, object : FileVisitor<Path> {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            list.add(dir)
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            list.add(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            logExc(file, exc)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null)
                logExc(dir, exc)
            return FileVisitResult.CONTINUE
        }

        private fun logExc(file: Path, exc: IOException) {
            val msg = "Exception thrown at '{}' while walking the file tree starting at '{}': {}"
            LOGGER.error(msg, file, root, exc.toString())
        }
    })
    return list
}


/** @throws IOException */
fun Path.cleanDirectory() {
    Files.walkFileTree(this, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            file.deleteIfExists()
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null)
                throw exc
            if (dir != this@cleanDirectory)
                dir.deleteIfExists()
            return FileVisitResult.CONTINUE
        }
    })
}


fun readToml(file: Path): MutableMap<String, Any> =
    Toml.read(file.toFile())

fun writeToml(file: Path, toml: Map<String, Any?>) {
    // First write to a string and not directly to the file so that it's not corrupted if the TOML writer crashes.
    val text = StringWriter().also { writer ->
        // Use the Unix line separator (\n) and not the OS-specific one, which would be the default.
        TomlWriter(writer, 1, false, "\n").write(toml)
    }.toString()
    // Then write the string to the file.
    file.writeText(text)
}


val CONFIG_DIR: Path = when {
    SystemInfo.isWindows -> Path(System.getenv("APPDATA"), "Cinecred")
    SystemInfo.isMacOS -> Path(System.getProperty("user.home"), "Library", "Application Support", "Cinecred")
    else /* Linux & fallback */ -> {
        val cfgDir = System.getenv("XDG_CONFIG_HOME")
        if (cfgDir.isNullOrEmpty())
            Path(System.getProperty("user.home"), ".config", "cinecred")
        else
            Path(cfgDir, "cinecred")
    }
}


val TRANSLATED_LOCALES: List<Locale> = listOf(
    Locale.of("cs"),
    Locale.of("de"),
    Locale.of("en"),
    Locale.of("fr"),
    Locale.of("zh", "CN")
)
val FALLBACK_TRANSLATED_LOCALE: Locale = Locale.ENGLISH

/**
 * This is the translated locale which is closest to the system's default locale. It necessarily has to be computed
 * before the default locale is changed for the first time, which is fulfilled by a read prior to that action.
 */
val SYSTEM_LOCALE: Locale = run {
    val def = Locale.getDefault()
    TRANSLATED_LOCALES.find { it.language == def.language && it.country == def.country }
        ?: TRANSLATED_LOCALES.find { it.language == def.language }
        ?: FALLBACK_TRANSLATED_LOCALE
}

private val BUNDLE_CONTROL = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
private fun getL10nBundle(locale: Locale) = ResourceBundle.getBundle("l10n.strings", locale, BUNDLE_CONTROL)

fun l10n(key: String, locale: Locale = Locale.getDefault()): String = getL10nBundle(locale).getString(key)

private val DOUBLE_BRACE_REGEX = Regex("(?<!\\{)\\{\\{|}}(?!})")
fun l10n(key: String, vararg args: Any?, locale: Locale = Locale.getDefault()): String {
    val effArgs = if (args.none { it is Float || it is Double }) args else {
        val fmt = NumberFormat.getInstance()
        Array(args.size) { idx ->
            val arg = args[idx]
            if (arg is Float || arg is Double) fmt.format(arg) else arg
        }
    }
    var str = l10n(key, locale)
    if ("{{" in str || "}}" in str)
        str = str.replace(DOUBLE_BRACE_REGEX, "'$0'")
    return MessageFormat(str).format(effArgs)
}

fun comprehensivelyApplyLocale(locale: Locale) {
    SYSTEM_LOCALE  // Run the initializer and thereby remember the default local before we change it in a moment.
    Locale.setDefault(locale)
    UIManager.getDefaults().defaultLocale = locale
    UIManager.getLookAndFeelDefaults().defaultLocale = locale
    JComponent.setDefaultLocale(locale)
    changeLocaleOfToolkitResources(locale)
}


const val XLINK_NS_URI = "http://www.w3.org/1999/xlink"
const val SVG_NS_URI = "http://www.w3.org/2000/svg"


/** Notice that the subtree contains the root node itself, and that the subtree may be modified during iteration. */
inline fun Node.forEachNodeInSubtree(whatToShow: Int, action: (Node) -> Unit) {
    val iter = (ownerDocument as DocumentTraversal).createNodeIterator(this, whatToShow, null, true)
    try {
        while (true)
            action(iter.nextNode() ?: break)
    } finally {
        iter.detach()
    }
}


// This is a reference font render context used to measure the size of fonts.
val REF_FRC = FontRenderContext(null, true, true)


fun AffineTransform.scale(s: Double) = scale(s, s)
fun Matrix.translate(tx: Double, ty: Double) = translate(tx.toFloat(), ty.toFloat())
fun Matrix.scale(sx: Double, sy: Double) = scale(sx.toFloat(), sy.toFloat())
fun Matrix.scale(s: Double) = scale(s.toFloat(), s.toFloat())

// See PDFBox's Matrix.getScalingFactorX/Y() for the derivation.
val AffineTransform.scalingFactorX: Double get() = sqrt(scaleX.pow(2) + shearY.pow(2))
val AffineTransform.scalingFactorY: Double get() = sqrt(scaleY.pow(2) + shearX.pow(2))


fun Shape.transformedBy(transform: AffineTransform?): Shape = when {
    transform == null || transform.isIdentity -> this
    this is Rectangle2D && transform.shearX == 0.0 && transform.shearY == 0.0 -> {
        val p = doubleArrayOf(minX, minY, maxX, maxY)
        transform.transform(p, 0, p, 0, 2)
        Rectangle2D.Double(min(p[0], p[2]), min(p[1], p[3]), abs(p[0] - p[2]), abs(p[1] - p[3]))
    }
    this is Path2D.Float -> Path2D.Float(this, transform)
    else -> Path2D.Double(this, transform)
}


const val KERNING_FONT_FEAT = "kern"
val LIGATURES_FONT_FEATS = setOf("liga", "clig")
const val SMALL_CAPS_FONT_FEAT = "smcp"
const val PETITE_CAPS_FONT_FEAT = "pcap"
const val CAPITAL_SPACING_FONT_FEAT = "cpsp"
val MANAGED_FONT_FEATS = LIGATURES_FONT_FEATS +
        setOf(KERNING_FONT_FEAT, SMALL_CAPS_FONT_FEAT, PETITE_CAPS_FONT_FEAT, CAPITAL_SPACING_FONT_FEAT)


val Font.lineMetrics: LineMetrics
    get() = getLineMetrics("Beispiel!", REF_FRC)
