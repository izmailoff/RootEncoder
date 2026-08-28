package com.pedro.common.frame

import java.nio.ByteBuffer

data class MediaFrame @JvmOverloads constructor(
    val data: ByteBuffer,
    val info: Info,
    val type: Type,
    /**
     * [[contract:change-video-size-on-fly]] The parameter sets this VIDEO frame was encoded
     * against, stamped by the sender at enqueue time (BaseSender.sendMediaFrame) when it opts in;
     * null for audio, and for senders that still apply parameter sets out of band.
     */
    val videoInfo: VideoInfo? = null
) {
    data class Info(
        val offset: Int,
        val size: Int,
        val timestamp: Long,
        val isKeyFrame: Boolean,
        val flags: Int = 0
    )

    enum class Type {
        VIDEO, AUDIO
    }
}
