package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.demo.PageDemo
import com.loadingbyte.cinecred.demo.VideoDemo
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.demo.parseCreditsTS
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.PRESET_CONTENT_STYLE
import kotlinx.collections.immutable.persistentListOf


private const val DIR = "guide/credits-spreadsheet"

val GUIDE_CREDITS_SPREADSHEET_DEMOS
    get() = listOf(
        GuideCreditsSpreadsheetBlankTagDemo,
        GuideCreditsSpreadsheetStyleTagDemo,
        GuideCreditsSpreadsheetPicAndVideoTagsDemo,
        GuideCreditsSpreadsheetVGapDemo,
        GuideCreditsSpreadsheetContentStylesDemo,
        GuideCreditsSpreadsheetSpinePositionScrollDemo,
        GuideCreditsSpreadsheetSpinePositionCardDemo,
        GuideCreditsSpreadsheetSpinePositionParallelDemo,
        GuideCreditsSpreadsheetSpinePositionHookDemo,
        GuideCreditsSpreadsheetPageGapMeltDemo,
        GuideCreditsSpreadsheetPageGapMeltVideoDemo,
        GuideCreditsSpreadsheetBreakHarmonizationDemo
    )


object GuideCreditsSpreadsheetBlankTagDemo : PageDemo("$DIR/blank-tag", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style
Ada Amazing,Demo
{{Blank}},
Ben Baffling,
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetStyleTagDemo : PageDemo("$DIR/style-tag", Format.PNG) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style
Copyright © 2023 {{Style Name}}Callie Cash{{Style}} and {{Style Name}}Molly Money,Blurb
        """.parseCreditsCS()
    )
}


object GuideCreditsSpreadsheetPicAndVideoTagsDemo : PageDemo("$DIR/pic-and-video-tags", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body
{{Pic logo.svg 100x}}

{{Video rainbow}}
        """.parseCreditsCS()
    )
}


object GuideCreditsSpreadsheetVGapDemo : PageDemo("$DIR/vgap", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Vertical Gap,@Content Style
Crew,,Demo
,1.5,
Sarah Supervisor,,
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetContentStylesDemo : PageDemo("$DIR/content-styles", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Head,@Body,@Content Style
,Crew,Heading
,,
1st Assistant Director,Sarah Supervisor,Gutter
2nd Assistant Director,Sandro Scheduler,
        """.parseCreditsCS()
    )
}


object GuideCreditsSpreadsheetSpinePositionScrollDemo : PageDemo(
    "$DIR/spine-position-scroll", Format.PNG, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style,@Spine Position
I’m a lefty!,Demo,-150
Me too!,,
,,
I’m a righty!,,150
,,
I’m dead center!,,0
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetSpinePositionCardDemo : PageDemo(
    "$DIR/spine-position-card", Format.PNG, pageHeight = 240, pageCenterY = true, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style,@Spine Position,@Page Style
I’m a lefty!,Demo,-150,Card
,,,
I’m a top-righty!,,150 -50,
,,,
I’m below y’all!,,0 50 Below,
Me too!,,,
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetSpinePositionParallelDemo : PageDemo(
    "$DIR/spine-position-parallel", Format.PNG, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style,@Spine Position
I’m a lefty!,Demo,-150
Me too!,,
,,
I’m a righty!,,150 Parallel
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetSpinePositionHookDemo : PageDemo(
    "$DIR/spine-position-hook", Format.PNG, pageHeight = 310, pageCenterY = true, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style,@Spine Position,@Page Style
I’m the root!,Demo,-50,Card
"Hi,",,,
me too!,,,
,,,
I’m right beneath!,,Hook 1 Bottom-Top,
,,,
I’m a bit offset!,,Hook 2 Middle-Top 250,
,,,
I’m with my friend!,,Hook 1 Bottom-Top 0 200,
        """.parseCreditsCS(demoCS.copy(gridCellHJustifyPerCol = persistentListOf(HJustify.LEFT)))
    )
}


object GuideCreditsSpreadsheetPageGapMeltDemo : PageDemo(
    "$DIR/page-gap-melt", Format.STEP_GIF, pageWidth = 700, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(TOP.parseCreditsCS(resolution = RES), (TOP + BOT).parseCreditsCS(resolution = RES))
    private val RES = Resolution(690, 300)
    private const val TOP = """
@Head,@Body,@Tail,@Vertical Gap,@Content Style,@Page Style,@Page Gap
1st AC,Paul Puller,,,Gutter,Scroll,
2nd AC,Charly Clapper,,,,,
    """
    private const val BOT = """
,,,5,,,Melt
,Copyright © 2023,,,Blurb,Card,
    """
}


object GuideCreditsSpreadsheetPageGapMeltVideoDemo : VideoDemo("$DIR/page-gap-melt-video", Format.VIDEO_GIF) {
    override val isLocaleSensitive get() = false
    override fun credits() = """
@Head,@Body,@Tail,@Vertical Gap,@Content Style,@Page Style,@Page Runtime,@Page Gap
1st AC,Paul Puller,,,Gutter,Scroll,,
2nd AC,Charly Clapper,,,,,,
,,,5,,,,Melt 00:00:02:00 Linear
,Copyright © 2023,,,Blurb,Card,00:00:03:00,
        """.parseCreditsTS()
}


object GuideCreditsSpreadsheetBreakHarmonizationDemo : PageDemo(
    "$DIR/break-harmonization", Format.STEP_GIF, pageGuides = true
) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(buildCredits(""), buildCredits("Head Body"))
    private fun buildCredits(breakHarmonization: String) = """
@Head,@Body,@Content Style,@Break Harmonization
Director of Photography,Peter Panner,Gutter,
,,,
1st AC,Paul Puller,,
2nd AC,Charly Clapper,,
,,,$breakHarmonization
Gaffer,Gustav Gluehbirne,,
Key Grip,Detlef “Dolly” Driver,,
Best Boy,Francesco Foreman,,
        """.parseCreditsCS()
}


private val demoCS = PRESET_CONTENT_STYLE.copy(name = "Demo", bodyLetterStyleName = "Name")
