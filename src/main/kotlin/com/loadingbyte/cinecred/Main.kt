@file:JvmName("Main")

package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatSystemProperties
import com.formdev.flatlaf.util.HSLColor
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.ctrl.PersistentStorage
import com.loadingbyte.cinecred.ui.helper.fixTaskbarProgressBarOnMacOS
import com.loadingbyte.cinecred.ui.helper.fixTextFieldVerticalCentering
import com.loadingbyte.cinecred.ui.helper.tryMail
import com.oracle.si.Singleton
import net.miginfocom.layout.PlatformDefaults
import net.miginfocom.swing.MigLayout
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.*
import java.util.logging.Formatter
import javax.swing.*
import kotlin.io.path.absolute


private const val SINGLETON_APP_ID = "com.loadingbyte.cinecred"

var demoCallback: (() -> Unit)? = null

private lateinit var masterCtrl: MasterCtrlComms
private val hasCrashed = AtomicBoolean()


fun main(args: Array<String>) {
    // Cinecred is a singleton application. When the application is launched a second time, we just simulate
    // a second application instance in the same VM.
    if (Singleton.invoke(SINGLETON_APP_ID, args))
        return
    Singleton.start({ otherArgs -> SwingUtilities.invokeLater { openUI(otherArgs) } }, SINGLETON_APP_ID)

    // If an unexpected exception reaches the top of a thread's stack, we want to terminate the program in a
    // controlled fashion and inform the user. We also ask whether to send a crash report.
    Thread.setDefaultUncaughtExceptionHandler(UncaughtHandler)

    // Remove all existing handlers from the root logger.
    val rootLogger = Logger.getLogger("")
    for (handler in rootLogger.handlers)
        rootLogger.removeHandler(handler)
    // Add new logging handlers.
    rootLogger.addHandler(ConsoleHandler().apply { level = Level.WARNING; formatter = JULFormatter })
    rootLogger.addHandler(JULBuilderHandler)

    // Load our native libraries.
    System.loadLibrary("harfbuzz")
    System.loadLibrary("zimg")

    // Make JavaCPP and FlatLaf load their native libraries from java.library.path.
    System.setProperty("org.bytedeco.javacpp.cacheLibraries", "false")
    System.setProperty(FlatSystemProperties.NATIVE_LIBRARY_PATH, "system")

    // Redirect JavaCPP's logging output to slf4j.
    System.setProperty("org.bytedeco.javacpp.logger", "slf4j")
    // Load the FFmpeg libs that we require.
    Loader.load(avutil::class.java)
    Loader.load(avcodec::class.java)
    Loader.load(avformat::class.java)
    Loader.load(swscale::class.java)
    avcodec.av_jni_set_java_vm(Loader.getJavaVM(), null)
    // Redirect FFmpeg's logging output to slf4j.
    avutil.setLogCallback(FFmpegLogCallback)

    SwingUtilities.invokeLater { mainSwing(args) }
}


