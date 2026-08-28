package com.pedro.common.base

import android.util.Log
import com.pedro.common.BitrateManager
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamBlockingQueue
import com.pedro.common.frame.MediaFrame
import com.pedro.common.frame.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

abstract class BaseSender(
    protected val connectChecker: ConnectChecker,
    protected val TAG: String
) {

    @Volatile
    protected var running = false

    protected val queue = StreamBlockingQueue(400)

    protected val audioFramesSent = AtomicLong(0)
    protected val videoFramesSent = AtomicLong(0)
    private val droppedAudioFrames = AtomicLong(0)
    private val droppedVideoFrames = AtomicLong(0)

    private val bitrateManager: BitrateManager = BitrateManager(connectChecker)
    protected var isEnableLogs = true
    private var job: Job? = null
    protected val scope = CoroutineScope(Dispatchers.IO)

    protected val bytesSend = AtomicLong(0)
    protected val bytesSendPerSecond = AtomicLong(0)

    /**
     * [[contract:change-video-size-on-fly]] The parameter-set generation to bind to video frames.
     * A sender that sets this from setVideoInfo gets every VIDEO frame stamped with the generation
     * in force when it was queued, so a parameter-set change travels IN ORDER with the frames and a
     * keyframe still queued from the previous codec goes out with the sets it was encoded against.
     * Null (RTMP/RTSP/WHIP today): frames carry nothing and the packetizer applies its own copy at
     * dequeue time. Written on the encoder thread, read on the same thread per codec; a restart
     * joins the old codec thread before the new one emits, hence volatile is enough.
     */
    @Volatile
    protected var videoInfo: VideoInfo? = null

    abstract fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
    abstract fun setAudioInfo(sampleRate: Int, isStereo: Boolean)
    protected abstract suspend fun onRun()
    protected abstract suspend fun stopImp(clear: Boolean = true)

    fun sendMediaFrame(mediaFrame: MediaFrame) {
        val frame = if (mediaFrame.type == MediaFrame.Type.VIDEO) {
            videoInfo?.let { mediaFrame.copy(videoInfo = it) } ?: mediaFrame
        } else mediaFrame
        if (running && !queue.trySend(frame)) {
            when (mediaFrame.type) {
                MediaFrame.Type.VIDEO -> {
                    Log.i(TAG, "Video frame discarded")
                    droppedVideoFrames.incrementAndGet()
                }
                MediaFrame.Type.AUDIO -> {
                    Log.i(TAG, "Audio frame discarded")
                    droppedAudioFrames.incrementAndGet()
                }
            }
        }
    }

    fun start() {
        bitrateManager.reset()
        queue.clear()
        running = true
        job = scope.launch {
            val bitrateTask = async {
                while (scope.isActive && running) {
                    //bytes to bits
                    bitrateManager.calculateBitrate(bytesSendPerSecond.get() * 8)
                    bytesSendPerSecond.set(0)
                    delay(timeMillis = 1000)
                }
            }
            onRun()
        }
    }

    suspend fun stop(clear: Boolean = true) {
        running = false
        stopImp(clear)
        resetSentAudioFrames()
        resetSentVideoFrames()
        resetDroppedAudioFrames()
        resetDroppedVideoFrames()
        resetBytesSend()
        job?.cancelAndJoin()
        job = null
        queue.clear()
    }

    @Throws(IllegalArgumentException::class)
    fun hasCongestion(percentUsed: Float = 20f): Boolean {
        if (percentUsed !in 0.0..100.0) throw IllegalArgumentException("the value must be in range 0 to 100")
        val size = queue.getSize().toFloat()
        val remaining = queue.remainingCapacity().toFloat()
        val capacity = size + remaining
        return size >= capacity * (percentUsed / 100f)
    }

    fun resizeCache(newSize: Int) {
        if (newSize < queue.getSize()) {
            throw RuntimeException("Can't fit current cache inside new cache size")
        }
        queue.capacity = newSize
    }

    fun getCacheSize(): Int = queue.capacity

    fun getItemsInCache(): Int = queue.getSize()

    fun clearCache() {
        queue.clear()
    }

    fun getSentAudioFrames(): Long = audioFramesSent.get()

    fun getSentVideoFrames(): Long = videoFramesSent.get()

    fun getDroppedAudioFrames(): Long = droppedAudioFrames.get()

    fun getDroppedVideoFrames(): Long = droppedVideoFrames.get()

    fun getBytesSend(): Long = bytesSend.get()

    fun resetSentAudioFrames() {
        audioFramesSent.set(0)
    }

    fun resetSentVideoFrames() {
        videoFramesSent.set(0)
    }

    fun resetDroppedAudioFrames() {
        droppedAudioFrames.set(0)
    }

    fun resetDroppedVideoFrames() {
        droppedVideoFrames.set(0)
    }

    fun setLogs(enable: Boolean) {
        isEnableLogs = enable
    }

    fun setBitrateExponentialFactor(factor: Float) {
        bitrateManager.exponentialFactor = factor
    }

    fun getBitrateExponentialFactor() = bitrateManager.exponentialFactor

    fun setDelay(delay: Long) {
        queue.setCacheTime(delay)
    }

    fun resetBytesSend() {
        bytesSend.set(0)
    }
}