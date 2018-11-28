package com.mapbox.vision.video.videosource

interface VideoSource {
    fun getSourceWidth(): Int

    fun getSourceHeight(): Int

    fun isAttached(): Boolean

    fun attach(videoSourceListener: VideoSourceListener)

    fun detach()

    fun release()

    fun stopVideoRecording()

    fun startVideoRecording()
}
