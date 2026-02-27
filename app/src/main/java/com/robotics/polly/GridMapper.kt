package com.robotics.polly

import android.util.Log
import com.google.ar.core.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fuses ARCore 6-DOF pose and forward-facing ultrasonic distance
 * into a 2D OccupancyGrid.
 *
 * Includes scan-to-scan matching for drift correction: periodic 360° scans
 * are compared to the reference scan, and the position offset is applied
 * to correct accumulated ARCore drift.
 *
 * Coordinate convention:
 * - ARCore is OpenGL: X=right, Y=up, Z=towards user
 * - Camera/robot faces -Z direction in local frame
 * - 2D grid uses the (X, Z) horizontal plane
 */
class GridMapper {

    val grid = OccupancyGrid(cellSize = 0.10f)

    /** Listeners notified on each grid update (for UI refresh). */
    val onGridUpdated = CopyOnWriteArrayList<() -> Unit>()

    // Source-agnostic pose state (set by onPose from ARCore or relay)
    @Volatile private var poseTx = 0f
    @Volatile private var poseTy = 0f
    @Volatile private var poseTz = 0f
    @Volatile private var poseQx = 0f
    @Volatile private var poseQy = 0f
    @Volatile private var poseQz = 0f
    @Volatile private var poseQw = 1f
    @Volatile private var hasPose = false

    var updateCount = 0
        private set
    var rejectedCount = 0
        private set

    /** Latest raw ultrasonic reading in cm (-1 if none yet). */
    @Volatile var lastDistCm: Int = -1

    // Pose filter: reject jumps > MAX_SPEED * time-between-updates
    private var lastAcceptedX = Float.NaN
    private var lastAcceptedZ = Float.NaN
    private var lastAcceptedTimeNs = 0L
    private var consecutiveRejects = 0

    // Raw reading log for diagnostics
    val rawLog = mutableListOf<FloatArray>()  // [x, z, heading, distCm, accepted(0/1)]

    // --- Drift correction ---
    private var driftX = 0f
    private var driftZ = 0f
    var correctionCount = 0
        private set

    // --- Scan recording ---
    data class ScanReading(val heading: Float, val distM: Float, val hitX: Float, val hitZ: Float)
    data class ScanProfile(val robotX: Float, val robotZ: Float, val readings: List<ScanReading>)

    private var scanRecording = false
    private val scanBuffer = mutableListOf<ScanReading>()
    private var referenceScan: ScanProfile? = null
    val scanProfiles = mutableListOf<ScanProfile>()

    @Volatile private var latestPoseTimestampNs = 0L

    /** Called by ARCoreBridge on each tracked frame (~30fps). */
    fun onPose(pose: Pose, timestampNs: Long = 0L) {
        onPose(pose.tx(), pose.ty(), pose.tz(), pose.qx(), pose.qy(), pose.qz(), pose.qw(), timestampNs)
    }

    /** Called with raw pose from relay (OKVIS2 via PC) or any other source. */
    fun onPose(tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float, timestampNs: Long = 0L) {
        poseTx = tx; poseTy = ty; poseTz = tz
        poseQx = qx; poseQy = qy; poseQz = qz; poseQw = qw
        hasPose = true
        latestPoseTimestampNs = timestampNs

        // Update grid robot position so FrontierController can track heading
        // even before ultrasonic data triggers grid.update()
        grid.setRobotPose(tx - driftX, tz - driftZ, extractHeading(qx, qy, qz, qw))
    }

