package com.loadingbyte.cinecred.demo

import com.loadingbyte.cinecred.projectio.CsvFormat
import com.loadingbyte.cinecred.projectio.tryCopyTemplate
import com.loadingbyte.cinecred.ui.UIFactory
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import java.awt.Window
import java.lang.Thread.sleep
import javax.swing.JComboBox


@Suppress("DEPRECATION")
abstract class ProjectDemo(filename: String, format: Format) : Demo(filename, format) {

    protected abstract fun trees(): List<DockingFrame.Tree>
    protected abstract fun generate()

    final override fun doGenerate() {
        val masterCtrl = UIFactory().master() as MasterCtrl
        this.masterCtrl = masterCtrl
        withDemoProjectDir { projectDir ->
            tryCopyTemplate(projectDir, template(locale), CsvFormat)
            edt { masterCtrl.openProject(projectDir, null, trees()) }
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
    protected val tolDok get() = projectCtrl.leakedToolbarDockable
    protected val preDok get() = projectCtrl.leakedPreviewDockable
    protected val styDok get() = projectCtrl.stylingDockable
    protected val plyDok get() = projectCtrl.leakedPlaybackDockable
    protected val dlvDok get() = projectCtrl.leakedDeliveryDockable
    protected fun prjImagePanel(pageIdx: Int) = preDok.leakedImagePanels[pageIdx]
    protected val plyControls get() = plyDok.leakedControlsPanel
    protected val dlvFormatCB get() = dlvDok.leakedConfigForm.leakedFormatWidget.components[0] as JComboBox<*>

}
