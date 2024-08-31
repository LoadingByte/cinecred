package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.FormattedString
import com.loadingbyte.cinecred.imaging.Y
import com.loadingbyte.cinecred.project.*
import java.util.*


val STAGE_GUIDE_COLOR = Color4f.fromSRGBHexString("#B646FA")
val SPINE_GUIDE_COLOR = Color4f.fromSRGBHexString("#00C8C8")
val BODY_CELL_GUIDE_COLOR = Color4f.fromSRGBHexString("#823200")
val BODY_WIDTH_GUIDE_COLOR = Color4f.fromSRGBHexString("#820000")
val HEAD_TAIL_GUIDE_COLOR = Color4f.fromSRGBHexString("#006400")


inline fun <E : Any> Opt<E>.orElse(block: () -> E) = if (isActive) value else block()


fun SingleLineHJustify.toHJustify() = when (this) {
    SingleLineHJustify.LEFT -> HJustify.LEFT
    SingleLineHJustify.CENTER, SingleLineHJustify.FULL -> HJustify.CENTER
    SingleLineHJustify.RIGHT -> HJustify.RIGHT
}


fun DeferredImage.drawString(
    fmtStr: FormattedString,
    x: Double, yBaseline: Y,
    layer: DeferredImage.Layer = DeferredImage.STATIC
) {
    fmtStr.drawTo(this, x, yBaseline, layer)
}


fun justify(hJustify: HJustify, areaWidth: Double, objWidth: Double): Double =
    when (hJustify) {
        HJustify.LEFT -> 0.0
        HJustify.CENTER -> (areaWidth - objWidth) / 2.0
        HJustify.RIGHT -> (areaWidth - objWidth)
    }

fun justify(vJustify: VJustify, areaHeight: Double, objHeight: Double): Double =
    when (vJustify) {
        VJustify.TOP -> 0.0
        VJustify.MIDDLE -> (areaHeight - objHeight) / 2.0
        VJustify.BOTTOM -> (areaHeight - objHeight)
    }


fun partitionToTransitiveClosures(
    contentStyles: List<ContentStyle>,
    getMemberStyleNames: (ContentStyle) -> List<String>,
    filter: ContentStyle.() -> Boolean
): Map<ContentStyle, PartitionId> {
    val filteredStyles = contentStyles.filter(filter)
    val partitioner = HashMap<String, Pair<Any, MutableList<ContentStyle>>>()
    for (style in filteredStyles) {
        val partition = partitioner.computeIfAbsent(style.name) { Pair(Any() /* unique partition ID */, ArrayList()) }
        partition.second.add(style)
        // For each referenced style that is actually available for partitioning...
        // There are some edge cases which would lead to unexpected behavior if we didn't have this check. For example,
        // consider two available styles referencing the same unavailable style. Even though these references are
        // invalid, they would lead to both styles ending up in the same partition.
        for (refStyleName in getMemberStyleNames(style))
            if (filteredStyles.any { it.name == refStyleName }) {
                val partition2 = partitioner[refStyleName]
                // If "partition2" doesn't exist yet, insert "partition" in its place.
                if (partition2 == null)
                    partitioner[refStyleName] = partition
                // If "partition" and "partition2" are not the same object, merge the contents of "partition2" into
                // "partition", and then get rid of "partition2" by again inserting "partition" in its place.
                else if (partition !== partition2) {
                    // This loop is not very efficient and just exists to ensure we always build the transitive closure
                    // of styles which reference each other, which is only necessary if the input styling for some
                    // reason is corrupt (it should already ensure the transitive closure invariant). We could make the
                    // line more efficient by using sets instead of lists for the partitions, but that would degrade the
                    // performance of the regular, non-bugged case.
                    for (sty in partition2.second)
                        if (sty !in partition.second)
                            partition.second.add(sty)
                    partitioner[refStyleName] = partition
                }
            }
    }
    return filteredStyles.associateWithTo(IdentityHashMap()) { style -> partitioner.getValue(style.name).first }
}
