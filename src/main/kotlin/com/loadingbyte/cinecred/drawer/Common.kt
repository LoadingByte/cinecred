package com.loadingbyte.cinecred.drawer

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.FormattedString
import com.loadingbyte.cinecred.common.Y
import com.loadingbyte.cinecred.common.Y.Companion.toY
import com.loadingbyte.cinecred.project.ContentStyle
import com.loadingbyte.cinecred.project.HJustify
import com.loadingbyte.cinecred.project.PartitionId
import com.loadingbyte.cinecred.project.VJustify
import java.awt.Color
import java.util.*


val STAGE_GUIDE_COLOR = Color(182, 70, 250)
val SPINE_GUIDE_COLOR = Color(0, 200, 200)
val BODY_CELL_GUIDE_COLOR = Color(130, 50, 0)
val BODY_WIDTH_GUIDE_COLOR = Color(120, 0, 0)
val HEAD_TAIL_GUIDE_COLOR = Color(0, 100, 0)


inline fun DeferredImage.drawJustified(
    hJustify: HJustify,
    areaX: Float,
    areaWidth: Float,
    objWidth: Float,
    draw: DeferredImage.(Float) -> Unit
) {
    val objX = when (hJustify) {
        HJustify.LEFT -> areaX
        HJustify.CENTER -> areaX + (areaWidth - objWidth) / 2f
        HJustify.RIGHT -> areaX + (areaWidth - objWidth)
    }
    draw(objX)
}


inline fun DeferredImage.drawJustified(
    hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Y,
    areaWidth: Float, areaHeight: Y,
    objWidth: Float, objHeight: Y,
    draw: DeferredImage.(Float, Y) -> Unit
) {
    val objY = when (vJustify) {
        VJustify.TOP -> areaY
        VJustify.MIDDLE -> areaY + (areaHeight - objHeight) / 2f
        VJustify.BOTTOM -> areaY + (areaHeight - objHeight)
    }
    drawJustified(hJustify, areaX, areaWidth, objWidth) { objX -> draw(objX, objY) }
}


fun DeferredImage.drawJustifiedString(
    fmtStr: FormattedString, hJustify: HJustify,
    areaX: Float, strY: Y, areaWidth: Float
) {
    drawJustified(hJustify, areaX, areaWidth, fmtStr.width) { strX ->
        drawString(fmtStr, strX, strY)
    }
}


fun DeferredImage.drawJustifiedString(
    fmtStr: FormattedString, hJustify: HJustify, vJustify: VJustify,
    areaX: Float, areaY: Y, areaWidth: Float, areaHeight: Y,
    referenceHeight: Y? = null
) {
    val strHeight = fmtStr.height.toY()
    val diff = if (referenceHeight == null) 0f.toY() else referenceHeight - strHeight
    drawJustified(
        hJustify, vJustify, areaX, areaY + diff / 2f, areaWidth, areaHeight - diff,
        fmtStr.width, strHeight
    ) { strX, strY ->
        drawString(fmtStr, strX, strY)
    }
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
