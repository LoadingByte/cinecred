package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.util.HSLColor
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.OpenController
import com.loadingbyte.cinecred.ui.PreferencesController
import com.oracle.si.Singleton
import net.miginfocom.layout.PlatformDefaults
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Desktop
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URLEncoder
import java.util.logging.*
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager


private const val SINGLETON_APP_ID = "com.loadingbyte.cinecred"

fun main() {
    // Cinecred is a singleton application. When the application is launched a second time, we just simulate
    // a second application instance in the same VM.
    if (Singleton.invoke(SINGLETON_APP_ID, emptyArray()))
        return
    Singleton.start({ OpenController.showOpenFrame() }, SINGLETON_APP_ID)

    // If a unexpected exception reaches the top of a thread's stack, we want to terminate the program in a
    // controlled fashion and inform the user. We also ask whether to send a crash report.
    Thread.setDefaultUncaughtExceptionHandler(UncaughtHandler)

    // Remove all existing handlers from the root logger.
    val rootLogger = Logger.getLogger("")
    for (handler in rootLogger.handlers)
        rootLogger.removeHandler(handler)
    // Add new logging handlers.
    rootLogger.addHandler(ConsoleHandler().apply { level = Level.WARNING; formatter = JULFormatter })
    rootLogger.addHandler(JULBuilderHandler)

    // Redirect JavaCPP's logging output to slf4j.
    System.setProperty("org.bytedeco.javacpp.logger", "slf4j")
    // Load FFmpeg.
    try {
        // Load the FFmpeg libs that we require.
        Loader.load(avutil::class.java)
        Loader.load(avcodec::class.java)
        Loader.load(avformat::class.java)
        Loader.load(swscale::class.java)
        avcodec.av_jni_set_java_vm(Loader.getJavaVM(), null)
        // Redirect FFmpeg's logging output to slf4j.
        setLogCallback(FFmpegLogCallback)
    } catch (t: Throwable) {
        LoggerFactory.getLogger("FFmpeg Loader").error("Failed to load FFmpeg", t)
    }

    // Tooltips should not disappear on their own after some time.
    // To achieve this, we set the dismiss delay to one hour.
    ToolTipManager.sharedInstance().dismissDelay = 60 * 60 * 1000

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we just force one invariant set of platform defaults.
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)

    // Set the Swing Look & Feel.
    FlatDarkLaf.install()
    // Enable alternated coloring of table rows.
    val uiDefaults = UIManager.getLookAndFeelDefaults()
    uiDefaults["Table.alternateRowColor"] = HSLColor(uiDefaults["Table.background"] as Color).adjustTone(10f)

    // Open the main window.
    SwingUtilities.invokeLater {
        if (!PreferencesController.onStartup())
            return@invokeLater

        OpenController.showOpenFrame()
    }
}


private object UncaughtHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        LoggerFactory.getLogger("Uncaught Exception Handler")
            .error("Uncaught exception. Will terminate the program.", e)
        SwingUtilities.invokeLater {
            sendReport(e)
            OpenController.tryExit(force = true)
        }
    }

    private fun sendReport(e: Throwable) {
        val send = JOptionPane.showConfirmDialog(
            null, l10n("ui.crash.msg", e), l10n("ui.crash.title"), JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE
        ) == JOptionPane.YES_OPTION
        if (send) {
            val address = encodeMailURIComponent("cinecred-crash-report@loadingbyte.com")
            val subject = encodeMailURIComponent("Cinecred Crash Report")
            // We replace tabs by four dots because some email programs trim leading tabs and spaces.
            val body = encodeMailURIComponent(JULBuilderHandler.log.toString().replace("\t", "...."))
            Desktop.getDesktop().mail(URI("mailto:$address?Subject=$subject&Body=$body"))
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
    private val threadMxBean = ManagementFactory.getThreadMXBean()
    override fun format(record: LogRecord): String {
        val millis = record.millis - startMillis
        val threadName = threadMxBean.getThreadInfo(record.threadID.toLong()).threadName
        val exc = record.thrown?.stackTraceToString() ?: ""
        val msg = formatMessage(record)
        return "$millis [$threadName] ${record.level} ${record.loggerName} - $msg\n$exc"
    }
}


private object JULBuilderHandler : Handler() {
    init {
        level = Level.INFO
        formatter = JULFormatter
    }

    val log = StringBuilder()
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
        // FFmpeg's log messages contain newline characters, which we have to remove.
        val message = msg.string.trim()
        when (level) {
            AV_LOG_PANIC, AV_LOG_FATAL, AV_LOG_ERROR -> logger.error(message)
            AV_LOG_WARNING -> logger.warn(message)
            AV_LOG_INFO -> logger.info(message)
            AV_LOG_VERBOSE, AV_LOG_DEBUG, AV_LOG_TRACE -> logger.debug(message)
        }
    }
}
