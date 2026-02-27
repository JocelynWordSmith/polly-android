package com.robotics.polly

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Stop-and-go wandering controller for accurate occupancy grid mapping.
 *
 * Phase 1 (SCAN): Step-rotate 360° — short rotation burst, stop, wait for
 *   stable reading, repeat. Builds a ring of obstacles around the start.
 *   Scan readings are recorded for drift correction via scan-to-scan matching.
 * Phase 2 (DRIVE): Drive 0.25s → stop → read → decide. Readings are only
 *   taken while stationary so heading is accurate.
 *   The log-odds grid self-corrects stale walls as the robot drives through
 *   previously-occupied areas (free rays erase old evidence).
 */
class WanderController(
    private val arduinoBridge: ArduinoBridge,
    private val gridMapper: GridMapper
) {
    private var job: Job? = null
    @Volatile private var stopped = false

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        stopped = false
        job = scope.launch { wanderLoop() }
        Log.i(TAG, "Wander started")
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        sendStop()
        Log.i(TAG, "Wander stopped")
    }

    private suspend fun wanderLoop() {
        waitForData()
        if (stopped) return

        recordedScan()
        if (stopped) return

        Log.i(TAG, "Initial scan complete, driving")
        drive()
    }

    // ---- Wait for ARCore + ultrasonic to start flowing ----

    private suspend fun waitForData() {
        Log.i(TAG, "Waiting for ARCore + ultrasonic data...")
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

    // ---- Phase 1: Step-rotate scan ----

    /** Perform a 360° scan with recording for drift correction. */
    private suspend fun recordedScan() {
        gridMapper.startScanRecording()
        scan()
        gridMapper.stopScanRecording()
    }

    private suspend fun scan() {
        var totalRotation = 0f
        var lastHeading = gridMapper.grid.robotHeadingRad
        var turnLeft = true

        while (!stopped && totalRotation < FULL_ROTATION) {
            // Rotate a small step
            sendSpin(turnLeft)
            delay(SCAN_STEP_MS)
            sendStop()

            // Wait for stable reading
            delay(SETTLE_MS)
            if (stopped) return

            // Track rotation
            val heading = gridMapper.grid.robotHeadingRad
            var delta = heading - lastHeading
            while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            totalRotation += abs(delta)
            lastHeading = heading
        }
        sendStop()
        delay(SETTLE_MS)
    }

    // ---- Phase 2: Stop-and-go drive ----

    private suspend fun drive() {
        var turnGoLeft = true
        var turnSteps = 0

        while (!stopped) {
            val grid = gridMapper.grid
            val distCm = gridMapper.lastDistCm
            val rx = grid.robotX
            val rz = grid.robotZ
            val heading = grid.robotHeadingRad

            val ultrasonicClose = distCm in 1..OBSTACLE_NEAR_CM
            val gridBlocked = !grid.isPathClear(rx, rz, heading, CHECK_DIST, HALF_WIDTH)

            if (!ultrasonicClose && !gridBlocked) {
                // Path clear — drive forward briefly
                turnSteps = 0
                driveForwardBurst()
                if (stopped) return
            } else {
                // Blocked — pick direction and step-rotate to find clear path
                if (turnSteps == 0) {
                    val leftClear = grid.isPathClear(rx, rz, heading + HALF_PI, CHECK_DIST, HALF_WIDTH)
                    val rightClear = grid.isPathClear(rx, rz, heading - HALF_PI, CHECK_DIST, HALF_WIDTH)
                    turnGoLeft = when {
                        leftClear && !rightClear -> true
                        rightClear && !leftClear -> false
                        else -> !turnGoLeft
                    }
                    Log.d(TAG, "Blocked (us=${distCm}cm, grid=$gridBlocked), turning ${if (turnGoLeft) "left" else "right"}")
                }

                turnSteps++
                if (turnSteps > MAX_TURN_STEPS) {
                    // Stuck — reverse to escape corner, then flip turn direction
                    Log.w(TAG, "Stuck after $MAX_TURN_STEPS turn steps, reversing to escape")
                    reverseBurst()
                    if (stopped) return
                    turnGoLeft = !turnGoLeft
                    turnSteps = 0
                    continue
                }

                // Step-rotate
                sendTurn(turnGoLeft)
                delay(TURN_STEP_MS)
                sendStop()
                delay(SETTLE_MS)
            }
        }
    }

    /** Reverse for REVERSE_BURST_MS to escape a corner. */
    private suspend fun reverseBurst() {
        val deadline = System.currentTimeMillis() + REVERSE_BURST_MS
        while (!stopped && System.currentTimeMillis() < deadline) {
            sendReverse()
            delay(CMD_INTERVAL_MS)
        }
        sendStop()
        delay(SETTLE_MS)
    }

    /** Drive forward for DRIVE_BURST_MS, checking ultrasonic each tick. */
    private suspend fun driveForwardBurst() {
        val deadline = System.currentTimeMillis() + DRIVE_BURST_MS
        while (!stopped && System.currentTimeMillis() < deadline) {
            val distCm = gridMapper.lastDistCm
            if (distCm in 1..OBSTACLE_NEAR_CM) {
                break // obstacle — stop early
            }
            sendForward()
            delay(CMD_INTERVAL_MS)
        }
        sendStop()
        delay(SETTLE_MS)
    }

    // ---- Motor commands ----

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
        private const val TAG = "WanderController"
        private const val SPEED = 80             // ~1/3 max, slow crawl
        private const val TURN_SPEED = 80        // in-place turn speed
        private const val SCAN_SPEED = 70        // scan rotation speed
        private const val OBSTACLE_NEAR_CM = 20  // ultrasonic safety threshold (cm)
        private const val CHECK_DIST = 0.25f     // grid look-ahead (meters)
        private const val HALF_WIDTH = 0.09f     // half robot width (~17cm car)
        private const val CMD_INTERVAL_MS = 100L // motor command tick during driving
        private const val SETTLE_MS = 300L       // pause after stopping for stable reading
        private const val DRIVE_BURST_MS = 250L  // drive forward duration
        private const val REVERSE_BURST_MS = 400L // reverse to escape corner
        private const val SCAN_STEP_MS = 200L    // rotation burst during scan
        private const val TURN_STEP_MS = 200L    // rotation burst during obstacle avoidance
        private const val MAX_TURN_STEPS = 15    // ~15 steps ≈ 360°, then escape
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val FULL_ROTATION = (2 * Math.PI).toFloat()
        private const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
