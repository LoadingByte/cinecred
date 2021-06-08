package com.loadingbyte.cinecred.project

import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1


interface StyleSetting<S : Style, V> {
    val styleClass: Class<S>
    val name: String
    val type: Class<V>
    val genericArg: Class<*>?

    fun getPlain(style: S): Any?
    fun getValue(style: S): V
    fun valueToPlain(value: Any?): Any?
}


interface RegularStyleSetting<S : Style, V> : StyleSetting<S, V> {
    override fun getPlain(style: S): V
    override fun valueToPlain(value: Any?): Any? = value
}


interface OptionallyEffectiveStyleSetting<S : Style, V> : StyleSetting<S, V> {
    override fun getPlain(style: S): OptionallyEffective<V>
    override fun valueToPlain(value: Any?): OptionallyEffective<Any?> = OptionallyEffective(true, value)
}


private val settingsCache = HashMap<Class<*>, List<StyleSetting<*, *>>>()

fun <S : Style> getStyleSettings(styleClass: Class<S>): List<StyleSetting<S, *>> {
    val cached = settingsCache[styleClass]
    return if (cached == null)
        styleClass.declaredFields
            .map { field ->
                if (field.type == OptionallyEffective::class.java)
                    ReflectedOptEffStyleSetting(styleClass, field.name)
                else
                    ReflectedRegularStyleSetting(styleClass, field.name)
            }
            .also { settingsCache[styleClass] = it }
    else
        @Suppress("UNCHECKED_CAST")
        cached as List<StyleSetting<S, *>>
}


fun <S : Style, V> KProperty1<S, V>.st(): RegularStyleSetting<S, V> =
    KProperty1RegularStyleSetting(this)

fun <S : Style, V> KProperty1<S, OptionallyEffective<V>>.st(): OptionallyEffectiveStyleSetting<S, V> =
    KProperty1OptEffStyleSetting(this)


fun <S : Style> newStyle(styleClass: Class<S>, settingValues: List<*>): S =
    styleClass
        .getDeclaredConstructor(*styleClass.declaredFields.map(Field::getType).toTypedArray())
        .newInstance(*settingValues.toTypedArray())


private abstract class AbstractStyleSetting<S : Style, V>(
    final override val styleClass: Class<S>,
    final override val name: String
) : StyleSetting<S, V> {

    final override val type: Class<V>
    final override val genericArg: Class<*>?

    init {
        val (type, genericArg) = findTypes(styleClass.getDeclaredField(name).genericType)
        @Suppress("UNCHECKED_CAST")
        this.type = type as Class<V>
        this.genericArg = genericArg
    }

    private fun findTypes(genericType: Type): Pair<Class<*>, Class<*>?> =
        if (genericType is ParameterizedType && genericType.rawType == OptionallyEffective::class.java) {
            findTypes(genericType.actualTypeArguments[0])
        } else {
            if (genericType is ParameterizedType)
                Pair(genericType.rawType as Class<*>, genericType.actualTypeArguments[0] as Class<*>)
            else
                Pair(genericType as Class<*>, null)
        }

    override fun equals(other: Any?) =
        this === other || other is StyleSetting<*, *> && styleClass == other.styleClass && name == other.name

    override fun hashCode(): Int {
        var result = styleClass.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }

    override fun toString(): String {
        val genericArgStr = genericArg?.let { "<${it.simpleName}>" } ?: ""
        return "StyleSetting(${styleClass.simpleName}.$name: ${type.simpleName}$genericArgStr)"
    }

}


private class ReflectedRegularStyleSetting<S : Style>(
    styleClass: Class<S>,
    name: String
) : AbstractStyleSetting<S, Any?>(styleClass, name), RegularStyleSetting<S, Any?> {

    private val getter = styleClass.getDeclaredMethod("get" + name.capitalize(Locale.ROOT))

    override fun getPlain(style: S): Any? = getter.invoke(style)
    override fun getValue(style: S): Any? = getPlain(style)

}


private class ReflectedOptEffStyleSetting<S : Style>(
    styleClass: Class<S>,
    name: String
) : AbstractStyleSetting<S, Any?>(styleClass, name), OptionallyEffectiveStyleSetting<S, Any?> {

    private val getter = styleClass.getDeclaredMethod("get" + name.capitalize(Locale.ROOT))

    override fun getPlain(style: S) = getter.invoke(style) as OptionallyEffective<Any?>
    override fun getValue(style: S): Any? = getPlain(style).value

}


@Suppress("UNCHECKED_CAST")
private class KProperty1RegularStyleSetting<S : Style, V>(
    private val kProp: KProperty1<S, V>
) : AbstractStyleSetting<S, V>(
    styleClass = (kProp.javaClass.getMethod("getOwner").invoke(kProp) as KClass<*>).java as Class<S>,
    name = kProp.name
), RegularStyleSetting<S, V> {

    override fun getPlain(style: S): V = kProp.get(style)
    override fun getValue(style: S): V = getPlain(style)

}


@Suppress("UNCHECKED_CAST")
private class KProperty1OptEffStyleSetting<S : Style, V>(
    private val kProp: KProperty1<S, OptionallyEffective<V>>
) : AbstractStyleSetting<S, V>(
    styleClass = (kProp.javaClass.getMethod("getOwner").invoke(kProp) as KClass<*>).java as Class<S>,
    name = kProp.name
), OptionallyEffectiveStyleSetting<S, V> {

    override fun getPlain(style: S): OptionallyEffective<V> = kProp.get(style)
    override fun getValue(style: S): V = getPlain(style).value

}