private fun mainSwing(args: Array<String>) {
    // Tooltips should not disappear on their own after some time.
    // To achieve this, we set the dismiss delay to one hour.
    ToolTipManager.sharedInstance().dismissDelay = 60 * 60 * 1000

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we force one invariant set of platform defaults.
    // We chose the Gnome defaults because they waste the least space on gaps.
    // At the same time, we retain the platform-specific button order.
    val nativeButtonOrder = PlatformDefaults.getButtonOrder()
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)
    PlatformDefaults.setButtonOrder(nativeButtonOrder)

    // Set the Swing Look & Feel.
    FlatDarkLaf.setup()
    // If text antialiasing is not enabled by the OS (which may choose a specific variant like subpixel rendering)
    // or we cannot detect it, enable its most generic variant now.
    val h = UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)
    if (h == null || h == RenderingHints.VALUE_TEXT_ANTIALIAS_OFF || h == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)
        UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    // If FlatLaf cannot find a font on KDE, that's because no one has been explicitly configured. First try to find
    // the default font via Gnome mechanisms since some distros expose the font that way as well. If even that fails,
    // try to use the hardcoded default KDE font, which is Noto Sans, or just Dialog if even that doesn't exist.
    if (SystemInfo.isKDE) {
        val defaultFont = UIManager.getFont("defaultFont")
        if (defaultFont.getFamily(Locale.ROOT).equals(Font.SANS_SERIF, ignoreCase = true)) {
            var replFont = resolveGnomeFont()
            if (replFont.getFamily(Locale.ROOT).equals(Font.SANS_SERIF, ignoreCase = true))
                replFont = Font("Noto Sans", Font.PLAIN, 1)  // Falls back to Dialog if Noto Sans is not found.
            UIManager.put("defaultFont", replFont.deriveFont(defaultFont.size2D))
        }
    }
    // On Linux, FlatLaf often fractionally scales the fonts a bit too large compared to what the OS does. Additionally,
    // this often results in weird looking fonts. As a quick fix, just remove the fractional part from the font size.
    if (SystemInfo.isLinux) {
        val defaultFont = UIManager.getFont("defaultFont")
        UIManager.put("defaultFont", defaultFont.deriveFont(defaultFont.size.toFloat()))
    }
    // Enable alternated coloring of table rows.
    UIManager.put("Table.alternateRowColor", HSLColor(UIManager.getColor("Table.background")).adjustTone(10f))
    // Fix the slightly offset vertical centering of text in text fields.
    fixTextFieldVerticalCentering()
    // Fix the inability to get a dock progress bar to appear on macOS.
    fixTaskbarProgressBarOnMacOS()

    // Run the demo code if configured, and then abort the regular startup.
    demoCallback?.let { it(); return }

    masterCtrl = UIFactory().master()

    // Globally listen to all key events.
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(masterCtrl::onGlobalKeyEvent)

    // Apply the locale configured by the user, if any.
    comprehensivelyApplyLocale(PersistentStorage.uiLocaleWish.locale)

    // On macOS, allow the user to open the preferences via the OS.
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES))
        Desktop.getDesktop().setPreferencesHandler {
            masterCtrl.showPreferences()
        }

    // On macOS, don't suddenly terminate the application when the user quits it or logs off, but instead try to close
    // all windows, which in turn triggers all "unsaved changes" dialogs.
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
        Desktop.getDesktop().setQuitHandler { _, response ->
            if (masterCtrl.tryCloseProjectsAndDisposeAllFrames())
                response.performQuit()
            else
                response.cancelQuit()
        }

    // Finally open the UI.
    openUI(args)
}


private fun openUI(args: Array<String>) {
    // If the program has crashed and the crash dialog is still open, launching another instance (which results in a
    // call to this method) should no longer open the welcome window.
    if (hasCrashed.get())
        return
    // If the user dragged a folder onto the program, try opening that, otherwise show the regular welcome window.
    val openProjectDir = if (args.isEmpty()) null else args[0].toPathSafely()?.absolute()
    masterCtrl.showWelcomeFrame(openProjectDir)
}


