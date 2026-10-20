package com.pedro.common

import junit.framework.TestCase.assertTrue
import org.junit.Test

class FpsUtilsTest {

    @Test(expected = IllegalArgumentException::class)
    fun testAdaptFpsFail() {
        FpsUtils.adaptFpsRange(60, listOf<IntArray>())
    }

    @Test
    fun testAdaptFpsLowerFps() {
        val result = FpsUtils.adaptFpsRange(60,
            listOf(
                intArrayOf(15, 15),
                intArrayOf(15, 30),
                intArrayOf(30, 30),
            ))
        assertTrue(result contentEquals intArrayOf(30, 30))
    }

    @Test
    fun testAdaptFpsEqualFps() {
        val result = FpsUtils.adaptFpsRange(60,
            listOf(
                intArrayOf(15, 15),
                intArrayOf(15, 30),
                intArrayOf(30, 30),
                intArrayOf(60, 75),
                intArrayOf(45, 60),
                intArrayOf(30, 60),
            ))
        assertTrue(result contentEquals intArrayOf(45, 60))
    }

    @Test
    fun testAdaptFpsHigherFps() {
        val result = FpsUtils.adaptFpsRange(60,
            listOf(
                intArrayOf(15, 15),
                intArrayOf(30, 30),
                intArrayOf(45, 45),
                intArrayOf(45, 75),
            ))
        assertTrue(result contentEquals intArrayOf(45, 75))
    }

    @Test
    fun testAdaptFpsSameFps() {
        val result = FpsUtils.adaptFpsRange(60,
            listOf(
                intArrayOf(15, 15),
                intArrayOf(15, 30),
                intArrayOf(30, 30),
                intArrayOf(60, 75),
                intArrayOf(45, 60),
                intArrayOf(30, 60),
                intArrayOf(60, 60),
            ))
        assertTrue(result contentEquals intArrayOf(60, 60))
    }

    @Test
    fun fixedRangePreferredOverWiderAeRange() {
        val r = FpsUtils.fixedFpsRange(30, listOf(intArrayOf(15, 30), intArrayOf(24, 30), intArrayOf(30, 30), intArrayOf(7, 60)))
        assertTrue(r contentEquals intArrayOf(30, 30))
    }

    @Test
    fun fixedRangeFallsBackToNarrowestContaining() {
        val r = FpsUtils.fixedFpsRange(25, listOf(intArrayOf(15, 30), intArrayOf(24, 30), intArrayOf(20, 30), intArrayOf(30, 30)))
        assertTrue(r contentEquals intArrayOf(24, 30))
        assertTrue(FpsUtils.fixedFpsRange(50, listOf(intArrayOf(15, 30))) == null)
    }
}
