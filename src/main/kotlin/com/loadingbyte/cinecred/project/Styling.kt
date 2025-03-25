package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.imaging.Tape
import kotlinx.collections.immutable.PersistentList
import java.awt.Font
import java.util.*
import kotlin.io.path.name


data class Styling(
    val global: Global,
    val pageStyles: PersistentList<PageStyle>,
    val contentStyles: PersistentList<ContentStyle>,
    val letterStyles: PersistentList<LetterStyle>,
    val pictureStyles: PersistentList<PictureStyle>,
    val tapeStyles: PersistentList<TapeStyle>
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
        PictureStyle::class.java -> pictureStyles
        TapeStyle::class.java -> tapeStyles
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
        val CLASSES: List<Class<out ListedStyle>> = listOf(
            PageStyle::class.java,
            ContentStyle::class.java,
            LetterStyle::class.java,
            PictureStyle::class.java,
            TapeStyle::class.java
        )
    }
}


sealed interface PopupStyle : ListedStyle {
    val volatile: Boolean
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
    val blankFirstFrame: Boolean,
    val blankLastFrame: Boolean,
    val grounding: Color4f,
    val unitVGapPx: Double,
    val locale: Locale,
    val uppercaseExceptions: PersistentList<String>
) : Style


data class PageStyle(
    override val name: String,
    val subsequentGapFrames: Int,
    val behavior: PageBehavior,
    val cardRuntimeFrames: Int,
    val cardFadeInFrames: Int,
    val cardFadeOutFrames: Int,
    /** Only retained for backwards compatibility. */
    val scrollMeltWithPrev: Boolean,
    /** Only retained for backwards compatibility. */
    val scrollMeltWithNext: Boolean,
    val scrollPxPerFrame: Double,
    val scrollRuntimeFrames: Opt<Int>
) : ListedStyle


enum class PageBehavior { CARD, SCROLL }


