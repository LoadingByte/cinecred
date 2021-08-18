package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.project.NumberConstr.Inequality.LARGER_0
import com.loadingbyte.cinecred.project.NumberConstr.Inequality.LARGER_OR_EQUAL_0
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.ImmutableList
import java.awt.Color
import java.util.*
import javax.swing.Icon
import javax.swing.SpinnerNumberModel


class StyleForm<S : Style>(private val styleClass: Class<S>) : Form() {

    private var changeListener: ((StyleSetting<S, *>) -> Unit)? = null

    // It is important that this map preserves insertion order.
    private val settingWidgets = SimpleLinkedBiMap<StyleSetting<S, *>, Widget<*>>()

    private var disableRefresh = false

    init {
        for (setting in getStyleSettings(styleClass)) {
            val settingMeta = getStyleMeta(styleClass).filter { m -> setting in m.settings }
            if (settingMeta.oneOf<NewSectionWidgetSpec<S>>() != null)
                addSeparator()
            addSettingWidget(setting, makeSettingWidget(setting, settingMeta))
        }
    }

    private fun <V> makeSettingWidget(setting: StyleSetting<S, V>, settingMeta: List<StyleMeta<S, *>>): Widget<*> {
        val numberConstr = settingMeta.oneOf<NumberConstr<S>>()
        val dynChoiceConstr = settingMeta.oneOf<DynChoiceConstr<S, *>>()
        val colorConstr = settingMeta.oneOf<ColorConstr<S>>()
        val fontNameConstr = settingMeta.oneOf<FontNameConstr<S>>()
        val dontGrowWidgetSpec = settingMeta.oneOf<DontGrowWidgetSpec<S>>()
        val numberStepWidgetSpec = settingMeta.oneOf<NumberStepWidgetSpec<S, *>>()
        val toggleButtonGroupWidgetSpec = settingMeta.oneOf<ToggleButtonGroupWidgetSpec<S>>()
        val toggleButtonGroupListWidgetSpec = settingMeta.oneOf<ToggleButtonGroupListWidgetSpec<S>>()
        val timecodeWidgetSpec = settingMeta.oneOf<TimecodeWidgetSpec<S>>()

        val settingGenericArg = setting.genericArg

        val settingWidget = when (setting.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                val inequality = numberConstr?.inequality
                val min = if (inequality == LARGER_0) 1 else if (inequality == LARGER_OR_EQUAL_0) 0 else null
                val step = numberStepWidgetSpec?.stepSize ?: 1
                val model = SpinnerNumberModel(min ?: 0, min, null, step)
                if (timecodeWidgetSpec != null)
                    TimecodeWidget(model, FPS(1, 1), TimecodeFormat.values()[0])
                else
                    SpinnerWidget(Int::class.javaObjectType, model)
            }
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> {
                val inequality = numberConstr?.inequality
                val min = if (inequality == LARGER_0) 0.01f else if (inequality == LARGER_OR_EQUAL_0) 0f else null
                val step = numberStepWidgetSpec?.stepSize ?: 1f
                SpinnerWidget(Float::class.javaObjectType, SpinnerNumberModel(min ?: 0f, min, null, step))
            }
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> CheckBoxWidget()
            String::class.java -> when {
                dynChoiceConstr != null -> InconsistentComboBoxWidget(String::class.java, emptyList())
                fontNameConstr != null -> FontChooserWidget()
                else -> TextWidget(grow = dontGrowWidgetSpec == null)
            }
            Locale::class.java -> InconsistentComboBoxWidget(
                Locale::class.java, Locale.getAvailableLocales().sortedBy(Locale::getDisplayName),
                toString = Locale::getDisplayName
            )
            Color::class.java -> ColorWellWidget(allowAlpha = colorConstr?.allowAlpha ?: true)
            FPS::class.java -> FPSWidget()
            Widening::class.java -> WideningWidget()
            else -> when {
                Enum::class.java.isAssignableFrom(setting.type) -> when {
                    dynChoiceConstr != null -> InconsistentComboBoxWidget(
                        setting.type as Class<*>, emptyList(),
                        toString = { l10nEnum(it as Enum<*>) }
                    )
                    toggleButtonGroupWidgetSpec != null ->
                        makeEnumTBGWidget(setting.type as Class<*>, toggleButtonGroupWidgetSpec.show)
                    else ->
                        makeEnumCBoxWidget(setting.type as Class<*>)
                }
                ImmutableList::class.java.isAssignableFrom(setting.type) && settingGenericArg != null -> when {
                    String::class.java == settingGenericArg -> TextListWidget(grow = dontGrowWidgetSpec == null)
                    Enum::class.java.isAssignableFrom(settingGenericArg) ->
                        if (toggleButtonGroupListWidgetSpec != null)
                            makeEnumTBGWidget(
                                settingGenericArg, toggleButtonGroupListWidgetSpec.show, list = true,
                                toggleButtonGroupListWidgetSpec.groupsPerRow
                            )
                        else
                            throw UnsupportedOperationException("Enum lists must use ToggleButtonGroupWidgetSpec.")
                    else -> throw UnsupportedOperationException(
                        "UI unsupported for objects of type ${setting.type.name}<${settingGenericArg.name}>."
                    )
                }
                else -> throw UnsupportedOperationException("UI unsupported for objects of type ${setting.type.name}.")
            }
        }

