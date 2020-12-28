package com.loadingbyte.cinecred.project

import java.awt.Color


class Project(
    val styling: Styling,
    pages: List<Page>
) {
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
    val unitHGapPx: Float,
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
    val spineDir: SpineDir,
    val centerOn: CenterOn,
    val bodyLayout: BodyLayout,
    val colsBodyLayoutColTypes: List<ColType>,
    val colsBodyLayoutColGapPx: Float,
    val flowBodyLayoutBodyWidthPx: Float,
    val flowBodyLayoutJustify: FlowJustify,
    val flowBodyLayoutSeparator: String,
    val flowBodyLayoutSeparatorSpacingPx: Float,
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


enum class SpineDir { HORIZONTAL, VERTICAL }
enum class CenterOn { EVERYTHING, HEAD, HEAD_GAP, BODY, TAIL_GAP, TAIL }
enum class BodyLayout { COLUMNS, FLOW }
enum class ColType { LEFT, CENTER, RIGHT, VACANT }
enum class FlowJustify { LEFT, CENTER, RIGHT, FULL }
enum class HJustify { LEFT, CENTER, RIGHT }
enum class VJustify { TOP_TOP, TOP_MIDDLE, TOP_BOTTOM, MIDDLE, BOTTOM_TOP, BOTTOM_MIDDLE, BOTTOM_BOTTOM }


// This must be a data class because its objects will be used as hash map keys.
data class FontSpec(
    val name: String,
    val heightPx: Int,
    val extraLineSpacingPx: Float,
    val color: Color
)


class Page(
    val style: PageStyle,
    sections: List<Section>,
    sectionGroups: List<List<Section>>,
    blockGroups: List<List<Block>>
) {
    val sections: List<Section> = sections.toMutableList()
    val sectionGroups: List<List<Section>> = sectionGroups.toMutableList()
    val blockGroups: List<List<Block>> = blockGroups.toMutableList()
}


class Section(
    columns: List<Column>,
    val vGapAfterPx: Float
) {
    val columns: List<Column> = columns.toMutableList()
}


class Column(
    blocks: List<Block>,
    val hGapAfterPx: Float
) {
    val blocks: List<Block> = blocks.toMutableList()
}


class Block(
    val style: ContentStyle,
    val head: String?,
    body: List<String>,
    val tail: String?,
    val vGapAfterPx: Float
) {
    val body: List<String> = body.toMutableList()
}