data class ContentStyle(
    override val name: String,
    val blockOrientation: BlockOrientation,
    val spineAttachment: SpineAttachment,
    val vMarginPx: Double,
    val bodyLetterStyleName: String,
    val bodyLayout: BodyLayout,
    val sort: Sort,
    val gridCols: Int,
    val gridFillingOrder: GridFillingOrder,
    val gridFillingBalanced: Boolean,
    val gridStructure: GridStructure,
    val gridForceColWidthPx: Opt<Double>,
    val gridForceRowHeightPx: Opt<Double>,
    val gridMatchColWidths: MatchExtent,
    val gridMatchColWidthsAcrossStyles: PersistentList<String>,
    val gridMatchColUnderoccupancy: GridColUnderoccupancy,
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
    val flowForceCellHeightPx: Opt<Double>,
    val flowMatchCellWidth: MatchExtent,
    val flowMatchCellWidthAcrossStyles: PersistentList<String>,
    val flowMatchCellHeight: MatchExtent,
    val flowMatchCellHeightAcrossStyles: PersistentList<String>,
    val flowCellHJustify: HJustify,
    val flowCellVJustify: VJustify,
    val flowLineWidthPx: Double,
    val flowLineGapPx: Double,
    val flowHGapPx: Double,
    val flowSeparator: String,
    val flowSeparatorLetterStyleName: Opt<String>,
    val flowSeparatorVJustify: AppendageVJustify,
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
    val headVShelve: AppendageVShelve,
    val headVJustify: AppendageVJustify,
    val headGapPx: Double,
    val headLeader: String,
    val headLeaderLetterStyleName: Opt<String>,
    val headLeaderHJustify: SingleLineHJustify,
    val headLeaderVJustify: AppendageVJustify,
    val headLeaderMarginLeftPx: Double,
    val headLeaderMarginRightPx: Double,
    val headLeaderSpacingPx: Double,
    val hasTail: Boolean,
    val tailLetterStyleName: String,
    val tailForceWidthPx: Opt<Double>,
    val tailMatchWidth: MatchExtent,
    val tailMatchWidthAcrossStyles: PersistentList<String>,
    val tailHJustify: HJustify,
    val tailVShelve: AppendageVShelve,
    val tailVJustify: AppendageVJustify,
    val tailGapPx: Double,
    val tailLeader: String,
    val tailLeaderLetterStyleName: Opt<String>,
    val tailLeaderHJustify: SingleLineHJustify,
    val tailLeaderVJustify: AppendageVJustify,
    val tailLeaderMarginLeftPx: Double,
    val tailLeaderMarginRightPx: Double,
    val tailLeaderSpacingPx: Double
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
enum class SingleLineHJustify { LEFT, CENTER, RIGHT, FULL }
enum class LineHJustify { LEFT, CENTER, RIGHT, FULL_LAST_LEFT, FULL_LAST_CENTER, FULL_LAST_RIGHT, FULL_LAST_FULL }
enum class AppendageVShelve { FIRST, OVERALL_MIDDLE, LAST }
enum class AppendageVJustify { TOP, MIDDLE, BOTTOM, BASELINE }
enum class MatchExtent { OFF, WITHIN_BLOCK, ACROSS_BLOCKS }
enum class Sort { OFF, ASCENDING, DESCENDING }
enum class GridFillingOrder { L2R_T2B, R2L_T2B, T2B_L2R, T2B_R2L }
enum class GridStructure { FREE, EQUAL_WIDTH_COLS, SQUARE_CELLS }
enum class GridColUnderoccupancy { LEFT_OMIT, LEFT_RETAIN, RIGHT_OMIT, RIGHT_RETAIN }
enum class FlowDirection { L2R, R2L }


// Custom units:
//   - rh: Ratio of total height (w/ leading)
//   - rfh: Ratio of font height (w/o leading)


data class LetterStyle(
    override val name: String,
    val font: FontRef,
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


data class FontRef(val name: String) {

    var font: Font? = null
        private set

    constructor(font: Font) : this(font.getFontName(Locale.ROOT)) {
        this.font = font
    }

}


data class FontFeature(
    val tag: String,
    val value: Int
)


data class Layer(
    override val name: String,
    override val collapsed: Boolean,
    val coloring: LayerColoring,
    val color1: Color4f,
    val color2: Color4f,
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


data class PictureStyle(
    override val name: String,
    override val volatile: Boolean,
    val picture: PictureRef,
    val widthPx: Opt<Double>,
    val heightPx: Opt<Double>,
    val cropBlankSpace: Boolean
) : PopupStyle


data class PictureRef(val name: String) {

    var loader: Picture.Loader? = null
        private set

    constructor(loader: Picture.Loader) : this(loader.file.name) {
        this.loader = loader
    }

}


data class TapeStyle(
    override val name: String,
    override val volatile: Boolean,
    val tape: TapeRef,
    val widthPx: Opt<Int>,
    val heightPx: Opt<Int>,
    val slice: TapeSlice,
    val temporallyJustify: HJustify,
    val leftTemporalMarginFrames: Int,
    val rightTemporalMarginFrames: Int,
    val fadeInFrames: Int,
    val fadeOutFrames: Int
) : PopupStyle


data class TapeRef(val name: String) {

    var tape: Tape? = null
        private set

    constructor(tape: Tape) : this(tape.fileOrDir.name) {
        this.tape = tape
    }

}


data class TapeSlice(
    val inPoint: Opt<Timecode>,
    val outPoint: Opt<Timecode>
) {
    init {
        require(inPoint.value.javaClass == outPoint.value.javaClass)
    }
}


data class Opt<out E : Any /* non-null */>(val isActive: Boolean, val value: E)

inline fun <E : Any> Opt<E>.orElse(block: () -> E): E = if (isActive) value else block()


val Enum<*>.label: String
    get() = when (this) {
        TimecodeFormat.SMPTE_NON_DROP_FRAME -> "SMPTE Non-Drop Frame"
        TimecodeFormat.SMPTE_DROP_FRAME -> "SMPTE Drop Frame"
        SingleLineHJustify.LEFT, LineHJustify.LEFT -> HJustify.LEFT.label
        SingleLineHJustify.CENTER, LineHJustify.CENTER -> HJustify.CENTER.label
        SingleLineHJustify.RIGHT, LineHJustify.RIGHT -> HJustify.RIGHT.label
        AppendageVJustify.TOP -> VJustify.TOP.label
        AppendageVJustify.MIDDLE -> VJustify.MIDDLE.label
        AppendageVJustify.BOTTOM -> VJustify.BOTTOM.label
        GridStructure.SQUARE_CELLS -> l10n("ui.styling.content.flowSquareCells")
        Sort.OFF, SmallCaps.OFF, Superscript.OFF -> l10n("off")
        Superscript.CUSTOM, StripePreset.CUSTOM -> l10n("custom")
        else -> l10n("project.${javaClass.simpleName}.${name}")
    }
