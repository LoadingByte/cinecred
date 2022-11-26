package com.loadingbyte.cinecred.project

import com.loadingbyte.cinecred.common.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.util.*


/*
 * The functions in this file generate updates to the styling to ensure that (a) certain invariants are always fulfilled
 * and (b) user changes like renaming a style are reflected in the other styles as expected. Currently, the functions
 * can only generate updates to named styles, but as of now, that is sufficient for our use case.
 */


fun <S : NamedStyle> ensureConsistency(ctx: StylingContext, styles: List<S>): Map<S, S> {
    if (styles.isEmpty())
        return emptyMap()
    val updates = IdentityHashMap<S, MutableList<NotarizedStyleSettingValue<S>>>()
    forEachStyleClusterSetting(styles[0].javaClass) { setting, _ ->
        val clusters = mutableListOf<TreeSet<String>>()
        for (style in styles) {
            val cluster = clusters.removeFirstOrNull { style.name in it } ?: TreeSet<String>().apply { add(style.name) }
            for (otherStyleName in setting.get(style)) {
                val otherCluster = clusters.removeFirstOrNull { otherCluster -> otherStyleName in otherCluster }
                if (otherCluster != null) cluster.addAll(otherCluster) else cluster.add(otherStyleName)
            }
            clusters.add(cluster)
        }
        for (cluster in clusters)
            cluster.removeIf { styleName ->
                // Do not cluster styles which may not take part because their setting is ineffective. In the case of
                // duplicate names, all duplicates must be ineffective for the style name to be excluded.
                // Note: We use isEffectiveUnsafe() without a Styling object here because such an object is currently
                // unnecessary for determining the effectivity of any style cluster setting, and not forcing the caller
                // to provide a Styling object turns out to improve the caller code.
                styles.none { oStyle -> oStyle.name == styleName && isEffectiveUnsafe(ctx, oStyle, setting) }
            }
        val persistentClusters = clusters.map { cluster -> cluster.toPersistentList() }
        for (style in styles) {
            var refs = persistentClusters.firstOrNull { cluster -> style.name in cluster }
            if (refs != null) {
                refs = refs.remove(style.name)
                if (setting.get(style) != refs)
                    updates.computeIfAbsent(style) { mutableListOf() }.add(setting.notarize(refs))
            }
        }
    }
    return updates.associateWithTo(IdentityHashMap()) { style, settingValues -> style.copy(settingValues) }
}


fun <S : NamedStyle> ensureConsistencyAfterRemoval(remainingStyles: List<S>, removedStyle: S): Map<S, S> {
    val updates = IdentityHashMap<S, MutableList<NotarizedStyleSettingValue<S>>>()
    forEachStyleClusterSetting(removedStyle.javaClass) { setting, _ ->
        for (style in remainingStyles) {
            val refs = setting.get(style)
            if (removedStyle.name in refs)
                updates.computeIfAbsent(style) { mutableListOf() }.add(setting.notarize(refs.remove(removedStyle.name)))
        }
    }
    return updates.associateWithTo(IdentityHashMap()) { style, settingValues -> style.copy(settingValues) }
}


