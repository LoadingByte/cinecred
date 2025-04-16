package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.StyleSettingsVideoDemo
import com.loadingbyte.cinecred.demo.parseCreditsPS
import com.loadingbyte.cinecred.project.PRESET_PAGE_STYLE
import com.loadingbyte.cinecred.project.PageBehavior
import com.loadingbyte.cinecred.project.PageStyle
import com.loadingbyte.cinecred.project.st


private const val DIR = "guide/page-style"

val GUIDE_PAGE_STYLE_DEMOS
    get() = listOf(
        GuidePageStyleNameDemo,
        GuidePageStyleSubsequentGapDemo,
        GuidePageStyleBehaviorDemo,
        GuidePageStyleCardRuntimeDemo,
        GuidePageStyleCardFadeDemo,
        GuidePageStyleScrollPerFrameDemo
    )


object GuidePageStyleNameDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/name", Format.PNG,
    listOf(PageStyle::name.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(name = l10n("project.PageBehavior.SCROLL"))
    }
}


object GuidePageStyleSubsequentGapDemo : StyleSettingsVideoDemo<PageStyle>(
    "$DIR/subsequent-gap", Format.VIDEO_GIF,
    listOf(PageStyle::subsequentGapFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(name = "Demo", subsequentGapFrames = 30)
        this += PRESET_PAGE_STYLE.copy(name = "Demo", subsequentGapFrames = 0)
        this += PRESET_PAGE_STYLE.copy(name = "Demo", subsequentGapFrames = -45)
    }

    override fun credits(style: PageStyle) = """
@Head,@Body,@Content Style,@Page Style,@Page Runtime
1st AC,Paul Puller,Gutter,Demo,
2nd AC,Charly Clapper,,,
,,,,
,Copyright © 2023,Blurb,Card,00:00:02:00
        """.parseCreditsPS(style)
}


object GuidePageStyleBehaviorDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/behavior", Format.PNG,
    listOf(PageStyle::behavior.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.CARD)
        this += last().copy(behavior = PageBehavior.SCROLL)
    }

    override val suffixes = listOf("-card", "-scroll")
}


object GuidePageStyleCardRuntimeDemo : StyleSettingsVideoDemo<PageStyle>(
    "$DIR/card-runtime", Format.VIDEO_GIF,
    listOf(PageStyle::cardRuntimeFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(name = "Demo", behavior = PageBehavior.CARD, cardRuntimeFrames = 60)
        this += last().copy(cardRuntimeFrames = 120)
    }

    override fun credits(style: PageStyle) = """
@Head,@Body,@Tail,@Content Style,@Page Style
A,Dirc Director,Film,Card,Demo
        """.parseCreditsPS(style)
}


object GuidePageStyleCardFadeDemo : StyleSettingsVideoDemo<PageStyle>(
    "$DIR/card-fade", Format.VIDEO_GIF,
    listOf(
        PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeInTransitionStyleName.st(),
        PageStyle::cardFadeOutFrames.st(), PageStyle::cardFadeOutTransitionStyleName.st()
    )
) {
    override fun styles() = buildList<PageStyle> {
        val linear = l10n("project.template.transitionStyleLinear")
        this += PRESET_PAGE_STYLE.copy(
            name = "Demo", subsequentGapFrames = 0, behavior = PageBehavior.CARD, cardRuntimeFrames = 90,
            cardFadeInFrames = 0, cardFadeInTransitionStyleName = linear,
            cardFadeOutFrames = 0, cardFadeOutTransitionStyleName = linear
        )
        this += last().copy(cardFadeInFrames = 15)
        this += last().copy(cardFadeOutFrames = 45)
    }

    override fun credits(style: PageStyle) = """
@Head,@Body,@Tail,@Content Style,@Page Style,@Page Runtime
,{{Blank}},,,Card,00:00:00:10
A,Dirc Director,Film,Card,Demo,
,{{Blank}},,,Card,00:00:00:10
        """.parseCreditsPS(style)
}


object GuidePageStyleScrollPerFrameDemo : StyleSettingsVideoDemo<PageStyle>(
    "$DIR/scroll-per-frame", Format.VIDEO_GIF,
    listOf(PageStyle::scrollPxPerFrame.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(name = "Demo", behavior = PageBehavior.SCROLL, scrollPxPerFrame = 3.0)
        this += last().copy(scrollPxPerFrame = 6.0)
    }

    override fun credits(style: PageStyle) = """
@Head,@Body,@Content Style,@Page Style
1st AC,Paul Puller,Gutter,Demo
2nd AC,Charly Clapper,,
        """.parseCreditsPS(style)
}
