package com.loadingbyte.cinecred.projectio

import com.loadingbyte.cinecred.common.SuperscriptMetrics
import com.loadingbyte.cinecred.common.getSuperscriptMetrics
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.lineMetrics
import java.awt.Font
import kotlin.math.abs
import kotlin.math.pow


/**
 * When the styling file has been saved by an older version of the program and some aspects of the format have changed
 * since then, this function adjusts the TOML maps to reflect the new structure.
 */
fun migrateStyling(ctx: StylingReaderContext, rawStyling: RawStyling) {
    // 1.0.0 -> 1.1.0: Enum lists are now stored as string lists instead of space-delimited strings.
    for (contentStyle in rawStyling.contentStyles) {
        val gridElemHJustifyPerCol = contentStyle["gridElemHJustifyPerCol"]
        if (gridElemHJustifyPerCol is String)
            contentStyle["gridElemHJustifyPerCol"] = gridElemHJustifyPerCol.split(" ")
    }

    // 1.0.0 -> 1.1.0: Whether a content style has a head and/or tail is now stored explicitly.
    for (contentStyle in rawStyling.contentStyles) {
        if ("hasHead" !in contentStyle)
            contentStyle["hasHead"] = contentStyle.keys.any { it.startsWith("head") }
        if ("hasTail" !in contentStyle)
            contentStyle["hasTail"] = contentStyle.keys.any { it.startsWith("tail") }
    }

    // 1.1.0 -> 1.2.0: What has previously been called "background" is now called "grounding".
    rawStyling.global["background"]?.let { rawStyling.global["grounding"] = it }

    // 1.1.0 -> 1.2.0: "tracking" is renamed to "trackingEm".
    for (letterStyle in rawStyling.letterStyles)
        letterStyle["tracking"]?.let { letterStyle["trackingEm"] = it }

    // 1.1.0 -> 1.2.0: A custom text decoration system is replacing the simple underline & strikethrough checkboxes.
    for (letterStyle in rawStyling.letterStyles) {
        val ul = letterStyle["underline"] == true
        val st = letterStyle["strikethrough"] == true
        if (ul || st) letterStyle.compute("decorations") { _, oldDeco ->
            val deco = ArrayList(oldDeco as? List<*> ?: emptyList())
            if (ul)
                deco.add(mapOf("preset" to "UNDERLINE"))
            if (st)
                deco.add(mapOf("preset" to "STRIKETHROUGH"))
            deco
        }
    }

    // 1.1.0 -> 1.2.0: We now support multiple variants of small caps.
    for (letterStyle in rawStyling.letterStyles)
        if (letterStyle["smallCaps"] == true)
            letterStyle["smallCaps"] = "SMALL_CAPS"

    // 1.1.0 -> 1.2.0: Because we now support mixed super- and subscripts, the enum names have changed.
    for (letterStyle in rawStyling.letterStyles) {
        val ss = letterStyle["superscript"]
        if (ss is String && (ss.last() == '1' || ss.last() == '2'))
            letterStyle["superscript"] = when (ss) {
                "NONE" -> "OFF"
                "SUP_1" -> "SUP"
                "SUB_1" -> "SUB"
                "SUP_2" -> "SUP_SUP"
                "SUB_2" -> "SUB_SUB"
                else -> ss
            }
    }

    // 1.1.0 -> 1.2.0: The absence of a background is no longer just represented by an alpha value of 0.
    for (letterStyle in rawStyling.letterStyles)
        if (letterStyle["background"].let { it is String && it.length == 9 && it.startsWith("#00") })
            letterStyle.remove("background")

    // 1.2.0 -> 1.3.0: "spineOrientation" and "alignWithAxis" are renamed.
    for (contentStyle in rawStyling.contentStyles) {
        contentStyle["spineOrientation"]?.let { contentStyle["blockOrientation"] = it }
        contentStyle["alignWithAxis"]?.let { contentStyle["spineAttachment"] = it }
    }

    // 1.2.0 -> 1.3.0: Body elements and element boxes are renamed to cells.
    for (contentStyle in rawStyling.contentStyles) {
        contentStyle["gridElemHJustifyPerCol"]?.let { contentStyle["gridCellHJustifyPerCol"] = it }
        contentStyle["gridElemVJustify"]?.let { contentStyle["gridCellVJustify"] = it }
        contentStyle["flowElemHJustify"]?.let { contentStyle["flowCellHJustify"] = it }
        contentStyle["flowElemVJustify"]?.let { contentStyle["flowCellVJustify"] = it }
    }

    // 1.2.0 -> 1.3.0: The matching of body cell extents has been reworked.
    for (contentStyle in rawStyling.contentStyles) {
        val gridC = contentStyle["gridElemBoxConform"]
        val flowC = contentStyle["flowElemBoxConform"]
        if (gridC is String) {
            if (gridC == "SQUARE") contentStyle["gridStructure"] = "SQUARE_CELLS"
            if (gridC == "WIDTH" || gridC == "WIDTH_AND_HEIGHT") contentStyle["gridStructure"] = "EQUAL_WIDTH_COLS"
            if (gridC == "HEIGHT" || gridC == "WIDTH_AND_HEIGHT") contentStyle["gridMatchRowHeight"] = "WITHIN_BLOCK"
        }
        if (flowC is String) {
            if (flowC == "SQUARE") contentStyle["flowSquareCells"] = true
            if (flowC == "WIDTH" || flowC == "WIDTH_AND_HEIGHT") contentStyle["flowMatchCellWidth"] = "WITHIN_BLOCK"
            if (flowC == "HEIGHT" || flowC == "WIDTH_AND_HEIGHT") contentStyle["flowMatchCellHeight"] = "WITHIN_BLOCK"
        }
    }

    // 1.3.0 -> 1.3.1: The resolution is now stored as a single setting.
    rawStyling.global.let { g -> g["widthPx"]?.let { w -> g["heightPx"]?.let { h -> g["resolution"] = "${w}x$h" } } }

    // 1.3.1 -> 1.4.0: Letter style leading & offset now use rh/rfh; offset & scaling is now intended for superscripts.
    for (letterStyle in rawStyling.letterStyles) {
        val leadTopRemObj = letterStyle["leadingTopRem"]
        val leadBotRemObj = letterStyle["leadingBottomRem"]
        val hOffsetRemObj = letterStyle["hOffsetRem"]
        val vOffsetRemObj = letterStyle["vOffsetRem"]
        val scalingObj = letterStyle["scaling"]
        // If any of the above legacy settings are set, we need to convert them.
        if (leadTopRemObj == null || leadBotRemObj != null ||
            hOffsetRemObj != null || vOffsetRemObj != null ||
            scalingObj != null
        ) {
            // Parse the legacy settings them as numbers. Continue only if they diverge from the no-op values.
            val leadTopRem = (leadTopRemObj as? Number)?.toDouble() ?: 0.0
            val leadBotRem = (leadBotRemObj as? Number)?.toDouble() ?: 0.0
            val hOffsetRem = (hOffsetRemObj as? Number)?.toDouble() ?: 0.0
            val vOffsetRem = (vOffsetRemObj as? Number)?.toDouble() ?: 0.0
            val scaling = (scalingObj as? Number)?.toDouble() ?: 1.0
            if (leadTopRem != 0.0 || leadBotRem != 0.0 || hOffsetRem != 0.0 || vOffsetRem != 0.0 || scaling != 1.0) {
                // Retrieve the letter style's height and referenced AWT font, which we both need for the conversion.
                // Continue only if we can get a handle on both.
                val heightPx = (letterStyle["heightPx"] as? Number)?.toDouble() ?: continue
                val awtFont = (letterStyle["fontName"] as? String)?.let(ctx::resolveFont) ?: continue
                // Find the point size in the same way as version 1.3.1 used to do it.
                val rootPointSize = legacy131FindSize(awtFont, leadTopRem + leadBotRem, heightPx)
                // Use the point size to convert the leading from "rem" to "rh".
                val leadTopRh = leadTopRem * rootPointSize / heightPx
                val leadBotRh = leadBotRem * rootPointSize / heightPx
                letterStyle["leadingTopRh"] = leadTopRh
                letterStyle["leadingBottomRh"] = leadBotRh
                // If the offset & scaling settings diverge from the no-op values, convert those as well.
                if (hOffsetRem != 0.0 || vOffsetRem != 0.0 || scaling != 1.0) {
                    // In 1.4.0, offset & scaling can only be specified in the "CUSTOM" superscript mode. As such,
                    // if the superscript mode differs from "OFF", we need to integrate the automatic superscript
                    // offset & scaling into the user-provided values. For that, retrieve the automatic values in
                    // the same way as version 1.3.1 used to do it.
                    val ss = letterStyle["superscript"] as? String
                    val (ssScaling, ssHOffset, ssVOffset) = legacy131FindSuperscriptOffsetAndScaling(awtFont, ss)
                    // Use all gathered information to convert the offset & scaling from "rem" to "rfh".
                    val fontHeightPx = heightPx * (1.0 - leadTopRh - leadBotRh)
                    letterStyle["superscript"] = "CUSTOM"
                    letterStyle["superscriptScaling"] = scaling * ssScaling
                    letterStyle["superscriptHOffsetRfh"] = (hOffsetRem + ssHOffset) * rootPointSize / fontHeightPx
                    letterStyle["superscriptVOffsetRfh"] = (vOffsetRem + ssVOffset) * rootPointSize / fontHeightPx
                }
            }
        }
    }

    // 1.3.1 -> 1.4.0: Letter styles are now composed of layers.
    for (letterStyle in rawStyling.letterStyles) {
        val foreground = letterStyle["foreground"]
        val hShearing = letterStyle["hShearing"]
        val decorations = letterStyle["decorations"]
        val background = letterStyle["background"]
        // If any of the above legacy settings are set and layers defined yet, we need to convert them.
        if ((foreground != null || hShearing != null || decorations != null || background != null) &&
            "layers" !in letterStyle
        ) {
            // Find the font height, which is needed to convert from "px" to "rfh".
            val heightPx = (letterStyle["heightPx"] as? Number)?.toDouble() ?: continue
            val leadTopRh = (letterStyle["leadingTopRh"] as? Number)?.toDouble() ?: 0.0
            val leadBotRh = (letterStyle["leadingBottomRh"] as? Number)?.toDouble() ?: 0.0
            val fontHeightPx = heightPx * (1.0 - leadTopRh - leadBotRh)

            // Create layers for each decoration, and partition them into overlays and underlays.
            // In case a layer clears around the text, don't add the reference ordinal yet.
            val decoLayersUnderlay = mutableListOf<MutableMap<String, Any>>()
            val decoLayersOverlay = mutableListOf<MutableMap<String, Any>>()
            if (decorations is List<*>)
                for (deco in decorations)
                    if (deco is Map<*, *>) {
                        val layer = mutableMapOf<String, Any>("shape" to "STRIPE")
                        layer["name"] = when (val preset = deco["preset"]) {
                            "UNDERLINE", "STRIKETHROUGH" -> l10n("project.StripePreset.$preset")
                            else -> l10n("project.LayerShape.STRIPE")
                        }
                        (deco["color"] ?: foreground)?.let { layer["color1"] = it }
                        deco["preset"]?.let { layer["stripePreset"] = if (it == "OFF") "CUSTOM" else it }
                        deco["offsetPx"].letIfNumber { layer["stripeOffsetRfh"] = it / fontHeightPx }
                        deco["thicknessPx"].letIfNumber { layer["stripeHeightRfh"] = it / fontHeightPx }
                        deco["widenLeftPx"].letIfNumber { layer["stripeWidenLeftRfh"] = it / fontHeightPx }
                        deco["widenRightPx"].letIfNumber { layer["stripeWidenRightRfh"] = it / fontHeightPx }
                        // In 1.3.1, if clearing is disabled, the layer is an overlay, otherwise it is an underlay.
                        // Also, morphologic clearing is only applied if the clearing radius is larger than 0.
                        val clearing = deco["clearingPx"]
                        clearing.letIfNumber { if (it != 0.0) layer["clearingRfh"] = it / fontHeightPx }
                        deco["clearingJoin"]?.let { layer["clearingJoin"] = it }
                        (if (clearing == null) decoLayersOverlay else decoLayersUnderlay).add(layer)
                    }

            // Create a layer for the background if that's configures.
            val bgLayer = if (background == null) null else {
                val layer = mutableMapOf(
                    "name" to l10n("project.StripePreset.BACKGROUND"),
                    "color1" to background,
                    "shape" to "STRIPE",
                    "stripePreset" to "BACKGROUND"
                )
                letterStyle["backgroundWidenLeftPx"].letIfNumber { layer["stripeWidenLeftRfh"] = it / fontHeightPx }
                letterStyle["backgroundWidenRightPx"].letIfNumber { layer["stripeWidenRightRfh"] = it / fontHeightPx }
                // In 1.3.1, the background included the custom leading, so include it in the converted bg as well.
                letterStyle["backgroundWidenTopPx"]
                    .letIfNumber { layer["stripeWidenTopRfh"] = (it + leadTopRh * heightPx) / fontHeightPx }
                letterStyle["backgroundWidenBottomPx"]
                    .letIfNumber { layer["stripeWidenBottomRfh"] = (it + leadBotRh * heightPx) / fontHeightPx }
                layer
            }

            // Now we know how many layers will be below the text layer, and hence can amend the clearing ordinal.
            val textLayerOrdinal = (if (bgLayer == null) 0 else 1) + decoLayersUnderlay.size + 1
            for (decoLayer in decoLayersUnderlay + decoLayersOverlay)
                if ("clearingRfh" in decoLayer)
                    decoLayer["clearingLayers"] = listOf(textLayerOrdinal)

            // Create a layer for the main text.
            val textLayer = mutableMapOf<String, Any>(
                "name" to l10n("project.LayerShape.TEXT"),
                "shape" to "TEXT"
            )
            foreground?.let { textLayer["color1"] = it }
            hShearing?.let { textLayer["hShearing"] = it }

            // Collect all layers in the final layer list.
            letterStyle["layers"] = buildList {
                bgLayer?.let(::add)
                addAll(decoLayersUnderlay)
                add(textLayer)
                addAll(decoLayersOverlay)
            }
        }
    }

    // 1.4.1 -> 1.5.0: Vertical head/tail justification is now more fine-grained.
    for (contentStyle in rawStyling.contentStyles) {
        fun patch(vJustify: String) = when (vJustify) {
            "TOP" -> "FIRST_MIDDLE"
            "BOTTOM" -> "LAST_MIDDLE"
            else -> vJustify
        }
        (contentStyle["headVJustify"] as? String)?.let { contentStyle["headVJustify"] = patch(it) }
        (contentStyle["tailVJustify"] as? String)?.let { contentStyle["tailVJustify"] = patch(it) }
    }

    // 1.5.1 -> 1.6.0: "afterwardSlugFrames" is renamed to "subsequentGapFrames".
    for (pageStyle in rawStyling.pageStyles)
        pageStyle["afterwardSlugFrames"]?.let { pageStyle["subsequentGapFrames"] = it }

    // 1.5.1 -> 1.6.0: The card runtime setting now includes the fade-in/out time.
    for (pageStyle in rawStyling.pageStyles) {
        var cardRuntimeFrames = (pageStyle["cardDurationFrames"] as? Number ?: continue).toInt()
        if (pageStyle["scrollMeltWithPrev"] != true)
            (pageStyle["cardFadeInFrames"] as? Number)?.let { cardRuntimeFrames += it.toInt() }
        if (pageStyle["scrollMeltWithNext"] != true)
            (pageStyle["cardFadeOutFrames"] as? Number)?.let { cardRuntimeFrames += it.toInt() }
        pageStyle["cardRuntimeFrames"] = cardRuntimeFrames
    }

    // 1.5.1 -> 1.6.0: Colors are now stored as four floats in the XYZD50 color space.
    rawStyling.global.let { g -> sRGBHex32ToXYZD50FloatList(g["grounding"] as? String)?.let { g["grounding"] = it } }
    for (letterStyle in rawStyling.letterStyles)
        (letterStyle["layers"] as? List<*>)?.forEach { layer ->
            if (layer is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                layer as MutableMap<String, Any>
                sRGBHex32ToXYZD50FloatList(layer["color1"] as? String)?.let { layer["color1"] = it }
                sRGBHex32ToXYZD50FloatList(layer["color2"] as? String)?.let { layer["color2"] = it }
            }
        }

    // 1.6.0 -> 1.7.0: Head/tail vertical justification is now stored as two settings.
    for (contentStyle in rawStyling.contentStyles)
        for (prefix in arrayOf("head", "tail")) {
            val vJustify = contentStyle[prefix + "VJustify"]
            contentStyle[prefix + "VShelve"] = when (vJustify) {
                "FIRST_TOP", "FIRST_MIDDLE", "FIRST_BOTTOM" -> "FIRST"
                "OVERALL_MIDDLE" -> "OVERALL_MIDDLE"
                "LAST_TOP", "LAST_MIDDLE", "LAST_BOTTOM" -> "LAST"
                else -> continue
            }
            contentStyle[prefix + "VJustify"] = (vJustify as String).substringAfter('_')
        }

    // 1.6.0 -> 1.7.0: Vertical flow separator justification now has its own setting.
    for (contentStyle in rawStyling.contentStyles)
        if ("flowSeparatorVJustify" !in contentStyle)
            contentStyle["flowCellVJustify"]?.let { contentStyle["flowSeparatorVJustify"] = it }

    // 1.6.0 -> 1.7.0: "fontName" is renamed to "font".
    for (letterStyle in rawStyling.letterStyles)
        letterStyle["fontName"]?.let { letterStyle["font"] = it }

    // 1.7.0 -> 1.8.0: The number of grid columns is now controlled by a dedicated setting.
    for (contentStyle in rawStyling.contentStyles)
        (contentStyle["gridCellHJustifyPerCol"] as? List<*>)?.let { contentStyle.putIfAbsent("gridCols", it.size) }

    // 1.7.0 -> 1.8.0: The "*Match*" settings are renamed to "*Harmonize*".
    for (contentSty in rawStyling.contentStyles) {
        contentSty["gridMatchColWidths"]?.let { contentSty["gridHarmonizeColWidths"] = it }
        contentSty["gridMatchColWidthsAcrossStyles"]?.let { contentSty["gridHarmonizeColWidthsAcrossStyles"] = it }
        contentSty["gridMatchColUnderoccupancy"]?.let { contentSty["gridHarmonizeColUnderoccupancy"] = it }
        contentSty["gridMatchRowHeight"]?.let { contentSty["gridHarmonizeRowHeight"] = it }
        contentSty["gridMatchRowHeightAcrossStyles"]?.let { contentSty["gridHarmonizeRowHeightAcrossStyles"] = it }
        contentSty["flowMatchCellWidth"]?.let { contentSty["flowHarmonizeCellWidth"] = it }
        contentSty["flowMatchCellWidthAcrossStyles"]?.let { contentSty["flowHarmonizeCellWidthAcrossStyles"] = it }
        contentSty["flowMatchCellHeight"]?.let { contentSty["flowHarmonizeCellHeight"] = it }
        contentSty["flowMatchCellHeightAcrossStyles"]?.let { contentSty["flowHarmonizeCellHeightAcrossStyles"] = it }
        contentSty["headMatchWidth"]?.let { contentSty["headHarmonizeWidth"] = it }
        contentSty["headMatchWidthAcrossStyles"]?.let { contentSty["headHarmonizeWidthAcrossStyles"] = it }
        contentSty["tailMatchWidth"]?.let { contentSty["tailHarmonizeWidth"] = it }
        contentSty["tailMatchWidthAcrossStyles"]?.let { contentSty["tailHarmonizeWidthAcrossStyles"] = it }
    }
}


