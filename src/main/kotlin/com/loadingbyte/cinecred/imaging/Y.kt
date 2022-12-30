package com.loadingbyte.cinecred.imaging

import com.loadingbyte.cinecred.common.mapToArray
import kotlin.math.abs
import kotlin.math.max


// Implementation note: The arrays ss and ls store the x and y coordinates respectively of a piecewise linear function.
class Y private constructor(private val ss: DoubleArray, private val ls: DoubleArray) {

    fun resolve(elasticScaling: Double = 1.0): Double {
        require(elasticScaling >= 0.0)
        val iLeft = when {
            elasticScaling <= ss.first() -> 0
            elasticScaling >= ss.last() -> ss.lastIndex - 1
            else -> ss.indexOfLast { it <= elasticScaling }
        }
        return resolveAfter(iLeft, elasticScaling)
    }

    /**
     * Returns the elastic scaling value that most closely produces [length] when put through [resolve].
     * If multiple elastic scaling values produce the same length, the largest one is returned.
     * If the Y graph is flat, 1 is returned.
     */
    fun deresolve(length: Double): Double {
        val iBelow = when {
            length <= ls.first() -> 0
            length >= ls.last() -> ls.lastIndex - 1
            else -> ls.indexOfLast { it <= length }
        }
        val slope = slopeAfter(iBelow)
        return if (abs(slope) <= EPS) ss[iBelow + 1] else ((length - biasAfter(iBelow)) / slope).coerceAtLeast(0.0)
    }

    private fun resolveAfter(i: Int, s: Double): Double {
        require(s >= 0.0)
        return slopeAfter(i) * s + biasAfter(i)
    }

    private fun slopeAfter(i: Int): Double {
        val iEff = i.coerceIn(0, ss.lastIndex - 1)
        return (ls[iEff + 1] - ls[iEff]) / (ss[iEff + 1] - ss[iEff])
    }

    private fun biasAfter(i: Int): Double {
        val iEff = i.coerceIn(0, ss.lastIndex - 1)
        return ls[iEff] - slopeAfter(i) * ss[iEff]
    }

    operator fun plus(other: Y): Y = add(this, other, 1.0)
    operator fun minus(other: Y): Y = add(this, other, -1.0)

    operator fun plus(rigid: Double): Y = Y(ss, ls.mapToArray { it + rigid })
    operator fun minus(rigid: Double): Y = Y(ss, ls.mapToArray { it - rigid })

    /** scale universe */
    operator fun times(universeScaling: Double): Y = Y(ss, ls.mapToArray { it * universeScaling })
    /** scale universe */
    operator fun div(universeScaling: Double): Y = Y(ss, ls.mapToArray { it / universeScaling })
    /** scale universe */
    operator fun unaryMinus(): Y = times(-1.0)

    fun scaleElastic(elasticScaling: Double): Y {
        require(elasticScaling >= 0.0)  // One can't remove more elastic space than there is.
        return when {
            elasticScaling < EPS -> resolve(0.0).toY()
            else -> Y(ss.mapToArray { it / elasticScaling }, ls)
        }
    }

    fun max(other: Y): Y = max(this, other)

    override fun equals(other: Any?) =
        this === other || other is Y && ss.contentEquals(other.ss) && ls.contentEquals(other.ls)

    override fun hashCode(): Int {
        var result = ss.contentHashCode()
        result = 31 * result + ls.contentHashCode()
        return result
    }


