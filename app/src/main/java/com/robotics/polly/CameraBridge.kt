package com.robotics.polly

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
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
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    // Only compress and send when there are clients connected
                    if (wsServer.cameraClients.isNotEmpty()) {
                        try {
                            val jpeg = yuvToJpeg(imageProxy)
                            if (jpeg != null) {
                                wsServer.broadcastBinary(wsServer.cameraClients, jpeg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Frame conversion error: ${e.message}")
                        }
                    }
                    imageProxy.close()
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                isConnected = true
                Log.d(TAG, "Camera bound with ImageAnalysis")
                LogManager.success("Camera: Streaming ready")
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
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 70, out)
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

    companion object {
        private const val TAG = "CameraBridge"
    }
}
