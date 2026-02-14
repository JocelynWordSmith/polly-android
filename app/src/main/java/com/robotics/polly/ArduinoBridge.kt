package com.robotics.polly

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class ArduinoBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) {
    private var usbSerial: UsbSerialManager? = null
    private var dataListener: ((String) -> Unit)? = null

    // Local listeners for fragments that want to display data
    val localListeners = CopyOnWriteArrayList<(String) -> Unit>()

    var isConnected = false
        private set

    fun start() {
        Log.d(TAG, "Starting ArduinoBridge")

        val usb = UsbSerialManager(context)
        usbSerial = usb

        usb.onConnectionChanged = { connected, message ->
            isConnected = connected
            if (connected) {
                LogManager.success("Arduino: $message")
                // Enable 20Hz streaming and set watchdog
                usb.sendCommand("{\"N\":102,\"D1\":500}")
                usb.sendCommand("{\"N\":103,\"D1\":50}")
                Log.d(TAG, "Streaming enabled (20Hz), watchdog set (500ms)")
            } else {
                LogManager.info("Arduino: $message")
            }
        }

        dataListener = { line ->
            // Forward to WebSocket clients
            if (wsServer.arduinoClients.isNotEmpty()) {
                wsServer.broadcastText(wsServer.arduinoClients, line)
            }
            // Forward to local listeners (UI fragments)
            for (listener in localListeners) {
                try {
                    listener(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Local listener error: ${e.message}")
                }
            }
        }
        usb.addDataListener(dataListener!!)

        usb.initialize()
    }

    fun handleCommand(json: JSONObject) {
        val cmd = json.optString("cmd", "")
        if (cmd.isNotEmpty()) {
            Log.d(TAG, "Control command: $cmd")
            usbSerial?.sendCommand(cmd)
        }
    }

    fun sendCommand(command: String) {
        usbSerial?.sendCommand(command)
    }

    fun getUsbSerial(): UsbSerialManager? = usbSerial

    fun stop() {
        Log.d(TAG, "Stopping ArduinoBridge")
        // Disable streaming before disconnecting
        try {
            usbSerial?.sendCommand("{\"N\":103,\"D1\":0}")
        } catch (e: Exception) {
            // Ignore if already disconnected
        }
        dataListener?.let { usbSerial?.removeDataListener(it) }
        usbSerial?.cleanup()
        usbSerial = null
    }

    companion object {
        private const val TAG = "ArduinoBridge"
    }
}
