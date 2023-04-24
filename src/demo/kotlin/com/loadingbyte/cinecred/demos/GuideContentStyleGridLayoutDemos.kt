package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.PageDemo
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf


private const val DIR = "guide/content-style/grid-layout"

val GUIDE_CONTENT_STYLE_GRID_LAYOUT_DEMOS
    get() = listOf(
        GuideContentStyleGridLayoutExampleDemo,
        GuideContentStyleGridLayoutFillingOrderDemo,
        GuideContentStyleGridLayoutFillingBalancedDemo,
        GuideContentStyleGridLayoutStructureDemo,
        GuideContentStyleGridLayoutMatchColWidthsDemo,
        GuideContentStyleGridLayoutMatchRowHeightDemo,
        GuideContentStyleGridLayoutCellHJustifyPerColDemo,
        GuideContentStyleGridLayoutCellVJustifyDemo,
        GuideContentStyleGridLayoutRowAndColGapsDemo
    )


object GuideContentStyleGridLayoutExampleDemo : PageDemo("$DIR/example", Format.PNG, pageGuides = true) {
    override fun credits() = listOf(GRID_SPREADSHEET.parseCreditsCS(tabularCS.copy(name = "Demo")))
}


object GuideContentStyleGridLayoutFillingOrderDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/filling-order", Format.SLOW_STEP_GIF,
    listOf(ContentStyle::gridFillingOrder.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (order in GridFillingOrder.values())
            this += tabularCS.copy(name = "Demo", gridFillingOrder = order, gridFillingBalanced = false)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
111,Demo
222,
333,
444,
555,
666,
777,
        """.parseCreditsCS(style)
}


object GuideContentStyleGridLayoutFillingBalancedDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/filling-balanced", Format.STEP_GIF,
    listOf(ContentStyle::gridFillingBalanced.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += tabularCS.copy(name = "Demo", gridFillingBalanced = true)
        this += last().copy(gridFillingBalanced = false)
    }

    override fun credits(style: ContentStyle) = GRID_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleGridLayoutStructureDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/structure", Format.STEP_GIF,
    listOf(ContentStyle::gridStructure.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (structure in GridStructure.values())
            this += tabularCS.copy(name = "Demo", gridStructure = structure)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Ada,Demo
Ben,
Chantal,
Dan,
Eva,
Florian
        """.parseCreditsCS(style)
}


