package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.AlignWithAxis.*
import com.loadingbyte.cinecred.project.NumberConstr.Inequality.*
import com.loadingbyte.cinecred.project.ToggleButtonGroupWidgetSpec.Show.*
import com.loadingbyte.cinecred.projectio.supportsDropFrameTimecode
import java.awt.Color
import kotlin.math.floor


private const val ASPECT_RATIO_LIMIT = 32

private val GLOBAL_META: List<StyleMeta<Global, *>> = listOf(
    NumberConstr(ERROR, Global::widthPx.st(), LARGER_0),
    NumberConstr(ERROR, Global::heightPx.st(), LARGER_0),
    JudgeConstr(
        ERROR, l10n("project.styling.constr.illegalAspectRatio", ASPECT_RATIO_LIMIT),
        Global::widthPx.st(), Global::heightPx.st(),
        judge = { _, global ->
            val aspectRatio = global.widthPx.toFloat() / global.heightPx
            aspectRatio in 1f / ASPECT_RATIO_LIMIT..ASPECT_RATIO_LIMIT / 1f
        }
    ),
    FPSConstr(ERROR, Global::fps.st()),
    DynChoiceConstr(ERROR, Global::timecodeFormat.st()) { _, global ->
        val formats = TimecodeFormat.values().toMutableList().apply { }
        if (!global.fps.supportsDropFrameTimecode)
            formats.remove(TimecodeFormat.SMPTE_DROP_FRAME)
        formats
    },
    NumberConstr(ERROR, Global::runtimeFrames.st(), LARGER_0),
    ColorConstr(ERROR, Global::background.st(), allowAlpha = false),
    NumberConstr(ERROR, Global::unitVGapPx.st(), LARGER_0),
    NumberStepWidgetSpec(Global::widthPx.st(), 10),
    NumberStepWidgetSpec(Global::heightPx.st(), 10),
    TimecodeWidgetSpec(Global::runtimeFrames.st(), { _, global -> global.fps }, { _, global -> global.timecodeFormat }),
    DontGrowWidgetSpec(Global::uppercaseExceptions.st())
)


private val PAGE_STYLE_META: List<StyleMeta<PageStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), PageStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), PageStyle::name.st()) { styling, style ->
        styling.pageStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    NumberConstr(ERROR, PageStyle::afterwardSlugFrames.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardDurationFrames.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardFadeInFrames.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardFadeOutFrames.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::scrollPxPerFrame.st(), LARGER_0),
    JudgeConstr(WARN, l10n("project.styling.constr.fractionalScrollPx"), PageStyle::scrollPxPerFrame.st()) { _, style ->
        val value = style.scrollPxPerFrame
        floor(value) == value
    },
    EffectivitySpec(
        PageStyle::cardDurationFrames.st(), PageStyle::cardFadeInFrames.st(), PageStyle::cardFadeOutFrames.st(),
        isTotallyIneffective = { style -> style.behavior != PageBehavior.CARD }
    ),
    EffectivitySpec(
        PageStyle::scrollMeltWithPrev.st(), PageStyle::scrollMeltWithNext.st(), PageStyle::scrollPxPerFrame.st(),
        isTotallyIneffective = { style -> style.behavior != PageBehavior.SCROLL }
    ),
    EffectivitySpec(
        PageStyle::afterwardSlugFrames.st(),
        isAlmostEffective = { style -> style.behavior == PageBehavior.SCROLL && style.scrollMeltWithNext }
    ),
    ToggleButtonGroupWidgetSpec(PageStyle::behavior.st(), LABEL)
)


