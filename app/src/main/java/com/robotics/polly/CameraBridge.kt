package com.robotics.polly

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    var isConnected = false
        private set

    /** Optional callback for recording frames. Called with (jpeg, timestampNs). */
    var recordingListener: ((ByteArray, Long) -> Unit)? = null

    /**
     * Starts camera with ImageAnalysis for WebSocket streaming.
     * Must be called from an activity that provides a LifecycleOwner.
     * Optionally accepts a Preview.SurfaceProvider for local display.
     */
    fun startWithLifecycle(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider? = null) {
        Log.d(TAG, "Starting CameraBridge")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                surfaceProvider?.let { preview.setSurfaceProvider(it) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 960))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    val hasClients = wsServer.cameraClients.isNotEmpty()
                    val hasRecording = recordingListener != null
                    if (hasClients || hasRecording) {
                        try {
                            val jpeg = yuvToJpeg(imageProxy)
                            if (jpeg != null) {
                                val ts = imageProxy.imageInfo.timestamp
                                if (hasClients) {
                                    wsServer.broadcastBinary(wsServer.cameraClients, jpeg)
                                }
                                recordingListener?.invoke(jpeg, ts)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Frame conversion error: ${e.message}")
                        }
                    }
                    imageProxy.close()
                }

                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                isConnected = true
                Log.d(TAG, "Camera bound with ImageAnalysis")
                LogManager.success("Camera: Streaming ready")

                // Lock AE/AWB after 3s convergence for consistent frames
                Handler(Looper.getMainLooper()).postDelayed({
                    lockExposure(camera)
                }, LOCK_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
                LogManager.error("Camera: Bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun yuvToJpeg(imageProxy: ImageProxy): ByteArray? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 format: Y + VU interleaved
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, out)
        return out.toByteArray()
    }

    fun stop() {
        Log.d(TAG, "Stopping CameraBridge")
        isConnected = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera: ${e.message}")
        }
        analyzerExecutor.shutdown()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun lockExposure(camera: androidx.camera.core.Camera) {
        try {
            val c2ctrl = Camera2CameraControl.from(camera.cameraControl)
            c2ctrl.setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
            )
            Log.i(TAG, "AE/AWB locked")
            LogManager.info("Camera: AE/AWB locked")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to lock AE/AWB: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CameraBridge"
        private const val LOCK_DELAY_MS = 3000L
    }
}
