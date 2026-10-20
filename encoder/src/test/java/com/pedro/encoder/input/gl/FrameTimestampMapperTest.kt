package com.pedro.encoder.input.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimestampMapperTest {
  private val frameNs = 33_333_333L
  private val jitter = longArrayOf(4_000_000, 0, 7_000_000, 1_000_000, 9_000_000, 2_000_000)

  @Test
  fun `spacing follows the source, not the jittery draw time`() {
    val m = FrameTimestampMapper(settleFrames = 3)
    val camBase = 5_000_000_000L
    val out = (0 until 60).map { i ->
      val cam = camBase + i * frameNs
      m.map(cam, cam + 20_000_000L + jitter[i % jitter.size])
    }
    // after the settle window the spacing is exactly the camera's
    for (i in 4 until out.size) assertEquals(frameNs, out[i] - out[i - 1])
  }

  @Test
  fun `same base as audio clock when the camera uses another clock`() {
    // camera on CLOCK_MONOTONIC, 3 h behind elapsedRealtime (deep sleep)
    val m = FrameTimestampMapper(settleFrames = 10)
    val sleep = 3 * 3600_000_000_000L
    val minLatency = 15_000_000L
    var last = 0L
    for (i in 0 until 30) {
      val cam = 10_000_000_000L + i * frameNs
      val now = cam + sleep + minLatency + jitter[i % jitter.size]
      last = m.map(cam, now)
      assertTrue(m.lastUsedSource)
    }
    val capture = 10_000_000_000L + 29 * frameNs + sleep
    // within the minimum pipeline latency of real capture time in the audio clock
    assertEquals(capture + minLatency, last)
  }

  @Test
  fun `no source timestamp falls back to the steady clock`() {
    val m = FrameTimestampMapper()
    assertEquals(1_000L, m.map(0L, 1_000L))
    assertFalse(m.lastUsedSource)
    assertEquals(2_000L, m.map(0L, 2_000L))
  }

  @Test
  fun `repeated frame uses clock and stays monotonic`() {
    val m = FrameTimestampMapper(settleFrames = 0)
    val a = m.map(1_000_000_000L, 1_010_000_000L)
    val b = m.map(1_000_000_000L, 1_005_000_000L) // forced redraw, clock behind the mapped value
    assertFalse(m.lastUsedSource)
    assertTrue(b > a)
  }

  @Test
  fun `source jump re-anchors and output never goes backwards`() {
    val m = FrameTimestampMapper(settleFrames = 0)
    var prev = Long.MIN_VALUE
    var now = 50_000_000_000L
    val cams = (0 until 10).map { 7_000_000_000L + it * frameNs } +
        (0 until 10).map { 100L + it * frameNs } // file loop / source switch
    for (cam in cams) {
      now += frameNs
      val t = m.map(cam, now)
      assertTrue(t > prev)
      prev = t
    }
    // after the jump the output tracks the clock again
    assertTrue(kotlin.math.abs(prev - now) < 1_000_000_000L)
  }
}
