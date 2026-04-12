package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.ColorSpace
import com.loadingbyte.cinecred.imaging.DeckLink
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.image.BufferedImage


interface PlaybackCtrlComms {

    fun registerView(view: PlaybackViewComms)
    fun isFullScreenWindow(window: Window): Boolean
    fun updateProject(drawnProject: DrawnProject)
    fun closeProject()
    fun onShowOrHide(visible: Boolean)
    fun setSelectedDeckLink(deckLink: DeckLink)
    fun setSelectedDeckLinkMode(mode: DeckLink.Mode)
    fun setSelectedDeckLinkDepth(depth: DeckLink.Depth)
    fun setSelectedDeckLinkPrimaries(primaries: ColorSpace.Primaries)
    fun setSelectedDeckLinkTransfer(transfer: ColorSpace.Transfer)
    fun setDeckLinkConnected(connected: Boolean)
    fun toggleDeckLinkConnected()
    fun setPreviewCreditsId(creditsId: CreditsId?)
    fun setSelectedCreditsId(creditsId: CreditsId)
    fun startScrubbing()
    fun stopScrubbing()
    fun scrub(frameIdx: Int)
    fun scrubRelativeFrames(frames: Int)
    fun scrubRelativeSeconds(seconds: Int)
    fun rewind()
    fun pause()
    fun play()
    fun togglePlay()
    fun setVideoCanvasSize(size: Dimension, gCfg: GraphicsConfiguration)
    fun setActualSize(actualSize: Boolean)
    fun toggleActualSize()
    fun setFullScreen(fullScreen: Boolean): Boolean
    fun toggleFullScreen()

}


interface PlaybackViewComms {

    fun isFullScreenWindow(window: Window): Boolean = false
    fun disposeResources() {}
    fun setDeckLinks(deckLinks: List<DeckLink>) {}
    fun setDeckLinkModes(modes: List<DeckLink.Mode>) {}
    fun setDeckLinkDepths(depths: List<DeckLink.Depth>) {}
    fun setDeckLinkPrimaries(primaries: List<ColorSpace.Primaries>) {}
    fun setDeckLinkTransfers(transfers: List<ColorSpace.Transfer>) {}
    fun setSelectedDeckLink(deckLink: DeckLink) {}
    fun setSelectedDeckLinkMode(mode: DeckLink.Mode) {}
    fun setSelectedDeckLinkDepth(depth: DeckLink.Depth) {}
    fun setSelectedDeckLinkPrimaries(primaries: ColorSpace.Primaries) {}
    fun setSelectedDeckLinkTransfer(transfer: ColorSpace.Transfer) {}
    fun setDeckLinkConnected(connected: Boolean) {}
    fun setCreditsIds(creditsIds: List<CreditsId>) {}
    fun setSelectedCreditsId(creditsId: CreditsId) {}
    fun setNumFrames(numFrames: Int, timecodeString: String) {}
    fun setPlayheadPosition(frameIdx: Int, timecodeString: String) {}
    fun setPlaybackDirection(direction: Int) {}
    fun setActualSize(actualSize: Boolean) {}
    fun setFullScreen(fullScreen: Boolean): Boolean = false
    fun toggleFullScreen() {}
    fun setVideoFrame(videoFrame: BufferedImage?, scaling: Double, clear: Boolean) {}

}
