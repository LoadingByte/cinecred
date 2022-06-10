package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Picture
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import java.awt.Color
import java.awt.Font
import java.util.*


class Project(
    val styling: Styling,
    val fonts: ImmutableMap<String, Font>,
    val pages: ImmutableList<Page>,
    val runtimeGroups: ImmutableList<RuntimeGroup>
)


data class Styling constructor(
    val global: Global,
    val pageStyles: ImmutableList<PageStyle>,
    val contentStyles: ImmutableList<ContentStyle>,
    val letterStyles: ImmutableList<LetterStyle>
) {

    // We want to allow duplicate styles, but equality should ignore order and duplicates. Hence, we use lists to
    // store the styles, but generate the following sets, which are then only used for equals() and hashCode().
    // Note that since almost every styling object will eventually have its equals() or hashCode() called,
    // we do not need to make these properties lazy.
    private val pageStyleSet = pageStyles.toImmutableSet()
    private val contentStyleSet = contentStyles.toImmutableSet()
    private val letterStyleSet = letterStyles.toImmutableSet()

    override fun equals(other: Any?) =
        this === other ||
                other is Styling && global == other.global && pageStyleSet == other.pageStyleSet &&
                contentStyleSet == other.contentStyleSet && letterStyleSet == other.letterStyleSet

    override fun hashCode(): Int {
        var result = global.hashCode()
        result = 31 * result + pageStyleSet.hashCode()
        result = 31 * result + contentStyleSet.hashCode()
        result = 31 * result + letterStyleSet.hashCode()
        return result
    }

}


sealed class Style


data class Global(
    val widthPx: Int,
    val heightPx: Int,
    val fps: FPS,
    val timecodeFormat: TimecodeFormat,
    val runtimeFrames: Opt<Int>,
    val grounding: Color,
    val unitVGapPx: Float,
    val locale: Locale,
    val uppercaseExceptions: ImmutableList<String>
) : Style()


enum class TimecodeFormat { SMPTE_NON_DROP_FRAME, SMPTE_DROP_FRAME, CLOCK, FRAMES }


data class PageStyle(
    val name: String,
    val afterwardSlugFrames: Int,
    val behavior: PageBehavior,
    val cardDurationFrames: Int,
    val cardFadeInFrames: Int,
    val cardFadeOutFrames: Int,
    val scrollMeltWithPrev: Boolean,
    val scrollMeltWithNext: Boolean,
    val scrollPxPerFrame: Float
) : Style()


enum class PageBehavior { CARD, SCROLL }


data class ContentStyle(
    val name: String,
    val spineOrientation: SpineOrientation,
    val alignWithAxis: AlignWithAxis,
    val vMarginPx: Float,
    val bodyLetterStyleName: String,
    val bodyLayout: BodyLayout,
    val gridFillingOrder: GridFillingOrder,
    val gridElemBoxConform: BodyElementBoxConform,
    val gridElemHJustifyPerCol: ImmutableList<HJustify>,
    val gridElemVJustify: VJustify,
    val gridRowGapPx: Float,
    val gridColGapPx: Float,
    val flowDirection: FlowDirection,
    val flowLineHJustify: LineHJustify,
    val flowElemBoxConform: BodyElementBoxConform,
    val flowElemHJustify: HJustify,
    val flowElemVJustify: VJustify,
    val flowLineWidthPx: Float,
    val flowLineGapPx: Float,
    val flowHGapPx: Float,
    val flowSeparator: String,
    val paragraphsLineHJustify: LineHJustify,
    val paragraphsLineWidthPx: Float,
    val paragraphsParaGapPx: Float,
    val paragraphsLineGapPx: Float,
    val hasHead: Boolean,
    val headLetterStyleName: String,
    val headHJustify: HJustify,
    val headVJustify: VJustify,
    val headGapPx: Float,
    val hasTail: Boolean,
    val tailLetterStyleName: String,
    val tailHJustify: HJustify,
    val tailVJustify: VJustify,
    val tailGapPx: Float
) : Style()


