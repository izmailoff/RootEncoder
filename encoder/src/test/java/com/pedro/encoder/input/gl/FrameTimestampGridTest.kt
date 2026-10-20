package com.pedro.encoder.input.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** tvcsrt9: the constant-frame-rate grid in [FrameTimestampMapper]. */
class FrameTimestampGridTest {
  private val fps = 30
  private val period = 1_000_000_000L / fps // 33_333_333 (slots alternate ..333/..334)
  private val camBase = 5_000_000_000L
  private val latency = 20_000_000L

  private fun isGridDelta(d: Long, slots: Long = 1) = abs(d - slots * 1_000_000_000L / fps) <= slots

  /** Runs camera times through a snapping and a non-snapping mapper with the same draw-time noise. */
  private fun run(cams: List<Long>, seed: Int = 1): Pair<List<Long>, List<Long>> {
    val snap = FrameTimestampMapper(settleFrames = 5).apply { setFrameRate(fps) }
    val raw = FrameTimestampMapper(settleFrames = 5)
    val r = Random(seed)
    val a = ArrayList<Long>(); val b = ArrayList<Long>()
    for (c in cams) {
      val now = c + latency + r.nextLong(0, 8_000_000)
      a += snap.map(c, now); b += raw.map(c, now)
    }
    return a to b
  }

  @Test
  fun `jittery camera timestamps land exactly on the grid`() {
    val r = Random(7)
    val cams = (0 until 3000).map { camBase + it * period + r.nextLong(-6_000_000, 6_000_000) }
    val (out, _) = run(cams)
    // the first frames may slew out the grid's start bias (<= 20 us per frame); then exact
    for (i in 10 until out.size) assertTrue(abs(out[i] - out[i - 1] - period) <= 20_001)
    for (i in 300 until out.size) assertTrue("delta ${out[i] - out[i - 1]} at $i", isGridDelta(out[i] - out[i - 1]))
  }

  @Test
  fun `VFR dip to 27 fps keeps the gaps as whole skipped slots and stays near real time`() {
    val p27 = 1_000_000_000L / 27
    val cams = (0 until 300).map { camBase + it * period } + (1..600).map { camBase + 300 * period + it * p27 }
    val (out, raw) = run(cams)
    var skipped = 0
    for (i in 10 until out.size) {
      val d = out[i] - out[i - 1]
      val slots = Math.round(d.toDouble() / period)
      assertTrue("delta $d not on grid", slots >= 1 && isGridDelta(d, slots))
      if (slots > 1) skipped += (slots - 1).toInt()
      val e = out[i] - raw[i]
      assertTrue("strayed $e", e >= -period / 2 - 1 && e <= period * 9 / 10)
    }
    // 600 frames at 27 fps span ~667 slots at 30 fps: ~66 skipped
    assertTrue("skipped $skipped", skipped in 60..72)
  }

  @Test
  fun `A-V base unchanged - snapped times stay within half a frame of the source times, zero mean`() {
    val r = Random(3)
    val cams = (0 until 18_000).map { camBase + it * period + r.nextLong(-5_000_000, 5_000_000) }
    val (out, raw) = run(cams)
    var sum = 0.0
    for (i in 10 until out.size) {
      val e = out[i] - raw[i]
      assertTrue(e >= -period / 2 - 1 && e <= period * 9 / 10)
      sum += e
    }
    assertTrue("mean offset ${sum / out.size} ns", abs(sum / out.size) < 2_000_000)
  }

  @Test
  fun `clock a few hundred ppm off nominal is slewed out without re-basing`() {
    val p = (period * (1 + 300e-6)).toLong()
    val cams = (0 until 18_000).map { camBase + it * p } // 10 min
    val m = FrameTimestampMapper(settleFrames = 5).apply { setFrameRate(fps) }
    val raw = FrameTimestampMapper(settleFrames = 5)
    for (c in cams) {
      val o = m.map(c, c + latency); val w = raw.map(c, c + latency)
      assertTrue(o - w >= -period / 2 && o - w <= period * 9 / 10)
    }
    assertEquals(0L, m.rebases)
  }

  @Test
  fun `monotonic through drops, bursts and noise`() {
    val r = Random(11)
    var t = camBase
    val cams = ArrayList<Long>()
    repeat(5000) {
      t += when (r.nextInt(20)) { 0 -> 3 * period; 1 -> period / 3; else -> period + r.nextLong(-4_000_000, 4_000_000) }
      cams += t
    }
    val (out, _) = run(cams)
    for (i in 1 until out.size) assertTrue(out[i] > out[i - 1])
  }

  @Test
  fun `no frame rate set keeps the source spacing`() {
    val m = FrameTimestampMapper(settleFrames = 0)
    val a = m.map(camBase, camBase + latency)
    val b = m.map(camBase + period + 3_000_000, camBase + period + latency)
    assertEquals(period + 3_000_000, b - a)
  }
}
