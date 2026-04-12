package com.loadingbyte.cinecred.ui.view.playback

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.ui.Shortcut
import com.loadingbyte.cinecred.ui.Shortcut.*
import com.loadingbyte.cinecred.ui.comms.CreditsId
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.event.ItemEvent
import javax.swing.*


class PlaybackControlsPanel(private val playbackCtrl: PlaybackCtrlComms) : JPanel(), PlaybackViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedDeckLinkConfigButton get() = deckLinkConfigButton
    @Deprecated("ENCAPSULATION LEAK") val leakedDeckLinkConnectedButton get() = deckLinkConnectedButton
    @Deprecated("ENCAPSULATION LEAK") val leakedPlayButton get() = playButton
    @Deprecated("ENCAPSULATION LEAK") val leakedFrameSlider get() = frameSlider
    // =========================================

    private val deckLinkLabel = JLabel("DeckLink")

    private val deckLinkConfigButton: JButton
    private val deckLinkConfigMenu: DropdownPopupMenu
    private val deckLinkSubmenu: DeckLinkSubmenu<DeckLink>
    private val deckLinkModeSubmenu: DeckLinkSubmenu<DeckLink.Mode>
    private val deckLinkDepthSubmenu: DeckLinkSubmenu<DeckLink.Depth>
    private val deckLinkPrimariesSubmenu: DeckLinkSubmenu<ColorSpace.Primaries>
    private val deckLinkTransferSubmenu: DeckLinkSubmenu<ColorSpace.Transfer>

    private val deckLinkConnectedButton: JToggleButton
    private val deckLinkSeparator = JSeparator(JSeparator.VERTICAL)
    private val creditsIdComboBox: JComboBox<CreditsIdWrapper>
    private val rewindButton: JToggleButton
    private val pauseButton: JToggleButton
    private val playButton: JToggleButton
    private val frameSlider: JSlider
    private val timecodeLabel = JLabel().apply { putClientProperty(STYLE_CLASS, "monospaced") }

    private var adjustingSlider = false
    private var curTimecode: String? = null
    private var maxTimecode: String? = null

    init {
        playbackCtrl.registerView(this)

        deckLinkConfigButton = newToolbarButton(GEAR_ICON, l10n("ui.video.configureDeckLink"))
        deckLinkConfigMenu = DropdownPopupMenu(deckLinkConfigButton)
        deckLinkConfigMenu.addMouseListenerTo(deckLinkConfigButton)
        deckLinkConfigMenu.addKeyListenerTo(deckLinkConfigButton)
        deckLinkSubmenu = DeckLinkSubmenu(
            deckLinkConfigMenu, null, DeckLink::name, playbackCtrl::setSelectedDeckLink
        )
        deckLinkModeSubmenu = DeckLinkSubmenu(
            deckLinkConfigMenu, l10n("ui.video.configureDeckLink.mode"), DeckLink.Mode::name,
            playbackCtrl::setSelectedDeckLinkMode
        )
        deckLinkDepthSubmenu = DeckLinkSubmenu(
            deckLinkConfigMenu, l10n("bitDepth"), { it.bits.toString() }, playbackCtrl::setSelectedDeckLinkDepth
        )
        deckLinkPrimariesSubmenu = DeckLinkSubmenu(
            deckLinkConfigMenu, l10n("gamut"), ColorSpace.Primaries::name,
            playbackCtrl::setSelectedDeckLinkPrimaries
        )
        deckLinkTransferSubmenu = DeckLinkSubmenu(
            deckLinkConfigMenu, "EOTF", ColorSpace.Transfer::name, playbackCtrl::setSelectedDeckLinkTransfer
        )
        deckLinkConfigMenu.add(deckLinkSubmenu)
        deckLinkConfigMenu.add(deckLinkModeSubmenu)
        deckLinkConfigMenu.add(deckLinkDepthSubmenu)
        deckLinkConfigMenu.add(deckLinkPrimariesSubmenu)
        deckLinkConfigMenu.add(deckLinkTransferSubmenu)

        deckLinkConnectedButton = newToolbarToggleButton(
            PLUG_ICON, l10n("ui.video.connectToDeckLink"), PLAYBACK_TOGGLE_DECK_LINK_CONNECTED,
            listener = playbackCtrl::setDeckLinkConnected
        )

        creditsIdComboBox = JComboBox<CreditsIdWrapper>().apply {
            isFocusable = false
            addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED)
                    playbackCtrl.setSelectedCreditsId((e.item as CreditsIdWrapper).item)
            }
        }

        val rewindIcon = PLAY_ICON.getScaledIcon(-1.0, 1.0)
        rewindButton = newToolbarPlayButton(rewindIcon, l10n("ui.video.rewind"), PLAYBACK_REWIND, playbackCtrl::rewind)
        pauseButton = newToolbarPlayButton(PAUSE_ICON, l10n("ui.video.pause"), PLAYBACK_PAUSE, playbackCtrl::pause)
        playButton = newToolbarPlayButton(PLAY_ICON, l10n("ui.video.play"), PLAYBACK_PLAY, playbackCtrl::play)

        frameSlider = JSlider().also { frameSlider ->
            frameSlider.value = 0
            frameSlider.isFocusable = false
            frameSlider.addChangeListener {
                // Note: This listener also listens for changes to the "valueIsAdjusting" property.
                if (frameSlider.valueIsAdjusting) {
                    if (!adjustingSlider) {
                        adjustingSlider = true
                        playbackCtrl.startScrubbing()
                    }
                    playbackCtrl.scrub(frameSlider.value)
                } else if (adjustingSlider) {
                    adjustingSlider = false
                    playbackCtrl.stopScrubbing()
                }
            }
        }

        layout = MigLayout("insets 0, hidemode 3", "[][]2[]2[][][]")
        add(deckLinkLabel, "split 5")
        add(deckLinkConfigButton, "gapright 2")
        add(deckLinkConnectedButton)
        add(deckLinkSeparator, "growy, wmin pref, gapright unrel")
        add(creditsIdComboBox, "wmin 80, wmax 15%")
        add(rewindButton)
        add(pauseButton)
        add(playButton)
        add(frameSlider, "wmin 120, growx, pushx")
        add(timecodeLabel, "wmin 120")
    }

    private fun newToolbarPlayButton(icon: Icon, ttip: String, shortcut: Shortcut, listener: () -> Unit) =
        newToolbarToggleButton(icon, ttip, shortcut).also { btn ->
            // Register an ActionListener (and not an ItemListener) to prevent feedback loops.
            btn.addActionListener { listener() }
        }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setDeckLinks(deckLinks: List<DeckLink>) {
        deckLinkSubmenu.items = deckLinks
        val visible = deckLinks.isNotEmpty()
        deckLinkLabel.isVisible = visible
        deckLinkConfigButton.isVisible = visible
        if (!visible) deckLinkConfigMenu.isVisible = false
        deckLinkConnectedButton.isVisible = visible
        deckLinkSeparator.isVisible = visible
    }

    // @formatter:off
    override fun setDeckLinkModes(modes: List<DeckLink.Mode>) { deckLinkModeSubmenu.items = modes }
    override fun setDeckLinkDepths(depths: List<DeckLink.Depth>) { deckLinkDepthSubmenu.items = depths }
    override fun setDeckLinkPrimaries(primaries: List<ColorSpace.Primaries>) { deckLinkPrimariesSubmenu.items = primaries }
    override fun setDeckLinkTransfers(transfers: List<ColorSpace.Transfer>) { deckLinkTransferSubmenu.items = transfers }
    override fun setSelectedDeckLink(deckLink: DeckLink) { deckLinkSubmenu.value = deckLink }
    override fun setSelectedDeckLinkMode(mode: DeckLink.Mode) { deckLinkModeSubmenu.value = mode }
    override fun setSelectedDeckLinkDepth(depth: DeckLink.Depth) { deckLinkDepthSubmenu.value = depth }
    override fun setSelectedDeckLinkPrimaries(primaries: ColorSpace.Primaries) { deckLinkPrimariesSubmenu.value = primaries }
    override fun setSelectedDeckLinkTransfer(transfer: ColorSpace.Transfer) { deckLinkTransferSubmenu.value = transfer }
    // @formatter:on

    override fun setDeckLinkConnected(connected: Boolean) {
        deckLinkConnectedButton.isSelected = connected
    }

    override fun setCreditsIds(creditsIds: List<CreditsId>) {
        creditsIdComboBox.model = DefaultComboBoxModel(creditsIds.map(::CreditsIdWrapper).toTypedArray())
    }

    override fun setSelectedCreditsId(creditsId: CreditsId) {
        creditsIdComboBox.selectedItem = CreditsIdWrapper(creditsId)
    }

    override fun setNumFrames(numFrames: Int, timecodeString: String) {
        frameSlider.maximum = numFrames - 1
        maxTimecode = timecodeString
        timecodeLabel.text = "$curTimecode / $maxTimecode"
    }

    override fun setPlayheadPosition(frameIdx: Int, timecodeString: String) {
        frameSlider.value = frameIdx
        curTimecode = timecodeString
        timecodeLabel.text = "$curTimecode / $maxTimecode"
    }

    override fun setPlaybackDirection(direction: Int) {
        rewindButton.isSelected = direction == -1
        pauseButton.isSelected = direction == 0
        playButton.isSelected = direction == 1
    }


    private data class CreditsIdWrapper(override val item: CreditsId) : ComboBoxWrapper {
        override fun toString() = "${item.fileName} \u2192 ${item.spreadsheetName}"
    }


    private class DeckLinkSubmenu<E : Any>(
        private val dropdownPopupMenu: DropdownPopupMenu,
        private val label: String?,
        private val toString: (E) -> String,
        private val onSelect: (E) -> Unit
    ) : DropdownPopupMenuSubmenu("") {

        private val btnGroup = ButtonGroup()

        var items: List<E> = emptyList()
            set(items) {
                if (field == items) return
                val sel = value
                field = items
                removeAll()
                btnGroup.elements.toList() /* copy for concurrent modification */.forEach(btnGroup::remove)
                for (item in items) {
                    val menuItem = DropdownPopupMenuCheckBoxItem(
                        dropdownPopupMenu, null, toString(item), isSelected = item == sel
                    )
                    val itemStr = toString(item)
                    menuItem.addItemListener { e ->
                        if (e.stateChange == ItemEvent.SELECTED) {
                            text = if (label == null) itemStr else "$label: $itemStr"
                            onSelect(item)
                        }
                    }
                    popupMenu.add(menuItem)
                    btnGroup.add(menuItem)
                }
            }

        var value: E?
            get() = items.getOrNull(btnGroup.elements.asSequence().indexOfFirst { it.isSelected })
            set(value) {
                val idx = items.indexOf(value)
                if (idx == -1)
                    btnGroup.clearSelection()
                else
                    btnGroup.setSelected(btnGroup.elements.asSequence().drop(idx).first().model, true)
            }

    }

}
