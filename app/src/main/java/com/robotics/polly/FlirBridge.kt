package com.robotics.polly

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.flir.flironesdk.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class FlirBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) : Device.Delegate, FrameProcessor.Delegate {

    private var flirDevice: Device? = null
    private var frameProcessor: FrameProcessor? = null
    private var sdkInitialized = false

    // Local listeners receive rendered bitmaps for fragment display
    val localListeners = CopyOnWriteArrayList<(Bitmap) -> Unit>()

    var isConnected = false
        private set

    fun start() {
        Log.d(TAG, "Starting FlirBridge")
        try {
            frameProcessor = FrameProcessor(
                context,
                this,
                EnumSet.of(RenderedImage.ImageType.ThermalLinearFlux14BitImage)
            )
            sdkInitialized = true
            Log.d(TAG, "FLIR SDK initialized")
            LogManager.success("FLIR: SDK initialized")

            Device.startDiscovery(context, this)
            LogManager.info("FLIR: Searching for camera...")
        } catch (e: UnsatisfiedLinkError) {
            sdkInitialized = false
            Log.e(TAG, "FLIR native library error: ${e.message}")
            LogManager.error("FLIR: Native library error")
        } catch (e: Exception) {
            sdkInitialized = false
            Log.e(TAG, "FLIR SDK init failed: ${e.message}")
            LogManager.error("FLIR: SDK init failed: ${e.message}")
        }
    }

    // Device.Delegate callbacks

    override fun onDeviceConnected(device: Device) {
        Log.d(TAG, "FLIR device connected")
        flirDevice = device
        isConnected = true
        LogManager.success("FLIR: Camera connected")

        try {
            device.startFrameStream(object : Device.StreamDelegate {
                override fun onFrameReceived(frame: Frame) {
                    try {
                        frameProcessor?.processFrame(frame)
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame processing error: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error starting frame stream: ${e.message}")
        }
    }

    override fun onDeviceDisconnected(device: Device) {
        Log.d(TAG, "FLIR device disconnected")
        flirDevice = null
        isConnected = false
        LogManager.info("FLIR: Camera disconnected")

        // Restart discovery
        if (sdkInitialized) {
            try {
                Device.startDiscovery(context, this)
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting discovery: ${e.message}")
            }
        }
    }

    override fun onTuningStateChanged(tuningState: Device.TuningState) {
        Log.d(TAG, "FLIR tuning state: $tuningState")
    }

    override fun onAutomaticTuningChanged(automatic: Boolean) {
        Log.d(TAG, "FLIR auto tuning: $automatic")
    }

    // FrameProcessor.Delegate callback

    override fun onFrameProcessed(renderedImage: RenderedImage) {
        try {
            val width = renderedImage.width()
            val height = renderedImage.height()
            val pixelData = renderedImage.pixelData()

            // Find min/max for auto-scaling
            var minVal = 65535
            var maxVal = 0
            for (i in 0 until (width * height)) {
                val value = ((pixelData[i * 2].toInt() and 0xFF) or
                    ((pixelData[i * 2 + 1].toInt() and 0xFF) shl 8)) and 0x3FFF
                if (value < minVal) minVal = value
                if (value > maxVal) maxVal = value
            }

            // Forward raw thermal data to WebSocket clients
            if (wsServer.flirClients.isNotEmpty()) {
                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                header.putShort(width.toShort())
                header.putShort(height.toShort())
                header.putInt(minVal)
                header.putInt(maxVal)
                val message = header.array() + pixelData
                wsServer.broadcastBinary(wsServer.flirClients, message)
            }

            // Render bitmap for local display
            if (localListeners.isNotEmpty()) {
                val range = maxVal - minVal
                if (range == 0) return

                val pixels = IntArray(width * height)
                for (i in 0 until (width * height)) {
                    val value = ((pixelData[i * 2].toInt() and 0xFF) or
                        ((pixelData[i * 2 + 1].toInt() and 0xFF) shl 8)) and 0x3FFF
                    val normalized = ((value - minVal) * 255 / range).coerceIn(0, 255)

                    val r: Int
                    val g: Int
                    val b: Int
                    when {
                        normalized < 64 -> {
                            r = (normalized * 2).coerceIn(0, 255)
                            g = 0
                            b = (normalized * 4).coerceIn(0, 255)
                        }
                        normalized < 128 -> {
                            val adj = normalized - 64
                            r = 128 + adj * 2
                            g = 0
                            b = 255 - adj * 4
                        }
                        normalized < 192 -> {
                            val adj = normalized - 128
                            r = 255
                            g = adj * 4
                            b = 0
                        }
                        else -> {
                            val adj = normalized - 192
                            r = 255
                            g = 255
                            b = adj * 4
                        }
                    }
                    pixels[i] = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }

                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                for (listener in localListeners) {
                    try {
                        listener(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Local listener error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping FlirBridge")
        try {
            Device.stopDiscovery()
        } catch (e: Exception) { /* ignore */ }
        flirDevice = null
        frameProcessor = null
        isConnected = false
    }

    companion object {
        private const val TAG = "FlirBridge"
    }
}
