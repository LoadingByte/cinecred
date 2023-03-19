package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.project.*
import com.loadingbyte.cinecred.ui.helper.*
import kotlinx.collections.immutable.toPersistentList
import java.awt.Color
import java.util.*
import javax.swing.Icon
import javax.swing.SpinnerNumberModel


class StyleForm<S : Style>(
    private val styleClass: Class<S>,
    private val constSettings: Map<StyleSetting<in S, *>, Any> = emptyMap(),
    insets: Boolean = true,
    noticeArea: Boolean = true,
    constLabelWidth: Boolean = true
) : Form.Storable<S>(insets, noticeArea, constLabelWidth) {

    private val valueWidgets = LinkedHashMap<StyleSetting<S, *>, Widget<*>>() // must retain order
    private val rootFormRows = ArrayList<Pair<FormRow, List<StyleSetting<S, *>>>>()
    private val rootFormRowLookup = HashMap<StyleSetting<S, *>, FormRow>()

    private var disableOnChange = false

    init {
        val widgetSpecs = getStyleWidgetSpecs(styleClass)
        val unionSpecs = widgetSpecs.filterIsInstance<UnionWidgetSpec<S>>()

        for (setting in getStyleSettings(styleClass)) {
            if (setting in constSettings)
                continue
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
        val valueWidget = makeSettingWidget(setting)
        val wholeWidth = valueWidget is LayerListWidget<*, *>
        val formRow = if (wholeWidth) FormRow("", valueWidget) else makeFormRow(setting.name, valueWidget)
        valueWidgets[setting] = valueWidget
        rootFormRows.add(Pair(formRow, listOf(setting)))
        rootFormRowLookup[setting] = formRow
        addFormRow(formRow, wholeWidth = wholeWidth)
    }

    private fun addSettingUnionWidget(spec: UnionWidgetSpec<S>) {
        val wrappedWidgets = mutableListOf<Widget<*>>()
        val wrappedLabels = if (spec.settingLabels.isEmpty()) null else mutableListOf<String?>()
        for ((idx, setting) in spec.settings.withIndex()) {
            val valueWidget = makeSettingWidget(setting)
            wrappedWidgets.add(valueWidget)
            valueWidgets[setting] = valueWidget
            wrappedLabels?.add(if (idx in spec.settingLabels) l10n(l10nKey(setting.name)) else null)
        }
        val unionWidget = UnionWidget(wrappedWidgets, wrappedLabels, spec.settingIcons, spec.settingNewlines)
        val unionName = spec.unionName ?: spec.settings.first().name
        val formRow = makeFormRow(unionName, unionWidget)
        rootFormRows.add(Pair(formRow, spec.settings))
        for (setting in spec.settings)
            rootFormRowLookup[setting] = formRow
        addFormRow(formRow)
    }

    private fun makeSettingWidget(setting: StyleSetting<S, *>): Widget<*> {
        val settingConstraints = getStyleConstraints(styleClass).filter { c -> setting in c.settings }
        val settingWidgetSpecs = getStyleWidgetSpecs(styleClass).filter { s -> setting in s.settings }

        return when (setting) {
            is DirectStyleSetting ->
                makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs)
            is OptStyleSetting ->
                OptWidget(makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs))
            is ListStyleSetting ->
                makeListWidget(setting, settingConstraints, settingWidgetSpecs)
        }
    }

    private fun <E : Any /* non-null */> makeListWidget(
        setting: ListStyleSetting<S, E>,
        settingConstraints: List<StyleConstraint<S, *>>,
        settingWidgetSpecs: List<StyleWidgetSpec<S>>
    ): Widget<*> {
        val fixedChoiceConstr = settingConstraints.oneOf<FixedChoiceConstr<S, E>>()
        val dynChoiceConstr = settingConstraints.oneOf<DynChoiceConstr<S, E>>()
        val styleNameConstr = settingConstraints.oneOf<StyleNameConstr<S, *>>()
        val minSizeConstr = settingConstraints.oneOf<MinSizeConstr<S>>()
        val siblingOrdinalConstr = settingConstraints.oneOf<SiblingOrdinalConstr<*>>()
        val widthWidgetSpec = settingWidgetSpecs.oneOf<WidthWidgetSpec<S>>()
        val simpleListWidgetSpec = settingWidgetSpecs.oneOf<SimpleListWidgetSpec<S, E>>()
        val layerListWidgetSpec = settingWidgetSpecs.oneOf<LayerListWidgetSpec<S, *>>()
        val choiceWidgetSpec = settingWidgetSpecs.oneOf<ChoiceWidgetSpec<S>>()

        val widthSpec = widthWidgetSpec?.widthSpec

        return when {
            (setting.type == Int::class.javaPrimitiveType || setting.type == Int::class.javaObjectType) &&
                    siblingOrdinalConstr != null ->
                MultiComboBoxWidget<Int>(
                    items = emptyList(), naturalOrder(), widthSpec = widthSpec,
                    // Even though sibling ordinal widgets are never actually inconsistent to the user, they are for a
                    // very short period after a style has been loaded into a form and before the widget's items have
                    // been adjusted to reflect the layers available in the currently loaded style.
                    inconsistent = true
                )
            setting.type == String::class.java -> when {
                fixedChoiceConstr != null || dynChoiceConstr != null || styleNameConstr != null ->
                    MultiComboBoxWidget(
                        items = fixedChoiceConstr?.run { choices.toList().requireIsInstance() } ?: emptyList(),
                        naturalOrder(), widthSpec = widthSpec, inconsistent = true,
                        noItemsMessage = choiceWidgetSpec?.getNoItemsMsg?.invoke()
                    )
                else -> TextListWidget(widthSpec)
            }
            layerListWidgetSpec != null -> makeLayerListWidget(
                @Suppress("UNCHECKED_CAST")
                (setting as ListStyleSetting<S, NamedNestedStyle>),
                settingWidgetSpecs
            )
            else -> SimpleListWidget(
                makeElementWidget = { makeBackingSettingWidget(setting, settingConstraints, settingWidgetSpecs) },
                newElement = simpleListWidgetSpec?.newElement,
                newElementIsLastElement = simpleListWidgetSpec?.newElementIsLastElement ?: false,
                elementsPerRow = simpleListWidgetSpec?.elementsPerRow ?: 1,
                minSize = minSizeConstr?.minSize ?: 0
            )
        }
    }

    private fun <E : NamedNestedStyle> makeLayerListWidget(
        setting: ListStyleSetting<S, E>,
        settingWidgetSpecs: List<StyleWidgetSpec<S>>
    ): Widget<*> {
        val layerListWidgetSpec = settingWidgetSpecs.oneOf<LayerListWidgetSpec<S, E>>()!!

        val siblingOrdinalSettings = buildList {
            for (constr in getStyleConstraints(setting.type))
                if (constr is SiblingOrdinalConstr<E>)
                    addAll(constr.settings)
        }

        return LayerListWidget(
            makeElementWidget = {
                val nestedForm = StyleForm(
                    setting.type, constSettings = mapOf(NamedStyle::name.st() to ""),
                    // Horizontal space in nested forms is scarce, so save where we can.
                    insets = false, noticeArea = false, constLabelWidth = false
                )
                NestedFormWidget(nestedForm)
            },
            newElement = layerListWidgetSpec.newElement,
            getElementName = { style -> style.name },
            setElementName = { style, name -> style.copy(NamedStyle::name.st().notarize(name)) },
            mapOrdinalsInElement = { nestedStyle, mapping ->
                nestedStyle.copy(siblingOrdinalSettings.map { setting ->
                    if (setting is DirectStyleSetting) setting.notarize(mapping(setting.get(nestedStyle)) ?: 0)
                    else setting.repackSubjects(setting.extractSubjects(nestedStyle).mapNotNull(mapping))
                })
            },
            toggleAdvanced = { widget, advanced ->
                (widget.form as StyleForm<E>).hiddenSettings =
                    if (advanced) emptySet() else layerListWidgetSpec.advancedSettings
            }
        )
    }

    private fun <V : Any /* non-null */> makeBackingSettingWidget(
        setting: StyleSetting<S, V>,
        settingConstraints: List<StyleConstraint<S, *>>,
        settingWidgetSpecs: List<StyleWidgetSpec<S>>
    ): Widget<V> {
        val intConstr = settingConstraints.oneOf<IntConstr<S>>()
        val doubleConstr = settingConstraints.oneOf<DoubleConstr<S>>()
        val fixedChoiceConstr = settingConstraints.oneOf<FixedChoiceConstr<S, V>>()
        val dynChoiceConstr = settingConstraints.oneOf<DynChoiceConstr<S, V>>()
        val styleNameConstr = settingConstraints.oneOf<StyleNameConstr<S, *>>()
        val colorConstr = settingConstraints.oneOf<ColorConstr<S>>()
        val fontNameConstr = settingConstraints.oneOf<FontNameConstr<S>>()
        val siblingOrdinalConstr = settingConstraints.oneOf<SiblingOrdinalConstr<*>>()
        val widthWidgetSpec = settingWidgetSpecs.oneOf<WidthWidgetSpec<S>>()
        val numberWidgetSpec = settingWidgetSpecs.oneOf<NumberWidgetSpec<S>>()
        val toggleButtonGroupWidgetSpec = settingWidgetSpecs.oneOf<ToggleButtonGroupWidgetSpec<S, *>>()
        val multiplierWidgetSpec = settingWidgetSpecs.oneOf<MultiplierWidgetSpec<S>>()
        val timecodeWidgetSpec = settingWidgetSpecs.oneOf<TimecodeWidgetSpec<S>>()

        val widthSpec = widthWidgetSpec?.widthSpec

        val widget = when (setting.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                val min = intConstr?.min
                val max = intConstr?.max
                val step = numberWidgetSpec?.step ?: 1
                val model = SpinnerNumberModel(min ?: max ?: 0, min, max, step)
                if (siblingOrdinalConstr != null) {
                    // See makeListWidget() for a comment on why we need an inconsistent widget for sibling ordinals.
                    InconsistentComboBoxWidget(Int::class.javaObjectType, items = emptyList(), widthSpec = widthSpec)
                } else if (timecodeWidgetSpec != null)
                    TimecodeWidget(model, FPS(1, 1), TimecodeFormat.values()[0], widthSpec)
                else
                    SpinnerWidget(Int::class.javaObjectType, model, widthSpec)
            }
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> {
                val min = doubleConstr?.let { if (it.minInclusive) it.min else it.min?.plus(0.01) }
                val max = doubleConstr?.let { if (it.maxInclusive) it.max else it.max?.minus(0.01) }
                val step = numberWidgetSpec?.step ?: 1.0
                val model = SpinnerNumberModel(min ?: max ?: 0.0, min, max, step)
                if (multiplierWidgetSpec != null)
                    MultipliedSpinnerWidget(model, widthSpec)
                else
                    SpinnerWidget(Double::class.javaObjectType, model, widthSpec)
            }
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> when {
                toggleButtonGroupWidgetSpec != null -> makeToggleButtonGroupWidget(
                    toggleButtonGroupWidgetSpec.show,
                    items = listOf(false, true),
                    getIcon = toggleButtonGroupWidgetSpec.getFixedIcon,
                    getLabel = { l10n(if (it) "on" else "off") },
                    inconsistent = false
                )
                else -> CheckBoxWidget()
            }
            String::class.java -> when {
                fixedChoiceConstr != null || dynChoiceConstr != null || styleNameConstr != null ->
                    InconsistentComboBoxWidget(
                        String::class.java,
                        items = fixedChoiceConstr?.run { choices.toList().requireIsInstance() } ?: emptyList(),
                        widthSpec = widthSpec
                    )
                fontNameConstr != null -> FontChooserWidget(widthSpec)
                else -> TextWidget(widthSpec)
            }
            Locale::class.java -> InconsistentComboBoxWidget(
                Locale::class.java, Locale.getAvailableLocales().sortedBy(Locale::getDisplayName),
                toString = Locale::getDisplayName, widthSpec
            )
            Color::class.java -> ColorWellWidget(allowAlpha = colorConstr?.allowAlpha ?: true, widthSpec)
            Resolution::class.java -> ResolutionWidget(defaultCustom = PRESET_GLOBAL.resolution)
            FPS::class.java -> FPSWidget(widthSpec)
            FontFeature::class.java -> FontFeatureWidget()
            else -> when {
                Enum::class.java.isAssignableFrom(setting.type) -> when {
                    toggleButtonGroupWidgetSpec != null -> makeEnumToggleButtonGroupWidget(
                        setting.type.asSubclass(Enum::class.java),
                        toggleButtonGroupWidgetSpec.show,
                        items = fixedChoiceConstr?.run { choices.toList() },
                        getFixedIcon = toggleButtonGroupWidgetSpec.getFixedIcon,
                        dynIcons = toggleButtonGroupWidgetSpec.getDynIcon != null,
                        inconsistent = dynChoiceConstr != null
                    )
                    fixedChoiceConstr != null || dynChoiceConstr != null -> InconsistentComboBoxWidget(
                        setting.type,
                        items = fixedChoiceConstr?.run { choices.toList() } ?: emptyList(),
                        toString = { l10nEnum(it as Enum<*>) }, widthSpec
                    )
                    else -> ComboBoxWidget(
                        setting.type, setting.type.enumConstants.asList(), toString = { l10nEnum(it as Enum<*>) },
                        widthSpec
                    )
                }
                else -> throw UnsupportedOperationException("UI unsupported for objects of type ${setting.type.name}.")
            }
        }

        @Suppress("UNCHECKED_CAST")
        return widget as Widget<V>
    }

    private fun <V : Any> makeToggleButtonGroupWidget(
        show: ToggleButtonGroupWidgetSpec.Show,
        items: List<V>,
        getIcon: ((Nothing) -> Icon)?,
        getLabel: (V) -> String,
        inconsistent: Boolean
    ): Widget<V> {
        @Suppress("UNCHECKED_CAST")
        var toIcon: ((V) -> Icon)? = getIcon as ((V) -> Icon)?
        var toLabel: ((V) -> String)? = getLabel
        var toTooltip: ((V) -> String)? = getLabel
        // @formatter:off
        when (show) {
            ToggleButtonGroupWidgetSpec.Show.LABEL -> { toIcon = null; toTooltip = null }
            ToggleButtonGroupWidgetSpec.Show.ICON -> toLabel = null
            ToggleButtonGroupWidgetSpec.Show.ICON_AND_LABEL -> toTooltip = null
        }
        // @formatter:on
        return ToggleButtonGroupWidget(items, toIcon, toLabel, toTooltip, inconsistent)
    }

    private fun <V : Enum<*>> makeEnumToggleButtonGroupWidget(
        enumClass: Class<V>,
        show: ToggleButtonGroupWidgetSpec.Show,
        items: List<Any>?,
        getFixedIcon: ((Nothing) -> Icon)?,
        dynIcons: Boolean,
        inconsistent: Boolean
    ): Widget<V> = makeToggleButtonGroupWidget(
        show,
        items?.requireIsInstance(enumClass) ?: if (inconsistent) emptyList() else enumClass.enumConstants.asList(),
        getIcon = getFixedIcon ?: if (dynIcons) null else Enum<*>::icon,
        getLabel = ::l10nEnum,
        inconsistent
    )

    private fun makeFormRow(name: String, widget: Widget<*>): FormRow {
        val l10nKey = l10nKey(name)
        val formRow = FormRow(l10n(l10nKey), widget)
        try {
            formRow.notice = Notice(Severity.INFO, l10n("$l10nKey.desc"))
        } catch (_: MissingResourceException) {
        }
        return formRow
    }

    private fun l10nKey(name: String) = "ui.styling." +
            styleClass.simpleName.removeSuffix("Style").replaceFirstChar(Char::lowercase) +
            ".$name"

    fun <T : Style> castToStyle(styleClass: Class<T>): StyleForm<T> {
        if (styleClass == this.styleClass)
            @Suppress("UNCHECKED_CAST")
            return this as StyleForm<T>
        throw ClassCastException("Cannot cast StyleForm<${this.styleClass.name}> to StyleForm<${styleClass.name}>")
    }

    fun getWidgetFor(setting: StyleSetting<S, *>): Widget<*> =
        valueWidgets.getValue(setting)

    override fun open(stored /* style */: S) {
        disableOnChange = true
        try {
            for ((setting, widget) in valueWidgets)
                @Suppress("UNCHECKED_CAST")
                (widget as Widget<Any>).value = setting.get(stored)
        } finally {
            disableOnChange = false
        }
    }

    fun <SUBJ : Any> openSingleSetting(setting: StyleSetting<S, SUBJ>, value: SUBJ) {
        disableOnChange = true
        try {
            @Suppress("UNCHECKED_CAST")
            (valueWidgets.getValue(setting) as Widget<Any>).value = value
        } finally {
            disableOnChange = false
        }
    }

    override fun save(): S =
        newStyleUnsafe(styleClass, getStyleSettings(styleClass).map { setting ->
            val value = valueWidgets[setting]?.value ?: constSettings[setting]!!
            if (value is List<*>) value.toPersistentList() else value
        })

    fun getNestedFormsAndStyles(style: S): Pair<List<StyleForm<*>>, List<NestedStyle>> {
        val nestedForms = mutableListOf<StyleForm<*>>()
        val nestedStyles = mutableListOf<NestedStyle>()
        for (setting in getStyleSettings(styleClass))
            if (NestedStyle::class.java.isAssignableFrom(setting.type))
                when (setting) {
                    is DirectStyleSetting -> {
                        val formWidget = valueWidgets[setting] as NestedFormWidget
                        nestedForms.add(formWidget.form as StyleForm)
                        nestedStyles.add(setting.get(style) as NestedStyle)
                    }
                    is OptStyleSetting -> {
                        val formWidget = (valueWidgets[setting] as OptWidget<*>).wrapped as NestedFormWidget
                        nestedForms.add(formWidget.form as StyleForm)
                        nestedStyles.add(setting.get(style).value as NestedStyle)
                    }
                    is ListStyleSetting -> {
                        val formWidgets = (valueWidgets[setting] as AbstractListWidget<*, *>).elementWidgets
                        for (formWidget in formWidgets)
                            nestedForms.add((formWidget as NestedFormWidget).form as StyleForm)
                        for (nestedStyle in setting.get(style))
                            nestedStyles.add(nestedStyle as NestedStyle)
                    }
                }
        return Pair(nestedForms, nestedStyles)
    }

    var hiddenSettings: Set<StyleSetting<S, *>> = emptySet()
        set(value) {
            field = value
            updateVisibleAndEnabled()
        }

    var ineffectiveSettings: Map<StyleSetting<S, *>, Effectivity> = emptyMap()
        set(value) {
            field = value
            updateVisibleAndEnabled()
        }

    private fun updateVisibleAndEnabled() {
        fun status(effectivity: Effectivity) = when (effectivity) {
            Effectivity.TOTALLY_INEFFECTIVE -> 2
            Effectivity.ALMOST_EFFECTIVE -> 1
            Effectivity.EFFECTIVE -> 0
        }

        for ((formRow, settings) in rootFormRows) {
            // First find the minimum status (2 invisible, 1 disabled, 0 regular) across all widgets in the form row
            // (there can be multiple when a UnionWidget is used). Apply that minimum status to the row's label and
            // notice area.
            val rowStatus = settings.minOf { st ->
                if (st in hiddenSettings) 2
                else status(ineffectiveSettings.getOrDefault(st, Effectivity.EFFECTIVE))
            }
            formRow.setAffixVisible(rowStatus != 2)
            formRow.setAffixEnabled(rowStatus != 1)
            // For each widget individually, find its status and apply it. Separating this from the row status is
            // relevant for UnionWidgets.
            for (setting in settings) {
                val settingStatus =
                    if (setting in hiddenSettings) 2
                    else status(ineffectiveSettings.getOrDefault(setting, Effectivity.EFFECTIVE))
                val widget = valueWidgets.getValue(setting)
                widget.isVisible = settingStatus != 2
                widget.isEnabled = settingStatus != 1
            }
        }
    }

    fun clearIssues() {
        for ((formRow, _) in rootFormRows)
            formRow.noticeOverride = null
        for (widget in valueWidgets.values)
            widget.setSeverity(-1, null)
    }

    fun showIssueIfMoreSevere(setting: StyleSetting<*, *>, subjectIndex: Int, issue: Notice) {
        // Only show the notice message if there isn't already a notice with the same or a higher severity.
        val formRow = rootFormRowLookup[setting]!!
        val prevNoticeOverride = formRow.noticeOverride
        if (prevNoticeOverride == null || issue.severity > prevNoticeOverride.severity)
            formRow.noticeOverride = issue
        // Only increase and not decrease the severity on the widget.
        val widget = valueWidgets[setting]!!
        val prevSeverity = widget.getSeverity(subjectIndex)
        if (prevSeverity == null || issue.severity > prevSeverity)
            widget.setSeverity(subjectIndex, issue.severity)
    }

    fun setSwatchColors(swatchColors: List<Color>) {
        val configurator = { w: Widget<*> -> if (w is ColorWellWidget) w.swatchColors = swatchColors }
        for (widget in valueWidgets.values)
            widget.applyConfigurator(configurator)
    }

    fun setProjectFontFamilies(projectFamilies: FontFamilies) {
        val configurator = { w: Widget<*> -> if (w is FontChooserWidget) w.projectFamilies = projectFamilies }
        for (widget in valueWidgets.values)
            widget.applyConfigurator(configurator)
    }

    fun setChoices(setting: StyleSetting<*, *>, choices: List<Any>, unique: Boolean = false) {
        val remaining = if (unique) choices.toMutableList() else choices
        valueWidgets[setting]!!.applyConfigurator { widget ->
            if (widget is Choice<*>) {
                @Suppress("UNCHECKED_CAST")
                (widget as Choice<Any>).items = remaining
                if (unique)
                    (remaining as MutableList).remove(widget.value)
            }
        }
    }

    fun setToStringFun(setting: StyleSetting<*, *>, toString: ((Nothing) -> String)) {
        valueWidgets[setting]!!.applyConfigurator { widget ->
            if (widget is ComboBoxWidget<*>)
                @Suppress("UNCHECKED_CAST")
                (widget as ComboBoxWidget<Nothing>).toString = toString
            if (widget is MultiComboBoxWidget<*>)
                @Suppress("UNCHECKED_CAST")
                (widget as MultiComboBoxWidget<Nothing>).toString = toString
        }
    }

    fun setToIconFun(setting: StyleSetting<S, *>, toIcon: ((Nothing) -> Icon)?) {
        valueWidgets.getValue(setting).applyConfigurator { widget ->
            if (widget is ToggleButtonGroupWidget)
                @Suppress("UNCHECKED_CAST")
                (widget as ToggleButtonGroupWidget<Nothing>).toIcon = toIcon
        }
    }

    fun setMultiplier(setting: StyleSetting<S, *>, multiplier: Double) {
        valueWidgets.getValue(setting).applyConfigurator { widget ->
            if (widget is MultipliedSpinnerWidget)
                widget.multiplier = multiplier
        }
    }

    fun setTimecodeFPSAndFormat(setting: StyleSetting<S, *>, fps: FPS, timecodeFormat: TimecodeFormat) {
        valueWidgets.getValue(setting).applyConfigurator { widget ->
            if (widget is TimecodeWidget) {
                widget.fps = fps
                widget.timecodeFormat = timecodeFormat
            }
        }
    }

    override fun onChange(widget: Widget<*>) {
        if (!disableOnChange)
            super.onChange(widget)
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

        private fun l10nEnum(enumConst: Enum<*>) =
            l10n("project.${enumConst.javaClass.simpleName}.${enumConst.name}")

    }

}
