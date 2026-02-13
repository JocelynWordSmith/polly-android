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

@Composable
fun LogsScreen() {
    var logText by remember { mutableStateOf(LogManager.getFormattedLogs()) }
    val scrollState = rememberScrollState()
    
    // Update logs periodically
    LaunchedEffect(Unit) {
        while (true) {
            logText = LogManager.getFormattedLogs()
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
                            text = "▓ SYSTEM LOGS",
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
        "┌─ NO LOGS ───────────────────────────────────┐\n" +
        "│ %-45s │\n".format("No system events yet") +
        "└─────────────────────────────────────────────┘"
    } else {
        rawLogs
    }
}
