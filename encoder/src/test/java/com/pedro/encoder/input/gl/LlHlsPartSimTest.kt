package com.pedro.encoder.input.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Offline check for tvcsrt9: feed a camera timestamp series through [FrameTimestampMapper] with and
 * without the CFR grid, convert like the pipeline does (ns -> encoder us -> MPEG-TS 90 kHz, Pes.kt
 * `us * 9 / 100`), cut LL-HLS parts like MediaMTX (a part closes at the first sample >= 200 ms after
 * its start) and count how often the part duration changes ("part duration changed from 234ms to
 * 267ms ... error in iOS clients"). Keyframe-forced segment cuts are not modelled.
 *
 * A recorded series: -DptsSeries=/path (one camera timestamp in ns per line, optionally
 * "<camera ns> <draw-time ns>"), e.g. from logcat. Run:
 *   ./gradlew :encoder:testDebugUnitTest --tests '*LlHlsPartSim*' -i | grep 'part-sim'
 */
class LlHlsPartSimTest {
  data class Sim(val parts: Int, val changes: Int, val growths: Int, val durationsMs: Map<Int, Int>, val rebases: Long)

  private fun simulate(series: List<Pair<Long, Long>>, fps: Int?, partMs: Int = 200): Sim {
    val m = FrameTimestampMapper().apply { if (fps != null) setFrameRate(fps) }
    val ticks = series.map { (cam, now) -> (m.map(cam, now) / 1000) * 9 / 100 }
    val target = partMs * 90L
    var start = ticks.first()
    val durations = ArrayList<Long>()
    for (t in ticks.drop(1)) if (t - start >= target) { durations += t - start; start = t }
    val ms = durations.map { ((it + 89) / 90).toInt() }
    var changes = 0; var growths = 0; var max = 0
    for (i in ms.indices) {
      if (i > 0 && ms[i] != ms[i - 1]) changes++
      if (i > 0 && ms[i] > max) growths++
      if (ms[i] > max) max = ms[i]
    }
    return Sim(ms.size, changes, growths, ms.groupingBy { it }.eachCount().toSortedMap(), m.rebases)
  }

  /** 2 min at 30 fps with AE dips to ~27 fps, sensor jitter +-3 ms, draw latency 15-30 ms. */
  private fun synthetic(seed: Int = 42): List<Pair<Long, Long>> {
    val r = Random(seed)
    var cam = 7_000_000_000L
    val out = ArrayList<Pair<Long, Long>>()
    val end = cam + 120_000_000_000L
    while (cam < end) {
      val dip = ((cam / 1_000_000_000L) % 20) >= 12 // 8 s of every 20 s dimmer: AE stretches frames
      val interval = if (dip) 1_000_000_000L / 27 else 1_000_000_000L / 30
      cam += interval + r.nextLong(-3_000_000, 3_000_000)
      out += cam to cam + 15_000_000 + r.nextLong(0, 15_000_000)
    }
    return out
  }

  private fun report(name: String, series: List<Pair<Long, Long>>): Pair<Sim, Sim> {
    val before = simulate(series, null)
    val after = simulate(series, 30)
    println("part-sim $name: ${series.size} frames, ${before.parts} parts | before: ${before.changes} changes, " +
        "${before.growths} max-growths, durations ${before.durationsMs} | after: ${after.changes} changes, " +
        "${after.growths} max-growths, durations ${after.durationsMs}, ${after.rebases} grid re-bases")
    return before to after
  }

  @Test
  fun `synthetic VFR camera - part durations stop flipping`() {
    val (before, after) = report("vfr-jitter", synthetic())
    assertTrue(after.changes * 3 < before.changes)
  }

  @Test
  fun `steady 30 fps with jitter - one part duration only`() {
    val r = Random(5)
    val series = (0 until 3600).map { val c = 7_000_000_000L + it * 33_333_333L + r.nextLong(-4_000_000, 4_000_000); c to c + 20_000_000 }
    val (_, after) = report("cfr-jitter", series)
    assertTrue(after.changes <= 3) // the grid's start bias slewing out: 200<->201 ms once or twice
  }

  @Test
  fun `recorded series from -DptsSeries`() {
    val path = System.getProperty("ptsSeries") ?: return
    val series = File(path).readLines().mapNotNull { l ->
      val p = l.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
      when (p.size) { 0 -> null; 1 -> p[0] to p[0] + 20_000_000; else -> p[0] to p[1] }
    }
    report("recorded:$path", series)
  }
}
