package com.loadingbyte.cinecred.ui.view.delivery

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.comms.DeliveryCtrlComms
import com.loadingbyte.cinecred.ui.comms.DeliverySpecs
import com.loadingbyte.cinecred.ui.comms.DeliveryViewComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.layout.PlatformDefaults
import net.miginfocom.layout.UnitValue
import net.miginfocom.swing.MigLayout
import java.awt.event.KeyEvent.CTRL_DOWN_MASK
import java.awt.event.KeyEvent.VK_B
import javax.swing.*


class DeliveryPanel(deliveryCtrl: DeliveryCtrlComms) : JPanel(), DeliveryViewComms {

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
        addButton.toolTipText = shortcutHint(VK_B, CTRL_DOWN_MASK)
        addButton.addActionListener { deliveryCtrl.onClickAddRenderJobToQueue() }

        renderingButton.addItemListener {
            deliveryCtrl.setRendering(renderingButton.isSelected)
            renderingButton.icon = if (renderingButton.isSelected) PAUSE_ICON else PLAY_ICON
        }

        // To solve issues with laggy resizing, we assign the config form an explicit width, namely the entire available
        // horizontal space. Sadly, just using 100% excludes the insets, so here build a size string that represents the
        // horizontal insets.
        val horInsets = "(" + intArrayOf(1, 3).joinToString(" + ") {
            val v = PlatformDefaults.getDialogInsets(1)
            require(v.unit == UnitValue.LPX && v.operation == UnitValue.STATIC)
            "${v.value}lpx"
        } + ")"

        layout = MigLayout("insets dialog, wrap", "", "[]20[]20[][]")
        add(configurationForm, "wmax (100% - $horInsets), center")
        add(specsAndIssuesPanel, "center")
        add(addButton, "split 2, growx")
        add(renderingButton, "growx")
        add(renderQueuePanel, "grow, push")
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

}
