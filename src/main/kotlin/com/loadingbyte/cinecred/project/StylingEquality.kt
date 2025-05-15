package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.removeFirstOrNull


fun Styling.equalsIgnoreStyleOrderAndIneffectiveSettings(other: Styling): Boolean =
    global.equalsIgnoreIneffectiveSettings(this, other.global) &&
            ListedStyle.CLASSES.all { cls ->
                getListedStyles(cls).equalsIgnoreStyleOrderAndIneffectiveSettings(this, other.getListedStyles(cls))
            }


private fun <S : ListedStyle> List<S>.equalsIgnoreStyleOrderAndIneffectiveSettings(
    styling: Styling,
    other: List<S>
): Boolean {
    val styles1 = toMutableList()
    val styles2 = other.toMutableList()

    // Ignore styles that have been added automatically and were not edited since.
    if ((styles1.firstOrNull() ?: styles2.firstOrNull()) is PopupStyle) {
        styles1.removeIf { s -> (s as PopupStyle).volatile }
        styles2.removeIf { s -> (s as PopupStyle).volatile }
    }

    // Ensure that both lists have the same number of styles. This condition will be assumed by the following code.
    if (styles1.size != styles2.size)
        return false

    // Sort both lists of styles by style name.
    val comp = compareBy(ListedStyle::name)
    styles1.sortWith(comp)
    styles2.sortWith(comp)

    // Ensure that both lists contain the same style names with the same multiplicities. This condition will be assumed
    // by the following duplicate-related code.
    for (idx in styles1.indices)
        if (styles1[idx].name != styles2[idx].name)
            return false

    // Iterate through all pairs of styles...
    var idx = 0
    while (idx < styles1.size) {
        if (idx < styles1.lastIndex && styles1[idx].name == styles1[idx + 1].name) {
            // If we're at the first style of a series of styles with the same name, run a duplicate-aware procedure.
            // First determine the amount of styles which use the same name. Collect all the same-name styles from
            // styles2 in a mutable list. Then for each same-name style from styles1, try to find and remove the
            // equivalent same-name style from styles2. If at one point no match is available anymore, the two style
            // lists are not equal.
            val dupName = styles1[idx].name
            val endIdx = styles1.endOfRange(startIdx = idx) { it.name == dupName }
            val dupStyles2 = styles2.subList(idx, endIdx).toMutableList()
            while (idx < endIdx) {
                val s1 = styles1[idx]
                if (dupStyles2.removeFirstOrNull { s2 -> s1.equalsIgnoreIneffectiveSettings(styling, s2) } == null)
                    return false
                idx++
            }
        } else {
            // In the regular case of non-duplicate names, check each pair of styles individually.
            if (!styles1[idx].equalsIgnoreIneffectiveSettings(styling, styles2[idx]))
                return false
            idx++
        }
    }

    return true
}

private inline fun <E> List<E>.endOfRange(startIdx: Int, predicate: (E) -> Boolean): Int {
    for (idx in startIdx..lastIndex)
        if (!predicate(get(idx)))
            return idx
    return size
}


fun Style.equalsIgnoreIneffectiveSettings(styling: Styling, other: Style): Boolean {
    fun eq(v1: Any, v2: Any): Boolean =
        when {
            v1 is Style && v2 is Style -> v1.equalsIgnoreIneffectiveSettings(styling, v2)
            v1 is Opt<*> && v2 is Opt<*> -> v1.isActive == v2.isActive && (!v1.isActive || eq(v1.value, v2.value))
            v1 is TapeSlice && v2 is TapeSlice -> eq(v1.inPoint, v2.inPoint) && eq(v1.outPoint, v2.outPoint)
            else -> v1 == v2
        }

    if (javaClass != other.javaClass)
        return false
    val excludedSettings = findIneffectiveSettings(styling, this)
    if (excludedSettings.keys != findIneffectiveSettings(styling, other).keys)
        return false
    for (setting in getStyleSettings(javaClass as Class<Style>))
        if (setting !in excludedSettings)
            when (setting) {
                is DirectStyleSetting, is OptStyleSetting ->
                    if (!eq(setting.get(this), setting.get(other)))
                        return false
                is ListStyleSetting -> {
                    val vs1 = setting.get(this)
                    val vs2 = setting.get(other)
                    if (vs1.size != vs2.size)
                        return false
                    for (idx in vs1.indices)
                        if (!eq(vs1[idx], vs2[idx]))
                            return false
                }
            }
    return true
}
