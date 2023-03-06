package com.loadingbyte.cinecred.project

import java.util.*


/** Returns a [Set] that compares elements based on identity for better performance. */
fun findUsedStyles(project: Project): Set<ListedStyle> {
    val usedStyles = Collections.newSetFromMap(IdentityHashMap<ListedStyle, Boolean>())
    val ctx = project.stylingCtx
    val styling = project.styling

    fun <S : Style> processStyle(style: S) {
        val ignoreSettings = findIneffectiveSettings(ctx, styling, style)
        for (cst in getStyleConstraints(style.javaClass))
            if (cst is StyleNameConstr<S, *> && !cst.clustering)
                for (setting in cst.settings)
                    if (setting !in ignoreSettings)
                        for (ref in setting.extractSubjects(style))
                            cst.choices(ctx, styling, style).find { choice -> choice.name == ref }?.let(usedStyles::add)
    }

    // Add styles referenced from other styles.
    // Note: Currently, we only look in ListedStyles, but as of now, that is sufficient for our use case.
    for (styleClass in ListedStyle.CLASSES)
        for (style in styling.getListedStyles(styleClass))
            processStyle(style)

    // Add the page, content, and letter styles referenced from the read pages.
    for (page in project.pages)
        for (stage in page.stages) {
            // Add the stage's page style.
            usedStyles.add(stage.style)
            for (segment in stage.segments)
                for (spine in segment.spines)
                    for (block in spine.blocks) {
                        // Add the block's content style.
                        usedStyles.add(block.style)
                        // Add the head's letter styles.
                        for ((_, letterStyle) in block.head.orEmpty())
                            usedStyles.add(letterStyle)
                        // Add the tail's letter styles.
                        for ((_, letterStyle) in block.tail.orEmpty())
                            usedStyles.add(letterStyle)
                        // Add the body's letter styles.
                        for (bodyElem in block.body)
                            when (bodyElem) {
                                is BodyElement.Nil -> usedStyles.add(bodyElem.sty)
                                is BodyElement.Str -> for ((_, letSty) in bodyElem.str) usedStyles.add(letSty)
                                is BodyElement.Pic -> {}
                            }
                    }
        }

    return usedStyles
}