private val CONTENT_STYLE_META: List<StyleMeta<ContentStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), ContentStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), ContentStyle::name.st()) { styling, style ->
        styling.contentStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    DynChoiceConstr(WARN, ContentStyle::alignWithAxis.st()) { _, style ->
        when (style.spineOrientation) {
            SpineOrientation.VERTICAL -> listOf(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
            SpineOrientation.HORIZONTAL -> when {
                !style.hasHead && !style.hasTail -> listOf(BODY_LEFT, BODY_CENTER, BODY_RIGHT)
                style.hasHead && !style.hasTail -> listOf(
                    OVERALL_CENTER,
                    HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT, HEAD_GAP_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT
                )
                !style.hasHead && style.hasTail -> listOf(
                    OVERALL_CENTER,
                    BODY_LEFT, BODY_CENTER, BODY_RIGHT,
                    TAIL_GAP_CENTER, TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT
                )
                else -> AlignWithAxis.values().asList()
            }
        }
    },
    NumberConstr(ERROR, ContentStyle::vMarginPx.st(), LARGER_OR_EQUAL_0),
    DynChoiceConstr(
        WARN, ContentStyle::bodyLetterStyleName.st(), ContentStyle::headLetterStyleName.st(),
        ContentStyle::tailLetterStyleName.st(),
        choices = { styling, _ -> styling.letterStyles.map(LetterStyle::name) }
    ),
    NumberConstr(ERROR, ContentStyle::gridRowGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::gridColGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::flowLineWidthPx.st(), LARGER_0),
    NumberConstr(ERROR, ContentStyle::flowLineGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::flowHGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::paragraphsLineWidthPx.st(), LARGER_0),
    NumberConstr(ERROR, ContentStyle::paragraphsParaGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::paragraphsLineGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::headGapPx.st(), LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::tailGapPx.st(), LARGER_OR_EQUAL_0),
    EffectivitySpec(
        ContentStyle::gridFillingOrder.st(), ContentStyle::gridElemBoxConform.st(),
        ContentStyle::gridElemHJustifyPerCol.st(), ContentStyle::gridElemVJustify.st(), ContentStyle::gridRowGapPx.st(),
        ContentStyle::gridColGapPx.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.GRID }
    ),
    EffectivitySpec(
        ContentStyle::flowDirection.st(), ContentStyle::flowLineHJustify.st(), ContentStyle::flowElemBoxConform.st(),
        ContentStyle::flowElemHJustify.st(), ContentStyle::flowElemVJustify.st(), ContentStyle::flowLineWidthPx.st(),
        ContentStyle::flowLineGapPx.st(), ContentStyle::flowHGapPx.st(), ContentStyle::flowSeparator.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.FLOW }
    ),
    EffectivitySpec(
        ContentStyle::paragraphsLineHJustify.st(), ContentStyle::paragraphsLineWidthPx.st(),
        ContentStyle::paragraphsParaGapPx.st(), ContentStyle::paragraphsLineGapPx.st(),
        isTotallyIneffective = { style -> style.bodyLayout != BodyLayout.PARAGRAPHS }
    ),
    EffectivitySpec(
        ContentStyle::headLetterStyleName.st(), ContentStyle::headHJustify.st(), ContentStyle::headVJustify.st(),
        ContentStyle::headGapPx.st(),
        isTotallyIneffective = { style -> !style.hasHead }
    ),
    EffectivitySpec(
        ContentStyle::tailLetterStyleName.st(), ContentStyle::tailHJustify.st(), ContentStyle::tailVJustify.st(),
        ContentStyle::tailGapPx.st(),
        isTotallyIneffective = { style -> !style.hasTail }
    ),
    EffectivitySpec(
        ContentStyle::spineOrientation.st(),
        isAlmostEffective = { style -> !style.hasHead && !style.hasTail }
    ),
    EffectivitySpec(
        ContentStyle::gridElemVJustify.st(),
        isAlmostEffective = { style ->
            style.gridElemHJustifyPerCol.size < 2 && style.gridElemBoxConform.let {
                it != BodyElementBoxConform.HEIGHT && it != BodyElementBoxConform.WIDTH_AND_HEIGHT &&
                        it != BodyElementBoxConform.SQUARE
            }
        }
    ),
    EffectivitySpec(
        ContentStyle::gridColGapPx.st(),
        isAlmostEffective = { style -> style.gridElemHJustifyPerCol.size < 2 }
    ),
    EffectivitySpec(
        ContentStyle::headVJustify.st(),
        isAlmostEffective = { style -> style.spineOrientation != SpineOrientation.HORIZONTAL }
    ),
    EffectivitySpec(
        ContentStyle::tailVJustify.st(),
        isAlmostEffective = { style -> style.spineOrientation != SpineOrientation.HORIZONTAL }
    ),
    ToggleButtonGroupWidgetSpec(ContentStyle::spineOrientation.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemBoxConform.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemHJustifyPerCol.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowDirection.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowLineHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemBoxConform.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::flowElemVJustify.st(), ICON),
    NumberStepWidgetSpec(ContentStyle::flowLineWidthPx.st(), 10f),
    DontGrowWidgetSpec(ContentStyle::flowSeparator.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::paragraphsLineHJustify.st(), ICON),
    NumberStepWidgetSpec(ContentStyle::paragraphsLineWidthPx.st(), 10f),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON)
)


