package com.robotics.polly

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity

    var mapActive by remember { mutableStateOf(false) }
    var wandering by remember { mutableStateOf(false) }
    var exploring by remember { mutableStateOf(false) }
    var explorationDone by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordedFrames by remember { mutableIntStateOf(0) }
    var cellCount by remember { mutableIntStateOf(0) }
    var updateCount by remember { mutableIntStateOf(0) }
    var rejectedCount by remember { mutableIntStateOf(0) }

    // Transform state for pan/zoom
    var scale by remember { mutableFloatStateOf(200f) } // pixels per meter
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Grid snapshot for rendering
    var gridSnapshot by remember {
        mutableStateOf<Map<OccupancyGrid.GridCell, OccupancyGrid.CellState>>(emptyMap())
    }
    var trailSnapshot by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var robotX by remember { mutableFloatStateOf(0f) }
    var robotZ by remember { mutableFloatStateOf(0f) }
    var robotHeading by remember { mutableFloatStateOf(0f) }
    var gridCellSize by remember { mutableFloatStateOf(0.05f) }

    // Ultrasonic hit point
    var hitX by remember { mutableFloatStateOf(Float.NaN) }
    var hitZ by remember { mutableFloatStateOf(Float.NaN) }

    // Camera preview
    var previewBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // Poll grid data at ~10Hz
    LaunchedEffect(Unit) {
        while (true) {
            val service = activity?.getBridgeService()
            mapActive = service?.mapMode == true
            wandering = service?.isWandering() == true
            exploring = service?.isExploring() == true
            explorationDone = service?.isExplorationComplete() == true
            recording = service?.isRecording() == true
            recordedFrames = service?.getRecordedFrameCount() ?: 0
            val mapper = service?.getGridMapper()
            if (mapper != null) {
                val grid = mapper.grid
                gridSnapshot = HashMap(grid.getCells())
                trailSnapshot = grid.getTrail().toList()
                robotX = grid.robotX
                robotZ = grid.robotZ
                robotHeading = grid.robotHeadingRad
                gridCellSize = grid.cellSize
                cellCount = grid.cellCount()
                updateCount = mapper.updateCount
                rejectedCount = mapper.rejectedCount
                hitX = grid.lastHitX
                hitZ = grid.lastHitZ
            }
            // Decode camera preview JPEG
            val jpeg = service?.getARCoreBridge()?.latestPreviewJpeg
            if (jpeg != null) {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) previewBitmap = bmp.asImageBitmap()
            } else if (!mapActive) {
                previewBitmap = null
            }
            delay(100)
        }
    }

    // Pinch-zoom
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(50f, 2000f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Controls bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "OCCUPANCY MAP",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "$cellCount cells | $updateCount ok | $rejectedCount rej",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mapActive) {
                        Button(
                            onClick = {
                                if (exploring) {
                                    activity?.getBridgeService()?.stopExploreMode()
                                } else if (wandering) {
                                    activity?.getBridgeService()?.stopWanderMode()
                                } else {
                                    activity?.getBridgeService()?.stopMapMode()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB22222)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                "STOP",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                val service = activity?.getBridgeService()
                                if (recording) {
                                    service?.stopRecording()
                                } else {
                                    service?.startRecording()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (recording) Color(0xFFCC0000) else Color(0xFF8B0000)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                if (recording) "REC $recordedFrames" else "REC",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                activity?.getBridgeService()?.startMapMode()
                            },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                "START MAP",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                activity?.getBridgeService()?.startExploreMode()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B7340)
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                "EXPLORE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            "CENTER",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Map canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF5F3EE))
                    .transformable(state = transformState)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                fun toScreenX(wx: Float) = cx + (wx - robotX) * scale + offsetX
                fun toScreenY(wz: Float) = cy + (wz - robotZ) * scale + offsetY

                val cellPx = gridCellSize * scale

                // Draw grid cells
                for ((cell, state) in gridSnapshot) {
                    val sx = toScreenX(cell.ix * gridCellSize)
                    val sy = toScreenY(cell.iz * gridCellSize)

                    if (sx < -cellPx || sx > size.width + cellPx ||
                        sy < -cellPx || sy > size.height + cellPx) continue

                    val color = when (state) {
                        OccupancyGrid.CellState.FREE -> Color(0xFFE8E8E8)
                        OccupancyGrid.CellState.OCCUPIED -> Color(0xFF0A3D6B)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(sx, sy),
                        size = Size(cellPx.coerceAtLeast(1f), cellPx.coerceAtLeast(1f))
                    )
                }

                // Draw robot trail (ARCore path)
                if (trailSnapshot.size >= 2) {
                    for (i in 1 until trailSnapshot.size) {
                        val (px, pz) = trailSnapshot[i - 1]
                        val (curX, curZ) = trailSnapshot[i]
                        drawLine(
                            color = Color(0xFF8EAEC4),
                            start = Offset(toScreenX(px), toScreenY(pz)),
                            end = Offset(toScreenX(curX), toScreenY(curZ)),
                            strokeWidth = 2f
                        )
                    }
                }

                // Draw ultrasonic ray from robot to current hit point
                if (!hitX.isNaN() && !hitZ.isNaN()) {
                    val rsx = toScreenX(robotX)
                    val rsy = toScreenY(robotZ)
                    val hsx = toScreenX(hitX)
                    val hsy = toScreenY(hitZ)
                    // Ray line (orange)
                    drawLine(
                        color = Color(0xFFE67E22),
                        start = Offset(rsx, rsy),
                        end = Offset(hsx, hsy),
                        strokeWidth = 2f
                    )
                    // Hit point dot (red)
                    drawCircle(
                        color = Color(0xFFE74C3C),
                        radius = 5f,
                        center = Offset(hsx, hsy)
                    )
                }

                // Draw robot triangle
                drawRobotTriangle(
                    toScreenX(robotX), toScreenY(robotZ), robotHeading, 12f
                )

                // Draw scale bar
                drawScaleBar(scale)
            }

            // Camera preview (bottom-right corner)
            previewBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Camera preview",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(160.dp, 120.dp)
                        .border(2.dp, Color(0xFF0A3D6B))
                        .background(Color.Black),
                    contentScale = ContentScale.Crop
                )
            }

            // Status badge
            if (mapActive) {
                val recSuffix = if (recording) " REC" else ""
                val (badgeColor, badgeText) = when {
                    explorationDone -> Color(0xFF2E86C1) to "COMPLETE$recSuffix"
                    exploring -> Color(0xFF1B7340) to "EXPLORING$recSuffix"
                    wandering -> Color(0xFFE67E22) to "WANDERING$recSuffix"
                    else -> Color(0xFF1B7340) to "TRACKING$recSuffix"
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawRobotTriangle(sx: Float, sy: Float, heading: Float, sz: Float) {
    val cosH = cos(heading)
    val sinH = sin(heading)

    val path = Path().apply {
        // Tip (forward direction)
        moveTo(sx + cosH * sz, sy + sinH * sz)
        // Left base
        lineTo(
            sx + (-cosH * 0.5f - sinH * 0.6f) * sz,
            sy + (-sinH * 0.5f + cosH * 0.6f) * sz
        )
        // Right base
        lineTo(
            sx + (-cosH * 0.5f + sinH * 0.6f) * sz,
            sy + (-sinH * 0.5f - cosH * 0.6f) * sz
        )
        close()
    }
    drawPath(path, color = Color(0xFF0055AA))
    drawCircle(color = Color.White, radius = 2f, center = Offset(sx, sy))
}

private fun DrawScope.drawScaleBar(scale: Float) {
    val barM = 0.10f // 10cm
    val barPx = barM * scale
    if (barPx < 10f || barPx > size.width / 2f) return

    val x = 20f
    val y = size.height - 30f
    val lineColor = Color(0xFF0A3D6B)

    drawLine(lineColor, Offset(x, y), Offset(x + barPx, y), strokeWidth = 3f)
    drawLine(lineColor, Offset(x, y - 5f), Offset(x, y + 5f), strokeWidth = 2f)
    drawLine(lineColor, Offset(x + barPx, y - 5f), Offset(x + barPx, y + 5f), strokeWidth = 2f)
}
