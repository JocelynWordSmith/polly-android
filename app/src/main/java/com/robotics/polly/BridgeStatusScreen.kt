package com.robotics.polly

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun BridgeStatusScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity

    var ip by remember { mutableStateOf("...") }
    var port by remember { mutableStateOf(8080) }
    var serverRunning by remember { mutableStateOf(false) }
    var arduinoConnected by remember { mutableStateOf(false) }
    var lidarConnected by remember { mutableStateOf(false) }
    var flirConnected by remember { mutableStateOf(false) }
    var cameraConnected by remember { mutableStateOf(false) }
    var imuConnected by remember { mutableStateOf(false) }
    var totalClients by remember { mutableIntStateOf(0) }
    var arduinoClients by remember { mutableIntStateOf(0) }
    var lidarClients by remember { mutableIntStateOf(0) }
    var cameraClients by remember { mutableIntStateOf(0) }
    var flirClients by remember { mutableIntStateOf(0) }
    var imuClients by remember { mutableIntStateOf(0) }
    var controlClients by remember { mutableIntStateOf(0) }

    // Poll bridge service status every second
    LaunchedEffect(Unit) {
        while (true) {
            val service = activity?.getBridgeService()
            if (service != null) {
                val status = service.getStatus()
                ip = status["ip"] as? String ?: "unknown"
                port = status["port"] as? Int ?: 8080
                serverRunning = status["running"] as? Boolean ?: false
                arduinoConnected = status["arduinoConnected"] as? Boolean ?: false
                lidarConnected = status["lidarConnected"] as? Boolean ?: false
                flirConnected = status["flirConnected"] as? Boolean ?: false
                cameraConnected = status["cameraConnected"] as? Boolean ?: false
                imuConnected = status["imuConnected"] as? Boolean ?: false
                totalClients = status["clients"] as? Int ?: 0

                @Suppress("UNCHECKED_CAST")
                val endpoints = status["endpoints"] as? Map<String, Int>
                if (endpoints != null) {
                    arduinoClients = endpoints["arduino"] ?: 0
                    lidarClients = endpoints["lidar"] ?: 0
                    cameraClients = endpoints["camera"] ?: 0
                    flirClients = endpoints["flir"] ?: 0
                    imuClients = endpoints["imu"] ?: 0
                    controlClients = endpoints["control"] ?: 0
                }
            }
            delay(1000)
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(12.dp)
    ) {
        // Server Info Card
        StatusCard(title = "WEBSOCKET SERVER") {
            StatusRow(
                label = "STATUS",
                value = if (serverRunning) "RUNNING" else "STOPPED",
                connected = serverRunning
            )
            StatusRow(label = "ADDRESS", value = "$ip:$port")
            StatusRow(label = "CLIENTS", value = "$totalClients connected")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Device Connections Card
        StatusCard(title = "DEVICE CONNECTIONS") {
            DeviceRow(name = "ARDUINO", connected = arduinoConnected, clients = arduinoClients, endpoint = "/arduino")
            DeviceRow(name = "LIDAR", connected = lidarConnected, clients = lidarClients, endpoint = "/lidar")
            DeviceRow(name = "FLIR", connected = flirConnected, clients = flirClients, endpoint = "/flir")
            DeviceRow(name = "CAMERA", connected = cameraConnected, clients = cameraClients, endpoint = "/camera")
            DeviceRow(name = "IMU", connected = imuConnected, clients = imuClients, endpoint = "/imu")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Endpoints Card
        StatusCard(title = "ENDPOINT DETAILS") {
            EndpointRow(path = "/arduino", description = "Arduino sensor stream (JSON)", clients = arduinoClients)
            EndpointRow(path = "/lidar", description = "RPLIDAR scan data (binary)", clients = lidarClients)
            EndpointRow(path = "/camera", description = "Camera frames (JPEG binary)", clients = cameraClients)
            EndpointRow(path = "/flir", description = "Thermal frames (14-bit raw)", clients = flirClients)
            EndpointRow(path = "/imu", description = "Phone IMU data (JSON)", clients = imuClients)
            EndpointRow(path = "/control", description = "Command channel (JSON)", clients = controlClients)
        }
    }
}

@Composable
private fun StatusCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Thin divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, connected: Boolean? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
            color = when (connected) {
                true -> Color(0xFF1B7340)  // AccentEmerald
                false -> Color(0xFFB22222) // AccentGarnet
                null -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun DeviceRow(name: String, connected: Boolean, clients: Int, endpoint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (connected) Color(0xFF1B7340) else Color(0xFFB22222),
                    shape = MaterialTheme.shapes.small
                )
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Device name
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(72.dp)
        )

        // Status text
        Text(
            text = if (connected) "CONNECTED" else "DISCONNECTED",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (connected) Color(0xFF1B7340) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f)
        )

        // Client count
        if (clients > 0) {
            Text(
                text = "${clients}x",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EndpointRow(path: String, description: String, clients: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$clients",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (clients > 0) Color(0xFF1B7340) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
