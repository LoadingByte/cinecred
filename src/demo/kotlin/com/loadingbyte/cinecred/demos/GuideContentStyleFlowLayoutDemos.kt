package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.PageDemo
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.l10nDemo
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import java.util.*


private const val DIR = "guide/content-style/flow-layout"

val GUIDE_CONTENT_STYLE_FLOW_LAYOUT_DEMOS
    get() = listOf(
        GuideContentStyleFlowLayoutExampleDemo,
        GuideContentStyleFlowLayoutSortDemo,
        GuideContentStyleFlowLayoutDirectionDemo,
        GuideContentStyleFlowLayoutJustifyRowsDemo,
        GuideContentStyleFlowLayoutSquareCellsDemo,
        GuideContentStyleFlowLayoutForceCellWidthAndHeightDemo,
        GuideContentStyleFlowLayoutHarmonizeCellWidthAndHeightDemo,
        GuideContentStyleFlowLayoutCellHJustifyAndCellAndTextVJustifyDemo,
        GuideContentStyleFlowLayoutRowWidthDemo,
        GuideContentStyleFlowLayoutRowGapAndHGapDemo,
        GuideContentStyleFlowLayoutSeparatorDemo,
        GuideContentStyleFlowLayoutSeparatorVJustifyDemo
    )


object GuideContentStyleFlowLayoutExampleDemo : PageDemo("$DIR/example", Format.PNG, pageGuides = true) {
    override fun credits() = listOf(FLOW_SPREADSHEET.parseCreditsCS(bulletsCS.copy(name = "Demo")))
}


object GuideContentStyleFlowLayoutSortDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/sort", Format.SLOW_STEP_GIF,
    listOf(ContentStyle::sort.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (sort in Sort.entries)
            this += bulletsCS.copy(name = "Demo", sort = sort)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Chantal,Demo
Eva,
Ada,
Ben,
Florian
Dan,
        """.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutDirectionDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/direction", Format.STEP_GIF,
    listOf(ContentStyle::flowDirection.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (direction in FlowDirection.entries)
            this += bulletsCS.copy(name = "Demo", flowDirection = direction)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
111,Demo
222,
333,
444,
        """.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutJustifyRowsDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/row-hjustify", Format.STEP_GIF,
    listOf(ContentStyle::flowRowHJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (justify in HJustifyCrumbsStack.entries)
            this += bulletsCS.copy(name = "Demo", flowRowHJustify = justify)
    }

    override fun credits(style: ContentStyle) = FLOW_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutSquareCellsDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/square-cells", Format.STEP_GIF,
    listOf(ContentStyle::flowSquareCells.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo")
        this += last().copy(flowSquareCells = true)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Ada,Demo
Ben,
Claire,
Dan,
Eva,
        """.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutForceCellWidthAndHeightDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/force-cell-width-and-height", Format.STEP_GIF,
    listOf(ContentStyle::flowForceCellWidthPx.st(), ContentStyle::flowForceCellHeightPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo")
        this += last().copy(flowForceCellWidthPx = Opt(true, 150.0))
        this += last().copy(flowForceCellHeightPx = Opt(true, 70.0))
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Ada,Demo
Ben,
Claire,
Dan,
Eva,
        """.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutHarmonizeCellWidthAndHeightDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/harmonize-cell-width-and-height", Format.SLOW_STEP_GIF,
    listOf(
        ContentStyle::flowHarmonizeCellWidth.st(), ContentStyle::flowHarmonizeCellWidthAcrossStyles.st(),
        ContentStyle::flowHarmonizeCellHeight.st(), ContentStyle::flowHarmonizeCellHeightAcrossStyles.st()
    ), pageGuides = true
) {
    private val altStyleName get() = l10n("project.template.contentStyleBullets") + " 2"

    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo", flowRowWidthPx = 500.0)
        this += last().copy(flowHarmonizeCellWidth = HarmonizeExtent.WITHIN_BLOCK)
        this += last().copy(flowHarmonizeCellWidth = HarmonizeExtent.ACROSS_BLOCKS)
        this += last().copy(flowHarmonizeCellWidthAcrossStyles = persistentListOf(altStyleName))
        this += last().copy(flowHarmonizeCellHeight = HarmonizeExtent.WITHIN_BLOCK)
        this += last().copy(flowHarmonizeCellHeight = HarmonizeExtent.ACROSS_BLOCKS)
        this += last().copy(flowHarmonizeCellHeightAcrossStyles = persistentListOf(altStyleName))
    }

    override fun credits(style: ContentStyle) = """
@Body,@Vertical Gap,@Content Style
{{Pic logo.svg x50}},,Demo
Emil Eater,,
Megan Muncher,,
Dylan Delicious,,
Tiffany Tasty,,
Ben Broccoli,,
,,
Tom,,
Samuel,,
,2,
Wanda,,$altStyleName
Philipp,,
        """.parseCreditsCS(
        style,
        bulletsCS.copy(
            name = altStyleName, bodyLetterStyleName = "Small", flowHarmonizeCellWidth = HarmonizeExtent.ACROSS_BLOCKS,
            flowHarmonizeCellHeight = HarmonizeExtent.ACROSS_BLOCKS, flowRowWidthPx = 500.0
        )
    )
}


