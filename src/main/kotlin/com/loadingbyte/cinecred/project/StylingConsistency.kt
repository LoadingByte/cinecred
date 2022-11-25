package com.loadingbyte.cinecred.project

import kotlinx.collections.immutable.toImmutableList
import java.util.*


/**
 * This class generates updates to the styling to ensure that user changes like renaming a style are reflected in the
 * other styles as expected. Currently, the functions can only generate updates to named styles, but as of now, that is
 * sufficient for our use case.
 */
class StylingConsistencyRetainer<S : NamedStyle>(
    styling: Styling,
    unusedStyles: Set<NamedStyle>,
    editedStyle: S
) {

    private class TrackedUsage<S2 : NamedStyle>(var style: S2, val settings: List<Setting<S2>>) {
        class Setting<S2 : NamedStyle>(val setting: StyleSetting<S2, String>, val baseItems: TreeSet<String>?)
    }


    private var oldName: String = editedStyle.name
    private val trackedUsages: List<TrackedUsage<*>>

    init {
        // We want to keep all other styles which "use" editedStyle in sync with changes to editedStyle's name.
        // For this, record all these usages now.
        trackedUsages = NamedStyle.CLASSES.flatMap { style2Class ->
            determineTrackedUsages(styling, unusedStyles, editedStyle, style2Class)
        }
    }

    private fun <S2 : NamedStyle> determineTrackedUsages(
        styling: Styling,
        unusedStyles: Set<NamedStyle>,
        editedStyle: S,
        style2Class: Class<S2>,
    ): List<TrackedUsage<S2>> {
        // Find all the style name constraints for style2 that reference styles of editedStyle's type.
        val refConstraints = buildList {
            for (constr in getStyleConstraints(style2Class))
                if (constr is StyleNameConstr<S2, *> && constr.styleClass == editedStyle.javaClass)
                    add(constr)
        }
        if (refConstraints.isEmpty())
            return emptyList()

        // Iterate over all instances of style2 and find which of the style name settings found above reference style2.
        val trackedUsages = mutableListOf<TrackedUsage<S2>>()
        for (style2 in styling.getNamedStyles(style2Class)) {
            val trackedUsageSettings = mutableListOf<TrackedUsage.Setting<S2>>()
            for (constr in refConstraints) {
                // In case that the edited style's name is not unique, the user expects that the reference stays in sync
                // with only the used one of the duplicate styles.
                if (editedStyle !in unusedStyles)
                    for (setting in constr.settings) {
                        val subjects = setting.extractSubjects(style2)
                        if (editedStyle.name in subjects) {
                            val baseItems = if (setting !is ListStyleSetting) null else
                                TreeSet(subjects).apply { remove(editedStyle.name) }
                            trackedUsageSettings.add(TrackedUsage.Setting(setting, baseItems))
                        }
                    }
            }
            if (trackedUsageSettings.isNotEmpty())
                trackedUsages.add(TrackedUsage(style2, trackedUsageSettings))
        }
        return trackedUsages
    }

    fun ensureConsistencyAfterEdit(editedStyle: S): Map<NamedStyle, NamedStyle> {
        val updates = IdentityHashMap<NamedStyle, NamedStyle>()
        val newName = editedStyle.name

        // Keep "usages" of renamed styles intact.
        // It is vital that we run these updates last, as the tracked usages need to memorize the final updated styles.
        if (oldName != newName)
            for (trackedUsage in trackedUsages)
                updateTrackedUsage(updates, trackedUsage, newName)

        oldName = newName
        return updates
    }

    private fun <S2 : NamedStyle> updateTrackedUsage(
        updates: MutableMap<NamedStyle, NamedStyle>,
        trackedUsage: TrackedUsage<S2>,
        newName: String
    ) {
        val newSettingValues = trackedUsage.settings.map { trackSt ->
            when (val st = trackSt.setting) {
                is DirectStyleSetting -> st.notarize(newName)
                is OptStyleSetting -> st.notarize(Opt(true, newName))
                is ListStyleSetting -> st.notarize(TreeSet(trackSt.baseItems).apply { add(newName) }.toImmutableList())
            }
        }
        val newStyle = updates.freshest(trackedUsage.style).copy(newSettingValues)
        updates[trackedUsage.style] = newStyle
        trackedUsage.style = newStyle
    }

}


@Suppress("UNCHECKED_CAST")
private fun <S : NamedStyle> Map<NamedStyle, NamedStyle>.freshest(style: S) =
    getOrDefault(style, style) as S
