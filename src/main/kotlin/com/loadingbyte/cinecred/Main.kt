package com.loadingbyte.cinecred

import com.formdev.flatlaf.FlatDarkLaf
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
import javax.swing.SwingUtilities


fun main() {
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

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we just force one invariant set of platform defaults.
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)

    // Set the Swing Look & Feel.
    FlatDarkLaf.install()

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
