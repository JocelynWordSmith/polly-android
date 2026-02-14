package com.robotics.polly

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CopyOnWriteArrayList

class LidarBridge(
    private val context: Context,
    private val wsServer: PollyWebSocketServer
) {

    private var serialPort: UsbSerialPort? = null
    private var readThread: Thread? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val localListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    var isConnected = false
        private set

    @Volatile
    private var running = false

    fun start() {
        Log.d(TAG, "Starting LidarBridge")
        running = true
        tryConnect()
    }

    private fun tryConnect() {
        if (!running) return

        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

            // Find CP2102 (LIDAR) - VID=0x10C4
            val lidarDriver = availableDrivers.firstOrNull { it.device.vendorId == 0x10C4 }

            if (lidarDriver == null) {
                Log.d(TAG, "No CP2102 LIDAR found")
                isConnected = false
                scheduleReconnect()
                return
            }

            val device = lidarDriver.device
            if (!usbManager.hasPermission(device)) {
                Log.d(TAG, "No USB permission for LIDAR")
                isConnected = false
                scheduleReconnect()
                return
            }

            val connection = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Failed to open LIDAR device")
                isConnected = false
                scheduleReconnect()
                return
            }

            val port = lidarDriver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = false
            port.rts = false

            // Stop any current scan
            port.write(byteArrayOf(0xA5.toByte(), 0x25.toByte()), 100) // STOP
            Thread.sleep(100)

            // Drain any pending data
            val clearBuf = ByteArray(256)
            while (port.read(clearBuf, 100) > 0) { /* drain */ }

            // Start SCAN
            port.write(byteArrayOf(0xA5.toByte(), 0x20.toByte()), 100)

            // Read response descriptor (7 bytes: A5 5A 05 00 00 40 81)
            Thread.sleep(50)
            val descBuf = ByteArray(64)
            val descLen = port.read(descBuf, 500)

            serialPort = port
            isConnected = true
            Log.d(TAG, "LIDAR connected and scanning")
            LogManager.success("LIDAR: Connected and scanning")

            // Start read thread
            startReadThread(port)
        } catch (e: Exception) {
            Log.e(TAG, "LIDAR connection error: ${e.message}", e)
            isConnected = false
            scheduleReconnect()
        }
    }

    private fun startReadThread(port: UsbSerialPort) {
        readThread = Thread {
            val buf = ByteArray(512)
            while (running && isConnected) {
                try {
                    val len = port.read(buf, 200)
                    if (len > 0) {
                        dispatchData(buf.copyOf(len))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LIDAR read error: ${e.message}")
                    isConnected = false
                    break
                }
            }
        }.apply {
            name = "LIDAR-Read"
            isDaemon = true
            start()
        }
    }

    private fun dispatchData(data: ByteArray) {
        // Forward raw bytes to WebSocket clients
        if (wsServer.lidarClients.isNotEmpty()) {
            wsServer.broadcastBinary(wsServer.lidarClients, data)
        }
        // Notify local listeners
        for (listener in localListeners) {
            try {
                listener(data)
            } catch (e: Exception) {
                Log.e(TAG, "Local listener error: ${e.message}")
            }
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        handler.postDelayed({ tryConnect() }, 2000)
    }

    private fun cleanup() {
        readThread?.interrupt()
        readThread = null
        try {
            serialPort?.close()
            serialPort = null
        } catch (e: Exception) { /* ignore */ }
    }

    fun stop() {
        Log.d(TAG, "Stopping LidarBridge")
        running = false
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        cleanup()
    }

    companion object {
        private const val TAG = "LidarBridge"
    }
}
