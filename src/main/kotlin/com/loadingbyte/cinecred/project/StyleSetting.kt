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


fun <S : Style, SUBJ : Any> KProperty1<S, SUBJ>.st(): DirectStyleSetting<S, SUBJ> =
    KProperty1DirectStyleSetting(this)

fun <S : Style, SUBJ : Any> KProperty1<S, Opt<SUBJ>>.st(): OptStyleSetting<S, SUBJ> =
    KProperty1OptStyleSetting(this)

fun <S : Style, SUBJ : Any> KProperty1<S, List<SUBJ>>.st(): ListStyleSetting<S, SUBJ> =
    KProperty1ListStyleSetting(this)


fun <S : Style> newStyle(styleClass: Class<S>, settingValues: List<*>): S =
    styleClass
        .getDeclaredConstructor(*styleClass.declaredFields.map(Field::getType).toTypedArray())
        .newInstance(*settingValues.toTypedArray())


sealed class StyleSetting<S : Style, SUBJ : Any>(val styleClass: Class<S>, val name: String, isNested: Boolean) {

    val type: Class<SUBJ>

    init {
        var baseType = styleClass.getDeclaredField(name).genericType
        if (isNested)
            baseType = (baseType as ParameterizedType).actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        type = (if (baseType is ParameterizedType) baseType.rawType else baseType) as Class<SUBJ>
    }

    abstract fun get(style: S): Any
    abstract fun extractSubjects(style: S): List<SUBJ>

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


abstract class DirectStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = false) {
    abstract override fun get(style: S): SUBJ
    override fun extractSubjects(style: S): List<SUBJ> = listOf(get(style))
}


abstract class OptStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): Opt<SUBJ>
    override fun extractSubjects(style: S): List<SUBJ> = get(style).run { if (isActive) listOf(value) else emptyList() }
}


abstract class ListStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): List<SUBJ>
    override fun extractSubjects(style: S): List<SUBJ> = get(style)
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


private class KProperty1DirectStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, SUBJ>) :
    DirectStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): SUBJ = kProp.get(style)
}


private class KProperty1OptStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, Opt<SUBJ>>) :
    OptStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): Opt<SUBJ> = kProp.get(style)
}


private class KProperty1ListStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, List<SUBJ>>) :
    ListStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): List<SUBJ> = kProp.get(style)
}


private fun Class<*>.getGetter(fieldName: String) =
    getDeclaredMethod("get" + fieldName.replaceFirstChar(Char::uppercase))


@Suppress("UNCHECKED_CAST")
private fun <T> KProperty1<T, *>.getOwnerClass() =
    (javaClass.getMethod("getOwner").invoke(this) as KClass<*>).java as Class<T>