object GuideContentStyleFlowLayoutCellHJustifyAndCellAndTextVJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/cell-hjustify-and-cell-and-text-vjustify", Format.SLOW_STEP_GIF,
    listOf(
        ContentStyle::flowCellHJustify.st(), ContentStyle::flowCellVJustify.st(),
        ContentStyle::flowTextVJustifyFragments.st(), ContentStyle::flowTextVJustify.st()
    ), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(
            name = "Demo", flowHarmonizeCellWidth = HarmonizeExtent.WITHIN_BLOCK,
            flowCellHJustify = HJustify.LEFT, flowCellVJustify = VJustify.TOP,
            flowTextVJustifyFragments = VTextFragment.ALL_LINES, flowTextVJustify = VJustifyText.MIDDLE
        )
        this += last().copy(flowCellHJustify = HJustify.CENTER)
        this += last().copy(flowCellVJustify = VJustify.MIDDLE)
        this += last().copy(flowTextVJustifyFragments = VTextFragment.FIRST_LINE)
        this += last().copy(flowTextVJustifyFragments = VTextFragment.LAST_LINE)
        this += last().copy(flowTextVJustify = VJustifyText.BASELINE)
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Amy Apricot,Demo
{{Pic logo.svg x170}},
"Brady “Bar” Owner
Ben Broccoli
{{Style ${l10n("project.template.letterStyleCardSmall", locale = Locale.ROOT)}}}Bella Brewer",
        """.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutRowWidthDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/row-width", Format.STEP_GIF,
    listOf(ContentStyle::flowRowWidthPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo")
        this += last().copy(flowRowWidthPx = 400.0)
    }

    override fun credits(style: ContentStyle) = FLOW_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutRowGapAndHGapDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/row-gap-and-hgap", Format.STEP_GIF,
    listOf(ContentStyle::flowRowGapPx.st(), ContentStyle::flowCellHGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo")
        this += last().copy(flowRowGapPx = 32.0)
        this += last().copy(flowRowGapPx = 64.0)
        this += last().copy(flowCellHGapPx = 64.0)
    }

    override fun credits(style: ContentStyle) = FLOW_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleFlowLayoutSeparatorDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/separator", Format.STEP_GIF,
    listOf(ContentStyle::flowSeparator.st(), ContentStyle::flowSeparatorLetterStyleName.st()), pageGuides = true
) {
    private val coloredStyleName get() = l10nDemo("styleColored")

    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(name = "Demo", flowSeparator = "")
        this += last().copy(flowSeparator = bulletsCS.flowSeparator)
        this += last().copy(flowSeparator = "\u2013")
        this += last().copy(flowSeparatorLetterStyleName = Opt(true, coloredStyleName))
    }

    override fun credits(style: ContentStyle) = FLOW_SPREADSHEET.parseCreditsCS(style)

    override fun augmentStyling(styling: Styling): Styling {
        var ls = styling.letterStyles.single()
        ls = ls.copy(name = coloredStyleName, layers = persistentListOf(ls.layers.single().copy(color1 = sepClr)))
        return styling.copy(letterStyles = styling.letterStyles.add(ls))
    }
}


object GuideContentStyleFlowLayoutSeparatorVJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/separator-vjustify", Format.STEP_GIF,
    listOf(ContentStyle::flowSeparatorVJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += bulletsCS.copy(
            name = "Demo", flowSeparator = "X", flowSeparatorLetterStyleName = Opt(true, "Sep")
        )
        this += last().copy(flowSeparatorVJustify = VJustifyText.BASELINE)
        this += last().copy(flowSeparatorVJustify = VJustifyText.BOTTOM)
        this += last().copy(flowSeparatorVJustify = VJustifyText.TOP)
    }

    override fun credits(style: ContentStyle) = FLOW_SPREADSHEET.parseCreditsCS(style)

    override fun augmentStyling(styling: Styling): Styling {
        var ls = styling.letterStyles.single()
        ls = ls.copy(name = "Sep", heightPx = 16.0, layers = persistentListOf(ls.layers.single().copy(color1 = sepClr)))
        return styling.copy(letterStyles = styling.letterStyles.add(ls))
    }
}


private val bulletsCS = PRESET_CONTENT_STYLE.copy(
    bodyLetterStyleName = "Name", bodyLayout = BodyLayout.FLOW, flowRowWidthPx = 600.0, flowCellHGapPx = 32.0
)
private val sepClr = Color4f.fromSRGBHexString("#42BEEF")

private const val FLOW_SPREADSHEET = """
@Body,@Content Style
Emil Eater,Demo
Megan Muncher,
Dylan Delicious,
Tiffany Tasty,
Samuel Saturated,
Thomas Thirsty,
Wanda Waters,
Philip “Pork” Chop,
Ben Broccoli,
"""
