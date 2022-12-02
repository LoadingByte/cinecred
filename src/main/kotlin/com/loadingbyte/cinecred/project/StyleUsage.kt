package com.loadingbyte.cinecred.project

import java.util.*


/** Returns a [Set] that compares elements based on identity for better performance. */
fun findUsedStyles(project: Project): Set<NamedStyle> {
    val usedStyles = Collections.newSetFromMap(IdentityHashMap<NamedStyle, Boolean>())
    val styling = project.styling

    // Add the letter styles referenced by the content styles.
    for (contentStyle in styling.contentStyles) {
        // Add the content style's body letter style.
        styling.letterStyles.find { it.name == contentStyle.bodyLetterStyleName }?.let(usedStyles::add)
        // If the content style supports heads, add its head letter style.
        if (contentStyle.hasHead)
            styling.letterStyles.find { it.name == contentStyle.headLetterStyleName }?.let(usedStyles::add)
        // If the content style supports heads, add its tail letter style.
        if (contentStyle.hasTail)
            styling.letterStyles.find { it.name == contentStyle.tailLetterStyleName }?.let(usedStyles::add)
    }

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