class StylingConsistencyRetainer<S : NamedStyle>(
    ctx: StylingContext,
    styling: Styling,
    unusedStyles: Set<NamedStyle>,
    editedStyle: S
) {

    private class TrackedUsage<S2 : NamedStyle>(var style: S2, val settings: List<Setting<S2>>) {
        class Setting<S2 : NamedStyle>(val setting: StyleSetting<S2, String>, val baseItems: TreeSet<String>?)
    }

    private inner class TrackedCluster(
        val setting: ListStyleSetting<S, String>, val constraint: StyleNameConstr<S, *>,
        var wasEffective: Boolean, var oldRefs: List<String>
    )


    private var oldName: String = editedStyle.name
    private val trackedClusters: List<TrackedCluster>
    private val trackedUsages: List<TrackedUsage<*>>

    init {
        // We want to sync each cluster list across all styles in the respective cluster.
        // For this, record the cluster lists of editedStyle now.
        trackedClusters = buildList {
            forEachStyleClusterSetting(editedStyle.javaClass) { setting, constr ->
                val isEff = isEffective(ctx, styling, editedStyle, setting)
                add(TrackedCluster(setting, constr, isEff, if (isEff) setting.get(editedStyle) else emptyList()))
            }
        }

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
        // Find all the non-clustering style name constraints for style2 that reference styles of editedStyle's type.
        val refConstraints = buildList {
            for (constr in getStyleConstraints(style2Class))
                if (constr is StyleNameConstr<S2, *> && !constr.clustering && constr.styleClass == editedStyle.javaClass)
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

    fun ensureConsistencyAfterEdit(
        ctx: StylingContext,
        styling: Styling,
        editedStyle: S
    ): Map<NamedStyle, NamedStyle> {
        val updates = IdentityHashMap<NamedStyle, NamedStyle>()
        val newName = editedStyle.name

        // Keep style clusters intact.
        for (trackedCluster in trackedClusters)
            updateTrackedCluster(updates, trackedCluster, ctx, styling, editedStyle)

        // Keep "usages" of renamed styles intact.
        // It is vital that we run these updates last, as the tracked usages need to memorize the final updated styles.
        if (oldName != newName)
            for (trackedUsage in trackedUsages)
                updateTrackedUsage(updates, trackedUsage, newName)

        oldName = newName
        return updates
    }

    private fun updateTrackedCluster(
        updates: MutableMap<NamedStyle, NamedStyle>,
        trackedCluster: TrackedCluster,
        ctx: StylingContext,
        styling: Styling,
        editedStyle: S
    ) {
        val setting = trackedCluster.setting
        val isEffective = isEffective(ctx, styling, editedStyle, setting)
        val choices = trackedCluster.constraint.choices(ctx, styling, editedStyle)
            .requireIsInstance(editedStyle.javaClass)

        if (!isEffective) {
            // When the user disables the setting, remove editedStyle from its cluster. This is so that he later has a
            // blank slate when he re-activates the setting.
            if (trackedCluster.wasEffective) {
                updates[editedStyle] = updates.freshest(editedStyle).copy(setting.notarize(persistentListOf()))
                // Remove refs to editedStyle's name only if there are no other selectable styles with the same name.
                if (choices.none { choice -> choice !== editedStyle && choice.name == editedStyle.name })
                    for (style in choices)
                        if (style !== editedStyle) {
                            val freshestStyle = updates.freshest(style)
                            val newStyleRefs = setting.get(freshestStyle).remove(oldName)
                            updates[style] = freshestStyle.copy(setting.notarize(newStyleRefs))
                        }
            }
        } else {
            // When the user re-enables the setting and there is another style with the same name that is part of a
            // cluster, make editedStyle part of that cluster too.
            if (!trackedCluster.wasEffective) {
                for (choice in choices)
                    if (choice !== editedStyle && choice.name == editedStyle.name) {
                        val refs = setting.get(choice)
                        if (refs.isNotEmpty()) {
                            updates[editedStyle] = updates.freshest(editedStyle).copy(setting.notarize(refs))
                            // Also set oldRefs to prevent the change from being interpreted as done by the user.
                            trackedCluster.oldRefs = refs
                            break
                        }
                    }
            }

            val curRefs = setting.get(updates.freshest(editedStyle))
            val chName = oldName != editedStyle.name
            val chRefs = trackedCluster.oldRefs != curRefs
            // When the user changed either the editedStyle's name or cluster references, rebuild the cluster.
            if (chName || chRefs) {
                val cluster = TreeSet(curRefs)
                cluster.add(editedStyle.name)

                if (chName) {
                    // If the name changed and there are other selectable styles with the new name, merge the cluster of
                    // those other styles with editedStyle's cluster to ensure that all styles with the same name also
                    // belong to the same cluster.
                    for (choice in choices)
                        if (choice !== editedStyle && choice.name == editedStyle.name) {
                            cluster.addAll(setting.get(choice))
                            // It could happen that editedStyle was already part of choice's cluster. In that case,
                            // remove editedStyle's old name because that name doesn't point to a style anymore.
                            cluster.remove(oldName)
                            break
                        }

                    // If the name changed but the style is part of a cluster and there are still other selectable
                    // styles with the old name, retain the old name in the cluster.
                    if (curRefs.isNotEmpty() && choices.any { choice -> choice.name == oldName })
                        cluster.add(oldName)
                }

                if (chRefs) {
                    // If style names have been added, merge the clusters that the added styles belong to with
                    // editedStyle's cluster.
                    val added = curRefs.filter { it !in trackedCluster.oldRefs }
                    if (added.isNotEmpty())
                        for (choice in choices)
                            if (choice.name in added)
                                cluster.addAll(setting.get(choice))

                    // If style names have been removed, clear the reference lists of the removed styles.
                    val removed = trackedCluster.oldRefs.filter { it !in curRefs }
                    if (removed.isNotEmpty())
                        for (choice in choices)
                            if (choice.name in removed)
                                updates[choice] = updates.freshest(choice).copy(setting.notarize(persistentListOf()))
                }

                // Apply the new reference list to all styles in the cluster (including editedStyle).
                val persistentCluster = cluster.toPersistentList()
                for (choice in choices)
                    if (choice.name in cluster) {
                        val refs = persistentCluster.remove(choice.name)
                        val freshestStyle = updates.freshest(choice)
                        if (setting.get(freshestStyle) != refs)
                            updates[choice] = freshestStyle.copy(setting.notarize(refs))
                    }
            }
        }

        trackedCluster.oldRefs = setting.get(updates.freshest(editedStyle))
        trackedCluster.wasEffective = isEffective
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
                is ListStyleSetting -> st.notarize(TreeSet(trackSt.baseItems).apply { add(newName) }.toPersistentList())
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


private inline fun <S : NamedStyle> forEachStyleClusterSetting(
    styleClass: Class<S>,
    action: (ListStyleSetting<S, String>, StyleNameConstr<S, *>) -> Unit
) {
    for (constr in getStyleConstraints(styleClass))
        if (constr is StyleNameConstr<S, *> && constr.clustering)
            for (setting in constr.settings)
                action(setting as ListStyleSetting, constr)
}
