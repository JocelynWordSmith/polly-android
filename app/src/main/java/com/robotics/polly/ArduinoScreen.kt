package com.robotics.polly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun ArduinoScreen() {
    val context = LocalContext.current
    var arduinoData by remember { mutableStateOf("Waiting for Arduino data...") }
    val scrollState = rememberScrollState()
    
    // Poll Arduino sensor data
    LaunchedEffect(Unit) {
        while (true) {
            // This would connect to the Arduino polling logic
            // For now just showing the structure
            delay(200)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 0.dp, vertical = 4.dp)
        ) {
            // Arduino Data Card
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
                        text = "▓ ARDUINO UNO R3",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = formatArduinoData(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                }
            }
        }
        
        // Retro scrollbar
        if (scrollState.maxValue > 0) {
            val scrollbarHeight = 200.dp
            val thumbHeight = 40.dp
            val scrollProgress = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            val thumbOffset = (scrollbarHeight - thumbHeight) * scrollProgress
            
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
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

fun formatArduinoData(): String {
    return buildString {
        append("┌─ MOTOR CONTROL ─────────────────────────────┐\n")
        append("│ %-45s │\n".format("LEFT:  [▓▓▓▓▓░░░░░] PWM 180  FORWARD"))
        append("│ %-45s │\n".format("RIGHT: [▓▓▓▓▓░░░░░] PWM 180  FORWARD"))
        append("└─────────────────────────────────────────────┘\n\n")
        
        append("┌─ ULTRASONIC (HC-SR04) ──────────────────────┐\n")
        append("│ %-45s │\n".format("DISTANCE: 27 cm"))
        append("│ %-45s │\n".format("PINS: TRIGGER=12  ECHO=13"))
        append("│ %-45s │\n".format("[▓▓▓▓░░░░░░░░░░░░░░░░] NOMINAL"))
        append("└─────────────────────────────────────────────┘\n\n")
        
        append("┌─ IR LINE SENSORS (ITR20001) ────────────────┐\n")
        append("│ %-45s │\n".format("LEFT:   ▓ 689  (A0)"))
        append("│ %-45s │\n".format("CENTER: ▓ 633  (A1)"))
        append("│ %-45s │\n".format("RIGHT:  ▓ 830  (A2)"))
        append("│ %-45s │\n".format("ON LINE: [1] [1] [1]"))
        append("└─────────────────────────────────────────────┘\n\n")
        
        append("┌─ IMU (MPU-6050) ────────────────────────────┐\n")
        append("│ %-45s │\n".format("ACCEL: X:-204  Y:320  Z:17960  (raw)"))
        append("│ %-45s │\n".format("GYRO:  X:-583  Y:97   Z:-111   (raw)"))
        append("│ %-45s │\n".format("TEMP:  25.9 C"))
        append("└─────────────────────────────────────────────┘\n\n")
        
        append("┌─ BATTERY ───────────────────────────────────┐\n")
        append("│ %-45s │\n".format("VOLTAGE: 1.64 V"))
        append("│ %-45s │\n".format("STATUS:  ▓ NOMINAL"))
        append("└─────────────────────────────────────────────┘\n\n")
        
        append("┌─ SERIAL MONITOR ────────────────────────────┐\n")
        append("│ %-45s │\n".format(">> {\"N\":100}"))
        append("│ %-45s │\n".format("<< {\"ts\":32750,\"distance\":27,...}"))
        append("│ %-45s │\n".format(">> {\"N\":2,\"D1\":180}"))
        append("│ %-45s │\n".format("<< {\"ts\":32951,\"distance\":26,...}"))
        append("└─────────────────────────────────────────────┘\n")
    }
}