        return when (setting) {
            is OptionallyEffectiveStyleSetting -> OptionallyEffectiveWidget(settingWidget)
            else -> settingWidget
        }
    }

    private fun <E : Any /* non-null */> makeEnumCBoxWidget(enumClass: Class<E>) =
        ComboBoxWidget(
            enumClass, enumClass.enumConstants.asList(),
            toString = { l10nEnum(it as Enum<*>) })

    private fun <E : Any /* non-null */> makeEnumTBGWidget(
        enumClass: Class<E>,
        show: ToggleButtonGroupWidgetSpec.Show,
        list: Boolean = false,
        groupsPerRow: Int = -1,
    ): Widget<*> {
        var toIcon: ((E) -> Icon)? = fun(item: E) = (item as Enum<*>).icon
        var toLabel: ((E) -> String)? = fun(item: E) = l10nEnum(item as Enum<*>)
        var toTooltip: ((E) -> String)? = toLabel
        if (show == ToggleButtonGroupWidgetSpec.Show.ICON)
            toLabel = null
        else if (show == ToggleButtonGroupWidgetSpec.Show.LABEL) {
            toIcon = null
            toTooltip = null
        }

        val items = enumClass.enumConstants.asList()
        return if (list)
            ToggleButtonGroupListWidget(items, toIcon, toLabel, toTooltip, groupsPerRow)
        else
            ToggleButtonGroupWidget(items, toIcon, toLabel, toTooltip)
    }

    private fun addSettingWidget(setting: StyleSetting<S, *>, settingWidget: Widget<*>) {
        val l10nKey = "ui.styling." +
                styleClass.simpleName.removeSuffix("Style").lowercase() +
                ".${setting.name}"

        try {
            settingWidget.notice = Notice(Severity.INFO, l10n("$l10nKey.desc"))
        } catch (_: MissingResourceException) {
        }

        addWidget(l10n(l10nKey), settingWidget)
        settingWidgets[setting] = settingWidget
    }

    fun open(style: S, onChange: ((StyleSetting<S, *>) -> Unit)?) {
        changeListener = null
        disableRefresh = true
        try {
            for ((setting, settingWidget) in settingWidgets)
                @Suppress("UNCHECKED_CAST")
                (settingWidget as Widget<Any?>).value = setting.getPlain(style)
        } finally {
            disableRefresh = false
        }
        refresh()
        changeListener = onChange
    }

    fun save(): S =
        newStyle(styleClass, settingWidgets.values.map(Widget<*>::value))

    fun clearNoticeOverrides() {
        for (widget in settingWidgets.values)
            widget.noticeOverride = null
    }

    fun setNoticeOverride(setting: StyleSetting<*, *>, noticeOverride: Notice) {
        settingWidgets[setting]!!.noticeOverride = noticeOverride
    }

    fun setDynChoices(setting: StyleSetting<*, *>, choices: ImmutableList<*>) {
        @Suppress("UNCHECKED_CAST")
        (getBackingWidget(setting) as ChoiceWidget<*, Any?>).items = choices
    }

    fun setTimecodeFPSAndFormat(setting: StyleSetting<*, *>, fps: FPS, timecodeFormat: TimecodeFormat) {
        val timecodeWidget = getBackingWidget(setting) as TimecodeWidget
        timecodeWidget.fps = fps
        timecodeWidget.timecodeFormat = timecodeFormat
    }

    private fun getBackingWidget(setting: StyleSetting<*, *>): Widget<*> {
        var widget = settingWidgets[setting]!!
        while (widget is WrapperWidget<*, *>)
            widget = widget.wrapped
        return widget
    }

    override fun onChange(widget: Widget<*>) {
        refresh()
        changeListener?.invoke(settingWidgets.getReverse(widget)!!)
    }

    private fun refresh() {
        if (disableRefresh)
            return

        val style = save()
        val ineffectiveSettings = findIneffectiveSettings(style)
        for ((setting, settingWidget) in settingWidgets) {
            val effectivity = ineffectiveSettings.getOrDefault(setting, Effectivity.EFFECTIVE)
            settingWidget.isVisible = effectivity >= Effectivity.ALMOST_EFFECTIVE
            settingWidget.isEnabled = effectivity >= Effectivity.OPTIONALLY_INEFFECTIVE
        }
    }


    companion object {

        private inline fun <reified T : Any> Iterable<*>.oneOf(): T? {
            var found: T? = null
            for (elem in this)
                if (elem is T)
                    if (found != null)
                        throw IllegalArgumentException("Collection has more than one element.")
                    else
                        found = elem
            return found
        }

        private fun l10nEnum(enumElem: Enum<*>) =
            l10n("project.${enumElem.javaClass.simpleName}.${enumElem.name}")

    }


    private class SimpleLinkedBiMap<K, V> : Iterable<Map.Entry<K, V>> {

        private val ktv = LinkedHashMap<K, V>()
        private val vtk = HashMap<V, K>()

        val values: Collection<V> = ktv.values

        override fun iterator(): Iterator<Map.Entry<K, V>> = ktv.iterator()

        operator fun set(key: K, value: V) {
            ktv[key] = value
            vtk[value] = key
        }

        operator fun get(key: Any?): V? = ktv[key]
        fun getReverse(value: Any?): K? = vtk[value]

    }

}
