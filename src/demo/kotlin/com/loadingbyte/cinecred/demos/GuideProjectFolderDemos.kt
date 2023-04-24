package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.demo.ScreencastDemo
import com.loadingbyte.cinecred.projectio.CsvFormat
import java.lang.Thread.sleep


private const val DIR = "guide/project-folder"

val GUIDE_PROJECT_FOLDER_DEMOS
    get() = listOf(
        GuideProjectFolderCreateProjectDemo
    )


object GuideProjectFolderCreateProjectDemo : ScreencastDemo("$DIR/create-project", Format.VIDEO_GIF, 700, 500) {
    @Suppress("DEPRECATION")
    override fun generate() {
        addWelcomeWindow(fullscreen = true)

        sc.hold(2 * hold)
        dt.mouseDownAndDragFolder(projectDir)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedStartPanel))
        dt.mouseUp()
        sc.hold(4 * hold)
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgFormatWidget.components[0]))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOfDropdownItem(CsvFormat))
        sc.click()
        sc.mouseTo(welcomeWin.desktopPosOf(projectsPanel.leakedCreCfgDoneButton))
        dt.mouseDown()
        sc.hold()
        dt.mouseUp()

        sleep(1000)
    }
}
