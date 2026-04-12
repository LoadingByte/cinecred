package com.loadingbyte.cinecred.ui.view.delivery

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nQuoted
import com.loadingbyte.cinecred.ui.Shortcut
import com.loadingbyte.cinecred.ui.comms.DeliveryCtrlComms
import com.loadingbyte.cinecred.ui.comms.DeliverySpecs
import com.loadingbyte.cinecred.ui.comms.DeliveryViewComms
import com.loadingbyte.cinecred.ui.comms.DockableId
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.layout.PlatformDefaults
import net.miginfocom.layout.UnitValue
import net.miginfocom.swing.MigLayout
import java.nio.file.Path
import javax.swing.*
import javax.swing.SwingUtilities.getWindowAncestor


class DeliveryDockable(deliveryCtrl: DeliveryCtrlComms) :
    DockingFrame.Dockable(
        dockableId = DockableId.DELIVERY.name,
        title = l10n("ui.delivery.title"),
        icon = DockableId.DELIVERY.icon
    ),
    DeliveryViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedConfigForm get() = configurationForm
    @Deprecated("ENCAPSULATION LEAK") val leakedRenderQueuePanel get() = renderQueuePanel
    @Deprecated("ENCAPSULATION LEAK") val leakedAddButton get() = addButton
    // =========================================

    private val configurationForm = DeliverConfigurationForm(deliveryCtrl)
    private val renderQueuePanel = DeliverRenderQueuePanel(deliveryCtrl)

    private val specsLabels = List(4) { JLabel("\u2014") }
    private val specsAndIssuesPanel = JPanel(MigLayout("insets 0, wrap"))
    private val addButton = JButton(l10n("ui.delivery.addToRenderQueue"), ADD_ICON)
    private val renderingButton = JToggleButton(l10n("ui.delivery.processRenderQueue"), PLAY_ICON)

    init {
        deliveryCtrl.registerView(this)

        val specsPanel = JPanel(MigLayout("insets 1, gap 1"))
        specsPanel.background = UIManager.getColor("Component.borderColor")
        for (label in specsLabels) {
            label.border = BorderFactory.createEmptyBorder(5, 15, 5, 15)
            label.isOpaque = true
            specsPanel.add(label)
        }
        specsAndIssuesPanel.add(specsPanel, "center")

        addButton.isEnabled = false
        addButton.toolTipText = Shortcut.DELIVERY_ADD_RENDER_JOB.hint
        addButton.addActionListener { deliveryCtrl.onClickAddRenderJobToQueue() }

        renderingButton.addItemListener {
            deliveryCtrl.setRendering(renderingButton.isSelected)
            renderingButton.icon = if (renderingButton.isSelected) PAUSE_ICON else PLAY_ICON
        }

        layout = MigLayout("insets 0, wrap", "", "[](20-${dialogInsets(2)})[]20[][]")
        // To solve issues with laggy resizing, we assign the config form an explicit width, namely the entire
        // available horizontal space.
        add(configurationForm, "wmax 100%, center")
        add(specsAndIssuesPanel, "center")
        add(addButton, "split 2, growx, gapleft ${dialogInsets(1)}")
        add(renderingButton, "growx, gapright ${dialogInsets(3)}")
        add(renderQueuePanel, "grow, push")
    }

    private fun dialogInsets(side: Int): Float {
        val v = PlatformDefaults.getDialogInsets(side)
        require((v.unit == UnitValue.LPX || v.unit == UnitValue.LPY) && v.operation == UnitValue.STATIC)
        return v.value
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setSpecs(specs: DeliverySpecs) {
        // Display the scaled specs in the specs labels.
        specsLabels[0].text = specs.optResolution ?: "\u2014"
        specsLabels[1].text = specs.optFPSAndScan ?: "\u2014"
        specsLabels[2].text = specs.optColorSpace ?: "\u2014"
        specsLabels[3].text = specs.optScrollSpeeds ?: "\u2014"
    }

    override fun clearIssues() {
        while (specsAndIssuesPanel.componentCount > 1)
            specsAndIssuesPanel.remove(1)
        specsAndIssuesPanel.revalidate()
    }

    override fun addIssue(severity: Severity, msg: String) {
        specsAndIssuesPanel.add(JLabel(msg, severity.icon, JLabel.LEADING))
        specsAndIssuesPanel.revalidate()
    }

    override fun setCanAddToRenderQueue(can: Boolean) {
        addButton.isEnabled = can
    }

    override fun setRendering(rendering: Boolean) {
        renderingButton.isSelected = rendering
        renderingButton.icon = if (rendering) PAUSE_ICON else PLAY_ICON
    }

    override fun showErroneousProjectMessage() {
        JOptionPane.showMessageDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.noPages.msg"),
            l10n("ui.deliverConfig.noPages.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNotAFileMessage(path: Path) {
        JOptionPane.showMessageDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.wrongFileType.dir", l10nQuoted(path)),
            l10n("ui.deliverConfig.wrongFileType.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showNotADirMessage(path: Path) {
        JOptionPane.showMessageDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.wrongFileType.file", l10nQuoted(path)),
            l10n("ui.deliverConfig.wrongFileType.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showOverwriteSeqDirReusedQuestion(dir: Path) =
        JOptionPane.showConfirmDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwrite.seqDirReused", l10nQuoted(dir)),
            l10n("ui.deliverConfig.overwrite.title"), JOptionPane.OK_CANCEL_OPTION
        ) == JOptionPane.OK_OPTION

    override fun showOverwriteSeqDirNonEmptyQuestion(dir: Path) =
        JOptionPane.showConfirmDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwrite.seqDirNonEmpty", l10nQuoted(dir)),
            l10n("ui.deliverConfig.overwrite.title"), JOptionPane.OK_CANCEL_OPTION
        ) == JOptionPane.OK_OPTION

    override fun showOverwriteSingleFileReusedQuestion(file: Path) =
        JOptionPane.showConfirmDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwrite.singleFileReused", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwrite.title"), JOptionPane.OK_CANCEL_OPTION
        ) == JOptionPane.OK_OPTION

    override fun showOverwriteSingleFileExistsQuestion(file: Path) =
        JOptionPane.showConfirmDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwrite.singleFileExists", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwrite.title"), JOptionPane.OK_CANCEL_OPTION
        ) == JOptionPane.OK_OPTION

    override fun showOverwriteProjectDirMessage() {
        JOptionPane.showMessageDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwriteProjectDir.msg"),
            l10n("ui.deliverConfig.overwriteProjectDir.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showOverwriteCreditsFileMessage(file: Path) {
        JOptionPane.showMessageDialog(
            getWindowAncestor(this), l10n("ui.deliverConfig.overwriteCreditsFile.msg", l10nQuoted(file)),
            l10n("ui.deliverConfig.overwriteCreditsFile.title"), JOptionPane.ERROR_MESSAGE
        )
    }

    override fun showCloseDespiteRenderingQuestion(): Boolean {
        val options = arrayOf(l10n("ui.deliverRenderQueue.runningWarning.stop"), l10n("cancel"))
        val selectedOption = JOptionPane.showOptionDialog(
            getWindowAncestor(this), l10n("ui.deliverRenderQueue.runningWarning.msg"),
            l10n("ui.deliverRenderQueue.runningWarning.title"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]
        )
        return selectedOption == 0
    }

}
