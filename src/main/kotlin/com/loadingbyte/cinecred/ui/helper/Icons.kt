package com.loadingbyte.cinecred.ui.helper

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.icons.FlatAbstractIcon
import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.*
import org.apache.batik.anim.dom.SAXSVGDocumentFactory
import org.apache.batik.anim.dom.SVGOMDocument
import org.apache.batik.bridge.BridgeContext
import org.apache.batik.bridge.GVTBuilder
import org.apache.batik.bridge.UserAgentAdapter
import org.apache.batik.gvt.GraphicsNode
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.FilteredImageSource
import java.awt.image.ImageFilter
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.roundToInt


const val ICON_ICON_GAP = 4

val WINDOW_ICON_IMAGES = run {
    val (logo, ctx) = loadSVGResource("/logo.svg")
    listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { size ->
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).withG2 { g2 ->
            g2.setHighQuality()
            val scale = size / ctx.documentSize.width
            g2.transform(AffineTransform.getScaleInstance(scale, scale))
            logo.paint(g2)
        }
    }
}


val CINECRED_ICON = SVGIcon.load("/logo.svg").getScaledIcon(0.0625f)

val X_16_TO_9_ICON = SVGIcon.load("/icons/16to9.svg")
val X_4_TO_3_ICON = SVGIcon.load("/icons/4to3.svg")
val ADD_ICON = SVGIcon.load("/icons/add.svg")
val CANCEL_ICON = SVGIcon.load("/icons/cancel.svg")
val CROSS_ICON = SVGIcon.load("/icons/cross.svg")
val DELIVER_ICON = SVGIcon.load("/icons/deliver.svg")
val DUPLICATE_ICON = SVGIcon.load("/icons/duplicate.svg")
val EDIT_ICON = SVGIcon.load("/icons/edit.svg")
val ERROR_ICON = SVGIcon.load("/icons/error.svg")
val EYE_ICON = SVGIcon.load("/icons/eye.svg")
val FILMSTRIP_ICON = SVGIcon.load("/icons/filmstrip.svg")
val FOLDER_ICON = SVGIcon.load("/icons/folder.svg")
val GEAR_ICON = SVGIcon.load("/icons/gear.svg")
val GIFT_ICON = SVGIcon.load("/icons/gift.svg")
val GLOBE_ICON = SVGIcon.load("/icons/globe.svg")
val HOME_ICON = SVGIcon.load("/icons/home.svg")
val INFO_ICON = SVGIcon.load("/icons/info.svg")
val LAYOUT_ICON = SVGIcon.load("/icons/layout.svg")
val LETTERS_ICON = SVGIcon.load("/icons/letters.svg")
val PAGE_ICON = SVGIcon.load("/icons/page.svg")
val PAUSE_ICON = SVGIcon.load("/icons/pause.svg")
val PLAY_ICON = SVGIcon.load("/icons/play.svg")
val PREFERENCES_ICON = SVGIcon.load("/icons/preferences.svg")
val REDO_ICON = SVGIcon.load("/icons/redo.svg")
val REMOVE_ICON = SVGIcon.load("/icons/remove.svg")
val RESET_ICON = SVGIcon.load("/icons/reset.svg")
val SAVE_ICON = SVGIcon.load("/icons/save.svg")
val TRASH_ICON = SVGIcon.load("/icons/trash.svg")
val UNDO_ICON = SVGIcon.load("/icons/undo.svg")
val UNIFORM_SAFE_AREAS_ICON = SVGIcon.load("/icons/uniformSafeAreas.svg")
val UPDATE_ICON = SVGIcon.load("/icons/update.svg")
val WARN_ICON = SVGIcon.load("/icons/warn.svg")
val ZOOM_ICON = SVGIcon.load("/icons/zoom.svg")

val ARROW_LEFT_ICON = SVGIcon.load("/icons/arrow/left.svg")
val ARROW_RIGHT_ICON = SVGIcon.load("/icons/arrow/right.svg")
val ARROW_LEFT_RIGHT_ICON = SVGIcon.load("/icons/arrow/leftRight.svg")
val ARROW_TOP_BOTTOM_ICON = SVGIcon.load("/icons/arrow/topBottom.svg")

val BEARING_LEFT_ICON = SVGIcon.load("/icons/bearing/left.svg")
val BEARING_CENTER_ICON = SVGIcon.load("/icons/bearing/center.svg")
val BEARING_RIGHT_ICON = SVGIcon.load("/icons/bearing/right.svg")
val BEARING_TOP_ICON = SVGIcon.load("/icons/bearing/top.svg")
val BEARING_MIDDLE_ICON = SVGIcon.load("/icons/bearing/middle.svg")
val BEARING_BOTTOM_ICON = SVGIcon.load("/icons/bearing/bottom.svg")


