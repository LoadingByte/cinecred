package com.loadingbyte.cinecred.ui.view.playback

import com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.DeckLink
import com.loadingbyte.cinecred.ui.comms.PlaybackCtrlComms
import com.loadingbyte.cinecred.ui.comms.PlaybackViewComms
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent.*
import java.util.*
import javax.swing.*


class PlaybackControlsPanel(private val playbackCtrl: PlaybackCtrlComms) : JPanel(), PlaybackViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedPlayButton get() = playButton
    @Deprecated("ENCAPSULATION LEAK") val leakedFrameSlider get() = frameSlider
    // =========================================

    private val deckLinkComboBox: JComboBox<DeckLinkWrapper>
    private val deckLinkModeComboBox: JComboBox<DeckLinkModeWrapper>
    private val deckLinkDepthComboBox: JComboBox<DeckLinkDepthWrapper>
    private val deckLinkConnectedButton: JToggleButton
    private val deckLinkSeparator = JSeparator(JSeparator.VERTICAL)
    private val spreadsheetNameComboBox: JComboBox<String>
    private val rewindButton: JToggleButton
    private val pauseButton: JToggleButton
    private val playButton: JToggleButton
    private val frameSlider: JSlider
    private val timecodeLabel = JLabel().apply { putClientProperty(STYLE_CLASS, "monospaced") }

    private var curTimecode: String? = null
    private var maxTimecode: String? = null
    private var suppressSliderChangeEvents = false

    init {
        playbackCtrl.registerView(this)

        deckLinkComboBox = JComboBox<DeckLinkWrapper>().apply {
            isFocusable = false
            addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED)
                    playbackCtrl.setSelectedDeckLink((e.item as DeckLinkWrapper).deckLink)
            }
        }
        deckLinkModeComboBox = JComboBox<DeckLinkModeWrapper>().apply {
            isFocusable = false
            addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED)
                    playbackCtrl.setSelectedDeckLinkMode((e.item as DeckLinkModeWrapper).mode)
            }
        }
        deckLinkDepthComboBox = JComboBox<DeckLinkDepthWrapper>().apply {
            isFocusable = false
            addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED)
                    playbackCtrl.setSelectedDeckLinkDepth((e.item as DeckLinkDepthWrapper).depth)
            }
        }
        deckLinkConnectedButton =
            newToolbarToggleButton(PLUG_ICON, l10n("ui.video.connectToDeckLink"), VK_L, CTRL_DOWN_MASK) { isSelected ->
                playbackCtrl.setDeckLinkConnected(isSelected)
            }

        spreadsheetNameComboBox = JComboBox<String>().apply {
            isFocusable = false
            addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED)
                    playbackCtrl.setSelectedSpreadsheetName(e.item as String)
            }
        }

        val rewindIcon = PLAY_ICON.getScaledIcon(-1.0, 1.0)
        rewindButton = newToolbarPlayButton(rewindIcon, l10n("ui.video.rewind"), VK_J, playbackCtrl::rewind)
        pauseButton = newToolbarPlayButton(PAUSE_ICON, l10n("ui.video.pause"), VK_K, playbackCtrl::pause)
        playButton = newToolbarPlayButton(PLAY_ICON, l10n("ui.video.play"), VK_L, playbackCtrl::play)

        frameSlider = JSlider().also { frameSlider ->
            frameSlider.value = 0
            frameSlider.isFocusable = false
            frameSlider.addChangeListener { if (!suppressSliderChangeEvents) playbackCtrl.scrub(frameSlider.value) }
        }

        layout = MigLayout("insets 0, hidemode 3", "[][]2[]2[][][]")
        add(deckLinkComboBox, "split 6, wmax 80")
        add(deckLinkModeComboBox, "wmax 80")
        add(deckLinkDepthComboBox, "wmax 50")
        add(deckLinkConnectedButton)
        add(deckLinkSeparator, "growy, shrink 0 0, gapright unrel")
        add(spreadsheetNameComboBox)
        add(rewindButton)
        add(pauseButton)
        add(playButton)
        add(frameSlider, "growx, pushx")
        add(timecodeLabel)
    }

    private fun newToolbarPlayButton(icon: Icon, ttip: String, kc: Int, listener: () -> Unit) =
        newToolbarToggleButton(icon, ttip, kc, 0).also { btn ->
            // Register an ActionListener (and not an ItemListener) to prevent feedback loops.
            btn.addActionListener { listener() }
        }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setDeckLinks(deckLinks: List<DeckLink>) {
        deckLinkComboBox.model = DefaultComboBoxModel(deckLinks.mapTo(Vector(), ::DeckLinkWrapper))
        val visible = deckLinks.isNotEmpty()
        deckLinkComboBox.isVisible = visible
        deckLinkModeComboBox.isVisible = visible
        deckLinkDepthComboBox.isVisible = visible
        deckLinkConnectedButton.isVisible = visible
        deckLinkSeparator.isVisible = visible
    }

    override fun setDeckLinkModes(modes: List<DeckLink.Mode>) {
        deckLinkModeComboBox.model = DefaultComboBoxModel(modes.mapTo(Vector(), ::DeckLinkModeWrapper))
    }

    override fun setDeckLinkDepths(depths: List<DeckLink.Depth>) {
        deckLinkDepthComboBox.model = DefaultComboBoxModel(depths.mapTo(Vector(), ::DeckLinkDepthWrapper))
    }

    override fun setSelectedDeckLink(deckLink: DeckLink) {
        deckLinkComboBox.selectedItem = DeckLinkWrapper(deckLink)
    }

    override fun setSelectedDeckLinkMode(mode: DeckLink.Mode) {
        deckLinkModeComboBox.selectedItem = DeckLinkModeWrapper(mode)
    }

    override fun setSelectedDeckLinkDepth(depth: DeckLink.Depth) {
        deckLinkDepthComboBox.selectedItem = DeckLinkDepthWrapper(depth)
    }

    override fun setDeckLinkConnected(connected: Boolean) {
        deckLinkConnectedButton.isSelected = connected
    }

    override fun setSpreadsheetNames(spreadsheetNames: List<String>) {
        spreadsheetNameComboBox.model = DefaultComboBoxModel(spreadsheetNames.toTypedArray())
    }

    override fun setSelectedSpreadsheetName(spreadsheetName: String) {
        spreadsheetNameComboBox.selectedItem = spreadsheetName
    }

    override fun setNumFrames(numFrames: Int, timecodeString: String) {
        suppressSliderChangeEvents = true
        try {
            frameSlider.maximum = numFrames - 1
        } finally {
            suppressSliderChangeEvents = false
        }
        maxTimecode = timecodeString
        timecodeLabel.text = "$curTimecode / $maxTimecode"
    }

    override fun setPlayheadPosition(frameIdx: Int, timecodeString: String) {
        suppressSliderChangeEvents = true
        try {
            frameSlider.value = frameIdx
        } finally {
            suppressSliderChangeEvents = false
        }
        curTimecode = timecodeString
        timecodeLabel.text = "$curTimecode / $maxTimecode"
    }

    override fun setPlaybackDirection(direction: Int) {
        rewindButton.isSelected = direction == -1
        pauseButton.isSelected = direction == 0
        playButton.isSelected = direction == 1
    }


    private data class DeckLinkWrapper(val deckLink: DeckLink) {
        override fun toString() = noEllipsisLabel(deckLink.name)
    }

    private data class DeckLinkModeWrapper(val mode: DeckLink.Mode) {
        override fun toString() = noEllipsisLabel(mode.name)
    }

    private data class DeckLinkDepthWrapper(val depth: DeckLink.Depth) {
        override fun toString() = noEllipsisLabel("${depth.bits} bit")
    }

}
