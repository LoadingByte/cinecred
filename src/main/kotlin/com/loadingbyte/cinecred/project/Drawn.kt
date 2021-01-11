package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.DeferredImage


class DrawnPage(val defImage: DeferredImage, val stageInfo: List<DrawnStageInfo>)


sealed class DrawnStageInfo {
    class Card(val middleY: Float) : DrawnStageInfo()
    class Scroll(val scrollStartY: Float, val scrollStopY: Float) : DrawnStageInfo()
}