val Severity.icon
    get() = when (this) {
        Severity.INFO -> INFO_ICON
        Severity.WARN -> WARN_ICON
        Severity.ERROR -> ERROR_ICON
    }


private val SPINE_ORIENTATION_HORIZONTAL_ICON = SVGIcon.load("/icons/spineOrientation/horizontal.svg")
private val SPINE_ORIENTATION_VERTICAL_ICON = SVGIcon.load("/icons/spineOrientation/vertical.svg")

val SpineOrientation.icon
    get() = when (this) {
        SpineOrientation.HORIZONTAL -> SPINE_ORIENTATION_HORIZONTAL_ICON
        SpineOrientation.VERTICAL -> SPINE_ORIENTATION_VERTICAL_ICON
    }


private val BODY_LAYOUT_GRID_ICON = SVGIcon.load("/icons/bodyLayout/grid.svg")
private val BODY_LAYOUT_FLOW_ICON = SVGIcon.load("/icons/bodyLayout/flow.svg")
private val BODY_LAYOUT_PARAGRAPHS_ICON = SVGIcon.load("/icons/bodyLayout/paragraphs.svg")

val BodyLayout.icon
    get() = when (this) {
        BodyLayout.GRID -> BODY_LAYOUT_GRID_ICON
        BodyLayout.FLOW -> BODY_LAYOUT_FLOW_ICON
        BodyLayout.PARAGRAPHS -> BODY_LAYOUT_PARAGRAPHS_ICON
    }


val HJustify.icon
    get() = when (this) {
        HJustify.LEFT -> BEARING_LEFT_ICON
        HJustify.CENTER -> BEARING_CENTER_ICON
        HJustify.RIGHT -> BEARING_RIGHT_ICON
    }


val VJustify.icon
    get() = when (this) {
        VJustify.TOP -> BEARING_TOP_ICON
        VJustify.MIDDLE -> BEARING_MIDDLE_ICON
        VJustify.BOTTOM -> BEARING_BOTTOM_ICON
    }


private val LINE_H_JUSTIFY_LEFT_ICON = SVGIcon.load("/icons/lineHJustify/left.svg")
private val LINE_H_JUSTIFY_CENTER_ICON = SVGIcon.load("/icons/lineHJustify/center.svg")
private val LINE_H_JUSTIFY_RIGHT_ICON = SVGIcon.load("/icons/lineHJustify/right.svg")
private val LINE_H_JUSTIFY_FULL_LAST_LEFT_ICON = SVGIcon.load("/icons/lineHJustify/fullLastLeft.svg")
private val LINE_H_JUSTIFY_FULL_LAST_CENTER_ICON = SVGIcon.load("/icons/lineHJustify/fullLastCenter.svg")
private val LINE_H_JUSTIFY_FULL_LAST_RIGHT_ICON = SVGIcon.load("/icons/lineHJustify/fullLastRight.svg")
private val LINE_H_JUSTIFY_FULL_LAST_FULL_ICON = SVGIcon.load("/icons/lineHJustify/fullLastFull.svg")

val LineHJustify.icon
    get() = when (this) {
        LineHJustify.LEFT -> LINE_H_JUSTIFY_LEFT_ICON
        LineHJustify.CENTER -> LINE_H_JUSTIFY_CENTER_ICON
        LineHJustify.RIGHT -> LINE_H_JUSTIFY_RIGHT_ICON
        LineHJustify.FULL_LAST_LEFT -> LINE_H_JUSTIFY_FULL_LAST_LEFT_ICON
        LineHJustify.FULL_LAST_CENTER -> LINE_H_JUSTIFY_FULL_LAST_CENTER_ICON
        LineHJustify.FULL_LAST_RIGHT -> LINE_H_JUSTIFY_FULL_LAST_RIGHT_ICON
        LineHJustify.FULL_LAST_FULL -> LINE_H_JUSTIFY_FULL_LAST_FULL_ICON
    }


