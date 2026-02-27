package com.robotics.polly

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity

    // Arduino state
    var arduinoConnected by remember { mutableStateOf(false) }
    var arduinoJson by remember { mutableStateOf<JSONObject?>(null) }

    // Phone IMU state
    var phoneAx by remember { mutableStateOf(0f) }
    var phoneAy by remember { mutableStateOf(0f) }
    var phoneAz by remember { mutableStateOf(0f) }
    var phoneGx by remember { mutableStateOf(0f) }
    var phoneGy by remember { mutableStateOf(0f) }
    var phoneGz by remember { mutableStateOf(0f) }

    // FLIR state
    var flirConnected by remember { mutableStateOf(false) }
    var thermalBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Listener refs for cleanup
    val arduinoListenerRef = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val flirListenerRef = remember { mutableStateOf<((Bitmap) -> Unit)?>(null) }

    // Phone IMU via SensorManager
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        phoneAx = event.values[0]
                        phoneAy = event.values[1]
                        phoneAz = event.values[2]
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        phoneGx = event.values[0]
                        phoneGy = event.values[1]
                        phoneGz = event.values[2]
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Register bridge listeners when service is ready
    LaunchedEffect(Unit) {
        var service: BridgeService? = null
        while (service == null) {
            service = activity?.getBridgeService()
            if (service == null) delay(500)
        }

        // Arduino listener
        val aListener: (String) -> Unit = { line ->
            try {
                val t = line.trim()
                if (t.startsWith("{")) arduinoJson = JSONObject(t)
            } catch (_: Exception) {}
        }
        service.getArduinoBridge()?.localListeners?.add(aListener)
        arduinoListenerRef.value = aListener

        // FLIR listener
        val fListener: (Bitmap) -> Unit = { thermalBitmap = it }
        service.getFlirBridge()?.localListeners?.add(fListener)
        flirListenerRef.value = fListener

        // Poll connection status
        while (true) {
            arduinoConnected = service.getArduinoBridge()?.isConnected == true
            flirConnected = service.getFlirBridge()?.isConnected == true
            delay(2000)
        }
    }

    // Cleanup listeners on dispose
    DisposableEffect(Unit) {
        onDispose {
            val service = activity?.getBridgeService()
            arduinoListenerRef.value?.let { service?.getArduinoBridge()?.localListeners?.remove(it) }
            flirListenerRef.value?.let { service?.getFlirBridge()?.localListeners?.remove(it) }
        }
    }

    // Two-column landscape layout
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        // Left column: text-based sensor data
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(end = 6.dp)
        ) {
            ArduinoSensorCard(arduinoConnected, arduinoJson)
            Spacer(modifier = Modifier.height(8.dp))
            PhoneImuCard(phoneAx, phoneAy, phoneAz, phoneGx, phoneGy, phoneGz)
        }

        // Right column: visual sensor data
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp)
        ) {
            // Thermal camera
            VisualCard(
                title = "THERMAL",
                connected = flirConnected,
                modifier = Modifier.weight(1f)
            ) {
                val bmp = thermalBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Thermal",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No thermal data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }
}

// -- Card composables --

@Composable
private fun DeviceCard(
    title: String,
    connected: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardHeader(title, connected)
            content()
        }
    }
}

@Composable
private fun VisualCard(
    title: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            CardHeader(title, connected, subtitle)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun CardHeader(title: String, connected: Boolean, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (connected) Color(0xFF1B7340) else Color(0xFFB22222),
                    shape = MaterialTheme.shapes.small
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
}

@Composable
private fun DataRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

// -- Sensor cards --

@Composable
private fun ArduinoSensorCard(connected: Boolean, json: JSONObject?) {
    DeviceCard(title = "ARDUINO", connected = connected) {
        if (json == null) {
            Text(
                text = if (connected) "Waiting for data..." else "Disconnected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            return@DeviceCard
        }

        // Ultrasonic distance
        val distance = json.optInt("distance", -1)
        if (distance >= 0) {
            DataRow("ULTRASONIC", "${distance} cm", valueColor = when {
                distance < 10 -> Color(0xFFB22222)
                distance < 30 -> Color(0xFFCC8800)
                else -> null
            })
        }

        // IR line sensors
        val ir = json.optJSONArray("ir")
        val onLine = json.optJSONArray("onLine")
        if (ir != null) {
            DataRow("IR L/M/R", "${ir.optInt(0)} ${ir.optInt(1)} ${ir.optInt(2)}")
            if (onLine != null) {
                val lines = (0..2).filter { onLine.optInt(it) == 1 }
                    .map { listOf("L", "M", "R")[it] }
                if (lines.isNotEmpty()) {
                    DataRow("ON LINE", lines.joinToString(" "))
                }
            }
        }

        // MPU-6050 IMU
        val mpuValid = json.optInt("mpuValid", 0) == 1
        if (mpuValid) {
            val accel = json.optJSONArray("accel")
            val gyro = json.optJSONArray("gyro")
            if (accel != null) {
                DataRow("ACCEL", "${accel.optInt(0)} ${accel.optInt(1)} ${accel.optInt(2)}")
            }
            if (gyro != null) {
                DataRow("GYRO", "${gyro.optInt(0)} ${gyro.optInt(1)} ${gyro.optInt(2)}")
            }
            val temp = json.optDouble("temp", 0.0)
            if (temp > 0) DataRow("TEMP", "${"%.1f".format(temp)}C")
        } else {
            DataRow("MPU-6050", "N/A", valueColor = Color(0xFF8EAEC4))
        }

        // Battery
        val battery = json.optDouble("battery", 0.0)
        if (battery > 0) {
            DataRow("BATTERY", "${"%.2f".format(battery)}V", valueColor = when {
                battery < 6.5 -> Color(0xFFB22222)
                battery < 7.0 -> Color(0xFFCC8800)
                else -> Color(0xFF1B7340)
            })
        }

        // Uptime
        val ts = json.optLong("ts", 0)
        if (ts > 0) DataRow("UPTIME", "${ts / 1000}s")
    }
}

@Composable
private fun PhoneImuCard(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
    DeviceCard(title = "PHONE IMU", connected = true) {
        DataRow("ACCEL X", "%.2f".format(ax))
        DataRow("ACCEL Y", "%.2f".format(ay))
        DataRow("ACCEL Z", "%.2f".format(az))
        DataRow("GYRO X", "%.4f".format(gx))
        DataRow("GYRO Y", "%.4f".format(gy))
        DataRow("GYRO Z", "%.4f".format(gz))
    }
}

