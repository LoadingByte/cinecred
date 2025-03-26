package com.loadingbyte.cinecred.project

import kotlinx.collections.immutable.PersistentList


// These classes are deliberately not data classes to improve the speed of equality and hash code operations,
// which is important as the classes are often used as map keys.


class Credits(
    val spreadsheetName: String,
    val pages: PersistentList<Page>,
    val runtimeGroups: PersistentList<RuntimeGroup>
)


class Page(
    val stages: PersistentList<Stage>,
    val gapAfterFrames: Int
)


class Stage(
    val style: PageStyle,
    val cardRuntimeFrames: Int,
    val compounds: PersistentList<Compound>,
    val vGapAfterPx: Double
)


sealed interface Compound {

    val hOffsetPx: Double
    val spines: PersistentList<Spine>

    class Card(
        val vAnchor: VAnchor,
        override val hOffsetPx: Double,
        val vOffsetPx: Double,
        override val spines: PersistentList<Spine>
    ) : Compound

    class Scroll(
        override val hOffsetPx: Double,
        override val spines: PersistentList<Spine>,
        val vGapAfterPx: Double
    ) : Compound

}


class Spine(
    val hookTo: Spine?,
    val hookVAnchor: VAnchor,
    val selfVAnchor: VAnchor,
    val hOffsetPx: Double,
    val vOffsetPx: Double,
    val blocks: PersistentList<Block>
)


enum class VAnchor { TOP, MIDDLE, BOTTOM }


class Block(
    val style: ContentStyle,
    val head: PersistentList<StyledString>?,
    val body: PersistentList<BodyElement>,
    val tail: PersistentList<StyledString>?,
    val vGapAfterPx: Double,
    val harmonizeHeadPartitionId: PartitionId,
    val harmonizeBodyPartitionId: PartitionId,
    val harmonizeTailPartitionId: PartitionId
)


typealias StyledString = List<Pair<String, LetterStyle>>
typealias PartitionId = Any


sealed interface BodyElement {
    class Nil(val sty: LetterStyle) : BodyElement
    class Str(val lines: PersistentList<StyledString>) : BodyElement
    class Pic(val sty: PictureStyle) : BodyElement
    class Tap(val sty: TapeStyle) : BodyElement
    object Mis : BodyElement
}


class RuntimeGroup(val stages: PersistentList<Stage>, val runtimeFrames: Int)