private val BODY_ELEMENT_BOX_CONFORM_NOTHING_ICON = SVGIcon.load("/icons/bodyElementBoxConform/nothing.svg")
private val BODY_ELEMENT_BOX_CONFORM_WIDTH_ICON = SVGIcon.load("/icons/bodyElementBoxConform/width.svg")
private val BODY_ELEMENT_BOX_CONFORM_HEIGHT_ICON = SVGIcon.load("/icons/bodyElementBoxConform/height.svg")
private val BODY_ELEMENT_BOX_CONFORM_WAH_ICON = SVGIcon.load("/icons/bodyElementBoxConform/widthAndHeight.svg")
private val BODY_ELEMENT_BOX_CONFORM_SQUARE_ICON = SVGIcon.load("/icons/bodyElementBoxConform/square.svg")

val BodyElementBoxConform.icon
    get() = when (this) {
        BodyElementBoxConform.NOTHING -> BODY_ELEMENT_BOX_CONFORM_NOTHING_ICON
        BodyElementBoxConform.WIDTH -> BODY_ELEMENT_BOX_CONFORM_WIDTH_ICON
        BodyElementBoxConform.HEIGHT -> BODY_ELEMENT_BOX_CONFORM_HEIGHT_ICON
        BodyElementBoxConform.WIDTH_AND_HEIGHT -> BODY_ELEMENT_BOX_CONFORM_WAH_ICON
        BodyElementBoxConform.SQUARE -> BODY_ELEMENT_BOX_CONFORM_SQUARE_ICON
    }


private val GRID_FILLING_ORDER_L2R_T2B_ICON = SVGIcon.load("/icons/gridFillingOrder/l2r-t2b.svg")
private val GRID_FILLING_ORDER_R2L_T2B_ICON = SVGIcon.load("/icons/gridFillingOrder/r2l-t2b.svg")
private val GRID_FILLING_ORDER_T2B_L2R_ICON = SVGIcon.load("/icons/gridFillingOrder/t2b-l2r.svg")
private val GRID_FILLING_ORDER_T2B_R2L_ICON = SVGIcon.load("/icons/gridFillingOrder/t2b-r2l.svg")

val GridFillingOrder.icon
    get() = when (this) {
        GridFillingOrder.L2R_T2B -> GRID_FILLING_ORDER_L2R_T2B_ICON
        GridFillingOrder.R2L_T2B -> GRID_FILLING_ORDER_R2L_T2B_ICON
        GridFillingOrder.T2B_L2R -> GRID_FILLING_ORDER_T2B_L2R_ICON
        GridFillingOrder.T2B_R2L -> GRID_FILLING_ORDER_T2B_R2L_ICON
    }


val FlowDirection.icon
    get() = when (this) {
        FlowDirection.L2R -> ARROW_RIGHT_ICON
        FlowDirection.R2L -> ARROW_LEFT_ICON
    }


private val SMALL_CAPS_OFF = SVGIcon.load("/icons/smallCaps/off.svg")
private val SMALL_CAPS_SMALL_CAPS = SVGIcon.load("/icons/smallCaps/smallCaps.svg")
private val SMALL_CAPS_PETITE_CAPS = SVGIcon.load("/icons/smallCaps/petiteCaps.svg")

val SmallCaps.icon
    get() = when (this) {
        SmallCaps.OFF -> SMALL_CAPS_OFF
        SmallCaps.SMALL_CAPS -> SMALL_CAPS_SMALL_CAPS
        SmallCaps.PETITE_CAPS -> SMALL_CAPS_PETITE_CAPS
    }


private val SUPERSCRIPT_OFF = SVGIcon.load("/icons/superscript/off.svg")
private val SUPERSCRIPT_SUP = SVGIcon.load("/icons/superscript/sup.svg")
private val SUPERSCRIPT_SUB = SVGIcon.load("/icons/superscript/sub.svg")
private val SUPERSCRIPT_SUP_SUP = SVGIcon.load("/icons/superscript/sup_sup.svg")
private val SUPERSCRIPT_SUP_SUB = SVGIcon.load("/icons/superscript/sup_sub.svg")
private val SUPERSCRIPT_SUB_SUP = SVGIcon.load("/icons/superscript/sub_sup.svg")
private val SUPERSCRIPT_SUB_SUB = SVGIcon.load("/icons/superscript/sub_sub.svg")

val Superscript.icon
    get() = when (this) {
        Superscript.OFF -> SUPERSCRIPT_OFF
        Superscript.SUP -> SUPERSCRIPT_SUP
        Superscript.SUB -> SUPERSCRIPT_SUB
        Superscript.SUP_SUP -> SUPERSCRIPT_SUP_SUP
        Superscript.SUP_SUB -> SUPERSCRIPT_SUP_SUB
        Superscript.SUB_SUP -> SUPERSCRIPT_SUB_SUP
        Superscript.SUB_SUB -> SUPERSCRIPT_SUB_SUB
    }


