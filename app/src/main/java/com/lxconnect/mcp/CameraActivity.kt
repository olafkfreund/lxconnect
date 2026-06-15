package com.lxconnect.mcp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class CameraActivity : Activity() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBackgroundThread()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                setResultAndFinish(null, "No camera found on device")
                return
            }

            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setResultAndFinish(null, "Camera permission not granted")
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    takePicture()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    setResultAndFinish(null, "Camera error: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            setResultAndFinish(null, e.message ?: "Failed to open camera")
        }
    }

    private fun takePicture() {
        val camera = cameraDevice ?: return
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(camera.id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.firstOrNull() ?: android.util.Size(640, 480)

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    setResultAndFinish(base64, null)
                } else {
                    setResultAndFinish(null, "Failed to acquire image from reader")
                }
            }, backgroundHandler)

            val targets = listOf(imageReader!!.surface)
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader!!.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        session.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                super.onCaptureFailed(session, request, failure)
                                setResultAndFinish(null, "Capture failed: ${failure.reason}")
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        setResultAndFinish(null, e.message)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    setResultAndFinish(null, "Configure session failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            setResultAndFinish(null, e.message)
        }
    }

    private fun setResultAndFinish(base64: String?, error: String?) {
        synchronized(CameraActivity::class.java) {
            latestResult = CameraResult(base64, error)
            latch?.countDown()
        }
        cleanup()
        finish()
    }

    private fun cleanup() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Stop background thread error", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                setResultAndFinish(null, "Camera permission denied by user")
            }
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    data class CameraResult(val base64: String?, val error: String?)

    companion object {
        private const val TAG = "McpCameraActivity"
        var latch: java.util.concurrent.CountDownLatch? = null
        var latestResult: CameraResult? = null

        fun capturePhoto(context: Context): CameraResult {
            val latchInstance = java.util.concurrent.CountDownLatch(1)
            latch = latchInstance
            latestResult = null

            val intent = android.content.Intent(context, CameraActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // Wait up to 8 seconds for the picture to be captured
            latchInstance.await(8, java.util.concurrent.TimeUnit.SECONDS)
            return latestResult ?: CameraResult(null, "Camera capture timed out")
        }
    }
}
