package com.mapbox.vision.video.videosource.camera

import android.graphics.ImageFormat
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Size
import android.view.Surface

internal class Yuv2RgbRender(
        renderScript: RenderScript,
        previewSize: Size,
        private val renderActionsListener: RenderActionsListener
) : Allocation.OnBufferAvailableListener {

    private val inputNormalAllocation: Allocation
    private val outputAllocation: Allocation
    private val script = ScriptIntrinsicYuvToRGB.create(renderScript, Element.RGBA_8888(renderScript))

    init {
        val yuvTypeBuilder = Type.Builder(renderScript, Element.YUV(renderScript))
        yuvTypeBuilder.setX(previewSize.width)
        yuvTypeBuilder.setY(previewSize.height)
        yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888)
        inputNormalAllocation = Allocation.createTyped(
                renderScript,
                yuvTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT or Allocation.USAGE_SCRIPT
        )

        val rgbTypeBuilder = Type.Builder(renderScript, Element.RGBA_8888(renderScript))
        rgbTypeBuilder.setX(previewSize.width)
        rgbTypeBuilder.setY(previewSize.height)
        outputAllocation = Allocation.createTyped(
                renderScript,
                rgbTypeBuilder.create(),
                Allocation.USAGE_SCRIPT
        )

        script.setInput(inputNormalAllocation)
        inputNormalAllocation.setOnBufferAvailableListener(this)
    }

    fun getInputNormalSurface(): Surface {
        return inputNormalAllocation.surface
    }

    override fun onBufferAvailable(a: Allocation) {
        var millis = System.currentTimeMillis()
        // Get to newest input
        inputNormalAllocation.ioReceive()

//        println("RS onAvailable received ${System.currentTimeMillis() - millis}")
//        millis = System.currentTimeMillis()
        // Run processing pass
        script.forEach(outputAllocation)

//        println("RS onAvailable onEach ${System.currentTimeMillis() - millis}")
//        millis = System.currentTimeMillis()
        val array = renderActionsListener.getRgbBytesArray()
        outputAllocation.copyTo(array)
        println("RS duration : ${System.currentTimeMillis() - millis}")
        renderActionsListener.onDataReady()
    }

    fun release() {
        inputNormalAllocation.surface.release()
        inputNormalAllocation.destroy()
        outputAllocation.destroy()
    }
}