private object UncaughtHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // Immediately collect contextual information before we do anything else.
        val header = collectReportHeader()
        LOGGER.error("Uncaught exception. Will terminate the program.", e)
        if (hasCrashed.getAndSet(true))
            return
        SwingUtilities.invokeLater {
            sendReport(header)
            // Once all frames have been disposed, no more non-daemon threads are running and hence Java will terminate.
            if (::masterCtrl.isInitialized)
                masterCtrl.tryCloseProjectsAndDisposeAllFrames(force = true)
        }
    }

    private fun collectReportHeader(): String {
        val rt = Runtime.getRuntime()
        val freeMem = rt.freeMemory()
        val totalMem = rt.totalMemory()
        val maxMem = rt.maxMemory()
        val mb = 1024 * 1024
        return """---- SYSTEM INFO ----
Cinecred: $VERSION
JVM: ${System.getProperty("java.vm.vendor")} ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}
OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")}
Memory: Used ${(totalMem - freeMem) / mb} MB, Reserved ${totalMem / mb} MB, Max ${maxMem / mb} MB
Cores: ${rt.availableProcessors()}
Locale: ${Locale.getDefault().toLanguageTag()}

---- JVM ARGS ----
${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString("\n")}

---- LOG ----
"""
    }

    private fun sendReport(header: String) {
        val log = JULBuilderHandler.log.toString()
        val logComp = JTextArea("$header$log").apply {
            isEditable = false
            putClientProperty(STYLE_CLASS, "monospaced")
        }
        val msgComp = JPanel(MigLayout("insets 0, wrap", "[::50sp]", "[][]unrel[][]")).apply {
            add(JLabel(l10n("ui.crash.msg.error")))
            add(JScrollPane(logComp), "hmax 40sp")
            add(JLabel(l10n("ui.crash.msg.exit")))
            add(JLabel(l10n("ui.crash.msg.report")))
        }
        val send = JOptionPane.showConfirmDialog(
            null, msgComp, l10n("ui.crash.title"), JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE
        ) == JOptionPane.YES_OPTION
        if (send) {
            val address = encodeMailURIComponent("crashes@cinecred.com")
            val subject = encodeMailURIComponent("Cinecred Crash Report")
            val report = "[If possible, please describe what you did leading up to this crash.]\n\n\n$header" +
                    // We replace tabs by four dots because some email programs trim leading tabs and spaces.
                    log.replace("\t", "....")
            val body = encodeMailURIComponent(report)
            tryMail(URI("mailto:$address?Subject=$subject&Body=$body"))
        }
    }

    private fun encodeMailURIComponent(str: String): String =
        URLEncoder.encode(str, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

}


private object JULFormatter : Formatter() {
    private val startMillis = System.currentTimeMillis()
    override fun format(record: LogRecord): String {
        val millis = record.millis - startMillis
        val threadName = getThreadByID(record.longThreadID)?.name ?: "???"
        val exc = record.thrown?.stackTraceToString() ?: ""
        val msg = formatMessage(record)
        return "$millis [$threadName] ${record.level} ${record.loggerName} - $msg\n$exc"
    }

    private fun getThreadByID(threadID: Long): Thread? {
        // Find the root thread group.
        var rootGroup = Thread.currentThread().threadGroup ?: return null
        while (true)
            rootGroup = rootGroup.parent ?: break
        // Enumerate all threads.
        var allThreads = arrayOfNulls<Thread>(rootGroup.activeCount() + 1)
        while (rootGroup.enumerate(allThreads) == allThreads.size)
            allThreads = arrayOfNulls(allThreads.size * 2)
        // Find the thread we are looking for.
        return allThreads.find { it != null && it.id == threadID }
    }
}


private object JULBuilderHandler : Handler() {
    init {
        level = Level.INFO
        formatter = JULFormatter
    }

    // Use StringBuffer for thread-safety.
    val log = StringBuffer()

    override fun publish(record: LogRecord) {
        if (isLoggable(record))
            try {
                log.append(formatter.format(record))
            } catch (e: Exception) {
                reportError(null, e, ErrorManager.FORMAT_FAILURE)
            }
    }

    override fun flush() {}
    override fun close() {}
}


private object FFmpegLogCallback : LogCallback() {
    private val logger = LoggerFactory.getLogger("FFmpeg")
    override fun call(level: Int, msg: BytePointer) {
        // FFmpeg's log messages end with a newline character, which we have to remove.
        val message = msg.string.trim()
        when (level) {
            avutil.AV_LOG_PANIC, avutil.AV_LOG_FATAL, avutil.AV_LOG_ERROR -> logger.error(message)
            avutil.AV_LOG_WARNING -> logger.warn(message)
            avutil.AV_LOG_INFO -> logger.info(message)
            avutil.AV_LOG_VERBOSE, avutil.AV_LOG_DEBUG -> logger.debug(message)
            avutil.AV_LOG_TRACE -> logger.trace(message)
        }
    }
}
