package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.Overlay


interface PreviewCtrlComms {

    fun registerView(view: PreviewViewComms)

    fun updateLog(log: List<ParserMsg>)
    fun updateProject(drawnProject: DrawnProject)
    fun switchCreditsBookTab(right: Boolean)
    fun switchCreditsTab(right: Boolean)
    fun switchPageTab(right: Boolean)
    fun scrollBlock(down: Boolean)
    fun setZoom(zoom: Double)
    fun setAvailableOverlays(availableOverlays: List<Overlay>)
    fun setVisibleLayers(visibleLayers: List<DeferredImage.Layer>)

    fun sendSelectedDrawnCredits(drawnCreditsBook: DrawnCreditsBook?, drawnCredits: DrawnCredits?)
    fun sendZoomChange(zoom: Double)

}


interface PreviewViewComms {

    fun setCard(card: PreviewCard)
    fun updateProject(drawnProject: DrawnProject)
    fun switchCreditsBookTab(right: Boolean)
    fun switchCreditsTab(right: Boolean)
    fun switchPageTab(right: Boolean)
    fun scrollBlock(down: Boolean)
    fun setZoom(zoom: Double)
    fun setAvailableOverlays(availableOverlays: List<Overlay>)
    fun setVisibleLayers(visibleLayers: List<DeferredImage.Layer>)

}


enum class PreviewCard { LOADING, ERROR, PREVIEW }
