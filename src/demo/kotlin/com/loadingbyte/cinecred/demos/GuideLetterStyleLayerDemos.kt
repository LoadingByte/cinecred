package com.loadingbyte.cinecred.demos

import com.loadingbyte.cinecred.common.getBundledFont
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.demo.*
import com.loadingbyte.cinecred.imaging.Color4f
import com.loadingbyte.cinecred.project.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.lang.Thread.sleep


private const val DIR = "guide/letter-style/layer"

val GUIDE_LETTER_STYLE_LAYER_DEMOS
    get() = listOf(
        GuideLetterStyleLayerHandlingDemo,
        GuideLetterStyleLayerInheritDemo,
        GuideLetterStyleLayerColoringDemo,
        GuideLetterStyleLayerShapeTextDemo,
        GuideLetterStyleLayerShapeStripeDemo,
        GuideLetterStyleLayerShapeCloneDemo,
        GuideLetterStyleLayerStripePresetDemo,
        GuideLetterStyleLayerStripeWidenDemo,
        GuideLetterStyleLayerStripeCornerDemo,
        GuideLetterStyleLayerStripeDashPatternDemo,
        GuideLetterStyleLayerDilationDemo,
        GuideLetterStyleLayerContourDemo,
        GuideLetterStyleLayerTransformDemo,
        GuideLetterStyleLayerAnchorDemo,
        GuideLetterStyleLayerAnchorCloneDemo,
        GuideLetterStyleLayerClearingDemo,
        GuideLetterStyleLayerBlurDemo
    )


@Suppress("DEPRECATION")
object GuideLetterStyleLayerHandlingDemo : ScreencastDemo("$DIR/handling", Format.VIDEO_GIF, 1100, 600) {
    override fun generate() {
        addProjectWindows(snapRatio = 0.33, styWinSplitRatio = 0.17)

        val oldStyling = projectCtrl.stylingHistory.current
        val oldStyle = oldStyling.letterStyles.first { it.name == l10n("project.template.letterStyleCardName") }
        val newStyle = oldStyle.copy(
            layers = persistentListOf(oldStyle.layers[0].copy(color1 = Color4f.fromSRGBHexString("#C85050")))
        )
        val newStyling = oldStyling.copy(
            letterStyles = oldStyling.letterStyles.map { if (it === oldStyle) newStyle else it }.toPersistentList(),
        )
        edt {
            prjPnl.leakedGuidesButton.isSelected = false
            prjImagePnl(0).apply { zoom = 2.0; leakedViewportCenterSetter(x = 1200.0) }
            projectCtrl.stylingHistory.loadAndRedraw(newStyling)
        }
        sleep(500)
        edt { styTree.selected = newStyle }
        sleep(500)
        edt { styLetrFormScrollBar.value = styLetrFormScrollBar.maximum }
        sleep(500)

        sc.hold(hold)
        sc.mouseTo(styWin.desktopPosOf(styLayrAddBtn(1)))
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(styLetrFormScrollBar))
        dt.mouseDown()
        sc.mouseTo(styWin.desktopPosOf(styLetrFormScrollBar).apply { y += 140 })
        dt.mouseUp()
        sc.hold(4 * hold)
        sc.demonstrateSetting(styWin, styLayrForm(1), Layer::stripePreset.st(), 2)
        sc.type(styWin, styLayrNameField(1), l10n("project.StripePreset.STRIKETHROUGH"), 4 * hold)
        sc.mouseTo(styWin.desktopPosOf(styLayrAdvancedBtn(1)))
        sc.click(4 * hold)
        sc.click()
        sc.mouseTo(styWin.desktopPosOf(styLayrGrip(1)))
        dt.mouseDownAndDrag()
        sc.hold()
        sc.mouseTo(styWin.desktopPosOf(styLayrAddBtn(0)), 2 * hold)
        dt.mouseUp()
        sc.hold(4 * hold)
        sc.mouseTo(styWin.desktopPosOf(styLayrDelBtn(0)))
        sc.click(4 * hold)
    }
}


