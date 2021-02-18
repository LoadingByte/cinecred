package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.DeferredImage
import kotlinx.collections.immutable.ImmutableList


class DrawnPage(val defImage: DeferredImage, val stageInfo: ImmutableList<DrawnStageInfo>)


sealed class DrawnStageInfo {
    class Card(val middleY: Float) : DrawnStageInfo()
    class Scroll(val scrollStartY: Float, val scrollStopY: Float) : DrawnStageInfo()
}
