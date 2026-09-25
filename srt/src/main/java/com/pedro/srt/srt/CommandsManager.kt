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

package com.pedro.srt.srt

import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.TimeUtils
import com.pedro.common.VideoCodec
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.srt.packets.DataPacket
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.srt.packets.control.Ack2
import com.pedro.srt.srt.packets.control.KeepAlive
import com.pedro.srt.srt.packets.control.Shutdown
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.data.KeyBasedEncryption
import com.pedro.srt.utils.Constants
import com.pedro.srt.utils.EncryptInfo
import com.pedro.srt.utils.EncryptionUtil
import com.pedro.srt.utils.SrtSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.random.Random

/**
 * Created by pedro on 23/8/23.
 */
class CommandsManager {

  private val TAG = "CommandsManager"
  //used for packet lost
  private val packetHandlingQueue = mutableListOf<DataPacket>()

  var sequenceNumber: Int = generateInitialSequence()
  var messageNumber = 1
  var MTU = Constants.MTU
  var socketId = 0
  //own socket id, must be unique per connection or the server can discard the handshake as duplicated
  private var localSocketId = generateSocketId()
  var startTS = 0L //microSeconds
  var audioDisabled = false
  var videoDisabled = false
  var host = ""
  var latency = 120 //in millis
  //Avoid write a packet in middle of other.
  private val writeSync = Mutex(locked = false)
  private var encryptor: EncryptionUtil? = null
  var videoCodec = VideoCodec.H264
  var audioCodec = AudioCodec.AAC
  /** TVC fork [[contract:srt-link-stats]]: unique data packets written / packets re-sent for a NAK. */
  @Volatile var packetsSent = 0L
    private set
  @Volatile var packetsRetransmitted = 0L
    private set

  fun setPassphrase(passphrase: String, type: EncryptionType) {
    encryptor = if (passphrase.isEmpty() || type == EncryptionType.NONE) null else EncryptionUtil(type, passphrase)
  }

  fun getEncryptInfo(): EncryptInfo? {
    return encryptor?.getEncryptInfo()
  }

  fun getEncryptType(): EncryptionType {
    return encryptor?.type ?: EncryptionType.NONE
  }

  fun encryptionEnabled() = encryptor != null

  fun loadStartTs() {
    startTS = TimeUtils.getCurrentTimeMicro()
    localSocketId = generateSocketId()
  }

  fun getTs(): Int {
    return (TimeUtils.getCurrentTimeMicro() - startTS).toInt()
  }

  @Throws(IOException::class)
  suspend fun writeHandshake(socket: SrtSocket?, handshake: Handshake = Handshake()) {
    writeSync.withLock {
      handshake.initialPacketSequence = sequenceNumber
      handshake.srtSocketId = localSocketId
      handshake.ipAddress = host
      handshake.write(getTs(), 0)
      Log.i(TAG, handshake.toString())
      socket?.write(handshake)
    }
  }

  /**
   * TVC fork: skip (a bounded number of) packets that are not a handshake. A reconnect under
   * congestion can find the peer's ACK/NAK/keep-alive for the connection being replaced still in
   * flight, and failing the whole reconnect on the first of them ("unexpected response type: Ack",
   * seen on the emulator 2026-09-26) turns one lost handshake into a retry loop.
   */
  @Throws(IOException::class)
  suspend fun readHandshake(socket: SrtSocket?): Handshake {
    var last: SrtPacket? = null
    repeat(MAX_STRAY_PACKETS) {
      val handshakeBuffer = socket?.readBuffer() ?: throw IOException("read buffer failed, socket disconnected")
      val packet = SrtPacket.getSrtPacket(handshakeBuffer)
      if (packet is Handshake) {
        Log.i(TAG, packet.toString())
        return packet
      }
      last = packet
    }
    throw IOException("unexpected response type: ${last?.javaClass?.name}")
  }

  private companion object {
    const val MAX_STRAY_PACKETS = 32
  }

