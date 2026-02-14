package com.robotics.polly

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("polly_settings", Context.MODE_PRIVATE)
    
    val originalServerPort = remember { prefs.getInt("server_port", 8080) }
    val originalDebugMode = remember { prefs.getBoolean("debug_mode", false) }

    var serverPort by remember { mutableStateOf(originalServerPort.toString()) }
    var debugMode by remember { mutableStateOf(originalDebugMode) }
    var testResult by remember { mutableStateOf("") }
    var lastCalibrationTime by remember { mutableStateOf(prefs.getLong("calibration_time", 0)) }
    var isCalibrated by remember { mutableStateOf(prefs.getBoolean("is_calibrated", false)) }
    
    // Current sensor values
    var currentAccelX by remember { mutableStateOf(0f) }
    var currentAccelY by remember { mutableStateOf(0f) }
    var currentAccelZ by remember { mutableStateOf(0f) }
    var currentGyroX by remember { mutableStateOf(0f) }
    var currentGyroY by remember { mutableStateOf(0f) }
    var currentGyroZ by remember { mutableStateOf(0f) }
    
    // Set up sensor listener
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        currentAccelX = event.values[0]
                        currentAccelY = event.values[1]
                        currentAccelZ = event.values[2]
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        currentGyroX = event.values[0]
                        currentGyroY = event.values[1]
                        currentGyroZ = event.values[2]
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
        
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header with buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SETTINGS",
                style = MaterialTheme.typography.displayMedium
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    serverPort = originalServerPort.toString()
                    debugMode = originalDebugMode
                    (context as? Activity)?.finish()
                }) {
                    Text("Revert")
                }

                Button(onClick = {
                    prefs.edit()
                        .putInt("server_port", serverPort.toIntOrNull() ?: 8080)
                        .putBoolean("debug_mode", debugMode)
                        .apply()
                    (context as? Activity)?.finish()
                }) {
                    Text("Save & Close")
                }
            }
        }
        
        // Sensor Calibration Section
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "SENSOR CALIBRATION",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Set current orientation as level baseline",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Button(
                    onClick = {
                        // Save current sensor values as baseline
                        val now = System.currentTimeMillis()
                        prefs.edit()
                            .putFloat("baseline_accel_x", currentAccelX)
                            .putFloat("baseline_accel_y", currentAccelY)
                            .putFloat("baseline_accel_z", currentAccelZ)
                            .putFloat("baseline_gyro_x", currentGyroX)
                            .putFloat("baseline_gyro_y", currentGyroY)
                            .putFloat("baseline_gyro_z", currentGyroZ)
                            .putLong("calibration_time", now)
                            .putBoolean("is_calibrated", true)
                            .apply()
                        
                        // Update UI state immediately
                        lastCalibrationTime = now
                        isCalibrated = true
                        testResult = "✅ Level calibrated! Baseline saved."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Calibrate Level")
                }
                
                if (isCalibrated) {
                    Text(
                        text = "Last calibrated: ${java.text.SimpleDateFormat("MMM dd, HH:mm").format(lastCalibrationTime)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                if (testResult.isNotEmpty()) {
                    Text(
                        text = testResult,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        
        // General Settings Section
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "GENERAL",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("WebSocket Server Port") },
                    placeholder = { Text("8080") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Debug Mode",
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = debugMode,
                        onCheckedChange = { debugMode = it }
                    )
                }
            }
        }
    }
}
