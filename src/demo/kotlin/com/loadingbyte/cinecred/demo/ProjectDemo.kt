package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.ProjectDialogType
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import java.awt.Window
import java.lang.Thread.sleep


@Suppress("DEPRECATION")
abstract class ProjectDemo(filename: String, format: Format) : Demo(filename, format) {

    protected abstract fun generate()

    final override fun doGenerate() {
        val masterCtrl = UIFactory().master() as MasterCtrl
        this.masterCtrl = masterCtrl
        withDemoProjectDir { projectDir ->
            tryCopyTemplate(projectDir, locale, CsvFormat, 1)
            edt {
                masterCtrl.openProject(projectDir, openOnScreen = gCfg)
                projectCtrl.setDialogVisible(ProjectDialogType.STYLING, false)
            }
            sleep(500)
            generate()
            edt { Window.getWindows().forEach(Window::dispose) }
            sleep(100)
        }
        this.masterCtrl = null
    }

    private var masterCtrl: MasterCtrl? = null

    protected val projectCtrl get() = masterCtrl!!.leakedProjectCtrls.single()
    protected val projectDir get() = projectCtrl.projectDir
    protected val prjFrame get() = projectCtrl.projectFrame
    protected val prjPanel get() = prjFrame.panel
    protected fun prjImagePanel(pageIdx: Int) = prjPanel.leakedPreviewPanels[pageIdx].leakedImagePanel
    protected val styDialog get() = projectCtrl.stylingDialog
    protected val styPanel get() = styDialog.panel
    protected val vidDialog get() = projectCtrl.videoDialog
    protected val vidPanel get() = vidDialog.panel
    protected val dlvDialog get() = projectCtrl.deliveryDialog
    protected val dlvPanel get() = dlvDialog.panel
    protected val dlvFormatCB get() = dlvPanel.configurationForm.leakedFormatWidget.components[0]

}
