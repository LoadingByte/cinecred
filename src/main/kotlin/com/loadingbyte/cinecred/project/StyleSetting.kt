package com.loadingbyte.cinecred.project

import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1


abstract class StyleSetting<S : Style, V> {

    abstract val styleClass: Class<S>
    abstract val name: String
    abstract val type: Class<V>
    abstract val genericArg: Class<*>?
    abstract fun get(style: S): V

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


fun <S : Style> getStyleSettings(styleClass: Class<S>): List<StyleSetting<S, *>> =
    styleClass.declaredFields.map { field -> ReflectedStyleSetting(styleClass, field.name) }

fun <S : Style, V> toStyleSetting(prop: KProperty1<S, V>): StyleSetting<S, V> = KProperty1StyleSetting(prop)

fun <S : Style> newStyle(styleClass: Class<S>, settingValues: List<*>): S =
    styleClass
        .getDeclaredConstructor(*styleClass.declaredFields.map(Field::getType).toTypedArray())
        .newInstance(*settingValues.toTypedArray())


private class ReflectedStyleSetting<S : Style>(
    override val styleClass: Class<S>,
    override val name: String
) : StyleSetting<S, Any?>() {

    private val getter = styleClass.getDeclaredMethod("get" + name.capitalize(Locale.ROOT))

    @Suppress("UNCHECKED_CAST")
    override val type: Class<Any?> = getter.returnType as Class<Any?>
    override val genericArg: Class<*>? = getGenericArg(getter.genericReturnType)

    override fun get(style: S): Any? = getter.invoke(style)

}


private class KProperty1StyleSetting<S : Style, V>(private val kProp: KProperty1<S, V>) : StyleSetting<S, V>() {

    @Suppress("UNCHECKED_CAST")
    override val styleClass: Class<S> =
        (kProp.javaClass.getMethod("getOwner").invoke(kProp) as KClass<*>).java as Class<S>
    override val name: String = kProp.name
    override val type: Class<V>
    override val genericArg: Class<*>?

    init {
        val field = styleClass.getDeclaredField(name)
        @Suppress("UNCHECKED_CAST")
        type = field.type as Class<V>
        genericArg = getGenericArg(field.genericType)
    }

    override fun get(style: S): V = kProp.get(style)

}


private fun getGenericArg(genericType: Type): Class<*>? =
    if (genericType is ParameterizedType)
        genericType.actualTypeArguments[0] as Class<*>
    else null
