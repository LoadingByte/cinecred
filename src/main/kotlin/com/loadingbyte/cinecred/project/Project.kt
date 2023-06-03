package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.FPS
import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.TimecodeFormat
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.imaging.Picture
import kotlinx.collections.immutable.PersistentList
import java.awt.Color
import java.util.*


class Project(
    val styling: Styling,
    val stylingCtx: StylingContext,
    val pages: PersistentList<Page>,
    val runtimeGroups: PersistentList<RuntimeGroup>
)


data class Styling(
    val global: Global,
    val pageStyles: PersistentList<PageStyle>,
    val contentStyles: PersistentList<ContentStyle>,
    val letterStyles: PersistentList<LetterStyle>
) {

    private val parentStyleLookup = IdentityHashMap<NestedStyle, Style>().apply {
        for (letterStyle in letterStyles)
            for (layer in letterStyle.layers)
                put(layer, letterStyle)
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : ListedStyle> getListedStyles(styleClass: Class<S>): PersistentList<S> = when (styleClass) {
        PageStyle::class.java -> pageStyles
        ContentStyle::class.java -> contentStyles
        LetterStyle::class.java -> letterStyles
        else -> throw IllegalArgumentException("${styleClass.name} is not a ListedStyle class.")
    } as PersistentList<S>

    fun getParentStyle(style: NestedStyle): Style =
        parentStyleLookup.getValue(style)

}


sealed interface Style


sealed interface NamedStyle : Style {
    val name: String
}


sealed interface ListedStyle : NamedStyle {
    companion object {
        val CLASSES: List<Class<out ListedStyle>> =
            listOf(PageStyle::class.java, ContentStyle::class.java, LetterStyle::class.java)
    }
}


sealed interface NestedStyle : Style


sealed interface LayerStyle : NamedStyle, NestedStyle {
    val collapsed: Boolean
}


data class Global(
    val resolution: Resolution,
    val fps: FPS,
    val timecodeFormat: TimecodeFormat,
    val runtimeFrames: Opt<Int>,
    val grounding: Color,
    val unitVGapPx: Double,
    val locale: Locale,
    val uppercaseExceptions: PersistentList<String>
) : Style


data class PageStyle(
    override val name: String,
    val afterwardSlugFrames: Int,
    val behavior: PageBehavior,
    val cardDurationFrames: Int,
    val cardFadeInFrames: Int,
    val cardFadeOutFrames: Int,
    val scrollMeltWithPrev: Boolean,
    val scrollMeltWithNext: Boolean,
    val scrollPxPerFrame: Double
) : ListedStyle


enum class PageBehavior { CARD, SCROLL }


data class ContentStyle(
    override val name: String,
    val blockOrientation: BlockOrientation,
    val spineAttachment: SpineAttachment,
    val vMarginPx: Double,
    val bodyLetterStyleName: String,
    val bodyLayout: BodyLayout,
    val gridFillingOrder: GridFillingOrder,
    val gridFillingBalanced: Boolean,
    val gridStructure: GridStructure,
    val gridForceColWidthPx: Opt<Double>,
    val gridMatchColWidths: MatchExtent,
    val gridMatchColWidthsAcrossStyles: PersistentList<String>,
    val gridMatchColUnderoccupancy: GridColUnderoccupancy,
    val gridForceRowHeightPx: Opt<Double>,
    val gridMatchRowHeight: MatchExtent,
    val gridMatchRowHeightAcrossStyles: PersistentList<String>,
    val gridCellHJustifyPerCol: PersistentList<HJustify>,
    val gridCellVJustify: VJustify,
    val gridRowGapPx: Double,
    val gridColGapPx: Double,
    val flowDirection: FlowDirection,
    val flowLineHJustify: LineHJustify,
    val flowSquareCells: Boolean,
    val flowForceCellWidthPx: Opt<Double>,
    val flowMatchCellWidth: MatchExtent,
    val flowMatchCellWidthAcrossStyles: PersistentList<String>,
    val flowForceCellHeightPx: Opt<Double>,
    val flowMatchCellHeight: MatchExtent,
    val flowMatchCellHeightAcrossStyles: PersistentList<String>,
    val flowCellHJustify: HJustify,
    val flowCellVJustify: VJustify,
    val flowLineWidthPx: Double,
    val flowLineGapPx: Double,
    val flowHGapPx: Double,
    val flowSeparator: String,
    val paragraphsLineHJustify: LineHJustify,
    val paragraphsLineWidthPx: Double,
    val paragraphsParaGapPx: Double,
    val paragraphsLineGapPx: Double,
    val hasHead: Boolean,
    val headLetterStyleName: String,
    val headForceWidthPx: Opt<Double>,
    val headMatchWidth: MatchExtent,
    val headMatchWidthAcrossStyles: PersistentList<String>,
    val headHJustify: HJustify,
    val headVJustify: HeadTailVJustify,
    val headGapPx: Double,
    val hasTail: Boolean,
    val tailLetterStyleName: String,
    val tailForceWidthPx: Opt<Double>,
    val tailMatchWidth: MatchExtent,
    val tailMatchWidthAcrossStyles: PersistentList<String>,
    val tailHJustify: HJustify,
    val tailVJustify: HeadTailVJustify,
    val tailGapPx: Double
) : ListedStyle


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

enum class HeadTailVJustify {
    FIRST_TOP, FIRST_MIDDLE, FIRST_BOTTOM,
    OVERALL_MIDDLE,
    LAST_TOP, LAST_MIDDLE, LAST_BOTTOM
}

enum class MatchExtent { OFF, WITHIN_BLOCK, ACROSS_BLOCKS }
enum class GridFillingOrder { L2R_T2B, R2L_T2B, T2B_L2R, T2B_R2L }
enum class GridStructure { FREE, EQUAL_WIDTH_COLS, SQUARE_CELLS }
enum class GridColUnderoccupancy { LEFT_OMIT, LEFT_RETAIN, RIGHT_OMIT, RIGHT_RETAIN }
enum class FlowDirection { L2R, R2L }


// Custom units:
//   - rh: Ratio of total height (w/ leading)
//   - rfh: Ratio of font height (w/o leading)


data class LetterStyle(
    override val name: String,
    val fontName: String,
    val heightPx: Double,
    val leadingTopRh: Double,
    val leadingBottomRh: Double,
    val trackingEm: Double,
    val kerning: Boolean,
    val ligatures: Boolean,
    val uppercase: Boolean,
    val useUppercaseExceptions: Boolean,
    val useUppercaseSpacing: Boolean,
    val smallCaps: SmallCaps,
    val superscript: Superscript,
    val superscriptScaling: Double,
    val superscriptHOffsetRfh: Double,
    val superscriptVOffsetRfh: Double,
    val hScaling: Double,
    val features: PersistentList<FontFeature>,
    val inheritLayersFromStyle: Opt<String>,
    val layers: PersistentList<Layer>
) : ListedStyle


enum class SmallCaps { OFF, SMALL_CAPS, PETITE_CAPS }
enum class Superscript { OFF, SUP, SUB, SUP_SUP, SUP_SUB, SUB_SUP, SUB_SUB, CUSTOM }


data class FontFeature(
    val tag: String,
    val value: Int
)


data class Layer(
    override val name: String,
    override val collapsed: Boolean,
    val coloring: LayerColoring,
    val color1: Color,
    val color2: Color,
    val gradientAngleDeg: Double,
    val gradientExtentRfh: Double,
    val gradientShiftRfh: Double,
    val shape: LayerShape,
    val stripePreset: StripePreset,
    val stripeHeightRfh: Double,
    val stripeOffsetRfh: Double,
    val stripeWidenLeftRfh: Double,
    val stripeWidenRightRfh: Double,
    val stripeWidenTopRfh: Double,
    val stripeWidenBottomRfh: Double,
    val stripeCornerJoin: LineJoin,
    val stripeCornerRadiusRfh: Double,
    val stripeDashPatternRfh: PersistentList<Double>,
    val cloneLayers: PersistentList<Int>,
    val dilationRfh: Double,
    val dilationJoin: LineJoin,
    val contour: Boolean,
    val contourThicknessRfh: Double,
    val contourJoin: LineJoin,
    val offsetCoordinateSystem: CoordinateSystem,
    val hOffsetRfh: Double,
    val vOffsetRfh: Double,
    val offsetAngleDeg: Double,
    val offsetDistanceRfh: Double,
    val hScaling: Double,
    val vScaling: Double,
    val hShearing: Double,
    val vShearing: Double,
    val anchor: LayerAnchor,
    val anchorSiblingLayer: Int,
    val clearingLayers: PersistentList<Int>,
    val clearingRfh: Double,
    val clearingJoin: LineJoin,
    val blurRadiusRfh: Double
) : LayerStyle


enum class LayerColoring { OFF, PLAIN, GRADIENT }
enum class LayerShape { TEXT, STRIPE, CLONE }
enum class StripePreset { BACKGROUND, UNDERLINE, STRIKETHROUGH, CUSTOM }
enum class LineJoin { MITER, ROUND, BEVEL }
enum class CoordinateSystem { CARTESIAN, POLAR }
enum class LayerAnchor { INDIVIDUAL, SIBLING, GLOBAL }


data class Opt<out E : Any /* non-null */>(val isActive: Boolean, val value: E)


// The following classes are deliberately not data classes to improve the speed of equality and hash code operations,
// which is important as the classes are often used as map keys.


class Page(
    val stages: PersistentList<Stage>
)


sealed interface Stage {

    val style: PageStyle
    val vGapAfterPx: Double

    class Card(
        override val style: PageStyle,
        val compounds: PersistentList<Compound>,
        override val vGapAfterPx: Double
    ) : Stage

    class Scroll(
        override val style: PageStyle,
        val laterals: PersistentList<Lateral>,
        override val vGapAfterPx: Double
    ) : Stage

}


class Compound(
    val vAnchor: VAnchor,
    val hOffsetPx: Double,
    val vOffsetPx: Double,
    val spines: PersistentList<Spine.Card>
)


class Lateral(
    val spines: PersistentList<Spine.Scroll>,
    val vGapAfterPx: Double
)


sealed interface Spine {

    val blocks: PersistentList<Block>

    class Card(
        val hookTo: Spine.Card?,
        val hookVAnchor: VAnchor,
        val selfVAnchor: VAnchor,
        val hOffsetPx: Double,
        val vOffsetPx: Double,
        override val blocks: PersistentList<Block>
    ) : Spine

    class Scroll(
        val hOffsetPx: Double,
        override val blocks: PersistentList<Block>
    ) : Spine

}


enum class VAnchor { TOP, MIDDLE, BOTTOM }


typealias PartitionId = Any

class Block(
    val style: ContentStyle,
    val head: StyledString?,
    val body: PersistentList<BodyElement>,
    val tail: StyledString?,
    val vGapAfterPx: Double,
    val matchHeadPartitionId: PartitionId,
    val matchBodyPartitionId: PartitionId,
    val matchTailPartitionId: PartitionId
)


typealias StyledString = List<Pair<String, LetterStyle>>

sealed interface BodyElement {
    class Nil(val sty: LetterStyle) : BodyElement
    class Str(val str: StyledString) : BodyElement
    class Pic(val pic: Picture) : BodyElement
}


class RuntimeGroup(val stages: PersistentList<Stage>, val runtimeFrames: Int)


val Enum<*>.label: String
    get() = when (this) {
        TimecodeFormat.SMPTE_NON_DROP_FRAME -> "SMPTE Non Drop-Frame"
        TimecodeFormat.SMPTE_DROP_FRAME -> "SMPTE Drop-Frame"
        GridStructure.SQUARE_CELLS -> l10n("ui.styling.content.flowSquareCells")
        SmallCaps.OFF, Superscript.OFF -> l10n("off")
        Superscript.CUSTOM, StripePreset.CUSTOM -> l10n("custom")
        else -> l10n("project.${javaClass.simpleName}.${name}")
    }
