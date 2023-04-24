package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.TEMPLATE_PROJECT
import com.loadingbyte.cinecred.demo.TEMPLATE_SCROLL_PAGE_FOR_MELT_DEMO
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf


private const val DIR = "guide/page-style"

val GUIDE_PAGE_STYLE_DEMOS
    get() = listOf(
        GuidePageStyleNameDemo,
        GuidePageStyleAfterwardSlugDemo,
        GuidePageStyleBehaviorDemo,
        GuidePageStyleCardAndFadeDurationsDemo,
        GuidePageStyleScrollMeltDemo,
        GuidePageStyleScrollPerFrameDemo
    )


object GuidePageStyleNameDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/name", Format.PNG,
    listOf(PageStyle::name.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(name = l10n("project.template.pageStyleScroll"))
    }
}


object GuidePageStyleAfterwardSlugDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/afterward-slug", Format.PNG,
    listOf(PageStyle::afterwardSlugFrames.st())
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


object GuidePageStyleCardAndFadeDurationsDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/card-and-fade-durations", Format.PNG,
    listOf(PageStyle::cardDurationFrames.st(), PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st())
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.CARD)
    }
}


object GuidePageStyleScrollMeltDemo : StyleSettingsDemo<PageStyle>(
    PageStyle::class.java, "$DIR/scroll-melt", Format.STEP_GIF,
    listOf(PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st()),
    pageScaling = 0.45, pageWidth = 465, pageGuides = true
) {
    override fun styles() = buildList<PageStyle> {
        this += PRESET_PAGE_STYLE.copy(behavior = PageBehavior.SCROLL)
        this += last().copy(scrollMeltWithNext = true)
    }

    override fun credits(style: PageStyle) = run {
        val global = TEMPLATE_PROJECT.styling.global.copy(resolution = Resolution(1024, 429))
        val origStages = TEMPLATE_SCROLL_PAGE_FOR_MELT_DEMO.stages
        val newStages = if (style.scrollMeltWithNext) origStages else persistentListOf(origStages[0])
        Pair(global, Page(newStages))
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
