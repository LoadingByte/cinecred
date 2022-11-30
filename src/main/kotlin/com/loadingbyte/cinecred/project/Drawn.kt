package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.DeferredImage
import com.loadingbyte.cinecred.common.Y
import kotlinx.collections.immutable.PersistentList


class DrawnPage(val defImage: DeferredImage, val stageInfo: PersistentList<DrawnStageInfo>)


sealed class DrawnStageInfo {
    class Card(val middleY: Y) : DrawnStageInfo()
    class Scroll(val scrollStartY: Y, val scrollStopY: Y, val frames: Int, val initialAdvance: Float) : DrawnStageInfo()
}
