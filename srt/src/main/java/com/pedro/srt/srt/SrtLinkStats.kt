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

/**
 * TVC fork [[contract:srt-link-stats]]: what the RECEIVER says about the link, as one snapshot.
 *
 * The sender's own queue only fills when the socket itself is back-pressured. When the bottleneck
 * is further along (a cell tower, a policing router) UDP writes never block, the queue stays empty
 * and a caller that watches only `hasCongestion()` believes the link is fine while almost nothing
 * gets through. The receiver knows better and says so in every ACK (RTT, receive rate) and NAK
 * (loss) — this carries those numbers to the application.
 *
 * All counters are cumulative since connect; rates are the receiver's own last report.
 *
 * @param rttUs smoothed RTT from the last full ACK, microseconds (0 = none yet)
 * @param packetsSent unique data packets sent (retransmissions excluded)
 * @param packetsRetransmitted data packets re-sent in answer to NAKs
 * @param packetsLost sequence numbers reported lost by NAKs (a periodic NAK may report a packet again)
 * @param peerReceiveRateBps the receiver's measured receive rate from the last full ACK, bits/s
 *   (0 = the receiver does not fill it)
 * @param peerPacketRate the receiver's measured packet rate, packets/s
 * @param peerLinkCapacity the receiver's link-capacity estimate, packets/s (0 = unknown)
 * @param ackReports full ACKs seen — lets a caller tell "no ACK since last tick" from "rate 0"
 */
data class SrtLinkStats(
  val rttUs: Int = 0,
  val packetsSent: Long = 0,
  val packetsRetransmitted: Long = 0,
  val packetsLost: Long = 0,
  val peerReceiveRateBps: Long = 0,
  val peerPacketRate: Int = 0,
  val peerLinkCapacity: Int = 0,
  val ackReports: Long = 0,
)
