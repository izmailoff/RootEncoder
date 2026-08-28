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

package com.pedro.srt.mpeg2ts

import com.pedro.common.frame.MediaFrame
import com.pedro.common.frame.VideoInfo
import com.pedro.srt.Utils
import com.pedro.srt.mpeg2ts.packets.H26XPacket
import com.pedro.srt.mpeg2ts.psi.PsiManager
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.utils.Constants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * [[contract:change-video-size-on-fly]] The packetizer must mux a keyframe with the parameter sets
 * bound to THAT frame, not with whatever sendVideoInfo last installed. A rung change restarts the
 * encoder under congestion, i.e. with a backlog in the sender queue; the new codec's SPS lands
 * (through formatChanged) while the old codec's keyframe may still be queued, and prepending the
 * new SPS to it hands the decoder 1080p slices under a 720p SPS for a whole GOP.
 */
class H26XPacketTest {

  private val service = Mpeg2TsService().apply {
    addTrack(Codec.AVC)
    generatePmt()
  }
  private val psiManager = PsiManager(service)
  private val videoPid = psiManager.getVideoPid().toInt()

  // Two SPS/PPS generations with bodies distinct enough that neither is a substring of the other.
  private val sps1080 = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x28, -84, 0x2c, -84, 0x07, -128, 0x22, 0x7e, 0x5c, 0x05, -88, 0x08, 0x08, 0x0a)
  private val pps1080 = byteArrayOf(0, 0, 0, 1, 0x68, -18, 0x3c, -80)
  private val sps720 = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x1f, -84, 0x2c, -84, 0x05, 0x00, 0x5b, -95, 0x00, 0x00, 0x03, 0x00, 0x01, 0x00)
  private val pps720 = byteArrayOf(0, 0, 0, 1, 0x68, -18, 0x3c, -80, 0x10)

  private fun keyframe(ts: Long, videoInfo: VideoInfo?): MediaFrame {
    // An IDR slice with no in-band SPS/PPS, as Qualcomm/Exynos encoders emit them.
    val data = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, -120, -128).plus(ByteArray(240) { (it % 7).toByte() }))
    return MediaFrame(data, MediaFrame.Info(0, data.remaining(), ts, true), MediaFrame.Type.VIDEO, videoInfo)
  }

  private fun videoInfo(sps: ByteArray, pps: ByteArray) = VideoInfo(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps), null)

  private suspend fun mux(packet: H26XPacket, vararg frames: MediaFrame): ByteArray {
    val out = ByteArrayOutputStream()
    frames.forEach { frame ->
      packet.createAndSendPacket(frame) { packets -> packets.forEach { out.write(it.buffer) } }
    }
    return Utils.tsPayload(out.toByteArray(), videoPid)
  }

  @Test
  fun `GIVEN a keyframe bound to the old sets WHEN newer sets were installed THEN it is muxed with its own`() = runTest {
    val packet = H26XPacket(Constants.MTU - SrtPacket.headerSize, psiManager)
    val old = videoInfo(sps1080, pps1080)
    val new = videoInfo(sps720, pps720)
    packet.sendVideoInfo(old)
    val queuedOldKeyframe = keyframe(0, old)
    packet.sendVideoInfo(new) // the resized codec's formatChanged, before the queue drained

    val es = mux(packet, queuedOldKeyframe, keyframe(40_000, new))

    val at1080 = Utils.indexOf(es, sps1080)
    val at720 = Utils.indexOf(es, sps720)
    assertTrue("old keyframe lost its SPS", at1080 >= 0)
    assertTrue("new keyframe lost its SPS", at720 >= 0)
    assertTrue("old SPS must precede the new one", at1080 < at720)
    assertEquals(1, Utils.countOf(es, sps1080))
    assertEquals(1, Utils.countOf(es, sps720))
    assertEquals(1, Utils.countOf(es, pps1080.plus(byteArrayOf(0, 0, 0, 1, 0x65))))
    assertEquals(1, Utils.countOf(es, pps720.plus(byteArrayOf(0, 0, 0, 1, 0x65))))
  }

  @Test
  fun `GIVEN a keyframe with no bound sets WHEN muxed THEN the packetizer's current sets are used`() = runTest {
    val packet = H26XPacket(Constants.MTU - SrtPacket.headerSize, psiManager)
    packet.sendVideoInfo(ByteBuffer.wrap(sps1080), ByteBuffer.wrap(pps1080), null)

    val es = mux(packet, keyframe(0, null))

    assertEquals(1, Utils.countOf(es, sps1080))
    assertEquals(0, Utils.countOf(es, sps720))
  }

  @Test
  fun `GIVEN a keyframe carrying its sets in-band WHEN muxed THEN they are not duplicated`() = runTest {
    val packet = H26XPacket(Constants.MTU - SrtPacket.headerSize, psiManager)
    val info = videoInfo(sps1080, pps1080)
    val slice = byteArrayOf(0, 0, 0, 1, 0x65, -120, -128).plus(ByteArray(200) { 1 })
    val data = ByteBuffer.wrap(sps1080.plus(pps1080).plus(slice))
    val frame = MediaFrame(data, MediaFrame.Info(0, data.remaining(), 0, true), MediaFrame.Type.VIDEO, info)

    val es = mux(packet, frame)

    assertEquals(1, Utils.countOf(es, sps1080))
    assertEquals(1, Utils.countOf(es, pps1080))
  }

  @Test
  fun `GIVEN no sets at all WHEN a keyframe is muxed THEN nothing is written`() = runTest {
    val packet = H26XPacket(Constants.MTU - SrtPacket.headerSize, psiManager)

    val es = mux(packet, keyframe(0, null))

    assertEquals(0, es.size)
  }
}
