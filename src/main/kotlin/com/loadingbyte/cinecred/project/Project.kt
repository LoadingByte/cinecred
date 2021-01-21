package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Picture
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import java.awt.Color
import java.awt.Font


class Project(
    val styling: Styling,
    val fonts: ImmutableMap<String, Font>,
    val pages: ImmutableList<Page>
)


data class Styling constructor(
    val global: Global,
    val pageStyles: ImmutableList<PageStyle>,
    val contentStyles: ImmutableList<ContentStyle>,
)


data class Global(
    val fps: FPS,
    val widthPx: Int,
    val heightPx: Int,
    val background: Color,
    val unitVGapPx: Float
)


data class FPS(val numerator: Int, val denominator: Int) {
    val frac: Float
        get() = numerator.toFloat() / denominator
}


data class PageStyle(
    val name: String,
    val behavior: PageBehavior,
    val meltWithPrev: Boolean,
    val meltWithNext: Boolean,
    val afterwardSlugFrames: Int,
    val cardDurationFrames: Int,
    val cardFadeInFrames: Int,
    val cardFadeOutFrames: Int,
    val scrollPxPerFrame: Float
)


enum class PageBehavior { CARD, SCROLL }


data class ContentStyle(
    val name: String,
    val spineOrientation: SpineOrientation,
    val alignWithAxis: AlignWithAxis,
    val vMarginPx: Float,
    val bodyLayout: BodyLayout,
    // Body layouts: Grid, Flow, Paragraphs
    val bodyLayoutLineGapPx: Float,
    // Body layouts: Grid, Flow
    val bodyLayoutElemConform: BodyElementConform,
    val bodyLayoutElemVJustify: VJustify,
    val bodyLayoutHorizontalGapPx: Float,
    // Body layout: Grid
    val bodyLayoutColsHJustify: ImmutableList<HJustify>,
    // Body layouts: Flow, Paragraph
    val bodyLayoutLineHJustify: LineHJustify,
    val bodyLayoutBodyWidthPx: Float,
    // Body layout: Flow
    val bodyLayoutElemHJustify: HJustify,
    val bodyLayoutSeparator: String,
    // Body layout: Paragraphs
    val bodyLayoutParagraphGapPx: Float,
    // End of body layout settings.
    val bodyFontSpec: FontSpec,
    val hasHead: Boolean,
    val headHJustify: HJustify,
    val headVJustify: VJustify,
    val headGapPx: Float,
    val headFontSpec: FontSpec,
    val hasTail: Boolean,
    val tailHJustify: HJustify,
    val tailVJustify: VJustify,
    val tailGapPx: Float,
    val tailFontSpec: FontSpec
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
enum class LineHJustify { LEFT, CENTER, RIGHT, FULL }
enum class BodyElementConform { NOTHING, WIDTH, HEIGHT, WIDTH_AND_HEIGHT, SQUARE }


// This must be a data class because its objects will be used as hash map keys.
data class FontSpec(
    val name: String,
    val heightPx: Int,
    val color: Color
)


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
    val head: String?,
    val body: ImmutableList<BodyElement>,
    val tail: String?,
    val vGapAfterPx: Float
)


sealed class BodyElement {
    class Str(val str: String) : BodyElement()
    class Pic(val pic: Picture) : BodyElement()
}
