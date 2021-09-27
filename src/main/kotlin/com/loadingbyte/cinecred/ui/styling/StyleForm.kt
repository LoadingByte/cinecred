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


class StyleForm<S : Style>(private val styleClass: Class<S>, insets: Boolean = true) : Form.Storable<S>(insets) {

    private val backingWidgets = HashMap<StyleSetting<S, *>, Widget<*>>()
    private val valueWidgets = LinkedHashMap<StyleSetting<S, *>, Widget<*>>() // must retain order
    private val rootWidgets = HashMap<StyleSetting<S, *>, Widget<*>>()

    private var disableOnChange = false

    init {
        val widgetSpecs = getStyleWidgetSpecs(styleClass)
        val unionSpecs = widgetSpecs.filterIsInstance<UnionWidgetSpec<S>>()

        for (setting in getStyleSettings(styleClass)) {
            val unionSpec = unionSpecs.find { setting in it.settings }
            if (unionSpec == null || unionSpec.settings.first() == setting) {
                if (widgetSpecs.any { setting in it.settings && it is NewSectionWidgetSpec<S> })
                    addSeparator()
                if (unionSpec == null)
                    addSingleSettingWidget(setting)
                else
                    addSettingUnionWidget(unionSpec)
            }
        }
    }

    private fun addSingleSettingWidget(setting: StyleSetting<S, *>) {
        val (backingWidget, valueWidget) = makeSettingWidget(setting)
        if (backingWidget != null)
            backingWidgets[setting] = backingWidget
        valueWidgets[setting] = valueWidget
        rootWidgets[setting] = valueWidget
        addRootWidget(setting.name, valueWidget)
    }

    private fun addSettingUnionWidget(spec: UnionWidgetSpec<S>) {
        val wrappedWidgets = mutableListOf<Widget<*>>()
        for (setting in spec.settings) {
            val (backingWidget, valueWidget) = makeSettingWidget(setting)
            if (backingWidget != null)
                backingWidgets[setting] = backingWidget
            wrappedWidgets.add(valueWidget)
            valueWidgets[setting] = valueWidget
        }
        val unionWidget = UnionWidget(wrappedWidgets, spec.settingIcons)
        for (setting in spec.settings)
            rootWidgets[setting] = unionWidget
        addRootWidget(spec.unionName, unionWidget)
    }

