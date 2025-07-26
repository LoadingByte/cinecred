package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.demo.ScreencastDemo
import com.loadingbyte.cinecred.demo.l10nDemo
import com.loadingbyte.cinecred.projectio.OdsFormat
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import com.loadingbyte.cinecred.projectio.service.Account
import com.loadingbyte.cinecred.projectio.service.GoogleService
import com.loadingbyte.cinecred.ui.LocaleWish
import com.loadingbyte.cinecred.ui.comms.WelcomeTab
import java.lang.Thread.sleep
import javax.swing.JTextField


private const val DIR = "guide/project-folder"

val GUIDE_PROJECT_FOLDER_DEMOS
    get() = listOf(
        GuideProjectFolderCreateProjectDemo,
        GuideProjectFolderAddOnlineAccountDemo
    )


object GuideProjectFolderCreateProjectDemo : ScreencastDemo("$DIR/create-project", Format.VIDEO_GIF, 700, 500) {
    @Suppress("DEPRECATION")
    override fun generate() {
        addWelcomeWindow(fullscreen = true)

        sc.hold(2 * hold)
        dt.mouseDownAndDragFolder(projectDir)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedStartPanel))
        dt.mouseUp()
        sc.hold(8 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgResWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(idx = 2))
        sc.click()
        projectsPanel.projects_createConfigure_setAccounts(listOf(DummyAccount))
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgLocWidget.components[0].getComponent(1)))
        sc.click(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgAccWidget.components[0]))
        sc.click(4 * hold)
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgLocWidget.components[0].getComponent(0)))
        sc.click(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgFormatWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(OdsFormat))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgDoneButton))
        dt.mouseDown()
        sc.hold(2 * hold)
        dt.mouseUp()

        sleep(1000)
    }

    private object DummyAccount : Account {
        override val id get() = l10nDemo("genericName")
        override val service get() = GoogleService
        override fun upload(filename: String?, spreadsheet: Spreadsheet, look: SpreadsheetLook) =
            throw UnsupportedOperationException()
    }
}


object GuideProjectFolderAddOnlineAccountDemo : ScreencastDemo("$DIR/add-online-account", Format.VIDEO_GIF, 700, 500) {
    @Suppress("DEPRECATION")
    override fun generate() {
        addWelcomeWindow(fullscreen = true)
        welcomeFrame.setTab(WelcomeTab.PREFERENCES)
        welcomeFrame.preferences_start_setUILocaleWish(LocaleWish.System)
        welcomeFrame.preferences_start_setCheckForUpdates(true)
        welcomeFrame.preferences_start_setAccounts(emptyList())
        welcomeFrame.preferences_start_setOverlays(emptyList())
        welcomeFrame.preferences_start_setDeliveryDestTemplates(emptyList())

        sc.hold(2 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedStartAddAccountButton))
        sc.click()
        sc.type(welcomeWin, prefsPanel.leakedCfgAccountLabelWidget.components[0] as JTextField, l10nDemo("genericName"))
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgAccountServiceWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(idx = 0))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOf(prefsPanel.leakedCfgAccountEstButton), 4 * hold)
    }
}
