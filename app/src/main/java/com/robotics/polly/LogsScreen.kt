package com.robotics.polly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    var showTx by remember { mutableStateOf(true) }
    var showRx by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showWarn by remember { mutableStateOf(true) }
    var showError by remember { mutableStateOf(true) }
    var logText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    // Update logs periodically with filtering
    LaunchedEffect(showTx, showRx, showInfo, showWarn, showError) {
        while (true) {
            val logs = LogManager.getLogs().filter { entry ->
                when (entry.level) {
                    LogManager.LogLevel.TX -> showTx
                    LogManager.LogLevel.RX -> showRx
                    LogManager.LogLevel.INFO, LogManager.LogLevel.SUCCESS -> showInfo
                    LogManager.LogLevel.WARN -> showWarn
                    LogManager.LogLevel.ERROR -> showError
                }
            }
            logText = logs.joinToString("\n") { entry ->
                val prefix = when (entry.level) {
                    LogManager.LogLevel.INFO -> "ℹ️"
                    LogManager.LogLevel.SUCCESS -> "✅"
                    LogManager.LogLevel.ERROR -> "❌"
                    LogManager.LogLevel.WARN -> "⚠️"
                    LogManager.LogLevel.TX -> "📤"
                    LogManager.LogLevel.RX -> "📥"
                }
                "${entry.timestamp} $prefix ${entry.message}"
            }
            delay(500)
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
            // Logs Header Card
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "SYSTEM LOGS",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        
                        Button(
                            onClick = { LogManager.clear() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = "[CLEAR]",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    // Filter chips
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(selected = showTx, onClick = { showTx = !showTx },
                            label = { Text("TX", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp))
                        FilterChip(selected = showRx, onClick = { showRx = !showRx },
                            label = { Text("RX", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp))
                        FilterChip(selected = showInfo, onClick = { showInfo = !showInfo },
                            label = { Text("INFO", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp))
                        FilterChip(selected = showWarn, onClick = { showWarn = !showWarn },
                            label = { Text("WARN", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp))
                        FilterChip(selected = showError, onClick = { showError = !showError },
                            label = { Text("ERR", fontSize = 9.sp) },
                            modifier = Modifier.height(24.dp))
                    }
                }
            }
            
            // Logs Content Card
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
                        text = formatLogsWithBoxes(logText),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
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

fun formatLogsWithBoxes(rawLogs: String): String {
    return if (rawLogs.isBlank()) {
        "-- NO LOGS --\n\n  No system events yet"
    } else {
        rawLogs
    }
}
