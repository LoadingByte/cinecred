package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.Overlay
import com.loadingbyte.cinecred.ui.ProjectController
import com.loadingbyte.cinecred.ui.comms.CreditsId
import com.loadingbyte.cinecred.ui.comms.PreviewCard
import com.loadingbyte.cinecred.ui.comms.PreviewCtrlComms
import com.loadingbyte.cinecred.ui.comms.PreviewViewComms


class PreviewCtrl(private val projectCtrl: ProjectController) : PreviewCtrlComms {

    private val views = mutableListOf<PreviewViewComms>()

    private var logHasErrors = false
    private var drawnProject: DrawnProject? = null

    private fun switchCard() {
        // If there are errors and no credits to show, display the big error mark.
        val card = when {
            !drawnProject?.drawnCreditsBooks.isNullOrEmpty() -> PreviewCard.PREVIEW
            logHasErrors -> PreviewCard.ERROR
            else -> PreviewCard.LOADING
        }
        for (view in views) view.setCard(card)
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun registerView(view: PreviewViewComms) {
        views += view
    }

    override fun updateLog(log: List<ParserMsg>) {
        logHasErrors = log.any { it.severity == Severity.ERROR }
        switchCard()
    }

    override fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject
        switchCard()
        // Update the pages tabs.
        for (view in views) view.updateProject(drawnProject)
    }

    override fun switchCreditsBookTab(right: Boolean) = views.forEach { view -> view.switchCreditsBookTab(right) }
    override fun switchCreditsTab(right: Boolean) = views.forEach { view -> view.switchCreditsTab(right) }
    override fun switchPageTab(right: Boolean) = views.forEach { view -> view.switchPageTab(right) }
    override fun setZoom(zoom: Double) = views.forEach { view -> view.setZoom(zoom) }

    override fun setAvailableOverlays(availableOverlays: List<Overlay>) {
        for (view in views) view.setAvailableOverlays(availableOverlays)
    }

    override fun setVisibleLayers(visibleLayers: List<DeferredImage.Layer>) {
        for (view in views) view.setVisibleLayers(visibleLayers)
    }

    override fun sendSelectedDrawnCredits(drawnCreditsBook: DrawnCreditsBook?, drawnCredits: DrawnCredits?) {
        val creditsId = if (drawnCreditsBook == null || drawnCredits == null) null else
            CreditsId(drawnCreditsBook.creditsBook.fileName, drawnCredits.credits.spreadsheetName)
        projectCtrl.toolbarCtrl.setSelectedDrawnCredits(drawnCreditsBook, drawnCredits)
        projectCtrl.playbackCtrl.setPreviewCreditsId(creditsId)
        projectCtrl.deliveryCtrl.setPreviewCreditsId(creditsId)
    }

    override fun sendZoomChange(zoom: Double) = projectCtrl.toolbarCtrl.setZoom(zoom)

}
