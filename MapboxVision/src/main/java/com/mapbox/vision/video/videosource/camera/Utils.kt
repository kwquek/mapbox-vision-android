package com.mapbox.vision.video.videosource.camera

import android.util.Size

internal fun chooseOptimalCameraResolution(
        choices: List<Size>,
        desiredSize: Size
): Size {
    val minDimension = Math.min(desiredSize.width, desiredSize.height)

    val bigEnough = mutableListOf<Size>()
    for (option in choices) {
        if (option == desiredSize) {
            return desiredSize
        }

        if (option.height >= minDimension && option.width >= minDimension) {
            bigEnough.add(option)
        }
    }

    // Pick the smallest of those, assuming we found any
    return bigEnough.minBy { it.width.toLong() * it.height }
           ?: choices.first()
}