private inline fun Any?.letIfNumber(block: (Double) -> Unit) {
    if (this is Number) block(toDouble())
}


/** Legacy 1.3.1 implementation of `findSize()` in `FormattedString`. */
private fun legacy131FindSize(awtFont: Font, extraLeadingEm: Double, targetHeightPx: Double): Float {
    // Step 1: Exponential search to determine the rough range of the font size we're looking for.
    var size = 2f
    // Upper-bound the number of repetitions to avoid:
    //   - Accidental infinite looping.
    //   - Too large fonts, as they cause the Java font rendering engine to destroy its own fonts.
    for (i in 0..<10) {
        val height = awtFont.deriveFont(size * 2f).lineMetrics.height + extraLeadingEm * (size * 2f)
        if (height >= targetHeightPx)
            break
        size *= 2f
    }

    // Step 2: Binary search to find the exact font size.
    // If $size is still 2, we look for a size between 0 and 4.
    // Otherwise, we look for a size between $size and $size*2.
    val minSize = if (size == 2f) 0f else size
    val maxSize = size * 2f
    var intervalLength = (maxSize - minSize) / 2f
    size = minSize + intervalLength
    // Upper-bound the number of repetitions to avoid accidental infinite looping.
    for (i in 0..<20) {
        intervalLength /= 2f
        val height = awtFont.deriveFont(size).lineMetrics.height + extraLeadingEm * size
        when {
            abs(height - targetHeightPx) < 0.001 -> break
            height > targetHeightPx -> size -= intervalLength
            height < targetHeightPx -> size += intervalLength
        }
    }

    return size
}


