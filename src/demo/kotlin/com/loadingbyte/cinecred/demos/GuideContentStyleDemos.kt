package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.TEMPLATE_PROJECT
import com.loadingbyte.cinecred.demo.parseCreditsCS
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
        GuideContentStyleHeadMatchWidthDemo,
        GuideContentStyleHeadHJustifyDemo,
        GuideContentStyleHeadVJustifyDemo,
        GuideContentStyleHeadGapDemo
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
        this += headBoyTailCS.copy(name = "Demo", headGapPx = 8.0, tailGapPx = 8.0)
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
        val baseStyle = headBoyTailCS.copy(name = "Demo", headGapPx = 24.0, tailGapPx = 24.0)
        for (att in SpineAttachment.values())
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
        this += gutterCS.copy(name = "Demo", headVJustify = HeadTailVJustify.FIRST_TOP)
        this += last().copy(headVJustify = HeadTailVJustify.FIRST_MIDDLE)
        this += last().copy(headVJustify = HeadTailVJustify.FIRST_BOTTOM)
        this += last().copy(headVJustify = HeadTailVJustify.OVERALL_MIDDLE)
        this += last().copy(headVJustify = HeadTailVJustify.LAST_MIDDLE)
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


private val headBoyTailCS = PRESET_CONTENT_STYLE.copy(
    blockOrientation = BlockOrientation.HORIZONTAL, bodyLetterStyleName = "Name",
    hasHead = true, headLetterStyleName = "Small",
    hasTail = true, tailLetterStyleName = "Small",
)

private val gutterCS = TEMPLATE_PROJECT.styling.contentStyles.first { it.name == "Gutter" }
