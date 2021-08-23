package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.AlignWithAxis.*
import com.loadingbyte.cinecred.projectio.supportsDropFrameTimecode
import java.awt.Color
import java.text.NumberFormat
import kotlin.math.floor


@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleConstraints(styleClass: Class<S>): List<StyleConstraint<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_META as List<StyleConstraint<S, *>>
    PageStyle::class.java -> PAGE_STYLE_META as List<StyleConstraint<S, *>>
    ContentStyle::class.java -> CONTENT_STYLE_META as List<StyleConstraint<S, *>>
    LetterStyle::class.java -> LETTER_STYLE_META as List<StyleConstraint<S, *>>
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
}


private const val ASPECT_RATIO_LIMIT = 32

private val GLOBAL_META: List<StyleConstraint<Global, *>> = listOf(
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
    FloatConstr(ERROR, Global::unitVGapPx.st(), min = 0f, minInclusive = false)
)


private val PAGE_STYLE_META: List<StyleConstraint<PageStyle, *>> = listOf(
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
    }
)


private val CONTENT_STYLE_META: List<StyleConstraint<ContentStyle, *>> = listOf(
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
    FloatConstr(ERROR, ContentStyle::tailGapPx.st(), min = 0f)
)


private val LETTER_STYLE_META: List<StyleConstraint<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), LetterStyle::name.st()) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), LetterStyle::name.st()) { styling, style ->
        styling.letterStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    FontNameConstr(WARN, LetterStyle::fontName.st()),
    IntConstr(ERROR, LetterStyle::heightPx.st(), min = 1),
    FloatConstr(ERROR, LetterStyle::smallCaps.st(), min = 0f, minInclusive = false, max = 100f)
)


sealed class StyleConstraint<S : Style, V>(
    vararg settings: StyleSetting<S, V>
) {
    val settings: List<StyleSetting<S, V>> = settings.toList()
}


class IntConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Int>,
    val min: Int? = null,
    val max: Int? = null,
) : StyleConstraint<S, Int>(setting)


class FloatConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Float>,
    val finite: Boolean = true,
    val min: Float? = null,
    val minInclusive: Boolean = true,
    val max: Float? = null,
    val maxInclusive: Boolean = true
) : StyleConstraint<S, Float>(setting)


class DynChoiceConstr<S : Style>(
    val severity: Severity,
    vararg settings: StyleSetting<S, Any?>,
    val choices: (Styling, S) -> List<Any?>
) : StyleConstraint<S, Any?>(*settings)


class ColorConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, Color>,
    val allowAlpha: Boolean
) : StyleConstraint<S, Color>(setting)


class FPSConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, FPS>
) : StyleConstraint<S, FPS>(setting)


class FontNameConstr<S : Style>(
    val severity: Severity,
    setting: StyleSetting<S, String>
) : StyleConstraint<S, String>(setting)


class JudgeConstr<S : Style>(
    val severity: Severity,
    val msg: String,
    vararg settings: StyleSetting<S, Any?>,
    val judge: (Styling, S) -> Boolean
) : StyleConstraint<S, Any?>(*settings)


class ConstraintViolation(val style: Style, val setting: StyleSetting<*, *>, val severity: Severity, val msg: String?)

fun verifyConstraints(styling: Styling, isFontName: (String) -> Boolean): List<ConstraintViolation> {
    val violations = mutableListOf<ConstraintViolation>()

    fun log(style: Style, setting: StyleSetting<*, *>, severity: Severity, msg: String?) {
        violations.add(ConstraintViolation(style, setting, severity, msg))
    }

    fun <S : Style> verifyStyle(style: S) {
        val ignoreSettings = findIneffectiveSettings(style)

        for (cst in getStyleConstraints(style.javaClass))
            when (cst) {
                is IntConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings }) {
                        val value = setting.getValue(style)
                        if (cst.min != null && value < cst.min || cst.max != null && value > cst.max) {
                            val minRestr = cst.min?.let { "\u2265 $it" }
                            val maxRestr = cst.max?.let { "\u2264 $it" }
                            val restriction = combineNumberRestrictions(minRestr, maxRestr)
                            log(style, setting, cst.severity, l10n("project.styling.constr.number", restriction))
                        }
                    }
                is FloatConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings }) {
                        val value = setting.getValue(style)
                        if (cst.finite && !value.isFinite() ||
                            cst.min != null && (if (cst.minInclusive) value < cst.min else value <= cst.min) ||
                            cst.max != null && (if (cst.maxInclusive) value > cst.max else value >= cst.max)
                        ) {
                            fun fmt(f: Float) = NumberFormat.getCompactNumberInstance().format(f)
                            val minRestr = cst.min?.let { (if (cst.minInclusive) "\u2265 " else "> ") + fmt(it) }
                            val maxRestr = cst.max?.let { (if (cst.maxInclusive) "\u2264 " else "< ") + fmt(it) }
                            val restriction = combineNumberRestrictions(minRestr, maxRestr)
                            val key = when {
                                cst.finite -> "project.styling.constr.finiteNumber"
                                else -> "project.styling.constr.number"
                            }
                            log(style, setting, cst.severity, l10n(key, restriction))
                        }
                    }
                is DynChoiceConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings })
                        if (setting.getValue(style) !in cst.choices(styling, style))
                            log(style, setting, cst.severity, l10n("project.styling.constr.dynChoice"))
                is ColorConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings })
                        if (!cst.allowAlpha && setting.getValue(style).alpha != 255)
                            log(style, setting, cst.severity, l10n("project.styling.constr.colorAlpha"))
                is FPSConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings })
                        if (setting.getValue(style).run { numerator <= 0 || denominator <= 0 || !frac.isFinite() })
                            log(style, setting, cst.severity, l10n("project.styling.constr.fps"))
                is FontNameConstr ->
                    for (setting in cst.settings.filter { it !in ignoreSettings })
                        if (!isFontName(setting.getValue(style)))
                            log(style, setting, cst.severity, l10n("project.styling.constr.font"))
                is JudgeConstr ->
                    if (!cst.judge(styling, style)) {
                        val settings = cst.settings.filter { it !in ignoreSettings }
                        log(style, settings[0], cst.severity, cst.msg)
                        for (setting in settings.drop(1))
                            log(style, setting, cst.severity, null)
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
