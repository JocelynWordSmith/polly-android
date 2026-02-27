package com.robotics.polly

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UsbSerialManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var isConnected = false

    private val ACTION_USB_PERMISSION = "com.robotics.polly.USB_PERMISSION"

    var onConnectionChanged: ((Boolean, String) -> Unit)? = null

    private val dataListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addDataListener(listener: (String) -> Unit) {
        dataListeners.add(listener)
    }

    fun removeDataListener(listener: (String) -> Unit) {
        dataListeners.remove(listener)
    }

    // Persistent read thread
    @Volatile
    private var readThreadRunning = false
    private var readThread: Thread? = null

    // Dedicated write thread — prevents blocking callers and serializes USB writes
    private val writeQueue = LinkedBlockingQueue<String>(64)
    @Volatile
    private var writeThreadRunning = false
    private var writeThread: Thread? = null

    // Auto-reconnect settings
    private var autoReconnect = true
    private val reconnectDelayMs = 2000L
    private val maxReconnectAttempts = 5
    private var reconnectAttempts = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it) }
                        } else {
                            Log.d(TAG, "USB permission denied")
                            onConnectionChanged?.invoke(false, "Permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.w(TAG, "USB device detached, will attempt reconnection")
                    disconnect()
                    if (autoReconnect) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    fun initialize() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findAndConnect()
    }

    fun findAndConnect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        Log.d(TAG, "USB scan: found ${availableDrivers.size} serial devices")

        if (availableDrivers.isEmpty()) {
            val allDevices = usbManager.deviceList
            if (allDevices.isEmpty()) {
                onConnectionChanged?.invoke(false, "No USB devices found")
            } else {
                Log.d(TAG, "Found ${allDevices.size} USB device(s) but no serial drivers")
                allDevices.values.forEach { device ->
                    Log.d(TAG, "  Device: ${device.deviceName} VID:0x${Integer.toHexString(device.vendorId)} PID:0x${Integer.toHexString(device.productId)}")
                }
                onConnectionChanged?.invoke(false, "USB device found but not a serial port")
            }
            return
        }

        availableDrivers.forEachIndexed { index, driver ->
            val dev = driver.device
            Log.d(TAG, "  [$index] ${dev.deviceName} VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)} Ports:${driver.ports.size}")
        }

        // Try to find Arduino Uno specifically
        val arduinoDriver = availableDrivers.firstOrNull { driver ->
            val vid = driver.device.vendorId
            val pid = driver.device.productId
            when {
                vid == 0x2341 && (pid == 0x0043 || pid == 0x0001) -> true
                vid == 0x2A03 && pid == 0x0043 -> true
                vid == 0x1A86 && pid == 0x7523 -> true
                vid == 0x0403 && pid == 0x6001 -> true
                else -> false
            }
        }

        val driver = arduinoDriver ?: availableDrivers[0]
        val device = driver.device

        if (arduinoDriver != null) {
            Log.d(TAG, "Found Arduino: ${device.deviceName} VID:0x${Integer.toHexString(device.vendorId)} PID:0x${Integer.toHexString(device.productId)}")
        } else {
            Log.d(TAG, "No Arduino found, using first device: ${device.deviceName}")
        }

        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, intent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = availableDrivers.firstOrNull { it.device == device } ?: return

        Log.d(TAG, "Opening device: ${device.deviceName}")

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device - permission denied?")
            onConnectionChanged?.invoke(false, "Failed to open device")
            return
        }

        try {
            serialPort = driver.ports[0]
            serialPort?.open(connection)
            serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.d(TAG, "Port configured: 115200 8N1")

            serialPort?.dtr = false
            serialPort?.rts = false
            Log.d(TAG, "Control lines set: DTR=false RTS=false")

            // Wait for Arduino bootloader timeout
            Thread.sleep(2500)

            // Flush pending data
            try {
                val flushBuffer = ByteArray(1024)
                var flushed = 0
                for (i in 0..5) {
                    val n = serialPort?.read(flushBuffer, 50) ?: 0
                    if (n > 0) flushed += n
                    else break
                }
                if (flushed > 0) {
                    Log.d(TAG, "Flushed $flushed bytes")
                }
            } catch (e: Exception) {
                // Ignore flush errors
            }

            isConnected = true
            reconnectAttempts = 0
            onConnectionChanged?.invoke(true, "Connected to Arduino")
            LogManager.success("USB serial connected to ${device.deviceName}")
            Log.d(TAG, "USB Serial ready")

            // Start persistent read/write threads
            startReadThread()
            startWriteThread()
        } catch (e: IOException) {
            Log.e(TAG, "Error opening serial port", e)
            onConnectionChanged?.invoke(false, "Connection error: ${e.message}")
            disconnect()
        }
    }

    private fun startReadThread() {
        readThreadRunning = true
        readThread = Thread({
            Log.d(TAG, "Read thread started")
            val buffer = ByteArray(2048)
            val lineBuffer = StringBuilder()

            while (readThreadRunning) {
                try {
                    val numBytes = serialPort?.read(buffer, 100) ?: -1
                    if (numBytes > 0) {
                        val chunk = String(buffer, 0, numBytes, Charsets.UTF_8)
                        lineBuffer.append(chunk)

                        // Split on newlines and dispatch complete lines
                        var newlineIndex = lineBuffer.indexOf('\n')
                        while (newlineIndex >= 0) {
                            val line = lineBuffer.substring(0, newlineIndex).trim()
                            lineBuffer.delete(0, newlineIndex + 1)

                            if (line.isNotEmpty()) {
                                notifyListeners(line)
                            }
                            newlineIndex = lineBuffer.indexOf('\n')
                        }

                        // Safety: if buffer gets very large without newlines, flush it
                        if (lineBuffer.length > 8192) {
                            val data = lineBuffer.toString().trim()
                            lineBuffer.clear()
                            if (data.isNotEmpty()) {
                                notifyListeners(data)
                            }
                        }
                    } else if (numBytes == -1) {
                        Log.w(TAG, "Read returned -1, port may be closed")
                        break
                    }
                } catch (e: IOException) {
                    if (readThreadRunning) {
                        Log.e(TAG, "Read thread IO error: ${e.message}")
                        break
                    }
                } catch (e: Exception) {
                    if (readThreadRunning) {
                        Log.e(TAG, "Read thread error: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Read thread stopped")

            if (readThreadRunning) {
                // Unexpected stop — disconnected
                readThreadRunning = false
                handler.post {
                    disconnect()
                    if (autoReconnect) {
                        scheduleReconnect()
                    }
                }
            }
        }, "Arduino-Read")
        readThread?.isDaemon = true
        readThread?.start()
    }

    private fun notifyListeners(data: String) {
        for (listener in dataListeners) {
            try {
                listener(data)
            } catch (e: Exception) {
                Log.e(TAG, "Data listener error: ${e.message}")
            }
        }
    }

    fun sendCommand(command: String) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send: $command")
            LogManager.warn("USB TX blocked (not connected): $command")
            return
        }

        // Non-blocking enqueue — if queue is full, drop oldest to make room
        if (!writeQueue.offer(command)) {
            writeQueue.poll() // drop oldest
            writeQueue.offer(command)
            Log.w(TAG, "Write queue full, dropped oldest command")
        }
    }

    private fun startWriteThread() {
        writeThreadRunning = true
        writeThread = Thread({
            Log.d(TAG, "Write thread started")
            while (writeThreadRunning) {
                try {
                    val command = writeQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val port = serialPort
                    if (port == null || !isConnected) {
                        Log.w(TAG, "Write thread: port gone, dropping: $command")
                        continue
                    }
                    try {
                        val data = "$command\n".toByteArray(Charsets.UTF_8)
                        port.write(data, 500)
                        Log.d(TAG, "TX: $command")
                        LogManager.tx("USB: $command")
                    } catch (e: IOException) {
                        Log.e(TAG, "TX error: ${e.message}", e)
                        LogManager.error("USB TX failed: ${e.message}")
                        handler.post {
                            disconnect()
                            if (autoReconnect) {
                                scheduleReconnect()
                            }
                        }
                        break
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "Write thread stopped")
        }, "Arduino-Write")
        writeThread?.isDaemon = true
        writeThread?.start()
    }

    fun disconnect() {
        LogManager.warn("USB serial disconnecting")
        writeThreadRunning = false
        writeThread?.interrupt()
        writeThread = null
        writeQueue.clear()

        readThreadRunning = false
        readThread?.interrupt()
        readThread = null

        try {
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing serial port", e)
        }

        serialPort = null
        isConnected = false
        onConnectionChanged?.invoke(false, "Disconnected")
        Log.d(TAG, "USB Serial disconnected")
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        Log.i(TAG, "Scheduling reconnection attempt $reconnectAttempts in ${reconnectDelayMs}ms")
        onConnectionChanged?.invoke(false, "Reconnecting... (attempt $reconnectAttempts)")

        handler.postDelayed({
            Log.d(TAG, "Attempting reconnection...")
            findAndConnect()
        }, reconnectDelayMs)
    }

    /** Called by BridgeService reconnect watchdog when not connected. */
    fun reconnect() {
        if (isConnected || !autoReconnect) return
        Log.d(TAG, "External reconnect requested")
        reconnectAttempts = 0
        findAndConnect()
    }

    fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }

    fun cleanup() {
        autoReconnect = false
        handler.removeCallbacksAndMessages(null)
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    fun isConnected() = isConnected

    /** Returns the raw serial port for direct access (e.g., firmware programming). */
    fun getPort(): UsbSerialPort? = serialPort

    /**
     * Stops read/write threads without closing the port.
     * Used to take exclusive serial access for firmware programming.
     */
    fun pauseThreads() {
        Log.d(TAG, "Pausing read/write threads")
        writeThreadRunning = false
        writeThread?.interrupt()
        writeThread = null
        writeQueue.clear()

        readThreadRunning = false
        readThread?.interrupt()
        readThread = null
    }

    /**
     * Restarts read/write threads after exclusive access is released.
     * Port must still be open.
     */
    fun resumeThreads() {
        if (serialPort == null) {
            Log.w(TAG, "Cannot resume threads — port is null")
            return
        }
        Log.d(TAG, "Resuming read/write threads")
        startReadThread()
        startWriteThread()
    }

    companion object {
        private const val TAG = "UsbSerialManager"
    }
}
