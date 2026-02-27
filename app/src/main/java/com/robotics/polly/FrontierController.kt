package com.robotics.polly

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Frontier-based exploration controller. Replaces random wander with directed
 * exploration toward unmapped regions.
 *
 * Phases:
 * 1. Wait for sensor data
 * 2. Initial 360° scan (same as WanderController)
 * 3. Exploration loop: find frontiers → plan path → drive → scan → repeat
 * 4. Stop when no reachable frontiers remain
 */
class FrontierController(
    private val arduinoBridge: ArduinoBridge,
    private val gridMapper: GridMapper
) {
    private var job: Job? = null
    @Volatile private var stopped = false

    val isRunning: Boolean get() = job?.isActive == true

    /** True when exploration finished because no frontiers remain. */
    @Volatile var explorationComplete = false
        private set

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        stopped = false
        explorationComplete = false
        job = scope.launch { explorationLoop() }
        Log.i(TAG, "Frontier exploration started")
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        sendStop()
        Log.i(TAG, "Frontier exploration stopped")
    }

    private suspend fun explorationLoop() {
        waitForData()
        if (stopped) return

        initialScan()
        if (stopped) return

        Log.i(TAG, "Initial scan complete, starting frontier exploration")

        var failedTargets = 0
        while (!stopped) {
            val grid = gridMapper.grid
            val rawCells = grid.getRawCells()
            val robotIx = grid.worldToGrid(grid.robotX)
            val robotIz = grid.worldToGrid(grid.robotZ)

            // Detect and cluster frontiers
            val frontiers = FrontierExplorer.findFrontiers(rawCells)
            if (frontiers.isEmpty()) {
                Log.i(TAG, "No frontiers found — exploration complete")
                explorationComplete = true
                return
            }

            val clusters = FrontierExplorer.clusterFrontiers(frontiers)
            Log.d(TAG, "${frontiers.size} frontier cells in ${clusters.size} clusters")

            // Try clusters in order of proximity until we find a reachable one
            var reached = false
            for (cluster in clusters.sortedBy { c ->
                val dx = c.centroidIx - robotIx
                val dz = c.centroidIz - robotIz
                dx * dx + dz * dz
            }) {
                if (stopped) return

                val path = FrontierExplorer.planPath(
                    robotIx, robotIz,
                    cluster.centroidIx, cluster.centroidIz,
                    rawCells
                )

                if (path == null || path.size < 2) continue

                Log.d(TAG, "Driving to frontier cluster (${cluster.cells.size} cells) " +
                    "at (${cluster.centroidIx}, ${cluster.centroidIz}), path length ${path.size}")

                val result = followPath(path)
                if (result) {
                    // Arrived — do a local scan to map the area
                    localScan()
                    reached = true
                    failedTargets = 0
                    break
                }
            }

            if (!reached) {
                failedTargets++
                if (failedTargets >= MAX_FAILED_TARGETS) {
                    Log.w(TAG, "Failed to reach $MAX_FAILED_TARGETS targets — stopping")
                    explorationComplete = true
                    return
                }
                // Try a reverse + scan to get unstuck
                reverseBurst()
                localScan()
            }
        }
    }

    // ---- Wait for sensor data ----

    private suspend fun waitForData() {
        Log.i(TAG, "Waiting for pose + ultrasonic data...")
        val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS
        while (gridMapper.updateCount == 0) {
            if (stopped) return
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "Timed out waiting for data, starting anyway")
                break
            }
            delay(200)
        }
        Log.i(TAG, "Data flowing (${gridMapper.updateCount} updates)")
    }

    // ---- Scanning ----

    private suspend fun initialScan() {
        gridMapper.startScanRecording()
        scan360()
        gridMapper.stopScanRecording()
    }

    private suspend fun localScan() {
        scan360()
    }

    private suspend fun scan360() {
        var totalRotation = 0f
        var lastHeading = gridMapper.grid.robotHeadingRad

        while (!stopped && totalRotation < FULL_ROTATION) {
            sendSpin(true)
            delay(SCAN_STEP_MS)
            sendStop()
            delay(SETTLE_MS)
            if (stopped) return

            val heading = gridMapper.grid.robotHeadingRad
            var delta = heading - lastHeading
            while (delta > PI) delta -= (2 * PI).toFloat()
            while (delta < -PI) delta += (2 * PI).toFloat()
            totalRotation += abs(delta)
            lastHeading = heading
        }
        sendStop()
        delay(SETTLE_MS)
    }

    // ---- Path following ----

    /**
     * Follow a path of grid cells. Returns true if the robot reached the target
     * area (within ARRIVAL_CELLS of the last waypoint).
     */
    private suspend fun followPath(path: List<OccupancyGrid.GridCell>): Boolean {
        val grid = gridMapper.grid
        val cellSize = grid.cellSize
        var waypointIdx = 0
        var replanAttempts = 0

        while (!stopped && waypointIdx < path.size) {
            val robotIx = grid.worldToGrid(grid.robotX)
            val robotIz = grid.worldToGrid(grid.robotZ)

            // Look ahead: pick farthest waypoint within LOOKAHEAD_CELLS
            var targetIdx = waypointIdx
            for (i in waypointIdx until minOf(waypointIdx + LOOKAHEAD_CELLS, path.size)) {
                val dx = abs(path[i].ix - robotIx)
                val dz = abs(path[i].iz - robotIz)
                if (dx <= LOOKAHEAD_CELLS && dz <= LOOKAHEAD_CELLS) {
                    targetIdx = i
                }
            }

            val target = path[targetIdx]

            // Check if we've arrived at this waypoint
            val dIx = abs(target.ix - robotIx)
            val dIz = abs(target.iz - robotIz)
            if (dIx <= ARRIVAL_CELLS && dIz <= ARRIVAL_CELLS) {
                waypointIdx = targetIdx + 1
                replanAttempts = 0
                continue
            }

            // Compute heading to target
            val targetX = (target.ix + 0.5f) * cellSize
            val targetZ = (target.iz + 0.5f) * cellSize
            val desiredHeading = atan2(
                (targetZ - grid.robotZ).toDouble(),
                (targetX - grid.robotX).toDouble()
            ).toFloat()

            // Rotate to face target
            if (!rotateToHeading(desiredHeading)) return false

            // Check if path is still clear
            val distCm = gridMapper.lastDistCm
            val ultrasonicClose = distCm in 1..OBSTACLE_NEAR_CM
            val gridBlocked = !grid.isPathClear(grid.robotX, grid.robotZ, desiredHeading, CHECK_DIST, HALF_WIDTH)

            if (ultrasonicClose || gridBlocked) {
                replanAttempts++
                if (replanAttempts >= MAX_REPLAN) {
                    Log.d(TAG, "Blocked after $MAX_REPLAN replan attempts, giving up on target")
                    return false
                }
                // Replan: break out and let exploration loop select new target
                Log.d(TAG, "Path blocked, replan attempt $replanAttempts")
                delay(SETTLE_MS)
                return false
            }

            // Drive forward one burst
            driveForwardBurst()
            if (stopped) return false
        }

        return true
    }

    /**
     * Rotate in place until heading matches desired heading (within tolerance).
     * Returns false if stopped or timed out.
     */
    private suspend fun rotateToHeading(desiredHeading: Float): Boolean {
        var steps = 0
        while (!stopped && steps < MAX_TURN_STEPS) {
            val heading = gridMapper.grid.robotHeadingRad
            var delta = desiredHeading - heading
            while (delta > PI) delta -= (2 * PI).toFloat()
            while (delta < -PI) delta += (2 * PI).toFloat()

            if (abs(delta) < HEADING_TOLERANCE) return true

            sendTurn(delta > 0)
            delay(TURN_STEP_MS)
            sendStop()
            delay(SETTLE_MS)
            steps++
        }
        return !stopped
    }

    // ---- Motor primitives ----

    private suspend fun driveForwardBurst() {
        val deadline = System.currentTimeMillis() + DRIVE_BURST_MS
        while (!stopped && System.currentTimeMillis() < deadline) {
            val distCm = gridMapper.lastDistCm
            if (distCm in 1..OBSTACLE_NEAR_CM) break
            sendForward()
            delay(CMD_INTERVAL_MS)
        }
        sendStop()
        delay(SETTLE_MS)
    }

    private suspend fun reverseBurst() {
        val deadline = System.currentTimeMillis() + REVERSE_BURST_MS
        while (!stopped && System.currentTimeMillis() < deadline) {
            sendReverse()
            delay(CMD_INTERVAL_MS)
        }
        sendStop()
        delay(SETTLE_MS)
    }

    private fun sendForward() {
        arduinoBridge.sendCommand("{\"N\":7,\"D1\":$SPEED,\"D2\":$SPEED}")
    }

    private fun sendReverse() {
        arduinoBridge.sendCommand("{\"N\":7,\"D1\":${-SPEED},\"D2\":${-SPEED}}")
    }

    private fun sendTurn(left: Boolean) {
        if (left) {
            arduinoBridge.sendCommand("{\"N\":7,\"D1\":${-TURN_SPEED},\"D2\":$TURN_SPEED}")
        } else {
            arduinoBridge.sendCommand("{\"N\":7,\"D1\":$TURN_SPEED,\"D2\":${-TURN_SPEED}}")
        }
    }

    private fun sendSpin(left: Boolean) {
        if (left) {
            arduinoBridge.sendCommand("{\"N\":7,\"D1\":${-SCAN_SPEED},\"D2\":$SCAN_SPEED}")
        } else {
            arduinoBridge.sendCommand("{\"N\":7,\"D1\":$SCAN_SPEED,\"D2\":${-SCAN_SPEED}}")
        }
    }

    private fun sendStop() {
        arduinoBridge.sendCommand("{\"N\":6}")
    }

    companion object {
        private const val TAG = "FrontierController"

        // Motor speeds (match WanderController)
        private const val SPEED = 80
        private const val TURN_SPEED = 80
        private const val SCAN_SPEED = 70

        // Timing (match WanderController)
        private const val CMD_INTERVAL_MS = 100L
        private const val SETTLE_MS = 300L
        private const val DRIVE_BURST_MS = 250L
        private const val REVERSE_BURST_MS = 400L
        private const val SCAN_STEP_MS = 200L
        private const val TURN_STEP_MS = 200L
        private const val INIT_TIMEOUT_MS = 10_000L

        // Navigation
        private const val OBSTACLE_NEAR_CM = 20
        private const val CHECK_DIST = 0.25f
        private const val HALF_WIDTH = 0.09f
        private const val MAX_TURN_STEPS = 15
        private const val HEADING_TOLERANCE = 0.26f  // ~15°
        private const val LOOKAHEAD_CELLS = 3
        private const val ARRIVAL_CELLS = 1
        private const val MAX_REPLAN = 3
        private const val MAX_FAILED_TARGETS = 5

        private const val FULL_ROTATION = (2 * PI).toFloat()
    }
}
