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

    private fun doTestResolve(y: Y, ss: FloatArray, lsExp: FloatArray) {
        assert(ss.size == lsExp.size)  // Catch errors made by the test author.
        for (i in ss.indices) {
            val s = ss[i]
            val lExp = lsExp[i]
            val lAct = y.resolve(s)
            assertEquals(lExp, lAct, 0.001f, "y.resolve($s) = $lAct, but should be $lExp.")
        }
    }

    private fun doTestDeresolve(y: Y, ls: FloatArray, ssExp: FloatArray) {
        assert(ls.size == ssExp.size)  // Catch errors made by the test author.
        for (i in ls.indices) {
            val l = ls[i]
            val sExp = ssExp[i]
            val sAct = y.deresolve(l)
            assertEquals(sExp, sAct, 0.001f, "y.deresolve($l) = $sAct, but should be $sExp.")
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `resolve cases`(makeY: () -> Y, ss: FloatArray, lsExp: FloatArray) {
        val y = makeY()
        doTestResolve(y, ss, lsExp)
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `deresolve cases`(makeY: () -> Y, ssExp: FloatArray, ls: FloatArray) {
        // If multiple elasticScalings yield them same length, deresolve() is ambiguous.
        // In these cases, this method can't run a meaningful test.
        if (ls.toMutableSet().size != ls.size)
            return
        val y = makeY()
        doTestDeresolve(y, ls, ssExp)
    }

    fun cases() = listOf(
        arguments(
            { 0f.toY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(0f, 0f, 0f, 0f)
        ),
        arguments(
            { 10f.toY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(10f, 10f, 10f, 10f)
        ),
        arguments(
            { 0f - 10f.toY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(-10f, -10f, -10f, -10f)
        ),
        arguments(
            { 10f.toElasticY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(0f, 5f, 10f, 15f)
        ),
        arguments(
            { 0f - 10f.toElasticY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(0f, -5f, -10f, -15f)
        ),
        arguments(
            { 7f + 10f.toElasticY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(7f, 12f, 17f, 22f)
        ),
        arguments(
            { 7f + 10f.toElasticY() + 5f.toElasticY() },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(7f, 14.5f, 22f, 29.5f)
        ),
        arguments(
            { (7f + 10f.toElasticY()) / 2f },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(3.5f, 6f, 8.5f, 11f)
        ),
        arguments(
            { (7f + 10f.toElasticY()).scaleElastic(0.5f) },
            floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 3f),
            floatArrayOf(7f, 9.5f, 12f, 14.5f, 17f, 22f)
        ),
        arguments(
            { (7f + 10f.toElasticY()).scaleElastic(0f) },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(7f, 7f, 7f, 7f)
        ),
        arguments(
            { 0f.toY().max(10f.toElasticY()) },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(0f, 5f, 10f, 15f)
        ),
        arguments(
            // Two parallel lines
            { 5f.toElasticY().max(10f + 5f.toElasticY()) },
            floatArrayOf(0f, 0.5f, 1f, 1.5f),
            floatArrayOf(10f, 12.5f, 15f, 17.5f)
        ),
        arguments(
            // The two lines meet at s=0.5. Left to that point, the first line is above the second line.
            // From that point on, the second one is above the first.
            { (10f + 5f.toElasticY()).max(25f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 1f, 1.5f),
            floatArrayOf(10f, 11.25f, 12f, 12.5f, 18.75f, 25f, 37.5f)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=1, where a control point lies.
            { (10f + 5f.toElasticY()).max(15f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 1f, 1.5f),
            floatArrayOf(10f, 11.25f, 12f, 12.5f, 13.75f, 15f, 22.5f)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=2, after both control points.
            { (10f + 5f.toElasticY()).max(10f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 1f, 1.5f, 2f, 2.5f),
            floatArrayOf(10f, 11.25f, 12f, 12.5f, 13.75f, 15f, 17.5f, 20f, 25f)
        ),
        arguments(
            // Same as above, but now the two lines meet at s=4, after both control points.
            { (10f + 5f.toElasticY()).max(7.5f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 1f, 3f, 4f, 5f),
            floatArrayOf(10f, 11.25f, 12f, 12.5f, 13.75f, 15f, 25f, 30f, 37.5f)
        ),
        arguments(
            // Add a line to a Y that has 3 control points.
            { (10f + 5f.toElasticY()).max(25f.toElasticY()) + (5f + 2.5f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 1f, 1.5f),
            floatArrayOf(15f, 16.875f, 18f, 18.75f, 25.625f, 32.5f, 46.25f)
        ),
        arguments(
            // Max a line with a Y that has 3 control points.
            { (10f + 5f.toElasticY()).max(25f.toElasticY()).max(4f + 20f.toElasticY()) },
            floatArrayOf(0f, 0.25f, 0.4f, 0.5f, 0.75f, 0.8f, 1f, 1.5f),
            floatArrayOf(10f, 11.25f, 12f, 14f, 19f, 20f, 25f, 37.5f)
        ),
        arguments(
            // Very complex example with a lot of maxing.
            {
                (10f + 5f.toElasticY())
                    .max(25f.toElasticY())
                    .max(4f + 20f.toElasticY())
                    .max((15f + (-15f).toElasticY()).max(16.5f + (-30f).toElasticY()))
            },
            floatArrayOf(0f, 0.05f, 0.1f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.75f, 0.8f, 1f, 1.5f),
            floatArrayOf(16.5f, 15f, 13.5f, 12f, 11.25f, 11.5f, 12f, 14f, 19f, 20f, 25f, 37.5f)
        ),
        arguments(
            // Very complex example with a lot of maxing and adding.
            {
                (10f + 5f.toElasticY()).max(25f.toElasticY()).max(4f + 20f.toElasticY()) +
                        (15f + (-15f).toElasticY()).max(16.5f + (-30f).toElasticY())
            },
            floatArrayOf(0f, 0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.75f, 0.8f, 1f, 1.5f),
            floatArrayOf(26.5f, 25.25f, 24f, 23f, 22f, 21f, 21.5f, 22.75f, 23f, 25f, 30f)
        )
    )

    @Test
    fun `deresolve ambiguous`() {
        doTestDeresolve(
            5f.toY().max(10f.toElasticY()),
            floatArrayOf(5f, 10f),
            floatArrayOf(0.5f, 1f)
        )

        doTestDeresolve(
            5f.toY(),
            floatArrayOf(5f),
            floatArrayOf(1f)
        )
    }

    @Test
    fun `deresolve best match`() {
        doTestDeresolve(
            5f.toY().max(10f.toElasticY()),
            floatArrayOf(3f, 4f),
            floatArrayOf(0.5f, 0.5f)
        )

        doTestDeresolve(
            5f.toY(),
            floatArrayOf(4f, 6f),
            floatArrayOf(1f, 1f)
        )

        // deresolve() should never return negative numbers.
        doTestDeresolve(
            5f + 10f.toElasticY(),
            floatArrayOf(-2f, 0f, 2f, 4f),
            floatArrayOf(0f, 0f, 0f, 0f)
        )
    }

}
