package com.robotics.polly

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.json.JSONObject

class ArduinoSensorsFragment : Fragment() {
    
    private lateinit var arduinoDataText: TextView
    private val handler = Handler(Looper.getMainLooper())
    
    private var lastSensorData: JSONObject? = null
    private var pendingResponse = false
    private var hasLoggedPoll = false  // Only log the first poll command
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!pendingResponse) {
                requestAllSensors()
            }
            handler.postDelayed(this, 200) // Poll every 200ms
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_arduino_sensors, container, false)
        
        arduinoDataText = view.findViewById(R.id.arduinoDataText)
        
        // Listen to Arduino responses
        val mainActivity = activity as? MainActivity
        mainActivity?.getUsbSerial()?.onDataReceived = { data ->
            parseArduinoResponse(data)
        }
        
        LogManager.info("Arduino sensors initialized")
        
        return view
    }
    
    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }
    
    private fun requestAllSensors() {
        val mainActivity = activity as? MainActivity ?: return
        val usbSerial = mainActivity.getUsbSerial()
        
        if (!usbSerial.isConnected()) {
            activity?.runOnUiThread {
                arduinoDataText.text = "Arduino not connected\n\nConnect via USB to see sensor data"
            }
            return
        }
        
        // Only log the first poll command to avoid spam
        if (!hasLoggedPoll) {
            LogManager.info("Polling Arduino sensors at 5 Hz")
            hasLoggedPoll = true
        }
        
        pendingResponse = true
        usbSerial.sendCommand("{\"N\":100}")
    }
    
    private fun parseArduinoResponse(data: String) {
        try {
            val trimmed = data.trim()
            if (!trimmed.startsWith("{")) return
            
            // Don't log every response - it spams the log
            // The data is displayed in the UI instead
            
            val json = JSONObject(trimmed)
            lastSensorData = json
            pendingResponse = false
            
            activity?.runOnUiThread {
                updateUI(json)
            }
        } catch (e: Exception) {
            LogManager.error("Arduino parse error: ${e.message}")
            pendingResponse = false
        }
    }
    
    private fun updateUI(json: JSONObject) {
        val output = buildString {
            // Execution time
            val execUs = json.optInt("execUs", 0)
            append("⏱️ Execution: ${execUs}µs (${execUs / 1000.0}ms)\n\n")
            
            // Ultrasonic distance
            val distance = json.optInt("distance", -1)
            if (distance >= 0) {
                append("📏 Ultrasonic Distance\n")
                append("$distance cm")
                if (distance < 10) append(" ⚠️ Very close!")
                else if (distance < 30) append(" 🔶 Close")
                append("\n\n")
            }
            
            // IR line sensors
            if (json.has("ir")) {
                val ir = json.getJSONArray("ir")
                val onLine = json.optJSONArray("onLine")
                append("🔦 IR Line Sensors\n")
                append("Left:   ${ir.getInt(0)}")
                if (onLine != null && onLine.getInt(0) == 1) append(" ⚪ ON LINE")
                append("\n")
                append("Middle: ${ir.getInt(1)}")
                if (onLine != null && onLine.getInt(1) == 1) append(" ⚪ ON LINE")
                append("\n")
                append("Right:  ${ir.getInt(2)}")
                if (onLine != null && onLine.getInt(2) == 1) append(" ⚪ ON LINE")
                append("\n\n")
            }
            
            // MPU-6050 IMU
            val mpuValid = json.optInt("mpuValid", 0) == 1
            if (mpuValid && json.has("accel")) {
                val accel = json.getJSONArray("accel")
                val gyro = json.getJSONArray("gyro")
                val temp = json.optDouble("temp", 0.0)
                
                append("🔷 MPU-6050 IMU\n")
                append("Accel: ")
                append("X:${accel.getInt(0)} ")
                append("Y:${accel.getInt(1)} ")
                append("Z:${accel.getInt(2)}\n")
                append("Gyro:  ")
                append("X:${gyro.getInt(0)} ")
                append("Y:${gyro.getInt(1)} ")
                append("Z:${gyro.getInt(2)}\n")
                append("Temp: ${"%.1f".format(temp)}°C\n\n")
            } else {
                append("🔷 MPU-6050 IMU\n")
                append("Not available\n\n")
            }
            
            // Battery
            val battery = json.optDouble("battery", 0.0)
            if (battery > 0) {
                append("🔋 Battery Voltage\n")
                append("${"%.2f".format(battery)}V")
                when {
                    battery < 6.5 -> append(" ⚠️ Low!")
                    battery < 7.0 -> append(" 🔶 Medium")
                    else -> append(" ✅ Good")
                }
                append("\n\n")
            }
            
            // Timestamp
            val ts = json.optLong("ts", 0)
            if (ts > 0) {
                append("⏰ Arduino uptime: ${ts / 1000}s")
            }
        }
        
        arduinoDataText.text = output
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(pollRunnable)
    }
}
