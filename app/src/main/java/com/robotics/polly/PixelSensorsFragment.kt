package com.robotics.polly

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class PixelSensorsFragment : Fragment(), SensorEventListener, LocationListener {
    
    private lateinit var sensorDataText: TextView
    private lateinit var logText: TextView
    private lateinit var cameraPreview: PreviewView
    private lateinit var logsBottomSheet: MaterialCardView
    private lateinit var logsHeader: View
    private lateinit var clearLogsButton: Button
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<MaterialCardView>
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateUI()
            handler.postDelayed(this, 200) // Update every 200ms
        }
    }
    
    // Sensor data
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f
    private var lightLevel = 0f
    private var pressure = 0f
    private var proximity = 0f
    private var stepCount = 0
    private var location: Location? = null
    private var micLevel = 0f
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_pixel_sensors, container, false)
        
        sensorDataText = view.findViewById(R.id.sensorDataText)
        logText = view.findViewById(R.id.logText)
        cameraPreview = view.findViewById(R.id.cameraPreview)
        logsBottomSheet = view.findViewById(R.id.logsBottomSheet)
        logsHeader = view.findViewById(R.id.logsHeader)
        clearLogsButton = view.findViewById(R.id.clearLogsButton)
        
        // Set up bottom sheet
        bottomSheetBehavior = BottomSheetBehavior.from(logsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        
        // Toggle bottom sheet on header click
        logsHeader.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }
        
        // Clear logs button
        clearLogsButton.setOnClickListener {
            LogManager.clear()
            updateLog()
        }
        
        // Initialize managers
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Register sensors
        registerSensors()
        
        // Request location updates
        requestLocationUpdates()
        
        // Start camera
        startCamera()
        
        // Start microphone monitoring
        startMicMonitoring()
        
        // Listen to logs
        LogManager.addListener { _ ->
            activity?.runOnUiThread {
                updateLog()
            }
        }
        
        LogManager.info("Pixel sensors initialized")
        
        return view
    }
    
    private fun registerSensors() {
        // Log all available sensors for debugging
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        LogManager.info("Available sensors: ${allSensors.size}")
        
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Add proximity sensor
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            LogManager.info("Proximity sensor registered")
        }
        // Add step counter if available
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            LogManager.info("Step counter registered")
        }
    }
    
    private fun requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                200
            )
            return
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                0f,
                this
            )
        } catch (e: Exception) {
            LogManager.error("GPS error: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        registerSensors()
        requestLocationUpdates()
        handler.post(updateRunnable)
        updateLog()
    }
    
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        handler.removeCallbacks(updateRunnable)
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magX = event.values[0]
                magY = event.values[1]
                magZ = event.values[2]
            }
            Sensor.TYPE_LIGHT -> {
                lightLevel = event.values[0]
            }
            Sensor.TYPE_PRESSURE -> {
                pressure = event.values[0]
            }
            Sensor.TYPE_PROXIMITY -> {
                proximity = event.values[0]
            }
            Sensor.TYPE_STEP_COUNTER -> {
                stepCount = event.values[0].toInt()
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not needed
    }
    
    override fun onLocationChanged(loc: Location) {
        location = loc
    }
    
    private fun updateUI() {
        val orientation = getOrientation(accelX, accelY, accelZ)
        val heading = getHeading(magX, magY)
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        // Get charging status from battery intent (more reliable than batteryManager.isCharging)
        val batteryStatus = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                        status == BatteryManager.BATTERY_STATUS_FULL
        
        val output = buildString {
            append("📐 Accelerometer\n")
            append("X: ${"%6.2f".format(accelX)}g  ")
            append("Y: ${"%6.2f".format(accelY)}g  ")
            append("Z: ${"%6.2f".format(accelZ)}g\n")
            append("Orientation: $orientation\n\n")
            
            val gyroMagnitude = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
            append("🔄 Gyroscope\n")
            append("X: ${"%6.2f".format(gyroX)}°/s  ")
            append("Y: ${"%6.2f".format(gyroY)}°/s  ")
            append("Z: ${"%6.2f".format(gyroZ)}°/s\n")
            append("Mag: ${"%5.2f".format(gyroMagnitude)}°/s\n\n")
            
            append("🧭 Magnetometer\n")
            append("Heading: ${"%3d".format(heading.first)}° (${heading.second})\n")
            append("X: ${"%6.1f".format(magX)}µT  ")
            append("Y: ${"%6.1f".format(magY)}µT  ")
            append("Z: ${"%6.1f".format(magZ)}µT\n\n")
            
            location?.let { loc ->
                append("📍 GPS\n")
                append("Lat: ${"%9.6f".format(loc.latitude)}  ")
                append("Lon: ${"%10.6f".format(loc.longitude)}\n")
                if (loc.hasAltitude()) {
                    append("Alt: ${"%6.1f".format(loc.altitude)}m  ")
                }
                if (loc.hasAccuracy()) {
                    append("Acc: ${"%4.1f".format(loc.accuracy)}m")
                }
                append("\n")
                if (loc.hasSpeed()) {
                    append("Speed: ${"%5.1f".format(loc.speed * 3.6)} km/h\n")
                }
                append("\n")
            } ?: run {
                append("📍 GPS\nSearching for signal...\n\n")
            }
            
            append("🎤 Microphone\n")
            val micBars = (micLevel / 10).toInt().coerceIn(0, 10)
            val micBar = "█".repeat(micBars) + "░".repeat(10 - micBars)
            append("$micBar ${"%5.1f".format(micLevel)}%\n\n")
            
            append("🔋 Battery: ${"%3d".format(batteryLevel)}% ")
            append(if (isCharging) "(Charging)" else "(Not charging)")
            append("\n\n")
            
            if (lightLevel > 0) {
                val lightDesc = when {
                    lightLevel < 10 -> "Dark      "
                    lightLevel < 100 -> "Dim       "
                    lightLevel < 1000 -> "Indoor    "
                    lightLevel < 10000 -> "Bright    "
                    else -> "Very bright"
                }
                append("💡 Light: ${"%7.1f".format(lightLevel)} lux ($lightDesc)\n\n")
            }
            
            if (pressure > 0) {
                append("🌡️ Pressure: ${"%7.2f".format(pressure)} hPa\n")
                val altitude = 44330 * (1 - Math.pow((pressure / 1013.25), 1.0 / 5.255))
                append("Est. alt: ${"%6.1f".format(altitude)}m\n\n")
            }
            
            if (proximity >= 0) {
                val proxDesc = if (proximity < 1) "Near" else "Far"
                append("👋 Proximity: ${"%5.1f".format(proximity)} cm ($proxDesc)\n\n")
            }
            
            if (stepCount > 0) {
                append("👣 Steps: $stepCount\n")
            }
        }
        
        sensorDataText.text = output
    }
    
    private fun getOrientation(x: Float, y: Float, z: Float): String {
        return when {
            abs(x) < 2 && abs(y) < 2 -> "📱 Flat"
            y < -5 -> "📱⬆️ Tilted Up"
            y > 5 -> "📱⬇️ Tilted Down"
            x < -5 -> "📱➡️ Tilted Right"
            x > 5 -> "📱⬅️ Tilted Left"
            else -> "📱 Angled"
        }
    }
    
    private fun getHeading(x: Float, y: Float): Pair<Int, String> {
        if (x == 0f && y == 0f) return Pair(0, "Unknown")
        
        var heading = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
        if (heading < 0) heading += 360f
        
        val direction = when {
            heading < 22.5 || heading >= 337.5 -> "N"
            heading < 67.5 -> "NE"
            heading < 112.5 -> "E"
            heading < 157.5 -> "SE"
            heading < 202.5 -> "S"
            heading < 247.5 -> "SW"
            heading < 292.5 -> "W"
            else -> "NW"
        }
        
        return Pair(heading.toInt(), direction)
    }
    
    private fun updateLog() {
        logText.text = LogManager.getFormattedLogs()
    }
    
    private fun startMicMonitoring() {
        // Check permission
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        
        try {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            audioRecord?.startRecording()
            isRecording = true
            
            // Read audio in background thread
            Thread {
                val buffer = ShortArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        // Calculate RMS amplitude
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        micLevel = (rms / 32768.0 * 100).toFloat() // Convert to percentage
                    }
                    Thread.sleep(50) // Sample every 50ms
                }
            }.start()
            
            LogManager.success("Microphone monitoring started")
        } catch (e: Exception) {
            LogManager.error("Mic error: ${e.message}")
        }
    }
    
    private fun stopMicMonitoring() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
                LogManager.success("Camera preview started")
            } catch (e: Exception) {
                LogManager.error("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }
    
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        
        // Preview use case
        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }
        
        // Select back camera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        // Unbind all use cases before rebinding
        provider.unbindAll()
        
        // Bind use cases to camera
        provider.bindToLifecycle(
            viewLifecycleOwner,
            cameraSelector,
            preview
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateRunnable)
        stopMicMonitoring()
        cameraExecutor.shutdown()
    }
}
