package com.loadingbyte.cinecred.ui.view.delivery

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nQuoted
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.DeliveryCtrlComms
import com.loadingbyte.cinecred.ui.comms.DeliveryViewComms
import com.loadingbyte.cinecred.ui.helper.center
import com.loadingbyte.cinecred.ui.helper.setup
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.JOptionPane.*


class DeliveryDialog(ctrl: ProjectController, deliveryCtrl: DeliveryCtrlComms) :
    JDialog(ctrl.projectFrame, "${ctrl.projectName} \u2013 ${l10n("ui.delivery.title")} \u2013 Cinecred"),
    DeliveryViewComms {

    val panel = DeliveryPanel(deliveryCtrl)

    init {
        deliveryCtrl.registerView(this)

        setup()

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                deliveryCtrl.setDialogVisibility(true)
            }

            override fun windowClosing(e: WindowEvent) {
                deliveryCtrl.setDialogVisibility(false)
            }
        })

        center(ctrl.openOnScreen, 0.45, 0.7)
        contentPane.add(panel)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun showErroneousProjectMessage() {
        showMessageDialog(
            this, l10n("ui.deliverConfig.noPages.msg"),
            l10n("ui.deliverConfig.noPages.title"), ERROR_MESSAGE
        )
    }

    override fun showNotAFileMessage(path: Path) {
        showMessageDialog(
            this, l10n("ui.deliverConfig.wrongFileType.dir", l10nQuoted(path)),
            l10n("ui.deliverConfig.wrongFileType.title"), ERROR_MESSAGE
        )
    }

    override fun showNotADirMessage(path: Path) {
        showMessageDialog(
            this, l10n("ui.deliverConfig.wrongFileType.file", l10nQuoted(path)),
            l10n("ui.deliverConfig.wrongFileType.title"), ERROR_MESSAGE
        )
    }

    override fun showOverwriteSeqDirReusedQuestion(dir: Path) =
        showConfirmDialog(
            this, l10n("ui.deliverConfig.overwrite.seqDirReused", l10nQuoted(dir)),
            l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
        ) == OK_OPTION

    override fun showOverwriteSeqDirNonEmptyQuestion(dir: Path) =
        showConfirmDialog(
            this, l10n("ui.deliverConfig.overwrite.seqDirNonEmpty", l10nQuoted(dir)),
            l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
        ) == OK_OPTION

    override fun showOverwriteSingleFileReusedQuestion(file: Path) =
        showConfirmDialog(
            this, l10n("ui.deliverConfig.overwrite.singleFileReused", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
        ) == OK_OPTION

    override fun showOverwriteSingleFileExistsQuestion(file: Path) =
        showConfirmDialog(
            this, l10n("ui.deliverConfig.overwrite.singleFileExists", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwrite.title"), OK_CANCEL_OPTION
        ) == OK_OPTION

    override fun showOverwriteProjectDirMessage() {
        showMessageDialog(
            this, l10n("ui.deliverConfig.overwriteProjectDir.msg"),
            l10n("ui.deliverConfig.overwriteProjectDir.title"), ERROR_MESSAGE
        )
    }

    override fun showOverwriteCreditsFileMessage(file: Path) {
        showMessageDialog(
            this, l10n("ui.deliverConfig.overwriteCreditsFile.msg", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwriteCreditsFile.title"), ERROR_MESSAGE
        )
    }

    override fun showCloseDespiteRenderingQuestion(): Boolean {
        val options = arrayOf(l10n("ui.deliverRenderQueue.runningWarning.stop"), l10n("cancel"))
        val selectedOption = showOptionDialog(
            this, l10n("ui.deliverRenderQueue.runningWarning.msg"),
            l10n("ui.deliverRenderQueue.runningWarning.title"),
            DEFAULT_OPTION, WARNING_MESSAGE, null, options, options[0]
        )
        return selectedOption == 0
    }

}
