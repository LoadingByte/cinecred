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
import javax.swing.SpinnerNumberModel


class StyleForm<S : Style>(private val styleClass: Class<S>) : Form() {

    private var changeListener: (() -> Unit)? = null

    // It is important that this map preserves insertion order.
    private val settingWidgets = LinkedHashMap<StyleSetting<S, *>, Widget<*>>()

    private var disableRefresh = false

    init {
        for (setting in getStyleSettings(styleClass))
            addSettingWidget(setting, makeSettingWidget(setting))
    }

    private fun <V> makeSettingWidget(setting: StyleSetting<S, V>): Widget<*> {
        val settingMeta = getStyleMeta(styleClass).filter { m -> setting in m.settings }
        val settingGenericArg = setting.genericArg

        val settingWidget = when (setting.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                val inequality = settingMeta.oneOf<NumberConstr<S>>()?.inequality
                val min = if (inequality == LARGER_0) 1 else if (inequality == LARGER_OR_EQUAL_0) 0 else null
                val step = settingMeta.oneOf<NumberStepWidgetSpec<S, Int>>()?.stepSize ?: 1
                SpinnerWidget(Int::class.javaObjectType, SpinnerNumberModel(min ?: 0, min, null, step))
            }
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> {
                val inequality = settingMeta.oneOf<NumberConstr<S>>()?.inequality
                val min = if (inequality == LARGER_0) 0.01f else if (inequality == LARGER_OR_EQUAL_0) 0f else null
                val step = settingMeta.oneOf<NumberStepWidgetSpec<S, Float>>()?.stepSize ?: 1f
                SpinnerWidget(Float::class.javaObjectType, SpinnerNumberModel(min ?: 0f, min, null, step))
            }
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> CheckBoxWidget()
            String::class.java -> when {
                settingMeta.oneOf<DynChoiceConstr<S, *>>() != null -> InconsistentComboBoxWidget(
                    String::class.java, emptyList()
                )
                settingMeta.oneOf<FontNameConstr<S>>() != null -> FontChooserWidget()
                else -> TextWidget(grow = settingMeta.oneOf<DontGrowWidgetSpec<S>>() == null)
            }
            Locale::class.java -> InconsistentComboBoxWidget(
                Locale::class.java, Locale.getAvailableLocales().sortedBy(Locale::getDisplayName),
                toString = Locale::getDisplayName
            )
            Color::class.java -> ColorWellWidget(allowAlpha = settingMeta.oneOf<ColorConstr<S>>()?.allowAlpha ?: true)
            FPS::class.java -> FPSWidget()
            else -> when {
                Enum::class.java.isAssignableFrom(setting.type) -> when {
                    settingMeta.oneOf<DynChoiceConstr<S, *>>() != null -> InconsistentComboBoxWidget(
                        setting.type as Class<*>, emptyList(),
                        toString = { l10nEnum(it as Enum<*>) }
                    )
                    else -> makeEnumCBoxWidget(setting.type as Class<*>)
                }
                ImmutableList::class.java.isAssignableFrom(setting.type) && settingGenericArg != null -> when {
                    String::class.java == settingGenericArg -> TextListWidget(
                        grow = settingMeta.oneOf<DontGrowWidgetSpec<S>>() == null
                    )
                    Enum::class.java.isAssignableFrom(settingGenericArg) -> makeEnumCBoxListWidget(settingGenericArg)
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

    private fun <E> makeEnumCBoxWidget(enumClass: Class<E>) =
        ComboBoxWidget(
            enumClass, enumClass.enumConstants.asList(),
            toString = { l10nEnum(it as Enum<*>) }
        )

    private fun <E> makeEnumCBoxListWidget(enumClass: Class<E>) =
        ComboBoxListWidget(
            enumClass, enumClass.enumConstants.asList(),
            toString = { l10nEnum(it as Enum<*>) }
        )

    private fun addSettingWidget(setting: StyleSetting<S, *>, settingWidget: Widget<*>) {
        val l10nKey = "ui.styling." +
                styleClass.simpleName.removeSuffix("Style").toLowerCase() +
                ".${setting.name}"

        try {
            settingWidget.notice = Notice(Severity.INFO, l10n("$l10nKey.desc"))
        } catch (_: MissingResourceException) {
        }

        addWidget(l10n(l10nKey), settingWidget)
        settingWidgets[setting] = settingWidget
    }

    fun open(style: S, onChange: (() -> Unit)?) {
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
        (settingWidgets[setting]!! as ChoiceWidget<*, Any?>).items = choices
    }

    override fun onChange(widget: Widget<*>) {
        refresh()
        changeListener?.invoke()
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

}
