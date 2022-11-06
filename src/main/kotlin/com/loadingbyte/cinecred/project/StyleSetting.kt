package com.loadingbyte.cinecred.project

import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1


private val settingsCache = HashMap<Class<*>, List<StyleSetting<*, *>>>()

fun <S : Style> getStyleSettings(styleClass: Class<S>): List<StyleSetting<S, *>> {
    val cached = settingsCache[styleClass]
    return if (cached == null)
        styleClass.declaredFields
            .map { field ->
                when {
                    Opt::class.java == field.type ->
                        ReflectedOptStyleSetting(styleClass, field.name)
                    List::class.java.isAssignableFrom(field.type) ->
                        ReflectedListStyleSetting(styleClass, field.name)
                    else ->
                        ReflectedDirectStyleSetting(styleClass, field.name)
                }
            }
            .also { settingsCache[styleClass] = it }
    else
        @Suppress("UNCHECKED_CAST")
        cached as List<StyleSetting<S, *>>
}


fun <S : Style, V : Any> KProperty1<S, V>.st(): DirectStyleSetting<S, V> =
    KProperty1DirectStyleSetting(this)

fun <S : Style, V : Any> KProperty1<S, Opt<V>>.st(): OptStyleSetting<S, V> =
    KProperty1OptStyleSetting(this)

fun <S : Style, V : Any> KProperty1<S, List<V>>.st(): ListStyleSetting<S, V> =
    KProperty1ListStyleSetting(this)


fun <S : Style> newStyle(styleClass: Class<S>, settingValues: List<*>): S =
    styleClass
        .getDeclaredConstructor(*styleClass.declaredFields.map(Field::getType).toTypedArray())
        .newInstance(*settingValues.toTypedArray())


sealed class StyleSetting<S : Style, V : Any>(val styleClass: Class<S>, val name: String, isNested: Boolean) {

    val type: Class<V>

    init {
        var baseType = styleClass.getDeclaredField(name).genericType
        if (isNested)
            baseType = (baseType as ParameterizedType).actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        type = (if (baseType is ParameterizedType) baseType.rawType else baseType) as Class<V>
    }

    abstract fun get(style: S): Any
    abstract fun extractValues(style: S): List<V>

    override fun equals(other: Any?) =
        this === other || other is StyleSetting<*, *> && styleClass == other.styleClass && name == other.name

    override fun hashCode(): Int {
        var result = styleClass.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }

    override fun toString() =
        "StyleSetting(${styleClass.simpleName}.$name: ${type.simpleName})"

}


abstract class DirectStyleSetting<S : Style, V : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, V>(styleClass, name, isNested = false) {
    abstract override fun get(style: S): V
    override fun extractValues(style: S): List<V> = listOf(get(style))
}


abstract class OptStyleSetting<S : Style, V : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, V>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): Opt<V>
    override fun extractValues(style: S): List<V> = get(style).run { if (isActive) listOf(value) else emptyList() }
}


abstract class ListStyleSetting<S : Style, V : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, V>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): List<V>
    override fun extractValues(style: S): List<V> = get(style)
}


private class ReflectedDirectStyleSetting<S : Style>(styleClass: Class<S>, name: String) :
    DirectStyleSetting<S, Any>(styleClass, name) {
    private val getter = styleClass.getGetter(name)
    override fun get(style: S): Any = getter.invoke(style)
}


private class ReflectedOptStyleSetting<S : Style>(styleClass: Class<S>, name: String) :
    OptStyleSetting<S, Any>(styleClass, name) {
    private val getter = styleClass.getGetter(name)
    override fun get(style: S): Opt<Any> = getter.invoke(style) as Opt<Any>
}


private class ReflectedListStyleSetting<S : Style>(styleClass: Class<S>, name: String) :
    ListStyleSetting<S, Any>(styleClass, name) {
    private val getter = styleClass.getGetter(name)
    override fun get(style: S): List<Any> = (getter.invoke(style) as List<*>).requireNoNulls()
}


private class KProperty1DirectStyleSetting<S : Style, V : Any>(private val kProp: KProperty1<S, V>) :
    DirectStyleSetting<S, V>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): V = kProp.get(style)
}


private class KProperty1OptStyleSetting<S : Style, V : Any>(private val kProp: KProperty1<S, Opt<V>>) :
    OptStyleSetting<S, V>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): Opt<V> = kProp.get(style)
}


private class KProperty1ListStyleSetting<S : Style, V : Any>(private val kProp: KProperty1<S, List<V>>) :
    ListStyleSetting<S, V>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): List<V> = kProp.get(style)
}


private fun Class<*>.getGetter(fieldName: String) =
    getDeclaredMethod("get" + fieldName.replaceFirstChar(Char::uppercase))


@Suppress("UNCHECKED_CAST")
private fun <T> KProperty1<T, *>.getOwnerClass() =
    (javaClass.getMethod("getOwner").invoke(this) as KClass<*>).java as Class<T>
