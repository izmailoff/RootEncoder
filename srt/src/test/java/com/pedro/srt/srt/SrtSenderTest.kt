package com.pedro.srt.srt

import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.srt.Utils
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.utils.Constants
import com.pedro.srt.utils.SrtSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(MockitoJUnitRunner::class)
class SrtSenderTest {

    @Mock
    lateinit var connectChecker: ConnectChecker
    @Mock
    lateinit var socket: SrtSocket
    @Mock
    lateinit var commandsManager: CommandsManager
    private val output = ByteArrayOutputStream()
    private var latch = CountDownLatch(7)
    // Held closed to keep the sender thread parked in its very first write (the PSI tables) while
    // a test builds a backlog behind it; open (the default) lets the queue drain.
    private var writeGate = CountDownLatch(0)

    @Before
    fun setup() = runTest {
        output.reset()
        Mockito.`when`(commandsManager.audioCodec).thenReturn(AudioCodec.AAC)
        Mockito.`when`(commandsManager.videoCodec).thenReturn(VideoCodec.H264)
        Mockito.`when`(commandsManager.MTU).thenReturn(Constants.MTU)
        Mockito.lenient().`when`(commandsManager.writeData(any<MpegTsPacket>(), any<SrtSocket>())).then {
            writeGate.await(5000, TimeUnit.MILLISECONDS)
            val packet = it.arguments[0] as MpegTsPacket
            val size = packet.buffer.size
            output.write(packet.buffer)
            latch.countDown().let { size }
        }
    }

    @Test
    fun `GIVEN video and audio mediaFrames WHEN send to sender THEN write the expected packets`() = runTest {
        latch = CountDownLatch(7) //writeData must be called 4 times
        val srtSender = SrtSender(connectChecker, commandsManager)
        srtSender.setAudioInfo(44100, true)
        val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 103, 100, 0, 30, -84, -76, 15, 2, -115, 53, 2, 2, 2, 7, -117, 23, 8))
        val pps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 104, -18, 13, -117))
        srtSender.setVideoInfo(sps, pps, null)
        srtSender.socket = socket
        srtSender.start()

        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x05)
        val videoData = ByteBuffer.wrap(header.plus(ByteArray(300) { 0x00 }))
        val audioData = ByteBuffer.wrap(ByteArray(256) { 0x00 })

        val videoFrame = MediaFrame(videoData, MediaFrame.Info(0, videoData.remaining(), 0, true), MediaFrame.Type.VIDEO)
        val audioFrame = MediaFrame(audioData, MediaFrame.Info(0, audioData.remaining(), 0, false), MediaFrame.Type.AUDIO)
        srtSender.sendMediaFrame(videoFrame)
        srtSender.sendMediaFrame(audioFrame)
        latch.await(1000, TimeUnit.MILLISECONDS)
        srtSender.stop()

        assertEquals(1692, output.toByteArray().size)
    }

    /**
     * [[contract:change-video-size-on-fly]] A rung change restarts the encoder under congestion,
     * so its new SPS/PPS reach setVideoInfo while keyframes from the old codec are still queued.
     * The sets must travel in queue order with the frames: the queued old keyframe goes out with
     * the old SPS, the first new keyframe with the new one.
     */
    @Test
    fun `GIVEN a queued keyframe WHEN new video info arrives before it is sent THEN it keeps the sets it was queued with`() = runTest {
        latch = CountDownLatch(4)
        writeGate = CountDownLatch(1)
        val srtSender = SrtSender(connectChecker, commandsManager)
        srtSender.setAudioInfo(44100, true)
        val sps1080 = byteArrayOf(0, 0, 0, 1, 103, 100, 0, 40, -84, 44, -84, 7, -128, 34, 126, 92, 5, -88, 8, 8, 10)
        val pps1080 = byteArrayOf(0, 0, 0, 1, 104, -18, 60, -80)
        val sps720 = byteArrayOf(0, 0, 0, 1, 103, 100, 0, 31, -84, 44, -84, 5, 0, 91, -95, 0, 0, 3, 0, 1, 0)
        val pps720 = byteArrayOf(0, 0, 0, 1, 104, -18, 60, -80, 16)
        srtSender.setVideoInfo(ByteBuffer.wrap(sps1080), ByteBuffer.wrap(pps1080), null)
        srtSender.socket = socket
        srtSender.start()

        val idr = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, -120, -128)
        fun keyframe(ts: Long): MediaFrame {
            val data = ByteBuffer.wrap(idr.plus(ByteArray(300) { (it % 5).toByte() }))
            return MediaFrame(data, MediaFrame.Info(0, data.remaining(), ts, true), MediaFrame.Type.VIDEO)
        }
        // The sender is parked on its PSI write: everything below piles up in the queue.
        srtSender.sendMediaFrame(keyframe(0))
        srtSender.setVideoInfo(ByteBuffer.wrap(sps720), ByteBuffer.wrap(pps720), null) // resized codec's formatChanged
        srtSender.sendMediaFrame(keyframe(40_000))
        writeGate.countDown()
        latch.await(2000, TimeUnit.MILLISECONDS)
        srtSender.stop()

        val ts = output.toByteArray()
        // Search every PID: the PSI tables cannot contain these SPS bodies.
        val es = Utils.tsPayloadAll(ts)
        val at1080 = Utils.indexOf(es, sps1080)
        val at720 = Utils.indexOf(es, sps720)
        assertTrue("queued 1080p keyframe went out without its SPS", at1080 >= 0)
        assertTrue("720p keyframe went out without its SPS", at720 >= 0)
        assertTrue("1080p SPS must precede the 720p SPS", at1080 < at720)
        assertEquals(1, Utils.countOf(es, sps1080))
        assertEquals(1, Utils.countOf(es, sps720))
    }
}
