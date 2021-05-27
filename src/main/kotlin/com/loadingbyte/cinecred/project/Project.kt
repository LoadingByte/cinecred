package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Picture
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import java.awt.Color
import java.awt.Font
import java.util.*


class Project(
    val styling: Styling,
    val fonts: ImmutableMap<String, Font>,
    val pages: ImmutableList<Page>
)


data class Styling constructor(
    val global: Global,
    val pageStyles: ImmutableSet<PageStyle>,
    val contentStyles: ImmutableSet<ContentStyle>,
    val letterStyles: ImmutableSet<LetterStyle>
)


data class Global(
    val widthPx: Int,
    val heightPx: Int,
    val fps: FPS,
    val timecodeFormat: TimecodeFormat,
    val background: Color,
    val unitVGapPx: Float,
    val locale: Locale,
    val uppercaseExceptions: ImmutableList<String>
)


data class FPS(val numerator: Int, val denominator: Int) {
    val frac: Float
        get() = numerator.toFloat() / denominator
}


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
)


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
)


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
    val tracking: Float,
    val foreground: Color,
    val background: Color,
    val underline: Boolean,
    val strikethrough: Boolean,
    val smallCaps: Boolean,
    val uppercase: Boolean,
    val useUppercaseExceptions: Boolean,
    val superscript: Superscript
)


enum class Superscript { SUP_2, SUP_1, NONE, SUB_1, SUB_2 }


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
