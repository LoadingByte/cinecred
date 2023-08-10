package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.DeferredVideo
import com.loadingbyte.cinecred.imaging.Y
import kotlinx.collections.immutable.PersistentList


class DrawnProject(val project: Project, val drawnCredits: PersistentList<DrawnCredits>)
class DrawnCredits(val credits: Credits, val drawnPages: PersistentList<DrawnPage>, val video: DeferredVideo)
class DrawnPage(val page: Page, val defImage: DeferredImage, val stageInfo: PersistentList<DrawnStageInfo>)


sealed interface DrawnStageInfo {

    class Card(
        val middleY: Y
    ) : DrawnStageInfo

    class Scroll(
        val scrollStartY: Y,
        val scrollStopY: Y,
        val ownedScrollHeight: Y,
        val frames: Int,
        val initialAdvance: Double
    ) : DrawnStageInfo

}
