package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.demo.PageDemo
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.TEMPLATE_SPREADSHEET
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.project.*


private const val DIR = "guide/content-style/paragraphs-layout"

val GUIDE_CONTENT_STYLE_PARAGRAPHS_LAYOUT_DEMOS
    get() = listOf(
        GuideContentStyleParagraphsLayoutExampleDemo,
        GuideContentStyleParagraphsLayoutLineHJustifyDemo,
        GuideContentStyleParagraphsLayoutLineWidthDemo,
        GuideContentStyleParagraphsLayoutParagraphGapAndLineGapDemo
    )


object GuideContentStyleParagraphsLayoutExampleDemo : PageDemo("$DIR/example", Format.PNG, pageGuides = true) {
    override fun credits() = listOf(PARAGRAPHS_SPREADSHEET.parseCreditsCS(blurbCS.copy(name = "Demo")))
}


object GuideContentStyleParagraphsLayoutLineHJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/line-hjustify", Format.STEP_GIF,
    listOf(ContentStyle::paragraphsLineHJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        for (justify in LineHJustify.values())
            this += blurbCS.copy(name = "Demo", paragraphsLineHJustify = justify)
    }

    override fun credits(style: ContentStyle) = PARAGRAPHS_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleParagraphsLayoutLineWidthDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/line-width", Format.STEP_GIF,
    listOf(ContentStyle::paragraphsLineWidthPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += blurbCS.copy(name = "Demo")
        this += last().copy(paragraphsLineWidthPx = 350.0)
    }

    override fun credits(style: ContentStyle) = PARAGRAPHS_SPREADSHEET.parseCreditsCS(style)
}


object GuideContentStyleParagraphsLayoutParagraphGapAndLineGapDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/paragraph-gap-and-line-gap", Format.STEP_GIF,
    listOf(ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += blurbCS.copy(name = "Demo")
        this += last().copy(paragraphsParaGapPx = 16.0)
        this += last().copy(paragraphsParaGapPx = 24.0)
        this += last().copy(paragraphsParaGapPx = 32.0)
        this += last().copy(paragraphsLineGapPx = 4.0)
        this += last().copy(paragraphsLineGapPx = 8.0)
    }

    override fun credits(style: ContentStyle) = PARAGRAPHS_SPREADSHEET.parseCreditsCS(style)
}


private val blurbCS = PRESET_CONTENT_STYLE.copy(
    bodyLetterStyleName = "Normal", bodyLayout = BodyLayout.PARAGRAPHS, paragraphsLineWidthPx = 500.0
)

private val PARAGRAPHS_SPREADSHEET by lazy {
    val row = TEMPLATE_SPREADSHEET.indexOfFirst { "Blurb" in it.cells }
    """
@Body,@Content Style
"${TEMPLATE_SPREADSHEET[row, 1]}",Demo
{{Pic logo.svg x100}}
"${TEMPLATE_SPREADSHEET[row + 1, 1]}"
    """
}
