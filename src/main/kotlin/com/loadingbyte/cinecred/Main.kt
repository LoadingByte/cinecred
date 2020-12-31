package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.ui.MainFrame
import net.miginfocom.layout.PlatformDefaults
import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.slf4j.LoggerFactory
import org.slf4j.impl.SimpleLogger
import javax.swing.SwingUtilities


fun main() {
    // By default, only log warning messages.
    System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "warn")

    // Redirect FFmpeg's logging output to slf4j.
    System.setProperty("org.bytedeco.javacpp.logger", "slf4j")
    setLogCallback(FFmpegLogCallback)

    // MigLayout's platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we just force one invariant set of platform defaults.
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)

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
