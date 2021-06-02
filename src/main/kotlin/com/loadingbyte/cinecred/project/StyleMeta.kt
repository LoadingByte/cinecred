package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.Severity.ERROR
import com.loadingbyte.cinecred.common.Severity.WARN
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.AlignWithAxis.*
import com.loadingbyte.cinecred.project.NumberConstr.Inequality.*
import com.loadingbyte.cinecred.projectio.supportsDropFrameTimecode
import java.awt.Color
import kotlin.math.floor
import kotlin.reflect.KProperty1


private val GLOBAL_META: List<StyleMeta<Global, *>> = listOf(
    NumberConstr(ERROR, Global::widthPx, LARGER_0),
    NumberConstr(ERROR, Global::heightPx, LARGER_0),
    FPSConstr(ERROR, Global::fps),
    NumberConstr(ERROR, Global::unitVGapPx, LARGER_0),
    DynChoiceConstr(ERROR, Global::timecodeFormat) { _, global ->
        val formats = TimecodeFormat.values().toMutableList().apply { }
        if (!global.fps.supportsDropFrameTimecode)
            formats.remove(TimecodeFormat.SMPTE_DROP_FRAME)
        formats
    },
    ColorConstr(ERROR, Global::background, allowAlpha = false),
    NumberStepWidgetSpec(Global::widthPx, 10),
    NumberStepWidgetSpec(Global::heightPx, 10),
    DontGrowWidgetSpec(Global::uppercaseExceptions)
)


private val PAGE_STYLE_META: List<StyleMeta<PageStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), PageStyle::name) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), PageStyle::name) { styling, style ->
        styling.pageStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    NumberConstr(ERROR, PageStyle::afterwardSlugFrames, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardDurationFrames, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardFadeInFrames, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::cardFadeOutFrames, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, PageStyle::scrollPxPerFrame, LARGER_0),
    JudgeConstr(WARN, l10n("project.styling.constr.fractionalScrollPx"), PageStyle::scrollPxPerFrame) { _, style ->
        val value = style.scrollPxPerFrame
        floor(value) == value
    },
    OccurrenceSpec(
        PageStyle::cardDurationFrames, PageStyle::cardFadeInFrames, PageStyle::cardFadeOutFrames,
        isRelevant = { style -> style.behavior == PageBehavior.CARD }
    ),
    OccurrenceSpec(
        PageStyle::scrollMeltWithPrev, PageStyle::scrollMeltWithNext, PageStyle::scrollPxPerFrame,
        isRelevant = { style -> style.behavior == PageBehavior.SCROLL }
    ),
    OccurrenceSpec(
        PageStyle::afterwardSlugFrames,
        isAdmissible = { style -> !(style.behavior == PageBehavior.SCROLL && style.scrollMeltWithNext) }
    )
)


