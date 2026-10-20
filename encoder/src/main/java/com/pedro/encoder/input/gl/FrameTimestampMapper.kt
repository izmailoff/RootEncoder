/*
 * Copyright (C) 2026 TVC (fork addition to pedroSG94/RootEncoder).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.encoder.input.gl

/**
 * Maps a source frame's own timestamp (SurfaceTexture.getTimestamp(): the Camera2/Camera1 sensor time,
 * a decoder's render time, ...) onto the clock the audio path stamps with
 * (TimeUtils.getCurrentTimeNano() = elapsedRealtimeNanos), and returns the presentation time to hand
 * the encoder surface.
 *
 * Why: stamping a frame with the moment the GL thread happened to draw it makes the spacing between
 * frames jitter by milliseconds; MediaMTX then sees LL-HLS part durations change ("this will cause an
 * error in iOS clients") and iPhone players reject the playlist. The sensor timestamp has the camera's
 * own clean cadence.
 *
 * How:
 *  - The source clock may not be elapsedRealtime (Camera2 SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN is
 *    CLOCK_MONOTONIC, which lags it by deep-sleep time; a decoder uses media time), so the output is
 *    `sourceTs + offset`. The offset is learnt as the smallest (now - sourceTs) seen over the first
 *    [settleFrames] frames (the lower envelope = least pipeline latency, i.e. closest to capture time,
 *    which is what the microphone timestamps are too), then frozen so draw-time noise never leaks in.
 *  - A source without a timestamp (0), a repeated frame (forced redraw with nothing new latched) or a
 *    source that jumps (switch, loop, pause) falls back to the steady clock: `now` — and a jump
 *    re-anchors the mapping.
 *  - [[gotcha:cfr-pts-grid]] Constant-frame-rate grid (tvcsrt9, once [setFrameRate] is given the nominal rate): even honest
 *    sensor timestamps wobble (AE, HAL scheduling), and on 2026-10-20 a phone still made MediaMTX flip
 *    200 ms LL-HLS parts between 7 and 8 frames. So each source-mapped time is snapped to the nearest
 *    slot of a nominal grid at least one slot past the previous output: jitter disappears, a dropped
 *    frame (or a VFR dip to 27 fps) skips whole slots, and the output never strays more than
 *    [snapTolerance] of a frame behind / [earlyTolerance] ahead of the real capture time, zero mean —
 *    so audio stays in sync. A frame further
 *    off than that re-bases the grid on its real time. A persistent offset (source clock rate a few
 *    hundred ppm off nominal) is slewed out slowly ([maxSlewNs] per frame) once it exceeds 1/20 frame.
 *  - Output is strictly increasing.
 *
 * Not thread-safe; call from the GL thread only. Pure JVM so it is unit-testable.
 */
