package com.loadingbyte.cinecred.ui.view.preview

import com.formdev.flatlaf.FlatClientProperties.*
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nEnum
import com.loadingbyte.cinecred.drawer.DrawnCredits
import com.loadingbyte.cinecred.drawer.DrawnCreditsBook
import com.loadingbyte.cinecred.drawer.DrawnProject
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.ui.Overlay
import com.loadingbyte.cinecred.ui.Shortcut
import com.loadingbyte.cinecred.ui.Shortcut.*
import com.loadingbyte.cinecred.ui.comms.*
import com.loadingbyte.cinecred.ui.helper.*
import net.miginfocom.swing.MigLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT


class PreviewDockable(private val previewCtrl: PreviewCtrlComms) :
    DockingFrame.Dockable(
        dockableId = DockableId.PREVIEW.name,
        title = l10n("ui.preview.title"),
        icon = DockableId.PREVIEW.icon
    ),
    PreviewViewComms {

    // ========== ENCAPSULATION LEAKS ==========
    @Deprecated("ENCAPSULATION LEAK") val leakedPreviewTabs: JTabbedPane get() = creditsBookTabs
    @Deprecated("ENCAPSULATION LEAK") val leakedPageTabs
        get() = (creditsBookTabs.getComponentAt(0) as JTabbedPane).getComponentAt(0) as JTabbedPane
    @Deprecated("ENCAPSULATION LEAK") val leakedImagePanels get() = imagePanels
    // =========================================

    private val cards = CardLayout()
    private val creditsBookTabs = JTabbedPane()

    private val highResCache = DeferredImage.CanvasMaterializationCache()
    private val lowResCache = DeferredImage.CanvasMaterializationCache()

    private var drawnProject: DrawnProject? = null
    private var zoom = 1.0
    private var availableOverlays = emptyList<Overlay>()
    private var visibleLayers = emptyList<DeferredImage.Layer>()

    init {
        previewCtrl.registerView(this)

        configure(creditsBookTabs)
        creditsBookTabs.border = BorderFactory.createEmptyBorder(2, 0, 0, 0)
        creditsBookTabs.addChangeListener {
            previewCtrl.sendSelectedDrawnCredits(selectedDrawnCreditsBook, selectedDrawnCredits)
            updateDeferredImagePanelPresentationStatuses()
        }

        layout = cards
        val loadingLabel = JLabel(l10n("ui.edit.loading")).apply { putClientProperty(STYLE, "font: bold \$h0.font") }
        add(JPanel(MigLayout()).apply { add(loadingLabel, "push, center") }, PreviewCard.LOADING.name)
        add(
            JPanel(MigLayout()).apply { add(JLabel(ERROR_ICON.getScaledIcon(4.0)), "push, center") },
            PreviewCard.ERROR.name
        )
        add(creditsBookTabs, PreviewCard.PREVIEW.name)
    }

    override fun getMinimumSize(): Dimension =
        if (isMinimumSizeSet) super.getMinimumSize() else Dimension(300, 200)

    private val selectedDrawnCreditsBook: DrawnCreditsBook?
        get() = drawnProject?.drawnCreditsBooks?.getOrNull(creditsBookTabs.selectedIndex)

    private val selectedDrawnCredits: DrawnCredits?
        get() = selectedDrawnCreditsBook?.drawnCredits?.getOrNull(selectedCreditsTabs.selectedIndex)

    private val selectedCreditsTabs get() = creditsBookTabs.selectedComponent as JTabbedPane
    private val selectedPageTabs get() = selectedCreditsTabs.selectedComponent as JTabbedPane

    // Utility to quickly get all DeferredImagePanels of all credits spreadsheets.
    private val imagePanels: List<DeferredImagePanel>
        get() = buildList(256) {
            for (creditsBookIdx in 0..<creditsBookTabs.tabCount) {
                val creditsTabs = creditsBookTabs.getComponentAt(creditsBookIdx) as JTabbedPane
                for (creditsIdx in 0..<creditsTabs.tabCount) {
                    val pageTabs = creditsTabs.getComponentAt(creditsIdx) as JTabbedPane
                    for (pageIdx in 0..<pageTabs.tabCount)
                        add(pageTabs.getComponentAt(pageIdx) as DeferredImagePanel)
                }
            }
        }

    private fun refreshCreditsBookTabs() {
        val drawnProject = this.drawnProject ?: return
        adjustParentTabCount(creditsBookTabs, drawnProject.drawnCreditsBooks.size, SPREADSHEET_FILE_ICON, false)
        for ((idx, drawnCreditsBook) in drawnProject.drawnCreditsBooks.withIndex()) {
            creditsBookTabs.setTitleAt(idx, drawnCreditsBook.creditsBook.fileName)
            refreshCreditsTabs(creditsBookTabs.getComponentAt(idx) as JTabbedPane, drawnCreditsBook)
        }
    }

    private fun refreshCreditsTabs(creditsTabs: JTabbedPane, drawnCreditsBook: DrawnCreditsBook) {
        adjustParentTabCount(creditsTabs, drawnCreditsBook.drawnCredits.size, TABLE_ICON, true)
        for ((idx, drawnCredits) in drawnCreditsBook.drawnCredits.withIndex()) {
            creditsTabs.setTitleAt(idx, drawnCredits.credits.spreadsheetName)
            refreshPageTabs(creditsTabs.getComponentAt(idx) as JTabbedPane, drawnCredits)
        }
    }

    private fun adjustParentTabCount(parentTabs: JTabbedPane, tabCount: Int, icon: Icon, creditsTabs: Boolean) {
        while (parentTabs.tabCount > tabCount)
            parentTabs.removeTabAt(parentTabs.tabCount - 1)
        while (parentTabs.tabCount < tabCount) {
            val childTabs = JTabbedPane()
            configure(childTabs)
            if (creditsTabs)
                childTabs.putClientProperty(TABBED_PANE_SHOW_CONTENT_SEPARATOR, false)
            childTabs.addChangeListener {
                if (!creditsTabs)
                    previewCtrl.sendSelectedDrawnCredits(selectedDrawnCreditsBook, selectedDrawnCredits)
                updateDeferredImagePanelPresentationStatuses()
            }
            parentTabs.addTab("", icon, childTabs, if (creditsTabs) TAB_TOOLTIP_CREDITS else TAB_TOOLTIP_CREDITS_BOOKS)
        }
    }

    private fun refreshPageTabs(pageTabs: JTabbedPane, drawnCredits: DrawnCredits) {
        val drawnProject = this.drawnProject ?: return
        // First adjust the number of tabs to the number of pages.
        val numPages = drawnCredits.drawnPages.size
        while (pageTabs.tabCount > numPages)
            pageTabs.removeTabAt(pageTabs.tabCount - 1)
        while (pageTabs.tabCount < numPages) {
            val pageNumber = pageTabs.tabCount + 1
            val tabTitle = if (pageTabs.tabCount == 0) l10n("ui.edit.page", pageNumber) else pageNumber.toString()
            val imagePanel =
                DeferredImagePanel(MAX_ZOOM.toDouble(), ZOOM_INCREMENT, highResCache, lowResCache).apply {
                    zoom = this@PreviewDockable.zoom
                    zoomListeners.add(previewCtrl::sendZoomChange)
                }
            pageTabs.addTab(tabTitle, PAGE_ICON, imagePanel, TAB_TOOLTIP_PAGES)
        }
        // Then fill each tab with its corresponding page, which also now has the overlays drawn onto it.
        // Also make the currently selected layers and overlays visible.
        val layers = visibleLayers
        for ((idx, drawnPage) in drawnCredits.drawnPages.withIndex()) {
            val image = drawnPage.defImage.copy()
            for (overlay in availableOverlays)
                overlay.draw(drawnProject.project.styling.global.resolution, drawnPage.stageInfo, image)
            val imagePanel = pageTabs.getComponentAt(idx) as DeferredImagePanel
            imagePanel.setImageAndGroundingAndLayers(image, drawnProject.project.styling.global.grounding, layers)
        }
    }

    private fun updateDeferredImagePanelPresentationStatuses() {
        for (creditsBookIdx in 0..<creditsBookTabs.tabCount) {
            val creditsTabs = creditsBookTabs.getComponentAt(creditsBookIdx) as JTabbedPane
            for (creditsIdx in 0..<creditsTabs.tabCount) {
                val pageTabs = creditsTabs.getComponentAt(creditsIdx) as JTabbedPane
                for (pageIdx in 0..<pageTabs.tabCount)
                    (pageTabs.getComponentAt(pageIdx) as DeferredImagePanel).isPresented =
                        creditsBookTabs.selectedIndex == creditsBookIdx &&
                                creditsTabs.selectedIndex == creditsIdx &&
                                pageTabs.selectedIndex == pageIdx
            }
        }
    }


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun setCard(card: PreviewCard) {
        cards.show(this, card.name)
    }

    override fun updateProject(drawnProject: DrawnProject) {
        this.drawnProject = drawnProject
        refreshCreditsBookTabs()
        previewCtrl.sendSelectedDrawnCredits(selectedDrawnCreditsBook, selectedDrawnCredits)
        updateDeferredImagePanelPresentationStatuses()
    }

    override fun switchCreditsBookTab(right: Boolean) = switch(creditsBookTabs, right)
    override fun switchCreditsTab(right: Boolean) = switch(selectedCreditsTabs, right)
    override fun switchPageTab(right: Boolean) = switch(selectedPageTabs, right)

    override fun setZoom(zoom: Double) {
        this.zoom = zoom
        for (imagePanel in imagePanels)
            imagePanel.zoom = zoom
    }

    override fun setAvailableOverlays(availableOverlays: List<Overlay>) {
        this.availableOverlays = availableOverlays
        if (drawnProject != null)
            refreshCreditsBookTabs()
    }

    override fun setVisibleLayers(visibleLayers: List<DeferredImage.Layer>) {
        this.visibleLayers = visibleLayers
        for (imagePanel in imagePanels)
            imagePanel.layers = visibleLayers
    }


    companion object {

        private val TAB_TOOLTIP_PAGES = tabTooltip(PREVIEW_SWITCH_PAGE_TAB_LEFT, PREVIEW_SWITCH_PAGE_TAB_RIGHT)
        private val TAB_TOOLTIP_CREDITS = tabTooltip(PREVIEW_SWITCH_CREDITS_TAB_LEFT, PREVIEW_SWITCH_CREDITS_TAB_RIGHT)
        private val TAB_TOOLTIP_CREDITS_BOOKS =
            tabTooltip(PREVIEW_SWITCH_CREDITS_BOOK_TAB_LEFT, PREVIEW_SWITCH_CREDITS_BOOK_TAB_RIGHT)

        private fun configure(tabs: JTabbedPane) {
            tabs.isFocusable = false
            tabs.tabLayoutPolicy = SCROLL_TAB_LAYOUT
            tabs.putClientProperty(TABBED_PANE_TAB_TYPE, TABBED_PANE_TAB_TYPE_CARD)
            tabs.putClientProperty(TABBED_PANE_SCROLL_BUTTONS_POLICY, TABBED_PANE_POLICY_AS_NEEDED)
        }

        private fun switch(tabs: JTabbedPane, right: Boolean) {
            tabs.selectedIndex = (tabs.selectedIndex + if (right) 1 else -1).coerceIn(0, tabs.tabCount - 1)
        }

        private fun tabTooltip(shortcutLeft: Shortcut, shortcutRight: Shortcut) =
            l10n("ui.edit.switchTabs", l10nEnum(shortcutLeft.hint, shortcutRight.hint))

    }

}