private val LETTER_STYLE_META: List<StyleMeta<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), LetterStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), LetterStyle::name.st()) { styling, style ->
        styling.letterStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    FontNameConstr(WARN, LetterStyle::fontName.st()),
    NumberConstr(ERROR, LetterStyle::heightPx.st(), LARGER_0),
    EffectivitySpec(LetterStyle::useUppercaseExceptions.st(), isAlmostEffective = { style -> !style.uppercase }),
    NumberStepWidgetSpec(LetterStyle::tracking.st(), 0.01f)
)


typealias Style = Any


sealed class StyleMeta<S : Style, V>(
    vararg settings: StyleSetting<S, V>
) {
    val settings: List<StyleSetting<S, V>> = settings.toList()
}


class NumberConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Number>,
    val inequality: Inequality = NONE,
    val finite: Boolean = true
) : StyleMeta<S, Number>(setting) {
    enum class Inequality { NONE, LARGER_OR_EQUAL_0, LARGER_0 }
}


class DynChoiceConstr<S : Style, V>(
    val severity: Severity,
    vararg settings: StyleSetting<S, V>,
    val choices: (Styling, S) -> List<V>
) : StyleMeta<S, V>(*settings)


class ColorConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Color>,
    val allowAlpha: Boolean
) : StyleMeta<S, Color>(setting)


class FPSConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FPS>
) : StyleMeta<S, FPS>(setting)


class FontNameConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, String>
) : StyleMeta<S, String>(setting)


class JudgeConstr<S : Style>(
    val severity: Severity,
    val msg: String,
    vararg settings: StyleSetting<S, Any?>,
    val judge: (Styling, S) -> Boolean
) : StyleMeta<S, Any?>(*settings)


class EffectivitySpec<S : Style>(
    vararg settings: StyleSetting<S, Any?>,
    val isAlmostEffective: ((S) -> Boolean)? = null,
    val isTotallyIneffective: ((S) -> Boolean)? = null
) : StyleMeta<S, Any?>(*settings) {
    init {
        require(isAlmostEffective != null || isTotallyIneffective != null)
    }
}


class DontGrowWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any?>
) : StyleMeta<S, Any?>(setting)


class NumberStepWidgetSpec<S : Style, N : Number>(
    setting: StyleSetting<S, N>,
    val stepSize: N
) : StyleMeta<S, N>(setting)


class ToggleButtonGroupWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any>,
    val show: Show
) : StyleMeta<S, Any>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }
}


class TimecodeWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val getFPS: (Styling, S) -> FPS,
    val getTimecodeFormat: (Styling, S) -> TimecodeFormat
) : StyleMeta<S, Number>(setting)


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleMeta(styleClass: Class<S>): List<StyleMeta<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_META as List<StyleMeta<S, *>>
    PageStyle::class.java -> PAGE_STYLE_META as List<StyleMeta<S, *>>
    ContentStyle::class.java -> CONTENT_STYLE_META as List<StyleMeta<S, *>>
    LetterStyle::class.java -> LETTER_STYLE_META as List<StyleMeta<S, *>>
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
}


