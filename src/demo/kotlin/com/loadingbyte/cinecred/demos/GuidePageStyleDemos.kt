package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
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


object GuidePageStyleSubsequentGapDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/subsequent-gap", Format.PNG,
    listOf(PageStyle::subsequentGapFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE
    }
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


object GuidePageStyleCardRuntimeDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/card-runtime", Format.PNG,
    listOf(PageStyle::cardRuntimeFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.CARD)
    }
}


object GuidePageStyleCardFadeDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/card-fade", Format.PNG,
    listOf(PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.CARD)
    }
}


object GuidePageStyleScrollPerFrameDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/scroll-per-frame", Format.PNG,
    listOf(PageStyle::scrollPxPerFrame.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.SCROLL)
    }
}
