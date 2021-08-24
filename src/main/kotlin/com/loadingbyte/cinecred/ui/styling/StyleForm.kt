package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.project.*
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
            val settingConstraints = getStyleConstraints(styleClass).filter { c -> setting in c.settings }
            val settingWidgetSpecs = getStyleWidgetSpecs(styleClass).filter { s -> setting in s.settings }
            if (settingWidgetSpecs.oneOf<NewSectionWidgetSpec<S>>() != null)
                addSeparator()
            addSettingWidget(setting, makeSettingWidget(setting, settingConstraints, settingWidgetSpecs))
        }
    }

    private fun <V> makeSettingWidget(
        setting: StyleSetting<S, V>,
        settingConstraints: List<StyleConstraint<S, *>>,
        settingWidgetSpecs: List<StyleWidgetSpec<S>>
    ): Widget<*> {
        val intConstr = settingConstraints.oneOf<IntConstr<S>>()
        val floatConstr = settingConstraints.oneOf<FloatConstr<S>>()
        val dynChoiceConstr = settingConstraints.oneOf<DynChoiceConstr<S>>()
        val colorConstr = settingConstraints.oneOf<ColorConstr<S>>()
        val fontNameConstr = settingConstraints.oneOf<FontNameConstr<S>>()
        val widthWidgetSpec = settingWidgetSpecs.oneOf<WidthWidgetSpec<S>>()
        val numberStepWidgetSpec = settingWidgetSpecs.oneOf<NumberStepWidgetSpec<S>>()
        val toggleButtonGroupWidgetSpec = settingWidgetSpecs.oneOf<ToggleButtonGroupWidgetSpec<S>>()
        val toggleButtonGroupListWidgetSpec = settingWidgetSpecs.oneOf<ToggleButtonGroupListWidgetSpec<S>>()
        val timecodeWidgetSpec = settingWidgetSpecs.oneOf<TimecodeWidgetSpec<S>>()

        val settingGenericArg = setting.genericArg
        val widthSpec = widthWidgetSpec?.widthSpec

        val settingWidget = when (setting.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                val min = intConstr?.min
                val max = intConstr?.max
                val step = numberStepWidgetSpec?.stepSize ?: 1
                val model = SpinnerNumberModel(min ?: max ?: 0, min, max, step)
                if (timecodeWidgetSpec != null)
                    TimecodeWidget(model, FPS(1, 1), TimecodeFormat.values()[0], widthSpec)
                else
                    SpinnerWidget(Int::class.javaObjectType, model, widthSpec)
            }
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> {
                val min = floatConstr?.let { if (it.minInclusive) it.min else it.min?.plus(0.01f) }
                val max = floatConstr?.let { if (it.maxInclusive) it.max else it.max?.minus(0.01f) }
                val step = numberStepWidgetSpec?.stepSize ?: 1f
                val model = SpinnerNumberModel(min ?: max ?: 0f, min, max, step)
                SpinnerWidget(Float::class.javaObjectType, model, widthSpec)
            }
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> CheckBoxWidget()
            String::class.java -> when {
                dynChoiceConstr != null -> InconsistentComboBoxWidget(
                    String::class.java, emptyList(), widthSpec = widthSpec
                )
                fontNameConstr != null -> FontChooserWidget(widthSpec)
                else -> TextWidget(widthSpec)
            }
            Locale::class.java -> InconsistentComboBoxWidget(
                Locale::class.java, Locale.getAvailableLocales().sortedBy(Locale::getDisplayName),
                toString = Locale::getDisplayName, widthSpec
            )
            Color::class.java -> ColorWellWidget(allowAlpha = colorConstr?.allowAlpha ?: true, widthSpec)
            FPS::class.java -> FPSWidget(widthSpec)
            Widening::class.java -> WideningWidget()
            else -> when {
                Enum::class.java.isAssignableFrom(setting.type) -> when {
                    dynChoiceConstr != null -> InconsistentComboBoxWidget(
                        setting.type as Class<*>, emptyList(), toString = { l10nEnum(it as Enum<*>) }, widthSpec
                    )
                    toggleButtonGroupWidgetSpec != null ->
                        makeEnumTBGWidget(setting.type as Class<*>, toggleButtonGroupWidgetSpec.show)
                    else ->
                        makeEnumCBoxWidget(setting.type as Class<*>, widthSpec)
                }
                ImmutableList::class.java.isAssignableFrom(setting.type) && settingGenericArg != null -> when {
                    String::class.java == settingGenericArg -> TextListWidget(widthSpec)
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

    private fun <E : Any /* non-null */> makeEnumCBoxWidget(enumClass: Class<E>, widthSpec: WidthSpec?) =
        ComboBoxWidget(enumClass, enumClass.enumConstants.asList(), toString = { l10nEnum(it as Enum<*>) }, widthSpec)

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

    fun getNoticeOverride(setting: StyleSetting<*, *>): Notice? =
        settingWidgets[setting]!!.noticeOverride

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
