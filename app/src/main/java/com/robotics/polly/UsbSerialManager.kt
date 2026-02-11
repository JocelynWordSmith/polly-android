package com.robotics.polly

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

class UsbSerialManager(private val context: Context) {
    
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var isConnected = false
    
    private val ACTION_USB_PERMISSION = "com.robotics.polly.USB_PERMISSION"
    
    var onConnectionChanged: ((Boolean, String) -> Unit)? = null
    var onDataReceived: ((String) -> Unit)? = null
    
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
                    // Try to reconnect after a delay
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
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        
        // Try to connect immediately
        findAndConnect()
    }
    
    fun findAndConnect() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        
        Log.d(TAG, "USB scan: found ${availableDrivers.size} serial devices")
        
        if (availableDrivers.isEmpty()) {
            // Check if any USB devices exist at all
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
        
        // Log all available devices
        availableDrivers.forEachIndexed { index, driver ->
            val dev = driver.device
            Log.d(TAG, "  [$index] ${dev.deviceName} VID:0x${Integer.toHexString(dev.vendorId)} PID:0x${Integer.toHexString(dev.productId)} Ports:${driver.ports.size}")
        }
        
        // Try to find Arduino Uno specifically
        // Arduino Uno VID:PID combinations:
        // - 0x2341:0x0043 (original Uno)
        // - 0x2341:0x0001 (Uno rev3)
        // - 0x2A03:0x0043 (clone)
        // - 0x1A86:0x7523 (CH340 chip)
        // - 0x0403:0x6001 (FTDI chip)
        val arduinoDriver = availableDrivers.firstOrNull { driver ->
            val vid = driver.device.vendorId
            val pid = driver.device.productId
            when {
                vid == 0x2341 && (pid == 0x0043 || pid == 0x0001) -> true // Official Arduino
                vid == 0x2A03 && pid == 0x0043 -> true // Arduino clone
                vid == 0x1A86 && pid == 0x7523 -> true // CH340
                vid == 0x0403 && pid == 0x6001 -> true // FTDI
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
        
        Log.d(TAG, "Attempting to connect to ${device.deviceName}")
        
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
        
        Log.d(TAG, "Device opened, configuring serial port...")
        
        try {
            serialPort = driver.ports[0]
            serialPort?.open(connection)
            
            // Configure port  
            serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Log.d(TAG, "Port configured: 115200 8N1")
            
            // Set control lines - CH340 needs both false for normal operation
            serialPort?.dtr = false
            serialPort?.rts = false
            Log.d(TAG, "Control lines set: DTR=false RTS=false")
            
            // Delay for chip to settle AND for Arduino bootloader to timeout (if it was reset)
            // Arduino bootloader waits ~2 seconds before jumping to sketch
            Thread.sleep(2500)
            
            // Manually flush by reading any pending data
            try {
                val flushBuffer = ByteArray(1024)
                var flushed = 0
                for (i in 0..5) {
                    val n = serialPort?.read(flushBuffer, 50) ?: 0
                    if (n > 0) flushed += n
                    else break
                }
                if (flushed > 0) {
                    Log.d(TAG, "Manually flushed $flushed bytes")
                }
            } catch (e: Exception) {
                // Ignore flush errors
            }
            
            isConnected = true
            reconnectAttempts = 0  // Reset reconnect counter on success
            onConnectionChanged?.invoke(true, "Connected to Arduino")
            Log.d(TAG, "USB Serial ready (CH340 compatible mode)")
        } catch (e: IOException) {
            Log.e(TAG, "Error opening serial port", e)
            onConnectionChanged?.invoke(false, "Connection error: ${e.message}")
            disconnect()
        }
    }
    
    fun sendCommand(command: String) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send: $command")
            return
        }
        
        try {
            val data = "$command\n".toByteArray(Charsets.UTF_8)
            val bytesWritten = serialPort?.write(data, 2000) ?: 0
            
            Log.d(TAG, "TX: $command")
            
            // Start read thread that polls continuously
            Thread {
                try {
                    val buffer = ByteArray(2048)
                    var totalBytes = 0
                    val responseBuilder = StringBuilder()
                    
                    // Poll for up to 2 seconds (20 attempts x 100ms)
                    for (attempt in 1..20) {
                        val numBytes = serialPort?.read(buffer, 100) ?: -1
                        
                        if (numBytes > 0) {
                            val chunk = String(buffer, 0, numBytes)
                            responseBuilder.append(chunk)
                            totalBytes += numBytes
                            
                            // If we got a complete JSON response (ends with }), stop reading
                            if (chunk.contains("}")) {
                                break
                            }
                        } else if (numBytes == -1) {
                            Log.w(TAG, "Read error or port closed")
                            break
                        }
                        
                        // Small delay between polls
                        if (attempt < 20) {
                            Thread.sleep(10)
                        }
                    }
                    
                    if (totalBytes > 0) {
                        val response = responseBuilder.toString().trim()
                        Log.d(TAG, "RX: $totalBytes bytes: $response")
                        onDataReceived?.invoke(response)
                    } else {
                        Log.w(TAG, "RX: No response after 2s")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "RX error: ${e.message}")
                    // Check if it's a connection error
                    if (e is IOException) {
                        disconnect()
                        if (autoReconnect) {
                            scheduleReconnect()
                        }
                    }
                }
            }.start()
        } catch (e: IOException) {
            Log.e(TAG, "TX: Error sending command: ${e.message}", e)
            disconnect()
            if (autoReconnect) {
                scheduleReconnect()
            }
        }
    }
    
    fun readData(): String? {
        if (!isConnected) return null
        
        try {
            val buffer = ByteArray(256)
            val numBytesRead = serialPort?.read(buffer, 1000) ?: 0
            
            if (numBytesRead > 0) {
                val data = String(buffer, 0, numBytesRead)
                Log.d(TAG, "Received: $data")
                onDataReceived?.invoke(data)
                return data
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading data", e)
            disconnect()
        }
        
        return null
    }
    
    fun disconnect() {
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
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnection attempts ($maxReconnectAttempts) reached, giving up")
            onConnectionChanged?.invoke(false, "Connection lost - max retries exceeded")
            return
        }
        
        reconnectAttempts++
        Log.i(TAG, "Scheduling reconnection attempt $reconnectAttempts/$maxReconnectAttempts in ${reconnectDelayMs}ms")
        onConnectionChanged?.invoke(false, "Reconnecting... (attempt $reconnectAttempts/$maxReconnectAttempts)")
        
        handler.postDelayed({
            Log.d(TAG, "Attempting reconnection...")
            findAndConnect()
        }, reconnectDelayMs)
    }
    
    fun resetReconnectAttempts() {
        reconnectAttempts = 0
    }
    
    fun cleanup() {
        autoReconnect = false  // Disable reconnection during cleanup
        handler.removeCallbacksAndMessages(null)  // Cancel any pending reconnects
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }
    
    fun isConnected() = isConnected
    
    companion object {
        private const val TAG = "UsbSerialManager"
    }
}
