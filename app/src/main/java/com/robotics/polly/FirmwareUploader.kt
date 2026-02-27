package com.robotics.polly

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates firmware upload: receives hex via WebSocket, pauses normal serial operation,
 * programs the Arduino via STK500v1, then resumes normal operation.
 */
class FirmwareUploader(
    private val wsServer: PollyWebSocketServer,
    private val getSerialPort: () -> UsbSerialPort?,
    private val pauseNormalOperation: () -> Unit,
    private val resumeNormalOperation: () -> Unit
) {
    companion object {
        private const val TAG = "FirmwareUploader"
    }

    val firmwareClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()
    private val uploading = AtomicBoolean(false)

    fun createWebSocket(handshake: fi.iki.elonen.NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        return FirmwareWebSocket(handshake)
    }

    private fun broadcastStatus(message: String) {
        val json = """{"firmware":${org.json.JSONObject.quote(message)}}"""
        wsServer.broadcastText(firmwareClients, json)
    }

    private var lastBroadcastPercent = -1

    private fun broadcastProgress(phase: String, percent: Int) {
        // Throttle: only send when percent changes by >= 2 or phase is non-page-write
        val isPageWrite = phase.startsWith("Writing page")
        if (isPageWrite && percent - lastBroadcastPercent < 2) return
        lastBroadcastPercent = percent

        val json = """{"phase":${org.json.JSONObject.quote(phase)},"percent":$percent}"""
        try {
            wsServer.broadcastText(firmwareClients, json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast progress: ${e.message}")
        }
    }

    private fun broadcastResult(success: Boolean, message: String) {
        val json = """{"done":true,"success":$success,"message":${org.json.JSONObject.quote(message)}}"""
        try {
            wsServer.broadcastText(firmwareClients, json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast result: ${e.message}")
        }
    }

    private fun handleHexUpload(hexContent: String) {
        if (!uploading.compareAndSet(false, true)) {
            broadcastResult(false, "Upload already in progress")
            return
        }

        Thread({
            try {
                doUpload(hexContent)
            } catch (e: Exception) {
                Log.e(TAG, "Firmware upload failed", e)
                broadcastResult(false, "Upload failed: ${e.message}")
                // Always try to resume normal operation
                try {
                    resumeNormalOperation()
                } catch (re: Exception) {
                    Log.e(TAG, "Failed to resume after error", re)
                }
            } finally {
                uploading.set(false)
            }
        }, "Firmware-Upload").start()
    }

    private fun doUpload(hexContent: String) {
        lastBroadcastPercent = -1

        // 1. Parse hex file
        broadcastProgress("Parsing hex file", 0)
        val image = try {
            IntelHexParser.parse(hexContent)
        } catch (e: Exception) {
            throw IOException("Invalid hex file: ${e.message}")
        }

        Log.d(TAG, "Parsed hex: ${image.data.size} bytes, ${image.pages.size} pages")
        broadcastProgress("Parsed: ${image.pages.size} pages (${image.data.size} bytes)", 2)

        // 2. Pause normal operation (stops streaming, disconnects read/write threads)
        broadcastProgress("Pausing normal operation", 3)
        pauseNormalOperation()

        // Brief delay for threads to stop
        Thread.sleep(500)

        // 3. Get the serial port
        val port = getSerialPort()
            ?: throw IOException("Serial port not available")

        // 4. Program via STK500v1
        val programmer = Stk500Programmer(port) { phase, percent ->
            broadcastProgress(phase, percent)
        }
        programmer.program(image.pages)

        // 5. Resume normal operation
        broadcastProgress("Resuming normal operation", 98)
        Thread.sleep(2000) // Wait for Arduino to boot with new firmware
        resumeNormalOperation()

        broadcastResult(true, "Firmware uploaded successfully (${image.pages.size} pages, ${image.data.size} bytes)")
        LogManager.success("Firmware upload complete: ${image.data.size} bytes")
    }

    inner class FirmwareWebSocket(
        handshake: fi.iki.elonen.NanoHTTPD.IHTTPSession
    ) : NanoWSD.WebSocket(handshake) {

        override fun onOpen() {
            firmwareClients.add(this)
            Log.d(TAG, "[firmware] Client connected (${firmwareClients.size} total)")
            LogManager.info("[firmware] Client connected")
            try {
                val status = if (uploading.get()) "uploading" else "ready"
                send("""{"status":"$status"}""")
            } catch (_: Exception) {}
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            firmwareClients.remove(this)
            Log.d(TAG, "[firmware] Client disconnected (${firmwareClients.size} remaining)")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            val text = message.textPayload
            if (text == null || text.isBlank()) return

            Log.d(TAG, "[firmware] Received message (${text.length} chars)")
            LogManager.rx("[firmware] Hex upload received (${text.length} chars)")

            // Text message = hex file content
            handleHexUpload(text)
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) {
            firmwareClients.remove(this)
            Log.w(TAG, "[firmware] Client error: ${exception.message}")
        }
    }
}
