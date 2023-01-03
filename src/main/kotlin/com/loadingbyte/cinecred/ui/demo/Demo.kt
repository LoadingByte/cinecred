package com.loadingbyte.cinecred.ui.demo

import com.loadingbyte.cinecred.common.TRANSLATED_LOCALES
import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.helper.disableSnapToSide
import kotlin.system.exitProcess


private const val ENABLE_SCREENSHOT_DEMO = false
private const val ENABLE_SCREENCAST_DEMO = false

fun demo(masterCtrl: MasterCtrlComms): Boolean {
    if (!ENABLE_SCREENSHOT_DEMO && !ENABLE_SCREENCAST_DEMO)
        return false

    // If we were to leave this enabled, we sometimes could not shrink the project and styling windows.
    disableSnapToSide = true
    Thread({
        if (ENABLE_SCREENSHOT_DEMO)
            for (locale in TRANSLATED_LOCALES)
                screenshotDemo(masterCtrl as MasterCtrl, locale)
        if (ENABLE_SCREENCAST_DEMO)
            for (locale in TRANSLATED_LOCALES)
                screencastDemo(masterCtrl as MasterCtrl, locale)
        // For some reason, some mouseTo() actions which take the mouse to the fake spreadsheet in the screencast demo
        // cause the process to not terminate by itself, so we have to do it forcefully here.
        exitProcess(0)
    }, "Demo").start()
    return true
}
