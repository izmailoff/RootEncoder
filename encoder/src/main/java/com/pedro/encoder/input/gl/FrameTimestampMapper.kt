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
 *  - Output is strictly increasing.
 *
 * Not thread-safe; call from the GL thread only. Pure JVM so it is unit-testable.
 */
class FrameTimestampMapper(
  private val settleFrames: Int = 30,
  private val maxDivergenceNs: Long = 1_000_000_000L,
) {
  private var offsetNs = 0L
  private var anchored = false
  private var framesSinceAnchor = 0
  private var lastSourceNs = 0L
  private var lastOutNs = Long.MIN_VALUE

  /** True when the last [map] call used the source timestamp (false = steady-clock fallback). */
  var lastUsedSource = false
    private set

  fun reset() {
    anchored = false
    framesSinceAnchor = 0
    lastSourceNs = 0L
    lastOutNs = Long.MIN_VALUE
    lastUsedSource = false
  }

  /**
   * @param sourceNs the frame's own timestamp in ns, 0 when the source has none.
   * @param nowNs the audio clock now (TimeUtils.getCurrentTimeNano()).
   */
  fun map(sourceNs: Long, nowNs: Long): Long {
    var out: Long
    if (sourceNs == 0L || (anchored && sourceNs == lastSourceNs)) {
      // No source time, or the same frame drawn again: steady clock.
      out = nowNs
      lastUsedSource = false
    } else {
      val candidate = nowNs - sourceNs
      val jumped = anchored && (sourceNs < lastSourceNs ||
          kotlin.math.abs(sourceNs + offsetNs - nowNs) > maxDivergenceNs)
      if (!anchored || jumped) {
        offsetNs = candidate
        anchored = true
        framesSinceAnchor = 0
      } else if (framesSinceAnchor < settleFrames && candidate < offsetNs) {
        offsetNs = candidate
      }
      framesSinceAnchor++
      lastSourceNs = sourceNs
      out = sourceNs + offsetNs
      lastUsedSource = true
    }
    if (lastOutNs != Long.MIN_VALUE && out <= lastOutNs) out = lastOutNs + 1_000L
    lastOutNs = out
    return out
  }
}
