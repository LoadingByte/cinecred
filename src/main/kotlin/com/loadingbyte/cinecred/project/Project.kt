package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.Picture
import java.awt.Color
import java.awt.Font


class Project(
    val styling: Styling,
    fonts: Map<String, Font>,
    pages: List<Page>
) {
    val fonts: Map<String, Font> = fonts.toMutableMap()
    val pages: List<Page> = pages.toMutableList()
}


class Styling(
    val global: Global,
    pageStyles: List<PageStyle>,
    contentStyles: List<ContentStyle>,
) {
    val pageStyles: List<PageStyle> = pageStyles.toMutableList()
    val contentStyles: List<ContentStyle> = contentStyles.toMutableList()
}


class Global(
    val fps: FPS,
    val widthPx: Int,
    val heightPx: Int,
    val background: Color,
    val unitVGapPx: Float
)


class FPS(val numerator: Int, val denominator: Int) {
    val frac: Float
        get() = numerator.toFloat() / denominator
}


data class PageStyle(
    val name: String,
    val behavior: PageBehavior,
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
    val bodyLayoutColsHJustify: List<HJustify>,
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
    val style: PageStyle,
    sections: List<Section>,
    alignBodyColsGroups: List<List<Block>>,
    alignHeadTailGroups: List<List<Block>>
) {
    val sections: List<Section> = sections.toMutableList()
    val alignBodyColsGroups: List<List<Block>> = alignBodyColsGroups.toMutableList()
    val alignHeadTailGroups: List<List<Block>> = alignHeadTailGroups.toMutableList()
}


class Section(
    columns: List<Column>,
    val vGapAfterPx: Float
) {
    val columns: List<Column> = columns.toMutableList()
}


class Column(
    val posOffsetPx: Float,
    blocks: List<Block>
) {
    val blocks: List<Block> = blocks.toMutableList()
}


class Block(
    val style: ContentStyle,
    val head: String?,
    body: List<BodyElement>,
    val tail: String?,
    val vGapAfterPx: Float
) {
    val body: List<BodyElement> = body.toMutableList()
}


sealed class BodyElement {
    class Str(val str: String) : BodyElement()
    class Pic(val pic: Picture) : BodyElement()
}
