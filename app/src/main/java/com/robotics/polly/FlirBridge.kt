package com.robotics.polly

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class FlirBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) : FlirUsbDriver.FrameListener {

    private var driver: FlirUsbDriver? = null

    // Local listeners receive rendered bitmaps for fragment display
    val localListeners = CopyOnWriteArrayList<(Bitmap) -> Unit>()

    var isConnected = false
        private set

    // EMA-smoothed min/max for stable display normalization
    private var smoothMin = -1.0
    private var smoothMax = -1.0

    fun start() {
        Log.d(TAG, "Starting FlirBridge (USB driver)")
        LogManager.info("FLIR: Starting USB driver...")

        val drv = FlirUsbDriver(context)
        drv.frameListener = this
        driver = drv
        drv.start()
    }

    // FlirUsbDriver.FrameListener callbacks

    override fun onFrame(frame: FlirUsbDriver.ThermalFrame) {
        isConnected = true

        val width = frame.width
        val height = frame.height
        val n = width * height

        // Build raw uint16 pixel byte array (LE) for WebSocket, matching existing wire format
        val pixelData = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = frame.rawPixels[i]
            pixelData[i * 2] = (v and 0xFF).toByte()
            pixelData[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }

        // Forward raw thermal data to WebSocket clients
        // Wire format: 12-byte header (u16 width, u16 height, u32 min, u32 max) + pixel data
        if (wsServer.flirClients.isNotEmpty()) {
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            header.putShort(width.toShort())
            header.putShort(height.toShort())
            header.putInt(frame.minVal)
            header.putInt(frame.maxVal)
            val message = header.array() + pixelData
            wsServer.broadcastBinary(wsServer.flirClients, message)
        }

        // Render bitmap for local display with EMA-smoothed percentile range
        if (localListeners.isNotEmpty()) {
            // Mask to 14-bit and find percentiles
            val values = IntArray(n)
            for (i in 0 until n) {
                values[i] = frame.rawPixels[i] and 0x3FFF
            }
            values.sort()
            val p2 = values[(n * 0.02).toInt()]
            val p98 = values[(n * 0.98).toInt()]

            // Update smoothed range (EMA prevents per-frame jumps)
            if (smoothMin < 0) {
                smoothMin = p2.toDouble()
                smoothMax = p98.toDouble()
            } else {
                smoothMin = smoothMin * (1 - EMA_ALPHA) + p2 * EMA_ALPHA
                smoothMax = smoothMax * (1 - EMA_ALPHA) + p98 * EMA_ALPHA
            }

            // Enforce minimum range to prevent noise amplification
            var displayMin = smoothMin.toInt()
            var displayMax = smoothMax.toInt()
            val currentRange = displayMax - displayMin
            if (currentRange < MIN_RANGE) {
                val mid = (displayMin + displayMax) / 2
                displayMin = mid - MIN_RANGE / 2
                displayMax = mid + MIN_RANGE / 2
            }
            val range = displayMax - displayMin

            val pixels = IntArray(width * height)
            for (i in 0 until (width * height)) {
                val value = frame.rawPixels[i] and 0x3FFF
                val normalized = ((value - displayMin) * 255 / range).coerceIn(0, 255)

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
    }

    override fun onFfcEvent() {
        Log.d(TAG, "FFC event — resetting EMA")
        smoothMin = -1.0
        smoothMax = -1.0
    }

    /** Called by BridgeService reconnect watchdog when not connected. */
    fun reconnect() {
        if (isConnected) return
        Log.d(TAG, "External reconnect requested")
        LogManager.info("FLIR: Retrying USB connection...")
        if (driver == null) {
            start()
        } else {
            driver?.reconnect()
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping FlirBridge")
        driver?.stop()
        driver = null
        isConnected = false
        smoothMin = -1.0
        smoothMax = -1.0
    }

    companion object {
        private const val TAG = "FlirBridge"
        private const val EMA_ALPHA = 0.1   // smoothing factor (~10 frames to settle at 8fps)
        private const val MIN_RANGE = 500    // minimum flux range to prevent noise amplification
    }
}
