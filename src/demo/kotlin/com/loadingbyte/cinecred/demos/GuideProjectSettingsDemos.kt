package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.demo.StyleSettingsDemo
import com.loadingbyte.cinecred.demo.TEMPLATE_PROJECT
import com.loadingbyte.cinecred.demo.TEMPLATE_SCROLL_PAGE_FROM_DOP
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.*


private const val DIR = "guide/project-settings"

val GUIDE_PROJECT_SETTINGS_DEMOS
    get() = listOf(
        GuideProjectSettingsResolutionAndFrameRateDemo,
        GuideProjectSettingsTimecodeFormatDemo,
        GuideProjectSettingsRuntimeFineAdjustmentDemo,
        GuideProjectSettingsGroundingDemo,
        GuideProjectSettingsUnitVGapDemo,
        GuideProjectSettingsLocaleDemo,
        GuideProjectSettingsUppercaseExceptionsDemo
    )


object GuideProjectSettingsResolutionAndFrameRateDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/resolution-and-frame-rate", Format.STEP_GIF,
    listOf(Global::resolution.st(), Global::fps.st())
) {
    override fun styles() = buildList<Global> {
        this += PRESET_GLOBAL
        this += last().copy(resolution = Resolution(1080, 1080))
        this += last().copy(fps = FPS(25, 2))
    }
}


object GuideProjectSettingsTimecodeFormatDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/timecode-format", Format.SLOW_STEP_GIF,
    listOf(Global::fps.st(), Global::timecodeFormat.st(), Global::runtimeFrames.st())
) {
    override fun styles() = buildList<Global> {
        this += PRESET_GLOBAL.copy(fps = FPS(30, 1), runtimeFrames = Opt(false, 3284))
        this += last().copy(timecodeFormat = TimecodeFormat.FRAMES)
        this += last().copy(fps = FPS(30000, 1001), timecodeFormat = TimecodeFormat.SMPTE_DROP_FRAME)
        this += last().copy(timecodeFormat = TimecodeFormat.EXACT_FRAMES_IN_SECOND)
    }
}


object GuideProjectSettingsRuntimeFineAdjustmentDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/runtime-fine-adjustment", Format.STEP_GIF,
    listOf(Global::runtimeFrames.st()), pageScaling = 0.45, pageHeight = 400
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global.copy(runtimeFrames = Opt(false, 1092))
        this += last().copy(runtimeFrames = last().runtimeFrames.copy(isActive = true))
    }

    override fun credits(style: Global) = Pair(style, TEMPLATE_SCROLL_PAGE_FROM_DOP)
}


object GuideProjectSettingsGroundingDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/grounding", Format.STEP_GIF,
    listOf(Global::grounding.st()), pageScaling = 0.45, pageHeight = 115
) {
    override fun styles() = buildList<Global> {
        this += TEMPLATE_PROJECT.styling.global
        this += last().copy(grounding = Color4f.fromSRGBHexString("#006400"))
    }

    override fun credits(style: Global) = Pair(style, TEMPLATE_SCROLL_PAGE_FROM_DOP)
}


object GuideProjectSettingsUnitVGapDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/unit-vgap", Format.STEP_GIF,
    listOf(Global::unitVGapPx.st()), pageGuides = true
) {
    override fun styles() = buildList<Global> {
        this += PRESET_GLOBAL
        this += last().copy(unitVGapPx = 2.0 * last().unitVGapPx)
    }

    override fun credits(style: Global) =
        Pair(style, buildPage(style, listOf("Peter Panner", "Paul Puller", "Charly Clapper"), vGap = 1.0))
}


object GuideProjectSettingsLocaleDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/locale", Format.STEP_GIF,
    listOf(Global::locale.st())
) {
    override fun styles() = buildList<Global> {
        this += PRESET_GLOBAL.copy(locale = locale)
        this += last().copy(locale = Locale.of("tr"))
    }

    override fun credits(style: Global) = Pair(style, buildPage(style, listOf("Tina Times"), uppercase = true))
}


object GuideProjectSettingsUppercaseExceptionsDemo : StyleSettingsDemo<Global>(
    Global::class.java, "$DIR/uppercase-exceptions", Format.SLOW_STEP_GIF,
    listOf(Global::uppercaseExceptions.st())
) {
    override fun styles() = buildList<Global> {
        this += PRESET_GLOBAL.copy(uppercaseExceptions = persistentListOf())
        this += last().copy(uppercaseExceptions = persistentListOf("von"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_", "Mac"))
        this += last().copy(uppercaseExceptions = persistentListOf("_von_", "_Mac#"))
    }

    override fun credits(style: Global) = run {
        val texts = listOf("Ronny von Tommy", "Cleavon Tommy", "Johnny MacRonny", "Johnny Macbeth")
        Pair(style, buildPage(style, texts, uppercase = true))
    }
}


private fun buildPage(global: Global, texts: List<String>, vGap: Double = 0.0, uppercase: Boolean = false): Page {
    val letterStyle = PRESET_LETTER_STYLE.copy(fontName = "Archivo Narrow Bold", uppercase = uppercase)
    val blocks = texts.map { text ->
        val styledString = persistentListOf(BodyElement.Str(listOf(Pair(text, letterStyle))))
        Block(PRESET_CONTENT_STYLE, null, styledString, null, vGap * global.unitVGapPx, Any(), Any(), Any())
    }.toPersistentList()
    val spine = Spine(null, VAnchor.TOP, VAnchor.TOP, 0.0, 0.0, blocks)
    val compound = Compound.Scroll(0.0, persistentListOf(spine), 0.0)
    return Page(persistentListOf(Stage(PRESET_PAGE_STYLE, 0, persistentListOf(compound), 0.0)), 0)
}