enum class Effectivity { TOTALLY_INEFFECTIVE, ALMOST_EFFECTIVE, OPTIONALLY_INEFFECTIVE, EFFECTIVE }

fun <S : Style> findIneffectiveSettings(style: S): Map<StyleSetting<*, *>, Effectivity> {
    val result = HashMap<StyleSetting<*, *>, Effectivity>()

    for (setting in getStyleSettings(style.javaClass))
        if (setting is OptionallyEffectiveStyleSetting && !setting.getPlain(style).isEffective)
            result[setting] = Effectivity.OPTIONALLY_INEFFECTIVE

    fun mark(settings: List<StyleSetting<*, *>>, tier: Effectivity) {
        for (setting in settings)
            result[setting] = minOf(result[setting] ?: tier, tier)
    }

    for (meta in getStyleMeta(style.javaClass))
        if (meta is EffectivitySpec) {
            meta.isAlmostEffective?.let { if (it(style)) mark(meta.settings, Effectivity.ALMOST_EFFECTIVE) }
            meta.isTotallyIneffective?.let { if (it(style)) mark(meta.settings, Effectivity.TOTALLY_INEFFECTIVE) }
        }

    return result
}


class ConstraintViolation(val style: Style, val setting: StyleSetting<*, *>, val severity: Severity, val msg: String?)

fun verifyConstraints(styling: Styling, isFontName: (String) -> Boolean): List<ConstraintViolation> {
    val violations = mutableListOf<ConstraintViolation>()

    fun log(style: Style, setting: StyleSetting<*, *>, severity: Severity, msg: String?) {
        violations.add(ConstraintViolation(style, setting, severity, msg))
    }

    fun <S : Style> verifyStyle(style: S) {
        val ignoreSettings = findIneffectiveSettings(style)

        for (meta in getStyleMeta(style.javaClass))
            when (meta) {
                is NumberConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings }) {
                        val value = setting.getValue(style).toFloat()
                        if (meta.inequality == LARGER_OR_EQUAL_0 && value < 0f ||
                            meta.inequality == LARGER_0 && value <= 0f ||
                            meta.finite && !value.isFinite()
                        ) {
                            val key = when {
                                meta.finite -> "project.styling.constr.finiteNumber"
                                else -> "project.styling.constr.number"
                            }
                            val restriction = when (meta.inequality) {
                                NONE -> ""
                                LARGER_0 -> " > 0"
                                LARGER_OR_EQUAL_0 -> " \u2265 0"
                            }
                            log(style, setting, meta.severity, l10n(key, restriction))
                        }
                    }
                is DynChoiceConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (setting.getValue(style) !in meta.choices(styling, style))
                            log(style, setting, meta.severity, l10n("project.styling.constr.dynChoice"))
                is ColorConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (!meta.allowAlpha && setting.getValue(style).alpha != 255)
                            log(style, setting, meta.severity, l10n("project.styling.constr.colorAlpha"))
                is FPSConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (setting.getValue(style).run { numerator <= 0 || denominator <= 0 || !frac.isFinite() })
                            log(style, setting, meta.severity, l10n("project.styling.constr.fps"))
                is FontNameConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (!isFontName(setting.getValue(style)))
                            log(style, setting, meta.severity, l10n("project.styling.constr.font"))
                is JudgeConstr ->
                    if (!meta.judge(styling, style)) {
                        val settings = meta.settings.filter { it !in ignoreSettings }
                        log(style, settings[0], meta.severity, meta.msg)
                        for (setting in settings.drop(1))
                            log(style, setting, meta.severity, null)
                    }
            }
    }

    verifyStyle(styling.global)
    styling.pageStyles.forEach(::verifyStyle)
    styling.contentStyles.forEach(::verifyStyle)
    styling.letterStyles.forEach(::verifyStyle)

    return violations
}