    companion object {

        private const val EPS = 0.001
        private val ARRAY_01 = doubleArrayOf(0.0, 1.0)

        fun Double.toY(): Y = Y(ARRAY_01, doubleArrayOf(this, this))
        fun Double.toElasticY(): Y = Y(ARRAY_01, doubleArrayOf(0.0, this))

        operator fun Double.plus(y: Y): Y = y + this
        operator fun Double.minus(y: Y): Y = -y + this
        operator fun Double.times(y: Y): Y = y * this

        private fun add(y1: Y, y2: Y, mulSnd: Double): Y {
            val maxNumPoints = y1.ss.size + y2.ss.size
            val yoSS = DoubleArray(maxNumPoints)
            val yoLS = DoubleArray(maxNumPoints)

            var io = 0
            forEachCtrlPoint(y1, y2) { _, s, l1, l2, _, _ ->
                yoSS[io] = s
                yoLS[io] = l1 + mulSnd * l2
                io++
            }

            return Y(yoSS.copyOf(io), yoLS.copyOf(io))
        }

        private fun max(y1: Y, y2: Y): Y {
            val estNumPoints = y1.ss.size + y2.ss.size
            var yoSS = DoubleArray(estNumPoints)
            var yoLS = DoubleArray(estNumPoints)

            var io = 0

            fun expandYoArraysIfNecessary() {
                if (io == yoSS.size) {
                    val newNumPoints = yoSS.size * 2
                    yoSS = yoSS.copyOf(newNumPoints)
                    yoLS = yoLS.copyOf(newNumPoints)
                }
            }

            var prevTop: Y? = null
            forEachCtrlPoint(y1, y2) { source, s, l1, l2, i1, i2 ->
                val currTop = if (l1 > l2) y1 else if (l2 > l1) y2 else null

                // If the two line segments between the previous and the current control point intersect,
                // output a control point where they intersect.
                if (prevTop != null && currTop != null && prevTop !== currTop) {
                    expandYoArraysIfNecessary()
                    val sIntersect = intersectAfter(y1, y2, i1 - 1, i2 - 1)
                    yoSS[io] = sIntersect
                    yoLS[io] = y1.resolveAfter(i1 - 1, sIntersect)  // y1(s) == y2(s) at the intersection point
                    io++
                }

                // If the current control point is identical for y1 and y2 or if it is on top of the other Y's graph,
                // output a control point here.
                if (source == null || source === currTop) {
                    expandYoArraysIfNecessary()
                    yoSS[io] = s
                    yoLS[io] = max(l1, l2)
                    io++
                }

                prevTop = currTop
            }

            // In case the two graphs intersect for a final time at the rightmost control point respectively even
            // more far right than the rightmost control point, output one respectively two control points that
            // realize this final intersection.
            val i1Last = y1.ss.lastIndex
            val i2Last = y2.ss.lastIndex
            val sLast = yoSS[io - 1]
            val sIntersect = intersectAfter(y1, y2, i1Last, i2Last)
            if (!sIntersect.isNaN() && sIntersect >= sLast) {
                // If we have already outputted a control point at the intersection point, don't do that again.
                if (sIntersect > sLast) {
                    expandYoArraysIfNecessary()
                    yoSS[io] = sIntersect
                    yoLS[io] = y1.resolveAfter(i1Last, sIntersect)  // y1(s) == y2(s) at the intersection point
                    io++
                }
                expandYoArraysIfNecessary()
                yoSS[io] = sIntersect + 1.0
                yoLS[io] = max(y1.resolveAfter(i1Last, sIntersect + 1.0), y2.resolveAfter(i2Last, sIntersect + 1.0))
                io++
            }

            return Y(yoSS.copyOf(io), yoLS.copyOf(io))
        }

        private fun intersectAfter(y1: Y, y2: Y, i1: Int, i2: Int): Double {
            val slope1 = y1.slopeAfter(i1)
            val slope2 = y2.slopeAfter(i2)
            if (abs(slope1 - slope2) < EPS)
                return Double.NaN
            return (y2.biasAfter(i2) - y1.biasAfter(i1)) / (slope1 - slope2)
        }

        private inline fun forEachCtrlPoint(
            y1: Y,
            y2: Y,
            block: (source: Y?, s: Double, l1: Double, l2: Double, i1: Int, i2: Int) -> Unit
        ) {
            var i1 = 0
            var i2 = 0
            while (i1 < y1.ss.size || i2 < y2.ss.size) {
                // Arguments for the block function.
                val source: Y?
                val s: Double
                val l1: Double
                val l2: Double
                // Capture these two because we will modify them in a moment.
                val blockI1 = i1
                val blockI2 = i2

                val s1 = if (i1 < y1.ss.size) y1.ss[i1] else Double.POSITIVE_INFINITY
                val s2 = if (i2 < y2.ss.size) y2.ss[i2] else Double.POSITIVE_INFINITY
                when {
                    s1 == s2 -> {
                        source = null // both
                        s = s1
                        l1 = y1.ls[i1]
                        l2 = y2.ls[i2]
                        i1++
                        i2++
                    }
                    s1 < s2 -> {
                        source = y1
                        s = s1
                        l1 = y1.ls[i1]
                        l2 = y2.resolveAfter(i2 - 1, s)
                        i1++
                    }
                    s2 < s1 -> {
                        source = y2
                        s = s2
                        l1 = y1.resolveAfter(i1 - 1, s)
                        l2 = y2.ls[i2]
                        i2++
                    }
                    else -> throw IllegalStateException("Either $s1 or $s2 is not a legal S coordinate.")
                }

                block(source, s, l1, l2, blockI1, blockI2)
            }
        }

    }

}