    /**
     * Parse remapped Arduino JSON and extract ultrasonic distance.
     * ArduinoBridge remaps "d" -> "dist_f" (distance in cm).
     */
    fun onArduinoData(line: String) {
        try {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) return
            val json = JSONObject(trimmed)
            if (json.has("dist_f")) {
                onUltrasonic(json.getInt("dist_f"))
            }
        } catch (_: Exception) {}
    }

    /** Process a single ultrasonic reading using the latest pose. */
    fun onUltrasonic(distCm: Int) {
        lastDistCm = distCm
        if (!hasPose) return
        if (distCm < 0) return

        // Apply drift correction to pose position
        val x = poseTx - driftX
        val z = poseTz - driftZ
        val heading = extractHeading(poseQx, poseQy, poseQz, poseQw)
        val now = System.nanoTime()

        // Velocity filter: reject if robot would need to move > 1 m/s
        val accepted = if (lastAcceptedX.isNaN()) {
            true
        } else {
            val dx = x - lastAcceptedX
            val dz = z - lastAcceptedZ
            val dist = sqrt(dx * dx + dz * dz)
            val dtSec = (now - lastAcceptedTimeNs) / 1_000_000_000.0f
            val speed = if (dtSec > 0.001f) dist / dtSec else Float.MAX_VALUE
            speed < MAX_SPEED
        }

        // Log raw reading for diagnostics (6 columns: x, z, heading, distCm, accepted, poseTimestampNs)
        if (rawLog.size < MAX_RAW_LOG) {
            rawLog.add(floatArrayOf(x, z, heading, distCm.toFloat(), if (accepted) 1f else 0f, latestPoseTimestampNs.toFloat()))
        }

        if (!accepted) {
            rejectedCount++
            consecutiveRejects++
            if (rejectedCount % 50 == 1) {
                Log.w(TAG, "Pose rejected ($rejectedCount total) — jump from " +
                    "(%.2f,%.2f) to (%.2f,%.2f)".format(lastAcceptedX, lastAcceptedZ, x, z))
            }
            // After 5 consecutive rejections, reset baseline to avoid getting stuck
            // (e.g. OKVIS2 re-initializes at a new position after tracking loss)
            if (consecutiveRejects >= 5) {
                Log.w(TAG, "Resetting velocity filter after $consecutiveRejects consecutive rejects")
                lastAcceptedX = x
                lastAcceptedZ = z
                lastAcceptedTimeNs = now
                consecutiveRejects = 0
            }
            return
        }

        consecutiveRejects = 0
        lastAcceptedX = x
        lastAcceptedZ = z
        lastAcceptedTimeNs = now

        val distM = distCm / 100.0f
        grid.update(x, z, heading, distM)
        updateCount++

        // Record scan reading if scanning
        if (scanRecording && distM in 0.10f..0.80f) {
            val hitX = x + distM * cos(heading)
            val hitZ = z + distM * sin(heading)
            scanBuffer.add(ScanReading(heading, distM, hitX, hitZ))
        }

        for (listener in onGridUpdated) {
            try {
                listener()
            } catch (e: Exception) {
                Log.e(TAG, "Grid listener error: ${e.message}")
            }
        }
    }

    // --- Scan recording API ---

    fun startScanRecording() {
        scanBuffer.clear()
        scanRecording = true
    }

    fun stopScanRecording() {
        scanRecording = false
        if (scanBuffer.size < MIN_SCAN_READINGS) {
            Log.w(TAG, "Scan too short (${scanBuffer.size} readings), discarding")
            return
        }
        val profile = ScanProfile(grid.robotX, grid.robotZ, scanBuffer.toList())
        scanProfiles.add(profile)
        if (referenceScan == null) {
            referenceScan = profile
            Log.i(TAG, "Reference scan saved (${profile.readings.size} readings)")
        }
    }

    /**
     * Match the latest scan against the reference scan and apply drift correction.
     * Returns the correction applied, or null if matching failed.
     */
    fun matchAndCorrect(): Pair<Float, Float>? {
        val ref = referenceScan ?: return null
        val current = scanProfiles.lastOrNull() ?: return null
        if (current === ref) return null // don't match reference against itself

        // For each reading in the current scan, find the closest-heading reading in reference
        val dxList = mutableListOf<Float>()
        val dzList = mutableListOf<Float>()

        for (cr in current.readings) {
            var bestRef: ScanReading? = null
            var bestDelta = Float.MAX_VALUE
            for (rr in ref.readings) {
                val delta = angleDiff(cr.heading, rr.heading)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestRef = rr
                }
            }
            if (bestRef != null && bestDelta < MAX_HEADING_MATCH) {
                dxList.add(cr.hitX - bestRef.hitX)
                dzList.add(cr.hitZ - bestRef.hitZ)
            }
        }

        if (dxList.size < MIN_MATCHES) {
            Log.w(TAG, "Scan match failed: only ${dxList.size} matches (need $MIN_MATCHES)")
            return null
        }

        // Median offset (robust to outliers)
        dxList.sort()
        dzList.sort()
        val medDx = dxList[dxList.size / 2]
        val medDz = dzList[dzList.size / 2]

        // Check consistency: if the spread is too large, the match is unreliable
        val dxSpread = dxList.last() - dxList.first()
        val dzSpread = dzList.last() - dzList.first()
        if (dxSpread > MAX_CORRECTION_SPREAD || dzSpread > MAX_CORRECTION_SPREAD) {
            Log.w(TAG, "Scan match unreliable: spread dx=%.3f dz=%.3f".format(dxSpread, dzSpread))
            return null
        }

        val corrMag = sqrt(medDx * medDx + medDz * medDz)
        if (corrMag < 0.01f) {
            Log.d(TAG, "Scan match: negligible drift (%.3fm)".format(corrMag))
            return null
        }

        // Apply correction
        driftX += medDx
        driftZ += medDz
        correctionCount++
        Log.i(TAG, "Drift correction #$correctionCount: dx=%.3f dz=%.3f (total: %.3f, %.3f) from %d matches"
            .format(medDx, medDz, driftX, driftZ, dxList.size))

        return Pair(medDx, medDz)
    }

    fun clear() {
        grid.clear()
        hasPose = false
        poseTx = 0f; poseTy = 0f; poseTz = 0f
        poseQx = 0f; poseQy = 0f; poseQz = 0f; poseQw = 1f
        updateCount = 0
        rejectedCount = 0
        lastDistCm = -1
        lastAcceptedX = Float.NaN
        lastAcceptedZ = Float.NaN
        consecutiveRejects = 0
        latestPoseTimestampNs = 0L
        rawLog.clear()
        driftX = 0f
        driftZ = 0f
        correctionCount = 0
        referenceScan = null
        scanProfiles.clear()
        scanBuffer.clear()
        scanRecording = false
    }

    fun rawLogToJson(): JSONArray {
        val arr = JSONArray()
        for (entry in rawLog) {
            arr.put(JSONArray().apply {
                put(entry[0].toDouble()) // x
                put(entry[1].toDouble()) // z
                put(entry[2].toDouble()) // heading
                put(entry[3].toInt())    // distCm
                put(entry[4].toInt())    // accepted
                if (entry.size > 5) put(entry[5].toLong()) // poseTimestampNs
            })
        }
        return arr
    }

    companion object {
        private const val TAG = "GridMapper"
        private const val MAX_SPEED = 1.0f  // m/s — generous for ELEGOO car
        private const val MAX_RAW_LOG = 5000
        private const val MIN_SCAN_READINGS = 10
        private const val MIN_MATCHES = 8
        private const val MAX_HEADING_MATCH = 0.26f  // ~15° in radians
        private const val MAX_CORRECTION_SPREAD = 0.40f // reject if offsets vary > 40cm

        private fun angleDiff(a: Float, b: Float): Float {
            var d = abs(a - b)
            if (d > Math.PI) d = (2 * Math.PI).toFloat() - d
            return d
        }

        /**
         * Extract heading on the XZ plane from a quaternion.
         *
         * Camera faces local -Z. We compute where -Z maps in world coords
         * by extracting the third column of the rotation matrix (negated):
         *   fwdX = -(2*(qx*qz + qw*qy))
         *   fwdZ = 2*(qx² + qy²) - 1
         *
         * heading = atan2(fwdZ, fwdX) to match OccupancyGrid convention
         * where endX = x + dist*cos(heading), endZ = z + dist*sin(heading).
         */
        fun extractHeading(qx: Float, qy: Float, qz: Float, qw: Float): Float {
            val fwdX = -(2.0f * (qx * qz + qw * qy))
            val fwdZ = 2.0f * (qx * qx + qy * qy) - 1.0f
            return atan2(fwdZ, fwdX)
        }

        fun extractHeading(pose: Pose): Float =
            extractHeading(pose.qx(), pose.qy(), pose.qz(), pose.qw())
    }
}
