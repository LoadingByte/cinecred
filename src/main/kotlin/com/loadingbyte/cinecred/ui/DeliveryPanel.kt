package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.ADD_ICON
import com.loadingbyte.cinecred.ui.helper.PAUSE_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import net.miginfocom.layout.PlatformDefaults
import net.miginfocom.layout.UnitValue
import net.miginfocom.swing.MigLayout
import javax.swing.*


class DeliveryPanel(ctrl: ProjectController) : JPanel() {

    val configurationForm = DeliverConfigurationForm(ctrl)
    val renderQueuePanel = DeliverRenderQueuePanel(ctrl)

    val specsLabels = List(4) { JLabel() }
    private val specsAndIssuesPanel = JPanel(MigLayout("insets 0, wrap"))
    val addButton = JButton(l10n("ui.delivery.addToRenderQueue"), ADD_ICON)
    val processButton = JToggleButton(l10n("ui.delivery.processRenderQueue"), PLAY_ICON)

    init {
        val specsPanel = JPanel(MigLayout("insets 1, gap 1"))
        specsPanel.background = UIManager.getColor("Component.borderColor")
        for (label in specsLabels) {
            label.border = BorderFactory.createEmptyBorder(5, 15, 5, 15)
            label.isOpaque = true
            specsPanel.add(label)
        }
        specsAndIssuesPanel.add(specsPanel, "center")

        addButton.isEnabled = false
        addButton.addActionListener { configurationForm.addRenderJobToQueue() }

        processButton.addItemListener {
            renderQueuePanel.setProcessRenderJobs(processButton.isSelected)
            processButton.icon = if (processButton.isSelected) PAUSE_ICON else PLAY_ICON
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
        add(processButton, "growx")
        add(renderQueuePanel, "grow, push")
    }

    fun clearIssues() {
        while (specsAndIssuesPanel.componentCount > 1)
            specsAndIssuesPanel.remove(1)
        specsAndIssuesPanel.revalidate()
    }

    fun addIssue(icon: Icon, msg: String) {
        specsAndIssuesPanel.add(JLabel(msg, icon, JLabel.LEADING))
        specsAndIssuesPanel.revalidate()
    }

}
