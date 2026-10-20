package com.pedro.common

import android.os.Build
import android.util.Range
import androidx.annotation.RequiresApi
import kotlin.math.abs

object FpsUtils {
    /**
     * TVC fork: the AE target range for a CONSTANT frame rate. A range like [15,30] lets auto-exposure
     * lower the frame rate in dim light, so the camera's own timestamps go uneven and LL-HLS part
     * durations flip (iOS rejects the playlist). Order: the fixed [N,N] when listed; else the
     * narrowest range containing N (ties: the higher floor); else null (caller falls back).
     */
    fun fixedFpsRange(expectedFps: Int, fpsRanges: List<IntArray>): IntArray? {
        fpsRanges.firstOrNull { it[0] == expectedFps && it[1] == expectedFps }?.let { return it }
        return fpsRanges.filter { it[0] <= expectedFps && it[1] >= expectedFps }
            .minWithOrNull(compareBy<IntArray> { it[1] - it[0] }.thenByDescending { it[0] })
    }

    fun adaptFpsRange(expectedFps: Int, fpsRanges: List<IntArray>): IntArray {
        if (fpsRanges.isEmpty()) throw IllegalArgumentException("fpsRanges is empty")
        val exactFps = fpsRanges.filter { it[1] == expectedFps }
        if (exactFps.isNotEmpty()) return exactFps.sortedBy { abs(expectedFps - it[0]) }[0]
        val higherFps = fpsRanges.filter { it[1] > expectedFps }
        if (higherFps.isNotEmpty()) {
            val upper = higherFps.sortedBy { abs(expectedFps - it[1]) }[0][1]
            return higherFps.filter { it[1] == upper }.sortedBy { abs(expectedFps - it[0]) }[0]
        }
        val lowerFps = fpsRanges.filter { it[1] < expectedFps }
        if (lowerFps.isNotEmpty()) {
            val upper = lowerFps.sortedBy { abs(expectedFps - it[1]) }[0][1]
            return lowerFps.filter { it[1] == upper }.sortedBy { abs(expectedFps - it[0]) }[0]
        }
        return fpsRanges[0]
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun adaptFpsRange(expectedFps: Int, fpsRanges: List<Range<Int>>): Range<Int> {
        val result = adaptFpsRange(expectedFps, fpsRanges.map { intArrayOf(it.lower, it.upper) })
        return Range(result[0], result[1])
    }
}