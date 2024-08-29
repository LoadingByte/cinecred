package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.TEMPLATE_PROJECT
import com.loadingbyte.cinecred.demo.l10nDemo
import com.loadingbyte.cinecred.demo.parseCreditsCS
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.BodyLayout.*
import kotlinx.collections.immutable.persistentListOf


private const val DIR = "guide/content-style"

val GUIDE_CONTENT_STYLE_DEMOS
    get() = listOf(
        GuideContentStyleNameDemo,
        GuideContentStyleBlockOrientationDemo,
        GuideContentStyleSpineAttachmentDemo,
        GuideContentStyleVMarginDemo,
        GuideContentStyleBodyLetterStyleDemo,
        GuideContentStyleBodyLayoutDemo,
        GuideContentStyleHasHeadDemo,
        GuideContentStyleHeadLetterStyleDemo,
        GuideContentStyleHeadForceWidthDemo,
        GuideContentStyleHeadMatchWidthDemo,
        GuideContentStyleHeadHJustifyDemo,
        GuideContentStyleHeadVJustifyDemo,
        GuideContentStyleHeadGapDemo,
        GuideContentStyleHeadLeaderDemo,
        GuideContentStyleHeadLeaderLetterStyleDemo,
        GuideContentStyleHeadLeaderHJustifyDemo,
        GuideContentStyleHeadLeaderVJustifyDemo,
        GuideContentStyleHeadLeaderMarginAndSpacingDemo
    )


object GuideContentStyleNameDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/name", Format.PNG,
    listOf(ContentStyle::name.st())
) {
    override fun styles() = buildList<ContentStyle> {
        this += PRESET_CONTENT_STYLE.copy(name = l10n("project.template.contentStyleGutter"))
    }
}


object GuideContentStyleBlockOrientationDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/block-orientation", Format.STEP_GIF,
    listOf(ContentStyle::blockOrientation.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += headBodyTailCS.copy(name = "Demo", headGapPx = 8.0, tailGapPx = 8.0)
        this += last().copy(blockOrientation = BlockOrientation.VERTICAL)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Tail,@Content Style
A,Dirc Director,Film,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleSpineAttachmentDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/spine-attachment", Format.STEP_GIF,
    listOf(ContentStyle::spineAttachment.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        val baseStyle = headBodyTailCS.copy(name = "Demo", headGapPx = 24.0, tailGapPx = 24.0)
        for (att in SpineAttachment.entries)
            this += baseStyle.copy(spineAttachment = att)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Tail,@Content Style
A,Dirc Director,Film,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleVMarginDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/vmargin", Format.STEP_GIF,
    listOf(ContentStyle::vMarginPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo")
        this += last().copy(vMarginPx = 16.0)
        this += last().copy(vMarginPx = 32.0)
        this += last().copy(vMarginPx = 48.0)
        this += last().copy(vMarginPx = 64.0)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style,@Spine Position
Director of Photography,Peter Panner,Demo,
,,,
Gaffer,Gustav Gluehbirne,,0
,,,
1st Assistant Camera,Paul Puller,,
2nd Assistant Camera,Charly Clapper,,
,Luke Loader,,
        """.parseCreditsCS(style)
}


object GuideContentStyleBodyLetterStyleDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/body-letter-style", Format.STEP_GIF,
    listOf(ContentStyle::bodyLetterStyleName.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += PRESET_CONTENT_STYLE.copy(name = "Demo", bodyLetterStyleName = "Name")
        this += last().copy(bodyLetterStyleName = "Small")
    }

    override fun credits(style: ContentStyle) = """
@Body,@Content Style
Ada Amazing Puller,Demo
Ben Baffling,
        """.parseCreditsCS(style)
}


object GuideContentStyleBodyLayoutDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/body-layout", Format.STEP_GIF,
    listOf(ContentStyle::bodyLayout.st())
) {
    override fun styles() = buildList<ContentStyle> {
        this += PRESET_CONTENT_STYLE.copy(name = "Demo", bodyLayout = GRID)
        this += last().copy(bodyLayout = FLOW)
        this += last().copy(bodyLayout = PARAGRAPHS)
    }
}


object GuideContentStyleHasHeadDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/has-head", Format.STEP_GIF,
    listOf(ContentStyle::hasHead.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo", spineAttachment = SpineAttachment.BODY_CENTER, hasHead = false)
        this += last().copy(hasHead = true)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st Assistant Camera,Paul Puller,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadLetterStyleDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-letter-style", Format.STEP_GIF,
    listOf(ContentStyle::headLetterStyleName.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo")
        this += last().copy(headLetterStyleName = "Normal")
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st Assistant Camera,Paul Puller,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadForceWidthDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-force-width", Format.STEP_GIF,
    listOf(ContentStyle::headForceWidthPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo", headHJustify = HJustify.LEFT)
        this += last().copy(headForceWidthPx = Opt(true, 150.0))
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st AC,Paul Puller,Demo
2nd AC,Charly Clapper,
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadMatchWidthDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-match-width", Format.STEP_GIF,
    listOf(ContentStyle::headMatchWidth.st(), ContentStyle::headMatchWidthAcrossStyles.st()), pageGuides = true
) {
    private val altStyleName get() = l10n("project.template.contentStyleGutter") + " 2"

    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(
            name = "Demo", gridMatchColWidths = MatchExtent.OFF, headMatchWidth = MatchExtent.OFF
        )
        this += last().copy(headMatchWidth = MatchExtent.ACROSS_BLOCKS)
        this += last().copy(
            headMatchWidth = MatchExtent.ACROSS_BLOCKS,
            headMatchWidthAcrossStyles = persistentListOf(altStyleName)
        )
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
Director of Photography,Peter Panner,Demo
1st AC,Paul Puller,
2nd AC,Charly Clapper,
,,
Gaffer,Gustav Gluehbirne,$altStyleName
Key Grip,Detlef “Dolly” Driver,
Best Boy,Francesco Foreman,
        """.parseCreditsCS(
        style,
        gutterCS.copy(
            name = altStyleName, bodyLetterStyleName = "Song Title", gridMatchColWidths = MatchExtent.OFF,
            headLetterStyleName = "Normal"
        )
    )
}


object GuideContentStyleHeadHJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-hjustify", Format.STEP_GIF,
    listOf(ContentStyle::headHJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo")
        this += last().copy(headHJustify = HJustify.LEFT)
        this += last().copy(headHJustify = HJustify.CENTER)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
Director of Photography,Peter Panner,Demo
Gaffer,Gustav Gluehbirne,
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadVJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-vjustify", Format.STEP_GIF,
    listOf(ContentStyle::headVJustify.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo")
        this += last().copy(headVShelve = AppendageVShelve.OVERALL_MIDDLE)
        this += last().copy(headVShelve = AppendageVShelve.LAST)
        this += last().copy(headVJustify = AppendageVJustify.BASELINE)
        this += last().copy(headVJustify = AppendageVJustify.BOTTOM)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st Assistant Camera,Paul Puller,Demo
2nd Assistant Camera,Charly Clapper,
,Luke Loader,
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadGapDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-gap", Format.STEP_GIF,
    listOf(ContentStyle::headGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += gutterCS.copy(name = "Demo")
        this += last().copy(headGapPx = last().headGapPx / 2.0)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st Assistant Camera,Paul Puller,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadLeaderDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-leader", Format.STEP_GIF,
    listOf(ContentStyle::headLeader.st()), pageGuides = true
) {
    override fun styles() = buildList<ContentStyle> {
        this += leaderCS.copy(
            name = "Demo", gridCellHJustifyPerCol = persistentListOf(HJustify.RIGHT),
            headHJustify = HJustify.LEFT, headGapPx = 100.0, headLeader = ""
        )
        this += last().copy(headLeader = ".")
        this += last().copy(headLeader = "-")
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
Director of Photography,Peter Panner,Demo
1st AC,Paul Puller,
Gaffer,Gustav Gluehbirne,
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadLeaderLetterStyleDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-leader-letter-style", Format.STEP_GIF,
    listOf(ContentStyle::headLeaderLetterStyleName.st())
) {
    private val coloredStyleName get() = l10nDemo("styleColored")

    override fun styles() = buildList<ContentStyle> {
        this += leaderCS.copy(name = "Demo")
        this += last().copy(headLeaderLetterStyleName = Opt(true, coloredStyleName))
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st AC,Paul Puller,Demo
        """.parseCreditsCS(style)

    override fun augmentStyling(styling: Styling): Styling {
        val color = Color4f.fromSRGBHexString("#42BEEF")
        var ls = styling.letterStyles.first { it.name == "Small" }
        ls = ls.copy(name = coloredStyleName, layers = persistentListOf(ls.layers.single().copy(color1 = color)))
        return styling.copy(letterStyles = styling.letterStyles.add(ls))
    }
}


object GuideContentStyleHeadLeaderHJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-leader-hjustify", Format.STEP_GIF,
    listOf(ContentStyle::headLeaderHJustify.st(), ContentStyle::headLeaderVJustify.st())
) {
    override fun styles() = buildList<ContentStyle> {
        this += leaderCS.copy(
            name = "Demo", headHJustify = HJustify.LEFT,
            headLeader = "_", headLeaderHJustify = SingleLineHJustify.LEFT, headLeaderSpacingPx = 12.5
        )
        this += last().copy(headLeaderHJustify = SingleLineHJustify.CENTER)
        this += last().copy(headLeaderHJustify = SingleLineHJustify.RIGHT)
        this += last().copy(headLeaderHJustify = SingleLineHJustify.FULL)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
Director of Photography,Peter Panner,Demo
1st AC,Paul Puller,
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadLeaderVJustifyDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-leader-vjustify", Format.STEP_GIF,
    listOf(ContentStyle::headLeaderHJustify.st(), ContentStyle::headLeaderVJustify.st())
) {
    override fun styles() = buildList<ContentStyle> {
        this += leaderCS.copy(name = "Demo", headLeaderVJustify = AppendageVJustify.TOP)
        this += last().copy(headLeaderVJustify = AppendageVJustify.MIDDLE)
        this += last().copy(headLeaderVJustify = AppendageVJustify.BASELINE)
        this += last().copy(headLeaderVJustify = AppendageVJustify.BOTTOM)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st AC,Paul Puller,Demo
        """.parseCreditsCS(style)
}


object GuideContentStyleHeadLeaderMarginAndSpacingDemo : StyleSettingsDemo<ContentStyle>(
    ContentStyle::class.java, "$DIR/head-leader-margin-and-spacing", Format.STEP_GIF,
    listOf(
        ContentStyle::headLeaderMarginLeftPx.st(), ContentStyle::headLeaderMarginRightPx.st(),
        ContentStyle::headLeaderSpacingPx.st()
    )
) {
    override fun styles() = buildList<ContentStyle> {
        this += leaderCS.copy(name = "Demo")
        this += last().copy(headLeaderMarginLeftPx = 15.0)
        this += last().copy(headLeaderMarginRightPx = 15.0)
        this += last().copy(headLeaderSpacingPx = 8.0)
    }

    override fun credits(style: ContentStyle) = """
@Head,@Body,@Content Style
1st AC,Paul Puller,Demo
        """.parseCreditsCS(style)
}


private val headBodyTailCS = PRESET_CONTENT_STYLE.copy(
    blockOrientation = BlockOrientation.HORIZONTAL, bodyLetterStyleName = "Name",
    hasHead = true, headLetterStyleName = "Small",
    hasTail = true, tailLetterStyleName = "Small",
)

private val gutterCS = TEMPLATE_PROJECT.styling.contentStyles.first { it.name == "Gutter" }
private val leaderCS = gutterCS.copy(headVJustify = AppendageVJustify.BASELINE, headGapPx = 150.0, headLeader = ".")
