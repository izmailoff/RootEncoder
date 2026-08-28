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

package com.pedro.srt

import org.junit.Assert.assertEquals

/**
 * Created by pedro on 1/9/23.
 */
object Utils {
  fun assertObjectEquals(actual: Any, expected: Any) {
    assertEquals(actual.toString(), expected.toString())
  }

  /**
   * Strip the TS framing from a run of 188-byte packets (in write order) and hand back the
   * concatenated payload of one PID, so a test can look for elementary-stream bytes — an SPS, a
   * slice — without caring where the 184-byte payload boundaries fell.
   */
  fun tsPayload(ts: ByteArray, pid: Int): ByteArray {
    require(ts.size % 188 == 0) { "not a whole number of TS packets: ${ts.size}" }
    return tsPayload(ts) { it == pid }
  }

  /** Every PID's payload, in write order. */
  fun tsPayloadAll(ts: ByteArray): ByteArray = tsPayload(ts) { true }

  private fun tsPayload(ts: ByteArray, wanted: (Int) -> Boolean): ByteArray {
    require(ts.size % 188 == 0) { "not a whole number of TS packets: ${ts.size}" }
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < ts.size) {
      require(ts[i].toInt() and 0xff == 0x47) { "lost sync at $i" }
      val packetPid = ((ts[i + 1].toInt() and 0x1f) shl 8) or (ts[i + 2].toInt() and 0xff)
      val afc = (ts[i + 3].toInt() shr 4) and 0x03
      var payloadStart = i + 4
      if (afc and 0x02 != 0) payloadStart += 1 + (ts[i + 4].toInt() and 0xff)
      if (wanted(packetPid) && afc and 0x01 != 0) out.write(ts, payloadStart, i + 188 - payloadStart)
      i += 188
    }
    return out.toByteArray()
  }

  /** Index of the first occurrence of [needle] in [haystack], or -1. */
  fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from
    var i = from
    while (i + needle.size <= haystack.size) {
      var j = 0
      while (j < needle.size && haystack[i + j] == needle[j]) j++
      if (j == needle.size) return i
      i++
    }
    return -1
  }

  fun countOf(haystack: ByteArray, needle: ByteArray): Int {
    var count = 0
    var at = indexOf(haystack, needle)
    while (at >= 0) {
      count++
      at = indexOf(haystack, needle, at + 1)
    }
    return count
  }
}
