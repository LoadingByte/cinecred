package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.AlignWithAxis.*
import com.loadingbyte.cinecred.project.ToggleButtonGroupWidgetSpec.Show.*
import com.loadingbyte.cinecred.projectio.supportsDropFrameTimecode
import kotlinx.collections.immutable.ImmutableList
import java.awt.Color
import java.text.NumberFormat
import kotlin.math.floor


private const val ASPECT_RATIO_LIMIT = 32

private val GLOBAL_META: List<StyleMeta<Global, *>> = listOf(
    IntConstr(ERROR, Global::widthPx.st(), min = 1),
    IntConstr(ERROR, Global::heightPx.st(), min = 1),
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
        val formats = TimecodeFormat.values().toMutableList()
        if (!global.fps.supportsDropFrameTimecode)
            formats.remove(TimecodeFormat.SMPTE_DROP_FRAME)
        formats
    },
    IntConstr(ERROR, Global::runtimeFrames.st(), min = 1),
    ColorConstr(ERROR, Global::grounding.st(), allowAlpha = false),
    FloatConstr(ERROR, Global::unitVGapPx.st(), min = 0f, minInclusive = false),
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
    IntConstr(ERROR, PageStyle::afterwardSlugFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardDurationFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeInFrames.st(), min = 0),
    IntConstr(ERROR, PageStyle::cardFadeOutFrames.st(), min = 0),
    FloatConstr(ERROR, PageStyle::scrollPxPerFrame.st(), min = 0f, minInclusive = false),
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
    FloatConstr(ERROR, ContentStyle::vMarginPx.st(), min = 0f),
    DynChoiceConstr(
        WARN, ContentStyle::bodyLetterStyleName.st(), ContentStyle::headLetterStyleName.st(),
        ContentStyle::tailLetterStyleName.st(),
        choices = { styling, _ -> styling.letterStyles.map(LetterStyle::name) }
    ),
    FloatConstr(ERROR, ContentStyle::gridRowGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::gridColGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::flowLineWidthPx.st(), min = 0f, minInclusive = false),
    FloatConstr(ERROR, ContentStyle::flowLineGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::flowHGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::paragraphsLineWidthPx.st(), min = 0f, minInclusive = false),
    FloatConstr(ERROR, ContentStyle::paragraphsParaGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::paragraphsLineGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::headGapPx.st(), min = 0f),
    FloatConstr(ERROR, ContentStyle::tailGapPx.st(), min = 0f),
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
    NewSectionWidgetSpec(ContentStyle::bodyLayout.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::bodyLayout.st(), ICON_AND_LABEL),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridFillingOrder.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::gridElemBoxConform.st(), ICON),
    ToggleButtonGroupListWidgetSpec(ContentStyle::gridElemHJustifyPerCol.st(), ICON, groupsPerRow = 4),
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
    NewSectionWidgetSpec(ContentStyle::hasHead.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::headHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::headVJustify.st(), ICON),
    NewSectionWidgetSpec(ContentStyle::hasTail.st()),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailHJustify.st(), ICON),
    ToggleButtonGroupWidgetSpec(ContentStyle::tailVJustify.st(), ICON)
)


private val LETTER_STYLE_META: List<StyleMeta<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), LetterStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), LetterStyle::name.st()) { styling, style ->
        styling.letterStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    FontNameConstr(WARN, LetterStyle::fontName.st()),
    IntConstr(ERROR, LetterStyle::heightPx.st(), min = 1),
    FloatConstr(ERROR, LetterStyle::smallCaps.st(), min = 0f, minInclusive = false, max = 100f),
    EffectivitySpec(LetterStyle::backgroundWidening.st(), isAlmostEffective = { style -> style.background.alpha == 0 }),
    EffectivitySpec(LetterStyle::useUppercaseExceptions.st(), isAlmostEffective = { style -> !style.uppercase }),
    NumberStepWidgetSpec(LetterStyle::tracking.st(), 0.01f)
)


typealias Style = Any


sealed class StyleMeta<S : Style, V>(
    vararg settings: StyleSetting<S, V>
) {
    val settings: List<StyleSetting<S, V>> = settings.toList()
}


class IntConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Int>,
    val min: Int? = null,
    val max: Int? = null,
) : StyleMeta<S, Int>(setting)


class FloatConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Float>,
    val finite: Boolean = true,
    val min: Float? = null,
    val minInclusive: Boolean = true,
    val max: Float? = null,
    val maxInclusive: Boolean = true
) : StyleMeta<S, Float>(setting)


class DynChoiceConstr<S : Style>(
    val severity: Severity,
    vararg settings: StyleSetting<S, Any?>,
    val choices: (Styling, S) -> List<Any?>
) : StyleMeta<S, Any?>(*settings)


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


class NewSectionWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any?>
) : StyleMeta<S, Any?>(setting)


class DontGrowWidgetSpec<S : Style>(
    setting: StyleSetting<S, Any?>
) : StyleMeta<S, Any?>(setting)


class NumberStepWidgetSpec<S : Style>(
    setting: StyleSetting<S, Number>,
    val stepSize: Number
) : StyleMeta<S, Number>(setting)


class ToggleButtonGroupWidgetSpec<S : Style>(
    setting: StyleSetting<S, Enum<*>>,
    val show: Show
) : StyleMeta<S, Enum<*>>(setting) {
    enum class Show { LABEL, ICON, ICON_AND_LABEL }
}


class ToggleButtonGroupListWidgetSpec<S : Style>(
    setting: StyleSetting<S, ImmutableList<Enum<*>>>,
    val show: ToggleButtonGroupWidgetSpec.Show,
    val groupsPerRow: Int
) : StyleMeta<S, ImmutableList<Enum<*>>>(setting)


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
                is IntConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings }) {
                        val value = setting.getValue(style)
                        if (meta.min != null && value < meta.min || meta.max != null && value > meta.max) {
                            val minRestr = meta.min?.let { "\u2265 $it" }
                            val maxRestr = meta.max?.let { "\u2264 $it" }
                            val restriction = combineNumberRestrictions(minRestr, maxRestr)
                            log(style, setting, meta.severity, l10n("project.styling.constr.number", restriction))
                        }
                    }
                is FloatConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings }) {
                        val value = setting.getValue(style)
                        if (meta.finite && !value.isFinite() ||
                            meta.min != null && (if (meta.minInclusive) value < meta.min else value <= meta.min) ||
                            meta.max != null && (if (meta.maxInclusive) value > meta.max else value >= meta.max)
                        ) {
                            fun fmt(f: Float) = NumberFormat.getCompactNumberInstance().format(f)
                            val minRestr = meta.min?.let { (if (meta.minInclusive) "\u2265 " else "> ") + fmt(it) }
                            val maxRestr = meta.max?.let { (if (meta.maxInclusive) "\u2264 " else "< ") + fmt(it) }
                            val restriction = combineNumberRestrictions(minRestr, maxRestr)
                            val key = when {
                                meta.finite -> "project.styling.constr.finiteNumber"
                                else -> "project.styling.constr.number"
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

private fun combineNumberRestrictions(minRestr: String?, maxRestr: String?): String =
    when {
        minRestr != null && maxRestr != null -> " $minRestr & $maxRestr"
        minRestr != null -> " $minRestr"
        maxRestr != null -> " $maxRestr"
        else -> ""
    }
