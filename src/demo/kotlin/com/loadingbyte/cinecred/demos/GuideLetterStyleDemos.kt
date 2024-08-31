package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.getBundledFont
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.parseCreditsLS
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf


private const val DIR = "guide/letter-style"

val GUIDE_LETTER_STYLE_DEMOS
    get() = listOf(
        GuideLetterStyleNameDemo,
        GuideLetterStyleFontDemo,
        GuideLetterStyleHeightDemo,
        GuideLetterStyleLeadingDemo,
        GuideLetterStyleTrackingDemo,
        GuideLetterStyleKerningDemo,
        GuideLetterStyleLigaturesDemo,
        GuideLetterStyleUppercaseDemo,
        GuideLetterStyleSmallCapsDemo,
        GuideLetterStyleSuperscriptDemo,
        GuideLetterStyleHScalingDemo,
        GuideLetterStyleFeaturesDemo
    )


object GuideLetterStyleNameDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/name", Format.PNG,
    listOf(LetterStyle::name.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = l10n("project.template.letterStyleNormal"))
    }
}


object GuideLetterStyleFontDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/font", Format.STEP_GIF,
    listOf(LetterStyle::font.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(font = FontRef(getBundledFont("Archivo Narrow Bold")!!))
        this += last().copy(font = FontRef(getBundledFont("Titillium Regular Upright")!!))
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleHeightDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/height", Format.STEP_GIF,
    listOf(LetterStyle::heightPx.st()), pageGuides = true
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(heightPx = last().heightPx * 2.0)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleLeadingDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/leading", Format.STEP_GIF,
    listOf(LetterStyle::leadingTopRh.st(), LetterStyle::leadingBottomRh.st()), pageGuides = true
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(leadingTopRh = -4.0 / 32.0)
        this += last().copy(leadingTopRh = -8.0 / 32.0)
        this += last().copy(leadingTopRh = -12.0 / 32.0)
        this += last().copy(leadingTopRh = 12.0 / 32.0)
        this += last().copy(leadingTopRh = 8.0 / 32.0)
        this += last().copy(leadingTopRh = 4.0 / 32.0)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleTrackingDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/tracking", Format.STEP_GIF,
    listOf(LetterStyle::trackingEm.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(trackingEm = 0.1)
        this += last().copy(trackingEm = 0.2)
        this += last().copy(trackingEm = -0.1)
        this += last().copy(trackingEm = -0.05)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleKerningDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/kerning", Format.STEP_GIF,
    listOf(LetterStyle::kerning.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(kerning = false)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}Tan").parseCreditsLS(style)
}


object GuideLetterStyleLigaturesDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/ligatures", Format.STEP_GIF,
    listOf(LetterStyle::ligatures.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo", font = FontRef(getBundledFont("Raleway Regular")!!))
        this += last().copy(ligatures = false)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}ffi").parseCreditsLS(style)
}


object GuideLetterStyleUppercaseDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/uppercase", Format.STEP_GIF,
    listOf(LetterStyle::uppercase.st(), LetterStyle::useUppercaseExceptions.st(), LetterStyle::useUppercaseSpacing.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(uppercase = true)
        this += last().copy(useUppercaseExceptions = false)
        this += last().copy(useUppercaseSpacing = false)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}Ronny von Tommy").parseCreditsLS(style)
}


object GuideLetterStyleSmallCapsDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/small-caps", Format.STEP_GIF,
    listOf(LetterStyle::smallCaps.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo", font = FontRef(getBundledFont("Noto Sans Condensed")!!))
        this += last().copy(smallCaps = SmallCaps.SMALL_CAPS)
        this += last().copy(smallCaps = SmallCaps.PETITE_CAPS)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleSuperscriptDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/superscript", Format.STEP_GIF,
    listOf(
        LetterStyle::superscript.st(), LetterStyle::superscriptScaling.st(),
        LetterStyle::superscriptHOffsetRfh.st(), LetterStyle::superscriptVOffsetRfh.st()
    )
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo", superscript = Superscript.SUP)
        this += last().copy(superscript = Superscript.SUB)
        this += last().copy(superscript = Superscript.SUP_SUP)
        this += last().copy(
            superscript = Superscript.CUSTOM, superscriptScaling = 0.5, superscriptVOffsetRfh = -10.0 / 32.0
        )
    }

    override fun credits(style: LetterStyle) =
        if (style.superscript == Superscript.SUP_SUP)
            textBox("{{Stil Ctx1}}a{{Stil Ctx2}}b{{Stil Demo}}c").parseCreditsLS(
                style,
                PRESET_LETTER_STYLE.copy(name = "Ctx1"),
                PRESET_LETTER_STYLE.copy(name = "Ctx2", superscript = Superscript.SUP)
            )
        else
            textBox("{{Stil Ctx}}a{{Stil Demo}}b").parseCreditsLS(
                style,
                PRESET_LETTER_STYLE.copy(name = "Ctx")
            )
}


object GuideLetterStyleHScalingDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/hscaling", Format.STEP_GIF,
    listOf(LetterStyle::hScaling.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo")
        this += last().copy(hScaling = 40.0 / 32.0)
        this += last().copy(hScaling = 48.0 / 32.0)
        this += last().copy(hScaling = 16.0 / 32.0)
        this += last().copy(hScaling = 24.0 / 32.0)
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}$LOREM").parseCreditsLS(style)
}


object GuideLetterStyleFeaturesDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/features", Format.STEP_GIF,
    listOf(LetterStyle::features.st())
) {
    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Demo", font = FontRef(getBundledFont("Roboto")!!))
        this += last().copy(features = persistentListOf(FontFeature("onum", 1)))
        this += last().copy(features = last().features.add(FontFeature("ss01", 1)))
    }

    override fun credits(style: LetterStyle) = textBox("{{Stil Demo}}56789 gg").parseCreditsLS(style)
}


private fun textBox(text: String) = "@Body\n$text"
private const val LOREM = "Lorem ipsum dolor sit amet"
