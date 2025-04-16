package com.loadingbyte.cinecred.imaging

import kotlin.math.abs
import kotlin.math.pow


data class Transition(
    val ctrl1X: Double,
    val ctrl1Y: Double,
    val ctrl2X: Double,
    val ctrl2Y: Double
) {

    companion object {
        val LINEAR = Transition(0.0, 0.0, 1.0, 1.0)
    }

    fun y(x: Double): Double =
        eval(t(x), ctrl1Y, ctrl2Y)

    fun yIntegral(x: Double): Double {
        val t = t(x)
        // The following code computes the definite integral of y(t) x'(t) dt between 0 and the t from above.
        val x1 = ctrl1X
        val y1 = ctrl1Y
        val x2 = ctrl2X
        val y2 = ctrl2Y
        val x1y1 = x1 * y1
        val x2y1 = x2 * y1
        val x1y2 = x1 * y2
        val x2y2 = x2 * y2
        return t.pow(6) * (9 * (x1y1 - x2y1 - x1y2 + x2y2) + 3 * (x1 - x2 + y1 - y2) + 1) / 2 +
                t.pow(5) * (-90 * x1y1 + 72 * x2y1 + 63 * x1y2 - 45 * x2y2 - 12 * x1 + 6 * x2 - 18 * y1 + 9 * y2) / 5 +
                t.pow(4) * (108 * x1y1 - 63 * x2y1 - 45 * x1y2 + 18 * x2y2 + 3 * x1 + 9 * y1) / 4 +
                t.pow(3) * (-18 * x1y1 + 6 * x2y1 + 3 * x1y2) +
                t.pow(2) * (9 * x1y1) / 2
    }

    private fun t(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        var low = 0.0
        var high = 1.0
        while (true) {
            val mid = 0.5 * (low + high)
            val est = eval(mid, ctrl1X, ctrl2X)
            when {
                abs(x - est) < 0.0005 -> return mid
                est < x -> low = mid
                else -> high = mid
            }
        }
    }

    private fun eval(t: Double, ctrl1: Double, ctrl2: Double): Double {
        val tInv = 1 - t
        val tSq = t * t
        val b1 = 3 * t * tInv * tInv
        val b2 = 3 * tSq * tInv
        val b3 = tSq * t
        return b1 * ctrl1 + b2 * ctrl2 + b3
    }

}
