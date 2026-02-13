package com.robotics.polly

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// Exponential moving average for smoothing
class SensorSmoother(private val alpha: Float = 0.15f) {
    private var value: Float = 0f
    private var initialized = false
    
    fun smooth(newValue: Float): Float {
        if (!initialized) {
            value = newValue
            initialized = true
            return value
        }
        value = alpha * newValue + (1 - alpha) * value
        return value
    }
}

// Video size states for expandable camera
enum class VideoSize { SMALL, MEDIUM, FULLSCREEN }

// Robot state data (from Arduino sensors)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollyDashboardScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Sensor state
    var sensorData by remember { mutableStateOf(SensorData()) }
    
    // Video size state for expandable camera
    var videoSize by remember { mutableStateOf(VideoSize.SMALL) }
    
    // Robot state (from Arduino/ESP32)
    
    // Initialize sensor monitoring
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // Smoothers for each sensor axis
        val accelXSmoother = SensorSmoother()
        val accelYSmoother = SensorSmoother()
        val accelZSmoother = SensorSmoother()
        val gyroXSmoother = SensorSmoother()
        val gyroYSmoother = SensorSmoother()
        val gyroZSmoother = SensorSmoother()
        val magXSmoother = SensorSmoother()
        val magYSmoother = SensorSmoother()
        val magZSmoother = SensorSmoother()
        
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        sensorData = sensorData.copy(
                            accelX = accelXSmoother.smooth(event.values[0]),
                            accelY = accelYSmoother.smooth(event.values[1]),
                            accelZ = accelZSmoother.smooth(event.values[2])
                        )
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        sensorData = sensorData.copy(
                            gyroX = gyroXSmoother.smooth(event.values[0]),
                            gyroY = gyroYSmoother.smooth(event.values[1]),
                            gyroZ = gyroZSmoother.smooth(event.values[2])
                        )
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        sensorData = sensorData.copy(
                            magX = magXSmoother.smooth(event.values[0]),
                            magY = magYSmoother.smooth(event.values[1]),
                            magZ = magZSmoother.smooth(event.values[2])
                        )
                    }
                    Sensor.TYPE_LIGHT -> {
                        sensorData = sensorData.copy(lightLevel = event.values[0])
                    }
                    Sensor.TYPE_PRESSURE -> {
                        sensorData = sensorData.copy(pressure = event.values[0])
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        sensorData = sensorData.copy(proximity = event.values[0])
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        sensorData = sensorData.copy(stepCount = event.values[0].toInt())
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        
        val locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                sensorData = sensorData.copy(location = loc)
            }
        }
        
        // Register sensors
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        
        // Request location updates
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
            } catch (e: Exception) {
                LogManager.error("GPS error: ${e.message}")
            }
        }
        
        // Start microphone monitoring
        var audioRecord: AudioRecord? = null
        var isRecording = true
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                val sampleRate = 44100
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                audioRecord.startRecording()
                
                Thread {
                    val buffer = ShortArray(bufferSize)
                    while (isRecording) {
                        val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (read > 0) {
                            var sum = 0.0
                            for (i in 0 until read) {
                                sum += buffer[i] * buffer[i]
                            }
                            val rms = kotlin.math.sqrt(sum / read)
                            sensorData = sensorData.copy(micLevel = (rms / 32768.0 * 100).toFloat())
                        }
                        Thread.sleep(50)
                    }
                }.start()
                
                LogManager.success("Microphone monitoring started")
            } catch (e: Exception) {
                LogManager.error("Mic error: ${e.message}")
            }
        }
        
        LogManager.info("Pixel sensors initialized (Compose)")
        
        onDispose {
            sensorManager.unregisterListener(sensorListener)
            locationManager.removeUpdates(locationListener)
            isRecording = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {}
        }
    }
    
    // Update battery periodically
    LaunchedEffect(Unit) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            try {
                val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                sensorData = sensorData.copy(batteryLevel = batteryLevel, batteryCharging = isCharging)
            } catch (e: Exception) {
                // Ignore battery errors
            }
            delay(1000)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 0.dp, vertical = 4.dp)
        ) {
            // Camera Card - Expandable
            Card(
                modifier = Modifier
                    .then(
                        when (videoSize) {
                            VideoSize.SMALL -> Modifier
                                .width(120.dp)
                                .height(90.dp)
                            VideoSize.MEDIUM -> Modifier
                                .fillMaxWidth(0.5f)
                                .aspectRatio(4f / 3f)
                            VideoSize.FULLSCREEN -> Modifier
                                .fillMaxWidth(0f)
                                .height(0.dp) // Hide when fullscreen overlay is shown
                        }
                    )
                    .padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
                    .clickable {
                        videoSize = when (videoSize) {
                            VideoSize.SMALL -> VideoSize.MEDIUM
                            VideoSize.MEDIUM -> VideoSize.FULLSCREEN
                            VideoSize.FULLSCREEN -> VideoSize.SMALL
                        }
                    },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                if (videoSize != VideoSize.FULLSCREEN) {
                    Column {
                        // Camera label header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "▓ CAMERA",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp
                            )
                        }
                        CameraPreviewComposable(lifecycleOwner = lifecycleOwner)
                    }
                }
            }
            
            
            // Sensor Data Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp, start = 4.dp, end = 4.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "▓ SENSOR DATA",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = formatSensorData(sensorData, context),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp
                    )
                }
            }
        }
        
        // Fullscreen camera overlay
        if (videoSize == VideoSize.FULLSCREEN) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable {
                        videoSize = VideoSize.SMALL
                    }
            ) {
                CameraPreviewComposable(lifecycleOwner = lifecycleOwner)
                
                // Close button (X)
                IconButton(
                    onClick = { videoSize = VideoSize.SMALL },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "[X]",
                        style = MaterialTheme.typography.headlineLarge
                    )
                }
            }
        }
        
        // Retro-style scrollbar (Windows 3.1 / Apple 1 aesthetic)
        if (scrollState.maxValue > 0) {
            val scrollbarHeight = 200.dp
            val thumbHeight = 40.dp
            val scrollProgress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            val thumbOffset = (scrollbarHeight - thumbHeight) * scrollProgress
            
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Scrollbar thumb
                Box(
                    modifier = Modifier
                        .offset(y = thumbOffset)
                        .width(12.dp)
                        .height(thumbHeight)
                        .background(MaterialTheme.colorScheme.outline)
                        .padding(1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

@Composable
fun CameraPreviewComposable(lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    
                    LogManager.success("Camera preview started (Compose)")
                } catch (e: Exception) {
                    LogManager.error("Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView.apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

data class SensorData(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f,
    val lightLevel: Float = 0f,
    val pressure: Float = 0f,
    val proximity: Float = 0f,
    val stepCount: Int = 0,
    val location: Location? = null,
    val micLevel: Float = 0f,
    val batteryLevel: Int = 0,
    val batteryCharging: Boolean = false
)

fun formatSensorData(data: SensorData, context: Context): String {
    val prefs = context.getSharedPreferences("polly_settings", Context.MODE_PRIVATE)
    val isCalibrated = prefs.getBoolean("is_calibrated", false)
    
    // Apply calibration baseline
    val accelX = if (isCalibrated) data.accelX - prefs.getFloat("baseline_accel_x", 0f) else data.accelX
    val accelY = if (isCalibrated) data.accelY - prefs.getFloat("baseline_accel_y", 0f) else data.accelY
    val accelZ = if (isCalibrated) data.accelZ - prefs.getFloat("baseline_accel_z", 0f) else data.accelZ
    
    return buildString {
        // Each line must be exactly 47 chars total (┌/│ + 45 content + ┐/│)
        append("┌─ ACCELEROMETER ─────────────────────────────\n")
        append("│ X:%6.2f Y:%6.2f Z:%6.2f m/s^2            \n".format(accelX, accelY, accelZ))
        append("│ %s\n".format("STATUS: ${getOrientation(accelX, accelY, accelZ)}"))
        if (isCalibrated) {
            append("│ %s\n".format("[CALIBRATED]"))
        }
        append("└────────────────────────────────────────────\n\n")
        
        val gyroMagnitude = sqrt(data.gyroX * data.gyroX + data.gyroY * data.gyroY + data.gyroZ * data.gyroZ)
        append("┌─ GYROSCOPE ─────────────────────────────────\n")
        append("│ X:%6.2f Y:%6.2f Z:%6.2f deg/s              \n".format(data.gyroX, data.gyroY, data.gyroZ))
        append("│ %s\n".format("MAGNITUDE: %.2f deg/s".format(gyroMagnitude)))
        append("└────────────────────────────────────────────\n\n")
        
        val heading = getHeading(data.magX, data.magY)
        append("┌─ MAGNETOMETER ──────────────────────────────\n")
        append("│ %s\n".format("HEADING: %3d deg (%2s)".format(heading.first, heading.second)))
        append("│ X:%7.1f Y:%7.1f Z:%7.1f uT            \n".format(data.magX, data.magY, data.magZ))
        append("└────────────────────────────────────────────\n\n")
        
        data.location?.let { loc ->
            append("┌─ GPS ───────────────────────────────────────\n")
            append("│ %s\n".format("LAT:  %9.6f".format(loc.latitude)))
            append("│ %s\n".format("LON: %10.6f".format(loc.longitude)))
            if (loc.hasAltitude() && loc.hasAccuracy()) {
                append("│ %s\n".format("ALT: %6.1f m  ACC: %4.1f m".format(loc.altitude, loc.accuracy)))
            } else if (loc.hasAltitude()) {
                append("│ %s\n".format("ALT: %6.1f m".format(loc.altitude)))
            }
            if (loc.hasSpeed()) {
                append("│ %s\n".format("SPEED: %5.1f km/h".format(loc.speed * 3.6)))
            }
            append("└────────────────────────────────────────────\n\n")
        } ?: run {
            append("┌─ GPS ───────────────────────────────────────\n")
            append("│ %s\n".format("STATUS: ░ SEARCHING FOR SIGNAL..."))
            append("└────────────────────────────────────────────\n\n")
        }
        
        append("┌─ MICROPHONE ────────────────────────────────\n")
        val micBars = (data.micLevel / 10).toInt().coerceIn(0, 10)
        val micBar = "▓".repeat(micBars) + "░".repeat(10 - micBars)
        append("│ %s\n".format("$micBar %5.1f%%".format(data.micLevel)))
        append("└────────────────────────────────────────────\n\n")
        
        append("┌─ BATTERY ───────────────────────────────────\n")
        val batteryBars = (data.batteryLevel / 10).coerceIn(0, 10)
        val batteryBar = "▓".repeat(batteryBars) + "░".repeat(10 - batteryBars)
        append("│ %s\n".format("$batteryBar %3d%%".format(data.batteryLevel)))
        append("│ %s\n".format("STATUS: ${if (data.batteryCharging) "▓ CHARGING" else "░ NOT CHARGING"}"))
        append("└────────────────────────────────────────────\n\n")
        
        if (data.lightLevel > 0) {
            val lightDesc = when {
                data.lightLevel < 10 -> "DARK"
                data.lightLevel < 100 -> "DIM"
                data.lightLevel < 1000 -> "INDOOR"
                data.lightLevel < 10000 -> "BRIGHT"
                else -> "VERY BRIGHT"
            }
            append("┌─ LIGHT SENSOR ──────────────────────────────\n")
            append("│ %s\n".format("LEVEL: %7.1f lux  (%s)".format(data.lightLevel, lightDesc)))
            append("└────────────────────────────────────────────\n\n")
        }
        
        if (data.pressure > 0) {
            val altitude = 44330 * (1 - Math.pow((data.pressure / 1013.25), 1.0 / 5.255))
            append("┌─ BAROMETER ─────────────────────────────────\n")
            append("│ %s\n".format("PRESSURE: %7.2f hPa".format(data.pressure)))
            append("│ %s\n".format("EST. ALT: %6.1f m".format(altitude)))
            append("└────────────────────────────────────────────\n\n")
        }
        
        if (data.proximity >= 0) {
            val proxDesc = if (data.proximity < 1) "▓ NEAR" else "░ FAR"
            append("┌─ PROXIMITY ─────────────────────────────────\n")
            append("│ %s\n".format("DISTANCE: %5.1f cm  STATUS: %s".format(data.proximity, proxDesc)))
            append("└────────────────────────────────────────────\n\n")
        }
        
        if (data.stepCount > 0) {
            append("┌─ STEP COUNTER ──────────────────────────────\n")
            append("│ %s\n".format("STEPS: %d".format(data.stepCount)))
            append("└────────────────────────────────────────────\n")
        }
    }
}

fun getOrientation(x: Float, y: Float, z: Float): String {
    // In landscape mode: X points "up" when tilted forward, Y points "left" when tilted left
    // Adjusted for landscape-first usage
    return when {
        abs(x) < 2 && abs(y) < 2 -> "▓ LEVEL"
        x < -5 -> "↑ TILTED FORWARD"
        x > 5 -> "↓ TILTED BACK"
        y < -5 -> "→ TILTED RIGHT"  
        y > 5 -> "← TILTED LEFT"
        else -> "~ ANGLED"
    }
}

fun getHeading(x: Float, y: Float): Pair<Int, String> {
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

// Fix negative zero display
fun Float.formatSensor(format: String): String {
    val value = if (abs(this) < 0.01f) abs(this) else this
    return format.format(value)
}

