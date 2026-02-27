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
    private var firmwareUploader: FirmwareUploader? = null

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
                // Set watchdog (1s) and enable 5Hz sensor streaming
                // Lower stream rate prevents serial saturation when motor commands flow
                usb.sendCommand("{\"N\":102,\"D1\":1000}")
                usb.sendCommand("{\"N\":103,\"D1\":200}")
                usb.sendCommand("{\"N\":105}") // request firmware version
                Log.d(TAG, "Streaming enabled (5Hz), watchdog set (1000ms)")
            } else {
                LogManager.info("Arduino: $message")
            }
        }

        dataListener = { line ->
            // Remap short Arduino keys to human-readable names
            val remapped = remapKeys(line)

            // Surface command acknowledgments in logs (not periodic sensor data)
            val trimmed = remapped.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val j = JSONObject(trimmed)
                    if (j.has("tank") || j.has("cmd") || j.has("ok") ||
                        j.has("error") || j.has("estop") || j.has("watchdog") ||
                        j.has("speed") || j.has("safety")) {
                        LogManager.rx("Arduino: $trimmed")
                    }
                } catch (_: Exception) {}
            }

            // Forward remapped data to WebSocket clients
            if (wsServer.arduinoClients.isNotEmpty()) {
                wsServer.broadcastText(wsServer.arduinoClients, remapped)
            }
            // Forward remapped data to local listeners (UI fragments)
            for (listener in localListeners) {
                try {
                    listener(remapped)
                } catch (e: Exception) {
                    Log.e(TAG, "Local listener error: ${e.message}")
                }
            }
        }
        usb.addDataListener(dataListener!!)

        // Set up firmware uploader
        firmwareUploader = FirmwareUploader(
            wsServer = wsServer,
            getSerialPort = { usb.getPort() },
            pauseNormalOperation = {
                Log.d(TAG, "Pausing for firmware upload")
                LogManager.info("Pausing serial for firmware upload")
                // Disable streaming and watchdog before taking over the port
                try {
                    usb.sendCommand("{\"N\":103,\"D1\":0}")
                    usb.sendCommand("{\"N\":102,\"D1\":0}")
                    Thread.sleep(300)
                } catch (_: Exception) {}
                usb.pauseThreads()
            },
            resumeNormalOperation = {
                Log.d(TAG, "Resuming after firmware upload")
                LogManager.info("Resuming serial after firmware upload")
                usb.resumeThreads()
                // Re-enable watchdog and streaming
                usb.sendCommand("{\"N\":102,\"D1\":1000}")
                usb.sendCommand("{\"N\":103,\"D1\":200}")
            }
        )
        wsServer.firmwareUploader = firmwareUploader

        usb.initialize()
    }

    fun handleCommand(json: JSONObject) {
        val cmd = json.optString("cmd", "")
        if (cmd.isNotEmpty()) {
            Log.d(TAG, "Control command: $cmd")
            LogManager.tx("Arduino: $cmd")
            if (usbSerial == null) {
                LogManager.warn("USB serial is null, command dropped: $cmd")
            } else if (!usbSerial!!.isConnected()) {
                LogManager.warn("USB not connected, command dropped: $cmd")
            }
            usbSerial?.sendCommand(cmd)
        } else {
            LogManager.warn("Empty cmd in control JSON: $json")
        }
    }

    fun sendCommand(command: String) {
        usbSerial?.sendCommand(command)
    }

    fun getUsbSerial(): UsbSerialManager? = usbSerial

    /** Called by BridgeService reconnect watchdog when not connected. */
    fun reconnect() {
        if (isConnected) return
        Log.d(TAG, "External reconnect requested")
        LogManager.info("Arduino: Retrying connection...")
        usbSerial?.reconnect()
    }

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

        // Short-key → human-readable key mapping for Arduino serial → WebSocket/UI
        private val KEY_REMAP = mapOf(
            "t" to "ts",
            "u" to "execUs",
            "d" to "dist_f",
            "a" to "accel",
            "g" to "gyro",
            "c" to "temp",
            "b" to "battery",
            "m" to "mpuValid",
            "M" to "motors",
            "T" to "targets",
            "l" to "led",
            "B" to "brightness",
            "s" to "speed",
            "w" to "watchdog",
            "mb" to "motorBias",
            "br" to "batteryRatio",
            "mp" to "mpuPresent",
            "ma" to "maxAccel",
            "st" to "stream",
            "r" to "raw",
            "cr" to "calibrated_ratio",
            "av" to "actual",
            "ad" to "adc",
            "fv" to "fw_version",
        )

        /** Remap short Arduino serial keys to human-readable names. */
        fun remapKeys(line: String): String {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return line
            return try {
                val src = JSONObject(trimmed)
                val dst = JSONObject()
                val keys = src.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    dst.put(KEY_REMAP[key] ?: key, src.get(key))
                }
                dst.toString()
            } catch (_: Exception) {
                line
            }
        }
    }
}