class FrameTimestampMapper(
  private val settleFrames: Int = 30,
  private val maxDivergenceNs: Long = 1_000_000_000L,
  private val snapTolerance: Double = 0.5,
  private val earlyTolerance: Double = 0.9,
  private val maxSlewNs: Long = 20_000L,
) {
  private var offsetNs = 0L
  private var anchored = false
  private var framesSinceAnchor = 0
  private var lastSourceNs = 0L
  private var lastOutNs = Long.MIN_VALUE

  // CFR grid: slot i is at gridBaseNs + i * 1e9 / fps (exact integer maths, no accumulated rounding).
  private var fps = 0
  private var gridActive = false
  private var gridBaseNs = 0L
  private var gridIndex = 0L
  private var errEmaNs = 0.0
  private var slewing = false

  /** True when the last [map] call used the source timestamp (false = steady-clock fallback). */
  var lastUsedSource = false
    private set

  /** True when the last [map] output sits on the nominal grid. */
  var lastSnapped = false
    private set

  /** Grid re-bases since creation (a frame further than the tolerance from its slot). Diagnostics. */
  var rebases = 0L
    private set

  // Rate-limited PTS-delta summary for judging a phone run from logcat.
  private var statFrames = 0
  private var statSnapped = 0
  private var statMinDelta = Long.MAX_VALUE
  private var statMaxDelta = 0L
  private var statRebases = 0L
  private var statSinceNs = Long.MIN_VALUE

  /**
   * A one-line summary of output PTS deltas since the previous call, at most once per [everyNs]
   * (null in between): frames, snapped share, min/max delta in microseconds, grid re-bases.
   */
  fun pollStats(nowNs: Long, everyNs: Long = 5_000_000_000L): String? {
    if (statSinceNs == Long.MIN_VALUE) { statSinceNs = nowNs; return null }
    if (nowNs - statSinceNs < everyNs || statFrames == 0) return null
    val line = "pts deltas ${statFrames}f grid=${fps}fps snapped=$statSnapped " +
        "min=${statMinDelta / 1000}us max=${statMaxDelta / 1000}us rebases=${rebases - statRebases}"
    statFrames = 0; statSnapped = 0; statMinDelta = Long.MAX_VALUE; statMaxDelta = 0L
    statRebases = rebases; statSinceNs = nowNs
    return line
  }

  /** Nominal frame rate for the CFR grid; 0 disables snapping. Takes effect on the next frame. */
  fun setFrameRate(fps: Int) {
    if (fps == this.fps) return
    this.fps = if (fps > 0) fps else 0
    gridActive = false
  }

  fun reset() {
    anchored = false
    framesSinceAnchor = 0
    lastSourceNs = 0L
    lastOutNs = Long.MIN_VALUE
    lastUsedSource = false
    lastSnapped = false
    gridActive = false
  }

  private fun slot(i: Long): Long = gridBaseNs + i * 1_000_000_000L / fps

  /**
   * @param sourceNs the frame's own timestamp in ns, 0 when the source has none.
   * @param nowNs the audio clock now (TimeUtils.getCurrentTimeNano()).
   */
  fun map(sourceNs: Long, nowNs: Long): Long {
    var out: Long
    lastSnapped = false
    if (sourceNs == 0L || (anchored && sourceNs == lastSourceNs)) {
      // No source time, or the same frame drawn again: steady clock.
      out = nowNs
      lastUsedSource = false
      gridActive = false
    } else {
      val candidate = nowNs - sourceNs
      val jumped = anchored && (sourceNs < lastSourceNs ||
          kotlin.math.abs(sourceNs + offsetNs - nowNs) > maxDivergenceNs)
      if (!anchored || jumped) {
        offsetNs = candidate
        anchored = true
        framesSinceAnchor = 0
        gridActive = false
      } else if (framesSinceAnchor < settleFrames && candidate < offsetNs) {
        offsetNs = candidate
      }
      framesSinceAnchor++
      lastSourceNs = sourceNs
      out = sourceNs + offsetNs
      lastUsedSource = true
      if (fps > 0) out = snap(out)
    }
    if (lastOutNs != Long.MIN_VALUE && out <= lastOutNs) {
      out = lastOutNs + 1_000L
      lastSnapped = false
      gridActive = false
    }
    if (lastOutNs != Long.MIN_VALUE) {
      val d = out - lastOutNs
      statFrames++
      if (lastSnapped) statSnapped++
      if (d < statMinDelta) statMinDelta = d
      if (d > statMaxDelta) statMaxDelta = d
    }
    lastOutNs = out
    return out
  }

  private fun snap(raw: Long): Long {
    val periodNs = 1_000_000_000.0 / fps
    if (!gridActive || lastOutNs == Long.MIN_VALUE) return rebase(raw)
    val steps = Math.round((raw - slot(gridIndex)) / periodNs).coerceAtLeast(1L)
    val index = gridIndex + steps
    val err = raw - slot(index)
    // Late by more than the tolerance, or early by more than [earlyTolerance] (a frame arriving half
    // a slot after one that was rounded up has no free slot nearer than the next one): real time wins.
    if (err > snapTolerance * periodNs || -err > earlyTolerance * periodNs) {
      rebases++
      return rebase(raw)
    }
    gridIndex = index
    // Slow drift correction: only a sustained offset (> 1/20 frame on average, until < 1/100) moves the grid, and by
    // at most maxSlewNs per frame, so zero-mean jitter leaves the output exactly on the grid.
    errEmaNs += (err - errEmaNs) / 64.0
    // Hysteresis: start at 1/20 frame, stop at 1/100, so noise around the threshold never dithers.
    val absEma = kotlin.math.abs(errEmaNs)
    if (absEma > periodNs / 20) slewing = true else if (absEma < periodNs / 100) slewing = false
    if (slewing) {
      val slew = errEmaNs.toLong().coerceIn(-maxSlewNs, maxSlewNs)
      gridBaseNs += slew
      errEmaNs -= slew
    }
    lastSnapped = true
    return slot(gridIndex)
  }

  private fun rebase(raw: Long): Long {
    gridBaseNs = raw
    gridIndex = 0
    errEmaNs = 0.0
    slewing = false
    gridActive = true
    lastSnapped = true
    return raw
  }
}
