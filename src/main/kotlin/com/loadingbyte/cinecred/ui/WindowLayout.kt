package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.comms.DockableId.*
import com.loadingbyte.cinecred.ui.helper.DockingFrame
import com.loadingbyte.cinecred.ui.helper.DockingFrame.Orientation.HORIZONTAL
import com.loadingbyte.cinecred.ui.helper.DockingFrame.Orientation.VERTICAL
import com.loadingbyte.cinecred.ui.helper.centered
import java.awt.Rectangle


interface WindowLayout {

    val name: String
    val label: String
    fun trees(bounds: Rectangle): List<DockingFrame.Tree>

}


class PresetWindowLayout private constructor(override val name: String) : WindowLayout {

    companion object {
        val PRESET_DOCKED = PresetWindowLayout("PRESET_DOCKED")
        val PRESET_FLOATING = PresetWindowLayout("PRESET_FLOATING")
        val ALL: List<PresetWindowLayout> = listOf(PRESET_DOCKED, PRESET_FLOATING)
    }

    override val label
        get() = l10n("ui.windowLayouts.label.$name")

    override fun trees(bounds: Rectangle) = when (name) {
        "PRESET_DOCKED" -> listOf(
            DockingFrame.Tree(
                bounds = bounds,
                leftRetractable = false, rightRetractable = true,
                DockingFrame.Node.Split(
                    HORIZONTAL, ratio = 0.5,
                    DockingFrame.Node.Split(
                        VERTICAL, ratio = 0.0,
                        DockingFrame.Node.Leaf(TOOLBAR.name, collapsed = false),
                        DockingFrame.Node.Split(
                            VERTICAL, ratio = 0.85,
                            DockingFrame.Node.Split(
                                VERTICAL, ratio = 0.5,
                                DockingFrame.Node.Leaf(PREVIEW.name, collapsed = false),
                                DockingFrame.Node.Leaf(PLAYBACK.name, collapsed = true)
                            ),
                            DockingFrame.Node.Leaf(LOG.name, collapsed = false)
                        )
                    ),
                    DockingFrame.Node.Split(
                        VERTICAL, ratio = 0.4,
                        DockingFrame.Node.Leaf(STYLING.name, collapsed = false),
                        DockingFrame.Node.Leaf(DELIVERY.name, collapsed = true)
                    )
                )
            )
        )

        "PRESET_FLOATING" -> listOf(
            DockingFrame.Tree(
                bounds = Rectangle(bounds).apply { width /= 2 },
                leftRetractable = false, rightRetractable = false,
                DockingFrame.Node.Split(
                    VERTICAL, ratio = 0.0,
                    DockingFrame.Node.Leaf(TOOLBAR.name, collapsed = false),
                    DockingFrame.Node.Split(
                        VERTICAL, ratio = 0.85,
                        DockingFrame.Node.Leaf(PREVIEW.name, collapsed = false),
                        DockingFrame.Node.Leaf(LOG.name, collapsed = false)
                    )
                )
            ),
            DockingFrame.Tree(
                bounds = Rectangle(bounds).apply { width /= 2; x += width },
                leftRetractable = false, rightRetractable = false,
                DockingFrame.Node.Leaf(STYLING.name, collapsed = false)
            ),
            DockingFrame.Tree(
                bounds = bounds.centered(0.5, 0.5),
                leftRetractable = false, rightRetractable = false,
                DockingFrame.Node.Leaf(PLAYBACK.name, collapsed = true)
            ),
            DockingFrame.Tree(
                bounds = bounds.centered(0.45, 0.7),
                leftRetractable = false, rightRetractable = false,
                DockingFrame.Node.Leaf(DELIVERY.name, collapsed = true)
            )
        )

        else -> throw IllegalStateException()
    }

}


class ConfigurableWindowLayout(override val name: String, val trees: List<DockingFrame.Tree>) : WindowLayout {

    override val label get() = name
    override fun trees(bounds: Rectangle) = trees

}