object GuideContentStyleGridLayoutMatchColWidthsDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/match-col-widths", Format.SLOW_STEP_GIF,
    listOf(
        ContentStyle::gridMatchColWidths.st(), ContentStyle::gridMatchColWidthsAcrossStyles.st(),
        ContentStyle::gridMatchColUnderoccupancy.st()
    ), pageGuides = true
) {
    private val altStyleName get() = l10n("project.template.contentStyleTabular") + " 2"

    override fun styles() = buildList<ContentStyle> {
        this += tabularCS.copy(
            name = "Demo", spineAttachment = SpineAttachment.BODY_LEFT, gridStructure = GridStructure.FREE,
            gridCellHJustifyPerCol = persistentListOf(HJustify.CENTER, HJustify.CENTER)
        )
        this += last().copy(gridMatchColWidths = MatchExtent.ACROSS_BLOCKS)
        this += last().copy(gridMatchColWidthsAcrossStyles = persistentListOf(altStyleName))
        this += last().copy(gridMatchColUnderoccupancy = GridColUnderoccupancy.LEFT_RETAIN)
        this += last().copy(gridMatchColUnderoccupancy = GridColUnderoccupancy.RIGHT_RETAIN)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Vertical Gap,@Content Style,@Spine Position
Benjamin Backer,,Demo,-160
Brady,,,
Christoph Caterer,,,
Dominic,,,
,,,
Draco,,,
Ian,,,
Sophia,,,
Teo,,,
,2,,
Max,,$altStyleName,
Tom,,,
Don,,,
        """.parseCreditsCS(
        style,
        tabularCS.copy(
            name = altStyleName, spineAttachment = SpineAttachment.BODY_LEFT, bodyLetterStyleName = "Small",
            gridStructure = GridStructure.FREE, gridMatchColWidths = MatchExtent.ACROSS_BLOCKS
        )
    )
}


object GuideContentStyleGridLayoutMatchRowHeightDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/match-row-height", Format.SLOW_STEP_GIF,
    listOf(ContentStyle::gridMatchRowHeight.st(), ContentStyle::gridMatchRowHeightAcrossStyles.st()), pageGuides = true
) {
    private val altStyleName get() = l10n("project.template.contentStyleTabular") + " 2"

    override fun styles() = buildList<ContentStyle> {
        this += tabularCS.copy(name = "Demo", gridStructure = GridStructure.FREE)
        this += last().copy(gridMatchRowHeight = MatchExtent.WITHIN_BLOCK)
        this += last().copy(gridMatchRowHeight = MatchExtent.ACROSS_BLOCKS)
        this += last().copy(gridMatchRowHeightAcrossStyles = persistentListOf(altStyleName))
    }

    override fun credits(style: ContentStyle) = """
@Body,@Vertical Gap,@Content Style
Benjamin Backer,,Demo
{{Pic Logo.svg x50}},,
Christoph Caterer,,
Dominic Donor,,
Draco Driver,,
Ian Inspiration,,
,,
Ludwig,,
Richard,,
Sophia,,
,2,
Valerie,,$altStyleName
Brady,,
Harold,,
        """.parseCreditsCS(
        style,
        tabularCS.copy(
            name = altStyleName, bodyLetterStyleName = "Small",
            gridStructure = GridStructure.FREE, gridMatchRowHeight = MatchExtent.ACROSS_BLOCKS
        )
    )
}


object GuideContentStyleGridLayoutCellHJustifyPerColDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/cell-hjustify-per-col", Format.STEP_GIF,
    listOf(ContentStyle::gridCellHJustifyPerCol.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        val c = HJustify.CENTER
        this += tabularCS.copy(name = "Demo", gridCellHJustifyPerCol = persistentListOf(c, c))
        this += last().copy(gridCellHJustifyPerCol = persistentListOf(c, c, c))
        this += last().copy(gridCellHJustifyPerCol = persistentListOf(HJustify.LEFT, c, HJustify.RIGHT))
    }

    override fun credits(style: ContentStyle) = GRID_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleGridLayoutCellVJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/cell-vjustify", Format.STEP_GIF,
    listOf(ContentStyle::gridCellVJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (vJustify in VJustify.values())
            this += tabularCS.copy(name = "Demo", gridCellVJustify = vJustify, gridRowGapPx = 32.0)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Benjamin Backer,Demo
{{Pic Logo.svg x100}},
Brady “Bar” Owner,
Christoph Caterer,
        """.parseCreditsCS(style)
}


object GuideContentStyleGridLayoutRowAndColGapsDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/row-and-col-gaps", Format.STEP_GIF,
    listOf(ContentStyle::gridRowGapPx.st(), ContentStyle::gridColGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += tabularCS.copy(name = "Demo")
        this += last().copy(gridRowGapPx = 32.0)
        this += last().copy(gridRowGapPx = 64.0)
        this += last().copy(gridColGapPx = 64.0)
    }

    override fun credits(style: ContentStyle) = GRID_SPREADSHEET.parseCreditsCS(style)
}


private val tabularCS = PRESET_CONTENT_STYLE.copy(
    bodyLetterStyleName = "Name", bodyLayout = BodyLayout.GRID, gridStructure = GridStructure.EQUAL_WIDTH_COLS,
    gridCellHJustifyPerCol = persistentListOf(HJustify.CENTER, HJustify.CENTER, HJustify.CENTER), gridColGapPx = 32.0
)

private const val GRID_SPREADSHEET = """
@Body,@Content Style
Benjamin Backer,Demo
Brady “Bar” Owner,
Christoph Caterer,
Dominic Donor,
Draco Driver,
Ian Inspiration,
Ludwig Lender,
Richard Renter,
Sophia Supporter,
Valerie Volunteer,
"""