/** Legacy 1.3.1 implementation of the superscript routine in `StyledStringFormatter`. */
private fun legacy131FindSuperscriptOffsetAndScaling(awtFont: Font, superscript: String?): Legacy131SSOffsetAndScaling {
    var ssScaling = 1.0
    var ssHOffset = 0.0
    var ssVOffset = 0.0

    val ssMetrics = awtFont.getSuperscriptMetrics()
        ?: SuperscriptMetrics(2 / 3.0, 0.0, 0.375, 2 / 3.0, 0.0, -0.375)

    fun sup() {
        ssHOffset += ssMetrics.supHOffsetEm * ssScaling
        ssVOffset += ssMetrics.supVOffsetEm * ssScaling
        ssScaling *= ssMetrics.supScaling
    }

    fun sub() {
        ssHOffset += ssMetrics.subHOffsetEm * ssScaling
        ssVOffset += ssMetrics.subVOffsetEm * ssScaling
        ssScaling *= ssMetrics.subScaling
    }

    // @formatter:off
    when (superscript) {
        "SUP" -> sup()
        "SUB" -> sub()
        "SUP_SUP" -> { sup(); sup() }
        "SUP_SUB" -> { sup(); sub() }
        "SUB_SUP" -> { sub(); sup() }
        "SUB_SUB" -> { sub(); sub() }
    }
    // @formatter:on

    return Legacy131SSOffsetAndScaling(ssScaling, ssHOffset, ssVOffset)
}

