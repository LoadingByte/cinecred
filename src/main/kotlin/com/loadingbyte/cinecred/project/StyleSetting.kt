package com.loadingbyte.cinecred.project

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
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
                    PersistentList::class.java.isAssignableFrom(field.type) ->
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

fun <S : Style, SUBJ : Any> KProperty1<S, PersistentList<SUBJ>>.st(): ListStyleSetting<S, SUBJ> =
    KProperty1ListStyleSetting(this)


fun <S : Style> S.copy(notarizedSettingValue: NotarizedStyleSettingValue<S>): S =
    copy(listOf(notarizedSettingValue))

fun <S : Style> S.copy(notarizedSettingValues: List<NotarizedStyleSettingValue<S>>): S {
    val settings = getStyleSettings(javaClass)
    val constructorArgs = Array(settings.size) { idx ->
        val setting = settings[idx]
        val notarized = notarizedSettingValues.find { (it as NotarSetImpl).setting == setting }
        if (notarized != null) (notarized as NotarSetImpl).settingValue else setting.get(this)
    }
    return javaClass.cast(javaClass.constructors[0].newInstance(*constructorArgs))
}

fun <S : Style> newStyleUnsafe(styleClass: Class<S>, settingValues: List<*>): S =
    styleClass.cast(styleClass.constructors[0].newInstance(*settingValues.toTypedArray()))


sealed interface NotarizedStyleSettingValue<S : Style>
private class NotarSetImpl<S : Style>(val setting: StyleSetting<S, *>, val settingValue: Any) :
    NotarizedStyleSettingValue<S>


sealed class StyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, val name: String, isNested: Boolean) {

    /** The upmost class/interface in the hierarchy which first defines the setting. */
    val declaringClass: Class<*> = findDeclaringClass(styleClass)!!

    val type: Class<SUBJ>

    init {
        var baseType = styleClass.getGetter(name).genericReturnType
        if (isNested)
            baseType = (baseType as ParameterizedType).actualTypeArguments[0]
        @Suppress("UNCHECKED_CAST")
        type = (if (baseType is ParameterizedType) baseType.rawType else baseType) as Class<SUBJ>
    }

    private fun findDeclaringClass(curClass: Class<*>): Class<*>? {
        for (inter in curClass.interfaces)
            findDeclaringClass(inter)?.let { return it }
        return try {
            curClass.getGetter(name)
            curClass
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    abstract fun get(style: S): Any
    abstract fun extractSubjects(style: S): List<SUBJ>
    abstract fun repackSubjects(subjects: List<SUBJ>): NotarizedStyleSettingValue<S>

    override fun equals(other: Any?) =
        this === other || other is StyleSetting<*, *> && declaringClass == other.declaringClass && name == other.name

    override fun hashCode(): Int {
        var result = declaringClass.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }

    override fun toString() =
        "StyleSetting(${declaringClass.simpleName}.$name: ${type.simpleName})"

}


abstract class DirectStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = false) {
    abstract override fun get(style: S): SUBJ
    fun notarize(settingValue: SUBJ): NotarizedStyleSettingValue<S> = NotarSetImpl(this, settingValue)
    override fun extractSubjects(style: S): List<SUBJ> = listOf(get(style))
    override fun repackSubjects(subjects: List<SUBJ>): NotarizedStyleSettingValue<S> = notarize(subjects.single())
}


abstract class OptStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): Opt<SUBJ>
    fun notarize(settingValue: Opt<SUBJ>): NotarizedStyleSettingValue<S> = NotarSetImpl(this, settingValue)
    override fun extractSubjects(style: S): List<SUBJ> = get(style).run { if (isActive) listOf(value) else emptyList() }
    override fun repackSubjects(subjects: List<SUBJ>): NotarizedStyleSettingValue<S> =
        notarize(Opt(isActive = true, subjects.single()))
}


abstract class ListStyleSetting<S : Style, SUBJ : Any>(styleClass: Class<S>, name: String) :
    StyleSetting<S, SUBJ>(styleClass, name, isNested = true) {
    abstract override fun get(style: S): PersistentList<SUBJ>
    fun notarize(settingValue: PersistentList<SUBJ>): NotarizedStyleSettingValue<S> = NotarSetImpl(this, settingValue)
    override fun extractSubjects(style: S): List<SUBJ> = get(style)
    override fun repackSubjects(subjects: List<SUBJ>): NotarizedStyleSettingValue<S> =
        notarize(subjects.toPersistentList())
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
    override fun get(style: S): PersistentList<Any> =
        (getter.invoke(style) as List<*>).requireNoNulls() as PersistentList<Any>
}


private class KProperty1DirectStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, SUBJ>) :
    DirectStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): SUBJ = kProp.get(style)
}


private class KProperty1OptStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, Opt<SUBJ>>) :
    OptStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): Opt<SUBJ> = kProp.get(style)
}


private class KProperty1ListStyleSetting<S : Style, SUBJ : Any>(private val kProp: KProperty1<S, PersistentList<SUBJ>>) :
    ListStyleSetting<S, SUBJ>(kProp.getOwnerClass(), kProp.name) {
    override fun get(style: S): PersistentList<SUBJ> = kProp.get(style)
}


private fun Class<*>.getGetter(fieldName: String) =
    getDeclaredMethod("get" + fieldName.replaceFirstChar(Char::uppercase))


@Suppress("UNCHECKED_CAST")
private fun <T> KProperty1<T, *>.getOwnerClass() =
    (javaClass.getMethod("getOwner").invoke(this) as KClass<*>).java as Class<T>