private val CONTENT_STYLE_META: List<StyleMeta<ContentStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), ContentStyle::name) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), ContentStyle::name) { styling, style ->
        styling.contentStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    DynChoiceConstr(WARN, ContentStyle::alignWithAxis) { _, style ->
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
    NumberConstr(ERROR, ContentStyle::vMarginPx, LARGER_OR_EQUAL_0),
    DynChoiceConstr(
        WARN, ContentStyle::bodyLetterStyleName, ContentStyle::headLetterStyleName, ContentStyle::tailLetterStyleName,
        choices = { styling, _ -> styling.letterStyles.map(LetterStyle::name) }
    ),
    NumberConstr(ERROR, ContentStyle::gridRowGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::gridColGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::flowLineWidthPx, LARGER_0),
    NumberConstr(ERROR, ContentStyle::flowLineGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::flowHGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::paragraphsLineWidthPx, LARGER_0),
    NumberConstr(ERROR, ContentStyle::paragraphsParaGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::paragraphsLineGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::headGapPx, LARGER_OR_EQUAL_0),
    NumberConstr(ERROR, ContentStyle::tailGapPx, LARGER_OR_EQUAL_0),
    OccurrenceSpec(
        ContentStyle::gridFillingOrder, ContentStyle::gridElemBoxConform, ContentStyle::gridElemHJustifyPerCol,
        ContentStyle::gridElemVJustify, ContentStyle::gridRowGapPx, ContentStyle::gridColGapPx,
        isRelevant = { style -> style.bodyLayout == BodyLayout.GRID }
    ),
    OccurrenceSpec(
        ContentStyle::flowDirection, ContentStyle::flowLineHJustify, ContentStyle::flowElemBoxConform,
        ContentStyle::flowElemHJustify, ContentStyle::flowElemVJustify, ContentStyle::flowLineWidthPx,
        ContentStyle::flowLineGapPx, ContentStyle::flowHGapPx, ContentStyle::flowSeparator,
        isRelevant = { style -> style.bodyLayout == BodyLayout.FLOW }
    ),
    OccurrenceSpec(
        ContentStyle::paragraphsLineHJustify, ContentStyle::paragraphsLineWidthPx, ContentStyle::paragraphsParaGapPx,
        ContentStyle::paragraphsLineGapPx,
        isRelevant = { style -> style.bodyLayout == BodyLayout.PARAGRAPHS }
    ),
    OccurrenceSpec(
        ContentStyle::headLetterStyleName, ContentStyle::headHJustify, ContentStyle::headVJustify,
        ContentStyle::headGapPx,
        isRelevant = ContentStyle::hasHead
    ),
    OccurrenceSpec(
        ContentStyle::tailLetterStyleName, ContentStyle::tailHJustify, ContentStyle::tailVJustify,
        ContentStyle::tailGapPx,
        isRelevant = ContentStyle::hasTail
    ),
    OccurrenceSpec(
        ContentStyle::spineOrientation,
        isAdmissible = { style -> style.hasHead || style.hasTail }
    ),
    OccurrenceSpec(
        ContentStyle::gridElemVJustify,
        isAdmissible = { style ->
            style.gridElemHJustifyPerCol.size >= 2 || style.gridElemBoxConform.let {
                it == BodyElementBoxConform.HEIGHT || it == BodyElementBoxConform.WIDTH_AND_HEIGHT ||
                        it == BodyElementBoxConform.SQUARE
            }
        }
    ),
    OccurrenceSpec(
        ContentStyle::gridColGapPx,
        isAdmissible = { style -> style.gridElemHJustifyPerCol.size >= 2 }
    ),
    OccurrenceSpec(
        ContentStyle::headVJustify,
        isAdmissible = { style -> style.spineOrientation == SpineOrientation.HORIZONTAL }
    ),
    OccurrenceSpec(
        ContentStyle::tailVJustify,
        isAdmissible = { style -> style.spineOrientation == SpineOrientation.HORIZONTAL }
    ),
    NumberStepWidgetSpec(ContentStyle::flowLineWidthPx, 10f),
    DontGrowWidgetSpec(ContentStyle::flowSeparator),
    NumberStepWidgetSpec(ContentStyle::paragraphsLineWidthPx, 10f)
)


private val LETTER_STYLE_META: List<StyleMeta<LetterStyle, *>> = listOf(
    JudgeConstr(WARN, l10n("blank"), LetterStyle::name) { _, style -> style.name.isNotBlank() },
    JudgeConstr(ERROR, l10n("project.styling.constr.duplicateStyleName"), LetterStyle::name) { styling, style ->
        styling.letterStyles.all { o -> o === style || !o.name.equals(style.name, ignoreCase = true) }
    },
    FontNameConstr(WARN, LetterStyle::fontName),
    NumberConstr(ERROR, LetterStyle::heightPx, LARGER_0),
    OccurrenceSpec(LetterStyle::useUppercaseExceptions, isAdmissible = LetterStyle::uppercase),
    NumberStepWidgetSpec(LetterStyle::tracking, 0.01f)
)


typealias Style = Any


sealed class StyleMeta<S : Style, V>(
    vararg props: KProperty1<S, V>
) {
    val settings: List<StyleSetting<S, V>> = props.map(::toStyleSetting)
}


class NumberConstr<S : Style>(
    val severity: Severity,
    prop: KProperty1<S, Number>,
    val inequality: Inequality = NONE,
    val finite: Boolean = true
) : StyleMeta<S, Number>(prop) {
    enum class Inequality { NONE, LARGER_OR_EQUAL_0, LARGER_0 }
}


class DynChoiceConstr<S : Style, V>(
    val severity: Severity,
    vararg props: KProperty1<S, V>,
    val choices: (Styling, S) -> List<V>
) : StyleMeta<S, V>(*props)


class ColorConstr<S : Style>(
    val severity: Severity,
    prop: KProperty1<S, Color>,
    val allowAlpha: Boolean
) : StyleMeta<S, Color>(prop)


class FPSConstr<S : Style>(
    val severity: Severity,
    prop: KProperty1<S, FPS>
) : StyleMeta<S, FPS>(prop)


class FontNameConstr<S : Style>(
    val severity: Severity,
    prop: KProperty1<S, String>
) : StyleMeta<S, String>(prop)


class JudgeConstr<S : Style>(
    val severity: Severity,
    val msg: String,
    vararg props: KProperty1<S, *>,
    val judge: (Styling, S) -> Boolean
) : StyleMeta<S, Any?>(*props)


class OccurrenceSpec<S : Style>(
    vararg props: KProperty1<S, *>,
    val isRelevant: ((S) -> Boolean)? = null,
    val isAdmissible: ((S) -> Boolean)? = null
) : StyleMeta<S, Any?>(*props) {
    init {
        require(isRelevant != null || isAdmissible != null)
    }
}


class DontGrowWidgetSpec<S : Style>(
    prop: KProperty1<S, *>
) : StyleMeta<S, Any?>(prop)


class NumberStepWidgetSpec<S : Style, N : Number>(
    prop: KProperty1<S, N>,
    val stepSize: N
) : StyleMeta<S, N>(prop)


fun <S : Style> getStyleMeta(style: S): List<StyleMeta<S, *>> =
    getStyleMeta(style.javaClass)

@Suppress("UNCHECKED_CAST")
fun <S : Style> getStyleMeta(styleClass: Class<S>): List<StyleMeta<S, *>> = when (styleClass) {
    Global::class.java -> GLOBAL_META as List<StyleMeta<S, *>>
    PageStyle::class.java -> PAGE_STYLE_META as List<StyleMeta<S, *>>
    ContentStyle::class.java -> CONTENT_STYLE_META as List<StyleMeta<S, *>>
    LetterStyle::class.java -> LETTER_STYLE_META as List<StyleMeta<S, *>>
    else -> throw IllegalArgumentException("${styleClass.name} is not a style class.")
}


fun <S : Style> findIrrelevantSettings(style: S) =
    evaluateOccurrence(style, OccurrenceSpec<S>::isRelevant)

fun <S : Style> findInadmissibleSettings(style: S) =
    evaluateOccurrence(style, OccurrenceSpec<S>::isAdmissible)

private fun <S : Style> evaluateOccurrence(
    style: S,
    which: (OccurrenceSpec<S>) -> ((S) -> Boolean)?
): Set<StyleSetting<*, *>> {
    val explicitlyPositive = HashSet<StyleSetting<*, *>>()
    val explicitlyNegative = HashSet<StyleSetting<*, *>>()
    for (meta in getStyleMeta(style))
        if (meta is OccurrenceSpec) {
            val func = which(meta)
            if (func != null)
                (if (func(style)) explicitlyPositive else explicitlyNegative).addAll(meta.settings)
        }
    explicitlyNegative.removeAll(explicitlyPositive)
    return explicitlyNegative
}


class ConstraintViolation(val style: Style, val setting: StyleSetting<*, *>, val severity: Severity, val msg: String?)

fun verifyConstraints(styling: Styling, isFontName: (String) -> Boolean): List<ConstraintViolation> {
    val violations = mutableListOf<ConstraintViolation>()

    fun log(style: Style, setting: StyleSetting<*, *>, severity: Severity, msg: String?) {
        violations.add(ConstraintViolation(style, setting, severity, msg))
    }

    fun <S : Style> verifyStyle(style: S) {
        val ignoreSettings = findIrrelevantSettings(style) + findInadmissibleSettings(style)

        for (meta in getStyleMeta(style))
            when (meta) {
                is NumberConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (meta.inequality == LARGER_OR_EQUAL_0 && setting.get(style).toFloat() < 0 ||
                            meta.inequality == LARGER_0 && setting.get(style).toFloat() <= 0 ||
                            meta.finite && !setting.get(style).toFloat().isFinite()
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
                is DynChoiceConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (setting.get(style) !in meta.choices(styling, style))
                            log(style, setting, meta.severity, l10n("project.styling.constr.dynChoice"))
                is ColorConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (!meta.allowAlpha && setting.get(style).alpha != 255)
                            log(style, setting, meta.severity, l10n("project.styling.constr.colorAlpha"))
                is FPSConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (setting.get(style).run { numerator <= 0 || denominator <= 0 || !frac.isFinite() })
                            log(style, setting, meta.severity, l10n("project.styling.constr.fps"))
                is FontNameConstr ->
                    for (setting in meta.settings.filter { it !in ignoreSettings })
                        if (!isFontName(setting.get(style)))
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