  @Throws(IOException::class)
  suspend fun writeData(packet: MpegTsPacket, socket: SrtSocket?): Int {
    writeSync.withLock {
      if (sequenceNumber.toUInt() > 0x7FFFFFFFu) sequenceNumber = 0
      val dataPacket = DataPacket(
        encryption = if (encryptor != null) KeyBasedEncryption.PAIR_KEY else KeyBasedEncryption.NONE,
        sequenceNumber = sequenceNumber,
        packetPosition = packet.packetPosition,
        messageNumber = messageNumber++,
        payload = encryptor?.encrypt(packet.buffer, sequenceNumber) ?: packet.buffer,
        ts = getTs(),
        socketId = socketId
      )
      sequenceNumber++
      packetHandlingQueue.add(dataPacket)
      dropTooLatePackets(dataPacket.ts)
      dataPacket.write()
      dataPacket.lastSentUs = TimeUtils.getCurrentTimeMicro()
      socket?.write(dataPacket)
      packetsSent++
      return dataPacket.getSize()
    }
  }

  /**
   * TVC fork [[contract:srt-link-stats]]: a packet already RE-sent within [minIntervalUs] is NOT
   * sent again (the first retransmission always goes: the NAK that asks for it arrives about one RTT
   * after the original send, which a plain interval would have swallowed). Receivers repeat their loss list in periodic NAKs (every ~RTT/2 or 20 ms), and the
   * stock loop answered every repeat with another copy — under congestion that multiplied the load on
   * the very link that was dropping, so the repair traffic itself kept the link saturated. libsrt
   * spaces retransmissions the same way. [minIntervalUs] = 0 keeps the stock behaviour.
   */
  @Throws(IOException::class)
  suspend fun reSendPackets(lostRanges: List<Pair<Int, Int>>, socket: SrtSocket?, minIntervalUs: Long = 0L) {
    writeSync.withLock {
      val now = TimeUtils.getCurrentTimeMicro()
      val dataPackets = packetHandlingQueue.filter { packet ->
        lostRanges.any { (min, max) ->
          ((packet.sequenceNumber - min) and 0x7FFFFFFF) <= ((max - min) and 0x7FFFFFFF)
        } && (!packet.retransmitted || now - packet.lastSentUs >= minIntervalUs)
      }
      dataPackets.forEach { packet ->
        packet.retransmitted = true
        packet.write()
        packet.lastSentUs = now
        socket?.write(packet)
        packetsRetransmitted++
      }
    }
  }

  suspend fun updateHandlingQueue(lastPacketSequence: Int) {
    writeSync.withLock {
      packetHandlingQueue.removeAll {
        //discard confirmed packets
        val diff = (lastPacketSequence - it.sequenceNumber) and 0x7FFFFFFF
        diff in 1 until 0x40000000
      }
    }
  }

  private fun dropTooLatePackets(nowTs: Int) {
    val thresholdUs = latency * 1000
    val firstKept = packetHandlingQueue.indexOfFirst { (nowTs - it.ts) <= thresholdUs }
    if (firstKept > 0) packetHandlingQueue.subList(0, firstKept).clear()
  }

  @Throws(IOException::class)
  suspend fun writeAck2(ackSequence: Int, socket: SrtSocket?) {
    writeSync.withLock {
      val ack2 = Ack2(ackSequence)
      ack2.write(getTs(), socketId)
      socket?.write(ack2)
    }
  }

  @Throws(IOException::class)
  suspend fun writeShutdown(socket: SrtSocket?) {
    writeSync.withLock {
      val shutdown = Shutdown()
      shutdown.write(getTs(), socketId)
      socket?.write(shutdown)
    }
  }

  @Throws(IOException::class)
  suspend fun writeKeepAlive(socket: SrtSocket?) {
    writeSync.withLock {
      val keepAlive = KeepAlive()
      keepAlive.write(getTs(), socketId)
      socket?.write(keepAlive)
    }
  }

  fun reset() {
    sequenceNumber = generateInitialSequence()
    messageNumber = 1
    MTU = Constants.MTU
    socketId = 0
    startTS = 0L
    host = ""
    packetHandlingQueue.clear()
    packetsSent = 0L
    packetsRetransmitted = 0L
  }

  private fun generateInitialSequence(): Int {
    return Random.nextInt(0, Int.MAX_VALUE)
  }

  private fun generateSocketId(): Int {
    return Random.nextInt(1, Int.MAX_VALUE)
  }
}