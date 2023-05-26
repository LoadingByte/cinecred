@file:JvmName("DemoMain")

package com.loadingbyte.cinecred

import com.loadingbyte.cinecred.common.FALLBACK_TRANSLATED_LOCALE
import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.common.comprehensivelyApplyLocale
import com.loadingbyte.cinecred.demo.Demo
import com.loadingbyte.cinecred.ui.helper.disableSnapToSide
import com.loadingbyte.cinecred.ui.helper.minimumWindowSize
import java.awt.Dimension
import kotlin.system.exitProcess


private val enabledDemos: List<Demo> = emptyList()


fun main() {
    // We want to shrink windows to smaller sizes than what would usually be sensible.
    minimumWindowSize = Dimension(300, 200)
    // If we were to leave this enabled, we would sometimes not be able to shrink the project and styling windows.
    disableSnapToSide = true

    // Call the regular entry point, which will in turn call our generateDemos() function and abort the regular startup.
    demoCallback = ::generateDemos
    main(emptyArray())
}


private fun generateDemos() {
    Thread({
        for (locale in TRANSLATED_LOCALES) {
            comprehensivelyApplyLocale(locale)
            for (demo in enabledDemos)
                if (demo.isLocaleSensitive || locale == FALLBACK_TRANSLATED_LOCALE)
                    demo.doGenerate(locale)
        }
        // For some reason, some mouseTo() actions which take the mouse to the fake spreadsheet in the screencast
        // demo cause the process to not terminate by itself, so we have to do it forcefully here.
        exitProcess(0)
    }, "DemoGenerator").start()
}