enum class SpineOrientation { HORIZONTAL, VERTICAL }

enum class AlignWithAxis {
    OVERALL_CENTER,
    HEAD_LEFT, HEAD_CENTER, HEAD_RIGHT,
    HEAD_GAP_CENTER,
    BODY_LEFT, BODY_CENTER, BODY_RIGHT,
    TAIL_GAP_CENTER,
    TAIL_LEFT, TAIL_CENTER, TAIL_RIGHT
}

enum class BodyLayout { GRID, FLOW, PARAGRAPHS }
enum class HJustify { LEFT, CENTER, RIGHT }
enum class VJustify { TOP, MIDDLE, BOTTOM }
enum class LineHJustify { LEFT, CENTER, RIGHT, FULL_LAST_LEFT, FULL_LAST_CENTER, FULL_LAST_RIGHT, FULL_LAST_FULL }
enum class BodyElementBoxConform { NOTHING, WIDTH, HEIGHT, WIDTH_AND_HEIGHT, SQUARE }
enum class GridFillingOrder { L2R_T2B, R2L_T2B, T2B_L2R, T2B_R2L }
enum class FlowDirection { L2R, R2L }


data class LetterStyle(
    val name: String,
    val fontName: String,
    val heightPx: Int,
    val foreground: Color,
    val leadingTopRem: Float,
    val leadingBottomRem: Float,
    val trackingEm: Float,
    val kerning: Boolean,
    val ligatures: Boolean,
    val uppercase: Boolean,
    val useUppercaseExceptions: Boolean,
    val useUppercaseSpacing: Boolean,
    val smallCaps: SmallCaps,
    val superscript: Superscript,
    val hOffsetRem: Float,
    val vOffsetRem: Float,
    val scaling: Float,
    val hScaling: Float,
    val hShearing: Float,
    val features: ImmutableList<FontFeature>,
    val decorations: ImmutableList<TextDecoration>,
    val background: Opt<Color>,
    val backgroundWidenLeftPx: Float,
    val backgroundWidenRightPx: Float,
    val backgroundWidenTopPx: Float,
    val backgroundWidenBottomPx: Float
) : Style()


enum class SmallCaps { OFF, SMALL_CAPS, PETITE_CAPS }
enum class Superscript { OFF, SUP, SUB, SUP_SUP, SUP_SUB, SUB_SUP, SUB_SUB }


data class FontFeature(
    val tag: String,
    val value: Int
)


data class TextDecoration(
    val color: Opt<Color>,
    val preset: TextDecorationPreset,
    val offsetPx: Float,
    val thicknessPx: Float,
    val widenLeftPx: Float,
    val widenRightPx: Float,
    val clearingPx: Opt<Float>,
    val clearingJoin: LineJoin,
    val dashPatternPx: ImmutableList<Float>
) : Style()


enum class TextDecorationPreset { UNDERLINE, STRIKETHROUGH, OFF }
enum class LineJoin { MITER, ROUND, BEVEL }


data class Opt<out T>(val isActive: Boolean, val value: T)


class Page(
    val stages: ImmutableList<Stage>,
    val alignBodyColsGroups: ImmutableList<ImmutableList<Block>>,
    val alignHeadTailGroups: ImmutableList<ImmutableList<Block>>
)


class Stage(
    val style: PageStyle,
    val segments: ImmutableList<Segment>,
    val vGapAfterPx: Float
)


class Segment(
    val columns: ImmutableList<Column>,
    val vGapAfterPx: Float
)


class Column(
    val posOffsetPx: Float,
    val blocks: ImmutableList<Block>
)


class Block(
    val style: ContentStyle,
    val head: StyledString?,
    val body: ImmutableList<BodyElement>,
    val tail: StyledString?,
    val vGapAfterPx: Float
)


typealias StyledString = List<Pair<String, LetterStyle>>

sealed class BodyElement {
    class Str(val str: StyledString) : BodyElement()
    class Pic(val pic: Picture) : BodyElement()
}


class RuntimeGroup(val stages: ImmutableList<Stage>, val runtimeFrames: Int)
