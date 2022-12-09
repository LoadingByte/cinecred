package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.ADD_ICON
import com.loadingbyte.cinecred.ui.helper.PAUSE_ICON
import com.loadingbyte.cinecred.ui.helper.PLAY_ICON
import net.miginfocom.swing.MigLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToggleButton


class DeliveryPanel(ctrl: ProjectController) : JPanel() {

    val configurationForm = DeliverConfigurationForm(ctrl)
    val renderQueuePanel = DeliverRenderQueuePanel(ctrl)

    val addButton = JButton(l10n("ui.delivery.addToRenderQueue"), ADD_ICON)
    val processButton = JToggleButton(l10n("ui.delivery.processRenderQueue"), PLAY_ICON)

    init {
        addButton.addActionListener { configurationForm.addRenderJobToQueue() }

        processButton.addItemListener {
            renderQueuePanel.setProcessRenderJobs(processButton.isSelected)
            processButton.icon = if (processButton.isSelected) PAUSE_ICON else PLAY_ICON
        }

        layout = MigLayout("insets dialog, wrap", "", "[]16[][]")
        add(configurationForm, "growx")
        add(addButton, "split 2, growx")
        add(processButton, "growx")
        add(renderQueuePanel, "grow, push")
    }

}
