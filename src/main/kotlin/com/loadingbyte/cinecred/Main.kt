package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.util.HSLColor
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.Controller
import com.loadingbyte.cinecred.ui.MainFrame
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
import org.slf4j.impl.SimpleLogger
import java.awt.Color
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager


fun main() {
    // If a unexpected exception reaches the top of a thread's stack, we want to terminate the program in a
    // controlled fashion and inform the user.
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        LoggerFactory.getLogger("Uncaught Catcher").error("Uncaught exception. Will terminate the program.", e)
        JOptionPane.showMessageDialog(
            MainFrame, l10n("ui.main.error.msg", e), l10n("ui.main.error.title"), JOptionPane.ERROR_MESSAGE
        )
        Controller.tryExit()
    }

    // By default, only log warning messages.
    System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "warn")

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
        MainFrame.isVisible = true
    }
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
