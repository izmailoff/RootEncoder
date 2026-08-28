/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.common.frame

import com.pedro.common.toByteArray
import java.nio.ByteBuffer

/**
 * [[contract:change-video-size-on-fly]] One generation of codec parameter sets — the SPS/PPS (and
 * VPS for HEVC) a video encoder emitted through formatChanged — as an immutable snapshot.
 *
 * A sender that supports a parameter-set change under a live connection (the encoder restarted at
 * a new size) stamps the generation in force onto every video [MediaFrame] as it is queued, so the
 * packetizer can pair each keyframe with the sets it was encoded against. Applying a new set to
 * the packetizer's mutable fields instead binds it to whatever is dequeued NEXT, which under a
 * backlog is a keyframe from the old codec: a rung-down fires under congestion, i.e. exactly when
 * the queue is fullest.
 *
 * Bytes are copied as given (with or without Annex-B start codes); packetizers normalise. Identity
 * is the generation: a packetizer may cache its normalised copy per instance.
 */
class VideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
  val sps: ByteArray = sps.toByteArray()
  val pps: ByteArray? = pps?.toByteArray()
  val vps: ByteArray? = vps?.toByteArray()
}
