package com.loadingbyte.cinecred.projectio


/**
 * When the styling file has been saved by an older version of the program and some aspects of the format have changed
 * since then, this function adjusts the TOML maps to reflect the new structure.
 */
fun migrateStyling(rawStyling: RawStyling) {
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
}
