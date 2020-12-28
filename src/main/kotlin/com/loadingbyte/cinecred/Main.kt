package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.ui.MainFrame
import net.miginfocom.layout.PlatformDefaults
import javax.swing.SwingUtilities


fun main() {
    // Platform-specific gaps etc. mess up our rather intricate layouts.
    // To alleviate this, we just force one invariant set of platform defaults.
    PlatformDefaults.setPlatform(PlatformDefaults.GNOME)

    SwingUtilities.invokeLater {
        MainFrame.isVisible = true
    }
}
