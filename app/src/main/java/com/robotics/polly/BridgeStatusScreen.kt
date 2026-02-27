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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BridgeStatusScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity

    var ip by remember { mutableStateOf("...") }
    var port by remember { mutableStateOf(8080) }
    var serverRunning by remember { mutableStateOf(false) }
    var arduinoConnected by remember { mutableStateOf(false) }
    var flirConnected by remember { mutableStateOf(false) }
    var cameraConnected by remember { mutableStateOf(false) }
    var imuConnected by remember { mutableStateOf(false) }
    var totalClients by remember { mutableIntStateOf(0) }
    var arduinoRetryExhausted by remember { mutableStateOf(false) }
    var flirRetryExhausted by remember { mutableStateOf(false) }
    var arduinoClients by remember { mutableIntStateOf(0) }
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
                flirConnected = status["flirConnected"] as? Boolean ?: false
                cameraConnected = status["cameraConnected"] as? Boolean ?: false
                imuConnected = status["imuConnected"] as? Boolean ?: false
                arduinoRetryExhausted = status["arduinoRetryExhausted"] as? Boolean ?: false
                flirRetryExhausted = status["flirRetryExhausted"] as? Boolean ?: false
                totalClients = status["clients"] as? Int ?: 0

                @Suppress("UNCHECKED_CAST")
                val endpoints = status["endpoints"] as? Map<String, Int>
                if (endpoints != null) {
                    arduinoClients = endpoints["arduino"] ?: 0
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
            DeviceRow(
                name = "ARDUINO", connected = arduinoConnected, clients = arduinoClients,
                endpoint = "/arduino", retryExhausted = arduinoRetryExhausted,
                onReconnect = { activity?.getBridgeService()?.retryBridge("arduino") }
            )
            DeviceRow(
                name = "FLIR", connected = flirConnected, clients = flirClients,
                endpoint = "/flir", retryExhausted = flirRetryExhausted,
                onReconnect = { activity?.getBridgeService()?.retryBridge("flir") }
            )
            DeviceRow(name = "CAMERA", connected = cameraConnected, clients = cameraClients, endpoint = "/camera")
            DeviceRow(name = "IMU", connected = imuConnected, clients = imuClients, endpoint = "/imu")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Diagnostics Card
        var pingResult by remember { mutableStateOf("--") }
        var pingLatency by remember { mutableStateOf("--") }

        StatusCard(title = "DIAGNOSTICS") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val arduino = activity?.getBridgeService()?.getArduinoBridge()
                        if (arduino != null && arduino.isConnected) {
                            pingResult = "..."
                            val startTime = System.currentTimeMillis()
                            val listener = object : (String) -> Unit {
                                override fun invoke(line: String) {
                                    if (line.contains("\"ok\"") || line.contains("\"error\"")) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        pingResult = line.trim()
                                        pingLatency = "${elapsed}ms"
                                        arduino.localListeners.remove(this)
                                    }
                                }
                            }
                            arduino.localListeners.add(listener)
                            arduino.sendCommand("{\"N\":1}")
                            LogManager.tx("Ping: {\"N\":1}")
                        } else {
                            pingResult = "Arduino not connected"
                            pingLatency = "--"
                        }
                    },
                    modifier = Modifier.weight(1f).height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("PING", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }

                Button(
                    onClick = {
                        val arduino = activity?.getBridgeService()?.getArduinoBridge()
                        if (arduino != null && arduino.isConnected) {
                            arduino.sendCommand("{\"N\":101}")
                            LogManager.tx("State dump: {\"N\":101}")
                            pingResult = "State dump sent (check logs)"
                        }
                    },
                    modifier = Modifier.weight(1f).height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("STATE", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            StatusRow(label = "PING RESPONSE", value = pingResult)
            StatusRow(label = "LATENCY", value = pingLatency)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Motor Test Card
        var motorSpeed by remember { mutableFloatStateOf(150f) }
        var lastMotorCmd by remember { mutableStateOf("--") }
        val motorScope = rememberCoroutineScope()
        var motorJob by remember { mutableStateOf<Job?>(null) }

        StatusCard(title = "MOTOR TEST") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SPD",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp)
                )
                Slider(
                    value = motorSpeed,
                    onValueChange = { motorSpeed = it },
                    valueRange = 50f..255f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${motorSpeed.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp)
                )
            }

            val speed = motorSpeed.toInt()
            val arduino = activity?.getBridgeService()?.getArduinoBridge()
            val enabled = arduino?.isConnected == true

            fun sendMotorOnce(left: Int, right: Int) {
                val cmd = "{\"N\":7,\"D1\":$left,\"D2\":$right}"
                arduino?.sendCommand(cmd)
            }

            fun sendStopOnce() {
                arduino?.sendCommand("{\"N\":6}")
            }

            // Start sending a motor command at 10Hz for 1 second, cancelling any prior job
            fun startMotor(left: Int, right: Int) {
                motorJob?.cancel()
                lastMotorCmd = "L=$left R=$right"
                LogManager.tx("Motor test: L=$left R=$right (1s)")
                motorJob = motorScope.launch {
                    repeat(10) { // 10 sends over 1 second
                        sendMotorOnce(left, right)
                        delay(100)
                    }
                    sendStopOnce()
                    lastMotorCmd = "STOP (auto)"
                }
            }

            fun stopMotor() {
                motorJob?.cancel()
                motorJob = null
                sendStopOnce()
                lastMotorCmd = "STOP"
                LogManager.tx("Motor test: STOP")
            }

            // FWD row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(1f))
                MotorButton("FWD", enabled, Modifier.weight(1f)) { startMotor(speed, speed) }
                Spacer(modifier = Modifier.weight(1f))
            }
            // LEFT STOP RIGHT row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MotorButton("LEFT", enabled, Modifier.weight(1f)) { startMotor(speed, -speed) }
                MotorButton("STOP", enabled, Modifier.weight(1f), isStop = true) { stopMotor() }
                MotorButton("RIGHT", enabled, Modifier.weight(1f)) { startMotor(-speed, speed) }
            }
            // BACK row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(1f))
                MotorButton("BACK", enabled, Modifier.weight(1f)) { startMotor(-speed, -speed) }
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(4.dp))
            StatusRow(label = "LAST CMD", value = lastMotorCmd)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Endpoints Card
        StatusCard(title = "ENDPOINT DETAILS") {
            EndpointRow(path = "/arduino", description = "Arduino sensor stream (JSON)", clients = arduinoClients)
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
private fun DeviceRow(
    name: String,
    connected: Boolean,
    clients: Int,
    endpoint: String,
    retryExhausted: Boolean = false,
    onReconnect: (() -> Unit)? = null
) {
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

        // Reconnect button when not connected
        if (!connected && onReconnect != null) {
            TextButton(
                onClick = onReconnect,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "RETRY",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

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

@Composable
private fun MotorButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isStop: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.padding(2.dp).height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isStop) Color(0xFFB22222) else MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
