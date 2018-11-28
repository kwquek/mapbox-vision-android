package com.mapbox.vision.video.videosource.camera

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.RenderScript
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.WindowManager
import com.mapbox.vision.camera.CameraParamsListener
import com.mapbox.vision.camera.CameraParamsRequestsManager
import com.mapbox.vision.models.CameraParamsData
import com.mapbox.vision.utils.FileUtils
import com.mapbox.vision.video.videosource.VideoSource
import com.mapbox.vision.video.videosource.VideoSourceListener
import java.io.File
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class CameraVideoSourceImpl(
        val application: Application,
        val width: Int,
        val height: Int
) : VideoSource, RenderActionsListener {

    // TODO: Move it late
    private var cameraParamsRequestsManager: CameraParamsRequestsManager? = null

    private var videoSourceListener: VideoSourceListener? = null

    private lateinit var yuv2RgbRender: Yuv2RgbRender

    private lateinit var previewSize: Size

    private lateinit var rgbaBytes: ByteArray
    private lateinit var rgbBitmap: Bitmap

    private var captureSession: CameraCaptureSession? = null

    // Prevents app from exiting before closing the camera.
    private val cameraOpenCloseLock = Semaphore(1)

    private var cameraDevice: CameraDevice? = null

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(currentCameraDevice: CameraDevice) {
            cameraDevice = currentCameraDevice
            cameraParamsRequestsManager?.startDataRequesting()
            createCameraPreviewSession()
            cameraOpenCloseLock.release()
        }

        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            currentCameraDevice.close()
            cameraDevice = null
            cameraOpenCloseLock.release()
        }

        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            currentCameraDevice.close()
            cameraDevice = null
            cameraOpenCloseLock.release()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureProgressed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                partialResult: CaptureResult
        ) {}

        override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
        ) {}
    }

    private var nextVideoFilePath: String? = null

    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingVideo = false

    private val recordingSurface = MediaCodec.createPersistentInputSurface()

    private var currentBufferNum = 0

    private var sensorOrientation = 0

    private val buffersDataDir = FileUtils.getDataDirPath(application)

    override fun getSourceWidth(): Int = previewSize.width

    override fun getSourceHeight(): Int = previewSize.height

    override fun isAttached(): Boolean = videoSourceListener != null

    override fun attach(videoSourceListener: VideoSourceListener) {
        this.videoSourceListener = videoSourceListener
        startBackgroundThread()
        openSource()
    }

    override fun detach() {
        closeSource()
        stopBackgroundThread()
        cameraParamsRequestsManager?.stopDataRequesting()
        cameraParamsRequestsManager = null
        videoSourceListener = null
    }

    override fun release() {
        yuv2RgbRender.release()
        releaseMediaRecorder()
    }

    override fun stopVideoRecording() {
        if (!isRecordingVideo) {
            return
        }
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder?.reset()
        }
        isRecordingVideo = false

        val filePath = nextVideoFilePath
        if (filePath != null && !filePath.isBlank()) {
            videoSourceListener?.onFileRecorded(filePath)
        }

        nextVideoFilePath = null
    }

    override fun startVideoRecording() {
        updateNextBufferFile()
        mediaRecorder?.setup(nextVideoFilePath!!)
        mediaRecorder?.start()
        isRecordingVideo = true
    }

    override fun getRgbBytesArray(): ByteArray = rgbaBytes

    override fun onDataReady() {
        videoSourceListener?.onNewFrame(rgbaBytes)
    }

    @SuppressLint("MissingPermission")
    private fun openSource() {
        val cameraId = setUpCameraCharacteristics()
        yuv2RgbRender = Yuv2RgbRender(RenderScript.create(application), previewSize, this)
        mediaRecorder = MediaRecorder()

        val manager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /** Closes the current [CameraDevice].  */
    private fun closeSource() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
                captureSession = null
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
                cameraDevice = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(HANDLE_THREAD_NAME)
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /** Creates a new [CameraCaptureSession] for camera preview.  */
    private fun createCameraPreviewSession() {
        try {

            updateNextBufferFile()
            mediaRecorder?.setup(nextVideoFilePath!!)

            cameraDevice?.let { camera ->

                val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(yuv2RgbRender.getInputNormalSurface())
                    addTarget(recordingSurface)
                }

                camera.createCaptureSession(
                        listOf(
                                yuv2RgbRender.getInputNormalSurface(),
                                recordingSurface
                        ),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                                cameraOpenCloseLock.acquire()
                                if (cameraDevice == null) {
                                    cameraOpenCloseLock.release()
                                    return
                                }

                                captureSession = cameraCaptureSession

                                isRecordingVideo = true
                                mediaRecorder?.start()

                                try {
                                    // Auto focus should be continuous for camera preview.
                                    previewRequestBuilder.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    )

                                    cameraCaptureSession.setRepeatingRequest(
                                            previewRequestBuilder.build(),
                                            captureCallback,
                                            backgroundHandler
                                    )
                                } catch (e: CameraAccessException) {
                                    e.printStackTrace()
                                } finally {
                                    cameraOpenCloseLock.release()
                                }
                            }

                            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                                // TODO: Proceed edge case
                            }
                        },
                        null
                )
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun setUpCameraCharacteristics(): String {
        val manager = application.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                          ?: continue

                previewSize = chooseOptimalCameraResolution(
                        choices = map.getOutputSizes(SurfaceTexture::class.java).toList(),
                        desiredSize = Size(width, height)
                )
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

                val focalLength = characteristics
                                          .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                          ?.firstOrNull()
                                  ?: 0f
                rgbaBytes = ByteArray(previewSize.width * previewSize.height * 4)
                rgbBitmap = Bitmap.createBitmap(previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888)

                cameraParamsRequestsManager = CameraParamsRequestsManager(
                        previewSize,
                        focalLength,
                        sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
                )
                        .also {
                            it.cameraParamsListener = object : CameraParamsListener {
                                override fun onCameraParamsReady(cameraParamsData: CameraParamsData) {
                                    videoSourceListener?.onNewCameraParams(cameraParamsData)
                                }
                            }
                        }

                return cameraId
            }

            return ""
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            return ""
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            // TODO:Error handling
            e.printStackTrace()
            return ""
        }
    }

    @Throws(IOException::class)
    private fun MediaRecorder.setup(nextVideoFilePath: String) {
        val rotation = (application.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(nextVideoFilePath)
        setVideoEncodingBitRate(6000000)
        setVideoFrameRate(10)
        setVideoSize(previewSize.width, previewSize.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setInputSurface(recordingSurface)
        prepare()
    }

    private fun updateNextBufferFile() {
        if (nextVideoFilePath.isNullOrEmpty()) {
            nextVideoFilePath = FileUtils.getVideoFilePath(buffersDataDir, BUFFER_FILE_NAMES[currentBufferNum])
            val bufferFile = File(nextVideoFilePath)
            if (bufferFile.exists()) {
                bufferFile.delete()
            }
            currentBufferNum++
            if (currentBufferNum >= VIDEO_BUFFERS_NUMBER) {
                currentBufferNum = 0
            }
        }
    }

    /**
     * Release media recorder instance
     */
    private fun releaseMediaRecorder() {
        mediaRecorder?.release()
        recordingSurface.release()
        mediaRecorder = null
    }

    companion object {
        private const val VIDEO_BUFFERS_NUMBER = 3

        private const val HANDLE_THREAD_NAME = "CameraBackground"

        private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
        private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

        private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }

        private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
            append(Surface.ROTATION_0, 270)
            append(Surface.ROTATION_90, 180)
            append(Surface.ROTATION_180, 90)
            append(Surface.ROTATION_270, 0)
        }

        val BUFFER_FILE_NAMES = listOf(
                "video1.mp4",
                "video2.mp4",
                "video3.mp4"
        )
    }
}