object GuideLetterStyleLayerInheritDemo : StyleSettingsDemo<LetterStyle>(
    LetterStyle::class.java, "$DIR/inherit", Format.STEP_GIF,
    listOf(LetterStyle::inheritLayersFromStyle.st())
) {
    private val oneStyleName get() = l10nDemo("styleOne")

    override fun styles() = buildList<LetterStyle> {
        this += PRESET_LETTER_STYLE.copy(name = "Two")
        this += last().copy(inheritLayersFromStyle = Opt(true, oneStyleName))
    }

    override fun credits(style: LetterStyle) =
        "@Body\n{{Style $oneStyleName}}Lorem{{Style Two}} ipsum {{Style $oneStyleName}}dolor".parseCreditsLS(
            style,
            PRESET_LETTER_STYLE.copy(
                name = oneStyleName, layers = persistentListOf(
                    PRESET_LAYER.copy(
                        color1 = Color4f.fromSRGBHexString("#640000"), shape = LayerShape.STRIPE,
                        stripePreset = StripePreset.BACKGROUND,
                        stripeWidenLeftRfh = 0.1, stripeWidenRightRfh = 0.1, stripeWidenTopRfh = -0.3,
                        stripeWidenBottomRfh = -0.3, stripeCornerJoin = LineJoin.ROUND, vShearing = -0.3
                    ),
                    PRESET_LAYER.copy(color1 = Color4f.fromSRGBHexString("#FA785A"), shape = LayerShape.TEXT)
                )
            )
        )
}


object GuideLetterStyleLayerColoringDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/coloring", Format.STEP_GIF,
    listOf(
        Layer::coloring.st(), Layer::color1.st(), Layer::color2.st(),
        Layer::gradientAngleDeg.st(), Layer::gradientExtentRfh.st(), Layer::gradientShiftRfh.st()
    )
) {
    // The existence of this layer prevents warning that the demo layer is off but not used.
    private val dummyLayer = PRESET_LAYER.copy(
        coloring = LayerColoring.OFF, shape = LayerShape.CLONE, cloneLayers = persistentListOf(1)
    )

    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(coloring = LayerColoring.OFF, shape = LayerShape.TEXT)
        this += last().copy(coloring = LayerColoring.PLAIN)
        this += last().copy(color1 = Color4f.fromSRGBHexString("#DCC382"))
        this += last().copy(coloring = LayerColoring.GRADIENT, color2 = Color4f.fromSRGBHexString("#502D0D"))
        this += last().copy(gradientAngleDeg = 90.0)
        this += last().copy(gradientExtentRfh = 2.0)
        this += last().copy(gradientExtentRfh = 4.0)
        this += last().copy(gradientShiftRfh = 0.5)
        this += last().copy(gradientShiftRfh = 1.0)
        this += last().copy(gradientShiftRfh = 1.5)
    }

    override fun credits(style: Layer) = buildCredits(style, dummyLayer)
}


object GuideLetterStyleLayerShapeTextDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/shape-text", Format.PNG,
    listOf(Layer::shape.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT)
    }

    override fun credits(style: Layer) = buildCredits(style)
}


object GuideLetterStyleLayerShapeStripeDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/shape-stripe", Format.PNG,
    listOf(Layer::shape.st())
) {
    override fun styles() = buildList<Layer> {
        this += customStripeLayer
    }

    override fun credits(style: Layer) =
        buildCredits(PRESET_LAYER.copy(color1 = inactiveColor, shape = LayerShape.TEXT), style)
}


object GuideLetterStyleLayerShapeCloneDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/shape-clone", Format.PNG,
    listOf(Layer::shape.st(), Layer::cloneLayers.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(
            shape = LayerShape.CLONE, cloneLayers = persistentListOf(1, 2), hOffsetRfh = 1.0, vOffsetRfh = -0.5
        )
    }

    override fun credits(style: Layer) = buildCredits(
        PRESET_LAYER.copy(name = l10n("project.LayerShape.TEXT"), color1 = inactiveColor, shape = LayerShape.TEXT),
        customStripeLayer.copy(name = l10n("project.StripePreset.UNDERLINE"), color1 = inactiveColor),
        style
    )
}


object GuideLetterStyleLayerStripePresetDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/stripe-preset", Format.STEP_GIF,
    listOf(Layer::stripePreset.st(), Layer::stripeHeightRfh.st(), Layer::stripeOffsetRfh.st())
) {
    override fun styles() = buildList<Layer> {
        this += customStripeLayer.copy(stripePreset = StripePreset.BACKGROUND)
        this += last().copy(stripePreset = StripePreset.UNDERLINE)
        this += last().copy(stripePreset = StripePreset.STRIKETHROUGH)
        this += last().copy(stripePreset = StripePreset.CUSTOM)
    }

    override fun credits(style: Layer) =
        buildCredits(style, PRESET_LAYER.copy(color1 = inactiveColor, shape = LayerShape.TEXT))
}


object GuideLetterStyleLayerStripeWidenDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/stripe-widen", Format.STEP_GIF,
    listOf(
        Layer::stripeWidenLeftRfh.st(), Layer::stripeWidenRightRfh.st(),
        Layer::stripeWidenTopRfh.st(), Layer::stripeWidenBottomRfh.st()
    )
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.STRIPE, stripePreset = StripePreset.BACKGROUND)
        this += last().copy(stripeWidenLeftRfh = 8.0 / 32.0)
        this += last().copy(stripeWidenRightRfh = 8.0 / 32.0)
        this += last().copy(stripeWidenTopRfh = 8.0 / 32.0)
        this += last().copy(stripeWidenBottomRfh = 8.0 / 32.0)
    }

    override fun credits(style: Layer) =
        buildCredits(style, PRESET_LAYER.copy(color1 = inactiveColor, shape = LayerShape.TEXT))
}


object GuideLetterStyleLayerStripeCornerDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/stripe-corner", Format.STEP_GIF,
    listOf(Layer::stripeCornerJoin.st(), Layer::stripeCornerRadiusRfh.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(
            shape = LayerShape.STRIPE, stripePreset = StripePreset.BACKGROUND, stripeWidenLeftRfh = 8.0 / 32.0,
            stripeWidenRightRfh = 8.0 / 32.0, stripeCornerRadiusRfh = 8.0 / 32.0
        )
        this += last().copy(stripeCornerJoin = LineJoin.ROUND)
        this += last().copy(stripeCornerJoin = LineJoin.BEVEL)
        this += last().copy(stripeCornerRadiusRfh = 12.0 / 32.0)
    }

    override fun credits(style: Layer) =
        buildCredits(style, PRESET_LAYER.copy(color1 = inactiveColor, shape = LayerShape.TEXT))
}


object GuideLetterStyleLayerStripeDashPatternDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/stripe-dash-pattern", Format.SLOW_STEP_GIF,
    listOf(Layer::stripeDashPatternRfh.st())
) {
    override fun styles() = buildList<Layer> {
        this += customStripeLayer
        this += last().copy(stripeDashPatternRfh = persistentListOf(10.0 / 32.0))
        this += last().copy(stripeDashPatternRfh = last().stripeDashPatternRfh.add(20.0 / 32.0))
        this += last().copy(stripeDashPatternRfh = last().stripeDashPatternRfh.add(30.0 / 32.0))
    }

    override fun credits(style: Layer) =
        buildCredits(PRESET_LAYER.copy(color1 = inactiveColor, shape = LayerShape.TEXT), style)
}


object GuideLetterStyleLayerDilationDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/dilation", Format.STEP_GIF,
    listOf(Layer::dilationRfh.st(), Layer::dilationJoin.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT)
        this += last().copy(dilationRfh = 0.5 / 64.0)
        this += last().copy(dilationRfh = 1.0 / 64.0)
        this += last().copy(dilationRfh = 1.5 / 64.0)
        this += last().copy(dilationRfh = 2.0 / 64.0)
        this += last().copy(dilationJoin = LineJoin.ROUND)
    }

    override fun credits(style: Layer) = buildCredits(style, height = 64.0, tracking = 0.1)
}


object GuideLetterStyleLayerContourDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/contour", Format.STEP_GIF,
    listOf(Layer::contour.st(), Layer::contourThicknessRfh.st(), Layer::contourJoin.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT, contourThicknessRfh = 2.0 / 64.0)
        this += last().copy(contour = true)
        this += last().copy(contourThicknessRfh = 3.0 / 64.0)
        this += last().copy(contourJoin = LineJoin.ROUND)
    }

    override fun credits(style: Layer) = buildCredits(style, bold = true, height = 64.0, tracking = 0.05)
}


object GuideLetterStyleLayerTransformDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/transform", Format.STEP_GIF,
    listOf(
        Layer::offsetCoordinateSystem.st(),
        Layer::hOffsetRfh.st(), Layer::vOffsetRfh.st(), Layer::offsetAngleDeg.st(), Layer::offsetDistanceRfh.st(),
        Layer::hScaling.st(), Layer::vScaling.st(), Layer::hShearing.st(), Layer::vShearing.st()
    ), pageExtend = 40, pageGuides = true
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT)
        this += last().copy(hOffsetRfh = 32.0 / 32.0)
        this += last().copy(vOffsetRfh = -8.0 / 32.0)
        this += last().copy(
            offsetCoordinateSystem = CoordinateSystem.POLAR,
            offsetAngleDeg = 14.0,
            offsetDistanceRfh = 1.03
        )
        this += last().copy(hScaling = 1.125)
        this += last().copy(vScaling = 0.75)
        this += last().copy(hShearing = -0.3)
        this += last().copy(vShearing = -0.2)
    }

    override fun credits(style: Layer) = buildCredits(style)
}


object GuideLetterStyleLayerAnchorDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/anchor", Format.STEP_GIF,
    listOf(Layer::anchor.st()), pageGuides = true
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT, hShearing = -0.5)
        this += last().copy(anchor = LayerAnchor.GLOBAL)
    }

    override fun credits(style: Layer) =
        buildCredits(style, customThickerStripeLayer.copy(hShearing = style.hShearing, anchor = style.anchor))
}


object GuideLetterStyleLayerAnchorCloneDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/anchor-clone", Format.SLOW_STEP_GIF,
    listOf(Layer::anchor.st(), Layer::anchorSiblingLayer.st()), pageExtend = 40, pageGuides = true
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(
            shape = LayerShape.CLONE, cloneLayers = persistentListOf(1, 2),
            hOffsetRfh = 6.2, vOffsetRfh = -0.8, hShearing = -0.5, anchorSiblingLayer = 1
        )
        this += last().copy(anchor = LayerAnchor.SIBLING)
        this += last().copy(anchor = LayerAnchor.GLOBAL)
    }

    override fun credits(style: Layer) = buildCredits(
        PRESET_LAYER.copy(name = l10n("project.LayerShape.TEXT"), color1 = inactiveColor, shape = LayerShape.TEXT),
        customThickerStripeLayer.copy(color1 = inactiveColor),
        style
    )
}


object GuideLetterStyleLayerClearingDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/clearing", Format.STEP_GIF,
    listOf(Layer::clearingLayers.st(), Layer::clearingRfh.st(), Layer::clearingJoin.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(
            shape = LayerShape.STRIPE, stripePreset = StripePreset.CUSTOM,
            stripeHeightRfh = 4.0 / 32.0, stripeOffsetRfh = 4.0 / 32.0
        )
        this += last().copy(clearingLayers = persistentListOf(1))
        this += last().copy(clearingRfh = 1.0 / 32.0)
        this += last().copy(clearingRfh = 2.0 / 32.0)
        this += last().copy(clearingRfh = 3.0 / 32.0)
    }

    override fun credits(style: Layer) = buildCredits(
        PRESET_LAYER.copy(name = l10n("project.LayerShape.TEXT"), color1 = inactiveColor, shape = LayerShape.TEXT),
        style,
        neque = true
    )
}


object GuideLetterStyleLayerBlurDemo : StyleSettingsDemo<Layer>(
    Layer::class.java, "$DIR/blur", Format.STEP_GIF,
    listOf(Layer::blurRadiusRfh.st())
) {
    override fun styles() = buildList<Layer> {
        this += PRESET_LAYER.copy(shape = LayerShape.TEXT)
        this += last().copy(blurRadiusRfh = 2.0 / 32.0)
        this += last().copy(blurRadiusRfh = 4.0 / 32.0)
        this += last().copy(blurRadiusRfh = 8.0 / 32.0)
    }

    override fun credits(style: Layer) = buildCredits(style)
}


private val inactiveColor = Color4f.fromSRGBHexString("#646464")

private val customStripeLayer = PRESET_LAYER.copy(
    shape = LayerShape.STRIPE, stripePreset = StripePreset.CUSTOM,
    stripeHeightRfh = 2.0 / 32.0, stripeOffsetRfh = 5.0 / 32.0
)

private val customThickerStripeLayer = customStripeLayer.copy(
    stripeHeightRfh = 10.0 / 32.0, stripeOffsetRfh = 10.0 / 32.0
)

private fun buildCredits(
    vararg layers: Layer,
    neque: Boolean = false,
    bold: Boolean = false,
    height: Double = 32.0,
    tracking: Double = 0.0
): Pair<Global, Page> {
    val spreadsheet = "@Body\n{{Style Demo}}" + if (neque) "Neque porro quisquam" else "Lorem ipsum dolor"
    val letterStyle = PRESET_LETTER_STYLE.copy(
        name = "Demo",
        font = FontRef(getBundledFont(if (bold) "Archivo Narrow Bold" else "Archivo Narrow Regular")!!),
        heightPx = height,
        trackingEm = tracking,
        layers = layers.asList().toPersistentList()
    )
    return spreadsheet.parseCreditsLS(letterStyle)
}