private val TEXT_DECORATION_PRESET_UNDERLINE = SVGIcon.load("/icons/textDecorationPreset/underline.svg")
private val TEXT_DECORATION_PRESET_STRIKETHROUGH = SVGIcon.load("/icons/textDecorationPreset/strikethrough.svg")

val TextDecorationPreset.icon
    get() = when (this) {
        TextDecorationPreset.UNDERLINE -> TEXT_DECORATION_PRESET_UNDERLINE
        TextDecorationPreset.STRIKETHROUGH -> TEXT_DECORATION_PRESET_STRIKETHROUGH
        TextDecorationPreset.OFF -> GEAR_ICON
    }


val Enum<*>.icon
    get() = when (this) {
        is Severity -> icon
        is SpineOrientation -> icon
        is BodyLayout -> icon
        is HJustify -> icon
        is VJustify -> icon
        is LineHJustify -> icon
        is BodyElementBoxConform -> icon
        is GridFillingOrder -> icon
        is FlowDirection -> icon
        is SmallCaps -> icon
        is Superscript -> icon
        is TextDecorationPreset -> icon
        else -> throw IllegalArgumentException("No icons defined for enum class ${javaClass}.")
    }


private fun loadSVGResource(name: String): Pair<GraphicsNode, BridgeContext> {
    val doc = useResourceStream(name) {
        SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName()).createDocument(null, it) as SVGOMDocument
    }
    val ctx = BridgeContext(UserAgentAdapter())
    return Pair(GVTBuilder().build(ctx, doc), ctx)
}


class SVGIcon private constructor(
    private val svg: GraphicsNode,
    private val svgWidth: Float,
    private val svgHeight: Float,
    private val scaling: Float,
    private val isDisabled: Boolean
) : FlatAbstractIcon((svgWidth * scaling).roundToInt(), (svgHeight * scaling).roundToInt(), null),
    FlatLaf.DisabledIconProvider {

    override fun paintIcon(c: Component, g2: Graphics2D) {
        if (!isDisabled)
            if (scaling == 1f)
                svg.paint(g2)
            else
                g2.preserveTransform {
                    g2.scale(scaling)
                    svg.paint(g2)
                }
        else {
            // Note: Custom composites are not universally supported. Once they are, we can also use the gray filter
            // from inside a custom composite. For now, we first render the icon to an image, then apply the gray
            // filter to that image, and finally draw the filtered image.
            val filter = UIManager.get("Component.grayFilter") as ImageFilter
            // We assume that scaleX and scaleY are always identical.
            val g2Scaling = g2.transform.scaleX
            // Draw the icon to an image.
            val img = gCfg.createCompatibleImage(
                (svgWidth * scaling * g2Scaling).roundToInt(),
                (svgHeight * scaling * g2Scaling).roundToInt(),
                Transparency.TRANSLUCENT
            ).withG2 { g2i ->
                g2i.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2i.scale(scaling * g2Scaling, scaling * g2Scaling)
                svg.paint(g2i)
            }
            // Filter the image to make it gray.
            val grayImg = Toolkit.getDefaultToolkit().createImage(FilteredImageSource(img.source, filter))
            // Draw the image into the original graphics object.
            g2.preserveTransform {
                g2.scale(1.0 / g2Scaling, 1.0 / g2Scaling)
                g2.drawImage(grayImg, 0, 0, null)
            }
        }
    }

    override fun getDisabledIcon() = SVGIcon(svg, svgWidth, svgHeight, scaling, isDisabled = true)
    fun getScaledIcon(scaling: Float) = SVGIcon(svg, svgWidth, svgHeight, this.scaling * scaling, isDisabled)

    companion object {
        fun load(name: String): SVGIcon {
            val (svg, ctx) = loadSVGResource(name)
            return SVGIcon(svg, ctx.documentSize.width.toFloat(), ctx.documentSize.height.toFloat(), 1f, false)
        }
    }


    class Dual constructor(private val left: SVGIcon, private val right: SVGIcon) :
        FlatAbstractIcon(left.width + ICON_ICON_GAP + right.width, max(left.height, right.height), null),
        FlatLaf.DisabledIconProvider {

        override fun paintIcon(c: Component, g2: Graphics2D) {
            g2.preserveTransform {
                left.paintIcon(c, g2)
                g2.translate(left.width + ICON_ICON_GAP, 0)
                right.paintIcon(c, g2)
            }
        }

        override fun getDisabledIcon() = Dual(left.disabledIcon, right.disabledIcon)

    }

}
