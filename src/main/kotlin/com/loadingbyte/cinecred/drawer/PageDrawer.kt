package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.FontSpec
import com.loadingbyte.cinecred.project.Global
import com.loadingbyte.cinecred.project.Page
import com.loadingbyte.cinecred.project.PageBehavior


fun drawPage(
    global: Global,
    fonts: Map<FontSpec, RichFont>,
    page: Page
): DeferredImage {
    // Convert the aligning group lists to maps that map from block to group id.
    val alignBodyColsGroupIds = page.alignBodyColsGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()
    val alignHeadTailGroupIds = page.alignHeadTailGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }.toMap()

    // Generate an image for each column.
    // Also remember the x coordinate of the center line inside each generated image.
    val columnImages = page.sections
        .flatMap { section -> section.columns }
        .map { column -> column to drawColumnImage(fonts, column, alignBodyColsGroupIds, alignHeadTailGroupIds) }
        .toMap()

    // For each column, find the x coordinate of the center line of that column inside the page image.
    val columnCenterLineXsInPageImage = page.sections.flatMap { section ->
        // First element in the hGapAfterSums list is 0, second to last is totalCenterLineSpan.
        val hGapAfterSums = section.columns.scan(0f) { sum, column -> sum + column.hGapAfterPx }
        val totalCenterLineSpan = hGapAfterSums.last()
        // Now convert the above sums (without the last one) to x coordinates centered on the page.
        section.columns.zip(hGapAfterSums.map { sum -> (global.widthPx - totalCenterLineSpan) / 2 + sum })
    }.toMap()

    // Combine the column images to the page image.
    val pageImage = DeferredImage()
    var y = 0f
    for (section in page.sections) {
        for (column in section.columns) {
            val centerLineXInPageImage = columnCenterLineXsInPageImage[column]!!
            val (columnImage, centerLineXInColumnImage) = columnImages[column]!!
            val x = centerLineXInPageImage - centerLineXInColumnImage
            pageImage.drawDeferredImage(columnImage, x, y, 1f)
        }

        val sectionHeight = section.columns.maxOf { col -> columnImages[col]!!.first.height }
        y += sectionHeight + section.vGapAfterPx
    }

    // Convert the page image to a centered one that either fits the movie pixel width an height (if it's a card page)
    // or the movie pixel width (if it's a scroll page).
    val centeredPageImage = DeferredImage()
    centeredPageImage.setMinWidth(global.widthPx.toFloat())
    when (page.style.behavior) {
        PageBehavior.CARD -> {
            centeredPageImage.setMinHeight(global.heightPx.toFloat())
            centeredPageImage.drawDeferredImage(pageImage, 0f, (global.heightPx - pageImage.height) / 2f, 1f)
        }
        PageBehavior.SCROLL ->
            centeredPageImage.drawDeferredImage(pageImage, 0f, 0f, 1f)
    }

    return centeredPageImage
}
