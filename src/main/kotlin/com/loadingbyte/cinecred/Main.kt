package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.json.Json
import com.formdev.flatlaf.util.HSLColor
import com.formdev.flatlaf.util.SystemInfo
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.ui.OpenController
import com.loadingbyte.cinecred.ui.PreferencesController
import com.loadingbyte.cinecred.ui.helper.tryBrowse
import com.loadingbyte.cinecred.ui.helper.tryMail
import com.loadingbyte.cinecred.ui.makeOpenHintTrack
import com.loadingbyte.cinecred.ui.playIfPending
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
import java.awt.Desktop
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import java.util.logging.*
import java.util.logging.Formatter
import javax.swing.*
import kotlin.io.path.*


private const val SINGLETON_APP_ID = "com.loadingbyte.cinecred"

fun main() {
    // Cinecred is a singleton application. When the application is launched a second time, we just simulate
    // a second application instance in the same VM.
    if (Singleton.invoke(SINGLETON_APP_ID, emptyArray()))
        return
    Singleton.start({ OpenController.showOpenFrame() }, SINGLETON_APP_ID)

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

    // Load all natives from the system-specific directory in the natives/ resource folder.
    val nativesExDir = System.getProperty("java.io.tmpdir") + "/cinecred-natives-" + System.getProperty("user.name")
    withResource("/natives/" + Loader.Detector.getPlatform()) { nativesDir ->
        for (file in nativesDir.listDirectoryEntries())
            if (file.fileSystem.provider().scheme == "file")
                System.load(file.toString())
            else {
                Path(nativesExDir).createDirectories()
                val exFile = Path(nativesExDir, file.name)
                file.copyTo(exFile, overwrite = true)
                System.load(exFile.toString())
            }
    }

    // Let JavaCPP extract its natives into the temp dir, so that they don't clutter the user's filesystem.
    System.setProperty("org.bytedeco.javacpp.cachedir", nativesExDir)
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
        LOGGER.error("Failed to load FFmpeg", t)
    }

    SwingUtilities.invokeLater(::mainSwing)
}


fun mainSwing() {
    // Tooltips should not disappear on their own after some time.
    // To achieve this, we set the dismiss delay to one hour.
    ToolTipManager.sharedInstance().dismissDelay = 60 * 60 * 1000

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we force one invariant set of platform defaults.
    // We chose the Gnome defaults because they waste the least space on gaps.
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)

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

    // Globally listen to all key events.
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(OpenController::onGlobalKeyEvent)

    // Load the preferences, show a form if they have not yet been set, and terminate if the user cancels the form.
    if (!PreferencesController.onStartup())
        return

    // If configured accordingly, check for updates in the background and show a dialog if an update is available.
    if (PreferencesController.checkForUpdates)
        checkForUpdates()

    // On MacOS, allow the user to open the preferences via the OS.
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES))
        Desktop.getDesktop().setPreferencesHandler {
            val activeScreen = FocusManager.getCurrentManager().activeWindow.graphicsConfiguration
            PreferencesController.showPreferencesDialog(activeScreen)
        }

    // On MacOS, don't suddenly terminate the application when the user quits it or logs off, but instead try to close
    // all windows, which in turn triggers all "unsaved changes" dialogs.
    if (Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER))
        Desktop.getDesktop().setQuitHandler { _, response ->
            OpenController.tryCloseProjectsAndDisposeAllFrames()
            response.performQuit()
        }

    // Show the project overview window ("OpenFrame").
    OpenController.showOpenFrame()
    makeOpenHintTrack(OpenController.getOpenFrame()!!).playIfPending()
}


private const val DL_API_URL = "https://loadingbyte.com/cinecred/dl/api/v1/components"
private const val HOMEPAGE_URL = "https://loadingbyte.com/cinecred/"

private fun checkForUpdates() {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    client.sendAsync(
        HttpRequest.newBuilder(URI.create(DL_API_URL)).build(),
        HttpResponse.BodyHandlers.ofString()
    ).thenAccept { resp ->
        if (resp.statusCode() != 200)
            return@thenAccept

        // Note: If the following processing fails for some reason, the thrown exception is just swallowed by the
        // CompletableFuture context. Since we do not need to react to a failed update check, this is finde.
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(StringReader(resp.body())) as Map<String, List<Map<String, String>>>
        val latestStableVersion = root
            .getValue("components")
            .firstOrNull { it["qualifier"] == "Release" }
            ?.getValue("version")

        if (latestStableVersion != null && latestStableVersion != VERSION)
            SwingUtilities.invokeLater {
                val openHomepage = JOptionPane.showConfirmDialog(
                    OpenController.getOpenFrame(), l10n("ui.updateAvailable.msg", VERSION, latestStableVersion),
                    l10n("ui.updateAvailable.title"), JOptionPane.YES_NO_OPTION
                ) == JOptionPane.YES_OPTION
                if (openHomepage)
                    tryBrowse(URI(HOMEPAGE_URL))
            }
    }
}


private object UncaughtHandler : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        LOGGER.error("Uncaught exception. Will terminate the program.", e)
        SwingUtilities.invokeLater {
            sendReport(e)
            // Once all frames have been disposed, no more non-daemon threads are running and hence Java will terminate.
            OpenController.tryCloseProjectsAndDisposeAllFrames(force = true)
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
        // FFmpeg's log messages end with a newline character, which we have to remove.
        val message = msg.string.trim()
        when (level) {
            AV_LOG_PANIC, AV_LOG_FATAL, AV_LOG_ERROR -> logger.error(message)
            AV_LOG_WARNING -> logger.warn(message)
            AV_LOG_INFO -> logger.info(message)
            AV_LOG_VERBOSE, AV_LOG_DEBUG -> logger.debug(message)
            AV_LOG_TRACE -> logger.trace(message)
        }
    }
}
