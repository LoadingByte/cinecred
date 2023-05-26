package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.demo.PageDemo
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.project.PRESET_CONTENT_STYLE


private const val DIR = "guide/credits-spreadsheet"

val GUIDE_CREDITS_SPREADSHEET_DEMOS
    get() = listOf(
        GuideCreditsSpreadsheetBlankTagDemo,
        GuideCreditsSpreadsheetStyleTagDemo,
        GuideCreditsSpreadsheetPicTagDemo,
        GuideCreditsSpreadsheetPicCropDemo,
        GuideCreditsSpreadsheetVGapDemo,
        GuideCreditsSpreadsheetContentStylesDemo,
        GuideCreditsSpreadsheetSpinePositionDemo,
        GuideCreditsSpreadsheetBreakMatchDemo
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


object GuideCreditsSpreadsheetPicTagDemo : PageDemo("$DIR/pic-tag", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body
{{Pic cinecred.svg 100x}}
        """.parseCreditsCS()
    )
}


object GuideCreditsSpreadsheetPicCropDemo : PageDemo("$DIR/pic-crop", Format.STEP_GIF, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body
{{Pic logo.svg 100x}}
        """.parseCreditsCS(),
        """
@Body
{{Pic logo.svg 100x Crop}}
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


object GuideCreditsSpreadsheetSpinePositionDemo : PageDemo("$DIR/spine-position", Format.PNG, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(
        """
@Body,@Content Style,@Spine Position
I’m a lefty!,Demo,-150
Me too!,,
,,
I’m a righty!,,150 Parallel
,,
I’m dead center!,,0
        """.parseCreditsCS(demoCS)
    )
}


object GuideCreditsSpreadsheetBreakMatchDemo : PageDemo("$DIR/break-match", Format.STEP_GIF, pageGuides = true) {
    override val isLocaleSensitive get() = false
    override fun credits() = listOf(buildCredits(""), buildCredits("Head Body"))
    private fun buildCredits(breakMatch: String) = """
@Head,@Body,@Content Style,@Break Match
Director of Photography,Peter Panner,Gutter,
,,,
1st AC,Paul Puller,,
2nd AC,Charly Clapper,,
,,,$breakMatch
Gaffer,Gustav Gluehbirne,,
Key Grip,Detlef “Dolly” Driver,,
Best Boy,Francesco Foreman,,
        """.parseCreditsCS()
}


private val demoCS = PRESET_CONTENT_STYLE.copy(name = "Demo", bodyLetterStyleName = "Name")
