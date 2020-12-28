package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.project.*


fun drawPage(
    global: Global,
    fonts: Map<FontSpec, RichFont>,
    page: Page
): DeferredImage {
    // Create a map from each block to the index of the user-defined block group that contains it.
    val blockGroupIds = page.blockGroups
        .flatMapIndexed { blockGrpIdx, blockGrp -> blockGrp.map { block -> block to blockGrpIdx } }
        .toMap()

    val columnCenterLineXsInPageImage = page.sections.flatMap { section ->
        // First element in the hGapAfterSums list is 0, second to last is totalCenterLineSpan.
        val hGapAfterSums = section.columns.scan(0f) { sum, column -> sum + column.hGapAfterPx }
        val totalCenterLineSpan = hGapAfterSums.last()
        // Now convert the above sums (without the last one) to x coordinates centered on the page.
        section.columns.zip(hGapAfterSums.map { sum -> (global.widthPx - totalCenterLineSpan) / 2 + sum })
    }.toMap()

    val columnImages = mutableMapOf<Column, Pair<DeferredImage, Float>>()
    for (sectionGroup in page.sectionGroups) {
        // Get columns in the user-defined section group that share the same centerLineX.
        // Inside such groups, some things are aligned, and so we need to columns for each group together.
        val columnGroups = sectionGroup
            .flatMap(Section::columns)
            .groupBy { column -> columnCenterLineXsInPageImage[column] }
            .values
        // For each column group, generate an image for each column in the group.
        // Also remember the x coordinate of the center line inside each generated image.
        for (columnGroup in columnGroups)
            columnImages.putAll(drawColumnImages(fonts, blockGroupIds, columnGroup))
    }

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
