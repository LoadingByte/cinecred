package com.loadingbyte.cinecred.common


inline fun <E> MutableList<E>.removeFirstOrNull(predicate: (E) -> Boolean): E? {
    val iter = iterator()
    for (elem in iter)
        if (predicate(elem)) {
            iter.remove()
            return elem
        }
    return null
}


inline fun <reified E> List<Any>.requireIsInstance() = requireIsInstance(E::class.java)

fun <E> List<Any>.requireIsInstance(clazz: Class<E>): List<E> {
    for (elem in this)
        if (!clazz.isAssignableFrom(elem.javaClass))
            throw IllegalArgumentException("Element of wrong type found in $this.")
    @Suppress("UNCHECKED_CAST")
    return this as List<E>
}


inline fun <K, I, O, M : MutableMap<in K, in O>> Map<K, I>.associateWithTo(
    destination: M,
    valueSelector: (K, I) -> O
): M {
    for ((key, value) in this)
        destination[key] = valueSelector(key, value)
    return destination
}
