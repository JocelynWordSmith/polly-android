package com.robotics.polly

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class PollyWebSocketServer(port: Int) : NanoWSD(port) {

    // Per-endpoint client tracking
    val arduinoClients = CopyOnWriteArrayList<WebSocket>()
    val cameraClients = CopyOnWriteArrayList<WebSocket>()
    val flirClients = CopyOnWriteArrayList<WebSocket>()
    val imuClients = CopyOnWriteArrayList<WebSocket>()
    val controlClients = CopyOnWriteArrayList<WebSocket>()
    var onControlMessage: ((String) -> Unit)? = null
    var firmwareUploader: FirmwareUploader? = null

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val uri = handshake.uri
        Log.d(TAG, "WebSocket connection request: $uri")

        return when (uri) {
            "/arduino" -> TrackedWebSocket(handshake, arduinoClients, "arduino")
            "/camera" -> TrackedWebSocket(handshake, cameraClients, "camera")
            "/flir" -> TrackedWebSocket(handshake, flirClients, "flir")
            "/imu" -> TrackedWebSocket(handshake, imuClients, "imu")
            "/control" -> ControlWebSocket(handshake)
            "/firmware" -> firmwareUploader?.createWebSocket(handshake)
                ?: TrackedWebSocket(handshake, arduinoClients, "firmware-unavail")
            else -> RejectWebSocket(handshake, uri)
        }
    }

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        // Handle non-WebSocket HTTP requests
        if (!isWebsocketRequested(session)) {
            return when (session.uri) {
                "/status" -> {
                    val json = buildStatusJson()
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json",
                        json
                    )
                }
                else -> NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Not Found"
                )
            }
        }
        return super.serve(session)
    }

    private fun buildStatusJson(): String {
        return """{"server":"polly-bridge",""" +
            """"app_version":"${BuildConfig.VERSION_NAME}",""" +
            """"endpoints":{""" +
            """"arduino":{"clients":${arduinoClients.size}},""" +
            """"camera":{"clients":${cameraClients.size}},""" +
            """"flir":{"clients":${flirClients.size}},""" +
            """"imu":{"clients":${imuClients.size}},""" +
            """"control":{"clients":${controlClients.size}},""" +
            """"firmware":{"clients":${firmwareUploader?.firmwareClients?.size ?: 0}}}}"""
    }

    fun broadcastText(clients: CopyOnWriteArrayList<WebSocket>, message: String) {
        for (client in clients) {
            try {
                client.send(message)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send to client, removing")
                clients.remove(client)
            }
        }
    }

    fun broadcastBinary(clients: CopyOnWriteArrayList<WebSocket>, data: ByteArray) {
        for (client in clients) {
            try {
                client.send(data)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send binary to client, removing")
                clients.remove(client)
            }
        }
    }

    fun totalClientCount(): Int {
        return arduinoClients.size + cameraClients.size +
            flirClients.size + imuClients.size + controlClients.size +
            (firmwareUploader?.firmwareClients?.size ?: 0)
    }

    // WebSocket that tracks itself in a client list
    inner class TrackedWebSocket(
        handshake: IHTTPSession,
        private val clientList: CopyOnWriteArrayList<WebSocket>,
        private val endpointName: String
    ) : WebSocket(handshake) {

        override fun onOpen() {
            clientList.add(this)
            Log.d(TAG, "[$endpointName] Client connected (${clientList.size} total)")
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            clientList.remove(this)
            Log.d(TAG, "[$endpointName] Client disconnected: $reason (${clientList.size} remaining)")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            // Read-only endpoints ignore incoming messages
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) {
            clientList.remove(this)
            Log.w(TAG, "[$endpointName] Client error: ${exception.message}")
        }
    }

    // Control WebSocket that forwards commands
    inner class ControlWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var msgCount = 0

        override fun onOpen() {
            controlClients.add(this)
            Log.d(TAG, "[control] Client connected (${controlClients.size} total)")
            LogManager.info("[control] Client connected")
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            controlClients.remove(this)
            Log.d(TAG, "[control] Client disconnected (${controlClients.size} remaining)")
            LogManager.info("[control] Client disconnected")
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            val text = message.textPayload
            Log.d(TAG, "[control] Received: $text")
            // Throttle motor command logging (every 20th), log all others immediately
            msgCount++
            val isMotor = text?.contains("\"N\":7") == true || text?.contains("\"N\": 7") == true
            if (!isMotor || msgCount % 20 == 1) {
                LogManager.rx("[control] $text")
            }
            onControlMessage?.invoke(text)
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) {
            controlClients.remove(this)
            Log.w(TAG, "[control] Client error: ${exception.message}")
        }
    }

    // WebSocket that immediately closes on unknown endpoints
    inner class RejectWebSocket(
        handshake: IHTTPSession,
        private val uri: String
    ) : WebSocket(handshake) {
        override fun onOpen() {
            Log.w(TAG, "Rejecting unknown endpoint: $uri")
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "Unknown endpoint: $uri", false)
            } catch (_: IOException) {}
        }
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {}
        override fun onMessage(message: NanoWSD.WebSocketFrame) {}
        override fun onPong(pong: NanoWSD.WebSocketFrame) {}
        override fun onException(exception: IOException) {}
    }

    companion object {
        private const val TAG = "PollyWS"
    }
}
