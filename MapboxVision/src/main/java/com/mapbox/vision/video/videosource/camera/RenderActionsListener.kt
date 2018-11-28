package com.mapbox.vision.video.videosource.camera


interface RenderActionsListener {

    fun getRgbBytesArray(): ByteArray

    fun onDataReady()
}
