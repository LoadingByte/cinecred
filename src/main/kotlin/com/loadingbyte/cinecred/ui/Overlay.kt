package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.common.Resolution
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.drawer.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.imaging.DeferredImage
import com.loadingbyte.cinecred.imaging.Picture
import com.loadingbyte.cinecred.ui.helper.*
import java.util.*
import javax.swing.Icon


sealed interface Overlay : DeferredImage.Layer, Comparable<Overlay> {

    val uuid: UUID
    val label: String
    val icon: Icon get() = icon(javaClass)

    fun draw(
        resolution: Resolution,
        stageInfo: List<DrawnStageInfo>,
        image: DeferredImage
    )

    companion object {

        val TYPES = listOf(SafeAreasOverlay::class.java) + ConfigurableOverlay.TYPES

        val BUNDLED = listOf(
            SafeAreasOverlay.TRADITIONAL,
            SafeAreasOverlay.MODERN,
            AspectRatioOverlay(UUID.randomUUID(), 16.0, 9.0),
            AspectRatioOverlay(UUID.randomUUID(), 4.0, 3.0)
        )

        fun icon(type: Class<out Overlay>): Icon = when (type) {
            SafeAreasOverlay::class.java -> OVERLAY_SAFE_AREAS_ICON
            AspectRatioOverlay::class.java -> OVERLAY_ASPECT_RATIO_ICON
            LinesOverlay::class.java -> OVERLAY_LINES_ICON
            ImageOverlay::class.java -> IMAGE_ICON
            else -> throw IllegalArgumentException()
        }

    }

}


sealed interface ConfigurableOverlay : Overlay {

    val typeName: String get() = typeName(javaClass)

    companion object {

        val TYPES = listOf(AspectRatioOverlay::class.java, LinesOverlay::class.java, ImageOverlay::class.java)

        fun typeName(type: Class<out ConfigurableOverlay>): String = when (type) {
            AspectRatioOverlay::class.java -> l10n("ui.overlays.type.aspectRatio")
            LinesOverlay::class.java -> l10n("ui.overlays.type.lines")
            ImageOverlay::class.java -> l10n("ui.overlays.type.image")
            else -> throw IllegalArgumentException()
        }

    }

}


class SafeAreasOverlay private constructor(
    private val actionSafe: Int, private val titleSafe: Int, private val l10nKey: String
) : Overlay {

    override val uuid: UUID = UUID.randomUUID()
    override val label get() = l10n(l10nKey, "$actionSafe/$titleSafe")

    override fun draw(
        resolution: Resolution,
        stageInfo: List<DrawnStageInfo>,
        image: DeferredImage
    ) {
        drawSafeAreasOverlay(resolution, stageInfo, image, this, OVERLAY_COLOR, actionSafe / 100.0, titleSafe / 100.0)
    }

    override fun compareTo(other: Overlay) = when (other) {
        is SafeAreasOverlay -> Overlay.BUNDLED.indexOf(this).compareTo(Overlay.BUNDLED.indexOf(other))
        else -> Overlay.TYPES.indexOf(javaClass).compareTo(Overlay.TYPES.indexOf(other.javaClass))
    }

    companion object {
        val TRADITIONAL = SafeAreasOverlay(90, 80, "ui.overlays.label.traditionalSafeAreas")
        val MODERN = SafeAreasOverlay(93, 90, "ui.overlays.label.modernSafeAreas")
    }

}


class AspectRatioOverlay(
    override val uuid: UUID,
    val h: Double,
    val v: Double
) : ConfigurableOverlay {

    override val label get() = l10n("ui.overlays.label.aspectRatio", "$h:$v")

    override fun draw(
        resolution: Resolution,
        stageInfo: List<DrawnStageInfo>,
        image: DeferredImage
    ) {
        drawAspectRatioOverlay(resolution, stageInfo, image, this, OVERLAY_COLOR, h / v)
    }

    override fun compareTo(other: Overlay) = when (other) {
        is AspectRatioOverlay -> (h / v).compareTo(other.h / other.v)
        else -> Overlay.TYPES.indexOf(javaClass).compareTo(Overlay.TYPES.indexOf(other.javaClass))
    }

}


class LinesOverlay(
    override val uuid: UUID,
    val name: String,
    val color: Color4f?,
    val hLines: List<Int>,
    val vLines: List<Int>
) :
    ConfigurableOverlay {

    override val label get() = name

    override fun draw(
        resolution: Resolution,
        stageInfo: List<DrawnStageInfo>,
        image: DeferredImage
    ) {
        drawLinesOverlay(resolution, stageInfo, image, this, color ?: OVERLAY_COLOR, hLines, vLines)
    }

    override fun compareTo(other: Overlay) = when (other) {
        is LinesOverlay -> name.compareTo(other.name)
        else -> Overlay.TYPES.indexOf(javaClass).compareTo(Overlay.TYPES.indexOf(other.javaClass))
    }

}


class ImageOverlay(
    override val uuid: UUID,
    val name: String,
    val raster: Picture.Raster,
    var rasterPersisted: Boolean,
    val underlay: Boolean
) : ConfigurableOverlay {

    override val label get() = name

    override fun draw(
        resolution: Resolution,
        stageInfo: List<DrawnStageInfo>,
        image: DeferredImage
    ) {
        drawPictureOverlay(resolution, stageInfo, image, this, raster)
    }

    override fun compareTo(other: Overlay) = when (other) {
        is ImageOverlay -> name.compareTo(other.name)
        else -> Overlay.TYPES.indexOf(javaClass).compareTo(Overlay.TYPES.indexOf(other.javaClass))
    }

}
