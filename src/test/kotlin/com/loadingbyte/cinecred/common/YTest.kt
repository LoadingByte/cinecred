package com.loadingbyte.cinecred.common

import com.loadingbyte.cinecred.common.Y.Companion.minus
import com.loadingbyte.cinecred.common.Y.Companion.plus
import com.loadingbyte.cinecred.common.Y.Companion.toElasticY
import com.loadingbyte.cinecred.common.Y.Companion.toY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource


internal class YTest {

    private fun doTestResolve(y: Y, ss: DoubleArray, lsExp: DoubleArray) {
        assert(ss.size == lsExp.size)  // Catch errors made by the test author.
        for (i in ss.indices) {
            val s = ss[i]
            val lExp = lsExp[i]
            val lAct = y.resolve(s)
            assertEquals(lExp, lAct, 0.001, "y.resolve($s) = $lAct, but should be $lExp.")
        }
    }

    private fun doTestDeresolve(y: Y, ls: DoubleArray, ssExp: DoubleArray) {
        assert(ls.size == ssExp.size)  // Catch errors made by the test author.
        for (i in ls.indices) {
            val l = ls[i]
            val sExp = ssExp[i]
            val sAct = y.deresolve(l)
            assertEquals(sExp, sAct, 0.001, "y.deresolve($l) = $sAct, but should be $sExp.")
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `resolve cases`(makeY: () -> Y, ss: DoubleArray, lsExp: DoubleArray) {
        val y = makeY()
        doTestResolve(y, ss, lsExp)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `deresolve cases`(makeY: () -> Y, ssExp: DoubleArray, ls: DoubleArray) {
        // If multiple elasticScalings yield them same length, deresolve() is ambiguous.
        // In these cases, this method can't run a meaningful test.
        if (ls.toMutableSet().size != ls.size)
            return
        val y = makeY()
        doTestDeresolve(y, ls, ssExp)
    }

    fun cases() = listOf(
        arguments(
            { 0.0.toY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        ),
        arguments(
            { 10.0.toY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(10.0, 10.0, 10.0, 10.0)
        ),
        arguments(
            { 0.0 - 10.0.toY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(-10.0, -10.0, -10.0, -10.0)
        ),
        arguments(
            { 10.0.toElasticY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(0.0, 5.0, 10.0, 15.0)
        ),
        arguments(
            { 0.0 - 10.0.toElasticY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(0.0, -5.0, -10.0, -15.0)
        ),
        arguments(
            { 7.0 + 10.0.toElasticY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(7.0, 12.0, 17.0, 22.0)
        ),
        arguments(
            { 7.0 + 10.0.toElasticY() + 5.0.toElasticY() },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(7.0, 14.5, 22.0, 29.5)
        ),
        arguments(
            { (7.0 + 10.0.toElasticY()) / 2.0 },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(3.5, 6.0, 8.5, 11.0)
        ),
        arguments(
            { (7.0 + 10.0.toElasticY()).scaleElastic(0.5) },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0, 3.0),
            doubleArrayOf(7.0, 9.5, 12.0, 14.5, 17.0, 22.0)
        ),
        arguments(
            { (7.0 + 10.0.toElasticY()).scaleElastic(0.0) },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(7.0, 7.0, 7.0, 7.0)
        ),
        arguments(
            { 0.0.toY().max(10.0.toElasticY()) },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(0.0, 5.0, 10.0, 15.0)
        ),
        arguments(
            // Two parallel lines
            { 5.0.toElasticY().max(10.0 + 5.0.toElasticY()) },
            doubleArrayOf(0.0, 0.5, 1.0, 1.5),
            doubleArrayOf(10.0, 12.5, 15.0, 17.5)
        ),
        arguments(
            // The two lines meet at s=0.5. Left to that point, the first line is above the second line.
            // From that point on, the second one is above the first.
            { (10.0 + 5.0.toElasticY()).max(25.0.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 1.0, 1.5),
            doubleArrayOf(10.0, 11.25, 12.0, 12.5, 18.75, 25.0, 37.5)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=1, where a control point lies.
            { (10.0 + 5.0.toElasticY()).max(15.0.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 1.0, 1.5),
            doubleArrayOf(10.0, 11.25, 12.0, 12.5, 13.75, 15.0, 22.5)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=2, after both control points.
            { (10.0 + 5.0.toElasticY()).max(10.0.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 1.0, 1.5, 2.0, 2.5),
            doubleArrayOf(10.0, 11.25, 12.0, 12.5, 13.75, 15.0, 17.5, 20.0, 25.0)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=4, after both control points.
            { (10.0 + 5.0.toElasticY()).max(7.5.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 1.0, 3.0, 4.0, 5.0),
            doubleArrayOf(10.0, 11.25, 12.0, 12.5, 13.75, 15.0, 25.0, 30.0, 37.5)
        ),
        arguments(
            // Add a line to a Y that has 3 control points.
            { (10.0 + 5.0.toElasticY()).max(25.0.toElasticY()) + (5.0 + 2.5.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 1.0, 1.5),
            doubleArrayOf(15.0, 16.875, 18.0, 18.75, 25.625, 32.5, 46.25)
        ),
        arguments(
            // Max a line with a Y that has 3 control points.
            { (10.0 + 5.0.toElasticY()).max(25.0.toElasticY()).max(4.0 + 20.0.toElasticY()) },
            doubleArrayOf(0.0, 0.25, 0.4, 0.5, 0.75, 0.8, 1.0, 1.5),
            doubleArrayOf(10.0, 11.25, 12.0, 14.0, 19.0, 20.0, 25.0, 37.5)
        ),
        arguments(
            // Very complex example with a lot of maxing.
            {
                (10.0 + 5.0.toElasticY())
                    .max(25.0.toElasticY())
                    .max(4.0 + 20.0.toElasticY())
                    .max((15.0 + (-15.0).toElasticY()).max(16.5 + (-30.0).toElasticY()))
            },
            doubleArrayOf(0.0, 0.05, 0.1, 0.2, 0.25, 0.3, 0.4, 0.5, 0.75, 0.8, 1.0, 1.5),
            doubleArrayOf(16.5, 15.0, 13.5, 12.0, 11.25, 11.5, 12.0, 14.0, 19.0, 20.0, 25.0, 37.5)
        ),
        arguments(
            // Very complex example with a lot of maxing and adding.
            {
                (10.0 + 5.0.toElasticY()).max(25.0.toElasticY()).max(4.0 + 20.0.toElasticY()) +
                        (15.0 + (-15.0).toElasticY()).max(16.5 + (-30.0).toElasticY())
            },
            doubleArrayOf(0.0, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.75, 0.8, 1.0, 1.5),
            doubleArrayOf(26.5, 25.25, 24.0, 23.0, 22.0, 21.0, 21.5, 22.75, 23.0, 25.0, 30.0)
        )
    )

    @Test
    fun `deresolve ambiguous`() {
        doTestDeresolve(
            5.0.toY().max(10.0.toElasticY()),
            doubleArrayOf(5.0, 10.0),
            doubleArrayOf(0.5, 1.0)
        )

        doTestDeresolve(
            5.0.toY(),
            doubleArrayOf(5.0),
            doubleArrayOf(1.0)
        )
    }

    @Test
    fun `deresolve best match`() {
        doTestDeresolve(
            5.0.toY().max(10.0.toElasticY()),
            doubleArrayOf(3.0, 4.0),
            doubleArrayOf(0.5, 0.5)
        )

        doTestDeresolve(
            5.0.toY(),
            doubleArrayOf(4.0, 6.0),
            doubleArrayOf(1.0, 1.0)
        )

        // deresolve() should never return negative numbers.
        doTestDeresolve(
            5.0 + 10.0.toElasticY(),
            doubleArrayOf(-2.0, 0.0, 2.0, 4.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        )
    }

}
