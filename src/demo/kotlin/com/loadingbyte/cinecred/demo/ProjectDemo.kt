package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.ProjectDialogType
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import java.awt.Window
import java.lang.Thread.sleep
import java.nio.file.Path
import javax.swing.JComboBox


@Suppress("DEPRECATION")
abstract class ProjectDemo(filename: String, format: Format) : Demo(filename, format) {

    protected open fun prepare(projectDir: Path) {}
    protected abstract fun generate()

    final override fun doGenerate() {
        val masterCtrl = UIFactory().master() as MasterCtrl
        this.masterCtrl = masterCtrl
        withDemoProjectDir { projectDir ->
            tryCopyTemplate(projectDir, template(locale), CsvFormat)
            prepare(projectDir)
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
    protected val prjFrame get() = projectCtrl.projectFrame
    protected val prjPanel get() = prjFrame.panel
    protected fun prjImagePanel(pageIdx: Int) = prjPanel.leakedImagePanels[pageIdx]
    protected val styDialog get() = projectCtrl.stylingDialog
    protected val styPanel get() = styDialog.panel
    protected val plyDialog get() = projectCtrl.playbackDialog
    protected val plyPanel get() = plyDialog.panel
    protected val plyControls get() = plyPanel.leakedControlsPanel
    protected val dlvDialog get() = projectCtrl.deliveryDialog
    protected val dlvPanel get() = dlvDialog.panel
    protected val dlvFormatCB get() = dlvPanel.configurationForm.leakedFormatWidget.components[0] as JComboBox<*>

}