private data class Legacy131SSOffsetAndScaling(val ssScaling: Double, val ssHOffset: Double, val ssVOffset: Double)


private fun sRGBHex32ToXYZD50FloatList(hex: String?): List<Float>? {
    if (hex == null || hex.length.let { it != 7 && it != 9 } || hex[0] != '#')
        return null
    // Note: We first have to convert to long and then to int because String.toInt() throws an exception when an
    // overflowing number is decoded (which happens whenever alpha > 128, since the first bit of the color number is
    // then 1, which is interpreted as a negative sign, so this is an overflow).
    val argb = (hex.substring(1).toLongOrNull(16) ?: return null).toInt()
    val a = if (hex.length == 7) 1f else ((argb shr 24) and 0xFF) / 255f
    val r = sRGB_EOTF(((argb shr 16) and 0xFF) / 255f)
    val g = sRGB_EOTF(((argb shr 8) and 0xFF) / 255f)
    val b = sRGB_EOTF((argb and 0xFF) / 255f)
    return listOf(
        0.43606567f * r + 0.3851471f * g + 0.1430664f * b,
        0.2224884f * r + 0.71687317f * g + 0.06060791f * b,
        0.013916016f * r + 0.097076416f * g + 0.71409607f * b,
        a
    )
}

// Pretty much copied from zimg.
private fun sRGB_EOTF(x: Float) =
    if (x < 12.92f * 0.0030412825f)
        x / 12.92f
    else
        ((x + (1.0550107f - 1.0f)) / 1.0550107f).pow(2.4f)
