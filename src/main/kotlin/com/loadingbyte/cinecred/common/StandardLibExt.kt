package com.loadingbyte.cinecred.common


/*
 * This file contains very slight variations of Kotlin standard library functions. As a rule of thumb, if a function
 * should probably be in the standard library but isn't, it tends to belong in this file.
 */


inline fun FloatArray.allBetween(startIdx: Int, endIdx: Int, predicate: (Float) -> Boolean): Boolean {
    for (idx in startIdx..<endIdx)
        if (!predicate(this[idx]))
            return false
    return true
}


fun BooleanArray.indexOfAfter(elem: Boolean, startIdx: Int): Int {
    for (idx in startIdx..<size) {
        if (this[idx] == elem) {
            return idx
        }
    }
    return -1
}


inline fun <E> Collection<E>.maxOfOr(default: Double, selector: (E) -> Double): Double =
    if (isEmpty()) default else maxOf(selector)


fun IntArray.sumBetween(startIdx: Int, endIdx: Int): Int {
    var sum = 0
    for (idx in startIdx..<endIdx)
        sum += this[idx]
    return sum
}


inline fun List<Int>.mapToIntArray(transform: (Int) -> Int): IntArray =
    IntArray(size) { i -> transform(this[i]) }

inline fun List<Double>.mapToDoubleArray(transform: (Double) -> Double): DoubleArray =
    DoubleArray(size) { i -> transform(this[i]) }


inline fun <R> IntArray.flatMapToSequence(crossinline transform: (Int) -> Iterable<R>): Sequence<R> =
    sequence { for (e in this@flatMapToSequence) yieldAll(transform(e)) }


inline fun DoubleArray.mapToArray(transform: (Double) -> Double): DoubleArray =
    DoubleArray(size) { i -> transform(this[i]) }


inline fun <E> MutableList<E>.removeFirstOrNull(predicate: (E) -> Boolean): E? {
    val iter = iterator()
    for (elem in iter)
        if (predicate(elem)) {
            iter.remove()
            return elem
        }
    return null
}


inline fun <reified E> List<*>.requireIsInstance() = requireIsInstance(E::class.java)

fun <E> List<*>.requireIsInstance(clazz: Class<E>): List<E> {
    for (elem in this)
        if (elem == null || !clazz.isAssignableFrom(elem.javaClass))
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