    // Returns backing widget and value widget.
    private fun makeSettingWidget(setting: StyleSetting<S, *>): Pair<Widget<*>?, Widget<*>> {
        val settingConstraints = getStyleConstraints(styleClass).filter { c -> setting in c.settings }
        val settingWidgetSpecs = getStyleWidgetSpecs(styleClass).filter { s -> setting in s.settings }

        return when (setting) {
            is DirectStyleSetting -> {
                val widget = makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs)
                Pair(widget, widget)
            }
            is OptStyleSetting -> {
                val backingWidget = makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs)
                Pair(backingWidget, OptWidget(backingWidget))
            }
            is ListStyleSetting -> {
                if (setting.type == String::class.java) {
                    val widthWidgetSpec = settingWidgetSpecs.oneOf<WidthWidgetSpec<S>>()
                    val widget = TextListWidget(widthWidgetSpec?.widthSpec)
                    Pair(widget, widget)
                } else {
                    val minSizeConstr = settingConstraints.oneOf<MinSizeConstr<S>>()
                    val listWidgetSpec = settingWidgetSpecs.oneOf<ListWidgetSpec<S>>()
                    val valueWidget = ListWidget(listWidgetSpec?.groupsPerRow ?: 1, minSizeConstr?.minSize ?: 0) {
                        makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs)
                    }
                    Pair(null, valueWidget)
                }
            }
        }
    }

    private fun makeBackingSettingWidget(
        setting: StyleSetting<S, *>,
        settingConstraints: List<StyleConstraint<S, *>>,
        settingWidgetSpecs: List<StyleWidgetSpec<S>>
    ): Widget<*> {
        val intConstr = settingConstraints.oneOf<IntConstr<S>>()
        val floatConstr = settingConstraints.oneOf<FloatConstr<S>>()
        val dynChoiceConstr = settingConstraints.oneOf<DynChoiceConstr<S>>()
        val colorConstr = settingConstraints.oneOf<ColorConstr<S>>()
        val fontNameConstr = settingConstraints.oneOf<FontNameConstr<S>>()
        val widthWidgetSpec = settingWidgetSpecs.oneOf<WidthWidgetSpec<S>>()
        val numberWidgetSpec = settingWidgetSpecs.oneOf<NumberWidgetSpec<S>>()
        val toggleButtonGroupWidgetSpec = settingWidgetSpecs.oneOf<ToggleButtonGroupWidgetSpec<S>>()
        val timecodeWidgetSpec = settingWidgetSpecs.oneOf<TimecodeWidgetSpec<S>>()

        val widthSpec = widthWidgetSpec?.widthSpec

        return when (setting.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                val min = intConstr?.min
                val max = intConstr?.max
                val init = numberWidgetSpec?.default ?: min ?: max ?: 0
                val step = numberWidgetSpec?.step ?: 1
                val model = SpinnerNumberModel(init, min, max, step)
                if (timecodeWidgetSpec != null)
                    TimecodeWidget(model, FPS(1, 1), TimecodeFormat.values()[0], widthSpec)
                else
                    SpinnerWidget(Int::class.javaObjectType, model, widthSpec)
            }
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> {
                val min = floatConstr?.let { if (it.minInclusive) it.min else it.min?.plus(0.01f) }
                val max = floatConstr?.let { if (it.maxInclusive) it.max else it.max?.minus(0.01f) }
                val init = numberWidgetSpec?.default ?: min ?: max ?: 0f
                val step = numberWidgetSpec?.step ?: 1f
                val model = SpinnerNumberModel(init, min, max, step)
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
                Style::class.java.isAssignableFrom(setting.type) ->
                    @Suppress("UNCHECKED_CAST")
                    NestedFormWidget(StyleForm(setting.type as Class<Style>, insets = false))
                        .apply { value = getPreset(setting.type) }
                else -> throw UnsupportedOperationException("UI unsupported for objects of type ${setting.type.name}.")
            }
        }
    }

    private fun <E : Any /* non-null */> makeEnumCBoxWidget(enumClass: Class<E>, widthSpec: WidthSpec?) =
        ComboBoxWidget(enumClass, enumClass.enumConstants.asList(), toString = { l10nEnum(it as Enum<*>) }, widthSpec)

    private fun <E : Any /* non-null */> makeEnumTBGWidget(
        enumClass: Class<E>,
        show: ToggleButtonGroupWidgetSpec.Show
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
        return ToggleButtonGroupWidget(items, toIcon, toLabel, toTooltip)
    }

    private fun addRootWidget(name: String, widget: Widget<*>) {
        val l10nKey = "ui.styling." +
                styleClass.simpleName.removeSuffix("Style").replaceFirstChar(Char::lowercase) +
                ".$name"
        try {
            widget.notice = Notice(Severity.INFO, l10n("$l10nKey.desc"))
        } catch (_: MissingResourceException) {
        }
        addWidget(l10n(l10nKey), widget)
    }

    override fun open(stored /* style */: S) {
        disableOnChange = true
        try {
            for ((setting, widget) in valueWidgets)
                @Suppress("UNCHECKED_CAST")
                (widget as Widget<Any?>).value = setting.get(stored)
        } finally {
            disableOnChange = false
        }
        refresh()
    }

    override fun save(): S =
        newStyle(styleClass, valueWidgets.map { (_, widget) -> widget.value })

    fun getNestedForms(): List<StyleForm<*>> {
        val nestedForms = mutableListOf<StyleForm<*>>()
        for (setting in getStyleSettings(styleClass))
            if (Style::class.java.isAssignableFrom(setting.type))
                when (setting) {
                    is DirectStyleSetting, is OptStyleSetting ->
                        nestedForms.add((backingWidgets[setting] as NestedFormWidget).form as StyleForm)
                    is ListStyleSetting ->
                        for (elementWidget in (valueWidgets[setting] as ListWidget<*>).elementWidgets)
                            nestedForms.add((elementWidget as NestedFormWidget).form as StyleForm)
                }
        return nestedForms
    }

    fun clearNoticeOverrides() {
        for (widget in rootWidgets.values)
            widget.noticeOverride = null
    }

    fun getNoticeOverride(setting: StyleSetting<*, *>): Notice? =
        rootWidgets[setting]!!.noticeOverride

    fun setNoticeOverride(setting: StyleSetting<*, *>, noticeOverride: Notice) {
        rootWidgets[setting]!!.noticeOverride = noticeOverride
    }

    fun setDynChoices(setting: StyleSetting<*, *>, choices: ImmutableList<*>) {
        @Suppress("UNCHECKED_CAST")
        (backingWidgets[setting] as ChoiceWidget<*, Any?>).items = choices
    }

    fun setTimecodeFPSAndFormat(setting: StyleSetting<*, *>, fps: FPS, timecodeFormat: TimecodeFormat) {
        val timecodeWidget = backingWidgets[setting] as TimecodeWidget
        timecodeWidget.fps = fps
        timecodeWidget.timecodeFormat = timecodeFormat
    }

    override fun onChange(widget: Widget<*>) {
        if (!disableOnChange) {
            refresh()
            super.onChange(widget)
        }
    }

    private fun refresh() {
        val style = save()
        val ineffectiveSettings = findIneffectiveSettings(style)
        for ((setting, widget) in valueWidgets) {
            val effectivity = ineffectiveSettings.getOrDefault(setting, Effectivity.EFFECTIVE)
            widget.isVisible = effectivity >= Effectivity.ALMOST_EFFECTIVE
            widget.isEnabled = effectivity == Effectivity.EFFECTIVE
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

}
