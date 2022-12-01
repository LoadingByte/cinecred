package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Picture
import kotlinx.collections.immutable.PersistentList
import java.awt.Color
import java.util.*


class Project(
    val styling: Styling,
    val stylingCtx: StylingContext,
    val pages: PersistentList<Page>,
    val runtimeGroups: PersistentList<RuntimeGroup>
)


data class Styling constructor(
    val global: Global,
    val pageStyles: PersistentList<PageStyle>,
    val contentStyles: PersistentList<ContentStyle>,
    val letterStyles: PersistentList<LetterStyle>
) {
    @Suppress("UNCHECKED_CAST")
    fun <S : NamedStyle> getNamedStyles(styleClass: Class<S>): PersistentList<S> = when (styleClass) {
        PageStyle::class.java -> pageStyles
        ContentStyle::class.java -> contentStyles
        LetterStyle::class.java -> letterStyles
        else -> throw IllegalArgumentException("${styleClass.name} is not a named style class.")
    } as PersistentList<S>
}


sealed interface Style


sealed interface NamedStyle : Style {
    val name: String

    companion object {
        val CLASSES: List<Class<out NamedStyle>> =
            listOf(PageStyle::class.java, ContentStyle::class.java, LetterStyle::class.java)
    }
}


data class Global(
    val widthPx: Int,
    val heightPx: Int,
    val fps: FPS,
    val timecodeFormat: TimecodeFormat,
    val runtimeFrames: Opt<Int>,
    val grounding: Color,
    val unitVGapPx: Float,
    val locale: Locale,
    val uppercaseExceptions: PersistentList<String>
) : Style


enum class TimecodeFormat { SMPTE_NON_DROP_FRAME, SMPTE_DROP_FRAME, CLOCK, FRAMES }


data class PageStyle(
    override val name: String,
    val afterwardSlugFrames: Int,
    val behavior: PageBehavior,
    val cardDurationFrames: Int,
    val cardFadeInFrames: Int,
    val cardFadeOutFrames: Int,
    val scrollMeltWithPrev: Boolean,
    val scrollMeltWithNext: Boolean,
    val scrollPxPerFrame: Float
) : NamedStyle


enum class PageBehavior { CARD, SCROLL }


data class ContentStyle(
    override val name: String,
    val blockOrientation: BlockOrientation,
    val spineAttachment: SpineAttachment,
    val vMarginPx: Float,
    val bodyLetterStyleName: String,
    val bodyLayout: BodyLayout,
    val gridFillingOrder: GridFillingOrder,
    val gridFillingBalanced: Boolean,
    val gridStructure: GridStructure,
    val gridMatchColWidths: MatchExtent,
    val gridMatchColWidthsAcrossStyles: PersistentList<String>,
    val gridMatchColUnderoccupancy: GridColUnderoccupancy,
    val gridMatchRowHeight: MatchExtent,
    val gridMatchRowHeightAcrossStyles: PersistentList<String>,
    val gridCellHJustifyPerCol: PersistentList<HJustify>,
    val gridCellVJustify: VJustify,
    val gridRowGapPx: Float,
    val gridColGapPx: Float,
    val flowDirection: FlowDirection,
    val flowLineHJustify: LineHJustify,
    val flowSquareCells: Boolean,
    val flowMatchCellWidth: MatchExtent,
    val flowMatchCellWidthAcrossStyles: PersistentList<String>,
    val flowMatchCellHeight: MatchExtent,
    val flowMatchCellHeightAcrossStyles: PersistentList<String>,
    val flowCellHJustify: HJustify,
    val flowCellVJustify: VJustify,
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
    val headMatchWidth: MatchExtent,
    val headMatchWidthAcrossStyles: PersistentList<String>,
    val headHJustify: HJustify,
    val headVJustify: VJustify,
    val headGapPx: Float,
    val hasTail: Boolean,
    val tailLetterStyleName: String,
    val tailMatchWidth: MatchExtent,
    val tailMatchWidthAcrossStyles: PersistentList<String>,
    val tailHJustify: HJustify,
    val tailVJustify: VJustify,
    val tailGapPx: Float
) : NamedStyle


enum class BlockOrientation { HORIZONTAL, VERTICAL }

enum class SpineAttachment {
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
enum class MatchExtent { OFF, WITHIN_BLOCK, ACROSS_BLOCKS }
enum class GridFillingOrder { L2R_T2B, R2L_T2B, T2B_L2R, T2B_R2L }
enum class GridStructure { FREE, EQUAL_WIDTH_COLS, SQUARE_CELLS }
enum class GridColUnderoccupancy { LEFT_OMIT, LEFT_RETAIN, RIGHT_OMIT, RIGHT_RETAIN }
enum class FlowDirection { L2R, R2L }


data class LetterStyle(
    override val name: String,
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
    val features: PersistentList<FontFeature>,
    val decorations: PersistentList<TextDecoration>,
    val background: Opt<Color>,
    val backgroundWidenLeftPx: Float,
    val backgroundWidenRightPx: Float,
    val backgroundWidenTopPx: Float,
    val backgroundWidenBottomPx: Float
) : NamedStyle


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
    val dashPatternPx: PersistentList<Float>
) : Style


enum class TextDecorationPreset { UNDERLINE, STRIKETHROUGH, OFF }
enum class LineJoin { MITER, ROUND, BEVEL }


data class Opt<out E : Any /* non-null */>(val isActive: Boolean, val value: E)


// The following classes are deliberately not data classes to improve the speed of equality and hash code operations,
// which is important as the classes are often used as map keys.


class Page(
    val stages: PersistentList<Stage>
)


class Stage(
    val style: PageStyle,
    val segments: PersistentList<Segment>,
    val vGapAfterPx: Float
)


class Segment(
    val spines: PersistentList<Spine>,
    val vGapAfterPx: Float
)


class Spine(
    val posOffsetPx: Float,
    val blocks: PersistentList<Block>
)


typealias PartitionId = Any

class Block(
    val style: ContentStyle,
    val head: StyledString?,
    val body: PersistentList<BodyElement>,
    val tail: StyledString?,
    val vGapAfterPx: Float,
    val matchHeadPartitionId: PartitionId,
    val matchBodyPartitionId: PartitionId,
    val matchTailPartitionId: PartitionId
)


typealias StyledString = List<Pair<String, LetterStyle>>

sealed class BodyElement {
    class Nil(val sty: LetterStyle) : BodyElement()
    class Str(val str: StyledString) : BodyElement()
    class Pic(val pic: Picture) : BodyElement()
}


class RuntimeGroup(val stages: PersistentList<Stage>, val runtimeFrames: Int)
